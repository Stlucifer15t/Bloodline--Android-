# Device report, 2026-11-13 — why the launch dies

The first real device run produced an answer, and it is **not** the display.
The runtime is being stopped one layer earlier, by the filesystem.

Device: TECNO CK6 (`mt6768`), Android 14 / API 34, arm64-v8a, Mali-G52 MC2,
OpenGL ES 3.2, 7.7 GB RAM. App 0.6.14-dev (29), targetSdk 28.

## What the report showed

`runtime.txt` from the device:

```
Wine — Windows API layer    : Ready
WoW64 — 32-bit x86 on ARM64 : Ready
Graphics translation        : Partial
32-bit execution            : no
Process limit               : unknown
GPU nodes                   : none found
Last phase                  : idle
Exit reading                : LAUNCH_FAILED
Command                     : .../wine-11.3-arm64ec/bin/wine D:\Vampire.exe
```

The log had three crashes, all identical, all in `ntdll.so`:

```
Cmdline: .../lib/wine/aarch64-unix/wine .../lib/wine/i386-windows/cmd.exe /c exit 3
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
#00 pc 000000000003f020  lib/wine/aarch64-unix/ntdll.so
Cause: null pointer dereference
```

## The cause

Running the same command by hand on the device gave the reason in one line:

```
002c:err:virtual:map_image_into_view failed to set 60000020 protection on
      L"\\??\\C:\\windows\\system32\\ntdll.dll" section .text, noexec filesystem?
002c:err:virtual:virtual_setup_exception stack overflow
```

**Wine cannot map its own DLLs executable from the app's data directory.**
Wine's loader does `mmap(PROT_READ|PROT_WRITE|PROT_EXEC)` over each PE section.
On this device that mapping is refused, Wine marks the image non-executable,
and the first attempt to run code in it segfaults at `pc` inside `ntdll.so`.

`wineboot` then fails as a knock-on effect: it never finishes creating
`drive_c`, so the prefix ends up with only `dosdevices`, `drive_d` and the
three registry files. That is why the next launch sees no `system32`.

Measured facts that narrow it down:

| Test | Result | Meaning |
| --- | --- | --- |
| `wine --version` | `wine-11.3`, exit 0 | The loader binary runs on this device |
| `cp /system/bin/toybox . && ./toybox echo` | `BINARY_EXEC_OK` | `execve` from app data works |
| `wine wineboot -u` | exit 1, no `drive_c` | Prefix creation fails |
| `wine cmd.exe /c exit 3` | `map_image_into_view ... noexec` | `mmap(PROT_EXEC)` is refused |
| `/proc/self/mounts` | `/data` is **not** mounted `noexec` | The refusal is not the mount flag |
| `getenforce` | `Enforcing` | SELinux is on |
| AVC denials for the app | only `shell_test_data_file` reads | no `execmod` denial logged |

So this is the Android W^X restriction enforced on `mmap` rather than on
`execve`: a file in the writable app data directory may be executed, but may
not be mapped writable-and-executable for a private image. `targetSdk 28` is
not enough to bypass the `mmap` half on this build.

That also explains the earlier `runtime_probe` results on the same device
(`32-bit execution: no`, `Execute from memory: no`): both were already saying
this.

## What this means for the plan

The display server was going to be the next milestone. It is not the current
blocker, and building one now would produce a renderer that never receives a
frame.

The runtime needs its **prefix and Wine DLLs placed where the loader may map
them executable**. The two routes that exist:

1. **The APK's native library directory.** `nativeLibraryDir` is read-only and
   executable, which is the whole reason Android unpacks `lib*.so` there. Wine
   cannot write its prefix there, but it can be given the *read-only* half
   (the Wine build's `lib/wine/*-windows` DLLs, `lib/wine/*-unix/*.so`) via
   `WINEDLLPATH` and `LD_LIBRARY_PATH`, while the writable prefix stays in app
   data. This is the cheapest experiment and the one to try first.
2. **A memfd-based loader.** Copy each DLL into an anonymous `memfd`, map it
   executable from there, and hand Wine the fd. The probe already reports
   `memfd` execution as unavailable on this device, so this is not a small
   change and may not work here at all.

A third option — running the whole thing inside a PRoot container with a
separate filesystem — does not help: `ptrace` is reported unavailable.

## Next step

Ship the Wine build's Windows DLLs inside the APK as `lib*.so`, so Android
unpacks them into `nativeLibraryDir`, and point Wine at that directory. That
turns "wineboot creates nothing" into a measurable next question instead of a
guess.
