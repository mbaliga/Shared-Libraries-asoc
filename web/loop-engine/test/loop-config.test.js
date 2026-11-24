// Port of Kotlin LoopConfigTest (BPMN XML round trip replaced by a JSON round trip — see README, deferred BPMN).
import test from 'node:test';
import assert from 'node:assert/strict';
import { loopConfigToDefinition, definitionToLoopConfig, expert, validateDefinition, LoopRunDriver } from '../index.js';

const config = {
  id: 'loop-1', name: 'Tighten an argument', objective: 'Make the case airtight.',
  proposer: expert('proposer', 'Proposer', 'You propose.', { modelId: 'qwen', x: 160, y: 60 }),
  critic: expert('critic', 'Critic', 'You critique; APPROVE only if perfect.', { modelId: 'claude', x: 380, y: 140 }),
  iterations: 3,
};

test('toDefinition lays the canonical refine shape', () => {
  const d = loopConfigToDefinition(config);
  assert.equal(d.startNodeId, 'start'); assert.deepEqual(d.terminalNodeIds, ['end']);
  assert.ok(d.edges.some((e) => e.fromNodeId === 'proposer' && e.toNodeId === 'critic'));
  assert.ok(d.edges.some((e) => e.fromNodeId === 'critic' && e.toNodeId === 'proposer' && e.label === 'refine'));
  assert.ok(d.edges.some((e) => e.fromNodeId === 'critic' && e.toNodeId === 'end' && e.label === 'approve'));
});

test('the produced definition is schema- and rule-valid', () => {
  const r = validateDefinition(loopConfigToDefinition(config));
  assert.deepEqual(r.findings.filter((f) => f.severity === 'ERROR'), []);
});

test('config round-trips through the definition mapping', () => {
  assert.deepEqual(definitionToLoopConfig(loopConfigToDefinition(config)), config);
});

test('config survives serialisation to JSON and back', () => {
  const restored = definitionToLoopConfig(JSON.parse(JSON.stringify(loopConfigToDefinition(config))));
  assert.equal(restored.objective, config.objective); assert.equal(restored.proposer.systemPrompt, config.proposer.systemPrompt);
  assert.equal(restored.critic.modelId, config.critic.modelId); assert.equal(restored.iterations, config.iterations);
});

test('the canonical loop runs: refine until the critic approves, bounded by iterations', async () => {
  let c = 0;
  const ok = await new LoopRunDriver({ executors: { MODEL_INFERENCE: async (n) => (n.nodeId === 'critic' ? (++c >= 2 ? 'APPROVE' : 'again') : 'draft') } }).run(loopConfigToDefinition(config), 'go');
  assert.equal(ok.runState, 'SUCCEEDED_UNVERIFIED'); assert.equal(ok.nodeAttempts.length, 4);
  const never = await new LoopRunDriver({ executors: { MODEL_INFERENCE: async (n) => (n.nodeId === 'critic' ? 'no' : 'draft') } }).run(loopConfigToDefinition({ ...config, iterations: 2 }), 'go');
  assert.equal(never.runState, 'STOPPED_BUDGET'); assert.equal(never.nodeAttempts.length, 4); // iterations * 2
});
