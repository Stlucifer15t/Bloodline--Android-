# Milestone 3 — open-source runtime discovery

Investigation only. **No integration code was written and no binary was
downloaded.** Everything below is either read from source, read from repository
metadata, or explicitly marked as unverified.

Target: 32-bit Windows `Vampire.exe` (Direct3D 9, Source 2004) on a
**TECNO CK6 — Helio G85, Mali-G52 MC2, Android 14**.

---

## 0. One gap up front: "lifthost" was not found

The brief asked me to investigate "the previously discovered Bloodlines Android
implementation, particularly its **lifthost**, GLES renderer, Wine integration
and SDL3 implementation."

I could not find it. What I searched:

| Search | Result |
| --- | --- |
| GitHub code search, `lifthost` | 20 hits, all unrelated (Rust/TypeScript/Haskell noise from fuzzy matching) |
| GitHub code search, `lift_host` / `lift-host` | unrelated (CrowdStrike templates, Fortran, DuckDB) |
| GitHub code search, `lifthost extension:java` / `extension:c` | unrelated (Minecraft mods, a GBA port test file) |
| GitHub repo search, `lifthost` | no repositories |
| Web search, `"lifthost" Bloodlines Android Wine SDL3` | unrelated |
| Web search, `"lifthost" wine android host binary` | unrelated |
| `grep -i lift` over the file trees of WinlatorMali, WinlatorMaliPlus, Bannerlator, winlator, darkos | **no match in any of them** |
| Your other repo `Stlucifer15t/-Bloodlines-Android-Rebuild` | exists, but contains one file: `README.md`, whose entire content is `# -Bloodlines-Android-Rebuild` |

The only real `lifthost` I can find anywhere is a Jetpack Compose modifier in an
email app, which is not this.

**I need the link.** If that project is a native/recompilation port rather than an
emulation host, it changes the shape of this whole investigation — a native port
would sidestep Wine entirely. Nothing below assumes it exists.

---

## 1. The headline finding: nobody uses Box86 any more

This is the finding that most changes our plan, and it contradicts the
assumption baked into Milestone 2.

Milestone 2 recorded: *"`Vampire.exe` is 32-bit, so Box86 inside a 32-bit Wine
prefix is required."* That was reasonable from first principles. It is **not what
the projects that actually work do.**

In every actively maintained Android Wine runtime I inspected, the 32-bit path is
**WoW64 inside a 64-bit prefix**, not a 32-bit prefix with Box86:

* **WinlatorMali** ships `app/src/main/assets/wowbox64/wowbox64-{0.3.7,0.4.3}.tzst`
  and **no Box86 package at all**. Its upstream `installable_components/` lists
  box64, dxvk, turnip, vkd3d, wined3d — again, no box86.
* `GuestProgramLauncherComponent.extractEmulatorsDlls()` extracts WOWBox64 into
  `drive_c/windows/system32`, which is the WoW64 location, not a 32-bit prefix.
* `execGuestProgram()` sets `HODLL=wowbox64.dll` (WOWBox64) or
  `HODLL=libwow64fex.dll` (FEX), and chooses between them with
  `wineInfo.isArm64EC()`.
* FEX's ARM64EC mode installs `libarm64ecfex.so` / `libwow64fex.so` into
  `<imagefs>/usr/lib/wine/aarch64-unix/`.
* Available Wine builds in WinlatorMali (`res/values/arrays.xml`):
  `proton-10-arm64ec`, `proton-9.0-arm64ec`, `proton-9.0-x86_64`.

Corroborated by the Winlator-Ludashi README (a Bionic-lineage fork):

> "It supports standard x86_64 containers using Box86/Box64, as well as **Arm64EC
> containers which utilize FEXCore (for 64/32-bit) or an optional WowBox64 (for
> 32-bit)**."

**Why this matters for us, specifically.** Milestone 2 flagged the 32-bit
dependency as the fragile part of the plan — Box86 *is* a 32-bit ARM program, so
it needs a 32-bit userland, and 32-bit support is exactly what kernels are
dropping, and exactly what our own `runtime_probe` measures. The WoW64 route
**removes that dependency entirely**: a 64-bit Wine prefix hosts the game's
32-bit code, with only the application's own instructions translated. Box86 is
not involved and no 32-bit userland is needed.

