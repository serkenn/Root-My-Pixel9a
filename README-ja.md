# Root My Pixel — 日本語マニュアル

**Root My Pixel** は、Google Pixel 端末で **NebuSec IonStack**（CVE-2026-43499）を利用し、**ReSukiSU / KernelSU** と組み合わせて root 権限を取得する Android アプリです。

取得される root は **一時的**（temporary root）です。再起動すると失われます。ブートローダーのアンロックやパーティションの書き換えは行いません。

> **警告**
> これはカーネルの脆弱性を突くツールです。オフセットが 1 つでも誤っていると、失敗するのではなく **無関係なカーネルメモリを破壊します**。カーネルパニック、データ破損、最悪の場合は端末が起動しなくなる可能性があります。「未検証」と記載された機種で実行するのは、そのリスクを理解し、失っても構わないデータ状態にしてからにしてください。

---

## 1. 対応機種

| 機種 | コードネーム | 対応ビルド | カーネル KMI | 実機検証 |
|:---|:---|:---|:---|:---|
| Pixel 10 Pro | `blazer` | `CP2A.260705.006` | `android15-6.6` | ✅ 済 |
| Pixel 10 Pro XL | `mustang` | `CP2A.260705.006` | `android15-6.6` | ✅ 済 |
| Pixel 10 Pro Fold | `rango` | `CP2A.260705.006` | `android15-6.6` | ✅ 済 |
| **Pixel 9a** | **`tegu`** | **`CP2A.260705.006`** | **`android14-6.1`** | ⏳ **未検証** |

ビルド番号は**完全一致**が必要です。`設定 → デバイス情報 → ビルド番号` で確認してください。異なるビルドでは、たとえ同じ機種でもオフセットが一致せず危険です。

