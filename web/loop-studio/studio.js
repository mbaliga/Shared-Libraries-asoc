/* loop-studio — a minimal browser editor for LoopDefinition v2 that runs on ../loop-engine.
   One module. Pure editing operations are exported (and unit-tested under node); `mountStudio` is the DOM part.

     import { mountStudio } from './studio.js';
     const studio = mountStudio(document.getElementById('app'), { definition, executors, onChange(def) {} });
     studio.getDefinition(); studio.setDefinition(def); studio.validate(); studio.run(); studio.destroy();

   Per LOOP_WEB_STUDIO_SPEC §7 the browser never holds credentials or originates real execution: the Run panel runs the
   engine against MOCK executors (scripted outputs, a human prompt for human-decision nodes). Hosts may pass their own
   `executors` — that is the host's decision, not the studio's. */

import { validateDefinition, LoopRunDriver, replayRun, checkCondition, RUN_STATES, TERMINAL, effectiveLimits } from '../loop-engine/index.js';

/* ================================================================== pure editing operations */

export const CATEGORIES = [
  ['DETERMINISTIC_TRANSFORMATION', 'DT', 'Transform'], ['MODEL_INFERENCE', 'AI', 'Model'], ['TYPED_TOOL', 'TL', 'Tool'], ['HUMAN_DECISION', 'HD', 'Human'],
  ['BOUNDED_SUBLOOP', 'SL', 'Subloop'], ['VERIFIER', 'VF', 'Verifier'], ['ARTIFACT_IMPORT_EXPORT', 'IO', 'Artifact'], ['WAIT_EVENT_GATE', 'WG', 'Wait'],
];
const clone = (x) => JSON.parse(JSON.stringify(x));
export const glyphOf = (cat) => (CATEGORIES.find((c) => c[0] === cat) || [, '??'])[1];

export function newDefinition(loopId = 'loop.new') {
  const d = {
    loopId, semanticVersion: '0.1.0', objective: 'Describe what this loop is for.', documentationRef: null,
    inputSchema: { location: 'INLINE', inlineSchema: { type: 'object', properties: {} } }, outputSchema: { location: 'INLINE', inlineSchema: { type: 'string' } },
    startNodeId: 'start', terminalNodeIds: ['end'], nodes: [], edges: [], gateways: [],
    budgets: { steps: 12, wallClockSeconds: 300, tokens: 50000, cost: { amount: 1, currency: 'USD' }, toolCalls: 10 },
    bindingSlots: [], requiredCapabilities: [], executionTargetConstraints: { permittedTargetTypes: ['LOCAL_ANDROID'], preferredTargetTypes: ['LOCAL_ANDROID'], requiresAlwaysOnLocal: false },
    distributionConstraints: {}, minEngineVersion: '1.0.0', maxEngineVersion: null,
    verificationPolicy: { requireVerificationForSuccess: false, verifiers: [] }, terminalStateMapping: { unverifiedSuccessAllowed: true, nodeFailureEscalations: [] },
    provenance: { sourceLocation: 'loop-studio', projectRevision: 'n/a', initiatingPrincipal: 'user:local' }, licenseRef: { spdxId: 'NOASSERTION' },
  };
  d.nodes.push(makeNode('DETERMINISTIC_TRANSFORMATION', 'start', { config: { passthrough: true, name: 'Start' }, presentation: { x: 60, y: 160 } }));
  d.nodes.push(makeNode('DETERMINISTIC_TRANSFORMATION', 'end', { config: { passthrough: true, name: 'End' }, presentation: { x: 560, y: 160 } }));
  d.edges.push({ edgeId: 'e1', fromNodeId: 'start', toNodeId: 'end', label: null, gatewayId: null });
  return d;
}

export function makeNode(category, nodeId, over = {}) {
  const human = category === 'HUMAN_DECISION', tool = category === 'TYPED_TOOL';
  return {
    nodeId, category, inputPorts: [], outputPorts: [], implementation: { kind: 'TYPED_SLOT', slotId: `${nodeId}-slot` }, requestedCapabilities: [],
    authorityClass: human ? 'PROPOSE' : tool ? 'EXECUTE_REVERSIBLE' : 'PROPOSE', preferredExecutionTargets: [], permittedExecutionTargets: ['LOCAL_ANDROID'],
    timeoutPolicy: { timeoutSeconds: category === 'WAIT_EVENT_GATE' ? 60 : null, onTimeout: 'FAIL_SAFE' }, retryPolicy: { maxRetries: 0, backoff: 'NONE' }, idempotencyMode: 'PURE',
    compensation: { compensable: false, nonCompensabilityAcknowledged: true }, verificationRequirements: { requiredForSuccess: false, verifierIds: [] },
    sideEffectClass: 'NONE', onFailureTerminalState: 'FAILED_SAFE', explanation: `${category.toLowerCase().replace(/_/g, ' ')} node.`,
    ...(category === 'BOUNDED_SUBLOOP' ? { maxRecursionDepth: 2, implementation: { kind: 'EMBEDDED_SUBLOOP', subloopDefinitionRef: 'subloops/sub.definition.json' } } : {}),
    ...over,
  };
}

export function uniqueId(list, key, prefix) {
  const used = new Set(list.map((x) => x[key]));
  let n = list.length + 1;
  while (used.has(`${prefix}${n}`)) n++;
  return `${prefix}${n}`;
}

export function addNode(def, category, pos = { x: 200, y: 160 }) {
  const d = clone(def);
  const id = uniqueId(d.nodes, 'nodeId', category === 'MODEL_INFERENCE' ? 'ai' : category === 'HUMAN_DECISION' ? 'human' : category === 'VERIFIER' ? 'check' : 'n');
  d.nodes.push(makeNode(category, id, { config: { name: id }, presentation: { x: Math.round(pos.x), y: Math.round(pos.y) } }));
  return { def: d, id };
}

/** Delete a node and everything that pointed at it: edges, gateway outcomes/defaults, start/terminal marks. */
export function deleteNode(def, id) {
  const d = clone(def);
  d.nodes = d.nodes.filter((n) => n.nodeId !== id);
  const gone = d.edges.filter((e) => e.fromNodeId === id || e.toNodeId === id).map((e) => e.edgeId);
  for (const e of gone) Object.assign(d, removeEdgeFrom(d, e));
  d.terminalNodeIds = d.terminalNodeIds.filter((t) => t !== id);
  if (d.startNodeId === id) d.startNodeId = '';
  d.terminalStateMapping.nodeFailureEscalations = d.terminalStateMapping.nodeFailureEscalations.filter((x) => x.nodeId !== id);
  return d;
}

function removeEdgeFrom(d, edgeId) {
  const e = d.edges.find((x) => x.edgeId === edgeId);
  if (!e) return d;
  d.edges = d.edges.filter((x) => x.edgeId !== edgeId);
  if (e.gatewayId) {
    const g = d.gateways.find((x) => x.gatewayId === e.gatewayId);
    if (g) {
      g.outcomes = g.outcomes.filter((o) => !(o.targetNodeId === e.toNodeId && (e.label == null || o.label === e.label)));
      if (g.defaultOrTerminalFailurePath && g.defaultOrTerminalFailurePath.targetNodeId === e.toNodeId && (e.label == null || g.defaultOrTerminalFailurePath.label === e.label)) g.defaultOrTerminalFailurePath = null;
      if (!g.outcomes.length && !g.defaultOrTerminalFailurePath) d.gateways = d.gateways.filter((x) => x.gatewayId !== g.gatewayId);
      else {
        // a gateway left with a single exit has nothing to decide: it becomes a plain edge again
        const remaining = d.edges.filter((x) => x.gatewayId === g.gatewayId);
        if (remaining.length === 1) { remaining[0].gatewayId = null; d.gateways = d.gateways.filter((x) => x.gatewayId !== g.gatewayId); }
      }
    }
  }
  return d;
}
export function deleteEdge(def, edgeId) { return removeEdgeFrom(clone(def), edgeId); }

