// Port of Kotlin LoopRunDriverTest, plus live §9 state-machine behaviour (WAITING_USER, SUSPENDED, CANCELLING).
import test from 'node:test';
import assert from 'node:assert/strict';
import { LoopRunDriver, checkEventSequence, isValidTransition, validateJsonSchema, loopRunSchema, PolicyBoundaryError, SideEffectsPossibleError } from '../index.js';
import { seqDef, refineDef, refineExecutors, allMock, baseDef, node, edge, passthrough } from './helpers.js';

const sourceRef = { draftId: 'draft-1', draftRevisionNumber: 1 };
const bindingProfileRef = { objectId: 'binding-1' };
const driver = (executors = allMock('output')) => new LoopRunDriver({ executors });
const states = (run) => run.eventLog.map((e) => e.toState);
function assertLegal(run) { assert.deepEqual(checkEventSequence(run.eventLog), []); }
function assertSchemaValid(run) { assert.deepEqual(validateJsonSchema(loopRunSchema, run).map((e) => `${e.path} ${e.message}`), []); }

test('a run that reaches the end succeeds unverified, visiting every required state in order', async () => {
  const run = await driver().run(seqDef(2), 'objective', { sourceRef, bindingProfileRef });
  assert.equal(run.runState, 'SUCCEEDED_UNVERIFIED');
  assert.equal(run.terminalReason.category, 'REQUIRED_VERIFIER_INCOMPLETE');
  assertLegal(run); assertSchemaValid(run);
  assert.deepEqual(states(run), ['CREATED', 'PREFLIGHT', 'WAITING_BINDING', 'WAITING_AUTHORITY', 'READY', 'RUNNING', 'SUCCEEDED_UNVERIFIED']);
  assert.equal(run.eventLog[0].fromState, null);
});

test('nodeAttempts mirror the real steps, one per executed task node', async () => {
  const run = await driver().run(seqDef(3), 'objective', { sourceRef, bindingProfileRef });
  assert.deepEqual(run.nodeAttempts.map((a) => a.nodeId), ['t1', 't2', 't3']);
  assert.ok(run.nodeAttempts.every((a) => a.outcome === 'SUCCEEDED' && a.attemptNumber === 1));
});

test('missing params fails at PREFLIGHT with VALIDATION_FAILURE, never reaching RUNNING', async () => {
  const run = await driver().run(seqDef(2), 'objective needs ${missing_key}', { sourceRef, bindingProfileRef });
  assert.equal(run.runState, 'FAILED_SAFE');
  assert.equal(run.terminalReason.category, 'VALIDATION_FAILURE');
  assert.equal(run.terminalReason.detail, 'Missing params: missing_key');
  assert.ok(!states(run).includes('RUNNING'));
  assertLegal(run); assertSchemaValid(run);
});

test('a declined authority resolution cancels via CANCELLING, never straight to CANCELLED', async () => {
  const run = await driver().run(seqDef(2), 'objective', { sourceRef, bindingProfileRef, resolveAuthority: async () => false });
  assert.equal(run.runState, 'CANCELLED');
  const v = states(run);
  assert.ok(v.includes('CANCELLING'));
  assert.equal(v.indexOf('CANCELLING') + 1, v.indexOf('CANCELLED'));
  assertLegal(run); assertSchemaValid(run);
});

test('an unresolved binding cancels before ever reaching WAITING_AUTHORITY', async () => {
  const run = await driver().run(seqDef(2), 'objective', { sourceRef, bindingProfileRef, resolveBinding: async () => false });
  assert.equal(run.runState, 'CANCELLED');
  assert.ok(!states(run).includes('WAITING_AUTHORITY'));
  assertLegal(run);
});

