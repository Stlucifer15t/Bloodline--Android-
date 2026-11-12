# Milestone 2 — runtime architecture assessment

Written before any runtime code, and kept as the record of *why* the runtime is
shaped the way it is. Every claim about third-party software is cited; every
claim about this repository is a fact about the code as it stands.

Target device throughout: **TECNO CK6, MediaTek Helio G85, Mali-G52 MC2,
Android 14 (API 34), 8 GB RAM, arm64-v8a.**

---

## 1. What exists in this repository today

| Piece | State | Evidence |
| --- | --- | --- |
| `runtime/RuntimeRegistry` | A catalogue of seven components with an explicit `IntegrationStatus` | Six of seven are hard-coded `NOT_INTEGRATED`; only storage is `PARTIAL` |
| `runtime/LaunchReadiness` | The single gate for the launch button | Requires **all** components `READY`, so it can only return `Blocked(RUNTIME_NOT_INTEGRATED)` today |
| `cpp/native_probe.c` | Proves a bundled executable can run at all (W^X-safe `lib*.so` trick) | ✅ Verified on device by earlier milestones |
| `cpp/runtime_probe.c` | Measures ptrace, seccomp, memfd-exec, 32-bit personality, ABI list, cores, memory | ✅ Verified on device |
| `diagnostics/GpuInfo` | Reads GL vendor/renderer/version and EGL version from an EGL context | ✅ Works |
| `game/GameInstallScanner` | Finds `Vampire.exe` and checks that the `Vampire/` folder sits beside it | ✅ Works — but stops at the filename |
| `storage/*` | Reads the game in place through the Storage Access Framework | ✅ Works |

**There is no Wine, no Box86/Box64, no glibc userland, no container, no graphics
translation and no audio bridge anywhere in this repository.** Nothing in the
APK can start `Vampire.exe` today, and this document does not pretend otherwise.

### Gaps that must close before a launch is possible

1. **No CPU translation.** Nothing turns x86 instructions into ARM64.
2. **No Windows API layer.** Wine is not present in any form.
3. **No glibc userland.** Wine is a glibc program; Android is bionic.
4. **No container.** `chroot` needs `CAP_SYS_ADMIN`, which an app never gets.
5. **No graphics translation.** Direct3D 9 calls have nothing to land on.
6. **No audio bridge.** DirectSound has nowhere to go.
7. **Install validation is name-deep.** The scanner confirms a file called
   `Vampire.exe` exists; it does not check that the file *is* a Windows binary.

---

## 2. What the game actually is