/** Connect two nodes. A second exit from the same node turns the exits into a gateway (first exit becomes a
 *  conditional outcome, the new one the default path) so the definition stays valid and the user edits conditions. */
export function connect(def, fromId, toId) {
  const d = clone(def);
  if (d.edges.some((e) => e.fromNodeId === fromId && e.toNodeId === toId)) return { def: d, edgeId: null };
  const edgeId = uniqueId(d.edges, 'edgeId', 'e');
  const existing = d.edges.filter((e) => e.fromNodeId === fromId);
  if (!existing.length) { d.edges.push({ edgeId, fromNodeId: fromId, toNodeId: toId, label: null, gatewayId: null }); return { def: d, edgeId }; }
  let gw = existing.find((e) => e.gatewayId)?.gatewayId;
  if (!gw) {
    gw = uniqueId(d.gateways, 'gatewayId', 'g');
    const first = existing[0]; first.label = first.label || 'if'; first.gatewayId = gw;
    d.gateways.push({ gatewayId: gw, exhaustive: false, outcomes: [{ label: first.label, conditionExpression: 'approved', priority: 1, targetNodeId: first.toNodeId }], defaultOrTerminalFailurePath: null });
    const g = d.gateways.find((x) => x.gatewayId === gw);
    g.defaultOrTerminalFailurePath = { label: 'else', conditionExpression: null, priority: null, targetNodeId: toId };
    d.edges.push({ edgeId, fromNodeId: fromId, toNodeId: toId, label: 'else', gatewayId: gw });
  } else {
    const g = d.gateways.find((x) => x.gatewayId === gw);
    const label = uniqueLabel(g, 'branch');
    if (!g.defaultOrTerminalFailurePath) g.defaultOrTerminalFailurePath = { label: 'else', conditionExpression: null, priority: null, targetNodeId: toId };
    else g.outcomes.push({ label, conditionExpression: null, priority: Math.max(0, ...g.outcomes.map((o) => o.priority ?? 0)) + 1, targetNodeId: toId });
    d.edges.push({ edgeId, fromNodeId: fromId, toNodeId: toId, label: g.defaultOrTerminalFailurePath.targetNodeId === toId && g.defaultOrTerminalFailurePath.label === 'else' ? 'else' : label, gatewayId: gw });
  }
  return { def: d, edgeId };
}
function uniqueLabel(g, base) {
  const used = new Set([...g.outcomes.map((o) => o.label), g.defaultOrTerminalFailurePath?.label]);
  let i = 1; while (used.has(`${base}${i}`)) i++; return `${base}${i}`;
}

export function renameNode(def, oldId, newId) {
  if (!newId || oldId === newId || def.nodes.some((n) => n.nodeId === newId)) return def;
  const d = clone(def);
  const sw = (v) => (v === oldId ? newId : v);
  d.nodes.forEach((n) => { if (n.nodeId === oldId) n.nodeId = newId; });
  d.edges.forEach((e) => { e.fromNodeId = sw(e.fromNodeId); e.toNodeId = sw(e.toNodeId); });
  d.gateways.forEach((g) => { g.outcomes.forEach((o) => { o.targetNodeId = sw(o.targetNodeId); }); if (g.defaultOrTerminalFailurePath) g.defaultOrTerminalFailurePath.targetNodeId = sw(g.defaultOrTerminalFailurePath.targetNodeId); });
  d.startNodeId = sw(d.startNodeId); d.terminalNodeIds = d.terminalNodeIds.map(sw);
  d.terminalStateMapping.nodeFailureEscalations.forEach((x) => { x.nodeId = sw(x.nodeId); });
  return d;
}

export function setStart(def, id) { const d = clone(def); d.startNodeId = id; return d; }
export function toggleTerminal(def, id) {
  const d = clone(def);
  d.terminalNodeIds = d.terminalNodeIds.includes(id) ? d.terminalNodeIds.filter((t) => t !== id) : [...d.terminalNodeIds, id];
  return d;
}

/** Set a nested property by dotted path on a node, edge, gateway or the definition root. */
export function setPath(def, kind, id, path, value) {
  const d = clone(def);
  const target = kind === 'node' ? d.nodes.find((n) => n.nodeId === id) : kind === 'edge' ? d.edges.find((e) => e.edgeId === id) : kind === 'gateway' ? d.gateways.find((g) => g.gatewayId === id) : d;
  if (!target) return def;
  const keys = path.split('.'); let o = target;
  for (const k of keys.slice(0, -1)) { if (o[k] == null || typeof o[k] !== 'object') o[k] = {}; o = o[k]; }
  const last = keys[keys.length - 1];
  if (value === undefined) delete o[last]; else o[last] = value;
  return d;
}

export function addOutcome(def, gatewayId) {
  const d = clone(def); const g = d.gateways.find((x) => x.gatewayId === gatewayId); if (!g) return def;
  const src = d.edges.find((e) => e.gatewayId === gatewayId)?.fromNodeId;
  const target = d.nodes.find((n) => n.nodeId !== src)?.nodeId || src;
  g.outcomes.push({ label: uniqueLabel(g, 'branch'), conditionExpression: null, priority: Math.max(0, ...g.outcomes.map((o) => o.priority ?? 0)) + 1, targetNodeId: target });
  const o = g.outcomes[g.outcomes.length - 1];
  d.edges.push({ edgeId: uniqueId(d.edges, 'edgeId', 'e'), fromNodeId: src, toNodeId: o.targetNodeId, label: o.label, gatewayId });
  return d;
}

/** Retarget an outcome (keeps the matching edge in step with it). */
export function setOutcome(def, gatewayId, index, patch) {
  const d = clone(def); const g = d.gateways.find((x) => x.gatewayId === gatewayId); if (!g) return def;
  const o = index === 'default' ? g.defaultOrTerminalFailurePath : g.outcomes[index]; if (!o) return def;
  const before = { label: o.label, targetNodeId: o.targetNodeId };
  Object.assign(o, patch);
  const edge = d.edges.find((e) => e.gatewayId === gatewayId && e.toNodeId === before.targetNodeId && e.label === before.label);
  if (edge) { edge.label = o.label; edge.toNodeId = o.targetNodeId; }
  return d;
}

export function removeOutcome(def, gatewayId, index) {
  const d = clone(def); const g = d.gateways.find((x) => x.gatewayId === gatewayId); if (!g) return def;
  const o = index === 'default' ? g.defaultOrTerminalFailurePath : g.outcomes[index]; if (!o) return def;
  const edge = d.edges.find((e) => e.gatewayId === gatewayId && e.toNodeId === o.targetNodeId && e.label === o.label);
  return edge ? removeEdgeFrom(d, edge.edgeId) : def;
}

/** Layered auto-layout from the start node (columns by depth). Positions land in `presentation`, which the semantic
 *  digest excludes — moving a node never changes a definition's identity. */
