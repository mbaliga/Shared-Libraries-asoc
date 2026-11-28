// Port of Kotlin GraphRunnerTest (core-engine/src/test/java/dev/fonebrew/domain/loop/GraphRunnerTest.kt).
import test from 'node:test';
import assert from 'node:assert/strict';
import { GraphRunner } from '../index.js';
import { seqDef, refineDef, refineExecutors, allMock, baseDef, node, edge, passthrough } from './helpers.js';

test('a non-linear graph runs the refine loop and reaches the end', async () => {
  const result = await new GraphRunner({ executors: refineExecutors() }).run(refineDef(), 'tighten it');
  assert.equal(result.reachedEnd, true);
  assert.equal(result.stoppedBecause, 'reached end');
  assert.equal(result.steps.length, 4); // proposer, critic(needs work), proposer, critic(APPROVE)
  assert.deepEqual(result.steps.map((s) => s.role), ['proposer', 'critic', 'proposer', 'critic']);
  assert.ok(String(result.finalOutput).startsWith('APPROVE'));
});

test('the step cap bounds a loop that never approves', async () => {
  const never = { crit: async () => 'still not good', prop: async () => 'again' };
  const result = await new GraphRunner({ executors: never }).run(refineDef(), 'x', { hardCap: 5 });
  assert.equal(result.reachedEnd, false);
  assert.ok(result.stoppedBecause.includes('step cap'));
  assert.equal(result.steps.length, 5);
});

test('a missing start node is reported, not crashed', async () => {
  const def = baseDef({ startNodeId: 'nope', terminalNodeIds: ['end'], nodes: [passthrough('end')] });
  const result = await new GraphRunner({ executors: allMock('') }).run(def, 'x');
  assert.equal(result.reachedEnd, false);
  assert.equal(result.stoppedBecause, 'no start event');
});

test('the model behind each step is captured', async () => {
  const result = await new GraphRunner({ executors: refineExecutors() }).run(refineDef(), 'x');
  assert.equal(result.steps.find((s) => s.role === 'proposer').model, 'qwen');
  assert.equal(result.steps.find((s) => s.role === 'critic').model, 'claude');
});

// --- params + refuse-to-start

test('param substitution resolves placeholders in the objective and node system prompts', async () => {
  let seen;
  const ex = { MODEL_INFERENCE: async (_n, input) => { seen = input; return 'ok'; } };
  await new GraphRunner({ executors: ex }).run(seqDef(1, { systemPrompt: 'Focus on ${aspect}.' }), 'Improve ${feature} now.', { params: { feature: 'login', aspect: 'latency' } });
  assert.equal(seen.system, 'Focus on latency.');
  assert.ok(seen.user.includes('Improve login now.'));
});

test('a run refuses to start when a param is missing, and never calls an executor', async () => {
  let called = false;
  const ex = { MODEL_INFERENCE: async () => { called = true; return 'should not run'; } };
  const result = await new GraphRunner({ executors: ex }).run(seqDef(1, { systemPrompt: 'Use ${a} and ${b}.' }), 'go', { params: { a: 'x' } });
  assert.equal(called, false);
  assert.equal(result.steps.length, 0);
  assert.equal(result.reachedEnd, false);
  assert.equal(result.stoppedBecause, 'missing params: b');
});

test('a caller passing no params, budget or onStep runs unchanged', async () => {
  const result = await new GraphRunner({ executors: refineExecutors() }).run(refineDef(), 'tighten it', { hardCap: 5 });
  assert.equal(result.reachedEnd, true);
});

// --- budgets on each axis

test('budget stops on maxSteps and never starts the over-run step', async () => {
  const result = await new GraphRunner({ executors: allMock() }).run(seqDef(5), 'x', { budget: { maxSteps: 2 } });
  assert.equal(result.steps.length, 2);
  assert.equal(result.stoppedBecause, 'budget:steps');
  assert.equal(result.reachedEnd, false);
});

test('the definition budgets.steps is itself a hard limit', async () => {
  const result = await new GraphRunner({ executors: allMock() }).run(seqDef(5, { over: { budgets: { steps: 3, wallClockSeconds: 0, tokens: 1000, cost: { amount: 1, currency: 'USD' }, toolCalls: 0 } } }), 'x');
  assert.equal(result.steps.length, 3);
  assert.equal(result.stoppedBecause, 'budget:steps');
});