| Property | Value | Why it matters |
| --- | --- | --- |
| Executable | `Vampire.exe`, **32-bit x86** [1](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines) | Box64 on its own is not sufficient; a 32-bit execution path is mandatory (§4) |
| Graphics API | **Direct3D 9** [1](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines) | D3D9 is exactly the era WineD3D handles well, and the era VirGL survives |
| Engine | Source 2004 [1](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines) | Mature, well-understood, heavily exercised under Wine on desktop |
| Minimum RAM | 384 MB [1](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines) | Not a memory problem on an 8 GB device |
| Minimum VRAM | 64 MB [1](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines) | Not a VRAM problem; a *driver* problem |
| Install size | ~3.3–4.7 GB [1](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines) | Must never be copied into the APK or into app storage |
| Audio | DirectSound3D [2](https://www.mobygames.com/game/15663/vampire-the-masquerade-bloodlines/specs/) | Needs a DirectSound → host-audio bridge |

**The single most useful fact in this table is Direct3D 9.** It puts Bloodlines
in the one class of games that is genuinely practical on Mali today — the class
that VirGL + WineD3D targets. Everything about the graphics plan follows from it.

---

## 3. The Android constraints that decide the design

These are not preferences. They are properties of unrooted Android that no
amount of engineering inside an app can remove.

| Constraint | Consequence | Replacement used in practice |
| --- | --- | --- |
| `chroot` needs `CAP_SYS_ADMIN` [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) | No real container | **PRoot** — ptrace-based path rewriting [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) |
| Bionic has **no System V shared memory** (`shmget`/`shmat`/`shmdt`/`shmctl`) [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) | Wine, X11 and DXVK all use SysV SHM and will fail without it | **Patched glibc** implementing SysV SHM over ashmem/memfd, via a UNIX-socket client/server [4](https://deepwiki.com/brunodev85/winlator/7-system-integration) |
| A glibc process **cannot `dlopen` a bionic `.so`** [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) | The GPU driver cannot be loaded directly from inside the userland | **IPC to a native renderer**, or thunks [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) |
| Android 10+ **W^X**: no execution from the writable data directory | Binaries cannot simply be unpacked and run | Ship them as `lib*.so` in the APK — already proven here by `native_probe` |
| **Android 12+ phantom process killer** caps an app's child processes at 32 [5](https://linuxvox.com/blog/linux-on-android-tablet/) | Wine spawns many processes (`wineserver`, `winedevice`, prefixes, services) and Android 14 will reap them | Process count must be budgeted, and the manager must detect a child that vanished without an exit status |
| 32-bit support is disappearing from kernels and ABIs | Box86 is itself a 32-bit ARM program | Already probed for on this device by `runtime_probe` (`abilist32`, `personality32`, `linker32`) |

---

## 4. CPU translation: three candidates

| | **Box86** | **Box64 (WOW64)** | **FEX-Emu** |
| --- | --- | --- | --- |
| Runs 32-bit x86 | ✅ Its entire purpose | ✅ Since the WOW64 build option: Box64 can host 32-bit WOW64 DLLs [6](https://github.com/ptitSeb/box64/issues/3648) | ✅ Supports both 32- and 64-bit [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) |
| Needs a 32-bit subsystem | **Yes** — "you NEED a 32-bit subsystem to run and build Box86" [7](https://github.com/cheadrian/termux-chroot-proot-wine-box86_64/blob/main/Setup_Wine_Box86_64.md) | No — it is a 64-bit program | No [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) |
| Needs a separate x86 rootfs | Yes | Yes | No, in ARM64EC mode it plugs into Wine as a CPU module and Wine services the syscalls [3](https://aospbooks.github.io/aosp-internal-book/66-windows-games/) |
| Maturity on Android | The established path; bundled by Winlator [8](https://tech-insider.org/winlator-vs-box64-vs-wine-2026/) | Newer 32-bit path | Better raw compatibility with "pure" x86 code; reported as more efficient on low-power devices [9](https://emulation.gametechwiki.com/index.php/Compatibility_layers) |
| Current version | — | Box64 v0.4.4 (Aug 2026) [8](https://tech-insider.org/winlator-vs-box64-vs-wine-2026/) | Actively developed |

**Position for this project:** Box86 + a 32-bit Wine prefix is the conservative
path and the one the existing `RuntimeRegistry` already describes, so it stays the
recorded default. But the translator must be a **replaceable module**, because
Box64-WOW64 and FEX-ARM64EC are both credible and both remove the 32-bit
subsystem dependency — and the 32-bit dependency is the fragile part of the
plan, since 32-bit support is exactly what `runtime_probe` measures and what
kernels are dropping.

---

## 5. Graphics: three routes compared for Mali-G52

Mali-G52 is **Bifrost, architecture v7, Job Manager frontend**. That
classification decides everything below [10](https://github.com/zenithblue-oss/panvk-kbase-android/blob/main/docs/MALI-GPU-ARCHITECTURES.md).

### Option A — OpenGL ES (WineD3D over VirGL)

| | |
| --- | --- |
| **Components** | Mesa `virglrenderer` + Mesa Panfrost/llvmpipe GLES + WineD3D (D3D9 → OpenGL) |
| **GPU compatibility** | **Panfrost GLES 3.1 is Khronos-conformant on Mali-G52** [11](https://docs.mesa3d.org/drivers/panfrost.html?highlight=panfrost) — the strongest compatibility statement available for this GPU |
| **Android compatibility** | Good. This is the configuration purpose-built for Mali: "Winlator Mali" replaces Turnip/DXVK with **VirGL + WineD3D** precisely because Turnip/DXVK "do not work properly on many Mali devices" [12](https://winlator.dev/winlator-mali/) |
| **Performance** | Modest but real. VirGL improvements took a DX9 title from 4 FPS to ~20–25 FPS on a Mali-G57 [13](https://winlator.dev/best-gpu-drivers/). Expect single-digit to low-tens FPS on a G52; **no figure is promised without measuring** |
| **Limitations** | VirGL is aimed at DX9-era titles; DX10/11 remain problematic [12](https://winlator.dev/winlator-mali/). Bloodlines is DX9, so this limitation does not bite |
| **Integration difficulty** | Lowest of the three: no external driver package, nothing device-specific |

### Option B — Vulkan (DXVK over PanVK)

| | |
| --- | --- |
| **Components** | A Vulkan driver for Mali-G52 + DXVK (D3D9/10/11 → Vulkan) |
| **GPU compatibility** | **Poor upstream, improving in the community.** PanVK is conformant **only on Mali-G610 (v10)**; on v7 it reports Vulkan 1.3 and is explicitly **experimental and non-conformant** — the driver prints `panvk is not a conformant Vulkan implementation, testing use only` and `panvk is not well-tested on v7` [10](https://github.com/zenithblue-oss/panvk-kbase-android/blob/main/docs/MALI-GPU-ARCHITECTURES.md) [11](https://docs.mesa3d.org/drivers/panfrost.html?highlight=panfrost) [14](https://github.com/Rydev-pc/panvk-mali-g52-testes) |
| **Community state** | Real progress: PanVK has been brought up on Mali-G52 MC2 over the **proprietary `mali_kbase`** device (`/dev/mali0`), with no root and no custom kernel. Validated: device creation, compute dispatch, offscreen rendering with correct readback, X11 WSI, AHardwareBuffer import, and **vkcube presenting on-screen through a Wine/Winlator stack**; basic **DXVK D3D9/D3D10/D3D11** rendering validated on a nearly identical device (Redmi 13C, MT6768, Mali-G52 MC2, Android 14) [15](https://github.com/LukeValen/panvk-mali-g52). Independently reproduced on a Galaxy A06: `PanVK-G52 MC2`, apiVersion 1.4.363, 157 extensions, `vkCreateDevice` succeeds [14](https://github.com/Rydev-pc/panvk-mali-g52-testes) |
| **Android compatibility** | Depends on a **third-party driver package** that this project would have to accept from outside — which this milestone explicitly does not implement (§10 of the brief) |
| **Performance** | Unknown on this device. The kbase path "prioritizes correctness and debuggability over maximum performance" [15](https://github.com/LukeValen/panvk-mali-g52). Potentially higher ceiling than VirGL eventually |
| **Limitations** | Broad game compatibility, long-running async workloads, full external semaphore/fence coverage and performance tuning are all **not yet validated** [15](https://github.com/LukeValen/panvk-mali-g52) |
| **Integration difficulty** | Highest: driver packaging, WSI, and a device-specific ICD |

### Option C — CPU software rendering (llvmpipe)

| | |
| --- | --- |
| **Components** | Mesa llvmpipe, no GPU involvement |
| **GPU compatibility** | Total — it does not use the GPU |
| **Android compatibility** | Total |
| **Performance** | **Single-digit FPS for 3D** [13](https://winlator.dev/best-gpu-drivers/) |
| **Value** | A diagnostic escape hatch: if llvmpipe renders and VirGL does not, the fault is in the GPU path, not in Wine or the game |
| **Integration difficulty** | Lowest |

### Decision

**Option A is the default, and it is not a close call for this GPU.** Panfrost's
GLES 3.1 conformance on Mali-G52 is the only Khronos conformance statement that
covers this device; PanVK's own source says it is not conformant here. Option B
stays **available but never auto-selected**, and becomes the default only once it
has been measured on the CK6 — which is exactly the rule the launcher already
applies to the experimental renderer.

The architecture therefore treats the graphics backend as a pluggable choice
with three implementations, and encodes the "never auto-select the experimental
one" rule in code rather than in a comment.

---

## 6. Audio

Bloodlines uses **DirectSound3D** [2](https://www.mobygames.com/game/15663/vampire-the-masquerade-bloodlines/specs/).
The route is Wine's ALSA/pulse backend bridged to a host API. Two host targets:

- **AAudio** — lower latency, Android 8.0+ (our `minSdk` is 26, so it is always
  available).
- **OpenSL ES** — broader compatibility, deprecated but still present.

The launcher already stores an `AudioBackend` preference (Auto / AAudio /
OpenSL ES). The runtime will honour it; until the bridge exists the backend is a
recorded preference only.

---

## 7. Proposed module boundaries

The brief asks for nine separated responsibilities. Mapped onto this codebase:

```
                    ┌───────────────────────────┐
   MainActivity ───▶│      RuntimeManager       │  lifecycle + state machine
   (UI only)        └────────────┬──────────────┘
                                 │
     ┌──────────────┬────────────┼──────────────┬──────────────┐
     ▼              ▼            ▼              ▼              ▼
 WineEnvironment  CpuTranslator GraphicsBackend AudioBackend  GameProcessManager
 (prefix, env)    (Box86/Box64  (VirGL/WineD3D, (ALSA→AAudio  (spawn, watch,
                   /FEX)         PanVK/DXVK,     /OpenSL)      reap, exit code)
                                 llvmpipe)
     └──────────────┴────────────┼──────────────┴──────────────┘
                                 ▼
                    RuntimeConfiguration ──▶ RuntimeDiagnostics
                    (settings + device facts)  (feeds ReportInputs)
                                 ▲
                    InstallationValidator
                    (PE header, required files)
```

Rules the implementation follows:

1. **`MainActivity` knows nothing about Wine.** It talks to `RuntimeManager` and
   renders states. That is what "avoid tightly coupling runtime functionality to
   MainActivity" means in practice.
2. **Every module declares its own prerequisites** and can say "not available,
   because …" without throwing.
3. **Nothing reports `READY` because it compiles.** A module is `READY` only when
   its binaries ship in the APK for this ABI.
4. **Backends are data, not `when` statements sprinkled through the UI.** Adding
   a backend is adding one entry to a catalogue plus one implementation.

---

## 8. What this milestone builds, and what it defers

| Built here | Deferred, with the reason |
| --- | --- |
| The module structure above, in Kotlin, with unit tests | **Wine itself** — a Wine build for Android is a multi-hour cross-compilation (mingw for the PE side, plus a glibc sysroot) that cannot be produced or verified in this environment. Shipping an unverifiable binary would be worse than shipping none |
| `InstallationValidator`: real PE-header parsing to prove `Vampire.exe` is a 32-bit x86 Windows binary | Box86/Box64 binaries — same reason, and they must match the Wine build they pair with |
| A native **graphics capability probe** that reports, on the device, whether the kbase route (`/dev/mali0`) or the DRM route (`/dev/dri/renderD*`) is even available | The glibc userland and PRoot — a large binary dependency, and it must be built against the Wine build |
| A native **process manager probe** that measures fork/exec/wait/exit-status round trips and the process limit | Driver importing (explicitly out of scope per the brief) |
| Runtime diagnostics wired into the existing report | Touch HUD, controls, quick slots (explicitly out of scope) |
| The launch lifecycle state machine, connected to `OrientationController` | — |

**The Launch button stays disabled.** `LaunchReadiness` is extended to also
require a validated installation, but it still returns `Blocked` because no
runtime module reports `READY`. It is not enabled by this milestone, and it will
not be enabled until a launch path genuinely exists.

---

## 9. Honesty ledger

To be read alongside the final report. Nothing here is claimed as working
because it compiles.

| Layer | Implemented | Built | Initialised | Verified on hardware |
| --- | --- | --- | --- | --- |
| Module structure, state machine | ✅ | ✅ | n/a | n/a (pure Kotlin, unit tested) |
| PE-header install validation | ✅ | ✅ | n/a | ⏳ Needs the CK6 |
| Graphics capability probe | ✅ | ⏳ CI | ⏳ | ⏳ Needs the CK6 |
| Process manager probe | ✅ | ⏳ CI | ⏳ | ⏳ Needs the CK6 |
| Wine | ❌ | ❌ | ❌ | ❌ |
| Box86 / Box64 / FEX | ❌ | ❌ | ❌ | ❌ |
| glibc userland / PRoot | ❌ | ❌ | ❌ | ❌ |
| Graphics translation | ❌ | ❌ | ❌ | ❌ |
| Audio bridge | ❌ | ❌ | ❌ | ❌ |
| Launching the game | ❌ | ❌ | ❌ | ❌ |
