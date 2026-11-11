# Data contract

`:viz-kit-android` takes exactly the data shapes of `web/viz-kit/viz.js` (0.2.0), so **one JSON feed
drives both kits**. `VizJson` parses and writes them (`VizJson.data`, `series`, `matrix`, `meter`,
`stat`, `legend`, `options`, `theme`, and the `toJson` / `seriesToJson` / `legendToJson` writers);
`VizJsonTest` pins the round trip.

Reading is as forgiving as viz.js, and for the same reasons (a feed is not always clean):

- A value that is not a number reads as `0` (`+v || 0`): numeric strings parse, anything else is 0.
- An unknown texture name reads as `solid`.
- A missing `label` is the empty string.
- What is refused is a wrong *shape*: an object where a list is wanted, or a matrix with no `values`.
  That throws `IllegalArgumentException` naming the place. Nothing is fetched, nothing is logged.

## Shapes

| Name | JSON | Kotlin | Charts |
|---|---|---|---|
| Datum | `{ "label": string, "value": number, "fill"?: texture }` in a list | `Datum(label, value, fill?)` | `StepsView`, `BarsView`, `FanView`, `StackView` |
| Series | `[{ "label"?: string, "values": (number\|null)[], "fill"?: texture }]`, or a bare `number[]` for one unlabelled series | `Series(label?, values, fill?)` | `LineView` (up to four), `SparkView` (one) |
| Matrix | `{ "rows"?: string[], "cols"?: string[], "values": number[][] }` | `Matrix(values, rows?, cols?)` | `HalftoneView` |
| Meter | `{ "value": number, "max"?: number (100), "label"?: string }` | `Meter(value, max, label?)` | `MeterView` |
| Stat | `{ "value": number\|string, "label": string, "delta"?: number, "deltaUnit"?: string, "vs"?: string }` | `Stat(label, value?, valueText?, delta?, deltaUnit?, vs?)` | `StatView` |
| LegendItem | `{ "label": string, "fill"?: texture, "dash"?: string, "value"?: number }` in a list | `LegendItem(label, fill?, dash?, value?)` | `LegendView` |

A texture is one of `solid`, `hatch`, `dots`, `fade`, `cross`, `lines`, `checks`, `stipple`, in that
fixed order. A mark without a `fill` takes the texture at its position; with a `fill` it keeps it, so a
filter never repaints the survivors. More than eight parts fold: the first seven keep their textures
and the rest become one `"Other"` (`Datum.parts` lists what it swallowed); there is no ninth texture.

`Stat.value` is a number (formatted by the theme) or a string the host already formatted. `delta` above
zero is an up arrow, below zero a down arrow, zero a flat one; direction is a shape and a sign, never a
colour. A `null` in a series is a gap.

`LegendItem.dash` is a dash pattern such as `"7 5"`; `""` is a solid line; absent takes the default
for the item's position (solid, `7 5`, `1.5 5`, `11 4 2 4`).

## Options (the feed may carry these too)

`VizJson.options` reads viz.js's option names into `ChartOptions`; unknown keys (the web-only ones:
`responsive`, `on`, `font`, `mode`, ...) are ignored.

| Key | Meaning | Used by |
|---|---|---|
| `sweep` | 90 (corner quarter), 180 (half, default), 360 (ring) | fan |
| `inner` | 0 to 0.9 hole radius (0.6 for a ring) | fan |
| `callout` | text, `true` for the total, `false` for none | fan |
| `caption` | text under a ring's figure | fan |
| `x`, `xTitle` | x labels (first and last are drawn); table heading | line |
| `zero`, `area`, `grid`, `smooth` | all true except `grid` | line |
| `unit` | the value column heading in the table view | steps, bars, fan, stack, line |
| `fill` | one texture for every mark, or `"each"` on bars | all |
| `plain` | the pill alone | meter |
| `cell` | cap on the cell size (dp) | halftone |
| `thickness` | bar / pill thickness (dp) | bars, stack, meter |
| `label` | accessible name of the chart | all |
| `height` | height in dp, overriding the chart's own | all |
| `row`, `values`, `lines`, `shades` | wrapping row; show values; dash keys; four steps of ink | legend |
| `size` | figure size in rem (2.6) | stat |

`VizJson.theme` reads `{ "tone": "sun" }` or `{ "ink": "#0c0c0c", "ground": "#f6ee2d", "accent": "#..." }`
(given colours win over the tone), plus `rem`, `weight` and `scale`.

## Differences from viz.js, on purpose

- **Units** are dp (viz.js's px), and `rem` is dp: `VizTheme.rem` defaults to 16.
- **Stipple** is irregular: a 4 x 4 tile of dots jittered by a seeded generator, so it differs from the
  dots texture by more than pitch and is still identical on every run. The web kit's stipple is a
  regular grid.
- **Number formatting** (`VizFormat.compact`) uses English suffixes (K, M, B, T) at any locale; digits
  and separators follow the locale.
- **Bars rows** are at least 44 dp tall (2.5 rem is 40) so each is a touch target.
- A `Stat.delta` is a number; viz.js also tolerates a string, which it renders without a sign logic.
- `graph`, `rose` and `gantt` are not ported (they sit on the Orrery engines).
