// Port of Kotlin GraphRunLedgerTest.
import test from 'node:test';
import assert from 'node:assert/strict';
import { toLedgerEntries, toTreeNodes, PricingBook, GraphRunner, RunLedger } from '../index.js';
import { baseDef, node, edge, passthrough } from './helpers.js';

const result = () => ({
  steps: [
    { index: 0, nodeId: 'prop', role: 'proposer', model: 'qwen', output: 'draft', tokensIn: 40, tokensOut: 10, durationMs: 120, estimated: true },
    { index: 1, nodeId: 'crit', role: 'critic', model: 'claude', output: 'APPROVE', tokensIn: 22, tokensOut: 6, durationMs: 340, estimated: false },
  ],
  stoppedBecause: 'reached end', reachedEnd: true, totalTokensIn: 62, totalTokensOut: 16, elapsedMs: 460,
});
const nodesFor = (r) => { let n = 0; return toTreeNodes('make it airtight', r, { loopRunId: 'run-1', loopId: 'loop-7', now: 1000, idGen: () => `n${n++}` }); };
const entries = (r, o = {}) => toLedgerEntries(r, nodesFor(r), { loopId: 'loop-7', runId: 'run-1', projectId: null, timestampMillis: 5000, ...o });

test('one ledger entry per completed step, correlated to the tree node by id', () => {
  const nodes = nodesFor(result());
  const e = entries(result());
  assert.equal(e.length, 2);
  assert.equal(e[0].nodeId, nodes[1].id); assert.equal(e[1].nodeId, nodes[2].id);
});

test('every entry is tagged surface loop with the run and loop ids', () => {
  for (const e of entries(result())) { assert.equal(e.surface, 'loop'); assert.equal(e.loopId, 'loop-7'); assert.equal(e.chatId, 'run-1'); }
});

test('tokens, duration and estimated propagate per step', () => {
  const e = entries(result());
  assert.deepEqual([e[0].inputTokens, e[0].outputTokens, e[0].latencyMs, e[0].estimated], [40, 10, 120, true]);
  assert.deepEqual([e[1].inputTokens, e[1].outputTokens, e[1].latencyMs, e[1].estimated], [22, 6, 340, false]);
});

test('cost stays zero when no pricing is wired — never invents a number', () => {
  assert.ok(entries(result()).every((e) => e.estCostMinor === 0));
});

test('an on-device step never carries a cost even when the model would otherwise price', () => {
  const book = new PricingBook().with('cloud:qwen', { centsPer1kInput: 100, centsPer1kOutput: 200 });
  const e = entries(result(), { pricingBook: book, resolveTokenizerId: (id) => (id === 'qwen' ? 'cloud:qwen' : id) });
  assert.equal(e[0].estCostMinor, 0);
});

test('a cloud step prices through the same PricingBook path a chat turn uses', () => {
  const book = new PricingBook().with('cloud:claude-x', { centsPer1kInput: 300, centsPer1kOutput: 1500 });
  const e = entries(result(), { pricingBook: book, resolveTokenizerId: (id) => (id === 'claude' ? 'cloud:claude-x' : id) });
  assert.equal(e[1].estCostMinor, 15); // 22*300/1000 (=6) + 6*1500/1000 (=9)
  assert.equal(e[0].estCostMinor, 0);
});

const nullModel = () => { const r = result(); r.steps = r.steps.map((s) => ({ ...s, model: null })); return r; };

test('a default-model step (null) prices via the run cloud fallback model, not on-device', () => {
  const book = new PricingBook().withFallback({ centsPer1kInput: 300, centsPer1kOutput: 1500 });
  const e = entries(nullModel(), { pricingBook: book, resolveTokenizerId: (id) => id ?? 'cloud:run-fallback' });
  assert.equal(e[1].estCostMinor, 15); assert.equal(e[0].estCostMinor, 0);
});

test('a default-model step still prices zero when the run fallback is genuinely on-device', () => {
  const book = new PricingBook().withFallback({ centsPer1kInput: 300, centsPer1kOutput: 1500 });
  const e = entries(nullModel(), { pricingBook: book, resolveTokenizerId: (id) => id ?? 'gguf:run-fallback' });
  assert.ok(e.every((x) => x.estCostMinor === 0));
});

