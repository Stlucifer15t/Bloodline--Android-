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

## 4. CPU translation: how a 32-bit executable is hosted

> **This section replaces the original assessment.** The Milestone 2 version
> recorded Box86 + a 32-bit Wine prefix as the default on the reasoning that
> `Vampire.exe` is a 32-bit binary, so a 32-bit translator fits. That reasoning
> was inverted, and the code has since been corrected rather than merely
> annotated. The inversion is worth recording, because the wrong answer looked
> like the careful one.

### What the working runtimes actually do

Inspecting the launchers of the Android runtimes that run 32-bit Windows games
on Mali showed **no Box86 anywhere**, and a `WINEARCH=win64` prefix rather than
`win32`. They host the 32-bit guest through **WoW64 inside a 64-bit prefix**:

| Route | Mechanism | Needs a 32-bit userland |
| --- | --- | --- |
| **WOWBox64** | Box64 built with WoW64 support, loaded by Wine as `wowbox64.dll` | No |
| **FEX-Emu** | FEX supplying the WoW64 layer as `libwow64fex.dll` | No |
| **Box86 + 32-bit prefix** | A 32-bit translator in a 32-bit Wine prefix | **Yes** |
| Plain Box64 | x86_64 only | No — but cannot host a 32-bit guest at all |

The deciding detail is not performance, it is the dependency. **Box86 is itself
a 32-bit ARM program**, so it needs a 32-bit userland — precisely the thing
Android is removing, and precisely the thing this project's own `runtime_probe`
exists to measure. The WoW64 routes need no 32-bit userland at all.

### The second distinction: ARM64EC versus x86_64 Wine

The command shape follows from which Wine build is used, and the two are not
interchangeable:

* **ARM64EC Wine** — the command is `wine <exe>`. Wine's own code is ARM64 and
  runs natively; only the 32-bit guest is translated, by the WoW64 DLL named in
  `HODLL`.
* **x86_64 Wine** — the command is `box64 wine <exe>`, because the `wine` binary
  itself is x86_64. No `HODLL` is set: Box64 is already emulating the whole
  process, 32-bit guests included.

This project targets ARM64EC. `WineFlavour` models both, and
`WineEnvironment` sets `HODLL` **only** for the flavours that use a WoW64
layer — setting it on an x86_64 Wine would be cargo cult.

### Position

**WOWBox64 is the default; FEX is the alternative and the retry.** Box86 remains
listed and selectable, because it is a legitimate way to run 32-bit code and
because being wrong about it once is a reason to keep it visible rather than
delete it — but it is never recommended, and no package in the catalogue
supplies it.

Plain Box64 is marked as unable to host the game, so the launch gate can refuse
a configuration that could never work instead of failing somewhere obscure.

The recommendation is deliberately **not** derived from a device measurement.
An earlier draft switched to FEX on a Mali device that could not execute 32-bit
processes; reading the Mali builds showed they keep Box64 and set
`BOX64_MMAP32=0` instead. The accommodation is a Box64 setting, not a different
translator — and nothing measurable on a phone tells us which emulator handles a
given game better.

---

## 5. Graphics: routes for Mali-G52