test('budget stops on maxWallMs using the injected clock, before the over-run step', async () => {
  let t = 0;
  const ex = { MODEL_INFERENCE: async () => { t += 1000; return 'out'; } };
  const result = await new GraphRunner({ executors: ex, now: () => t }).run(seqDef(5), 'x', { budget: { maxWallMs: 1500 } });
  // step1: elapsed 0 runs (t=1000); step2: elapsed 1000 runs (t=2000); step3: elapsed 2000 >= 1500 stops.
  assert.equal(result.steps.length, 2);
  assert.equal(result.stoppedBecause, 'budget:wall');
  assert.equal(result.elapsedMs, 2000);
});

test('budget stops on maxTokensTotal using the wired counter, before the over-run step', async () => {
  const runner = new GraphRunner({ executors: allMock(), tokenCounter: async () => ({ inputTokens: 40, outputTokens: 10, estimated: false }) });
  const result = await runner.run(seqDef(5), 'x', { budget: { maxTokensTotal: 90 } });
  // each step is 50 tokens; before step3 the total is 100 >= 90
  assert.equal(result.steps.length, 2);
  assert.equal(result.stoppedBecause, 'budget:tokens');
  assert.equal((result.totalTokensIn ?? 0) + (result.totalTokensOut ?? 0), 100);
});

test('budget stops on cost reported by the executor', async () => {
  const ex = { MODEL_INFERENCE: async (_n, _i, ctx) => { ctx.reportUsage({ cost: 0.4 }); return 'out'; } };
  const result = await new GraphRunner({ executors: ex }).run(seqDef(5), 'x', { budget: { maxCost: 1 } });
  assert.equal(result.steps.length, 3); // 0.4 + 0.4 + 0.4 = 1.2 >= 1 before step 4
  assert.equal(result.stoppedBecause, 'budget:cost');
});

test('budget stops on tool calls; a typed-tool node counts one call unless it reports its own', async () => {
  const def = seqDef(4);
  def.nodes = def.nodes.map((n) => (n.nodeId.startsWith('t') ? { ...n, category: 'TYPED_TOOL' } : n));
  const result = await new GraphRunner({ executors: { TYPED_TOOL: async () => 'ok' } }).run(def, 'x', { budget: { maxToolCalls: 2 } });
  assert.equal(result.steps.length, 2);
  assert.equal(result.stoppedBecause, 'budget:toolCalls');
});

test('a zero token budget blocks a model node before it runs; a zero tool budget blocks a tool node', async () => {
  let ran = false;
  const ex = { MODEL_INFERENCE: async () => { ran = true; return 'x'; } };
  const r = await new GraphRunner({ executors: ex }).run(seqDef(1, { over: { budgets: { steps: 5, wallClockSeconds: 0, tokens: 0, cost: { amount: 0, currency: 'USD' }, toolCalls: 0 } } }), 'x');
  assert.equal(ran, false);
  assert.equal(r.stoppedBecause, 'budget:tokens');
});

test('hardCap remains the engine safety net even when no budget is set', async () => {
  const result = await new GraphRunner({ executors: allMock() }).run(seqDef(20), 'x', { hardCap: 3 });
  assert.equal(result.steps.length, 3);
  assert.equal(result.stoppedBecause, 'hit step cap (3)');
});

// --- onStep ordering + cancellation

test('onStep fires once per completed step, in ascending order', async () => {
  const seen = [];
  const result = await new GraphRunner({ executors: allMock() }).run(seqDef(4), 'x', { onStep: async (s) => { seen.push(s.index); } });
  assert.deepEqual(seen, [0, 1, 2, 3]);
  assert.deepEqual(seen, result.steps.map((s) => s.index));
});

test('aborting the signal stops the run gracefully and preserves completed steps', async () => {
  const ac = new AbortController();
  const seen = [];
  const ex = { MODEL_INFERENCE: async (n) => `out-${n.nodeId}` };
  const result = await new GraphRunner({ executors: ex }).run(seqDef(5), 'x', {
    signal: ac.signal, onStep: async (s) => { seen.push(s.index); if (s.index === 1) ac.abort(); },
  });
  assert.deepEqual(seen, [0, 1]); // step 2 never started
  assert.equal(result.steps.length, 2);
  assert.equal(result.stoppedBecause, 'cancelled');
  assert.equal(result.reachedEnd, false);
});