test('a budget-exhausted run stops at STOPPED_BUDGET with the correct exhaustion category', async () => {
  const run = await driver().run(seqDef(5), 'objective', { sourceRef, bindingProfileRef, budget: { maxSteps: 2 } });
  assert.equal(run.runState, 'STOPPED_BUDGET');
  assert.equal(run.terminalReason.category, 'BUDGET_STEPS_EXHAUSTED');
  assertLegal(run); assertSchemaValid(run);
  const wall = await driver().run(seqDef(5), 'objective', { sourceRef, bindingProfileRef, budget: { maxWallMs: -1 } });
  assert.equal(wall.terminalReason.category, 'BUDGET_WALL_CLOCK_EXHAUSTED');
  const tok = await new LoopRunDriver({ executors: allMock(), tokenCounter: async () => ({ inputTokens: 5, outputTokens: 5, estimated: true }) })
    .run(seqDef(5), 'o', { budget: { maxTokensTotal: 10 } });
  assert.equal(tok.terminalReason.category, 'BUDGET_TOKENS_EXHAUSTED');
  const step = await driver().run(seqDef(10), 'o', { hardCap: 2 });
  assert.equal(step.terminalReason.category, 'BUDGET_STEPS_EXHAUSTED');
});

test('definitionDigest is a well-formed sha256 reference, matching the LoopRun contract regex', async () => {
  const run = await driver().run(seqDef(2), 'objective', { sourceRef, bindingProfileRef });
  assert.match(run.definitionDigest, /^sha256:[0-9a-f]{64}$/);
});

test('definitionDigest ignores presentation (layout) and changes with semantics', async () => {
  const a = seqDef(2), b = seqDef(2), c = seqDef(3);
  b.nodes[1].presentation = { x: 10, y: 20 };
  const [ra, rb, rc] = await Promise.all([a, b, c].map((d) => driver().run(d, 'o')));
  assert.equal(ra.definitionDigest, rb.definitionDigest);
  assert.notEqual(ra.definitionDigest, rc.definitionDigest);
});

test('outputs carries the final step output as the primary result', async () => {
  const run = await driver(allMock('final answer')).run(seqDef(1), 'objective', { sourceRef, bindingProfileRef });
  assert.equal(run.outputs.primaryResult, 'final answer');
});

// ---- beyond the Kotlin tests: verification, failures, live states

function verifiedDef(pass) {
  const def = baseDef({
    nodes: [passthrough('start'), node('work', 'MODEL_INFERENCE'), node('check', 'VERIFIER', { verificationRequirements: { requiredForSuccess: true, verifierIds: ['v1'] } }), passthrough('end')],
    edges: [edge('e1', 'start', 'work'), edge('e2', 'work', 'check'), edge('e3', 'check', 'end')],
    verificationPolicy: { requireVerificationForSuccess: true, verifiers: [{ verifierId: 'v1', appliesToNodeIds: ['check'], requiredForSuccess: true }] },
    terminalStateMapping: { unverifiedSuccessAllowed: false, nodeFailureEscalations: [] },
  });
  return { def, executors: { MODEL_INFERENCE: async () => 'w', VERIFIER: async () => ({ passed: pass }) } };
}

test('every required verifier passing yields SUCCEEDED_VERIFIED; failing yields FAILED_SAFE when unverified success is disallowed', async () => {
  const ok = verifiedDef(true);
  const run = await new LoopRunDriver({ executors: ok.executors }).run(ok.def, 'o');
  assert.equal(run.runState, 'SUCCEEDED_VERIFIED'); assert.equal(run.terminalReason.category, 'ALL_REQUIRED_VERIFIERS_PASSED'); assert.equal(run.sideEffectState, 'COMPLETED');
  assertSchemaValid(run);
  const bad = verifiedDef(false);
  const run2 = await new LoopRunDriver({ executors: bad.executors }).run(bad.def, 'o');
  assert.equal(run2.runState, 'FAILED_SAFE'); assert.equal(run2.terminalReason.category, 'REQUIRED_VERIFIER_INCOMPLETE');
  bad.def.terminalStateMapping.unverifiedSuccessAllowed = true;
  const run3 = await new LoopRunDriver({ executors: bad.executors }).run(bad.def, 'o');
  assert.equal(run3.runState, 'SUCCEEDED_UNVERIFIED');
});

test('an invalid definition fails preflight with its LOOP-* findings and never executes', async () => {
  const def = seqDef(2); def.nodes.push(passthrough('t1'));
  let called = false;
  const run = await new LoopRunDriver({ executors: { MODEL_INFERENCE: async () => { called = true; return ''; } } }).run(def, 'o');
  assert.equal(run.runState, 'FAILED_SAFE'); assert.equal(called, false);
  assert.match(run.terminalReason.detail, /LOOP-ID-001/);
});

