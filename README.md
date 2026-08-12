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
| **Pixel 8 Pro**       | `husky`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 7**           | `panther`  | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 7a**          | `lynx`     | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 9a**          | `tegu`     | `CP2A.260705.006` | `android14-6.1` | ⏳      |

The Pixel 10 family is `android15-6.6` and builds from `src/`; every other
supported device is `android14-6.1` and builds from `src/61/`. Struct layouts
are not shared across those kernel lines, so nothing is inherited between
them — the Makefile keeps the two source sets apart on purpose.

`tegu` is the one target here that has not been run to completion. On a
physical Pixel 9a the KernelSnitch `mm_struct` leak, the page reclaim, the
`pselect` waiter corruption and the slide route all succeed and the run
reports `slide-kaslr-ok` with a recovered kernel base; the main route past
that point is still unverified on this device. Two things had to be measured
on the device rather than derived, and both apply to every `android14-6.1`
target, not just this one:

- `MM_STRUCT_SZ` is the SLUB object size, `0x400`, not the `0x3c0` that BTF
  reports for `sizeof(struct mm_struct)` — `proc_caches_init()` adds
  `cpumask_size()` before `SLAB_HWCACHE_ALIGN` rounds up. `/proc/slabinfo` is
  world-readable on these builds, so read it off the device.
- `prepare_kernel_page()` has to unfreeze the target slab off the per-CPU
  partial list before the reclaim sends. `CONFIG_SLUB_CPU_PARTIAL` is set, and
  a slab that empties while still frozen never reaches the page allocator, so
  the reclaim cannot win the page at all.

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
