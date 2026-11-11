# viz-kit — charts whose categories are textures, not hues

One dependency-free browser file, `viz.js`, attaching to `window.ASOC.viz` (also `require()`-able). Every mark is
drawn in one ink on one ground; identity is carried by texture — solid, hatch, dots, fade and their kin — so a chart
reads the same in print, in forced colours and to any colour vision, and the ground is free to be as loud as the host
likes. SVG out, nothing fetched. Open [`demo.html`](demo.html) for every chart on three sets of grounds (the demo
page loads one web font; the module loads nothing).

```html
<script src="viz.js"></script>
<script>
  ASOC.viz.steps(el, [
    { label: 'Failed', value: 1 }, { label: 'Flaky', value: 3 },
    { label: 'Running', value: 14 }, { label: 'Passed', value: 94 }
  ], { tone: 'sun' });
</script>
```

## Charts

Each call is `ASOC.viz.<name>(el, data, opts)` and returns `{ el, update(data, opts), table(), destroy() }`. The chart
fills the width of `el` and redraws when that width changes.

| Chart | Data | What it is |
|---|---|---|
| `steps` | `[{label, value, fill?}]` | Columns from one baseline, the figure and its name standing on each. Takes the height of `el`. |
| `bars` | `[{label, value, fill?}]` | Ranked rows; name and figure on the line above each bar. Sets its own height. |
| `fan` | `[{label, value, fill?}]` | Parts of a whole. `sweep: 90` (corner quarter), `180` (half, default) or `360` (ring); `inner` 0–0.9; `callout` text or `false`; `caption` under a ring's figure. |
| `stack` | `[{label, value, fill?}]` | One pill split into parts; a part is labelled only where its label fits. |
| `line` | `[{label, values, fill?}]` or `number[]` | Up to four series on **one** axis, told apart by dash; the first stands on a fade. `x` labels, `zero` (true), `area` (true), `grid` (false), `smooth` (true). |
| `spark` | `number[]` | A line with nothing but its end. |
| `halftone` | `{rows, cols, values}` | A matrix where magnitude is the area of a dot. Sets its own height; `cell` caps the cell size. |
| `meter` | `{value, max, label}` | A figure against its ceiling; the remainder is the same ink, hatched and faint. `plain: true` for the pill alone. |
| `stat` | `{value, label, delta?, deltaUnit?, vs?}` | The number itself (HTML). Direction is an arrow, never a colour. |
| `legend` | `[{label, fill?, value?}]` | Keys in the same textures (HTML). `row`, `values`, and `lines: true` for dash keys beside a `line`. |

Shared options:

| Option | Default | |
|---|---|---|
| `tone` | — | `'sun'`, `'ember'`, `'night'`, `'bone'`, `'radium'`, `'violet'`, or `{ ink, ground, accent }` |
| `ink`, `ground`, `accent` | `#0c0c0c`, `#f6ee2d`, — | Given directly, these win over `tone`. `ground` must be the solid colour behind the chart: gaps and rings are drawn in it. `accent` colours the fan's callout pill. |
| `rem` | 16 | The type and spacing unit |
| `font`, `weight` | inherit, 300 | |
| `format` | compact numbers | `function (n) → string` |
| `fill` | by order | One texture name for every mark, or `'each'` on `bars` |
| `scale` | 1 | Texture scale |
| `height` | chart's own | |
| `label` | from the data | Accessible name of the chart |
| `on` | — | `{ hover(d), select(d) }` |
| `responsive` | true | Redraw on width change |

## The rules it keeps

- **Textures in a fixed order** (`ASOC.viz.order`): solid, hatch, dots, fade, cross, lines, checks, stipple. A mark
  takes its texture from its position, or from its own `fill` so that a filter never repaints the survivors. A ninth
  part folds into "Other"; there is no ninth texture.
- **One ink.** Text, lines and fills share it. Nothing is encoded by hue, so a host can change the ground at will.
- **Bars start at zero, on a linear scale.** A value of 1 beside 94 is a sliver, and says so.
- **One y axis** on `line`; a second measure is a second chart.
- **Touching parts are separated by a 2px gap in the ground**, never by an outline.
- **Hover and focus are part of the chart.** Every mark is focusable and gives its name and figure; `line` has a
  crosshair readout that the arrow keys step through. Names go in with `textContent`.
- **`table()`** returns the same figures as an HTML table, for a table view beside the chart.

## Drawing your own marks

```js
var paint = ASOC.viz.fills(svg, { ink: '#0c0c0c', scale: 1 });
rect.setAttribute('fill', paint('hatch'));        // any name in ASOC.viz.order
rect.setAttribute('fill', paint('fade', 'up'));   // 'up' | 'right' | { cx, cy, r } for a radial fade
```

`ASOC.viz.swatch(kind, { ink, size })` returns a small SVG key in that texture.

## Tones

`sun`, `ember` and `night` are the loud grounds; `bone` is the quiet one; `radium` and `violet` are Hyle's. They are
a convenience — a host with its own design system passes `ink` and `ground` and ignores them.

## Versioning

`viz.js` carries a `version` (0.1.0); bump it in the file when the API changes. Consume by copying the file or by a
submodule, as with [`orrery-kit`](../orrery-kit/README.md).
