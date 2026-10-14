# Spec: cell-shell motion contract

Status: **DRAFT for owner review** (PORTING_PLAN.md S0.2; PD-4 "spec-only reuse is the sanctioned
Ubuntu Touch contribution"). Normative source: `cell-shell` 0.1.0, `SpatialMotion.kt` and
`EdgeTimelineScrubber.kt`. Golden vectors: [`fixtures/cell-shell-motion.v1.json`](fixtures/cell-shell-motion.v1.json),
asserted against the Kotlin implementation by `cell-shell/.../SpecFixturesTest.kt`.

Words in capitals (MUST, MUST NOT, SHOULD) are used in the RFC 2119 sense.

## 1. What this spec is for

`cell-shell` is the constellation's navigation and motion shell: one navigation feel across every
app ("the navigation pattern of fonebrew ... followed everywhere"). The Kotlin module cannot run
where there is no JVM or Compose (Ubuntu Touch QML apps, a Swift shell). This document lets such an
implementation reproduce **the part that must not drift** - the settle decision, the parking
geometry, the scrubber's index mapping and the motion constants - and prove it with the same
vectors the Kotlin build is held to.

An implementation conforms if, for every case in the fixture, its function returns the fixture's
`expect` (tolerances in section 6), and its constants equal section 3.

## 2. Model

A **room** is a surface parked off one edge of **home**. Rooms are not screens: opening one MUST
NOT push a back-stack entry; home stays alive and visible behind it, and dragging it back is the way
out. Edges: `HOME`, `LEFT`, `RIGHT`, `TOP`, `BOTTOM`.

Two axes, each a number in `[-1, +1]` where `0` is home:

| Axis | -1 | 0 | +1 |
|---|---|---|---|
| `h` | RIGHT room open | home | LEFT room open |
| `v` | BOTTOM room open | home | TOP room open |

The sign reads as "which way the home card was pushed": dragging from the left edge pushes home to
the right (`+h`) and reveals the left room behind it. Every drag is applied 1:1 with the finger
(no smoothing); only release animates. A host that supplies no room on an edge MUST clamp that
direction to `0` so a drag cannot reveal an edge that is empty.

Vertical travel is shorter than horizontal: a top or bottom room opens over
`VERTICAL_TRAVEL_FRACTION` of the viewport height (`delta_px / (height * 0.7)`), horizontal over the
full width (`delta_px / width`).

## 3. Constants (a contract, not defaults)

| Constant | Value | Meaning |
|---|---|---|
| `settle_duration_ms` | 320 | Duration of the release animation |
| `settle_delay_ms` | 0 | No delay |
| `settle_easing_cubic_bezier` | (0.4, 0, 0.2, 1) | Easing curve control points. **No spring, no overshoot**: a room is a place you arrive at, not something that bounces into position |
| `park_scale_drop` | 0.10 | How far the parked home card shrinks at full open (scale 0.90) |
| `park_elevation` | 24 | Shadow under the parked card at full open (raw elevation units) |
| `park_corner_dp` | 20 | Corner radius of the parked card at full open |
| `park_ring_alpha` | 0.6 | Alpha of the accent ring around the parked card |
| `park_scrim_alpha` | 0.62 | Scrim over the parked card; quiets its content so it cannot read as overlap |
| `settle_threshold` | 0.5 | Past this fraction of travel (strictly), release completes |
| `flick_velocity_px` | 2 | A flick faster than this (strictly) decides direction by itself |
| `vertical_travel_fraction` | 0.7 | See section 2 |

An app that tunes any of these has broken what the module exists to guarantee.

## 4. The settle decision

`settle_target(value, last_delta) -> {-1, 0, +1}` where `value` is the axis position in `[-1, 1]`
at release and `last_delta` is the most recent frame's movement in px (sign = direction).

```
settle_target(value, last_delta):
    if -0.001 <= value <= 0.001:            return 0      # nothing moved: stay home
    if abs(last_delta) > flick_velocity_px:                 # strict: the threshold itself is NOT a flick
        if last_delta > 0:  return  1 if value >= 0 else 0
        else:               return -1 if value <= 0 else 0
    if value >  settle_threshold:           return  1       # strict: exactly halfway retreats
    if value < -settle_threshold:           return -1
    return 0
```

Consequences the vectors pin: a fast short flick outward opens from barely any travel; a flick back
toward home dismisses an almost fully open room (direction beats distance in both directions, or the
gesture would be one-way); a slow drag lands at the nearer end; a fully open room released without
movement stays open; sensor noise on a tap never drifts a room open.

## 5. Parking geometry and the scrubber

### 5.1 `park_distance`

```
park_distance(extent, scale, band_px) = extent * (1 + scale) / 2 - band_px
```

`extent` is the viewport width or height in px, `scale` the card's current scale. The card is scaled
down, so its half-extent shrinks too; parking it by the full extent would push it entirely off
screen. **Invariant** (asserted for every fixture case): after parking, exactly `band_px` of the
card is still on screen - the grab band is the only way back that does not involve a system gesture.

### 5.2 `index_for_fraction`

Maps a finger position along the edge scrubber to an item index.

```
index_for_fraction(fraction, item_count):
    if item_count <= 0:   return 0
    if fraction is NaN:   return 0          # no fixture vector: JSON has no NaN
    clamped = clamp(fraction, 0, 1)
    return clamp(trunc(clamped * item_count), 0, item_count - 1)
```

Each item owns an equal band of travel; a band boundary belongs to the band it **opens**, not the
one it closes (`0.25` of 4 items is the second item). The result is always a legal index, because it
feeds a list scroll directly.

### 5.3 `stop_for_index`

```
stop_for_index(stops, index) = the LAST stop whose item_index <= index, or none
```

`stops` are ascending by `item_index`. A stop labels the run of items that **starts** at it, so the
containing stop wins over the nearer one (index 39 of March 0 / April 12 / May 40 is April). An index
before the first stop has no label (the bubble hides; it MUST NOT borrow the first stop's name). The
last stop runs to the end of the list. Stops sharing a start index resolve to the later one.

## 6. Fixture format and tolerances

`fixtures/cell-shell-motion.v1.json` is one JSON object: `spec`, `version`, `source`, `constants`,
and the case lists `settle_target`, `park_distance`, `index_for_fraction`, `stop_sets` (named stop
lists) and `stop_for_index`. Every case has a human-readable `name`; computed cases carry `expect`.
Numbers are IEEE doubles; the Kotlin implementation computes in 32-bit float.

| List | Compare |
|---|---|
| `settle_target` | exact (results are exactly -1, 0 or 1), tolerance 1e-6 |
| `park_distance` | absolute tolerance 1e-2 px |
| `index_for_fraction` | exact integer |
| `stop_for_index` | exact label, or `null` for no stop |
| `constants` | absolute tolerance 1e-6 |

The fixture is **golden**: it is regenerated only by the explicit opt-in switch described in
[`README.md`](README.md), and a changed expected value is a contract break that the owner must
approve.

## 7. Platform notes (none of this is verified on a device)

- **Ubuntu Touch / Lomiri.** Lomiri owns the left, right and top edges. That statement is an
  `ASSUMPTION` carried from the repo reader's notes and is not sourced in the program's UT brief;
  confirm it at S-UT1 before relying on it. If it holds, the four-edge rooms pattern cannot be
  reproduced there as written, so a QML port implements sections 4-5 (the decision functions and
  constants) while the layout is the app's call (PORTING_PLAN.md section 8 item 3 is the owner's open
  question).
- **iOS / iPadOS.** System edge gestures may compete with the LEFT and BOTTOM rooms; unknown until a
  device is in hand (`NEEDS-DEVICE-VALIDATION`).
- **Desktop.** A resizable mouse window has no edge-drag convention; also an open owner question.

## 8. Not covered by this spec (named follow-ups)

- `WordWheelRail` focus falloff and `ShakePeakTrain` (`ShakeToRefreshTest`, `WordWheelRailTest`)
  have pure functions with test vectors but are outside the four vector sources the plan names for
  S0.2. Candidate fixtures for a later revision.
- `item_center_y` (the inverse of `index_for_fraction`) has no test vectors yet.
- Layout composition (`SpatialShell`), `systemGestureExclusion` and the accelerometer source are
  platform-bound and are not specified here.

## 9. Evidence

| Claim | Class |
|---|---|
| Kotlin implementation reproduces every fixture case | `CONTAINER-BUILD-ONLY` (JVM unit tests in `:cell-shell:test`) |
| A QML / Swift / other implementation conforms | `NOT-APPLICABLE` here; `NEEDS-DEVICE-VALIDATION` on a device, owned by the consumer |
| The motion *feels* right on any platform | `NEEDS-OWNER-VALIDATION` |
