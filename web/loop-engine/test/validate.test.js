// Static validation: the upstream fixtures plus one test per rule code the engine implements.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { validateDefinition, validationRules, RULESET_VERSION } from '../index.js';
import { baseDef, node, edge, passthrough, seqDef, refineDef } from './helpers.js';

const FIX = join(dirname(fileURLToPath(import.meta.url)), '..', 'schemas', 'fixtures', 'loop-definition.v2');
const load = (d, f) => JSON.parse(readFileSync(join(FIX, d, f), 'utf8'));
const codes = (r) => r.findings.map((f) => f.code);
const has = (r, code) => codes(r).includes(code);

test('ruleset version is the frozen registry version', () => { assert.equal(RULESET_VERSION, 'fb-loop-validation-rules-1'); assert.equal(validationRules.rules.length, 28); });

test('every valid upstream fixture validates clean', () => {
  for (const f of readdirSync(join(FIX, 'valid'))) { const r = validateDefinition(load('valid', f)); assert.equal(r.valid, true, f + JSON.stringify(r.findings)); assert.deepEqual(r.findings, []); }
});

test('every invalid upstream fixture is rejected', () => {
  for (const f of readdirSync(join(FIX, 'invalid'))) assert.equal(validateDefinition(load('invalid', f)).valid, false, f);
  assert.ok(has(validateDefinition(load('invalid', '02-subloop-missing-recursion-depth.json')), 'LOOP-BUDGET-002'));
});

test('adversarial fixtures: schema-valid defects are caught by the semantic layer with the expected code', () => {
  const dup = validateDefinition(load('adversarial', '01-duplicate-node-id.adversarial.json'));
  assert.equal(dup.valid, false); assert.ok(has(dup, 'LOOP-ID-001')); assert.ok(!dup.findings.some((f) => f.layer === 'schema'));
  const unr = validateDefinition(load('adversarial', '02-unreachable-node.adversarial.json'));
  assert.equal(unr.valid, true); // GRAPH-002 is a WARNING in the registry
  assert.deepEqual(unr.findings.map((f) => [f.code, f.severity, f.objectId]), [['LOOP-GRAPH-002', 'WARNING', 'n3']]);
});

test('findings carry the registry severity and remediation, and are deterministically sorted', () => {
  const def = seqDef(2); def.nodes.push(passthrough('orphan'), passthrough('orphan'));
  const r = validateDefinition(def);
  assert.deepEqual(r.findings, validateDefinition(def).findings);
  const sev = r.findings.map((f) => f.severity);
  assert.deepEqual(sev, [...sev].sort((a, b) => ['ERROR', 'WARNING', 'INFO'].indexOf(a) - ['ERROR', 'WARNING', 'INFO'].indexOf(b)));
  assert.ok(r.findings.every((f) => f.code === 'SCHEMA' || f.remediation));
});

test('LOOP-ID-001: duplicate node, edge, gateway, slot and verifier ids', () => {
  const d = refineDef();
  d.nodes.push(d.nodes[1]); d.edges.push(d.edges[0]); d.gateways.push(d.gateways[0]);
  d.bindingSlots = [{ slotId: 's', kind: 'MODEL', required: false }, { slotId: 's', kind: 'MODEL', required: false }];
  const r = validateDefinition(d);
  assert.deepEqual([...new Set(r.findings.filter((f) => f.code === 'LOOP-ID-001').map((f) => f.objectType))].sort(), ['edge', 'gateway', 'node', 'slot']);
});

test('LOOP-GRAPH-001: missing start, unknown terminal, dangling edge, no reachable terminal', () => {
  assert.ok(has(validateDefinition(baseDef({ startNodeId: 'zz', nodes: [passthrough('end')] })), 'LOOP-GRAPH-001'));
  assert.ok(has(validateDefinition(baseDef({ nodes: [passthrough('start')], edges: [], terminalNodeIds: ['ghost'] })), 'LOOP-GRAPH-001'));
  const d = seqDef(1); d.edges.push(edge('bad', 't1', 'nowhere'));
  assert.ok(has(validateDefinition(d), 'LOOP-GRAPH-001'));
  const d2 = seqDef(1); d2.edges = d2.edges.filter((e) => e.toNodeId !== 'end');
  assert.ok(has(validateDefinition(d2), 'LOOP-GRAPH-001'));
});

test('LOOP-GRAPH-003: non-exhaustive gateway with no default, bad condition, ambiguity, missing edge', () => {
  const noDefault = refineDef(); noDefault.gateways[0].exhaustive = false;
  assert.ok(has(validateDefinition(noDefault), 'LOOP-GRAPH-003'));
  const badCond = refineDef(); badCond.gateways[0].outcomes[0].conditionExpression = 'approved ==';
  assert.ok(validateDefinition(badCond).findings.some((f) => f.code === 'LOOP-GRAPH-003' && /invalid condition/.test(f.message)));
  const noPrio = refineDef(); noPrio.gateways[0].outcomes[1].priority = null;
  assert.ok(validateDefinition(noPrio).findings.some((f) => /priority/.test(f.message)));
  const samePrio = refineDef(); samePrio.gateways[0].outcomes[1].priority = 1;
  assert.ok(validateDefinition(samePrio).findings.some((f) => /sharing a priority/.test(f.message)));
  const fan = seqDef(2); fan.edges.push(edge('extra', 't1', 'end'));
  assert.ok(validateDefinition(fan).findings.some((f) => /ambiguous exits/.test(f.message)));
  const noEdge = refineDef(); noEdge.edges = noEdge.edges.filter((e) => e.edgeId !== 'e4');
  assert.ok(validateDefinition(noEdge).findings.some((f) => /no matching edge/.test(f.message)));
  const undef = refineDef(); undef.gateways = [];
  assert.ok(validateDefinition(undef).findings.some((f) => /undefined gateway/.test(f.message)));
});

