# Settings map and implementation plan

Companion to `README.md`. Documents every setting the launcher has, what it
controls, whether it actually works, and what is planned.

**Status legend**

| Mark | Meaning |
| --- | --- |
| ✅ **WORKS** | Implemented and has a real, observable effect today |
| ⏳ **PENDING RUNTIME** | Stored and validated, but cannot do anything until Wine/Box86/graphics exist. Shown disabled with the reason, never as a working switch |
| ❌ **UNSUPPORTED** | Cannot be implemented without a component that does not exist. Not faked |

Device context: TECNO CK6, Helio G85, Mali-G52 MC2, Android 14 (API 34), arm64-v8a.

---

## 1. What exists today (before this work)

| Setting | Current implementation | Controls | Works? |
| --- | --- | --- | --- |
| Game folder | `GameFolderStore` (SAF tree URI in SharedPreferences) | Where files are read from | ✅ |
| Game detection | `GameInstallScanner` (depth 2, case-insensitive) | Installation status card | ✅ |
| Native probe | `NativeProbe` (runs `libnative_probe.so`) | "Native execution" status | ✅ |
| Renderer / DirectX wrapper / Resolution | Hard-coded strings in `strings.xml`, shown in `view_config_row` | **Nothing** | ❌ display-only |
| Audio rows | Hard-coded strings | **Nothing** | ❌ display-only |
| Everything else | — | — | ❌ does not exist |

There is **no HUD system** in this project: no joystick, quick slots, camera
sensitivity, button layout or transparency code exists anywhere in the tree.
Building those controls now would produce switches wired to nothing, which is
exactly what must be avoided — see §3.E.

---

## 2. Settings map after implementation

### A. Display and screen

Two different numbers are kept apart here, because conflating them is what
makes a "resolution" slider meaningless on a phone:

- **display resolution** is the size the game surface is *presented* at — what
  the Windows desktop inside the container is told the screen is.
- **internal render resolution** is the fraction of that size the game actually
  renders, before the GPU scales it back up.

| Setting | Values | Status | Effect |
| --- | --- | --- | --- |
| Detected display | read-only | ✅ WORKS | Measured from the device: panel size and refresh rate, read through `Display#getRealMetrics`. Never estimated |
| Display resolution | Native / 640 × 360 / 854 × 480 / 960 × 540 / 1280 × 720 / 1600 × 900 / Custom | ⏳ PENDING (value accepted) | Stored for the runtime. The screen shows the computed result against the measured display, so the choice can be judged |
| Custom resolution | 320–3840 × 240–2160 | ⏳ PENDING (value accepted) | Shown when the resolution above is Custom. Clamped, made even, and reduced further if this screen cannot show it |
| Internal render resolution | 100 / 90 / 85 / 80 / 75 / 70 / 60 / 50 % / Custom | ⏳ PENDING (value accepted) | The fraction of the presented size that is actually rendered |
| Custom scaling | 25–100 % | ⏳ PENDING (value accepted) | Shown when the scaling above is Custom |
| Resulting render size | read-only | ✅ WORKS (computed) | `presented → rendered`, worked out from the measured display. Says when a choice had to be reduced to fit |
| Aspect ratio | Preserve / Fit to screen / Stretch to fill | ⏳ PENDING | How a frame whose shape differs from the display is fitted |
| Letterbox / pillarbox | on / off | ⏳ PENDING | Bars instead of cropping when the frame cannot cover the screen |
| Fullscreen scaling | on / off | ⏳ PENDING | Scale a smaller frame up to fill the display, rather than showing it 1:1 |
| Reset graphics settings | action | ✅ WORKS | Forgets the display, scaling, fitting and renderer choices. Real work, so it is not greyed out |
| Immersive / fullscreen | on / off | ✅ WORKS | Hides system bars on the launcher via `WindowCompat`; will apply to the game surface |
| Keep screen awake | on / off | ✅ WORKS | `FLAG_KEEP_SCREEN_ON` while the launcher (later the game) is foreground |
| UI scale | 85 / 100 / 115 % | ✅ WORKS | Scales launcher text and layout together via a scaled density (see §5) |
| Screen brightness | Follow system / custom | ✅ WORKS | Window brightness while the launcher is foreground only (Android forbids changing system brightness) |

**Orientation is no longer a setting.** It was removed, because it only ever
controlled the launcher's own screens and was a single global preference that
could contradict the one orientation rule that is *not* a preference: the game
is a landscape Windows title and has to run landscape. See §4 below.

### B. FPS and performance