test('a node with no executor fails preflight naming the node', async () => {
  const run = await new LoopRunDriver({ executors: {} }).run(seqDef(1), 'o');
  assert.equal(run.runState, 'FAILED_SAFE'); assert.match(run.terminalReason.detail, /No executor bound for node 't1'/);
});

test('node failure maps through onFailureTerminalState; executor-thrown policy and side-effect errors steer the state', async () => {
  const mk = (err) => new LoopRunDriver({ executors: { MODEL_INFERENCE: async () => { throw err; } } });
  const a = await mk(new Error('x')).run(seqDef(1), 'o');
  assert.equal(a.runState, 'FAILED_SAFE'); assert.equal(a.terminalReason.category, 'NODE_FAILURE_NO_SIDE_EFFECTS');
  const b = await mk(new PolicyBoundaryError('revoked')).run(seqDef(1), 'o');
  assert.equal(b.runState, 'STOPPED_POLICY'); assert.equal(b.terminalReason.category, 'POLICY_BOUNDARY');
  const c = await mk(new SideEffectsPossibleError('wrote then died')).run(seqDef(1), 'o');
  assert.equal(c.runState, 'FAILED_SIDE_EFFECTS_POSSIBLE'); assert.equal(c.sideEffectState, 'PARTIAL');
  const def = seqDef(1); def.terminalStateMapping.nodeFailureEscalations = [{ nodeId: 't1', onFailure: 'CANCELLED' }];
  const d = await mk(new Error('x')).run(def, 'o');
  assert.equal(d.runState, 'CANCELLED'); assert.ok(states(d).includes('CANCELLING')); assertLegal(d);
});

test('authorize returning false stops the run with STOPPED_POLICY', async () => {
  const run = await driver().run(seqDef(3), 'o', { authorize: async (n) => n.nodeId !== 't2' });
  assert.equal(run.runState, 'STOPPED_POLICY'); assert.equal(run.nodeAttempts.length, 1);
});

function humanDef() {
  return baseDef({
    nodes: [passthrough('start'), node('ask', 'HUMAN_DECISION', { authorityClass: 'PROPOSE' }), node('go', 'MODEL_INFERENCE'), passthrough('end')],
    edges: [edge('e1', 'start', 'ask'), edge('e2', 'ask', 'go'), edge('e3', 'go', 'end')],
  });
}

test('a human-decision node moves the run RUNNING > WAITING_USER > RUNNING and records the intervention', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  const drv = new LoopRunDriver({ executors: { HUMAN_DECISION: async () => { await gate; return { decision: 'approved' }; }, MODEL_INFERENCE: async () => 'done' } });
  const h = drv.start(humanDef(), 'o');
  const seen = []; h.on('state', (e) => seen.push(e.to));
  await new Promise((r) => setTimeout(r, 15));
  assert.equal(h.state, 'WAITING_USER');
  release();
  const { run } = await h.promise;
  assert.deepEqual(run.eventLog.map((e) => e.toState).slice(5), ['RUNNING', 'WAITING_USER', 'RUNNING', 'SUCCEEDED_UNVERIFIED']);
  assert.equal(run.userInterventions.find((i) => i.kind === 'HUMAN_DECISION_SUPPLIED').nodeId, 'ask');
  assertLegal(run); assertSchemaValid(run);
});

test('cancelling while WAITING_USER goes CANCELLING > CANCELLED', async () => {
  const drv = new LoopRunDriver({ executors: { HUMAN_DECISION: (_n, _i, ctx) => new Promise((_, rej) => ctx.signal.addEventListener('abort', () => { const e = new Error('a'); e.name = 'AbortError'; rej(e); })), MODEL_INFERENCE: async () => 'x' } });
  const h = drv.start(humanDef(), 'o');
  await new Promise((r) => setTimeout(r, 10));
  h.cancel();
  const { run } = await h.promise;
  assert.equal(run.runState, 'CANCELLED');
  assert.deepEqual(run.eventLog.map((e) => e.toState).slice(-3), ['WAITING_USER', 'CANCELLING', 'CANCELLED']);
  assertLegal(run); assertSchemaValid(run);
});

