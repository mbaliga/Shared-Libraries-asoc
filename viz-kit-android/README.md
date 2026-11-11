# :viz-kit-android — charts whose categories are textures, not hues

The native Kotlin/Canvas port of [`web/viz-kit`](../web/viz-kit/README.md) (viz.js 0.2.0): plain
`android.view.View` subclasses. No Compose, no WebView, no network, no third-party dependency
(AndroidX `customview` and `core` only, for virtual accessibility nodes), nothing logged or sent.
Coordinate `dev.aarso:viz-kit-android`, namespace `dev.aarso.viz`, minSdk 26, version `Viz.VERSION` (0.1.0).

Every mark is one ink on one ground; identity is carried by texture, so a chart reads the same on
any ground, in dark theme, in print and to any colour vision. The data contract is viz.js's, so one JSON
feed drives both kits: see [CONTRACT.md](CONTRACT.md).

```kotlin
val steps = StepsView(context).apply {
    theme = VizTone.SUN.theme()            // or VizTheme(ink = ..., ground = ..., accent = ...)
    setData(listOf(Datum("Failed", 1.0), Datum("Flaky", 3.0), Datum("Running", 14.0), Datum("Passed", 94.0)))
    chartLabel = "Builds this week"
    onSelect = { mark -> open(mark.label) }  // tap, Enter or TalkBack double-tap
    onHover = { mark -> /* pressed or focused; null when none */ }
}
// From a feed shared with the web kit:
steps.setData(VizJson.data(json))
```

Each view is width-driven and sets its own height where the web chart does (bars, stack, halftone,
meter, stat, legend); steps, fan and line take the height the parent fixes, else a default.

## Charts

| View | Data | What it is |
|---|---|---|
| `StepsView` | `List<Datum>` | Columns from one baseline, figure and name standing on each. |
| `BarsView` | `List<Datum>` | Ranked rows; name and figure on the line above each bar. `fillEach` for a texture per row. |
| `FanView` | `List<Datum>` | Parts of a whole. `options.sweep` 90 (corner quarter), 180 (half), 360 (ring); `inner`, `callout`, `showCallout`, `caption`. |
| `StackView` | `List<Datum>` | One pill split into parts; a part is labelled only where its label fits. |
| `LineView` | `List<Series>` or `setValues(List<Double?>)` | Up to four series on ONE axis told apart by dash; the first on a fade. `options.x`, `zero`, `area`, `grid`, `smooth`; crosshair readout. |
| `SparkView` | one series | A line with nothing but its end. Announced as a trend, not as nodes. |
| `HalftoneView` | `Matrix` | A matrix where magnitude is the area of a dot. |
| `MeterView` | `Meter` | A figure against its ceiling; the remainder is hatched and faint. `options.plain`. |
| `StatView` | `Stat` | The number, with a delta arrow. Direction is a shape and a sign, never a colour. |
| `LegendView` | `List<LegendItem>` | Keys in the same textures. `options.row`, `values`, `lines` (dash keys), `shades`. |

Not ported: `graph`, `rose` and `gantt`. They sit on the Orrery engines (`web/orrery-kit`), which have no
native counterpart; use the web kit for them.

Shared: `theme: VizTheme` (`ink`, `ground`, `accent`, `rem` in dp, `font`, `weight` 300, `textureScale`,
`format`), `options: ChartOptions`, `chartLabel`, `onSelect(Mark)`, `onHover(Mark?)`, `table()` / `tableText()`.
Tones: `VizTone.SUN`, `EMBER`, `NIGHT`, `BONE`, `RADIUM`, `VIOLET`. For your own marks:
`TexturePainter(ink, scale, density).apply(paint, Texture.HATCH, bounds)` gives any of the eight textures
as a shader; `Texture` is in the fixed order solid, hatch, dots, fade, cross, lines, checks, stipple.

## The rules it keeps

- **Textures in a fixed order.** A mark takes its texture from its position, or from its own `fill` so a filter
  never repaints the survivors. A ninth part folds into "Other"; there is no ninth texture.
- **One ink.** Text, lines and fills share it. Nothing is encoded by hue.
- **Bars start at zero, on a linear scale.** A value of 1 beside 94 is a sliver (3 dp floor), and says so.
- **One y axis** on `LineView`; a second measure is a second chart.
- **Touching parts are separated by a 2 dp gap in the ground**, never an outline (`GeometryTest` reads the pixels).
- **Dark theme friendly:** the ground is whatever the host passes; nothing assumes light.
- **On device only:** no permissions, no network, no telemetry, no WebView, no resources.

## Accessibility and touch

- Every mark is a virtual accessibility node (`ExploreByTouchHelper`): description `"name: figure"`, a click
  action when `onSelect` is set, bounds of at least 48 dp where the layout has room (steps, bars, stack, fan,
  meter, interactive legend; `AccessibilityTest` asserts 44 dp).
- Exceptions, stated plainly: a line chart's points are strips as wide as the plot allows (a 30-point line on a
  phone is ~11 dp each; dragging a finger scrubs the crosshair), and a halftone's cells are below 44 dp, so
  TalkBack gets one node per row (total and peak) and the figures live in `tableText()`. A legend is only
  a set of 48 dp targets when `onSelect` is set; otherwise it is one announcement.
- DPAD / keyboard: arrows step through marks, Home/End jump, Enter/Space/Center select, Tab walks off the
  ends; the focused mark shows a ring and a readout. Halftone up/down moves by row.
- Touch: a press shows the readout and steps the other marks back to 30%; lifting selects. A mouse hover does the same.
- `contentDescription` is `chartLabel`, else the first dozen rows of the data. `tableText()` is the same figures,
  tab-separated, for a table view.

## Consuming

As any module here (see the [root README](../README.md)): add the submodule, `includeBuild("shared-libraries")`, then

```kotlin
implementation("dev.aarso:viz-kit-android:0.1.0")
```

## Tests

`./gradlew :viz-kit-android:testDebugUnitTest` (Robolectric 4.14.1, native graphics). Contract parsing and
round trip, texture determinism (including the seeded stipple), geometry, accessibility nodes, and
`RenderTest`, which writes a PNG of every chart on sun, night and bone at 405 dp xxhdpi to `build/viz-render`
(or `-Dviz.render.dir=<dir>`). The shared example feed is `src/test/resources/demo-feed.json`.
Robolectric needs its android-all jar for SDK 34; offline, point
`-Drobolectric.dependency.dir` at a directory holding it and set `-Drobolectric.offline=true`.