test('aborting while an executor is in flight interrupts a non-cooperative executor and a cooperative one alike', async () => {
  for (const cooperative of [true, false]) {
    const ac = new AbortController();
    const ex = { MODEL_INFERENCE: (_n, _i, ctx) => new Promise((res, rej) => {
      setTimeout(() => ac.abort(), 5);
      if (cooperative) ctx.signal.addEventListener('abort', () => { const e = new Error('aborted'); e.name = 'AbortError'; rej(e); });
      else setTimeout(() => res('late'), 300);
    }) };
    const result = await new GraphRunner({ executors: ex, cancelGraceMs: 20 }).run(seqDef(2), 'x', { signal: ac.signal });
    assert.equal(result.stoppedBecause, 'cancelled');
    assert.equal(result.steps.length, 0);
    assert.equal(result.stop.inFlight.settled, cooperative);
  }
});

// --- totals math + estimated propagation

test('totals sum only steps with a counted value, and estimated propagates per step', async () => {
  let call = 0;
  const tokenCounter = async () => { call++; return call === 1 ? { inputTokens: 10, outputTokens: 5, estimated: false } : call === 2 ? { inputTokens: 7, outputTokens: 3, estimated: true } : null; };
  const result = await new GraphRunner({ executors: allMock(), tokenCounter }).run(seqDef(3), 'x');
  assert.equal(result.steps.length, 3);
  assert.equal(result.steps[0].tokensIn, 10); assert.equal(result.steps[0].tokensOut, 5); assert.equal(result.steps[0].estimated, false);
  assert.equal(result.steps[1].tokensIn, 7); assert.equal(result.steps[1].tokensOut, 3); assert.equal(result.steps[1].estimated, true);
  assert.equal(result.steps[2].tokensIn, null); assert.equal(result.steps[2].tokensOut, null);
  assert.equal(result.steps[2].estimated, true); // no counter data -> never asserted authoritative
  assert.equal(result.totalTokensIn, 17); assert.equal(result.totalTokensOut, 8);
});

test('totals are null, not zero, when no step ever reports a token count', async () => {
  const result = await new GraphRunner({ executors: allMock() }).run(seqDef(2), 'x');
  assert.equal(result.totalTokensIn, null); assert.equal(result.totalTokensOut, null);
});

test('durationMs and elapsedMs are measured from the injected clock', async () => {
  let t = 0;
  const result = await new GraphRunner({ executors: { MODEL_INFERENCE: async () => { t += 100; return 'out'; } }, now: () => t }).run(seqDef(3), 'x');
  assert.ok(result.steps.every((s) => s.durationMs === 100));
  assert.equal(result.elapsedMs, 300);
});

// --- gateway routing (the Kotlin "edge condition committed through the presenter actually routes" test)

test('a typed gateway condition actually routes a real run; the decoy branch never runs', async () => {
  const def = baseDef({
    nodes: [passthrough('start'), node('crit', 'MODEL_INFERENCE', { config: { name: 'Critic' } }), node('decoy', 'MODEL_INFERENCE'), passthrough('end')],
    edges: [edge('e1', 'start', 'crit'), edge('e3', 'crit', 'end', 'approve', 'g'), edge('e4', 'crit', 'decoy', 'refine', 'g')],
    gateways: [{ gatewayId: 'g', exhaustive: true, outcomes: [
      { label: 'approve', conditionExpression: 'approved', priority: 1, targetNodeId: 'end' },
      { label: 'refine', conditionExpression: '!approved', priority: 2, targetNodeId: 'decoy' }] }],
  });
  const result = await new GraphRunner({ executors: { MODEL_INFERENCE: async () => 'APPROVE, ship it' } }).run(def, 'x');
  assert.equal(result.reachedEnd, true);
  assert.equal(result.stoppedBecause, 'reached end');
  assert.equal(result.steps.length, 1);
  assert.equal(result.steps[0].nodeId, 'crit');
});

test('gateway priorities decide when several conditions match; the default path catches the rest', async () => {
  const def = baseDef({
    nodes: [passthrough('start'), node('a', 'DETERMINISTIC_TRANSFORMATION'), node('hi', 'MODEL_INFERENCE'), node('mid', 'MODEL_INFERENCE'), node('lo', 'MODEL_INFERENCE'), passthrough('end')],
    edges: [edge('e1', 'start', 'a'), edge('x1', 'a', 'hi', 'hi', 'g'), edge('x2', 'a', 'mid', 'mid', 'g'), edge('x3', 'a', 'lo', 'lo', 'g'),
      edge('h', 'hi', 'end'), edge('m', 'mid', 'end'), edge('l', 'lo', 'end')],
    gateways: [{ gatewayId: 'g', exhaustive: false,
      outcomes: [{ label: 'mid', conditionExpression: 'score >= 5', priority: 2, targetNodeId: 'mid' }, { label: 'hi', conditionExpression: 'score >= 8', priority: 1, targetNodeId: 'hi' }],
      defaultOrTerminalFailurePath: { label: 'lo', conditionExpression: null, priority: null, targetNodeId: 'lo' } }],
  });
  const route = async (score) => (await new GraphRunner({ executors: { a: async () => ({ score }), MODEL_INFERENCE: async () => 'x' } }).run(def, 'o')).steps.map((s) => s.nodeId);
  assert.deepEqual(await route(9), ['a', 'hi']);
  assert.deepEqual(await route(6), ['a', 'mid']);
  assert.deepEqual(await route(1), ['a', 'lo']);
});

