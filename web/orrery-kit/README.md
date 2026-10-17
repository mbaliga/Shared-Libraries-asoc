# orrery-kit — the web modules the constellation shares

Dependency-free browser modules (plain `<script>` files; also `require()`-able). Each attaches to `window.ASOC`. They
were carved out of Orrery so that no app draws a cell, a rose, a gantt or a force graph twice (Personal-Tracker
`DECISIONS.md` D-AJ). Hyle-Design-System stays the *design system*; this is the neutral home for shared web code, as
the Kotlin modules beside it are for Android.

| Module | `ASOC.` | What it is | Version |
|---|---|---|---|
| `cells.js` | `cells` | The mark of record: a cell, not a disc. Seeded membranes with a nucleus; canvas and SVG. | 0.1.0 |
| `sfx.js` | `sfx` | Console-style interface tones from a few oscillators; on/off and level through a store adapter. | 0.1.0 |
| `rose.js` | `rose` | Twenty-four hours as a rose; stacked series on one square-root scale; segment ring; hit testing. | 0.1.0 |
| `brands.js` | `brands` | Colours and marks of languages, libraries and platforms, generated from simple-icons (CC0 paths). | 0.1.0 |
| `graph.js` | `Graph` | A force-directed graph in 3D or 2D with the interactions a graph owes its users (table below). | 0.1.0 |
| `gantt.js` | `gantt` | Days as columns, dated rows, bars for the work that leads to each, swimlanes, a load row. SVG. | 0.1.0 |

Consume by copying the files you need into your build (Orrery's `build.py` reads them from a sibling checkout and
keeps a vendored snapshot), or by a submodule. No bundler is assumed; no module imports another — `graph.js` draws
nothing itself and `gantt.js` and `rose.js` take colours as options, so a host's design system stays in charge.

## graph.js against AntV G6 v5

The owner's rule: every interaction G6 offers should be present. G6 v5 ships fifteen built-in behaviours and
eighteen plugins (its own docs, read 2026-10-17). The engine lays out, projects and handles input; the host draws, so
"plugins" that are drawing or chrome come back as hooks or helpers.

| G6 behaviour | Here | How |
|---|---|---|
| DragCanvas | ✓ | drag on empty ground pans (2D); in 3D drag orbits, ctrl/⌘- or middle-drag pans |
| ZoomCanvas | ✓ | wheel zooms about the pointer; pinch on touch; `+`/`-` keys; `minZoom`/`maxZoom` |
| ScrollCanvas | ✓ | `options.wheel = 'scroll'` makes the wheel pan; shift-wheel pans sideways in either mode |
| DragElement | ✓ | drag a node; it pins where you drop it; drop with shift onto another node emits `on.link` |
| DragElementForce | ✓ | `options.forceDrag`: a few force steps each move, so the neighbourhood follows |
| ClickSelect | ✓ | click selects, shift/ctrl adds; click on empty ground clears; `on.select(ids)` |
| BrushSelect | ✓ | shift-drag a rectangle |
| LassoSelect | ✓ | alt-drag a free shape |
| HoverActivate | ✓ | `on.hover(id)` with `neighbours(id)` for the host to light what touches it |
| FocusElement | ✓ | double-click a node, or `focus(id)`: animates it to the centre and nearer |
| CollapseExpand | ✓ | `collapse(group)` / `expand(group)` fold a group into one node; shift-double-click a member, double-click the group node |
| CreateEdge | ✓ | shift-drag from one node onto another → `on.link(a, b)`; the host decides what a link means |
| FixElementSize | ✓ | `options.fixSize`: the host draws with the screen scale `s.k`, so a node keeps its size under zoom |
| AutoAdaptLabel | ✓ | `labelsFor(order, measure)`: labels placed in priority order, overlapping ones dropped |
| OptimizeViewportTransform | ✓ | `state.moving` is true while the view pans, turns or zooms; the host draws a cheaper frame |

| G6 plugin | Here | How |
|---|---|---|
| Minimap | ✓ | `drawMinimap(ctx, x, y, w, h)`; click or drag on it moves the view |
| Tooltip | ✓ | `on.tooltip(id, x, y)` — the host renders its own |
| Legend | ✓ | `legend()` returns the groups with counts and folded state — the host renders |
| Hull | ✓ | `hullOf(ids, pad)`; drawn round the selection by `drawOverlays` when `options.hull` |
| BubbleSets | ≈ | the padded hull stands in; true bubble sets are not implemented |
| Fisheye | ✓ | `options.fisheye` distorts screen positions round the pointer (Sarkar–Brown) |
| EdgeFilterLens | ✓ | `options.edgeLens`: `visibleLinks()` returns only edges near the pointer |
| History | ✓ | moves, pins, folds, mode changes; `undo()`/`redo()`, ⌘Z / ⇧⌘Z through `key(ev)` |
| Snapline | ✓ | `options.snap` in 2D: a dragged node snaps to neighbours' x and y with guide lines |
| GridLine | ✓ | `options.grid` in 2D, drawn by `drawOverlays` |
| Contextmenu | ✓ | `on.context(id, x, y, ev)` on right-click — the host renders the menu |
| Toolbar | hook | the host's buttons call `fit()`, `reset()`, `setMode()`, `undo()`, `redo()` |
| Fullscreen | host | the host's own fullscreen control |
| Background | host | the field colour belongs to the host |
| Title | host | a caption belongs to the host |
| Timebar | host | in Orrery the ring of days is the timebar; a generic one is not implemented |
| EdgeBundling | ✗ | not implemented; a host may bundle in its own link drawing |
| Watermark | ✗ | deliberately absent |

Also here, not in G6's list: a 3D ↔ 2D toggle (`setMode`), orbit in 3D, keyboard walking of the selection (arrows),
`fit()` on double-click of empty ground, `within(x, y)` so a graph can share a canvas with other drawing.

## brands.js

Generated by `tools/brands-from-simple-icons.js` from the `simple-icons` npm package: a brand's own hex and its path,
plus an `on` colour lifted for dark fields. The marks identify a technology or platform; each belongs to its owner,
which is why a host keeps them behind a setting and never uses them as ornament. Regenerate when the constellation
adopts a new library:

```
npm pack simple-icons && tar xzf simple-icons-*.tgz
node tools/brands-from-simple-icons.js ./package > brands.js
```

## Versioning

Each file carries a `version`; bump it in the file when its API changes. There is no registry: consumers pin by the
commit they copy from, as the Kotlin modules pin by submodule.