Pixel 9a 対応の詳細と制約は [§6](#6-pixel-9a-tegu-対応について) を参照してください。

---

## 2. 前提条件

1. 上表に載っている機種・ビルドであること
2. **Shizuku** がインストールされ、起動していること
   - ADB 経由: `adb shell sh /sdcard/Android/data/rikka.shizuku/starter.sh`
   - またはワイヤレスデバッグ経由
3. **ReSukiSU Manager** がインストールされていること（root 権限の付与管理に必要）

Shizuku は ADB shell 権限（UID 2000）をアプリに渡す役割を担います。これにより、root を取得する前の段階で `/data/local/tmp` にペイロードを配置・実行できます。

---

## 3. 使い方

1. Shizuku を起動する
2. Root My Pixel を開く
3. 起動時に端末の判定が走る
   - ネイティブ JNI（`NativeProbe`）、`/proc/version`、システムプロパティからコードネーム・カーネルバージョン・CPU ABI・ページサイズ・ビルド表示 ID を取得
   - `assets/profiles.json` のプロファイルと照合（コードネーム + ビルド番号 + カーネルリリース接頭辞の 3 点一致）
4. Shizuku の権限を許可する
5. 実行ボタンを押す
6. ログをリアルタイムで確認する

判定に失敗した場合は `No profile for <codename> / <kernel> / <build>` と表示されます。これは「その組み合わせのオフセットを持っていない」という意味で、無理に実行させる手段は用意されていません（意図的な設計です）。

### 内部で起きていること

1. **ペイロード展開** — APK の assets から、機種別の `.so` とネイティブヘルパー `libcve43499root.so` を `/data/local/tmp` に展開
2. **エクスプロイト実行** — IonStack（CVE-2026-43499）を実行し、root デーモンのソケット `temp_su.sock` を確立
3. **KernelSU 連携** — 端末の KMI に対応した `ksud` を配置し、late-load を実行（`ksud late-load --kmi <kmi>`）
4. **確認** — `/dev/kernelsu`、`/sys/kernel/kernelsu`、`/data/adb/ksu` を見て KernelSU が有効か判定

### 付属ツール

- **ソフト再起動** — `system_server` を再起動
- **ログのエクスポート / クリップボードへコピー** — 不具合報告用

---

## 4. ソースからのビルド

### 必要なもの

- Android NDK r25 以降（`ANDROID_NDK_HOME` を設定するか、`$ANDROID_HOME/ndk/` に配置）
- Java 17 以降
- Linux x86_64 または macOS（arm64 / x86_64）

### 手順

```bash
./build-all.sh
```

これで以下が順に行われます。

1. ネイティブヘルパー `libcve43499root.so` のビルド
2. `TARGETS` に列挙された全機種分のエクスプロイトペイロードのビルドと `app/src/main/assets/exploits/` への配置
3. デバッグ APK のビルド

成果物: `app/build/outputs/apk/debug/app-debug.apk`

インストール:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. 新しい機種・ビルドを追加する

必要なものは 2 種類あり、**取得元がまったく違います**。ここを混同すると危険です。

### (a) シンボルアドレス

カーネルの `kallsyms` から取れます。工場出荷イメージの `boot.img` からカーネル `Image` を取り出し、`vmlinux-to-elf` などで復元します。

```bash
vmlinux-to-elf /path/to/Image out.elf
```

### (b) 構造体のメンバーオフセット

**kallsyms からは絶対に取れません。** シンボルの「アドレス」しか分からないためです。取得手段は次のいずれかです。

1. **カーネルの BTF**（推奨） — Android GKI は `CONFIG_DEBUG_INFO_BTF=y` でビルドされているため、`Image` の中に BTF ブロブがそのまま含まれています。これは pahole がビルド済み vmlinux の DWARF から生成したもので、**その build の実際のレイアウト**を表します。構造体レイアウトのランダム化が有効な場合でも、BTF はランダム化後の姿を反映するため正確です。

   ```bash
   # BTF ブロブを探す（マジック 9f eb 01 00 18 00 00 00）
   # 切り出したら
   bpftool btf dump file carved.btf format raw
   ```

2. 同じ KMI の既存ターゲットから流用 — **同じ KMI である場合に限り**有効
3. 実機での確認

> **やってはいけないこと**
> KMI が違うターゲットから構造体オフセットをコピーすること。`task_struct` や `cred` はカーネルのマイナーバージョン間で頻繁に変わります。実例として Pixel 10 系（6.6）と Pixel 9a（6.1）では `cred->uid` が 8 と 4、`task_struct->cred` が 0x820 と 0x838、`slab->slab_cache` が 0x08 と 0x18 と、いずれも異なります。コピーしていれば偽造した資格情報構造体が隣接メモリを破壊していました。

### 追加する箇所

1. `Root-My-Pixel-Payloads/src/targets/<codename>-<build>/target.h` を作成
2. `build-all.sh` の `TARGETS` に追加
3. `app/src/main/assets/profiles.json` にプロファイルを追加
   - `kernelRelease` は `uname -r` の**安定部分の接頭辞**を書きます（Git リビジョンより前まで）。例: `6.1.157-android14-11-gbd23337e42e7-ab14791245` → `6.1.157-android14-11`
4. その KMI 用の `ksud` を `app/src/main/assets/ksud/ksud-<kmi>` に配置

---

## 6. Pixel 9a (tegu) 対応について

Pixel 9a は、このリポジトリで唯一 **android14-6.1** 系のターゲットです（他はすべて android15-6.6）。そのため構造体オフセットを既存機種から流用できず、すべて独自に取得しています。

### オフセットの出所

`.factory-images/tegu-cp2a.260705.006/Image` から、独立した 2 系統で取得しました。推測値はありません。

| 種別 | 取得元 | 備考 |
|:---|:---|:---|
| シンボルアドレス | kallsyms（`vmlinux-to-elf`、102271 シンボル） | すべて名前解決。パターンマッチや近似は不使用 |
| 構造体メンバーオフセット | カーネル同梱の BTF（5,653,158 バイト） | `CONFIG_DEBUG_INFO_BTF=y`。この build の実レイアウト |

イメージのベースアドレス `0xffffffc008000000` も推測ではありません。arm64 では `KIMAGE_VADDR = _PAGE_END(VA_BITS_MIN) + MODULES_VSIZE` で決まり、この build は `CONFIG_ARM64_VA_BITS=39`、6.1 のモジュール領域は 128MB なので、この値が導出されます。Pixel 10 系が `0xffffffc080000000` なのは、6.6 でモジュール領域が 2GB に拡大されたためで、同じ式から出ています。

### 6.6 との主な相違点

| 項目 | Pixel 10 系 (6.6) | Pixel 9a (6.1) | 理由 |
|:---|:---|:---|:---|
| `cred->uid` | 0x08 | 0x04 | 6.6 で `usage` が `atomic_t` → `atomic_long_t` に拡大 |
| `task_struct->cred` | 0x820 | 0x838 | `task_struct` のレイアウト変更 |
| `slab->slab_cache` | 0x08 | 0x18 | 6.6 で `slab_cache` が先頭側へ移動 |
| `file_operations->splice_read` | 0xb8 | 0xc8 | 6.6 で `->sendpage` が削除され後半がずれた |
| `rt_mutex_waiter` | `rt_waiter_node` で tree/pi_tree が各々 prio/deadline を持つ | フラット構造で prio/deadline は共有 | 6.6 での構造体分割 |
| splice ヘルパー | `copy_splice_read` | `generic_file_splice_read` | 6.1 に `copy_splice_read` は存在しない |
| SELinux enforcing | 単独のグローバル変数 | `struct selinux_state` の先頭メンバー | 6.1 では state 構造体に集約 |

`rt_mutex_waiter` のレイアウト差は、ヘッダの値だけでは吸収できなかったため `slide.c` を修正しています。偽装 waiter をバイト列として組み立ててからワード単位で書き出す方式に変え、ワード番号のハードコードを廃止しました。既存 3 機種については、生成されるワード列が変更前と**バイト単位で同一**であることを確認済みです。

### ksud について

アプリは `ksud/ksud-<kmi>` という名前で ksud を探すため、当初 `ksud-android14-6.1` が無く展開に失敗していました。

ただし **ksud バイナリ自体は KMI 固有ではありません**。同梱されているビルド（`4.1.0-1326-g88dbc786`）に対して実機で問い合わせたところ、対応 KMI として `android12-5.10` / `android13-5.10` / `android13-5.15` / `android14-5.15` / `android14-6.1` / `android15-6.6` / `android16-6.12` の 7 種すべてを列挙し、Pixel 9a 上での `ksud boot-info current-kmi` は `android14-6.1` を返します。KMI はアプリが渡す `late-load --kmi` 引数で実行時に選択されます。

したがってファイル名は単なる検索規則であり、同一バイナリを規定の名前で配置すれば足ります（md5 は両者とも `060aa8d8766299ea8e51be09fb71c3b5`）。第三者からのバイナリ入手は不要です。ReSukiSU は GitHub リリースを公開していません。

### 検証状況

| 項目 | 状態 |
|:---|:---|
| 全オフセットが実イメージ由来（推測なし） | ✅ |
| 4 機種すべてのターゲットヘッダが静的検証を通過 | ✅ |
| `slide.c` の変更が既存 3 機種で完全に等価 | ✅ |
| 実機 Pixel 9a での端末判定（プロファイル照合） | ✅ |
| 実機でのペイロード展開 | ✅ |
| 実機での KernelSnitch `mm_struct` リーク | ✅ |
| 実機での `pselect` waiter 破壊 / slide ルート | ✅ |
| 実機での KASLR ベース奪取（`slide-kaslr-ok`） | ✅ |
| **メイン FOPS ルート / root 取得** | ❌ **未達** |

KASLR の突破までは実機で確認済みですが、その先のメイン FOPS ルートで
`ashmem` の `f_op` 上書きが載らず、`try_cfi_stage()` が毎回 step 4 で抜けます。
root は取れていません。

ここまで到達するのに、導出ではなく**実機計測**が必要だった tegu 固有の値が 2 つあり、
いずれも `target.h` に根拠付きで記録しています。

1. `mm_struct` の SLUB オブジェクトサイズ。`sizeof(struct mm_struct)` は BTF で 960 ですが、
   `proc_caches_init()` が `+ cpumask_size()` してから `SLAB_HWCACHE_ALIGN` するため実際は
   **1024** です。このビルドは `/proc/slabinfo` が誰でも読めるので実測できます。
2. リクレイム前に対象スラブを per-CPU partial リストから unfreeze させる解放順序。
   `CONFIG_SLUB_CPU_PARTIAL` が有効なため、frozen のまま空になったスラブは
   ページアロケータに戻らず、次の `mm_struct` として再利用されてしまいます。

---

## 7. トラブルシューティング

| 症状 | 原因と対処 |
|:---|:---|
| `No profile for ...` | 機種かビルド番号が対応表と一致していません。ビルド番号は完全一致が必要です |
| Shizuku 関連のエラー | Shizuku が起動していないか、権限が未許可です。再起動のたびに Shizuku の起動が必要です |
| `Failed to extract ksud` | その KMI 用の `ksud` が同梱されていません。バイナリは KMI 共通なので、既存の `ksud-<別kmi>` を `ksud-<必要なkmi>` としてコピーすれば足ります（§6 参照） |
| KernelSU が有効にならない | ReSukiSU Manager が未インストール、または ksud の KMI が不一致です |
| root が消えた | 仕様です。一時 root なので再起動で失われます |

---

## 8. クレジット

- エクスプロイト: [NebuSec IonStack](https://github.com/NebuSec/CyberMeowfia)
- アプリ構成: [Root My Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) を参考に構築
- [ReSukiSU](https://github.com/ReSukiSU/ReSukiSU)
