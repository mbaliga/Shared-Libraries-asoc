# Migrating to `mbaliga/Shared-Libraries-asoc`

## `dev.aarso:crash-recovery` has moved here from `mbaliga/Hyle-Design-System`

**Old home:** `mbaliga/Hyle-Design-System`, module `:crash-recovery` (Personal-Tracker `DECISIONS.md` D-O)
**New home:** `mbaliga/Shared-Libraries-asoc`, module `:crash-recovery`
**Coordinate is unchanged:** `dev.aarso:crash-recovery`
**Version:** `1.1.0` → `1.2.0`

### Why it moved

The utility was built with **zero dependency on `:hyle`** on purpose (plain `android.widget`
views, no Compose, no Material, colours as plain `@ColorInt Int`), precisely so that apps
forbidden from depending on Hyle — Animalcules and Clackpad, under D-L — could still use it.

But leaving it inside the Hyle repo meant those same apps had to add the **entire
Hyle-Design-System submodule** to reach a module that deliberately has nothing to do with Hyle.
D-O's reasoning was sound when there was only one shared repo; this is the neutral home that
removes the contradiction.

### What you have to change

Two lines, in two files.

**1. `settings.gradle.kts`** — swap which build you composite:

```diff
-includeBuild("hyle-design-system")
+includeBuild("hyle-design-system")     // keep this ONLY if you also use dev.aarso:hyle
+includeBuild("shared-libraries")
```

If your app took the Hyle submodule *only* for crash-recovery (BOS_launcher, Clackpad and
hnm_playground all did), remove `hyle-design-system` entirely and replace the submodule:

```bash
git submodule deinit -f hyle-design-system
git rm -f hyle-design-system
git submodule add https://github.com/mbaliga/Shared-Libraries-asoc.git shared-libraries
```

**2. Your module's `build.gradle.kts`** — bump the version:

```diff
-implementation("dev.aarso:crash-recovery:1.1.0")
+implementation("dev.aarso:crash-recovery:1.2.0")
```

The `group:name` coordinate is unchanged, so Gradle's composite substitution keeps working with
no other edits. **No import changes** — the package is still `dev.aarso.crashrecovery` and the
API is source-compatible.

**3. CI** — if your checkout step lists submodule paths explicitly, add the new one. If it uses
`submodules: recursive`, nothing to do.

### What happens if you don't

`hyle-design-system` keeps a **tombstone** at `:crash-recovery` declaring the same
`dev.aarso:crash-recovery` coordinate, so composite substitution still resolves and you do *not*
get an unhelpful `Could not find dev.aarso:crash-recovery`. Instead every call site fails to
compile with a message naming this repo.

The tombstone deliberately does **not** fail at Gradle configuration time. Gradle configures
every project in an `includeBuild`, so a configuration-time failure would break consumers that
use `:hyle` and never touch crash-recovery. A compile-time error only reaches actual users.

### What changed in 1.2.0

`1.2.0` is `hyle-design-system@c586f8f` (which declared `1.1.0`) **plus** `previewIntent` /
`samplePreview` merged forward.

Those two histories had **diverged, and neither was a superset**:

| | `c586f8f` (Hyle `main`) | `33b0faa` (never merged) |
|---|---|---|
| Version declared | 1.1.0 | 1.0.0 |
| Richer recovery Activity, crash-mark drawables | ✅ | ❌ |
| `previewIntent` / `samplePreview` | ❌ | ✅ |

Android-IDE-core pinned its Hyle submodule to `33b0faa` — an unmerged branch commit — and calls
`CrashRecovery.previewIntent` from `SettingsRoom.kt`. So it compiled, but only against work that
never landed, and it was missing every later improvement on `main`. Anyone "tidying" that pin
onto `main` would have broken IDE-core's build.

`1.2.0` ends that split: it takes `main`'s evolved implementation and re-applies `previewIntent`
on top, rebuilt against the richer `CrashReport.Decoded` that `main` introduced.

Preview safety properties, pinned by tests in `CrashReportTest`:

- "PREVIEW" appears in the headline, the plain-language summary **and** the shareable full
  report, so a screenshot or an accidentally-shared preview can never be mistaken for a real crash.
- Every device/app metadata field is `null` — a preview cannot leak real device data.
- **Reset is inert** — previewing can never wipe app data.
- **Continue is inert** beyond closing the screen — previewing never relaunches the app.
- The report is synthesized, never read from disk, so previewing works with no captured crash
  and cannot consume a real pending one.

