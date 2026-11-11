# viz-kit — charts whose categories are textures, not hues

Dependency-free browser files attaching to `window.ASOC.viz`. Every mark is drawn in one ink on one ground; identity
is carried by texture — solid, hatch, dots, fade and their kin — by shape, by dash, or by a step of the one ink, never
by hue alone. A chart reads the same in print, in forced colours and to any colour vision, and the ground is free to
be as loud as the host likes. SVG and canvas out, nothing fetched. Open [`demo.html`](demo.html) for every chart, its
variants, its call and its table view (the demo page loads one web font; the modules load nothing).

| File | What it holds |
|---|---|
| `viz.js` | The core: textures, tooltip, hover and keyboard, table view; the figures and everyday charts; the graph, rose and gantt drawn with the Orrery engines. |
| `viz-charts.js` | The wider set, to AntV G2's range: comparison, time, distribution, relationship, hierarchy, flow. Load after `viz.js`. |

```html
<script src="viz.js"></script>
<script src="viz-charts.js"></script>
<script>
  ASOC.viz.steps(el, [
    { label: 'Failed', value: 1 }, { label: 'Flaky', value: 3 },
    { label: 'Running', value: 14 }, { label: 'Passed', value: 94 }
  ], { tone: 'sun' });
</script>
```

Every call is `ASOC.viz.<name>(el, data, opts)` and returns `{ el, update(data, opts), table(), destroy() }`. A chart
fills the width of `el` and redraws when that width changes; charts marked *own height* size themselves, the rest take
the height of `el` (or `opts.height`).

## Charts in `viz.js`

| Chart | Data | What it is |
|---|---|---|
| `steps` | `[{label, value, fill?}]` | Columns from one baseline, the figure and its name standing on each. |
| `bars` | `[{label, value, fill?, brand?}]` | Ranked rows; name and figure on the line above each bar. Own height. |
| `fan` | `[{label, value, fill?}]` | Parts of a whole. `sweep: 90`, `180` (default) or `360`; `inner`; `callout`; `caption`. |
| `stack` | `[{label, value, fill?}]` | One pill split into parts; a part is labelled only where its label fits. Own height. |
| `line` | `[{label, values}]` or `number[]` | Up to four series on **one** axis, told apart by dash. `x`, `zero`, `area`, `grid`, `smooth`, `step`. |
| `spark` | `number[]` | A line with nothing but its end. |
| `halftone` | `{rows, cols, values}` | A matrix where magnitude is the area of a dot. Own height. |
| `meter` | `{value, max, label}` | A figure against its ceiling. `plain: true` for the pill alone. |
| `stat` | `{value, label, delta?, deltaUnit?, vs?}` | The number itself (HTML). Direction is an arrow, never a colour. |
| `legend` | `[{label, fill?, value?}]` | Keys in the same textures (HTML). `row`, `values`, `lines` (dash keys), `shades` (ink steps). |

## Charts in `viz-charts.js`

| Job | Chart | Data | Notes |
|---|---|---|---|
| Compare | `columns` | `{categories, series: [{label, values}]}` | `mode: 'group' \| 'stack' \| 'percent'` |
| | `waterfall` | `[{label, value, total?}]` | Rises solid, falls hatched, totals dotted; signs are written |
| | `bullet` | `[{label, value, target, ranges}]` | Own height |
| | `range` | `[{label, lo, hi}]` | Own height |
| | `radial` | `[{label, value}]` | Bars bent round a centre |
| | `funnel` | `[{label, value}]` | Share of first and of previous stage. Own height |
| | `gauge` | `{value, max, label}` | |
| Time | `area` | `[{label, values}]` | `x`, `mode: 'stack' \| 'percent' \| 'stream'`; crosshair readout |
| | `candle` | `[{t, o, h, l, c}]` | A rise is hollow, a fall is solid |
| | `calendar` | `[{date, value}]` | Weeks as columns, magnitude as dot area. Own height |
| Spread | `histogram` | `number[]` | `bins` |
| | `box` | `[{label, values}]` | Quartiles, whiskers at 1.5 IQR, outliers. Own height |
| | `violin` | `[{label, values}]` | Density, mirrored, with median and quartiles. Own height |
| Relate | `scatter` | `[{x, y, size?, group?, label?}]` | Groups are shapes (`ASOC.viz.shapes`); `size` makes bubbles; `fit` a trend line |
| | `radar` | `{axes, series, max?}` | Series told apart by dash; the first is filled |
| | `parallel` | `{dims, rows: [{label, values, group?}]}` | Each axis has its own extent, labelled |
| Hierarchy | `treemap` | `[{label, value, children?}]` | Squarified; names sit on plates of ground |
| | `sunburst` | `[{label, value, children?}]` | Two rings |
| | `pack` | `[{label, value}]` | Up to 60 circles |
| | `tree` | `{label, children}` | Tidy, left to right. Own height |
| Flow | `sankey` | `{nodes: [{id, label?}], links: [{a, b, value}]}` | Layered by longest path |
| | `chord` | `{nodes, links}` | Arc length is how much; a ribbon is a pair |
| Text | `words` | `[{label, value}]` | Up to 80 words |

Not here, and deliberately so until an app needs them: maps, contour and density fields, faceting into small
multiples, linked brushing between charts, axis zoom and sliders, and annotations.

## With the Orrery engines

Three charts draw with the engines in [`../orrery-kit`](../orrery-kit/README.md). The engines stay where Orrery's
build reads them — nothing is copied — so load the one a chart needs before calling it; a missing engine throws an
error that names the file.