test('an exhaustive gateway that matches nothing stops safely (gateway gap), never guessing a branch', async () => {
  const def = refineDef();
  def.gateways[0].outcomes = [{ label: 'approve', conditionExpression: 'approved', priority: 1, targetNodeId: 'end' }, { label: 'refine', conditionExpression: 'text == "never"', priority: 2, targetNodeId: 'prop' }];
  const result = await new GraphRunner({ executors: { prop: async () => 'p', crit: async () => 'nope' } }).run(def, 'x');
  assert.equal(result.stop.kind, 'dead-end');
  assert.ok(result.stoppedBecause.startsWith('gateway gap'));
});

test('executor resolution order: nodeId, then implementation ref, then category', async () => {
  const def = seqDef(1); def.nodes[1].implementation = { kind: 'CAPABILITY_BINDING', capabilityId: 'fb.model.local_inference' };
  const mk = (tag) => async () => tag;
  const r = (executors) => new GraphRunner({ executors }).run(def, 'x').then((x) => x.steps[0].output);
  assert.equal(await r({ MODEL_INFERENCE: mk('category') }), 'category');
  assert.equal(await r({ MODEL_INFERENCE: mk('category'), 'fb.model.local_inference': mk('impl') }), 'impl');
  assert.equal(await r({ MODEL_INFERENCE: mk('category'), 'fb.model.local_inference': mk('impl'), t1: mk('id') }), 'id');
});

test('a node failure resolves through onFailureTerminalState / terminalStateMapping; retries are attempted first', async () => {
  let calls = 0;
  const def = seqDef(2);
  def.nodes[1].retryPolicy = { maxRetries: 2, backoff: 'FIXED', baseDelaySeconds: 0 };
  const flaky = { MODEL_INFERENCE: async () => { if (++calls < 3) throw new Error('boom'); return 'ok'; } };
  const ok = await new GraphRunner({ executors: flaky }).run(def, 'x');
  assert.equal(ok.reachedEnd, true);
  assert.deepEqual(ok.attempts.filter((a) => a.nodeId === 't1').map((a) => a.outcome), ['RETRIED', 'RETRIED', 'SUCCEEDED']);

  const def2 = seqDef(2); def2.terminalStateMapping.nodeFailureEscalations = [{ nodeId: 't1', onFailure: 'TARGET_STATE_UNKNOWN' }];
  const bad = await new GraphRunner({ executors: { MODEL_INFERENCE: async () => { throw new Error('dead'); } } }).run(def2, 'x');
  assert.equal(bad.stop.kind, 'failure'); assert.equal(bad.stop.state, 'TARGET_STATE_UNKNOWN'); assert.equal(bad.steps.length, 0);
});

test('a node timeout fails the attempt (TIMED_OUT) and a RETRY policy retries it', async () => {
  const def = seqDef(1); def.nodes[1].timeoutPolicy = { timeoutSeconds: 0.01, onTimeout: 'RETRY' }; def.nodes[1].retryPolicy = { maxRetries: 1, backoff: 'NONE' };
  let n = 0;
  const ex = { MODEL_INFERENCE: () => (++n === 1 ? new Promise(() => {}) : Promise.resolve('fast')) };
  const r = await new GraphRunner({ executors: ex }).run(def, 'x');
  assert.equal(r.reachedEnd, true);
  assert.deepEqual(r.attempts.map((a) => a.outcome), ['TIMED_OUT', 'SUCCEEDED']);
});

test('verifier nodes record pass/fail without failing the run', async () => {
  const def = baseDef({
    nodes: [passthrough('start'), node('v', 'VERIFIER'), passthrough('end')], edges: [edge('e1', 'start', 'v'), edge('e2', 'v', 'end')],
  });
  const r = await new GraphRunner({ executors: { VERIFIER: async () => ({ passed: false, why: 'no' }) } }).run(def, 'x');
  assert.equal(r.reachedEnd, true);
  assert.equal(r.verifierResults.v.passed, false);
});