test('a null loopId (ad-hoc, unsaved run) carries through as null', () => {
  const r = result();
  const e = toLedgerEntries(r, nodesFor(r), { loopId: null, runId: 'run-1', timestampMillis: 1 });
  assert.ok(e.every((x) => x.loopId === null));
});

test('model falls back to the role when a step has no model', () => {
  assert.equal(entries(nullModel())[0].model, 'proposer');
});

test('tree nodes: objective root then one assistant node per step, chained, tagged', () => {
  const nodes = nodesFor(result());
  assert.equal(nodes.length, 3);
  assert.equal(nodes[0].parentId, null); assert.equal(nodes[1].parentId, nodes[0].id); assert.equal(nodes[2].parentId, nodes[1].id);
  assert.equal(nodes[0].metadata.stoppedBecause, 'reached end');
  assert.equal(nodes[2].metadata.stepIndex, '1'); assert.equal(nodes[2].metadata.nodeId, 'crit'); assert.equal(nodes[2].modelId, 'claude');
});

// A real run feeding the ledger: engine-silent step stays estimated and free; counted cloud step prices exactly.
test('a real run: an engine-silent step stays estimated with zero cost, a counted step prices correctly', async () => {
  const def = baseDef({
    nodes: [passthrough('start'), node('silent', 'MODEL_INFERENCE', { config: { model: 'local-gguf' } }), node('counted', 'MODEL_INFERENCE', { config: { model: 'claude' } }), passthrough('end')],
    edges: [edge('e1', 'start', 'silent'), edge('e2', 'silent', 'counted'), edge('e3', 'counted', 'end')],
  });
  const ex = { MODEL_INFERENCE: async (n) => (n.nodeId === 'silent' ? 'local draft' : 'APPROVE, ship it') };
  const tokenCounter = async (n) => (n.nodeId === 'counted' ? { inputTokens: 22, outputTokens: 6, estimated: false } : null);
  const res = await new GraphRunner({ executors: ex, tokenCounter }).run(def, 'make it airtight');
  assert.equal(res.steps.length, 2);
  const silent = res.steps.find((s) => s.nodeId === 'silent'); assert.equal(silent.tokensIn, null); assert.equal(silent.estimated, true);
  const counted = res.steps.find((s) => s.nodeId === 'counted'); assert.equal(counted.tokensIn, 22); assert.equal(counted.estimated, false);
  let n = 0;
  const nodes = toTreeNodes('make it airtight', res, { loopRunId: 'run-2', loopId: 'loop-9', now: 1000, idGen: () => `n${n++}` });
  const book = new PricingBook().with('cloud:claude-x', { centsPer1kInput: 300, centsPer1kOutput: 1500 });
  const e = toLedgerEntries(res, nodes, { loopId: 'loop-9', runId: 'run-2', timestampMillis: 5000, pricingBook: book, resolveTokenizerId: (id) => (id === 'claude' ? 'cloud:claude-x' : id) });
  const se = e.find((x) => x.model === 'local-gguf'), ce = e.find((x) => x.model === 'claude');
  assert.deepEqual([se.tier, se.estimated, se.inputTokens, se.outputTokens, se.estCostMinor], ['ON_DEVICE', true, 0, 0, 0]);
  assert.deepEqual([ce.tier, ce.estimated, ce.inputTokens, ce.outputTokens, ce.estCostMinor], ['CLOUD', false, 22, 6, 15]);
});

test('RunLedger is append-only: entries are frozen copies and there is no mutating API', () => {
  const l = new RunLedger(() => 7);
  l.append('a', { x: 1 });
  const snap = l.entries; snap.push('junk');
  assert.equal(l.length, 1);
  assert.throws(() => { 'use strict'; l.entries[0].x = 2; }, TypeError);
  assert.equal(l.entries[0].atMs, 7);
  assert.equal(typeof l.remove, 'undefined'); assert.equal(typeof l.clear, 'undefined');
});