Mali-G52 is **Bifrost, architecture v7, Job Manager frontend**. That
classification decides everything below
[10](https://github.com/zenithblue-oss/panvk-kbase-android/blob/main/docs/MALI-GPU-ARCHITECTURES.md).

> **This section replaces the original assessment in one respect:** VirGL is no
> longer the default. It remains available as a fallback.

### The default — native GLES wrapper + WineD3D

| | |
| --- | --- |
| **Components** | The device's own OpenGL ES driver, loaded through a wrapper, + WineD3D (D3D9 → OpenGL) |
| **GPU compatibility** | **Panfrost GLES 3.1 is Khronos-conformant on Mali-G52** [11](https://docs.mesa3d.org/drivers/panfrost.html?highlight=panfrost) — the strongest compatibility statement available for this GPU |
| **Why the wrapper rather than VirGL** | This is what the Mali-focused runtimes ship. Winlator Mali **removed** VirGL in 1.2 in favour of a native EGL renderer, and offers only driver-*wrapper* variants. Loading the vendor's own GLES driver needs no Mesa in between |
| **Integration difficulty** | Low: no device-specific driver package to acquire |

VirGL + WineD3D stays in the catalogue as the fallback. It is the better-tested
route on desktop-class Linux, and it is what tells us whether a failure is in
the wrapper or in WineD3D.

### The texture problem, which is not optional

Every 2004 Source-engine texture is **DXT1 or DXT5** — BC1 and BC3 in modern
terms. **Mali does not decode BCn.** Nothing about a conformant GLES 3.2 driver
implies otherwise: BCn is not in the GLES mandatory set.

Miss this and the failure is silent and total. The game does not crash and does
not stutter: it runs, and the world is black. That reads as "Wine is broken" and
sends the investigation down the wrong path entirely.

`TextureCompatibility` reads the GL extension string and picks the best route
this GPU supports:

| Route | When | Cost |
| --- | --- | --- |
| Native BCn | `GL_EXT_texture_compression_s3tc` present | none |
| BCn → ASTC | ASTC extensions present | transcode |
| BCn → ETC2 | any GLES 3.0 device (ETC2 is mandatory) | transcode |
| Software | GLES 2 only | CPU decode, per texture |

An unmeasured device reports **unsupported** rather than a hopeful guess.

### Vulkan (DXVK over PanVK) — optional, never automatic

| | |
| --- | --- |
| **GPU compatibility** | **Poor upstream, improving in the community.** PanVK is conformant **only on Mali-G610 (v10)**; on v7 it is explicitly **experimental and non-conformant** — the driver prints `panvk is not a conformant Vulkan implementation, testing use only` and `panvk is not well-tested on v7` [10](https://github.com/zenithblue-oss/panvk-kbase-android/blob/main/docs/MALI-GPU-ARCHITECTURES.md) [11](https://docs.mesa3d.org/drivers/panfrost.html?highlight=panfrost) [14](https://github.com/Rydev-pc/panvk-mali-g52-testes) |
| **Community state** | Brought up on Mali-G52 MC2 over the proprietary `mali_kbase` device (`/dev/mali0`), no root. Device creation, compute dispatch, offscreen rendering with correct readback, X11 WSI, AHardwareBuffer import and vkcube-on-screen validated; basic DXVK D3D9/10/11 rendering validated on a nearly identical device (Redmi 13C, MT6768, Mali-G52 MC2, Android 14) [15](https://github.com/LukeValen/panvk-mali-g52). Reproduced on a Galaxy A06: `PanVK-G52 MC2`, apiVersion 1.4.363 [14](https://github.com/Rydev-pc/panvk-mali-g52-testes) |
| **Licensing** | The Bifrost build declares **no licence**. It therefore cannot be bundled and this application does not download it. It is user-installed or nothing |
| **Policy** | Never selected automatically, however capable the device looks. A measurement can prove a route *might* work, never that it *does* |

### llvmpipe — the diagnostic

Software rendering, no GPU involvement, single-digit FPS for 3D
[13](https://winlator.dev/best-gpu-drivers/). Its value is diagnostic: if
llvmpipe draws and the wrapper does not, the fault is in the GPU path rather
than in Wine or the game.

### Decision

**The native GLES wrapper is the default.** Graphics is a pluggable choice with
four implementations, the texture route is chosen explicitly and reported, and
the "never auto-select the experimental route" rule is encoded in
`GraphicsBackendSelector` rather than left in a comment.

---

## 6. Audio

Bloodlines uses **DirectSound3D**. The chain is longer than it looks, and the
number of hops is the reason this needs spelling out:

```
Bloodlines ──▶ DirectSound3D ──▶ Wine's ALSA driver ──▶ an ALSA plugin ──▶ Android
   (x86)          (in the prefix)       (in the prefix)        (host)       (host)
```

The last hop is the only one this app owns, and it is not one hop.

### What the working implementation actually does

Read from Winlator's source, rather than inferred. `android_alsa/` builds
`asound_module_pcm_android_aserver.so` — an ALSA **ioplug** external plugin
(`snd_pcm_ioplug_t`), placed where `ALSA_PLUGIN_DIR` points. `asound.conf`
routes `pcm.!default` to it:

```
pcm.!default { type android_aserver }
ctl.!default { type android_aserver }
```

The plugin does **not** call AAudio or OpenSL ES. It cannot: it runs inside the
Wine process, which is a foreign-binary process with no access to Android's Java
APIs. Instead it speaks a small request protocol over a **Unix domain socket** to
a server in the app's own process, and passes the audio buffer between the two as
shared memory handed over `SCM_RIGHTS`. The app-side server is the only thing
that touches `AudioTrack`.

So the real design is: guest-side ALSA plugin (C) ⇄ socket + shared memory ⇄
host-side sink (Kotlin, owning an `AudioTrack`).

### What this project does today

The `asound.conf` written at launch names ALSA's own `null` device, and says so
in a comment inside the file.

That is a deliberate, reported fallback rather than a working backend. Naming a
plugin type that nothing implements is not graceful degradation — ALSA fails with
"Unknown PCM" the moment Wine opens the device, and the game's audio dies with
it. The `null` device always resolves: it opens, accepts every sample the game
writes, and emits silence. The guest sees a sound card that works, and the rest
of the runtime can be exercised through it.

Diagnostics keep the two apart, so a report can say *sound is enabled but
nothing is carrying it* rather than implying both work:

* `Wine · audio` — enabled, no PCM plugin present, so the game will be silent
* `Wine · audio` — AAudio · 48000 Hz · 512 frames

The preference stored in the launcher (Auto / AAudio / OpenSL ES / none) is
honoured throughout, and "none" is a real setting rather than an absent one: it
starts the game muted so a sound problem cannot block testing everything else.

### What would be needed

Two pieces, and neither exists here yet:

1. **A guest-side ALSA ioplug**, compiled against alsa-lib's external plugin API
   for the guest ABI, speaking the protocol above.
2. **A host-side sink** in Kotlin owning an `AudioTrack`, listening on the socket
   end.

Neither can be compiled or run in this environment, so neither is claimed.

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

## 8. What Milestone 2 built, and what it deferred

| Built | Deferred, with the reason |
| --- | --- |
| The module structure, in Kotlin, with unit tests | **Wine itself** — a Wine build for Android is a multi-hour cross-compilation that cannot be produced or verified here. Shipping an unverifiable binary would be worse than shipping none |
| `InstallationValidator`: real PE-header parsing to prove `Vampire.exe` is a 32-bit x86 Windows binary | Box86/Box64 binaries — must match the Wine build they pair with |
| A native **graphics capability probe** reporting whether the kbase route (`/dev/mali0`) or the DRM route (`/dev/dri/renderD*`) is available | Driver importing (explicitly out of scope per the brief) |
| A native **process manager probe** measuring fork/exec/wait/exit round trips and the process limit | Touch HUD, controls, quick slots (explicitly out of scope) |
| Runtime diagnostics wired into the existing report | — |
| The launch lifecycle state machine, connected to `OrientationController` | — |

The deferred items have since been resolved not by building those binaries but
by **acquiring** them — see §10.

---

## 9. Honesty ledger

To be read alongside the final report. Nothing here is claimed as working
because it compiles.

| Layer | Implemented | Built | Executed here | Verified on hardware |
| --- | --- | --- | --- | --- |
| Module structure, state machine | ✅ | ✅ | ✅ unit tests | n/a |
| PE-header install validation | ✅ | ✅ | ✅ unit tests | ⏳ Needs the CK6 |
| Package acquisition (download, resume, verify) | ✅ | ✅ | ✅ **real downloads, in CI** | ⏳ |
| Archive handling (zip, tar, xz, zst, gz) | ✅ | ✅ | ✅ **real upstream packages** | ⏳ |
| Executable permissions, path traversal | ✅ | ✅ | ✅ unit tests | ⏳ |
| ELF validation of the Wine binary | ✅ | ✅ | ✅ **on the real downloaded binary** | ⏳ |
| Process execution, exit codes, timeouts | ✅ | ✅ | ✅ **real processes, in tests** | ⏳ |
| Wine initialisation sequence | ✅ | ✅ | ⏳ staged and asserted, never against Wine | ❌ |
| Prefix creation | ✅ | ✅ | ❌ | ❌ |
| WoW64 hosting a 32-bit program | ✅ | ✅ | ❌ | ❌ |
| Graphics initialisation | ✅ config | ✅ | ❌ | ❌ |
| Audio output | ⬜ config only | ✅ | ❌ | ❌ |
| Launching the game | ✅ code path | ✅ | ❌ | ❌ |
| Reporting why a launch failed | ✅ code path | ✅ | ✅ partial — observed on one device, one crash | ❌ |
| The 32-bit check not vetoing the launch | ✅ code path | ✅ | ❌ — reasoned, never watched | ❌ |
| Bloodlines | — | — | — | ❌ |

**"Built" means it compiles and its unit tests pass. It does not mean the game
ran.** The rows marked "executed here" were genuinely exercised — real bytes
downloaded, real archives unpacked, real processes started — but not one of
them involved Wine running, because there is no device here and no ARM64
machine to run it on.

---

## 10. What the runtime integration added

Milestone 3 established *what* the runtime should be. This is the work that made
it real, and the shape it took.

### The runtime is acquired, not bundled

Wine and its WoW64 layer are downloaded once, from the upstream projects that
build them, into a folder belonging to this app. Nothing is written beside the
game files. The catalogue pins each component to a specific release so it cannot
change under us, and records its licence and source location — which is what
makes the LGPL source-distribution obligation satisfiable rather than merely
acknowledged. See `NOTICE`.

The one component found during discovery with **no declared licence** — the
Bifrost PanVK build — is absent from the catalogue rather than present with a
hopeful note. Vulkan is user-installed or nothing.

### The pipeline

```
catalogue ─▶ download to staging ─▶ verify ─▶ unpack ─▶ mark installed
                                                  │
                              resolve files ──────┘
                                     │
        install the WoW64 DLL into the prefix's system32
                                     │
        build environment + registry settings + command
                                     │
                              start the process
```

Each step refuses rather than guesses:

* **Staging before extracting.** A partial download is never mistaken for a
  package, so the launch gate cannot be fooled by an interrupted transfer.
* **Verified as far as the truth allows.** Upstream publishes no SHA-256 for
  these assets, so packages install as *unverified* and say so — treating "no
  checksum" as "no problem" would be the easy implementation and the dishonest
  one. It does publish each asset's byte length, and the catalogue records
  those exact numbers (read back out of the release API, not copied from a
  changelog), so the download is checked against its published length. That
  catches the two failures that actually happen on a phone: a truncated
  transfer, and a redirect that serves HTML instead. It also does what a pin
  is for — if upstream ever re-uploads an asset of a different size, refusing
  is the correct answer.
* **Path traversal refused.** Archive entry names are resolved against the
  target and anything that escapes aborts the whole extraction. A package is a
  third-party file fetched over the network.
* **Files searched, not assumed.** `RuntimeLayout` looks for `wine` rather than
  trusting `bin/wine`, because the internal layout of a third-party package is
  not ours to decide and a hard-coded path one directory off reports the wrong
  problem.

### Two corrections worth stating

Both were wrong in the code, not just in the documentation, and both were
corrected rather than annotated:

1. **The prefix is 64-bit and the 32-bit game runs through WoW64.** Milestone 2
   hard-coded `WINEARCH=win32`. It is now derived from the Wine flavour, and
   `HODLL` is set only for the flavours that use a WoW64 layer.
2. **There is no container.** The Bionic builds run Wine against Android's own
   libc, so there is no glibc userland and no PRoot. `RuntimeRegistry` reports
   the container as ready because there is nothing to be missing, rather than
   as unfinished work.

### What the gate now asks

`LaunchReadiness` requires every runtime component to report READY, and those
statuses now come from a `RuntimeProvider` that answers from what is actually on
the device. Three states are kept distinct because a user can act on all three:

* **Not installed** — the packages have not been downloaded.
* **Installed but unusable** — the marker is there, the files are not.
* **Ready** — the packages are installed and their files resolve.

Only the third used to open the Launch button. That turned out to be a deadlock,
described in the next section, so there is now a fourth state the gate reports.

### What a real package turned out to contain

CI fetches the pinned packages and reports what it found. This is observation,
replacing inference:

| Observation | Why it mattered |
| --- | --- |
| Wine downloads in 20 s; 2210 files | The pipeline is not theoretical |
| Its loader is 64-bit ARM, linked against `/system/bin/linker64` | Confirms a Bionic build, which is what decides whether it can run on Android at all |
| `bin/wine` exists, and so does `lib/wine/aarch64-unix/wine` | The loader had to prefer `bin/wine`; a walk found the other one first |
| There is **no `windows` directory** | Every path that assumed one was wrong |
| 32-bit Windows files are at `lib/wine/i386-windows` | Where `cmd.exe` lives — the 32-bit program the WoW64 stage runs |
| `cmd.exe` there is a PE 32-bit x86 Windows console program | Exactly what is needed to prove the 32-bit path |
| WOWBox64 holds `system32/wowbox64.dll`, a PE 64-bit ARM DLL | Confirms what `HODLL` names |
| The package contains no ALSA plugin | The audio null-device fallback is not a stopgap; there is nothing to route to yet |

Three inferred paths were wrong and are now corrected against measurement.

### What running the APK on a real device found

A debug APK was installed on the target hardware and opened. It produced no
download prompt and no way to start one. That is the only device observation in
this document, and it found a real bug that every test here had passed:

`LaunchReadiness` returned `Blocked(RUNTIME_NOT_INTEGRATED)` for a runtime that
was merely *absent*, and the activity enabled the Launch button only for
`LaunchState.Ready`. The runtime is downloaded rather than bundled, so the gate
was closed by the very thing it was waiting for — `offerRuntimeInstall()` sat
behind a button that could not be pressed, and the packages could not be
acquired on any device.

Two lessons, both now in the code:

1. **"Not integrated" and "not downloaded" are different states.** They were the
   same thing while the runtime was not integrated at all. Once it became
   something fetched at run time, one of them is a thing a tap can fix and the
   other is not, so they had to stop sharing a name. `LaunchState.NeedsRuntime`
   is the fixable one; `Blocked` is reserved for what no tap can change.
2. **A test suite cannot catch a state that is unreachable.** Every unit test
   passed, because each one asked `LaunchReadiness` a question in isolation.
   None of them asked whether the answer could still be acted on. The regression
   test now asserts the property that was missing — a runtime that is only
   absent must not come back as a blocked state — rather than a particular value.

The runtime card also carries its own download button, because the Launch button
is disabled while the game files are missing and the runtime has nothing to do
with them.

### Still unverified

Everything above compiles and is unit tested. Nothing in the runtime itself has
been executed on a device. In particular:

* No Wine process has been started; no prefix has been created.
* The packages **are** downloaded in CI, and the checksums in the catalogue are
  measured from those bytes rather than published by upstream (see the
  observation table above). They have never been downloaded by a phone.
* `.wcp` is a private extension for a tar archive compressed with XZ **or**
  Zstandard. Which one a given package uses is not stated anywhere we can read,
  so both are supported and the format is detected from the bytes.
* The internal layout is no longer searched for blindly: the table above records
  what CI found inside the real package. It is still not documented upstream, so
  a different package could still differ.
* The only device observation is the one in the previous section, and it is a
  bug report, not a success. Nothing here is a claim about hardware.