export function autoLayout(def) {
  const d = clone(def); const depth = new Map(); const q = [];
  const start = d.nodes.find((n) => n.nodeId === d.startNodeId) || d.nodes[0];
  if (start) { depth.set(start.nodeId, 0); q.push(start.nodeId); }
  while (q.length) { const id = q.shift(); for (const e of d.edges) if (e.fromNodeId === id && !depth.has(e.toNodeId)) { depth.set(e.toNodeId, depth.get(id) + 1); q.push(e.toNodeId); } }
  let extra = Math.max(0, ...depth.values()) + 1;
  for (const n of d.nodes) if (!depth.has(n.nodeId)) depth.set(n.nodeId, extra++);
  const rows = new Map();
  for (const n of d.nodes) { const c = depth.get(n.nodeId); const r = rows.get(c) || 0; rows.set(c, r + 1); n.presentation = { ...(n.presentation || {}), x: 60 + c * 200, y: 70 + r * 110 }; }
  return d;
}

export function exitsOf(def, nodeId) { return def.edges.filter((e) => e.fromNodeId === nodeId); }
export function nodeLabel(n) { return n.config?.name || n.nodeId; }

/** Mock output for a node on its Nth visit: `config.mock` is `a|b|c` cycling by round ({round} substituted), JSON ok. */
export function mockOutput(node, round) {
  const spec = node.config?.mock;
  const defaults = { VERIFIER: '{"passed":true}', HUMAN_DECISION: '{"decision":"approved"}', WAIT_EVENT_GATE: '{"event":"arrived"}' };
  const raw = spec != null && spec !== '' ? String(spec).split('|')[Math.min(round, String(spec).split('|').length) - 1] : defaults[node.category] ?? `${nodeLabel(node)} output, round ${round}`;
  const text = raw.replace(/\{round\}/g, String(round));
  if (/^\s*[{[]/.test(text)) { try { return JSON.parse(text); } catch { /* fall through to text */ } }
  return text;
}

/* ================================================================== DOM */

const CSS = `
.ls{position:absolute;inset:0;overflow:hidden;font:13px/1.45 system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink);background:var(--ground);color-scheme:dark;--ground:#0e1215;--ink:#d9e2e7;--muted:#8a979f;--accent:#8ff0c4;--warn:#ffb86b;--err:#ff7b7b;--glass:rgba(24,31,36,.62);--line:rgba(217,226,231,.5);--node:rgba(40,52,60,.9)}
.ls.light{color-scheme:light;--ground:#eef1f3;--ink:#1d2a31;--muted:#5d6b73;--accent:#11916a;--glass:rgba(255,255,255,.66);--line:rgba(29,42,49,.32);--node:rgba(255,255,255,.95)}
.ls *{box-sizing:border-box}
.ls svg.cv{position:absolute;inset:0;width:100%;height:100%;touch-action:none;cursor:grab}
.ls svg.cv.pan{cursor:grabbing}
.ls .vg{position:absolute;inset:0;pointer-events:none;background:radial-gradient(ellipse at 50% 45%,transparent 55%,rgba(0,0,0,.35))}
.ls.light .vg{background:radial-gradient(ellipse at 50% 45%,transparent 60%,rgba(0,0,0,.08))}
.ls .gl{position:absolute;background:var(--glass);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);border-radius:20px;padding:12px 14px}
.ls .lb{font:10.5px ui-monospace,SFMono-Regular,Menlo,monospace;text-transform:uppercase;letter-spacing:.14em;color:var(--muted)}
.ls .wm{left:16px;top:14px;display:flex;gap:10px;align-items:center;padding:8px 14px}.ls .wm b{color:var(--accent);font:600 11px ui-monospace,monospace;letter-spacing:.22em}
.ls .bar{left:50%;top:14px;transform:translateX(-50%);display:flex;gap:4px;padding:6px 8px;max-width:calc(100% - 330px);flex-wrap:wrap;justify-content:center}
.ls button{font:inherit;color:var(--ink);background:transparent;border:0;border-radius:12px;padding:8px 12px;min-height:40px;cursor:pointer;font:10.5px ui-monospace,monospace;text-transform:uppercase;letter-spacing:.12em}
.ls button:hover{background:rgba(143,240,196,.12)}.ls button.on{color:var(--accent);box-shadow:inset 0 0 0 1px var(--accent)}.ls button:disabled{opacity:.35;cursor:default}
.ls .pal{left:16px;top:84px;width:150px;display:flex;flex-direction:column;gap:2px}.ls .pal button{text-align:left}
.ls .ins{right:16px;top:14px;bottom:14px;width:300px;overflow:auto}
.ls .dock{left:182px;right:332px;bottom:14px;height:260px;display:flex;flex-direction:column}
.ls .dock .body{overflow:auto;flex:1;margin-top:6px}
.ls .tabs{display:flex;gap:2px;align-items:center}
.ls label{display:block;margin:10px 0 3px}.ls label.inl{display:flex;gap:8px;align-items:center}
.ls input,.ls select,.ls textarea{width:100%;font:12px ui-monospace,monospace;color:var(--ink);background:rgba(127,140,148,.14);border:0;border-radius:10px;padding:8px 10px;min-height:36px}
.ls input[type=checkbox]{width:auto;min-height:0}.ls textarea{resize:vertical;min-height:60px}
.ls .row{display:flex;gap:8px}.ls .row>*{flex:1;min-width:0}
.ls .fd{padding:6px 8px;border-radius:10px;margin:3px 0;cursor:pointer;display:flex;gap:8px;align-items:baseline}.ls .fd:hover{background:rgba(127,140,148,.14)}
.ls .fd .c{font:10px ui-monospace,monospace;letter-spacing:.08em;white-space:nowrap}.ls .fd.ERROR .c{color:var(--err)}.ls .fd.WARNING .c{color:var(--warn)}
.ls .ok{color:var(--accent)}.ls .bad{color:var(--err)}
.ls .tr{font:12px ui-monospace,monospace;padding:3px 0;border-bottom:1px solid rgba(127,140,148,.12);white-space:pre-wrap;word-break:break-word}
.ls .tr .t{color:var(--muted);margin-right:8px}.ls .tr.st{color:var(--accent)}.ls .tr.gw{color:var(--warn)}
.ls .i{display:inline-flex;align-items:center;justify-content:center;width:16px;height:16px;margin-left:6px;border-radius:50%;font:600 10px ui-monospace,monospace;background:rgba(127,140,148,.25);color:var(--muted);cursor:help;position:relative;vertical-align:middle;text-transform:none;letter-spacing:0}
.ls .tip{position:fixed;z-index:9;max-width:260px;background:var(--glass);backdrop-filter:blur(16px);padding:10px 12px;border-radius:14px;font:12px/1.4 system-ui,sans-serif;color:var(--ink);pointer-events:none;box-shadow:0 8px 30px rgba(0,0,0,.3)}
.ls .meter{height:4px;border-radius:2px;background:rgba(127,140,148,.2);overflow:hidden;margin:3px 0 8px}.ls .meter>i{display:block;height:100%;background:var(--accent)}
.ls .prompt{display:flex;gap:8px;align-items:center;padding:8px;border-radius:12px;box-shadow:inset 0 0 0 1px var(--warn);margin:6px 0}
.ls .gx{color:var(--muted);font-size:11px}
@media (max-width:900px){
 .ls .ins{left:8px;right:8px;top:auto;bottom:226px;width:auto;height:34vh}
 .ls .dock{left:8px;right:8px;bottom:8px;height:210px}
 .ls .pal{left:8px;top:68px;width:auto;flex-direction:row;flex-wrap:wrap;max-width:calc(100% - 16px);padding:6px}
 .ls .bar{top:8px;max-width:calc(100% - 16px)}.ls .wm{display:none}
 .ls button{min-height:40px}
}
.ls .nd{cursor:move}.ls .nd text{pointer-events:none;fill:var(--ink)}.ls .nd .gl2{fill:var(--accent);font:600 10px ui-monospace,monospace;letter-spacing:.1em}
.ls .nd rect.b,.ls .nd circle.b{fill:var(--node);stroke:var(--line);stroke-width:1.2}.ls .nd.sel rect.b,.ls .nd.sel circle.b{stroke:var(--accent);stroke-width:2.4}
.ls .nd.act rect.b,.ls .nd.act circle.b{stroke:var(--accent);stroke-width:2.4;stroke-dasharray:6 3}
.ls .nd.err rect.b,.ls .nd.err circle.b{stroke:var(--err);stroke-width:2}.ls .nd.warn rect.b,.ls .nd.warn circle.b{stroke:var(--warn);stroke-width:2}
.ls .ed path{fill:none;stroke:var(--line);stroke-width:1.6}.ls .ed.sel path{stroke:var(--accent);stroke-width:2.4}.ls .ed.hit path.h{stroke:transparent;stroke-width:14}
.ls .ed text{fill:var(--muted);font:10px ui-monospace,monospace;letter-spacing:.06em;pointer-events:none}.ls .ed.gw path{stroke-dasharray:5 3}
.ls .port{fill:var(--accent);opacity:.0;cursor:crosshair}.ls .nd:hover .port,.ls .nd.sel .port{opacity:.9}
`;

const TIPS = {
  validate: 'Runs the same static validation the engine runs at preflight: JSON Schema plus the LOOP-* rules (ids, graph, gateways, cycles, budgets, verification).',
  run: 'Runs this definition on the engine with mock executors. Nothing leaves the browser.',
  mock: 'What the mock executor returns for this node. Separate rounds with |, e.g. needs work|APPROVE. {round} is the visit count. JSON is parsed.',
  gateway: 'Outcomes are tried by priority (lowest first). The first matching condition wins; otherwise the default path. Conditions: a == "x", n >= 3 && ok, status in ["a","b"], approved.',
  budgets: 'Hard limits. Steps, wall clock, tokens, cost, tool calls. 0 for tokens, cost or tool calls means none permitted. Hitting one ends the run as STOPPED_BUDGET.',
  passthrough: 'A structural node: no executor, not a step, not counted in budgets. Start and end markers are usually this.',
  replay: 'Replays the last recorded run with no executors: same steps, same gateway choices, same end state.',
  connect: 'Drag from the dot on a node to another node. A second exit from a node becomes a gateway.',
};

export function mountStudio(root, options = {}) {
  const doc = root.ownerDocument; const win = doc.defaultView;
  if (!doc.getElementById('ls-css')) { const st = doc.createElement('style'); st.id = 'ls-css'; st.textContent = CSS; doc.head.appendChild(st); }
  root.classList.add('ls'); if (options.theme === 'light') root.classList.add('light');
  const S = {
    def: options.definition ? clone(options.definition) : autoLayout(newDefinition()), sel: null, // { kind, id }
    undo: [], redo: [], report: null, view: { x: 0, y: 0, k: 1 }, tab: 'findings', trace: [], handle: null, active: new Set(), usage: null,
    last: null, sound: options.sound ?? false, objective: options.objective ?? 'Improve the draft.', params: options.params ?? '{}', prompt: null,
  };
  const ns = 'http://www.w3.org/2000/svg';
  const h = (tag, attrs = {}, ...kids) => { const el = doc.createElement(tag); for (const [k, v] of Object.entries(attrs)) { if (k === 'class') el.className = v; else if (k.startsWith('on')) el.addEventListener(k.slice(2), v); else if (v !== false && v != null) el.setAttribute(k, v === true ? '' : v); } for (const kid of kids.flat()) el.append(kid?.nodeType ? kid : doc.createTextNode(kid ?? '')); return el; };
  const sv = (tag, attrs = {}, ...kids) => { const el = doc.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) { if (k.startsWith('on')) el.addEventListener(k.slice(2), v); else el.setAttribute(k, v); } for (const kid of kids.flat()) el.append(kid?.nodeType ? kid : doc.createTextNode(kid)); return el; };
  const tip = (key) => { const i = h('span', { class: 'i' }, 'i'); i.addEventListener('pointerenter', () => { const t = h('div', { class: 'tip' }, TIPS[key]); const r = i.getBoundingClientRect(); t.style.left = Math.min(r.left, win.innerWidth - 280) + 'px'; t.style.top = r.bottom + 6 + 'px'; root.append(t); i._t = t; }); i.addEventListener('pointerleave', () => i._t?.remove()); i.addEventListener('click', () => { i._t ? (i._t.remove(), (i._t = null)) : i.dispatchEvent(new win.Event('pointerenter')); }); return i; };

  let audio; const blip = (f = 660) => { if (!S.sound) return; try { audio = audio || new (win.AudioContext || win.webkitAudioContext)(); const o = audio.createOscillator(), g = audio.createGain(); o.type = 'square'; o.frequency.value = f; g.gain.value = 0.03; o.connect(g); g.connect(audio.destination); o.start(); o.stop(audio.currentTime + 0.05); } catch { /* no audio */ } };

  const commit = (d, label) => { S.undo.push(S.def); if (S.undo.length > 100) S.undo.shift(); S.redo = []; S.def = d; S.report = null; blip(); options.onChange?.(S.def, label); render(); };
  const doUndo = () => { if (S.undo.length) { S.redo.push(S.def); S.def = S.undo.pop(); S.report = null; blip(440); render(); options.onChange?.(S.def, 'undo'); } };
  const doRedo = () => { if (S.redo.length) { S.undo.push(S.def); S.def = S.redo.pop(); S.report = null; blip(520); render(); options.onChange?.(S.def, 'redo'); } };

  // ---- skeleton
  const svg = sv('svg', { class: 'cv' }); const gEdges = sv('g'), gNodes = sv('g'), gView = sv('g'); gView.append(gEdges, gNodes); svg.append(sv('defs', {}, sv('marker', { id: 'ar', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' }, sv('path', { d: 'M0 0L10 5L0 10z', fill: 'currentColor' }))), gView);
  svg.style.color = 'var(--line)';
  const wm = h('div', { class: 'gl wm' }, h('b', {}, 'LOOP'), h('span', { class: 'lb' }, 'studio / engine 1.0'));
  const bar = h('div', { class: 'gl bar' }); const pal = h('div', { class: 'gl pal' }); const ins = h('div', { class: 'gl ins' }); const dock = h('div', { class: 'gl dock' });
  root.append(svg, h('div', { class: 'vg' }), wm, bar, pal, ins, dock);

  const applyView = () => gView.setAttribute('transform', `translate(${S.view.x} ${S.view.y}) scale(${S.view.k})`);
  const pt = (ev) => { const r = svg.getBoundingClientRect(); return { x: (ev.clientX - r.left - S.view.x) / S.view.k, y: (ev.clientY - r.top - S.view.y) / S.view.k }; };
  const NW = 150, NH = 56, PR = 26;
  const isPass = (n) => !!n.config?.passthrough;
  const posOf = (n) => ({ x: n.presentation?.x ?? 0, y: n.presentation?.y ?? 0 });
  const centre = (n) => { const p = posOf(n); return isPass(n) ? { x: p.x, y: p.y } : { x: p.x + NW / 2, y: p.y + NH / 2 }; };
  const edgePoint = (n, toward) => { const c = centre(n); const dx = toward.x - c.x, dy = toward.y - c.y; const d = Math.hypot(dx, dy) || 1; if (isPass(n)) return { x: c.x + (dx / d) * PR, y: c.y + (dy / d) * PR }; const sx = (NW / 2) / (Math.abs(dx) || 1e-6), sy = (NH / 2) / (Math.abs(dy) || 1e-6); const s = Math.min(sx, sy); return { x: c.x + dx * s, y: c.y + dy * s }; };

  const findingsByObj = () => { const m = new Map(); for (const f of S.report?.findings || []) if (f.objectId != null) { const a = m.get(f.objectId) || []; a.push(f); m.set(f.objectId, a); } return m; };

  function drawGraph() {
    gEdges.replaceChildren(); gNodes.replaceChildren();
    const fo = findingsByObj(); const byId = new Map(S.def.nodes.map((n) => [n.nodeId, n]));
    // parallel / reverse edges bend apart
    const key = (e) => [e.fromNodeId, e.toNodeId].sort().join('|'); const seen = new Map();
    for (const e of S.def.edges) {
      const a = byId.get(e.fromNodeId), b = byId.get(e.toNodeId); if (!a || !b) continue;
      const n = seen.get(key(e)) || 0; seen.set(key(e), n + 1);
      const ca = centre(a), cb = centre(b);
      const self = a === b;
      let d, lx, ly;
      if (self) { const p = posOf(a); d = `M ${p.x + NW - 20} ${p.y} C ${p.x + NW + 40} ${p.y - 60}, ${p.x + NW + 60} ${p.y + 10}, ${p.x + NW} ${p.y + NH / 2}`; lx = p.x + NW + 30; ly = p.y - 30; }
      else {
        const dx = cb.x - ca.x, dy = cb.y - ca.y, len = Math.hypot(dx, dy) || 1; const bend = (n % 2 ? 1 : -1) * Math.ceil(n / 2) * 34 + (S.def.edges.some((x) => x.fromNodeId === e.toNodeId && x.toNodeId === e.fromNodeId) ? 26 : 0);
        const mx = (ca.x + cb.x) / 2 + (-dy / len) * bend, my = (ca.y + cb.y) / 2 + (dx / len) * bend;
        const p1 = edgePoint(a, { x: mx, y: my }), p2 = edgePoint(b, { x: mx, y: my });
        d = `M ${p1.x} ${p1.y} Q ${mx} ${my} ${p2.x} ${p2.y}`; lx = (p1.x + 2 * mx + p2.x) / 4; ly = (p1.y + 2 * my + p2.y) / 4 - 5;
      }
      const sel = S.sel?.kind === 'edge' && S.sel.id === e.edgeId;
      const g = sv('g', { class: `ed hit${e.gatewayId ? ' gw' : ''}${sel ? ' sel' : ''}`, onpointerdown: (ev) => { ev.stopPropagation(); S.sel = { kind: 'edge', id: e.edgeId }; blip(560); render(); } },
        sv('path', { d, 'marker-end': 'url(#ar)', style: 'color:' + (sel ? 'var(--accent)' : 'var(--line)') }), sv('path', { class: 'h', d }));
      if (e.label) g.append(sv('text', { x: lx, y: ly, 'text-anchor': 'middle' }, e.label));
      gEdges.append(g);
    }
    for (const n of S.def.nodes) {
      const p = posOf(n); const sel = S.sel?.kind === 'node' && S.sel.id === n.nodeId; const f = fo.get(n.nodeId) || [];
      const cls = ['nd', sel && 'sel', S.active.has(n.nodeId) && 'act', f.some((x) => x.severity === 'ERROR') ? 'err' : f.length && 'warn'].filter(Boolean).join(' ');
      const g = sv('g', { class: cls, 'data-id': n.nodeId });
      if (isPass(n)) {
        const term = S.def.terminalNodeIds.includes(n.nodeId), start = S.def.startNodeId === n.nodeId;
        g.append(sv('circle', { class: 'b', cx: p.x, cy: p.y, r: PR }));
        if (term) g.append(sv('circle', { class: 'b', cx: p.x, cy: p.y, r: PR - 6, style: 'fill:none' }));
        g.append(sv('text', { x: p.x, y: p.y + 4, 'text-anchor': 'middle', style: 'font:10px ui-monospace,monospace;letter-spacing:.08em' }, start ? 'START' : term ? 'END' : '·'));
        g.append(sv('text', { x: p.x, y: p.y + PR + 14, 'text-anchor': 'middle', style: 'font:10px ui-monospace,monospace;fill:var(--muted)' }, nodeLabel(n)));
        g.append(sv('circle', { class: 'port', cx: p.x + PR, cy: p.y, r: 5, 'data-port': n.nodeId }));
      } else {
        g.append(sv('rect', { class: 'b', x: p.x, y: p.y, width: NW, height: NH, rx: 14 }));
        g.append(sv('text', { class: 'gl2', x: p.x + 12, y: p.y + 21 }, glyphOf(n.category)));
        g.append(sv('text', { x: p.x + 38, y: p.y + 21, style: 'font:600 12px system-ui' }, nodeLabel(n).slice(0, 16)));
        g.append(sv('text', { x: p.x + 12, y: p.y + 41, style: 'font:9.5px ui-monospace,monospace;letter-spacing:.1em;fill:var(--muted)' }, n.category.replace(/_/g, ' ').toLowerCase().slice(0, 22)));
        if (S.def.startNodeId === n.nodeId) g.append(sv('text', { x: p.x + NW - 8, y: p.y + 15, 'text-anchor': 'end', style: 'font:9px ui-monospace,monospace;fill:var(--accent)' }, 'START'));
        else if (S.def.terminalNodeIds.includes(n.nodeId)) g.append(sv('text', { x: p.x + NW - 8, y: p.y + 15, 'text-anchor': 'end', style: 'font:9px ui-monospace,monospace;fill:var(--accent)' }, 'END'));
        if (f.length) g.append(sv('text', { x: p.x + NW - 8, y: p.y + 41, 'text-anchor': 'end', style: `font:700 11px ui-monospace,monospace;fill:var(${f.some((x) => x.severity === 'ERROR') ? '--err' : '--warn'})` }, f.some((x) => x.severity === 'ERROR') ? '! ' + f.length : '~ ' + f.length));
        g.append(sv('circle', { class: 'port', cx: p.x + NW, cy: p.y + NH / 2, r: 5, 'data-port': n.nodeId }));
      }
      g.addEventListener('pointerdown', (ev) => nodeDown(ev, n));
      gNodes.append(g);
    }
    applyView();
  }

  // ---- pointer interaction: drag node, connect from port, pan, zoom
  let drag = null; let ghost = null;
  function nodeDown(ev, n) {
    ev.stopPropagation(); svg.setPointerCapture?.(ev.pointerId);
    const port = ev.target.getAttribute?.('data-port');
    S.sel = { kind: 'node', id: n.nodeId };
    if (port) { drag = { type: 'connect', from: n.nodeId }; ghost = sv('path', { d: '', style: 'stroke:var(--accent);fill:none;stroke-width:2;stroke-dasharray:4 3' }); gEdges.append(ghost); }
    else { const p = pt(ev); const pos = posOf(n); drag = { type: 'move', id: n.nodeId, dx: p.x - pos.x, dy: p.y - pos.y, moved: false, before: S.def }; }
    blip(600); renderPanels();
  }
  svg.addEventListener('pointerdown', (ev) => { if (ev.target !== svg && !ev.target.closest?.('.cv') ) return; if (ev.target === svg) { drag = { type: 'pan', sx: ev.clientX, sy: ev.clientY, vx: S.view.x, vy: S.view.y }; svg.classList.add('pan'); S.sel = null; render(); } });
  svg.addEventListener('pointermove', (ev) => {
    if (!drag) return;
    if (drag.type === 'pan') { S.view.x = drag.vx + ev.clientX - drag.sx; S.view.y = drag.vy + ev.clientY - drag.sy; applyView(); }
    else if (drag.type === 'move') { const p = pt(ev); const n = S.def.nodes.find((x) => x.nodeId === drag.id); n.presentation = { ...(n.presentation || {}), x: Math.round(p.x - drag.dx), y: Math.round(p.y - drag.dy) }; drag.moved = true; drawGraph(); }
    else if (drag.type === 'connect') { const a = S.def.nodes.find((x) => x.nodeId === drag.from); const c = centre(a), p = pt(ev); ghost.setAttribute('d', `M ${c.x} ${c.y} L ${p.x} ${p.y}`); }
  });
  const up = (ev) => {
    if (!drag) return; const d = drag; drag = null; svg.classList.remove('pan');
    if (d.type === 'move' && d.moved) { const moved = S.def; S.def = d.before; commit(moved, 'move'); }
    else if (d.type === 'connect') { ghost?.remove(); const el = doc.elementFromPoint(ev.clientX, ev.clientY)?.closest?.('.nd'); const to = el?.getAttribute('data-id'); if (to) { const { def, edgeId } = connect(S.def, d.from, to); if (edgeId) { S.sel = { kind: 'edge', id: edgeId }; commit(def, 'connect'); } } }
  };
  svg.addEventListener('pointerup', up); svg.addEventListener('pointercancel', up);
  svg.addEventListener('wheel', (ev) => { ev.preventDefault(); const r = svg.getBoundingClientRect(); const mx = ev.clientX - r.left, my = ev.clientY - r.top; const k2 = Math.min(2.5, Math.max(0.35, S.view.k * (ev.deltaY < 0 ? 1.1 : 0.9))); S.view.x = mx - ((mx - S.view.x) / S.view.k) * k2; S.view.y = my - ((my - S.view.y) / S.view.k) * k2; S.view.k = k2; applyView(); }, { passive: false });
  const onKey = (ev) => {
    if (/INPUT|TEXTAREA|SELECT/.test(ev.target.tagName)) return;
    if ((ev.metaKey || ev.ctrlKey) && ev.key === 'z') { ev.preventDefault(); ev.shiftKey ? doRedo() : doUndo(); }
    else if (ev.key === 'Delete' || ev.key === 'Backspace') deleteSelected();
  };
  win.addEventListener('keydown', onKey);

  function deleteSelected() {
    if (!S.sel) return;
    if (S.sel.kind === 'node') commit(deleteNode(S.def, S.sel.id), 'delete node'); else if (S.sel.kind === 'edge') commit(deleteEdge(S.def, S.sel.id), 'delete edge');
    S.sel = null; render();
  }

  // ---- toolbar, palette
  const btn = (label, fn, extra = {}) => h('button', { onclick: fn, ...extra }, label);
  function drawBar() {
    bar.replaceChildren(
      btn('Undo', doUndo, { disabled: !S.undo.length }), btn('Redo', doRedo, { disabled: !S.redo.length }),
      btn('Delete', deleteSelected, { disabled: !S.sel }), btn('Layout', () => commit(autoLayout(S.def), 'layout')),
      h('span', {}, btn('Validate', () => { validate(); S.tab = 'findings'; render(); }), tip('validate')),
      h('span', {}, btn(S.handle ? 'Stop' : 'Run', () => (S.handle ? S.handle.cancel() : run()), { class: S.handle ? 'on' : '' }), tip('run')),
      btn('Export', exportJson), btn('Import', importJson), btn(S.sound ? 'Sound on' : 'Sound off', () => { S.sound = !S.sound; blip(); render(); }),
      btn(root.classList.contains('light') ? 'Dark' : 'Light', () => { root.classList.toggle('light'); render(); }),
    );
  }
  function drawPalette() {
    pal.replaceChildren(h('div', { class: 'lb' }, 'Add', tip('connect')), ...CATEGORIES.map(([cat, g, name]) => btn(`${g} ${name}`, () => { const c = { x: (240 - S.view.x) / S.view.k + Math.random() * 60, y: (200 - S.view.y) / S.view.k + Math.random() * 60 }; const r = addNode(S.def, cat, c); S.sel = { kind: 'node', id: r.id }; commit(r.def, 'add node'); })));
  }

  // ---- inspector
  const field = (label, value, onSet, { type = 'text', tipKey, ta } = {}) => {
    const el = ta ? h('textarea', { rows: 3 }) : h('input', { type }); el.value = value ?? '';
    el.addEventListener('change', () => onSet(type === 'number' ? (el.value === '' ? null : Number(el.value)) : el.value));
    return h('div', {}, h('label', { class: 'lb' }, label, tipKey ? tip(tipKey) : ''), el);
  };
  const select = (label, value, options, onSet) => { const el = h('select', {}, options.map((o) => h('option', { value: o }, o))); el.value = value ?? ''; el.addEventListener('change', () => onSet(el.value)); return h('div', {}, h('label', { class: 'lb' }, label), el); };
  const check = (label, value, onSet, tipKey) => { const el = h('input', { type: 'checkbox' }); el.checked = !!value; el.addEventListener('change', () => onSet(el.checked)); return h('label', { class: 'lb inl' }, el, label, tipKey ? tip(tipKey) : ''); };
  const nodeIds = () => S.def.nodes.map((n) => n.nodeId);

  function drawInspector() {
    const d = S.def; const kids = [];
    if (S.sel?.kind === 'node') {
      const n = d.nodes.find((x) => x.nodeId === S.sel.id);
      if (n) {
        const set = (path, v) => commit(setPath(d, 'node', n.nodeId, path, v), `edit ${path}`);
        kids.push(h('div', { class: 'lb' }, 'Node'),
          field('Id', n.nodeId, (v) => { const nd = renameNode(d, n.nodeId, v.trim()); if (nd !== d) { S.sel = { kind: 'node', id: v.trim() }; commit(nd, 'rename'); } }),
          field('Name', n.config?.name ?? '', (v) => set('config.name', v)),
          select('Category', n.category, CATEGORIES.map((c) => c[0]), (v) => set('category', v)),
          field('Explanation', n.explanation, (v) => set('explanation', v), { ta: true }),
          check('Passthrough (structural)', n.config?.passthrough, (v) => set('config.passthrough', v || undefined), 'passthrough'),
          h('div', { class: 'row' }, btn(d.startNodeId === n.nodeId ? 'Is start' : 'Set start', () => commit(setStart(d, n.nodeId), 'start'), { class: d.startNodeId === n.nodeId ? 'on' : '' }), btn(d.terminalNodeIds.includes(n.nodeId) ? 'Is end' : 'Mark end', () => commit(toggleTerminal(d, n.nodeId), 'terminal'), { class: d.terminalNodeIds.includes(n.nodeId) ? 'on' : '' })));
        if (!isPass(n)) kids.push(
          field('Role', n.config?.role ?? '', (v) => set('config.role', v || undefined)), field('Model', n.config?.model ?? '', (v) => set('config.model', v || undefined)),
          field('System prompt', n.config?.systemPrompt ?? '', (v) => set('config.systemPrompt', v || undefined), { ta: true }),
          field('Mock output', n.config?.mock ?? '', (v) => set('config.mock', v || undefined), { tipKey: 'mock' }),
          h('div', { class: 'row' }, field('Retries', n.retryPolicy?.maxRetries ?? 0, (v) => set('retryPolicy.maxRetries', v ?? 0), { type: 'number' }), field('Timeout s', n.timeoutPolicy?.timeoutSeconds ?? '', (v) => set('timeoutPolicy.timeoutSeconds', v), { type: 'number' })),
          select('Side effects', n.sideEffectClass, ['NONE', 'REVERSIBLE_LOCAL', 'PERSISTENT_LOCAL', 'EXTERNAL_VISIBLE', 'DESTRUCTIVE'], (v) => set('sideEffectClass', v)),
          select('On failure', n.onFailureTerminalState, ['FAILED_SAFE', 'STOPPED_BUDGET', 'STOPPED_POLICY', 'FAILED_SIDE_EFFECTS_POSSIBLE', 'TARGET_STATE_UNKNOWN', 'CANCELLED'], (v) => set('onFailureTerminalState', v)));
        const exits = exitsOf(d, n.nodeId);
        if (exits.length) kids.push(h('label', { class: 'lb' }, 'Exits'), ...exits.map((e) => h('div', { class: 'fd', onclick: () => { S.sel = { kind: 'edge', id: e.edgeId }; render(); } }, h('span', { class: 'c' }, e.gatewayId || 'plain'), `${e.label || ''} > ${e.toNodeId}`)));
        const g = exits.find((e) => e.gatewayId)?.gatewayId; if (g) kids.push(...gatewayEditor(g));
      }
    } else if (S.sel?.kind === 'edge') {
      const e = d.edges.find((x) => x.edgeId === S.sel.id);
      if (e) {
        kids.push(h('div', { class: 'lb' }, 'Edge ', e.edgeId), h('div', { class: 'gx' }, `${e.fromNodeId} > ${e.toNodeId}`),
          field('Label', e.label ?? '', (v) => commit(setPath(d, 'edge', e.edgeId, 'label', v || null), 'label')), btn('Delete edge', deleteSelected));
        if (e.gatewayId) kids.push(...gatewayEditor(e.gatewayId));
      }
    } else {
      const b = d.budgets;
      const bset = (p, v) => commit(setPath(d, 'root', null, p, v), 'budget');
      kids.push(h('div', { class: 'lb' }, 'Loop'),
        field('Loop id', d.loopId, (v) => commit(setPath(d, 'root', null, 'loopId', v), 'id')), field('Objective', d.objective, (v) => commit(setPath(d, 'root', null, 'objective', v), 'objective'), { ta: true }),
        h('label', { class: 'lb' }, 'Budgets', tip('budgets')),
        h('div', { class: 'row' }, field('Steps', b.steps, (v) => bset('budgets.steps', v), { type: 'number' }), field('Wall s', b.wallClockSeconds, (v) => bset('budgets.wallClockSeconds', v), { type: 'number' })),
        h('div', { class: 'row' }, field('Tokens', b.tokens, (v) => bset('budgets.tokens', v), { type: 'number' }), field('Cost', b.cost?.amount, (v) => bset('budgets.cost.amount', v), { type: 'number' })),
        field('Tool calls', b.toolCalls, (v) => bset('budgets.toolCalls', v), { type: 'number' }),
        check('Require verification for success', d.verificationPolicy.requireVerificationForSuccess, (v) => bset('verificationPolicy.requireVerificationForSuccess', v)),
        check('Allow unverified success', d.terminalStateMapping.unverifiedSuccessAllowed, (v) => bset('terminalStateMapping.unverifiedSuccessAllowed', v)));
    }
    ins.replaceChildren(...kids);
  }

  function gatewayEditor(gid) {
    const d = S.def; const g = d.gateways.find((x) => x.gatewayId === gid); if (!g) return [];
    const out = [h('label', { class: 'lb' }, `Gateway ${gid}`, tip('gateway')), check('Exhaustive', g.exhaustive, (v) => commit(setPath(d, 'gateway', gid, 'exhaustive', v), 'exhaustive'))];
    const outcomeRow = (o, idx) => {
      const cond = o.conditionExpression ?? ''; const c = cond ? checkCondition(cond) : { ok: true };
      return h('div', { class: 'prompt', style: 'display:block;box-shadow:none;background:rgba(127,140,148,.1)' },
        h('div', { class: 'row' }, field('Label', o.label, (v) => commit(setOutcome(d, gid, idx, { label: v }), 'outcome')), idx === 'default' ? h('div', {}) : field('Priority', o.priority ?? '', (v) => commit(setOutcome(d, gid, idx, { priority: v }), 'outcome'), { type: 'number' })),
        idx === 'default' ? '' : field('Condition', cond, (v) => commit(setOutcome(d, gid, idx, { conditionExpression: v || null }), 'outcome')),
        c.ok ? '' : h('div', { class: 'bad gx' }, c.error), select('Target', o.targetNodeId, nodeIds(), (v) => commit(setOutcome(d, gid, idx, { targetNodeId: v }), 'outcome')),
        btn('Remove', () => commit(removeOutcome(d, gid, idx), 'remove outcome')));
    };
    g.outcomes.forEach((o, i) => out.push(outcomeRow(o, i)));
    if (g.defaultOrTerminalFailurePath) out.push(h('div', { class: 'lb' }, 'Default path'), outcomeRow(g.defaultOrTerminalFailurePath, 'default'));
    out.push(btn('Add outcome', () => commit(addOutcome(d, gid), 'add outcome')));
    return out;
  }

  // ---- dock: findings + run
  function validate() { S.report = validateDefinition(S.def); blip(S.report.valid ? 760 : 220); return S.report; }
  function drawDock() {
    const tabs = h('div', { class: 'tabs' }, btn('Findings' + (S.report ? ` ${S.report.errors}/${S.report.warnings}` : ''), () => { S.tab = 'findings'; render(); }, { class: S.tab === 'findings' ? 'on' : '' }), btn('Run', () => { S.tab = 'run'; render(); }, { class: S.tab === 'run' ? 'on' : '' }), h('span', { class: 'lb', style: 'margin-left:auto' }, S.handle ? S.handle.state : S.last?.run.runState || 'idle'));
    const body = h('div', { class: 'body' });
    if (S.tab === 'findings') {
      if (!S.report) body.append(h('div', { class: 'gx' }, 'Press Validate.'));
      else if (!S.report.findings.length) body.append(h('div', { class: 'ok lb' }, `Valid. ${S.report.rulesetVersion}`));
      else for (const f of S.report.findings) body.append(h('div', { class: `fd ${f.severity}`, onclick: () => { if (f.objectId != null) { const isNode = S.def.nodes.some((n) => n.nodeId === f.objectId); S.sel = isNode ? { kind: 'node', id: f.objectId } : f.objectType === 'edge' ? { kind: 'edge', id: f.objectId } : null; render(); } } }, h('span', { class: 'c' }, f.code), f.message));
    } else {
      const obj = h('input', { value: S.objective }); obj.addEventListener('change', () => { S.objective = obj.value; });
      const prm = h('input', { value: S.params, placeholder: '{"key":"value"}' }); prm.addEventListener('change', () => { S.params = prm.value; });
      body.append(h('div', { class: 'row' }, h('div', {}, h('label', { class: 'lb' }, 'Objective'), obj), h('div', {}, h('label', { class: 'lb' }, 'Params JSON'), prm)));
      if (S.last?.recording && !S.handle) body.append(h('div', {}, btn('Replay last run', replay), tip('replay')));
      if (S.usage) { const L = S.usage.limits; const bars = [['steps', S.usage.steps, L.steps], ['tokens', S.usage.tokens, L.tokens], ['cost', S.usage.cost, L.cost], ['tool calls', S.usage.toolCalls, L.toolCalls]]; for (const [name, used, lim] of bars) body.append(h('div', { class: 'lb' }, `${name} ${used}${lim != null ? ' / ' + lim : ''}`), h('div', { class: 'meter' }, h('i', { style: `width:${lim ? Math.min(100, (used / lim) * 100) : 0}%` }))); }
      if (S.prompt) body.append(h('div', { class: 'prompt' }, h('span', { class: 'lb' }, `${S.prompt.node.nodeId} needs a decision`), btn('Approve', () => S.prompt.answer({ decision: 'approved' })), btn('Reject', () => S.prompt.answer({ decision: 'rejected' }))));
      for (const t of S.trace) body.append(h('div', { class: `tr ${t.cls || ''}` }, h('span', { class: 't' }, t.t), t.text));
    }
    dock.replaceChildren(tabs, body);
  }

  const say = (text, cls) => { S.trace.push({ t: String(S.trace.length + 1).padStart(2, '0'), text, cls }); };
  function mockExecutors() {
    const ex = options.executors ? { ...options.executors } : {};
    const mk = (cat) => async (node, _input, ctx) => {
      await new Promise((r) => setTimeout(r, options.mockDelayMs ?? 180));
      if (cat === 'HUMAN_DECISION' && !options.autoDecide) return new Promise((res, rej) => { S.prompt = { node, answer: (v) => { S.prompt = null; res(v); } }; ctx.signal?.addEventListener('abort', () => { S.prompt = null; const e = new Error('aborted'); e.name = 'AbortError'; rej(e); }); render(); });
      ctx.reportUsage?.({ tokensIn: cat === 'MODEL_INFERENCE' ? 120 : undefined, tokensOut: cat === 'MODEL_INFERENCE' ? 60 : undefined, estimated: true });
      return mockOutput(node, ctx.round);
    };
    for (const [cat] of CATEGORIES) if (!ex[cat]) ex[cat] = mk(cat);
    return ex;
  }
  function listen(handle) {
    handle.on('state', (e) => { say(`${e.from || '-'} > ${e.to}`, 'st'); render(); });
    handle.on('node:started', (e) => { S.active = new Set([e.nodeId]); say(`start ${e.nodeId} (round ${e.round})`); drawGraph(); });
    handle.on('node:finished', (e) => { const o = typeof e.step.output === 'string' ? e.step.output : JSON.stringify(e.step.output); say(`done  ${e.step.nodeId}: ${o.slice(0, 140)}`); S.active = new Set(); drawGraph(); });
    handle.on('gateway', (e) => say(`gateway ${e.gatewayId || e.nodeId}: took '${e.label}' (${e.via}) > ${e.toNodeId}`, 'gw'));
    handle.on('budget', (e) => { S.usage = e; drawDock(); });
    handle.on('verifier', (e) => say(`verifier ${e.nodeId}: ${e.passed ? 'pass' : 'fail'}`));
  }
  async function run() {
    let params; try { params = JSON.parse(S.params || '{}'); } catch { say('Params JSON is not valid.', 'gw'); S.tab = 'run'; render(); return; }
    S.trace = []; S.usage = null; S.tab = 'run';
    const driver = new LoopRunDriver({ executors: mockExecutors() });
    S.handle = driver.start(S.def, S.objective, { params, hardCap: options.hardCap ?? 40 }); listen(S.handle);
    render();
    const out = await S.handle.promise; S.handle = null; S.active = new Set(); S.last = out; S.prompt = null;
    say(`${out.run.runState}: ${out.run.terminalReason?.category} - ${out.run.terminalReason?.detail}`, 'st'); options.onRun?.(out); render();
  }
  async function replay() {
    S.trace = []; S.tab = 'run';
    try { const out = await replayRun(S.def, S.last.recording); S.last = out; say(`replay reproduced ${out.result.steps.length} steps; ${out.run.runState}`, 'st'); } catch (e) { say(`replay failed: ${e.message}`, 'gw'); }
    render();
  }
  function exportJson() { const blob = new win.Blob([JSON.stringify(S.def, null, 2)], { type: 'application/json' }); const a = h('a', { href: win.URL.createObjectURL(blob), download: `${S.def.loopId}.loop.json` }); a.click(); }
  function importJson() { const i = h('input', { type: 'file', accept: '.json,application/json' }); i.addEventListener('change', async () => { try { S.sel = null; commit(JSON.parse(await i.files[0].text()), 'import'); } catch (e) { say('Import failed: ' + e.message, 'gw'); S.tab = 'run'; render(); } }); i.click(); }

  function renderPanels() { drawBar(); drawPalette(); drawInspector(); drawDock(); }
  function render() { drawGraph(); renderPanels(); }
  const fit = () => { const xs = S.def.nodes.map((n) => posOf(n)); if (!xs.length) return; const minX = Math.min(...xs.map((p) => p.x)) - 40, maxX = Math.max(...xs.map((p) => p.x)) + NW + 40, minY = Math.min(...xs.map((p) => p.y)) - 40, maxY = Math.max(...xs.map((p) => p.y)) + NH + 40; const r = root.getBoundingClientRect(); const w = Math.max(300, (r.width || 1000) - 520), hgt = Math.max(200, (r.height || 700) - 260); const k = Math.min(1.2, w / (maxX - minX), hgt / (maxY - minY)); S.view = { k, x: 180 + (w - (maxX - minX) * k) / 2 - minX * k, y: 80 + (hgt - (maxY - minY) * k) / 2 - minY * k }; };
  if (S.def.nodes.some((n) => !n.presentation)) S.def = autoLayout(S.def);
  fit(); render();

  return {
    getDefinition: () => clone(S.def), setDefinition(d) { S.sel = null; commit(clone(d), 'set'); fit(); render(); }, validate, run, replay, fit: () => { fit(); render(); },
    select: (kind, id) => { S.sel = { kind, id }; render(); }, undo: doUndo, redo: doRedo, getState: () => ({ sel: S.sel, report: S.report, trace: S.trace, last: S.last }),
    destroy() { win.removeEventListener('keydown', onKey); S.handle?.cancel(); root.replaceChildren(); root.classList.remove('ls', 'light'); },
  };
}

export { RUN_STATES, TERMINAL, effectiveLimits };
