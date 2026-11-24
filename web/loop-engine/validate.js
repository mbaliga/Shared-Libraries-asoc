/* Static validation of a LoopDefinition v2 (LOOP_ENGINEERING_SPEC_V2.1 §7, §19).
   Layer 1: JSON Schema (schemas/loop-definition.v2.schema.json) through ./jsonschema.js.
   Layer 2: the semantic rules JSON Schema cannot express, each with its stable code from
   loop-validation-rules.v1.json (rulesetVersion fb-loop-validation-rules-1):
     LOOP-ID-001      duplicate node/edge/gateway/slot/verifier ids
     LOOP-GRAPH-001   missing start / terminal, dangling edge references, no terminal reachable
     LOOP-GRAPH-002   unreachable node (WARNING)
     LOOP-GRAPH-003   gateway gaps and ambiguous edges (non-exhaustive without default, bad/ill-typed condition,
                      outcome with no matching edge, duplicate/missing priorities, plain fan-out)
     LOOP-GRAPH-004   cycle with no statically visible bound, or a cycle with no way out
     LOOP-SCHEMA-001  `${param}` used but not declared by the input schema
     LOOP-SCHEMA-002  inline schema that is not a plausible JSON Schema
     LOOP-CAP-001     requested capability not in options.availableCapabilities (only when supplied)
     LOOP-BUDGET-001  external-effect node while a minimum budget dimension is absent
     LOOP-BUDGET-002  recursive subloop without a depth limit
     LOOP-VERIFY-001  destructive node with no compensation and no non-compensability acknowledgement
     LOOP-VERIFY-002  verified success required, but no verifier can produce it
     LOOP-COMPAT-001  node permitted targets disjoint from the definition's permitted target types
   Package-level rules (PKG, TEST, LINEAGE, ID-002, COMPAT-002) concern packages, installs and updates, not a bare
   definition, and are out of scope here. Findings are sorted deterministically (§19). */

import { loopDefinitionSchema, validationRules } from './schemas.js';
import { validate as validateSchema } from './jsonschema.js';
import { check as checkCondition } from './conditions.js';
import { placeholdersIn } from './params.js';

export const RULESET_VERSION = validationRules.rulesetVersion;
const RULES = Object.fromEntries(validationRules.rules.map((r) => [r.code, r]));
const EXTERNAL_EFFECTS = new Set(['PERSISTENT_LOCAL', 'EXTERNAL_VISIBLE', 'DESTRUCTIVE']);
const SEV_ORDER = { ERROR: 0, WARNING: 1, INFO: 2 };

function finding(code, message, extra = {}) {
  const rule = RULES[code];
  return { code, severity: rule ? rule.severity : 'ERROR', message, remediation: rule ? rule.remediation : undefined, ...extra };
}

/** Every edge the runner can follow: plain edges plus gateway outcome targets. */
export function successors(def) {
  const map = new Map();
  const add = (a, b) => { if (!map.has(a)) map.set(a, new Set()); map.get(a).add(b); };
  for (const e of def.edges || []) add(e.fromNodeId, e.toNodeId);
  return map;
}

function reachableFrom(start, succ) {
  const seen = new Set();
  const stack = [start];
  while (stack.length) {
    const n = stack.pop();
    if (seen.has(n)) continue;
    seen.add(n);
    for (const m of succ.get(n) || []) stack.push(m);
  }
  return seen;
}

/** Tarjan SCCs; returns components that contain a cycle (size>1, or a self-loop). */
function cyclicComponents(nodeIds, succ) {
  let index = 0; const idx = new Map(), low = new Map(), on = new Set(), stack = [], out = [];
  function strong(v) {
    idx.set(v, index); low.set(v, index); index++; stack.push(v); on.add(v);
    for (const w of succ.get(v) || []) {
      if (!idx.has(w)) { strong(w); low.set(v, Math.min(low.get(v), low.get(w))); }
      else if (on.has(w)) low.set(v, Math.min(low.get(v), idx.get(w)));
    }
    if (low.get(v) === idx.get(v)) {
      const comp = [];
      let w;
      do { w = stack.pop(); on.delete(w); comp.push(w); } while (w !== v);
      if (comp.length > 1 || (succ.get(v) || new Set()).has(v)) out.push(comp.reverse());
    }
  }
  for (const v of nodeIds) if (!idx.has(v)) strong(v);
  return out;
}

