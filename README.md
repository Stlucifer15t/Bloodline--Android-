# Bloodlines Android

[![Android CI](https://github.com/Stlucifer15t/Bloodline--Android-/actions/workflows/android.yml/badge.svg)](https://github.com/Stlucifer15t/Bloodline--Android-/actions/workflows/android.yml)

A standalone Android launcher for **Vampire: The Masquerade – Bloodlines** (PC, 2004).

This repository is the foundation of that project: a real, buildable Android
application with a native dark launcher UI, a working game-file importer, a
proven path for running bundled native binaries, and a modular architecture that
the Windows compatibility runtime will be plugged into.

> **Read this first.** The launcher does **not** run the game yet. No Wine,
> Box86/Box64, graphics or audio component has been integrated. The Launch button
> is disabled on purpose, and the screen tells you exactly which component is
> missing. Everything else in this README describes the plan, honestly labelled
> as a plan.

---

## Current status — Milestone 1 (foundation)

**Build: green.** Every push and pull request runs unit tests, Android lint and a
debug APK build on GitHub Actions; the APK is published as a downloadable
artifact. Verified configuration: Temurin JDK 17 + Gradle 8.13 + AGP 8.13 +
NDK 27 (CMake 3.22.1) on `ubuntu-latest`, producing a ~3.3 MB debug APK
(`app-debug.apk`, API 26+) that contains the native probe for every ABI.

| Area | State |
| --- | --- |
| Android app module (Kotlin, AGP, Gradle) | ✅ Done |
| Dark launcher UI (native Android views) | ✅ Done |
| Game installation detection (app storage + picked folder) | ✅ Done |
| Import game files (Storage Access Framework) | ✅ Done |
| Native execution probe (NDK, W^X-safe) | ✅ Done |
| Settings system (typed, persisted, honest) | ✅ Done |
| Launcher settings that take effect now (immersive, orientation, keep-awake, brightness, UI scale, FPS overlay, master volume) | ✅ Done |
| Runtime-dependent settings (FPS limit, resolution scaling, VSync, renderer, audio backend) | ⏳ Stored, shown disabled with the reason |
| Background import with real progress | ✅ Done |
| Cached install metadata (fast reopen, no needless rescan) | ✅ Done |
| Automatic crash reports (session detection, ZIP, save/share/delete) | ✅ Done |
| Touch HUD / on-screen controls | ❌ Does not exist yet |
| Wine (Windows API) | ❌ Not integrated |
| Box86 / Box64 (x86 → ARM64) | ❌ Not integrated |
| Rootless container / glibc userland | ❌ Not integrated |
| Graphics translation | ❌ Not integrated |
| Audio bridge | ❌ Not integrated |
| Launching the game | ❌ Impossible until the above exist |

**There is no Windows compatibility runtime in this repository**, and this app is
**not** a reskin of Winlator: nothing here requires a separate Winlator install.
The runtime will be built into this APK in later milestones.

---

## Requirements

* Android **8.0 (API 26)** or newer — the same floor Winlator uses.
* `arm64-v8a` device for any future runtime work (the launcher itself builds for
  all ABIs today).
* To build from source: JDK **17**. Gradle and the Android SDK are handled by the
  wrapper and by CI.

---

## Technical choices

| Choice | Value | Why |
| --- | --- | --- |
| Language | Kotlin | Stated requirement; null-safety matters for storage scanning |
| Android Gradle Plugin | 8.13.0 | Needs Gradle ≥ 8.13 and JDK 17 |
| Gradle | 8.13.0 (wrapper committed) | Pinned, so CI and local builds match |
| `minSdk` | 26 | Android 8.0, the practical floor for an ARM64 userland |
| `targetSdk` / `compileSdk` | 36 | Current stable |
| Java | 17 | Required by AGP 8.13 |
| Native | NDK 27 (27.0.12077973), CMake 3.22.1 | Needed to prove bundled native binaries can run (see below) |
| UI | XML layouts + custom drawables on AppCompat | Full control of the theme; no stock Material look |
| Dependencies | `androidx.appcompat`, `androidx.core-ktx`, `androidx.documentfile`, JUnit | Nothing else is needed yet |

---

## Project structure

```
.
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/
│       │   │   ├── CMakeLists.txt               # builds the probe as libnative_probe.so
│       │   │   └── native_probe.c               # capability check, incl. the W^X test
│       │   ├── java/com/bloodline/android/
│       │   │   ├── BloodlinesApplication.kt     # forces night mode
│       │   │   ├── diagnostics/
│       │   │   │   ├── SessionTracker.kt        # marker file: was the last exit clean?
│       │   │   │   ├── LogcatRecorder.kt        # continuous capture of this app's log
│       │   │   │   ├── DeviceInfo.kt / GpuInfo.kt # hardware, Android, GPU facts
│       │   │   │   ├── ReportBuilder.kt         # the readable text inside a report
│       │   │   │   ├── ReportArchive.kt         # writes, rotates and deletes ZIP reports
│       │   │   │   └── DiagnosticsManager.kt    # ties the above together
│       │   │   ├── game/
│       │   │   │   ├── GameInstall.kt           # what a detected install is
│       │   │   │   ├── GameInstallScanner.kt    # read-only, depth-limited scan
│       │   │   │   ├── InstallScan.kt           # real stages and counts, no fake percentages
│       │   │   │   └── GameInstallRepository.kt # background scans + remembered results
│       │   │   ├── runtime/
│       │   │   │   ├── NativeProbe.kt           # runs + interprets the native probe
│       │   │   │   ├── NativeProbeCache.kt      # the device answer, not a session answer
│       │   │   │   ├── RuntimeComponent.kt      # status model
│       │   │   │   ├── RuntimeRegistry.kt       # catalogue of the whole stack
│       │   │   │   └── LaunchReadiness.kt       # the launch gate
│       │   │   ├── settings/
│       │   │   │   ├── SettingsModels.kt        # every setting and its values
│       │   │   │   ├── SettingsStore.kt         # persistence (SharedPreferences / in-memory)
│       │   │   │   ├── SettingsRepository.kt    # typed access, defaults, listeners
│       │   │   │   ├── SettingsApplier.kt       # what can be applied right now
│       │   │   │   ├── UiScaleApplier.kt        # context wrapping for the UI scale
│       │   │   │   ├── FpsMeter.kt              # counts real Choreographer frames
│       │   │   │   └── AudioVolumeController.kt # the real Android music-stream volume
│       │   │   ├── storage/
│       │   │   │   ├── StorageLocations.kt      # app-specific folders + the picked folder
│       │   │   │   └── InstallMetadata.kt       # the remembered scan result
│       │   │   └── ui/
│       │   │       ├── MainActivity.kt          # the launcher
│       │   │       └── SettingsActivity.kt      # the settings screen
│       │   └── res/                             # theme, colours, drawables, layouts
│       └── test/java/com/bloodline/android/     # JVM unit tests
├── gradle/libs.versions.toml                    # single source of dependency versions
├── .github/workflows/android.yml                # build, test, lint, upload APK
├── LICENSE                                      # GPL-3.0
└── NOTICE                                       # third-party software credits
```

The runtime package is deliberately **modular**: `RuntimeRegistry` lists Wine,
the CPU translator, the container, graphics, audio and storage as independent
components with an `IntegrationStatus` each. A component can be built, swapped
or removed without touching the others or the UI, which only reads the registry.

---

## Building

```bash
./gradlew :app:assembleDebug     # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # JVM unit tests
./gradlew :app:lintDebug         # Android lint
```

You do **not** need to build anything locally to get an APK. Every push and pull
request is built by GitHub Actions — see below.

> **Wrapper note.** If `./gradlew` ever fails while downloading Gradle, check
> `gradle/wrapper/gradle-wrapper.properties`. Gradle omits the trailing `.0` in
> distribution filenames: version 8.13 is published as `gradle-8.13-bin.zip`,
> not `gradle-8.13.0-bin.zip`.

---

## Getting the APK from GitHub Actions

1. Open the **Actions** tab of the repository.
2. Click the latest **Android CI** run (green = passed).
3. Scroll to **Artifacts** and download **`bloodlines-debug-apk`**
   (the `bloodlines-reports` artifact next to it holds the test and lint
   reports, plus the full Gradle logs).
4. Unzip and install `app-debug.apk` on your device (enable *Install unknown
   apps* for your browser/files app when prompted).

The workflow runs on every push to `main`, on every pull request targeting
`main`, and on demand via **Run workflow**.

---

## Signing a release APK

**No keystore or password is stored in this repository.** The build reads the
signing configuration from, in order:

1. `keystore.properties` in the project root (gitignored), and/or
2. environment variables — `BLOODLINE_STORE_FILE`, `BLOODLINE_STORE_PASSWORD`,
   `BLOODLINE_KEY_ALIAS`, `BLOODLINE_KEY_PASSWORD`.

If neither is present, `./gradlew assembleRelease` still succeeds and produces
an *unsigned* APK (useful for checking that the build works, not installable).

For CI, store the keystore as a base64 secret and run the workflow with
**Also build and upload a signed release APK** ticked:

```bash
keytool -genkeypair -v -keystore bloodline-release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias bloodline

base64 -w0 bloodline-release.jks > keystore.b64

gh secret set BLOODLINE_KEYSTORE_BASE64      < keystore.b64
gh secret set BLOODLINE_KEY_ALIAS           -b "bloodline"
gh secret set BLOODLINE_STORE_PASSWORD      -b "<store password>"
gh secret set BLOODLINE_KEY_PASSWORD        -b "<key password>"
```

> ⚠️ **A signing key that has ever been committed must be treated as
> compromised.** Earlier work on this repository committed a keystore *and* its
> password. If that key is ever used for a public release, generate a fresh one
> with the command above and update the secrets — anyone with repository access
> can otherwise sign apps with that identity.

---

## Game files

**The game is not included, and this project will never include it.**

* Supply your own legally owned copy (retail CD/DVD, GOG or Steam installation).
* The launcher only ever *reads* the files you provide; it never downloads,
  patches or redistributes them.
* Expected layout, next to each other:

  ```
  <import folder>/
  ├── Vampire.exe
  ├── Vampire/            # Source engine data folder — required
  └── Unofficial_Patch/   # optional, strongly recommended for a 2004 game
  ```

* Two ways to point the launcher at your copy:

  1. **Pick the folder** (recommended) — press **Import Game Files** and choose
     the folder that contains `Vampire.exe`. Android grants the launcher
     persistent read access to that folder and **nothing is copied**: the game is
     read where it already is, which avoids duplicating several gigabytes.
  2. **Copy it into app storage** — put that structure in
     `Android/data/com.bloodline.android/files/Bloodlines` with any file
     manager. The launcher scans that folder automatically.

  Either way the launcher only reads. It never modifies, patches or moves your
  files, and clearing the app's data just forgets the link.

---

## Runtime architecture — feasibility research

The goal is a self-contained APK with its own Windows compatibility
environment. These are the findings that shape the plan.

| Component | Plan | Licence | Finding |
| --- | --- | --- | --- |
| Wine | Wine 10.x built for Android | LGPL-2.1+ | Wine 10.0 shipped full ARM64EC support, so x86-64 Windows code can run on ARM64 with only the application code emulated [1](https://ostechnix.com/wine-10-0-released/). Upstream Wine's own Android builds historically stopped at 32-bit ARM, so an Android arm64 build has to be produced as part of this project. |
| CPU translation | **Box86**, not Box64 | MIT | Bloodlines is a **32-bit** DirectX 9 Source-engine game [2](https://www.pcgamingwiki.com/wiki/Vampire:_The_Masquerade_-_Bloodlines), so `Vampire.exe` needs **Box86** in a 32-bit Wine prefix. Box64 alone cannot run it. (CodeWeavers reached the same conclusion and chose FEX-Emu over Box64 for x86-64 on ARM64 because Box64 has not implemented ARM64EC [3](https://www.codeweavers.com/blog/mjohnson/2025/11/6/twist-our-arm64-heres-the-latest-crossover-preview).) |
| Container | glibc userland + PRoot | GPL-2.0 | Android 10+ enforces W^X: files in the writable app data directory cannot be executed [4](https://chaosgoo.com/en/android-run-executable-binary-bypass/). The standard, rootless workaround is to ship every executable as `lib<name>.so` in the APK's `jniLibs`, where the package installer extracts it into the executable, read-only `nativeLibraryDir`. `android:extractNativeLibs="true"` is already set in the manifest for this. |
| Graphics | VirGL + WineD3D | MIT / LGPL | Turnip is an **Adreno-only** Vulkan driver, so it is useless on Mali [5](https://brunodev85-winlator.mintlify.app/introduction). PanVK (Mesa's Mali Vulkan driver) lists Mali-G52 as Bifrost with Vulkan 1.3 [6](https://docs.mesa3d.org/drivers/panfrost.html), but independent Android testing on a Mali-G52 still shows D3D9 failing while OpenGL paths work [7](https://github.com/zolotvok/panvk-kbase-android). Because the game is DirectX 9, **WineD3D over VirGL (OpenGL)** is the realistic first target, exactly the approach taken by the Mali-focused Winlator builds. |
| Audio | Wine ALSA → AAudio / OpenSL ES | LGPL / Apache | Bloodlines uses DirectSound3D. WineD3D's ALSA backend maps onto a small bridge to AAudio; no Android-specific blocker identified, but nothing is written yet. |
| Storage | App-specific external storage | — | Chosen because it needs no storage permission and works under Android 11+ scoped storage. SAF import comes later. |

**Native hosting is now proven, not assumed.** A C executable built with the NDK
is renamed `libnative_probe.so`, packaged in the APK, extracted by Android into
the executable `nativeLibraryDir`, and run by the launcher at startup. CI blocks
the build if it goes missing. The same probe copies itself into writable app
storage and tries to execute the copy, so each device reports whether W^X blocks
it (`exec-from-app-data: DENIED` is the expected result on Android 10+). Tap the
*Native execution* row in the launcher to see the raw output.

**Conclusion: feasible, but not quick.** Every layer above exists as open source
and is already proven by Winlator (LGPL-2.1) on Adreno devices. The hard part is
Mali-G52: the Turnip/DXVK fast path is unavailable, so the project has to rely on
the slower VirGL/WineD3D OpenGL path and then measure whether a Mali-G52 can hold
a playable frame rate. **Nobody should expect the game to be playable in this
milestone, or the next.**

---

## Settings

`docs/SETTINGS.md` is the full map: every setting, what it controls, and whether
it actually works. The short version:

* Everything is persisted through `SettingsRepository`, so a setting survives
  restarting the app and the device.
* A control is only enabled when it does something. Immersive mode, orientation,
  keep-awake, window brightness, UI scale, the frame-rate overlay and master
  volume all take effect immediately.
* The frame-rate overlay counts real `Choreographer` frames, so it shows the
  refresh rate Android is actually delivering rather than a stored number.
  Master volume drives the real `STREAM_MUSIC` volume.
* Frame limiting, resolution scaling, VSync, frame pacing, the renderer choice
  and the audio backend are stored but shown **disabled with the reason**: they
  cannot do anything until the Windows compatibility runtime exists, and a
  switch that pretends otherwise is worse than no switch.
* The touch HUD (joystick, quick slots, button layout, transparency) does not
  exist yet, so there is nothing to customise. Those controls arrive with the
  input layer milestone rather than as editors wired to nothing.

The launcher's appearance is unchanged; the settings screen is reached from the
*Settings* button under the launch button.

---

## Importing game files

Scanning a game folder over the Storage Access Framework costs one binder call
per entry, and an install holds thousands of files. It therefore runs on a
background thread and reports what it is genuinely doing: the current stage plus
the number of entries actually examined. There is no percentage, because the
total is unknowable until the walk ends.

The result is remembered (`InstallMetadata`), so reopening the launcher shows
the verified status instantly instead of walking the tree again. A rescan
happens only when there is a reason: nothing remembered yet, the result is older
than 24 hours, the picked folder changed, the app was updated, or you pressed
*Re-scan for game files* in Settings. A scan that found nothing is only
repeated when it was cheap, so an expensive one cannot make reopening slow
again. No game file is ever written, moved or deleted.

---

## Diagnostics and crash reports

The launcher notices when it did not get to close properly:

* A marker file is written when a session starts and removed only when you close
  the app deliberately. Android gives no "app exited" callback and runs nothing
  at all when a process is killed, so a surviving marker is the only reliable
  evidence of an unclean exit. Rotating the screen is not a new session.
* A report is a dated ZIP in
  `Android/data/com.bloodline.android/files/diagnostics/reports/`, containing a
  readable `summary.txt`, the device facts (including the GPU, read from an EGL
  context), the runtime statuses, your settings, the install status, the session
  record, the native probe output and the captured logs.
* Logs are captured continuously, because since Android 7 an app can only read
  its own log lines and a dead process cannot be asked for anything afterwards.
* The newest ten reports are kept. The launcher card can generate one on demand,
  save it wherever you like, share it through Android's share sheet, or delete
  it.
* Only this app's own output goes in: no account, no password, no personal
  files. The game folder path is left out on purpose, because a path can contain
  your name.
* Closing the app normally produces no report.

---

## Roadmap

1. **Milestone 1 — foundation** *(done)*: Android module, launcher UI,
   storage detection, CI, documentation.
2. **Milestone 2 — importer** *(done)*: pick the game folder through the Storage
   Access Framework, verify the expected layout, persist the access grant, and
   read the files in place instead of copying them.
3. **Milestone 3 — native hosting** *(done)*: the NDK builds a probe executable
   that ships as `libnative_probe.so`; the app runs it from the APK's library
   folder and reports the measured ABI, API level and W^X verdict. This is the
   mechanism Wine and Box86 will be launched through.
4. **Milestone 4 — container**: glibc userland + PRoot (or a leaner chroot-free
   approach) booting inside the app.
5. **Milestone 5 — Wine + Box86**: Wine arm64 build with a 32-bit prefix, Box86
   translating `Vampire.exe`.
6. **Milestone 6 — graphics and audio**: VirGL + WineD3D, then the audio bridge.
7. **Milestone 7 — input and polish**: touch/gamepad mapping, on-screen controls,
   per-title settings, performance presets.

Each milestone is expected to land as its own set of commits with the CI green.

---

## Testing

Unit tests are plain JVM tests (no emulator needed) and run on every CI build:

* `GameInstallScannerTest` — detection of `Vampire.exe`, case-insensitive
  matching, depth limit, hidden folders, unreadable/missing roots, revoked
  access, complete-over-partial preference, and detection inside an external
  (Storage Access Framework) folder using a fake storage backend.
* `LaunchReadinessTest` — the launch gate: blocked with no files, blocked with an
  incomplete install, blocked until **every** runtime component is ready, and
  allowed only when both conditions hold.
* `NativeProbeTest` — how probe output is interpreted: success marker, ABI/API
  summary, and the W^X verdict. (Launching the probe itself needs a device.)
* `RuntimeRegistryTest` — unique component ids, full stack coverage, a
  deliberate guard that fails if any component claims to be `READY` before it is
  actually integrated, and that a measured status flows into the registry.
* `SettingsRepositoryTest` — defaults, persistence across repository instances,
  clamped ranges, corrupted stored values falling back to defaults, change
  listeners, reset, and that the experimental renderer is never the default.
* `GameInstallRepositoryTest` — that a fresh cached result skips the scan
  entirely, that a stale one, a different folder or a new app version forces a
  rescan, that only one scan runs at a time, that progress reports real counts,
  that a failed scan is reported with its reason, and that an expensive
  not-found scan is not repeated on every start.
* `SessionTrackerTest` — a clean close leaves nothing behind, a crash is
  classified as a crash, a background stop is *not* called a crash, and a
  rotation of the screen is not a new session.
* `ReportArchiveTest` — the archive is a real ZIP the user can open, logs land
  under `logs/`, the classification survives in the file name, old reports are
  rotated away, and delete works.
* `ReportBuilderTest` — the wording of a report: a background stop is never
  called a crash, a report made on request says so, and durations read well.

CI additionally fails the build if `libnative_probe.so` is not packaged under
`lib/<abi>/` in the APK, because without it no native runtime can ever start.

---

## Licence and trademarks

* This project: **GPL-3.0** — see [LICENSE](LICENSE).
* Third-party components that will be embedded, and their licences, are listed in
  [NOTICE](NOTICE).
* *Vampire: The Masquerade – Bloodlines* is a trademark of Paradox Interactive
  AB; *Vampire: The Masquerade* is a trademark of White Wolf Publishing. This is
  an unaffiliated fan project. No game assets, executable or data files are
  included in this repository.
