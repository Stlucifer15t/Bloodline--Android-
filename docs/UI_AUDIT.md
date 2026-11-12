# Launcher UI audit

Findings from reading the launcher, the settings screen, the layouts, the theme
and the display code before changing anything. Reference device: **TECNO CK6,
Android 14, Helio G85, Mali-G52 MC2, 8 GB RAM** — a 720 × 1600 class screen at
~320 dpi, which is where every width-related problem below shows up first.

## 1. Text and layout problems

| # | Problem | Where | Cause | Fix |
| --- | --- | --- | --- | --- |
| 1 | **Button labels can be cut off** | Diagnostics card: four buttons (`GENERATE`, `SAVE`, `SHARE`, `DELETE`) sharing one row | Four `0dp`/weight-1 buttons on a 328 dp content width leaves ~76 dp each; `android:singleLine="true"` then *silently truncates* rather than wrapping | Two-by-two grid, `wrap_content` height with a `minHeight`, padding instead of a fixed 40 dp |
| 2 | **Fixed button heights** | `activity_main.xml`, `activity_settings.xml` (46 dp), diagnostics buttons (40 dp) | A fixed height cannot grow when the system font scale or the in-app UI scale makes the label taller | `wrap_content` + `minHeight` + vertical padding |
| 3 | **Long values squeeze their label** | `view_config_row.xml` — label `weight=1`, value `wrap_content` | An unbounded value (the device-capability summary) takes the whole row and the label collapses to nothing | Weights on both columns (0.6 / 0.4), value wraps inside its column |
| 4 | **Long values have nowhere to wrap** | The same row, used for *Device capability* | A single-line value in a horizontal row cannot wrap | A dedicated `view_info_row.xml`: label above, value below, full width |
| 5 | **Chips can squeeze the text column** | `view_status_row.xml` | Chip is `wrap_content` and unbounded; `NOT INTEGRATED` at 9.5 sp caps is ~70 dp | `maxLines` on the chip and a bounded text column |
| 6 | **Brand title is a fixed 35 sp** | `activity_main.xml` | On a narrow screen with a wide glyph run, `BLOODLINES` with 0.32 letter-spacing is close to the edge, and at 115 % UI scale it is over it | `autoSizeTextType="uniform"` with min/max/step, so it shrinks instead of overflowing |
| 7 | **UI scale double-scales text** | `UiScaleApplier` | It sets *both* `fontScale` and `densityDpi`. Since `scaledDensity = density × fontScale`, text grows by `1.15 × 1.15 = 1.32` while dp dimensions grow by `1.15` — text then overflows every fixed-height container | Scale density only; the copied configuration already carries the system font scale |
| 8 | **Spacing is hand-picked per file** | Every layout | Values like 6/8/10/11/12/14/16/20/26/30 dp appear ad hoc, so sections do not line up | `res/values/dimens.xml` with one spacing scale used everywhere |
| 9 | **Icons can push text out** | Rows have no icon slot today, so any icon added later would compete with the text | — | Rows get a fixed 20 dp icon slot with `layout_marginEnd`, and the text column takes the remaining weight |

## 2. Visual design

* Every section is a 14 dp rounded card with a 1 dp stroke. Six of them stacked
  reads as a stack of boxes, not as a composed screen. → Flatter panels (6 dp),
  hairline dividers, and section headers that carry the hierarchy.
* Typography is flat: section titles, row titles and values are all within 3 sp
  of each other. → A real hierarchy: 11 sp small-caps headers, 14 sp row titles,
  11.5 sp details, 12 sp monospace values.
* No icons anywhere except the emblem. → Six small, meaningful 16 dp vector
  icons, one per settings section.
* Accent colour is used inconsistently (buttons, one card stroke, one divider).
  → One accent rule: deep red for actions and headers only.

## 3. Orientation

* A single `ScreenOrientation` setting drives `Activity.requestedOrientation`
  on the launcher. It only ever affects the launcher, which is exactly the
  "setting with no meaningful purpose" that must go.