function plausibleSchema(s, path, out) {
  if (s === true || s === false) return;
  if (!s || typeof s !== 'object' || Array.isArray(s)) { out.push(`${path}: not an object`); return; }
  const types = ['string', 'number', 'integer', 'boolean', 'object', 'array', 'null'];
  if (s.type !== undefined) {
    for (const t of Array.isArray(s.type) ? s.type : [s.type]) if (!types.includes(t)) out.push(`${path}/type: unknown type '${t}'`);
  }
  if (s.required !== undefined && !(Array.isArray(s.required) && s.required.every((x) => typeof x === 'string'))) out.push(`${path}/required: must be an array of strings`);
  if (s.properties !== undefined) {
    if (!s.properties || typeof s.properties !== 'object' || Array.isArray(s.properties)) out.push(`${path}/properties: must be an object`);
    else for (const [k, v] of Object.entries(s.properties)) plausibleSchema(v, `${path}/properties/${k}`, out);
  }
  if (s.items !== undefined) plausibleSchema(s.items, `${path}/items`, out);
}

/**
 * @param {object} def  a LoopDefinition v2
 * @param {object} [options]
 * @param {Iterable<string>} [options.availableCapabilities]  enables LOOP-CAP-001
 * @param {boolean} [options.schemaOnly]
 * @returns {{ valid: boolean, rulesetVersion: string, findings: object[], errors: number, warnings: number }}
 */
export function validateDefinition(def, options = {}) {
  const findings = [];
  for (const e of validateSchema(loopDefinitionSchema, def)) {
    findings.push({ code: 'SCHEMA', layer: 'schema', severity: 'ERROR', message: `${e.path}: ${e.message}`, path: e.path });
  }
  const structurallyUsable = def && typeof def === 'object' && Array.isArray(def.nodes) && Array.isArray(def.edges ?? []);
  if (structurallyUsable && !options.schemaOnly) semantic(def, options, findings);

  findings.sort((a, b) =>
    (SEV_ORDER[a.severity] - SEV_ORDER[b.severity]) || String(a.code).localeCompare(String(b.code)) ||
    String(a.objectId ?? a.path ?? '').localeCompare(String(b.objectId ?? b.path ?? '')) || a.message.localeCompare(b.message));
  const errors = findings.filter((f) => f.severity === 'ERROR').length;
  return { valid: errors === 0, rulesetVersion: RULESET_VERSION, findings, errors, warnings: findings.filter((f) => f.severity === 'WARNING').length };
}

