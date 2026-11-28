// Port of Kotlin RecordingGatewayPolicyTest + deterministic replay of a recorded run.
import test from 'node:test';
import assert from 'node:assert/strict';
import { RecordingGatewayPolicy, ReplayGatewayPolicy, ReplayDivergenceError, LoopRunDriver, replayRun, conditionGatewayPolicy } from '../index.js';
import { refineDef, refineExecutors, node, edge } from './helpers.js';

const gate = node('gate', 'MODEL_INFERENCE');
const ctx = (output, outgoing, gateway = null) => ({ node: gate, gateway, output, outgoing, scope: undefined });
const g = (conds) => ({ gatewayId: 'g', exhaustive: true, outcomes: conds });
const approve = edge('e-approve', 'gate', 'end', 'approve', 'g'), refine = edge('e-refine', 'gate', 'prop', 'refine', 'g');
const gw = g([{ label: 'approve', conditionExpression: 'approved', priority: 1, targetNodeId: 'end' }, { label: 'refine', conditionExpression: '!approved', priority: 2, targetNodeId: 'prop' }]);

test('a gateway with two outgoing edges is recorded', () => {
  const p = new RecordingGatewayPolicy();
  const chosen = p.choose(ctx('APPROVE this', [approve, refine], gw));
  assert.equal(chosen.edge, approve);
  assert.equal(p.recorded.length, 1);
  assert.deepEqual(p.recorded[0], { nodeId: 'gate', gatewayId: 'g', chosenEdgeRef: 'e-approve', alternativeRefs: ['e-refine'] });
});

test('a single-outgoing-edge node is not a choice — never recorded', () => {
  const p = new RecordingGatewayPolicy();
  const only = edge('e-only', 'gate', 'end');
  assert.equal(p.choose(ctx('anything', [only])).edge, only);
  assert.deepEqual(p.recorded, []);
});

test('no outgoing edges: nothing chosen, nothing recorded', () => {
  const p = new RecordingGatewayPolicy();
  assert.equal(p.choose(ctx('anything', [])), null); assert.deepEqual(p.recorded, []);
});

test('recorded accumulates across multiple gateway visits in run order', () => {
  const p = new RecordingGatewayPolicy();
  p.choose(ctx('APPROVE', [approve, refine], gw)); p.choose(ctx('no good', [approve, refine], gw));
  assert.deepEqual(p.recorded.map((r) => r.chosenEdgeRef), ['e-approve', 'e-refine']);
});

test('delegates the actual choice to the inner policy', () => {
  const alwaysSecond = { choose: (c) => (c.outgoing[1] ? { edge: c.outgoing[1], outcome: null, via: 'condition' } : null) };
  const p = new RecordingGatewayPolicy(alwaysSecond);
  const e1 = edge('e1', 'gate', 'x'), e2 = edge('e2', 'gate', 'y');
  assert.equal(p.choose(ctx('', [e1, e2])).edge, e2);
  assert.deepEqual(p.recorded[0], { nodeId: 'gate', gatewayId: null, chosenEdgeRef: 'e2', alternativeRefs: ['e1'] });
});

test('recorded is a snapshot — reading it again after another choose reflects the new entry', () => {
  const p = new RecordingGatewayPolicy();
  const before = p.recorded;
  p.choose(ctx('APPROVE', [approve, refine], gw));
  assert.equal(before.length, 0); assert.equal(p.recorded.length, 1);
});

test('ReplayGatewayPolicy replays recorded choices and flags divergence', () => {
  const rp = new ReplayGatewayPolicy([{ nodeId: 'gate', chosenEdgeRef: 'e-refine', alternativeRefs: ['e-approve'] }]);
  assert.equal(rp.choose(ctx('APPROVE — would normally approve', [approve, refine], gw)).edge, refine); // the recording wins over conditions
  assert.throws(() => rp.choose(ctx('x', [approve, refine], gw)), ReplayDivergenceError);
  const wrong = new ReplayGatewayPolicy([{ nodeId: 'other', chosenEdgeRef: 'e-refine', alternativeRefs: [] }]);
  assert.throws(() => wrong.choose(ctx('x', [approve, refine], gw)), ReplayDivergenceError);
});

test('replaying a recorded run reproduces steps, outputs, gateways and terminal state with no executors', async () => {
  const def = refineDef();
  const original = await new LoopRunDriver({ executors: refineExecutors() }).execute(def, 'tighten it', { params: {} });
  assert.equal(original.run.runState, 'SUCCEEDED_UNVERIFIED');
  const recording = JSON.parse(JSON.stringify(original.recording)); // survives serialisation
  const replayed = await replayRun(def, recording);
  assert.deepEqual(replayed.result.steps.map((s) => [s.nodeId, s.output]), original.result.steps.map((s) => [s.nodeId, s.output]));
  assert.deepEqual(replayed.recording.gateways, recording.gateways);
  assert.equal(replayed.run.runState, original.run.runState);
  assert.equal(replayed.run.terminalReason.category, original.run.terminalReason.category);
  assert.equal(replayed.run.definitionDigest, original.run.definitionDigest);
});

test('replay refuses a changed definition and a tampered recording', async () => {
  const def = refineDef();
  const original = await new LoopRunDriver({ executors: refineExecutors() }).execute(def, 'x');
  const changed = refineDef(); changed.objective = 'a different loop';
  await assert.rejects(replayRun(changed, original.recording), ReplayDivergenceError);
  const tampered = JSON.parse(JSON.stringify(original.recording)); tampered.gateways[0].nodeId = 'prop';
  await assert.rejects(replayRun(def, tampered));
  const short = JSON.parse(JSON.stringify(original.recording)); short.steps.pop();
  const r = await new LoopRunDriver({}).execute(def, 'x', { params: {}, replay: short });
  assert.equal(r.run.runState, 'FAILED_SAFE'); // a node asked for an output that was never recorded
});

test('the default policy and the recording wrapper agree on a plain run', async () => {
  const rec = new RecordingGatewayPolicy(conditionGatewayPolicy);
  const out = await new LoopRunDriver({ executors: refineExecutors(), gatewayPolicy: rec }).execute(refineDef(), 'x');
  assert.equal(out.recording.gateways.length, 2);
});