* The manifest additionally locks both activities to `portrait`, so nothing can
  rotate at all.
* The game is a landscape PC title and must *always* run in landscape, whatever
  the launcher is doing. There is no code for that yet, and nothing in the
  architecture separates the two.

→ Remove the setting; let the launcher follow the device; introduce an
orientation policy with two phases (launcher / gameplay) that the future
runtime host will drive.

## 4. Display settings

* `ResolutionScale` offers only 100 / 75 / 50 % and a boolean "native
  resolution". There is no notion of a render *target*, no custom value, and no
  connection to the actual screen.
* Nothing reports the device's real display size, so the user cannot tell what
  "native" means or whether a chosen resolution is above what the screen can
  show.
* "Display resolution" and "internal game render resolution" are not
  distinguished anywhere.

## 5. Settings organisation

Sections were Display / Performance / Renderer / Audio / Controls / Runtime &
Storage — the last two mix launcher housekeeping with future game controls.
→ General / Display / Performance / Audio / Input / Diagnostics, with
launcher-only and runtime-bound settings kept apart inside each section.

## 6. What was kept

Brand header and emblem, the status-dot + chip pattern, the dark near-black
palette with deep red accents, the existing functionality behind every row
(game detection, cached scans, diagnostics, probes, volume, immersive mode,
keep-awake, brightness, UI scale, FPS overlay). Nothing working was removed to
make the refactor easier.

---

## 7. What was actually changed

Every row of §1 fixed, in priority order. Two of the planned fixes were
changed once the layouts were open, and both changes are noted below.

| # | Fix applied |
| --- | --- |
| 1 | Diagnostics actions are now two rows of two (`Widget.Bloodlines.Button.Compact`), `wrap_content` high. Four across left ~64 dp each on a 360 dp phone, and `singleLine` turned the overflow into a silent truncation. |
| 2 | Every fixed button height (46 dp / 56 dp / 40 dp) is gone. Buttons are `wrap_content` with a `minHeight` floor plus padding, so a taller font scale grows the button instead of clipping its label. |
| 3 | `view_config_row` weights *both* columns (62 / 38, `weightSum="100"`). A long value now wraps inside its own share instead of collapsing the label. |
| 4 | **Changed from the plan.** Instead of a new `view_info_row.xml`, the weighted columns from #3 turned out to be enough: the value wraps inside 38 % of the row, so no extra layout type is needed. One fewer component to keep in sync. |
| 5 | **Changed from the plan.** The chip is bounded with `maxWidth` (84 dp) rather than `maxLines`: "NOT INTEGRATED" then wraps to two lines inside the chip instead of being cut with an ellipsis, which would have hidden the status. |
| 6 | The wordmark is `match_parent` with `autoSizeTextType="uniform"` between 24 sp and 30 sp, and letter-spacing reduced from 0.32 to 0.24. It shrinks on a narrow phone instead of running off the edge. |
| 7 | `UiScaleApplier` now changes the density only. `fontScale` is left alone, so text and spacing scale together and the device's own font size survives. |
| 8 | `res/values/dimens.xml` holds one spacing, size and text scale. Every layout and style references it; no raw dp or sp remains in the layouts. |
| 9 | Section headings own a fixed 20 dp icon slot (`ImageView` + 12 dp gap), and the heading text takes the remaining weight — an icon can never push text out of the panel. |

Visual design: panels are 8 dp radius with a hairline seam instead of 14 dp
boxed cards; one accent rule (deep red for headings and actions only); a real
type hierarchy; six meaningful section icons; a small lozenge ornament under
the wordmark as the one gothic detail.

Orientation: the setting is gone, the manifest pins nothing, and
`OrientationController` separates the launcher phase from the gameplay phase.

Display: the launcher's *Graphics* card, which printed a hard-coded
"1280 × 720", is now a *Display* panel whose first row is measured from the
device. `docs/SETTINGS.md` §A has the full map of what was added.