test('cancelling a non-cooperative external-effect node ends TARGET_STATE_UNKNOWN (§9: cannot confirm safely stopped)', async () => {
  const def = seqDef(1); def.nodes[1].sideEffectClass = 'EXTERNAL_VISIBLE'; def.nodes[1].compensation = { compensable: false, nonCompensabilityAcknowledged: true };
  const drv = new LoopRunDriver({ executors: { MODEL_INFERENCE: () => new Promise((r) => setTimeout(() => r('late'), 200)) }, cancelGraceMs: 10 });
  const h = drv.start(def, 'o');
  await new Promise((r) => setTimeout(r, 10));
  h.cancel();
  const { run } = await h.promise;
  assert.equal(run.runState, 'TARGET_STATE_UNKNOWN'); assert.equal(run.terminalReason.category, 'CANCELLATION_TARGET_UNCONFIRMED'); assert.equal(run.sideEffectState, 'UNKNOWN');
  assertLegal(run);
});

test('suspend pauses at the next step boundary (SUSPENDED) and resume continues; cancel while suspended works', async () => {
  const drv = new LoopRunDriver({ executors: { MODEL_INFERENCE: async () => { await new Promise((r) => setTimeout(r, 5)); return 'x'; } } });
  const h = drv.start(seqDef(4), 'o');
  await new Promise((r) => setTimeout(r, 8));
  h.suspend();
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(h.state, 'SUSPENDED');
  h.resume();
  const { run } = await h.promise;
  assert.equal(run.runState, 'SUCCEEDED_UNVERIFIED');
  assert.ok(states(run).includes('SUSPENDED'));
  assertLegal(run);
  const h2 = drv.start(seqDef(4), 'o');
  await new Promise((r) => setTimeout(r, 8)); h2.suspend(); await new Promise((r) => setTimeout(r, 30));
  h2.cancel();
  const r2 = (await h2.promise).run;
  assert.equal(r2.runState, 'CANCELLED'); assert.deepEqual(r2.eventLog.map((e) => e.toState).slice(-3), ['SUSPENDED', 'CANCELLING', 'CANCELLED']);
  assertLegal(r2);
});

test('an external AbortSignal cancels the run', async () => {
  const ac = new AbortController();
  const run = await new LoopRunDriver({ executors: { MODEL_INFERENCE: async () => { ac.abort(); return 'x'; } } }).run(seqDef(3), 'o', { signal: ac.signal });
  assert.equal(run.runState, 'CANCELLED');
});

test('events for UIs: node started/finished, gateway taken, budget update, state change, in order', async () => {
  const drv = new LoopRunDriver({ executors: refineExecutors() });
  const h = drv.start(refineDef(), 'o');
  const types = []; h.on('*', (e) => types.push(e.type));
  const gateways = []; h.on('gateway', (e) => gateways.push(e.label));
  const { ledger } = await h.promise;
  assert.deepEqual(gateways, ['refine', 'approve']);
  for (const t of ['state', 'node:started', 'node:finished', 'gateway', 'budget', 'done']) assert.ok(types.includes(t), t);
  const l = ledger.entries; // append-only: seq is dense and entries are frozen
  assert.deepEqual(l.map((e) => e.seq), l.map((_, i) => i));
  assert.ok(Object.isFrozen(l[0]));
  assert.throws(() => { 'use strict'; l[0].type = 'x'; }, TypeError);
});

test('every legal and illegal §9 transition pair matches the table', () => {
  assert.equal(isValidTransition('CREATED', 'PREFLIGHT'), true);
  assert.equal(isValidTransition('CREATED', 'RUNNING'), false);
  assert.equal(isValidTransition('RUNNING', 'CANCELLED'), false); // must pass through CANCELLING
  assert.equal(isValidTransition('CANCELLED', 'RUNNING'), false); // terminal has no exits
  assert.equal(isValidTransition('WAITING_USER', 'RUNNING'), true);
});