function semantic(def, options, out) {
  const nodes = def.nodes.filter((n) => n && typeof n === 'object');
  const edges = (def.edges || []).filter((e) => e && typeof e === 'object');
  const gateways = (def.gateways || []).filter((g) => g && typeof g === 'object');
  const nodeIds = new Set(nodes.map((n) => n.nodeId));
  const byId = new Map(nodes.map((n) => [n.nodeId, n]));

  // ---- LOOP-ID-001
  const dup = (items, key, objectType) => {
    const seen = new Set();
    for (const it of items) {
      const id = it?.[key];
      if (id == null) continue;
      if (seen.has(id)) out.push(finding('LOOP-ID-001', `Duplicate identity '${id}' used by more than one ${objectType}.`, { objectType, objectId: id }));
      seen.add(id);
    }
  };
  dup(nodes, 'nodeId', 'node'); dup(edges, 'edgeId', 'edge'); dup(gateways, 'gatewayId', 'gateway');
  dup(def.bindingSlots || [], 'slotId', 'slot'); dup(def.verificationPolicy?.verifiers || [], 'verifierId', 'verifier');

  // ---- LOOP-GRAPH-001 (start, terminals, dangling refs)
  let startOk = true;
  if (!nodeIds.has(def.startNodeId)) { startOk = false; out.push(finding('LOOP-GRAPH-001', `Missing explicit start node: startNodeId '${def.startNodeId}' is not a node.`, { objectType: 'definition', objectId: String(def.startNodeId) })); }
  const terminals = (def.terminalNodeIds || []).filter((t) => typeof t === 'string');
  for (const t of terminals) if (!nodeIds.has(t)) out.push(finding('LOOP-GRAPH-001', `Terminal node '${t}' is not a node.`, { objectType: 'definition', objectId: t }));
  for (const e of edges) {
    if (!nodeIds.has(e.fromNodeId)) out.push(finding('LOOP-GRAPH-001', `Edge '${e.edgeId}' starts at unknown node '${e.fromNodeId}'.`, { objectType: 'edge', objectId: e.edgeId }));
    if (!nodeIds.has(e.toNodeId)) out.push(finding('LOOP-GRAPH-001', `Edge '${e.edgeId}' points at unknown node '${e.toNodeId}'.`, { objectType: 'edge', objectId: e.edgeId }));
  }
  const succ = successors({ edges });
  // gateway outcome targets also count as control flow
  for (const g of gateways) {
    const src = edges.find((e) => e.gatewayId === g.gatewayId)?.fromNodeId;
    if (src != null) for (const o of [...(g.outcomes || []), g.defaultOrTerminalFailurePath].filter(Boolean)) {
      if (!succ.has(src)) succ.set(src, new Set()); succ.get(src).add(o.targetNodeId);
    }
  }
  let reach = new Set();
  if (startOk) {
    reach = reachableFrom(def.startNodeId, succ);
    if (terminals.length && !terminals.some((t) => reach.has(t))) out.push(finding('LOOP-GRAPH-001', 'No terminal node is reachable from the start node.', { objectType: 'definition' }));
    // ---- LOOP-GRAPH-002
    for (const n of nodes) if (!reach.has(n.nodeId)) out.push(finding('LOOP-GRAPH-002', `Node '${n.nodeId}' is unreachable from the start node.`, { objectType: 'node', objectId: n.nodeId }));
  }

  // ---- LOOP-GRAPH-003 (gateways + ambiguity)
  const gwById = new Map(gateways.map((g) => [g.gatewayId, g]));
  const bySource = new Map();
  for (const e of edges) { if (!bySource.has(e.fromNodeId)) bySource.set(e.fromNodeId, []); bySource.get(e.fromNodeId).push(e); }
  for (const [src, es] of bySource) {
    const gated = es.filter((e) => e.gatewayId);
    const plain = es.filter((e) => !e.gatewayId);
    if (gated.length && plain.length) out.push(finding('LOOP-GRAPH-003', `Node '${src}' mixes gateway edges and plain edges: ambiguous exits.`, { objectType: 'node', objectId: src }));
    else if (plain.length > 1) out.push(finding('LOOP-GRAPH-003', `Node '${src}' has ${plain.length} plain outgoing edges with no gateway: ambiguous exits.`, { objectType: 'node', objectId: src }));
    const gids = [...new Set(gated.map((e) => e.gatewayId))];
    if (gids.length > 1) out.push(finding('LOOP-GRAPH-003', `Node '${src}' routes through more than one gateway (${gids.join(', ')}).`, { objectType: 'node', objectId: src }));
  }
  for (const e of edges) if (e.gatewayId && !gwById.has(e.gatewayId)) out.push(finding('LOOP-GRAPH-003', `Edge '${e.edgeId}' references undefined gateway '${e.gatewayId}'.`, { objectType: 'edge', objectId: e.edgeId }));
  for (const g of gateways) {
    const gEdges = edges.filter((e) => e.gatewayId === g.gatewayId);
    const sources = new Set(gEdges.map((e) => e.fromNodeId));
    const oid = { objectType: 'gateway', objectId: g.gatewayId };
    if (!gEdges.length) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' is not attached to any edge.`, oid));
    if (sources.size > 1) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' is attached to edges from more than one node.`, oid));
    if (g.exhaustive === false && !g.defaultOrTerminalFailurePath) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' is not exhaustive and declares no default or terminal-failure path.`, oid));
    const outs = g.outcomes || [];
    const all = [...outs, g.defaultOrTerminalFailurePath].filter(Boolean);
    const labels = new Set();
    for (const o of all) {
      if (labels.has(o.label)) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' has two outcomes labelled '${o.label}'.`, oid));
      labels.add(o.label);
      if (!nodeIds.has(o.targetNodeId)) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' outcome '${o.label}' targets unknown node '${o.targetNodeId}'.`, oid));
      else if (gEdges.length && !gEdges.some((e) => e.toNodeId === o.targetNodeId)) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' outcome '${o.label}' has no matching edge to '${o.targetNodeId}'.`, oid));
      if (o.conditionExpression != null && o.conditionExpression !== '') {
        const c = checkCondition(o.conditionExpression);
        if (!c.ok) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' outcome '${o.label}' has an invalid condition: ${c.error}.`, oid));
      }
    }
    if (outs.length > 1) {
      const prios = outs.map((o) => o.priority);
      if (prios.some((p) => p == null)) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' has several outcomes but not all declare a priority: ambiguous order.`, oid));
      else if (new Set(prios).size !== prios.length) out.push(finding('LOOP-GRAPH-003', `Gateway '${g.gatewayId}' has outcomes sharing a priority: ambiguous order.`, oid));
    }
  }

  // ---- LOOP-GRAPH-004 (cycles)
  const comps = cyclicComponents([...nodeIds], succ);
  const stepsBound = Number.isInteger(def.budgets?.steps) && def.budgets.steps >= 1;
  const toTerminal = (from) => terminals.some((t) => reachableFrom(from, succ).has(t));
  for (const comp of comps) {
    const path = [...comp, comp[0]].join(' -> ');
    if (!stepsBound) out.push(finding('LOOP-GRAPH-004', `Cycle ${path} has no statically visible bound (budgets.steps is missing or invalid).`, { objectType: 'edge', objectId: comp[0] }));
    else if (!comp.some(toTerminal)) out.push(finding('LOOP-GRAPH-004', `Cycle ${path} has no way out: no node on it can reach a terminal node.`, { objectType: 'edge', objectId: comp[0] }));
  }

  // ---- LOOP-SCHEMA-001 / 002
  const declared = def.inputSchema?.location === 'INLINE' && def.inputSchema.inlineSchema && typeof def.inputSchema.inlineSchema.properties === 'object'
    ? new Set(Object.keys(def.inputSchema.inlineSchema.properties)) : null;
  if (declared) {
    const used = [{ where: 'objective', text: def.objective }, ...nodes.map((n) => ({ where: `node ${n.nodeId}`, text: n.config?.systemPrompt }))];
    for (const u of used) for (const k of placeholdersIn(u.text)) {
      if (!declared.has(k)) out.push(finding('LOOP-SCHEMA-001', `Required parameter '${k}' is unbound at ${u.where}.`, { objectType: u.where === 'objective' ? 'definition' : 'node', objectId: k }));
    }
  }
  for (const key of ['inputSchema', 'outputSchema']) {
    const ref = def[key];
    if (ref?.location === 'INLINE' && ref.inlineSchema != null) {
      const probs = []; plausibleSchema(ref.inlineSchema, key, probs);
      for (const p of probs) out.push(finding('LOOP-SCHEMA-002', `Schema at '${p}' is not valid JSON Schema.`, { objectType: 'schema', objectId: key }));
    }
  }

  // ---- LOOP-CAP-001
  if (options.availableCapabilities) {
    const avail = new Set(options.availableCapabilities);
    for (const n of nodes) for (const c of n.requestedCapabilities || []) {
      if (!avail.has(c)) out.push(finding('LOOP-CAP-001', `Node '${n.nodeId}' requests capability '${c}', which is unavailable or prohibited on this target/flavor.`, { objectType: 'node', objectId: n.nodeId }));
    }
  }

  // ---- LOOP-BUDGET-001 / 002
  const b = def.budgets || {};
  const missingDims = [];
  if (!(Number.isInteger(b.steps) && b.steps >= 1)) missingDims.push('steps');
  if (typeof b.wallClockSeconds !== 'number') missingDims.push('wallClockSeconds');
  if (typeof b.tokens !== 'number') missingDims.push('tokens');
  if (!b.cost || typeof b.cost.amount !== 'number') missingDims.push('cost');
  if (typeof b.toolCalls !== 'number') missingDims.push('toolCalls');
  for (const n of nodes) {
    if (EXTERNAL_EFFECTS.has(n.sideEffectClass) && missingDims.length) out.push(finding('LOOP-BUDGET-001', `External-effect node '${n.nodeId}' declares no budget dimension (missing: ${missingDims.join(', ')}).`, { objectType: 'node', objectId: n.nodeId }));
    if (n.category === 'BOUNDED_SUBLOOP' && !(Number.isInteger(n.maxRecursionDepth) && n.maxRecursionDepth >= 1)) out.push(finding('LOOP-BUDGET-002', `Recursive subloop at '${n.nodeId}' has no statically visible depth limit.`, { objectType: 'node', objectId: n.nodeId }));
  }

  // ---- LOOP-VERIFY-001 / 002
  for (const n of nodes) {
    const c = n.compensation;
    if (n.sideEffectClass === 'DESTRUCTIVE' && !(c && (c.compensable === true || c.nonCompensabilityAcknowledged === true))) {
      out.push(finding('LOOP-VERIFY-001', `Destructive path through '${n.nodeId}' has no compensation behavior and no explicit non-compensability acknowledgement.`, { objectType: 'node', objectId: n.nodeId }));
    }
  }
  const vp = def.verificationPolicy;
  if (vp) {
    const verifierNodes = nodes.filter((n) => n.category === 'VERIFIER');
    const requiredRefs = (vp.verifiers || []).filter((v) => v.requiredForSuccess);
    if (vp.requireVerificationForSuccess && !requiredRefs.length && !verifierNodes.some((n) => n.verificationRequirements?.requiredForSuccess)) {
      out.push(finding('LOOP-VERIFY-002', 'Verified success is required but no verifier is marked requiredForSuccess.', { objectType: 'definition', objectId: 'verificationPolicy' }));
    }
    for (const v of requiredRefs) {
      if (!verifierNodes.some((n) => verifierMatches(v, n))) out.push(finding('LOOP-VERIFY-002', `Verifier '${v.verifierId}' (requiredForSuccess=true) has no VERIFIER node that can produce a result for it.`, { objectType: 'definition', objectId: v.verifierId }));
    }
  }

  // ---- LOOP-COMPAT-001
  const permitted = new Set(def.executionTargetConstraints?.permittedTargetTypes || []);
  if (permitted.size) for (const n of nodes) {
    const nt = n.permittedExecutionTargets || [];
    if (nt.length && !nt.some((t) => permitted.has(t))) out.push(finding('LOOP-COMPAT-001', `Target constraint at node '${n.nodeId}' cannot be satisfied by any declared execution target class.`, { objectType: 'node', objectId: n.nodeId }));
  }
  void byId;
}

/** A VERIFIER node N can satisfy verifier reference V when N.nodeId == V.verifierId, N.nodeId ∈ V.appliesToNodeIds,
 *  or V.verifierId ∈ N.verificationRequirements.verifierIds. */
export function verifierMatches(v, node) {
  return node.nodeId === v.verifierId || (v.appliesToNodeIds || []).includes(node.nodeId) ||
    (node.verificationRequirements?.verifierIds || []).includes(v.verifierId);
}

/** Convenience used by tests and UIs: findings grouped by node/edge/gateway id for inspector badges. */
export function findingsByObject(report) {
  const map = new Map();
  for (const f of report.findings) { const k = f.objectId; if (k == null) continue; if (!map.has(k)) map.set(k, []); map.get(k).push(f); }
  return map;
}
