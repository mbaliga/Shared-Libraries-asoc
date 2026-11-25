// The editor's pure operations (loop-studio/studio.js): every edit must keep the definition valid or say why not.
import test from 'node:test';
import assert from 'node:assert/strict';
import { newDefinition, addNode, deleteNode, deleteEdge, connect, renameNode, setPath, addOutcome, setOutcome, removeOutcome, autoLayout, mockOutput, toggleTerminal, setStart, makeNode } from '../../loop-studio/studio.js';
import { validateDefinition, LoopRunDriver, semanticDigest } from '../index.js';

const errs = (d) => validateDefinition(d).findings.filter((f) => f.severity === 'ERROR').map((f) => f.code + ' ' + f.message);

test('a new definition is valid and runs (start > end)', async () => {
  const d = newDefinition();
  assert.deepEqual(errs(d), []);
  const run = await new LoopRunDriver({ executors: {} }).run(d, 'x');
  assert.equal(run.runState, 'SUCCEEDED_UNVERIFIED');
});

test('addNode / connect / deleteNode keep the graph consistent and valid', () => {
  let d = newDefinition();
  const a = addNode(d, 'MODEL_INFERENCE', { x: 200, y: 100 }); d = a.def;
  d = connect(d, 'start', a.id).def;
  d = deleteEdge(d, 'e1').def ?? deleteEdge(d, 'e1');
  d = connect(d, a.id, 'end').def;
  assert.deepEqual(errs(d), []);
  d = deleteNode(d, a.id);
  assert.equal(d.nodes.some((n) => n.nodeId === a.id), false);
  assert.equal(d.edges.some((e) => e.fromNodeId === a.id || e.toNodeId === a.id), false);
});

test('a second exit becomes a gateway with the first exit as a conditional outcome and the new one as default', () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = a.def;
  d = connect(d, 'start', a.id).def; // start now has two exits: end, a
  const g = d.gateways[0];
  assert.equal(d.gateways.length, 1); assert.equal(g.outcomes.length, 1); assert.equal(g.defaultOrTerminalFailurePath.targetNodeId, a.id);
  assert.deepEqual(errs(d), []);
  // a third exit appends an outcome with the next priority and stays valid
  const b = addNode(d, 'VERIFIER'); d = connect(b.def, 'start', b.id).def;
  assert.deepEqual(errs(d), []);
});

test('removing the exits of a gateway collapses it back to a plain edge / removes it', () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = connect(a.def, 'start', a.id).def;
  const gwEdges = d.edges.filter((e) => e.gatewayId);
  d = deleteEdge(d, gwEdges[1].edgeId);
  assert.equal(d.gateways.length, 0); assert.ok(d.edges.every((e) => !e.gatewayId));
});

test('deleting a node removes gateway outcomes that targeted it', () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = connect(a.def, 'start', a.id).def;
  d = deleteNode(d, 'end');
  assert.ok(d.gateways.every((g) => g.outcomes.every((o) => o.targetNodeId !== 'end')));
  assert.ok(!d.terminalNodeIds.includes('end'));
});

test('renameNode rewrites every reference (edges, gateways, start, terminals, escalations)', () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = connect(a.def, 'start', a.id).def;
  d.terminalStateMapping.nodeFailureEscalations.push({ nodeId: a.id, onFailure: 'FAILED_SAFE' });
  d = renameNode(d, a.id, 'writer'); d = renameNode(d, 'end', 'finish'); d = renameNode(d, 'start', 'begin');
  assert.equal(d.startNodeId, 'begin'); assert.deepEqual(d.terminalNodeIds, ['finish']);
  assert.deepEqual(errs(d), []);
  assert.equal(renameNode(d, 'writer', 'begin'), d); // collisions are refused
});

test('setOutcome keeps the matching edge in step; addOutcome / removeOutcome stay valid', () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = connect(a.def, 'start', a.id).def;
  const gid = d.gateways[0].gatewayId;
  d = setOutcome(d, gid, 0, { label: 'ok', conditionExpression: 'score > 3' });
  assert.ok(d.edges.some((e) => e.gatewayId === gid && e.label === 'ok'));
  d = addOutcome(d, gid);
  assert.deepEqual(errs(d), []);
  d = removeOutcome(d, gid, d.gateways[0].outcomes.length - 1);
  assert.deepEqual(errs(d), []);
});

test('an invalid condition typed in the inspector is flagged by validation, not silently accepted', () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = connect(a.def, 'start', a.id).def;
  d = setOutcome(d, d.gateways[0].gatewayId, 0, { conditionExpression: 'a ==' });
  assert.ok(errs(d).some((m) => /invalid condition/.test(m)));
});

test('layout and moves live in presentation and never change the semantic digest', async () => {
  let d = newDefinition(); const a = addNode(d, 'MODEL_INFERENCE'); d = connect(a.def, 'start', a.id).def;
  const before = (await semanticDigest(d)).digest;
  const laid = autoLayout(d); const moved = setPath(d, 'node', a.id, 'presentation.x', 999);
  assert.equal((await semanticDigest(laid)).digest, before); assert.equal((await semanticDigest(moved)).digest, before);
  assert.notEqual((await semanticDigest(setPath(d, 'node', a.id, 'explanation', 'changed'))).digest, before);
});

test('start / terminal toggles, setPath on nested objects, and edits never mutate the input', () => {
  const d = newDefinition(); const frozen = JSON.stringify(d);
  const d2 = toggleTerminal(setStart(setPath(d, 'node', 'end', 'retryPolicy.maxRetries', 2), 'end'), 'start');
  assert.equal(JSON.stringify(d), frozen);
  assert.equal(d2.nodes.find((n) => n.nodeId === 'end').retryPolicy.maxRetries, 2); assert.ok(d2.terminalNodeIds.includes('start'));
});

test('mockOutput cycles by round, substitutes {round}, parses JSON, and has category defaults', () => {
  const n = makeNode('MODEL_INFERENCE', 'm', { config: { mock: 'a|b {round}|{"k":1}' } });
  assert.equal(mockOutput(n, 1), 'a'); assert.equal(mockOutput(n, 2), 'b 2'); assert.deepEqual(mockOutput(n, 3), { k: 1 }); assert.deepEqual(mockOutput(n, 9), { k: 1 });
  assert.deepEqual(mockOutput(makeNode('VERIFIER', 'v'), 1), { passed: true });
});
