# Root My Pixel

**Root My Pixel** is an Android application designed to automate root access on **Google Pixel** devices leveraging the **NebuSec IonStack** exploit (CVE-2026-43499) and integrating **ReSukiSU / KernelSU**.

日本語のマニュアルは [README-ja.md](README-ja.md) を参照してください。

---

## How the Application Works

Root My Pixel lets you *temporarily* gain root access with ReSukiSU in just one tap.

### Installation Workflow

1. **Device Detection & Profiling**
   - At startup, the app uses native JNI (`NativeProbe`), `/proc/version` queries, and system properties to detect the device codename, kernel version, CPU ABI, memory page size, and build display ID.
   - Via `ResolveTargetUseCase`, it matches the device details against supported target profiles defined in `assets/profiles.json`.

2. **Shizuku Integration**
   - The app uses **Shizuku** (UID 2000) to acquire ADB shell privileges without needing initial root access, which is required to stage and execute payload binaries in `/data/local/tmp`.
   - A managed `ExploitService` is bound via Binder IPC to stream exploit execution logs to the UI in real time.

3. **Exploit Payload Extraction & Execution**
   - Precompiled binary payloads (`.so`) corresponding to each supported build and the native helper tool (`libcve43499root.so`) are extracted from APK assets to `/data/local/tmp`.
   - The IonStack exploit (CVE-2026-43499) is executed to establish a local root daemon socket (`temp_su.sock`), acquiring full `root` privileges.

4. **KernelSU / ReSukiSU Integration**
   - Staging of the `ksud` binary matching the device's Kernel Module Interface (KMI, e.g., `android15-6.6`).
   - The app triggers the KernelSU **late-load** mechanism (`ksud late-load --kmi <kmi>`).
   - Verifies KernelSU active status by probing kernel device nodes (`/dev/kernelsu`, `/sys/kernel/kernelsu`, `/data/adb/ksu`).

5. **User Interface & Management Tools**
   - Real-time live log progress monitoring.
   - Handy actions for **Soft Reboot** (restarting `system_server`) and **Log Exporting** for debugging purposes.

---

## Supported Devices & Build Profiles

| Device                | Codename   | Supported Build   | Kernel KMI      | Tested |
|:----------------------|:-----------|:------------------|:----------------|:-------|
| **Pixel 10**          | `frankel`  | `CP2A.260705.006` | `android15-6.6` | ⏳      |
| **Pixel 10 Pro**      | `blazer`   | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro XL**   | `mustang`  | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro Fold** | `rango`    | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10a**         | `stallion` | `CP2A.260705.006` | `android15-6.6` | ⏳      |
| **Pixel 9a**          | `tegu`     | `CP2A.260705.006` | `android14-6.1` | ⏳      |

`tegu` is the only `android14-6.1` target; the rest are `android15-6.6`. Struct
layouts are not shared across those kernel lines, so none of its offsets are
inherited — symbol addresses come from the factory image's `kallsyms`, struct
member offsets from the kernel's own BTF blob.

On a physical Pixel 9a the exploit now gets as far as defeating KASLR: the
KernelSnitch `mm_struct` leak, the page reclaim, the `pselect` waiter
corruption and the slide route all succeed, and the run reports
`slide-kaslr-ok` with a recovered kernel base. The main FOPS route that
follows still fails — the `ashmem` `f_op` overwrite does not land, so every
`try_cfi_stage()` attempt exits at step 4 and no root is obtained. Two
tegu-only constants had to be measured on the device rather than derived to
get this far, both recorded in `target.h`: the `mm_struct` SLUB object size
(`/proc/slabinfo` is world-readable on this build) and the ordering that
unfreezes the target slab off the per-CPU partial list before the reclaim
sends.

---

## Prerequisites

1. A supported Google Pixel device listed in the table above.
2. **Shizuku** installed and running via ADB (`adb shell sh /sdcard/Android/data/rikka.shizuku/starter.sh` or Wireless Debugging).
3. **ReSukiSU Manager** installed on the device to manage root permissions granted to apps.

---

## Building from Source

To compile the entire project (native helper, exploit payloads for all targets, and the final debug APK):

### Build Requirements
- Android NDK r25+ (`ANDROID_NDK_HOME` set or present in Android SDK)
- macOS (arm64/x86_64) or Linux (x86_64) host
- Java 17+ and Gradle Wrapper

### Build Command
```bash
./build-all.sh
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

To install it on a connected device via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Credits

- Exploit: [NebuSec IonStack](https://github.com/NebuSec/CyberMeowfia)
- App architecture: Inspired and adapted from [Root My Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)
- ReSukiSU (https://github.com/ReSukiSU/ReSukiSU)