| Setting | Values | Status | Effect |
| --- | --- | --- | --- |
| FPS limit | Uncapped / 60 / 45 / 40 / 30 / 20 | ⏳ PENDING | Needs a frame-pacing loop in the runtime. Not a cosmetic number: stored but inert |
| VSync | on / off | ⏳ PENDING | Needs the graphics backend |
| Frame pacing | on / off | ⏳ PENDING | Needs the runtime frame loop |
| FPS counter overlay | on / off | ✅ WORKS | `Choreographer` measures the real on-screen frame rate of the launcher surface (and the game surface once it exists) |
| Performance preset | Safe / Balanced / Aggressive | ⏳ PENDING | Bundles Box86/DXVK environment presets; applied when the runtime lands |

No FPS setting can change gameplay today: **there is no game loop.** Anything
claiming otherwise would be a decorative switch.

### C. Renderer and graphics

| Setting | Values | Status | Effect |
| --- | --- | --- | --- |
| Renderer selection | WineD3D + VirGL (planned) / PanVK + DXVK (experimental) | ⏳ PENDING | Selection is stored and validated; nothing is loaded yet |
| Texture / shadow / shader quality | — | ❌ UNSUPPORTED | WineD3D exposes no such runtime knobs. Will be revisited per backend |
| GPU identification | read-only | ✅ WORKS | `GpuInfo` reads the GL renderer, vendor, GLSL and EGL version from an EGL context — the data needed to judge Mali-G52 support |

**Renderer policy (as requested):** the existing path is kept as the reliable
fallback. PanVK + DXVK is recorded as *experimental* and will only become
selectable after it has been tested on the TECNO CK6 and proven to initialise.
Nothing is replaced before that.

### D. Audio

| Setting | Values | Status | Effect |
| --- | --- | --- | --- |
| Master volume | 0–100 | ✅ WORKS | Sets the Android `STREAM_MUSIC` volume (real, system-level) |
| Music / SFX / Dialogue volume | 0–100 | ❌ UNSUPPORTED | These are separate channels inside the game's own mixer. Splitting them needs the runtime |
| Audio backend | Auto / AAudio / OpenSL ES | ⏳ PENDING | Applied by the audio bridge once it exists |

### E. Controls / HUD

| Setting | Status | Note |
| --- | --- | --- |
| Joystick size & position, camera sensitivity, button layout, transparency, left/right-handed, nine quick slots, HUD profiles | ❌ NOT IMPLEMENTED — no HUD exists | Building the editors now yields controls wired to nothing. Deferred to the milestone that adds the input layer, so every control has a real target |

### F. Runtime and storage

| Setting | Status | Effect |
| --- | --- | --- |
| Game installation folder + status | ✅ WORKS | Pick, verify, persist; metadata cached for fast startup |
| Runtime version | ✅ WORKS | Build/version + component statuses from the registry |
| Selected renderer | ⏳ PENDING | Stored, shown under *Renderer & Graphics* with the reason it is disabled |
| Cached scan data | ✅ WORKS | Settings → *Clear cached scan data* forgets the last verified result; *Re-scan for game files* forces a fresh scan |
| Logs | ✅ WORKS | This app's logcat is captured continuously; the last four sessions go into a report. Not a setting: logs are an input to reports |
| Diagnostics & crash reports | ✅ WORKS | Session detection, ZIP reports, share/delete (Phase 4) |

---

## 3. Implementation status

### Phase 1 — audit (done)

`docs/SETTINGS.md` itself is the deliverable: it records what existed before
anything was built on top of it, so no setting is ever claimed to work without
evidence.

### Phase 2 — settings that work, stored honestly (done)

- `SettingsStore` (SharedPreferences, with an in-memory implementation for unit
  tests) and a typed `SettingsRepository` with safe defaults, clamping and
  change listeners.
- A **Settings** screen reachable from a *Settings* button under the launch
  button, built from the launcher's own cards, drawables and typography.
  Categories A–F, in that order.
- Only ✅ settings are enabled. ⏳ settings are visible but dimmed, cannot be
  clicked, and say what they are waiting for. ❌ items are shown as unsupported
  rather than omitted, so the plan stays visible.
- Applied immediately: immersive mode, keep-awake, window brightness, the FPS
  overlay and master volume. UI scale recreates the activity, because the scale
  is baked into the context.
- The FPS overlay counts real `Choreographer` frames, so it shows the refresh
  rate Android is actually delivering rather than a stored number.
- Master volume drives the real Android `STREAM_MUSIC` volume, seeded from the
  device the first time it is needed.

### Phase 3 — the black screen and the slow reopen (done)

**Black screen on import**

1. All scanning moved off the main thread into `GameInstallRepository`, which
   owns a single-threaded background executor.
2. Real progress: the current stage (scanning folders → checking required
   files) plus the number of directory entries actually examined. No
   percentages, because the total is unknowable until the walk ends.
3. A progress bar with the stage text and the running count, instead of a
   frozen black UI.
4. Single-flight guard plus a disabled import button, so a second import cannot
   start while one is running.
5. Errors are shown with the reason: access revoked, unreadable folder, or no
   `Vampire.exe`.
6. After a fresh scan, the card shows *Preparing runtime…* while the bundled
   native probe is still running, and hides it when the probe reports back.