That is a strictly better fit for our constraint, and it is the approach with
actual device mileage behind it.

---

## 2. Two architectures exist, and they are not the same thing

I had assumed all of these projects were "PRoot + glibc rootfs". They are not.

### Family A — glibc + PRoot

`brunodev85/winlator` v11.2, **LGPL-2.1**, 19.9k stars. README credits
"GLIBC Patches by Termux Pacman", Box86/Box64, Mesa (Turnip/Zink/VirGL), DXVK,
VKD3D. Top-level `glibc_patches/sysdeps/unix/sysv/linux/` implements
`shmget` / `shmat` / `shmdt` / `shmctl` — the SysV SHM shim I documented in
Milestone 2. `app/`, `vortek/` and `gladio/` are **git submodules**
(`brunodev85/winlator-app`, `brunodev85/vortek`, `brunodev85/gladio`).

### Family B — Bionic, no PRoot, no glibc rootfs

This is the one that matters for Mali. From the Winlator Mali README:

> "Built upon the high-performance **Android Bionic C runtime** (originating from
> Pipetto Cripto's Winlator Bionic fork), Winlator Mali **bypasses traditional
> PRoot container and syscall-translation overhead**."

I verified this is real and not marketing. `app/src/main/cpp/CMakeLists.txt` in
WinlatorMali builds: `patchelf`, `adrenotools`, `winlator` (with a **native C++
EGL renderer**), `zerofg`, `fakeinput`, `gladiorenderer`.

**`proot` is not in that CMakeLists.** The PRoot sources are still present at
`app/src/main/cpp/proot/src/` with their GPL-2 `COPYING`, but the module is
**never built**. It is dead weight inherited from upstream. `android_sysvshm/`
*is* real (it is referenced from Java: `ANDROID_SYSVSHM_SERVER`, and there is a
`SysVSharedMemoryComponent`), so a SysV SHM shim is still needed — but there is
no container.

This is a materially simpler thing to integrate: **no root filesystem to unpack,
no chroot emulation, no syscall translation layer.**

---

## 3. Repositories discovered

### Primary candidates