| Chart | Needs | Data | What it is |
|---|---|---|---|
| `graph` | `graph.js` | `{nodes: [{id, label?, group?, size?, rings?, hollow?, brand?}], links: [{a, b, w?}]}` | The force graph, 2D or 3D, with every interaction the engine has. A group is a texture. |
| `rose` | `rose.js` | `[{label, values[24]}]` | A day as a rose. Stacked series are steps of the one ink (`ASOC.viz.shades`). |
| `gantt` | `gantt.js` | `{days, rows, groups}` as the gantt takes them | The Orrery gantt in this kit's ink, ground and type. |

### graph

```js
var g = ASOC.viz.graph(el, { nodes, links }, { mode: '2d', directed: true });
ASOC.viz.graphTools(toolsEl, g);      // the whole control row, or drive it yourself:
g.layout('radial'); g.set('fisheye', true); g.setMode('3d'); g.toggle('search'); g.undo();
```

| | |
|---|---|
| Gestures (the AntV G6 set, from the engine) | drag a node (it pins), pan, orbit in 3D, wheel and pinch zoom, click and shift-click select, shift-drag brush, alt-drag lasso, shift-drag node to node to link, double-click to focus or fit, right-click menu, arrow keys walk the selection, Enter / P / 0 / R, ⌘Z and ⇧⌘Z |
| `g.layout(kind)` | `force`, `circular`, `grid`, `concentric`, `radial`, `layered`, `tree` — nodes travel to the new arrangement; radial, layered and tree grow from the selected node, else the best-connected one |
| `g.set(key, on)` | `fisheye`, `edgeLens`, `grid`, `snap`, `forceDrag`, `bundle`, `bubbles`, `labels`, `directed` (arrows), `minimap` (`true` shows it when part of the graph is off the ground, `'always'`, `false`) |
| `g.setMode('2d' \| '3d')`, `g.fit()`, `g.reset()`, `g.undo()`, `g.redo()`, `g.select(ids)`, `g.focus(id)`, `g.toggle(group)`, `g.legend()` | |
| Marks by facet | `group` → texture, `size` → area, `rings` → rings round the node, `hollow` → empty body, `brand` → the technology's mark inside it, pinned → a dot |
| Context menu | Focus, Pin, Select neighbours, Fold group; on the ground: Fit, Reset, Undo, Clear. `menu: false` removes it, `on.context` replaces it |
| `on` | `select(ids)`, `hover(id)`, `focus(id)`, `link(a, b)`, `context(id, x, y, ev)`, `change(kind)` |
| `g.engine` | The `ASOC.Graph` instance, for anything not wrapped |

The wheel zooms only once the graph has focus or with ctrl/⌘ held, so a graph in a page never traps the scroll;
`wheel: 'always'` lifts that. `graphTools` draws the controls as one row: 2D/3D, arrangement, Fit, Reset, Undo, Redo,
the switches, a legend whose entries fold their group, and the gestures behind a "?".

## Cells, brand marks and sound

These come from `../orrery-kit` and switch on when their file is loaded.

- **Cells** (`cells.js`): points in `scatter` and `tree`, and nodes in `graph`, are cells rather than discs — seeded by
  what they stand for, so each is slightly different and always the same. `marks: 'disc'` keeps them round.
- **Brand marks** (`brands.js`): `bars` rows and `graph` nodes that carry `brand: 'Kotlin'` show that technology's
  mark with `brands: true`, in the ink; `brands: 'colour'` uses the brand's own colour. Off unless asked for: a mark
  identifies a technology and belongs to its owner.
- **Sound** (`sfx.js`): `ASOC.viz.sound(true)` plays the interface tones on hover, select, menu and switch across
  every chart; pass your own `sfx` instance to share its on/off and level; `sound(false)` stops.

## Shared options

| Option | Default | |
|---|---|---|
| `tone` | — | `'sun'`, `'ember'`, `'night'`, `'bone'`, `'radium'`, `'violet'`, or `{ ink, ground, accent }` |
| `ink`, `ground`, `accent` | `#0c0c0c`, `#f6ee2d`, — | Given directly, these win over `tone`. `ground` must be the solid colour behind the chart: gaps, rings and plates are drawn in it. |
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
- **One ink.** Text, lines and fills share it. Where a series must be told from another it is by texture, shape
  (`ASOC.viz.shapes`), dash (`ASOC.viz.dashes`) or a step of the ink (`ASOC.viz.shades`, for series that stack in order).
- **Bars start at zero, on a linear scale.** A value of 1 beside 94 is a sliver, and says so.
- **One y axis**; a second measure is a second chart.
- **Touching parts are separated by a 2px gap in the ground**, never by an outline. A name on a texture sits on a
  plate of ground.
- **Direction is written, not coloured**: a waterfall's fall is hatched and signed, a candle's fall is solid, a
  delta carries an arrow.
- **Hover and focus are part of the chart.** Every mark is focusable and gives its name and figure; `line` and
  `area` have a crosshair readout that the arrow keys step through. Names go in with `textContent`.
- **`table()`** returns the same figures as an HTML table, for a table view beside the chart.

## Drawing your own marks

```js
var paint = ASOC.viz.fills(svg, { ink: '#0c0c0c', scale: 1 });
rect.setAttribute('fill', paint('hatch'));        // any name in ASOC.viz.order
rect.setAttribute('fill', paint('fade', 'up'));   // 'up' | 'right' | { cx, cy, r } for a radial fade

var cpaint = ASOC.viz.canvasFills(ctx, { ink: '#0c0c0c', scale: 0.62, dpr: devicePixelRatio });
ctx.fillStyle = cpaint('dots');
```

`ASOC.viz.swatch(kind, { ink, size, alpha, dash })` returns a small SVG key.

## Versioning

`viz.js` carries a `version` (0.3.0); bump it in the file when the API changes. Consume by copying the files or by a
submodule, as with [`orrery-kit`](../orrery-kit/README.md).
