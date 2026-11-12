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

| Setting | Values | Status | Effect |
| --- | --- | --- | --- |
| Native resolution | on / off | ⏳ PENDING | Render target for the game surface. Stored; applied by the graphics backend once it exists |
| Resolution scaling | 100 / 75 / 50 % | ⏳ PENDING | Scales the game render target, not the launcher UI |
| Aspect ratio | Preserve / Fit / Stretch | ⏳ PENDING | Game surface presentation mode |
| Immersive / fullscreen | on / off | ✅ WORKS | Hides system bars on the launcher via `WindowCompat`; will apply to the game surface |
| Screen orientation | Portrait / Landscape / Follow device | ✅ WORKS | `Activity.requestedOrientation` |
| Keep screen awake | on / off | ✅ WORKS | `FLAG_KEEP_SCREEN_ON` while the launcher (later the game) is foreground |
| UI scale | 85 / 100 / 115 % | ✅ WORKS | Scales launcher text and text-driven layout via a scaled `Configuration` |
| Screen brightness | Follow system / custom | ✅ WORKS | Window brightness while the launcher is foreground only (Android forbids changing system brightness) |

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
| GPU identification | read-only | ✅ WORKS | The native probe reports EGL/GL renderer, vendor and version — the data needed to judge Mali-G52 support |

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
| Selected renderer | ⏳ PENDING | Stored |
| Cache management | ✅ WORKS | Clears caches; reports size before/after |
| Log management | ✅ WORKS | Keeps/clears captured logs |
| Diagnostics & crash reports | ✅ WORKS | Session detection, ZIP reports, share/delete (Phase 4) |

---

## 3. Implementation plan

### Phase 3 — fix the two reported bugs (highest priority)

**Black screen on import**

1. Move all scanning off the main thread into a single-threaded background
   executor (`GameInstallRepository`).
2. Report **real** progress: stage (`Scanning → Verifying → Preparing`) plus the
   number of directory entries actually examined. No invented percentages.
3. Show a proper loading overlay (card + indeterminate bar + stage text) instead
   of a frozen, black UI.
4. Single-flight guard so a second import cannot start while one is running;
   the button is disabled during the operation.
5. Errors are shown with the reason (unreadable folder, missing `Vampire.exe`,
   missing `Vampire/` data folder, access revoked).

**Slow startup**

1. Persist validated installation metadata (kind, reference, display path, data
   folder present, entries examined, validation timestamp) in SharedPreferences.
2. On startup, render instantly from that cache — no I/O beyond one preference
   read.
3. Re-validate in the background only when: there is no cache, the cache is
   stale (>24 h), the folder URI changed, or the user asks for a refresh.
4. `onResume` no longer triggers a rescan; it only re-renders.
5. The native probe (already background) is skipped on repeat starts when its
   result is still cached for this process/app version.
6. No game file is ever written, moved or deleted by any of this.

### Phase 2 — settings that work, stored honestly

- `SettingsStore` (SharedPreferences, with an in-memory implementation for
  unit tests) + typed `SettingsRepository` with defaults and change listeners.
- A **Settings** screen reachable from a gear button in the launcher header,
  built from the same cards, drawables and typography as the launcher.
- Only ✅ settings are enabled. ⏳ settings are visible, disabled, and labelled
  with what they are waiting for. ❌ settings are shown as unsupported rather
  than omitted, so the plan stays visible.
- Applied immediately where safe (orientation, immersive, keep-awake,
  brightness, FPS overlay); UI scale recreates the activity.

### Phase 4 — diagnostics

- `SessionTracker`: marker file written at process start, removed on a clean
  finish. Surviving marker ⇒ previous session did not end cleanly.
- Classification: **crash** (recorded uncaught exception + stack trace),
  **killed while backgrounded** (process died after `onStop`), or
  **unclean exit**. A deliberate close removes the marker, so no false reports.
- `DiagnosticsCollector`: logcat of this app's own process (the only logs
  Android lets an app read), device/ABI/SDK info, GPU info from the native
  probe, settings snapshot, runtime component statuses, session record.
- `ReportArchive`: ZIP in `Android/data/<package>/files/diagnostics/` with a
  readable `summary.txt` plus per-topic files. Keeps the newest 10, deletes the
  rest, and can be deleted on demand.
- Launcher card listing the latest report (date + classification) with
  **Generate**, **Share** (FileProvider + `ACTION_SEND`) and **Delete**.
- Privacy: only this app's own data — no contacts, no personal files, no
  credentials.

### Phase 5 — verification

| Check | How |
| --- | --- |
| APK builds | GitHub Actions on every push/PR (no local Gradle on the phone) |
| Settings persistence, cache logic, session tracking, report naming/rotation | JVM unit tests |
| Everything marked ✅ | Requires on-device confirmation on the TECNO CK6 |

I have no device or emulator in this environment, so the ✅ items are verified
by code and unit tests only — the on-device pass is yours. Each ✅ item is
listed with how to confirm it in §2 above.