test('LOOP-GRAPH-004: a cycle with no bound, and a cycle with no way out', () => {
  assert.equal(validateDefinition(refineDef()).valid, true); // bounded by budgets.steps
  const d = refineDef(); d.budgets.steps = 0;
  assert.ok(has(validateDefinition(d), 'LOOP-GRAPH-004'));
  const trap = baseDef({
    nodes: [passthrough('start'), node('a'), node('b'), passthrough('end')],
    edges: [edge('e1', 'start', 'a'), edge('e2', 'a', 'b'), edge('e3', 'b', 'a')],
  });
  const r = validateDefinition(trap);
  assert.ok(r.findings.some((f) => f.code === 'LOOP-GRAPH-004' && /no way out/.test(f.message)));
});

test('LOOP-SCHEMA-001 / 002: undeclared ${param}; implausible inline schema', () => {
  const d = seqDef(1, { systemPrompt: 'Use ${tone}.' });
  d.inputSchema = { location: 'INLINE', inlineSchema: { type: 'object', properties: { subject: { type: 'string' } } } };
  assert.ok(validateDefinition(d).findings.some((f) => f.code === 'LOOP-SCHEMA-001' && /tone/.test(f.message)));
  d.inputSchema.inlineSchema.properties.tone = { type: 'string' };
  assert.ok(!has(validateDefinition(d), 'LOOP-SCHEMA-001'));
  const bad = seqDef(1); bad.outputSchema = { location: 'INLINE', inlineSchema: { type: 'strng', required: 'x' } };
  assert.equal(codes(validateDefinition(bad)).filter((c) => c === 'LOOP-SCHEMA-002').length, 2);
});

test('LOOP-CAP-001 only when the host supplies the available capability set', () => {
  const d = seqDef(1); d.nodes[1].requestedCapabilities = ['fb.network.egress'];
  assert.ok(!has(validateDefinition(d), 'LOOP-CAP-001'));
  assert.ok(has(validateDefinition(d, { availableCapabilities: ['fb.repo.read'] }), 'LOOP-CAP-001'));
  assert.ok(!has(validateDefinition(d, { availableCapabilities: ['fb.network.egress'] }), 'LOOP-CAP-001'));
});

test('LOOP-BUDGET-001: an external-effect node with a missing budget dimension', () => {
  const d = seqDef(1); d.nodes[1].sideEffectClass = 'EXTERNAL_VISIBLE'; delete d.budgets.toolCalls;
  assert.ok(has(validateDefinition(d), 'LOOP-BUDGET-001'));
});

test('LOOP-VERIFY-001: destructive node without compensation or acknowledgement', () => {
  const d = seqDef(1); d.nodes[1].sideEffectClass = 'DESTRUCTIVE'; d.nodes[1].compensation = { compensable: false, nonCompensabilityAcknowledged: false };
  const r = validateDefinition(d);
  assert.ok(has(r, 'LOOP-VERIFY-001'));
});

test('LOOP-VERIFY-002: verified success required but no verifier can produce it', () => {
  const d = seqDef(1); d.verificationPolicy = { requireVerificationForSuccess: true, verifiers: [] };
  assert.ok(has(validateDefinition(d), 'LOOP-VERIFY-002'));
  const d2 = seqDef(1); d2.verificationPolicy = { requireVerificationForSuccess: true, verifiers: [{ verifierId: 'v', requiredForSuccess: true }] };
  assert.ok(has(validateDefinition(d2), 'LOOP-VERIFY-002')); // no VERIFIER node
  d2.nodes[1] = { ...d2.nodes[1], category: 'VERIFIER' }; d2.nodes[1].verificationRequirements = { requiredForSuccess: true, verifierIds: ['v'] };
  assert.ok(!has(validateDefinition(d2), 'LOOP-VERIFY-002'));
});

test('LOOP-COMPAT-001: node permitted targets disjoint from the definition constraint', () => {
  const d = seqDef(1); d.nodes[1].permittedExecutionTargets = ['SSH_HOST'];
  assert.ok(has(validateDefinition(d), 'LOOP-COMPAT-001'));
});

test('malformed input never throws', () => {
  for (const bad of [null, {}, { nodes: 'x' }, { nodes: [null, 3], edges: [null] }, []]) assert.doesNotThrow(() => validateDefinition(bad));
  assert.equal(validateDefinition({}).valid, false);
});
