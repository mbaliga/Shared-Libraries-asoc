# loop-studio — a minimal loop editor on `loop-engine`

One module (`studio.js`) and one demo (`demo.html`). It renders a LoopDefinition v2 as a graph and edits it, validates
it with the engine, and runs it against **mock executors** so you can watch the §9 states, gateway choices and budgets
live. House style follows Alloy `docs/STYLE.md`: glass panels, monospace uppercase labels, explanations behind an `i`,
accent mint, toggleable square-wave blips (off by default), bottom sheets under 900px, state never by hue alone.

```
python3 -m http.server -d web 8080      # from the repo root; ES modules do not load from file://
open http://localhost:8080/loop-studio/demo.html          # add ?autorun to run at once
```

```js
import { mountStudio } from './studio.js';
const studio = mountStudio(el, { definition, executors /* optional */, theme: 'dark', onChange, onRun });
studio.getDefinition(); studio.setDefinition(def); studio.validate(); studio.run(); studio.replay(); studio.destroy();
```

| Area | What it does |
|---|---|
| Graph | Plain SVG: drag nodes, drag from the dot on a node to another node to connect, wheel to zoom, drag the ground to pan, Delete removes the selection, Ctrl/Cmd+Z undo (every edit is undoable). Nodes carry a two-letter category glyph and badge with finding counts (`!` error, `~` warning); `END`/`START` are marked in text. Gateway edges are dashed and labelled with their outcome. |
| Add | Palette of the eight node categories. |
| Inspector | Node (id with reference-safe rename, name, category, passthrough, start/end, role, model, system prompt, mock output, retries, timeout, side effects, failure state), edge (label), gateway (exhaustive, outcomes with label/priority/condition/target, default path, live condition check), loop (id, objective, budgets, verification policy). |
| Gateways | A second exit from a node turns its exits into a gateway automatically (first exit conditional, new exit the default) so the definition stays valid; deleting back to one exit collapses it. |
| Validate | The engine's `validateDefinition`; findings list (click to select the object), badges on nodes. |
| Run | Objective + params JSON, then the engine with mock executors (`node.config.mock`: `a|b|c` cycles by round, `{round}` substituted, JSON parsed; human-decision nodes prompt you). Live state, trace, budget meters, Stop, and **Replay last run** (`replayRun`). |
| Export / Import | The definition as JSON. Positions live in `presentation`, which never changes the semantic digest. |

The editing operations (`addNode`, `connect`, `deleteNode`, `renameNode`, `setOutcome`, `autoLayout`, …) are exported
pure functions and are tested from `../loop-engine/test/studio-ops.test.js`.

Per LOOP_WEB_STUDIO_SPEC §7 the browser neither holds credentials nor originates real execution: the studio's Run
button never calls a model. A host may pass its own `executors`, which is the host's decision.

I chose plain SVG over `web/orrery-kit` `Graph`: that module is a force-directed canvas graph; a loop is a small, hand-placed,
directed graph with labelled gateway edges, so a force layout would fight the author.
