# Device report 2 — the existing port is not Wine, and that changes the plan

Follow-up to `DEVICE_REPORT_2026-11-13.md`, after installing 0.6.18 on the
TECNO CK6 and driving it directly over adb.

## What the latest launch actually did

The symlink fix, the drive mapping and the patch argument all work. The
recorded run:

```
reason=ERROR_CODE
termination=GUEST_ERROR
exitCode=53
durationMillis=3651
command.0=.../wine-11.3-arm64ec/bin/wine
command.1=D:\Vampire.exe
command.2=-game
command.3=Unofficial_Patch
```

The prefix is also fully built now: `drive_c` exists, `system32` holds 833
files, and `dosdevices/D:` correctly points at the game folder. So every bug
in the launcher's own launch path is fixed.

Wine still fails, for one reason:

```
err:virtual:map_image_into_view failed to set 60000020 protection on
    L"\\??\\C:\\windows\\system32\\ntdll.dll" section .text, noexec filesystem?
wine: failed to open "D:\\Vampire.exe"
```

## What it is not

Ruled out by measurement, not by assumption:

| Hypothesis | Test | Result |
| --- | --- | --- |
| File permissions | `chmod` the whole prefix to 644/755 | no change |
| SELinux denial | `logcat` for AVC on the app | no denials; the log has `avc: granted { execute }` |
| Mount is `noexec` | `/proc/self/mounts` | `/data` is `rw,lazytime,...` with no `noexec` |
| Wrong file location | symlink `ntdll.dll` → the APK's `nativeLibraryDir` | still fails; the target dir does not help |
| Missing DLL | copy the real `ntdll.dll` back in | still fails |
| Env workaround | `WINE_MMAP_EXEC`, `WINE_FORCE_EXEC`, `WINE_DISABLE_WX` | no change |

Wine's own source says what the message means: `map_image_into_view` calls
`set_vprot` per PE section, which calls `mprotect_range`, which ends in
`mprotect(PROT_EXEC)`. Wine's debug trace reports `errno_to_status errno = 9`
(EBADF) on the same run. Android 14 on this device refuses that mapping for a
file-backed image even when the file is executed successfully by `execve` —
which we proved separately by copying `toybox` into app data and running it.

So this is the platform's W^X at the `mmap`/`mprotect` level. `targetSdk 28`
does not bypass it on this build, and no supported Wine environment variable
turns it off.

## The finding that matters

**The port already on this device is not Wine.** `/sdcard/Download/
VTMB-apkvision-English/` contains:

```
Loader.exe              <- a separate native launcher
Bin/engine.dll
Bin/dxvk.conf
Bin/shaderapidx9.dll
app_process64_d3d9.log
overlay/
```

and that log is DXVK's:

```
warn: DxvkAdapter: requested core feature #11 is not supported...
err:  D3D9SwapChainEx: recreateSwapChain returned VK_ERROR_SURFACE_LOST_KHR
err:  Presenter: createSurface failed VK_ERROR_SURFACE_LOST_KHR
err:  SDL3 WSI: no native window (the app is in the background)
```

That is **DXVK over Vulkan with SDL3 for the window**, loading the game's own
`Vampire.exe` and its Source modules natively. There is no Wine prefix, no
WoW64 and no PE-loader `mmap` of system DLLs — which is exactly why it runs
where our Wine build cannot.

## What this means

The existing port is closer to the "native port" idea than to Winlator, and
that is the route that works on this GPU. Our launcher has now been proven to
handle everything except the one thing Wine cannot do on this device.

Two honest options from here:

1. **Fork the working runtime.** It is already on the device and already
   uses DXVK + SDL3, which is the Mali-friendly path. Specialise it for
   Bloodlines, add the touch HUD and the settings your launcher already has.
2. **Keep the Wine path** and accept that it needs a modified Wine build whose
   PE loader does not `mprotect(PROT_EXEC)` on the image — a source-level
   change in `dlls/ntdll/unix/virtual.c`, plus a custom build for arm64ec.
   That is real work with no guarantee on this kernel.

The launcher's own work is not wasted in either case: install detection, PE
validation, patch selection, settings, diagnostics and report export all carry
over.