**Slow reopen**

1. `InstallMetadata` remembers the location, whether the data folder is there,
   how much work the scan took, and when it was verified.
2. Startup renders straight from that cache.
3. The cache is reused while it is younger than 24 h, was produced by this
   build, and came from the same search folders.
4. A scan that found nothing is only repeated when it was cheap (< 2,000
   entries); repeating an expensive one would bring the slow reopen back.
5. `onResume` no longer rescans. Settings offers *Re-scan for game files* and
   *Clear cached scan data* for when a rescan is genuinely wanted.
6. The native probe result is cached per app version and API level: the answer
   is a property of the device, not of the session.
7. No game file is written, moved or deleted by any of this.

### Phase 4 — automatic crash reports (done)

- `SessionTracker`: a marker file is written when a session starts and removed
  only when the app is closed deliberately. Android runs nothing at all when a
  process is killed, so a surviving marker is the only reliable evidence of an
  unclean exit. Recreating the Activity (rotation, theme change) is not a new
  session, so it never produces a report.
- Classification: **crash** (the app was in front), **background stop**
  (Android reclaimed a backgrounded process — explicitly *not* called a crash),
  or **unclean exit** when there is no better answer.
- `LogcatRecorder` captures this app's own log continuously. It has to: since
  Android 7 an app can only read its own log lines, and a dead process cannot
  be asked for anything afterwards. The last four sessions are kept.
- `DeviceInfo` records hardware, Android version, ABIs, memory and screen. The
  GPU is read from an EGL context on a background thread, which is the only way
  Android exposes it.
- `ReportArchive` writes a dated ZIP to
  `Android/data/com.bloodline.android/files/diagnostics/reports/` containing
  `summary.txt`, `device.txt`, `runtime.txt`, `settings.txt`, `install.txt`,
  `session.txt`, `native-probe.txt` and `logs/`. The newest ten are kept.
- The launcher card shows the latest report with what it was, when it was made
  and how big it is, plus **Generate**, **Save** (SAF), **Share**
  (FileProvider + `ACTION_SEND`) and **Delete**.
- Privacy: only this app's own output. The game folder path is deliberately
  left out of reports because a path can contain the owner's name.

### Phase 5 — verification (yours, on the TECNO CK6)

| Check | How to confirm |
| --- | --- |
| APK builds | GitHub Actions on every push; download `bloodlines-debug-apk` |
| No unexplained black screen | Import a folder with the game in it; the card must show the stage and a climbing entry count the whole time |
| Installation metadata persists | Close and reopen: the status is shown instantly, and the caption says when it was verified |
| No needless rescan | Reopen and watch: no progress bar appears on an ordinary start |
| Settings persist | Change immersive / UI scale / volume / display resolution, restart, confirm they held |
| Launcher rotates, game does not have to | Rotate the phone: the launcher follows. Orientation is no longer pinned by a setting |
| FPS limits affect gameplay | ⏳ Cannot be checked until the runtime exists; the FPS overlay is checkable now |
| Resolution changes the rendered size | ⏳ Cannot be checked until the runtime exists. What *is* checkable now: the Detected display row shows this phone's real panel, and the Resulting render size row follows the resolution and scaling choices |
| Renderer selection and safe fallback | ⏳ Same; the safe renderer is the default and the Vulkan path is marked experimental |
| HUD functional | ❌ No HUD exists yet |
| Crash reports after an unexpected exit | Force-stop the app from developer options, reopen: a report is written automatically |
| Reports savable and shareable | *Save* writes the ZIP where you choose; *Share* opens Android's share sheet |
| Normal shutdown produces no report | Back out of the app, reopen: the card still says *No reports yet* |

---

## 4. Orientation, in two phases

`OrientationController` (pure Kotlin, unit tested) owns the rule;
`OrientationApplier` is the only place it becomes an `ActivityInfo` constant.

| Phase | Request | Why |
| --- | --- | --- |
| `LAUNCHER` | `FOLLOW_DEVICE` | The launcher and settings are usable either way up, so rotation is left to the device |
| `GAMEPLAY` | `SENSOR_LANDSCAPE` | A requirement of the game, not a preference |

`MainActivity.enterGameplay()` and `exitGameplay()` are the seam the future
runtime host will call: entering forces landscape and remembers what the
launcher was using, leaving puts it back. Nothing calls them yet, and nothing
claims otherwise — the `screenOrientation` attribute has been removed from both
activities in the manifest so neither is pinned any more.

## 5. Interface scaling

`UiScaleApplier` changes **only the density**. It used to change `fontScale` as
well, which multiplied: `scaledDensity = density × fontScale`, so at 115 % text
grew by 1.32× while every dp box around it grew by 1.15×, and labels overflowed
their own rows. Leaving `fontScale` alone scales text and spacing together and
keeps whatever font size the device is set to.