| Repository | Licence | Stars | Last push | Why it matters |
| --- | --- | --- | --- | --- |
| [`brunodev85/winlator`](https://github.com/brunodev85/winlator) | LGPL-2.1 | 19,927 | 2026-11-02 | The reference implementation. v11.2. glibc+PRoot. Credits Box86, Mesa Turnip/Zink/VirGL. Submodules: `winlator-app`, `vortek`, `gladio` |
| [`Pipetto-crypto/winlator`](https://github.com/Pipetto-crypto/winlator) | **MIT** | 218 | 2026-11-10 | **Origin of the Bionic architecture.** Most permissive licence of any candidate |
| [`GunaCharanTeja/WinlatorMali`](https://github.com/GunaCharanTeja/WinlatorMali) | **MIT** | 519 | 2026-10-31 | **Built specifically for Mali.** ⚠️ README: *"Winlator Mali is no longer being updated. 1.2.1 is the final release."* |
| [`The412Banner/Bannerlator`](https://github.com/The412Banner/Bannerlator) | GPL-3.0 | 536 | **2026-11-12** | Most active. Continues the discontinued Star Bionic line. Has CI + releases + a **Mali bug-report board**. Bundles SDL3 |
| [`LamPPKK/WinlatorMaliPlus`](https://github.com/LamPPKK/WinlatorMaliPlus) | MIT | 0 | 2026-11-02 | Near-identical tree to WinlatorMali. No stars, no visible track record |
| [`ahmad1abbadi/darkos`](https://github.com/ahmad1abbadi/darkos) | GPL-3.0 | 227 | 2024-10-02 | **Not relevant.** 25 files, Python scripts that drive a Termux chroot. No Android app, no APK |

### Lineage and component infrastructure

| Repository | Licence | Note |
| --- | --- | --- |
| [`star-emu/star`](https://github.com/star-emu/star) | GPL-3.0 | "Winlator Star Bionic" — the line Bannerlator continues |
| [`coffincolors/winlator`](https://github.com/coffincolors/winlator) | MIT | 1,119 stars but last pushed 2025-12-09 |
| [`Pipetto-crypto/libadrenotools`](https://github.com/Pipetto-crypto/libadrenotools) | **BSD-2-Clause** | The driver-loading library. Used as a git submodule by WinlatorMali. Far more permissive than WinlatorMali's credit line ("GPL v3") suggests |
| [`The412Banner/winlator-contents`](https://github.com/The412Banner/winlator-contents) | none declared | **Component catalog** — `contents.json` index; binaries hosted on `The412Banner/Nightlies` releases. This is the external-runtime-package model, already working |

### Mali graphics — directly relevant to our GPU

| Repository | Licence | Note |
| --- | --- | --- |
| [`soulexit/panvk-bifrost-soulexit`](https://github.com/soulexit/panvk-bifrost-soulexit) | **NOASSERTION** | ⭐ **A PanVK driver for Mali Bifrost — G52 explicitly supported.** Releases only |
| [`soulexit/panvk-kirin990`](https://github.com/soulexit/panvk-kirin990) | NOASSERTION | The source repo behind the above. Has a `LICENSE` file GitHub cannot classify |

---

## 4. Source files worth reading (verified paths)

### The launch path — read this first

`app/src/main/java/com/winlator/cmod/xenvironment/components/GuestProgramLauncherComponent.java`
(WinlatorMali, branch `bionic-mali-1.2`)

This single file is the most useful thing in the whole investigation. The
relevant parts:

```java
// start(): pick the emulator path
if (wineInfo.isArm64EC()) extractEmulatorsDlls();   // FEX / WOWBox64
else                      extractBox64Files();      // plain Box64
pid = execGuestProgram();

// execGuestProgram(): the Mali-specific workaround
String renderer = GPUInformation.getRenderer(null, null);
if (renderer.contains("Mali")) execEnvVars.put("BOX64_MMAP32", "0");

// the launch command itself
if (wineInfo.isArm64EC()) {
    command = winePath + "/" + guestExecutable;
    execEnvVars.put("HODLL", emulator.equals("fexcore")
            ? "libwow64fex.dll" : "wowbox64.dll");
} else {
    command = imageFs.getBinDir() + "/box64 " + guestExecutable;
}
return ProcessHelper.exec(command, execEnvVars.toStringArray(), rootDir, callback);
```

That `renderer.contains("Mali")` branch is direct evidence that Mali is a
first-class, tested configuration in this codebase — not an afterthought.

Other files, in the order I would read them:

| File | What it settles |
| --- | --- |
| `.../xenvironment/ImageFs.java` | Rootfs layout: `/home/xuser/.wine` prefix, `/opt/<wine-version>`, `/usr/bin/box64`. No glibc/bionic marker either way — see CMakeLists for that |
| `.../xenvironment/ImageFsInstaller.java` | Unpacks `imagefs.tzst`, per-version Wine from `wine_entries`, drivers from `wrapper_graphics_driver_version_entries` |
| `.../core/EnvVars.java`, `core/WineUtils.java`, `core/WineInfo.java` | Environment construction, prefix creation, `isArm64EC()` |
| `.../components/SysVSharedMemoryComponent.java` | The SysV SHM shim's lifecycle |
| `.../components/ALSAServerComponent.java`, `PulseAudioComponent.java` | Audio: the two working options |
| `.../fexcore/FEXCoreManager.java`, `box64/Box64PresetManager.java` | Emulator presets and env vars |
| `app/src/main/cpp/CMakeLists.txt` | **Proves PRoot is not built** |
| `app/src/main/cpp/winlator/renderer/egl.cpp` | The native EGL renderer that replaced VirGL in 1.2 |
| `app/src/main/cpp/gladiorenderer/src/*.c` | Full GLES wrapper: shader converter, texture/BCn handling, ARB_program support |
| `android_sysvshm/android_sysvshm.c` | SysV SHM over a Unix socket |
| `glibc_patches/.../android_sysvshm.c` (upstream winlator) | The glibc-side equivalent |
| `app/src/main/res/values/arrays.xml` | Authoritative list of wines, emulators, drivers, DX wrappers |

---

## 5. Licence and redistribution analysis

Our project is **GPL-3.0** (`LICENSE`), with a `NOTICE` that already lists Wine,
Box86/Box64, Mesa and WineD3D under "Planned — not yet integrated".

| Component | Licence | Can we bundle it in a GPL-3 app? |
| --- | --- | --- |
| WinlatorMali app code | MIT | ✅ Yes — MIT is GPL-compatible |
| Pipetto-crypto/winlator | MIT | ✅ Yes |
| Bannerlator | GPL-3.0 | ✅ Yes — identical licence |
| star-emu/star | GPL-3.0 | ✅ Yes |
| brunodev85/winlator | LGPL-2.1 | ✅ Yes — LGPL-2.1 §3 permits conversion to "GPL version 2 or any later version" |
| libadrenotools | **BSD-2-Clause** | ✅ Yes (and note WinlatorMali's credit line saying "GPL v3" is *more* restrictive than the actual licence) |
| patchelf | GPL-3.0 | ✅ Yes |
| PRoot | GPL-2.0 (`src/COPYING`) | ⚠️ Only if it is "v2 **or later**" — must check the actual text. Moot if we take the Bionic path, where PRoot is not built |
| Wine / Proton | LGPL-2.1+ | ✅ Yes, with source offer + notices |
| Box64 | MIT | ✅ Yes |
| FEX-Emu | MIT | ✅ Yes |
| Mesa (incl. VirGL, Zink, Panfrost) | MIT / Expat | ✅ Yes |
| DXVK | zlib/libpng | ✅ Yes |
| VKD3D | LGPL-2.1 | ✅ Yes |
| SDL3 | zlib | ✅ Yes |
| ZeroFG | Apache-2.0 / BSD-3 | ✅ Yes |
| **`soulexit` PanVK (Bifrost)** | **NOASSERTION** | ❌ **Cannot redistribute.** GitHub cannot classify it; the source repo has an unrecognised `LICENSE`. This is a driver *package* to be installed by the user, not a bundlable component |

**Practical consequences.** The permissive stack (MIT / BSD / zlib / LGPL) is
bundlable. The one component we would most like to bundle — a working Mali Vulkan
driver — is the single one we cannot. That asymmetry is itself an argument for
the default being the **GLES path**, which needs no undeclared-licence binary.

We must also, before shipping anything, extend `NOTICE` with: the actual runtime
components bundled, their copyright holders and licences, and — for LGPL
components — a written offer for the corresponding source.

---

## 6. 32-bit Bloodlines compatibility assessment

**Question:** can these runtimes execute 32-bit x86 Windows applications?

**Answer: yes, by WoW64 — and this is the mainstream path, not a fallback.**

| Route | How | Evidence | Needs 32-bit userland? |
| --- | --- | --- | --- |
| **WOWBox64** | Box64 built with WOW64 support; `wowbox64.dll` + WoW64 thunks into `drive_c/windows/system32`; `HODLL=wowbox64.dll` | `wowbox64-0.3.7.tzst` / `0.4.3.tzst`; extraction target is `system32`; `HODLL` assignment | **No** |
| **FEX-Emu (ARM64EC/WoW64)** | `libwow64fex.dll` for 32-bit guests, `libarm64ecfex.so` for 64-bit; ARM64EC Wine | `fexcore-{2508,2601,2605,hybrid-x86}.tzst`; `FEX_UNIXLIB_SO_NAMES` reconciliation into `/usr/lib/wine/aarch64-unix/` | **No** |
| Box86 + win32 prefix | The route Milestone 2 assumed | Credited in the *glibc-family* READMEs; **no package ships in any Bionic-family asset tree I inspected** | **Yes** |

So the answer to the brief's requirement — *"a runtime that only supports 64-bit
is not acceptable unless it demonstrably supports the required 32-bit
compatibility path"* — is that these runtimes **do** support it, demonstrably,
via WoW64, and that the Box86 route we had planned is the one with no shipping
packages behind it.

**Bloodlines specifics.** Source 2004, D3D9, 32-bit, DirectSound3D. Under WoW64
Wine with WineD3D, none of that is exotic: D3D9 via WineD3D is the single
best-trodden path in Wine. `wincomponents/` in WinlatorMali ships
`direct3d.tzst`, `directsound.tzst`, `directmusic.tzst`, `ddraw.tzst`,
`xaudio.tzst` — the legacy DLL overrides this era of game expects.

⚠️ **Unverified:** none of this has been run against `Vampire.exe`. I have no
device, and I found no report of Bloodlines specifically.

---

## 7. Mali-G52 graphics assessment

### What the Mali-focused build actually uses

From the Winlator Mali README, the installation instructions:

> "**Mali GPUs**: set Graphics Driver to **Wrapper**, and set BCn Emulation to
> ASTC or ETC2.
> **Adreno GPUs**: choose **Turnip (Adreno)** with DXVK."

The available graphics drivers (`arrays.xml`) are **all Wrapper variants** —
`Wrapper`, `Wrapper-1.1`, `Wrapper-v2`, `Wrapper-leegao`, `Wrapper-gamenative`,
and in Bannerlator `Wrapper + bcn_layer`, `Wrapper + compat + bcn`.

**There is no VirGL option, no Zink option and no PanVK option in any of these
apps' driver lists.** VirGL appears in upstream (glibc-family) credits and was
explicitly *removed* in Winlator Mali 1.2:

> "**Native EGL Rendering Engine & Gladio Integration (Replaces 1.1 Renderer)**:
> Migrated from the legacy 1.1 rendering stack (GLRenderer, DisplayX, **VirGL**,
> blit_converter, and Java EffectComposer) to Pipetto-crypto's native C++ EGL
> renderer (`app/src/main/cpp/winlator/renderer/egl.cpp`)."

**So my Milestone 2 default — "VirGL + WineD3D" — is not what the Mali-focused
projects use.** They use a **native EGL/GLES wrapper** and load the system's own
GLES driver, which sidesteps Mesa entirely. That is a better default than VirGL
here: fewer moving parts, and it exercises the vendor's GLES driver directly,
which is the only Khronos-conformant graphics stack this GPU has.

### The BCn problem — this one is specific to our game

> "Mali GPUs don't natively support the BCn texture formats (BC1–BC7) that PC
> games rely on, which caused black textures and missing models."

Source-engine games from 2004 store their world textures as DXT1/DXT5 — i.e.
BC1/BC3. **Bloodlines will hit this.** The fix is a Vulkan layer by `leegao`
that transcodes BCn → ASTC/ETC2 with a disk cache, shipping as
`graphics_driver/leegao_bcn.tzst` / `wrapper-leegao`. This is not optional for
us; it is the difference between a rendered world and black geometry.

### PanVK: materially better than Milestone 2 assumed — but still not the default

Milestone 2 recorded PanVK on Bifrost as experimental and non-conformant, which
remains true of Mesa upstream. But there is now a **device-tested** driver:

`soulexit/panvk-bifrost-soulexit` README:

| GPU | Phone | Status |
| --- | --- | --- |
| Mali-G76 MC16 | Huawei P40 (Kirin 990) | works: main test phone |
| Mali-G72 MP3 | Samsung Galaxy A51 (Exynos 9611) | works |
| **Mali-G52 MC2** | **Vivo V21s (Helio G80)** | **works**, MediaTek note |

> "**May also work** (same GPU family, not tested yet): Mali-G71, G31, G51, G52
> and G76 on other chips, for example **Helio G70/G80/G85/G88/G90/G95** …"

**Our SoC — Helio G85 — is named explicitly**, and the working test device is a
Mali-G52 MC2 on a Helio G80, one step from ours. That is far stronger evidence
than anything available in Milestone 2.

⚠️ Still: the author calls it "still experimental", it is **NOASSERTION**-licensed
so we cannot ship it, and the D3D9 → Vulkan route (DXVK) on a non-conformant
Mali Vulkan driver remains unproven for a real game.

**Conclusion for graphics.** Default = **Wrapper (GLES/EGL) + WineD3D + BCn
emulation**. Treat PanVK as an optional, user-installed, experimental path. Drop
VirGL as our stated default — the projects that specialise in our GPU moved off
it. This does not change the *principle* from Milestone 2 (keep a working GLES
fallback, never auto-select the experimental path); it changes which GLES
implementation is the right one.

---

## 8. Integration approaches

### Approach A — extract components, keep our orchestration

Take the `.tzst` component archives and the small native glue, and drive them
from our existing `RuntimeManager`.

* **Effort:** high. The glue is not small — X server, ALSA/PulseAudio server,
  SysV SHM, the EGL renderer, `fakeinput`, `patchelf`, the adrenotools loader.
* **APK size:** 400–600 MB if bundled.
* **Compatibility:** we choose; nothing imposed.
* **Maintenance:** we own all of it.
* **Licensing:** clean, if we extend `NOTICE`.

### Approach B — adapt an existing runtime wholesale

Fork WinlatorMali or Bannerlator, replace the UI with ours.

* **Effort:** *looks* low, and is not. Their UI is not a skin — `Container`,
  `ContainerManager`, `XServerDisplayActivity`, `ContentsManager`,
  `WineDownloader` are load-bearing for the runtime, not decoration.
* **APK size:** 424 MB (Winlator Mali 1.2.1) / 569 MB (Bannerlator 3.1.9-pre2).
* **Maintenance:** we inherit every upstream change to a codebase with
  ~4,100 files.
* **Risk:** directly contradicts the brief's point 7 — "avoid replacing the
  entire launcher with Winlator or another application's interface."

### Approach C — external runtime package behind a clean interface

Keep the runtime as a separately delivered package; our launcher talks to it
through a contract.

* **Effort:** low for us *if* the interface is real; the hard question is what
  binds to what, since Winlator-family apps expose no documented inter-app API.
  The closest working precedent is `The412Banner/winlator-contents`: a
  `contents.json` catalog whose binaries live in GitHub releases — a
  package-and-fetch model, not an IPC model.
* **APK size:** ours stays ~3.5 MB.
* **Maintenance:** low; upstream moves independently.
* **Risk:** the binding mechanism does not exist yet and would have to be
  invented or agreed.

### Recommendation: **C as the architecture, entered via A, gated on a device test**

These are not really exclusive, and picking one in the abstract is premature.
What I recommend is:

1. **Adopt C as the target architecture.** Our own Milestone 2 brief already
   anticipated it: *"able to support multiple backends and externally managed
   driver packages later."* Add a `RuntimeProvider` contract now. It changes no
   behaviour.
2. **Test on the device before writing any integration code.** Install Winlator
   Mali 1.2.1 (MIT, Mali-specialised, has an actual release) on the CK6, point it
   at the real game folder, and find out whether it runs at all. This costs
   nothing and it is the only thing that can turn the rest of this document from
   inference into evidence. Everything above is source inspection; none of it is
   a measurement on your hardware.
3. **Then choose A or B on the evidence.** If it runs, A becomes a matter of
   reimplementing a known-good configuration rather than guessing at one.

### The specific things a device test would settle

* Does Wine initialise at all on Android 14 / Helio G85?
* Does the WOWBox64 or FEX path start `Vampire.exe`?
* Does D3D9 via WineD3D render, and does the BCn layer produce visible textures?
* What frame rate does a Source 2004 game actually hold on a Mali-G52 MC2?
* Does DirectSound come up through ALSA?

I am deliberately not predicting any of these.

---

## 9. Files that would need modification

Nothing here has been changed. This is the map.

### Must change regardless of approach

| File | Change |
| --- | --- |
| `runtime/backends/CpuTranslator.kt` | **Box86 should stop being the default.** `recommendedFor()` currently prefers `BOX86` whenever `canExecute32Bit` is true. Given that no shipping package uses it, the default should be `BOX64_WOW64` (then `FEX_ARM64EC`). Keep Box86 as an entry — it is still a valid route — but not the recommendation |
| `runtime/WineEnvironment.kt` | The `WINEARCH` model is inverted. It hard-codes `WIN32` because "Vampire.exe is a 32-bit binary". Under WoW64 the prefix is **64-bit** and the game's 32-bit code runs inside it. Needs a `WINEARCH = win64` + WoW64 mode |
| `runtime/RuntimeRegistry.kt` | Component text says *"Box86 inside a 32-bit Wine prefix is required; Box64 on its own cannot run it."* That half is now wrong — Box64 **with WOW64** is the mainstream route. Also says graphics is "VirGL + WineD3D"; should be "GLES wrapper + WineD3D" |
| `docs/RUNTIME_ARCHITECTURE.md` | §4 (CPU translation) and §5 (graphics) both need revising against §1 and §7 above |
| `NOTICE` | Add every component actually bundled, with copyright, licence and (for LGPL) a source offer |
| `runtime/RuntimeContracts.kt` | Add the `RuntimeProvider` / external-package concept: a capability set, a version, an ABI, and a lifecycle — so a runtime can be discovered or supplied rather than assumed present |

### If we take Approach A

Additionally: `runtime/GraphicsBackend.kt` (add the GLES-wrapper backend, add
BCn emulation as a requirement of the D3D9 path, keep PanVK experimental),
`runtime/RuntimeManager.kt` and `runtime/RuntimeHost.kt` (provider lifecycle
instead of a hard-coded command), `app/build.gradle.kts` and
`.github/workflows/android.yml` (a 400 MB APK breaks our current size
assumptions and probably our CI cache budget).

### Explicitly preserved

Launcher UI, settings and display configuration, install detection and the SAF
importer, diagnostics, `OrientationController` and the gameplay hand-off, the
existing CI workflow, and the `RuntimeModule` contract shape itself — a provider
implements `RuntimeModule`; it does not replace it.

---

## 10. Expected size and dependencies

| Scenario | Our APK | Notes |
| --- | --- | --- |
| Today (Milestone 2) | **3.5 MB** debug / 2.7 MB release | Runtime code only; no runtime binaries |
| Approach C (external) | ~3.5 MB + one or more runtime packages fetched or sideloaded | `The412Banner/winlator-contents` shows this model working |
| Approach A or B (bundled) | **~400–600 MB** | Winlator Mali 1.2.1 = 424 MB; Bannerlator 3.1.9-pre2 = 569 MB |
| PanVK driver, if used | **8.4 MB** | `soulexit-panvk-1.0.000.zip` — user-installed, not bundled |

Runtime dependencies, whichever route: Wine/Proton (LGPL-2.1+), Box64 or FEX
(MIT), a GLES wrapper or Mesa (MIT/Expat), WineD3D (LGPL-2.1), ALSA or PulseAudio
bridge, `libadrenotools` (BSD-2), `patchelf` (GPL-3), a SysV SHM shim, and an
X/Wayland surface. Plus, for our GPU, the BCn→ASTC/ETC2 layer.

---

## 11. Honesty ledger

| Claim | Basis |
| --- | --- |
| Repo licences, stars, push dates | GitHub API metadata |
| File paths, launch commands, env vars, `arrays.xml` contents | Fetched and read from source |
| PRoot is not built in WinlatorMali | Its `CMakeLists.txt` has no `add_subdirectory(proot)`; PRoot sources are present but unreferenced |
| Box86 ships in no Bionic-family asset tree | Absent from all four asset listings I read; `wowbox64/` and `fexcore/` are present instead |
| Mali-G52 MC2 works with soulexit PanVK on a Helio G80 | **The author's README**, not my test, and the G85 entry is "may work", not tested |
| BCn emulation is needed | Author's statement about Mali + the fact that Source 2004 uses DXT textures |
| **Anything running on the CK6** | ❌ **No device testing was performed. Nothing here has been executed on hardware.** |
| Bloodlines specifically working | ❌ No report found; no test run |

---

## 12. What I want approval on

1. **The "lifthost" link** — I could not find that project and need it before
   finalising anything.
2. **Dropping Box86 as the recommended CPU route** in favour of WOWBox64/FEX
   WoW64, and correcting `WineEnvironment`'s 32-bit-prefix assumption.
3. **Replacing "VirGL" with "native GLES wrapper"** as our stated Mali default,
   keeping PanVK experimental.
4. **Running the device test** (install Winlator Mali 1.2.1 on the CK6 with the
   real game) *before* any integration code — I think this is the highest-value
   next action by a wide margin, and it needs no code.
5. **Approach C as the target architecture**, with A or B chosen only after (4).