## `dev.aarso:cell-shell` — nav-bar-aware bottom zone and the optional bottom peek

**Version:** unchanged (`0.1.0`). Source-compatible; nothing to do unless you want the new stage.

### One behaviour change for every consumer: the bottom pull zone

`SpatialShell`'s bottom edge acquisition zone used to be the window's last 56dp. Edge-to-edge
hosts therefore lost most of it to the navigation bar (a 48dp 3-button bar left 8dp; gesture
navigation overlapped the home swipe). The zone is now the 56dp band that **ends at the larger of
the navigation-bar and system-gesture bottom insets**, and the bottom edge hint sits above that
inset, mirroring how the top zone already sat below the status bar. With no bottom inset (a
non-edge-to-edge host, or hidden bars) it is identical to before. The shell still consumes no
insets; it only reads them. Pure helpers `inTopEdgeZone` / `inBottomEdgeZone` carry the
arithmetic and are tested with non-zero insets.

### New and optional: `bottomPeek`

```kotlin
SpatialShell(..., bottom = { ActionsRoom() }, bottomPeek = 96.dp, bottomReserve = 56.dp) { Home() }
```

- `bottomPeek: Dp? = null`. Null keeps the single-stage room. Set, the bottom room has three
  stops: closed, a strip, and full.
- **What the number means.** The room arrives top-first and is inset by `SpatialMotion.BAND_DP`
  (72dp, now public) at its head, for the parked card's edge. `bottomPeek` is the height of the
  strip **below that band**: the shell adds the band itself, so pass just the height of the strip
  you want to show. Anchor the strip to the **top** of the room's content (`Modifier.height(bottomPeek)`
  at the head of the room, under the shell's inset); the navigation bar covers the foot of the
  revealed area, so that strip is exactly what is on screen, clear of the bar. Hosts that mirrored
  the band into their own peek (`strip + 72.dp`) must drop that addition.
- **When it does not fit.** If the strip would need more than half the window (`MAX_PEEK_REVEAL`) -
  a landscape phone, a large font - there is no peek: the room is single-stage rather than showing
  a cropped strip. `SpatialController.hasBottomPeek: Boolean` says whether the two-stage room is
  actually in effect; hide the strip when it is false.
- **Re-settling.** When the stop moves (rotation in place, insets, density, a new `bottomPeek`) a
  room parked on the old stop follows it; if the peek goes away (null, or it no longer fits) the
  room closes. Nothing moves while a finger is on the axis, and a room left at closed or full stays.
- Release in the lower part of the pull settles on the strip; pulling past it continues to full;
  a flick up from the strip opens full, a flick down closes; a slow release snaps to the nearest
  stop (`settleTargetStaged`, pure). `closeAll()` and a tap on the parked card work from any stage.
- `SpatialController.bottomStage: RoomStage` (`CLOSED` / `PEEK` / `FULL`) is read-only and is the
  stage a slow release would settle to. `SpatialController.peekBottom()` opens straight to the
  strip (it is `open(RoomEdge.BOTTOM)` when there is no peek in effect).
- `openRoom` stays `HOME` at the peek: a peek is not a fully open room. `anyRoomVisible` is true,
  so a `BackHandler` bound to it closes the peek.
- While peeked, the home card is parked (lifted a little) and, as at any parked stage, the whole
  card is the return affordance: tap closes, dragging it up carries on to full.

### New and optional: `bottomReserve`

`bottomReserve: Dp = 0.dp` mirrors `topReserve`: a strip above the navigation bar that the host owns
(a filmstrip, a video transport). The bottom pull zone becomes
`[h - inset - reserve - 56dp, h - inset - reserve]` and the edge hint moves up with it, so a
vertical-first start on the host's own control scrubs instead of opening the room. The default
keeps today's zone. Pure `inBottomEdgeZone(..., reservePx)` carries the arithmetic.

### Release velocity no longer outlives the finger

A release more than 80ms after the last drag event is now treated as having no velocity (it used to
reuse the last per-event delta forever, so pulling up, holding on the strip and lifting flung the
room to full). `dragH` / `dragV` / `settleH` / `settleV` take an optional trailing event uptime
(`-1L` = unknown, which keeps the old behaviour), so hosts that drive the controller from their own
gesture code can pass `change.uptimeMillis`. On the parked card, a tap now has to stay inside touch
slop to count: a jittery tap closes instead of re-settling the room where it stands.
