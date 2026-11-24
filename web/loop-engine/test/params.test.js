// Port of Kotlin LoopParamsTest.
import test from 'node:test';
import assert from 'node:assert/strict';
import { LoopParams } from '../index.js';
import { baseDef, node } from './helpers.js';

const defWith = (...prompts) => baseDef({ nodes: prompts.map((p, i) => node(`t${i + 1}`, 'MODEL_INFERENCE', { config: { systemPrompt: p } })) });

test('placeholdersIn finds every distinct key in first-seen order', () => {
  assert.deepEqual(LoopParams.placeholdersIn('Improve ${feature} per ${reviewer}, then re-check ${feature}.'), ['feature', 'reviewer']);
});
test('placeholdersIn is empty for plain text', () => { assert.deepEqual(LoopParams.placeholdersIn('no templating here'), []); });
test('scan collects keys from the objective and every node system prompt', () => {
  assert.deepEqual(LoopParams.scan(defWith('Focus on ${aspect}.', 'Address ${aspect} and ${tone}.'), 'Do ${thing} now.'), ['thing', 'aspect', 'tone']);
});
test('scan ignores node names and explanations (display text, never templated)', () => {
  const d = defWith('plain'); d.nodes[0].explanation = 'about ${nope}'; d.nodes[0].config.name = '${nope2}';
  assert.deepEqual(LoopParams.scan(d, 'x'), []);
});
test('missing lists only keys params does not supply', () => {
  assert.deepEqual(LoopParams.missing(defWith('Use ${a} and ${b}.'), '${a}', { a: 'x' }), ['b']);
});
test('missing is empty once every key has a value', () => {
  assert.deepEqual(LoopParams.missing(defWith('Use ${a} and ${b}.'), '${a}', { a: 'x', b: 'y' }), []);
});
test('an empty-string value still counts as supplied', () => {
  assert.deepEqual(LoopParams.missing(defWith('${a}'), 'x', { a: '' }), []);
});
test('substitute replaces every occurrence of a key', () => {
  assert.equal(LoopParams.substitute('Improve ${feature} per ${reviewer}, then re-check ${feature}.', { feature: 'login', reviewer: 'Alice' }), 'Improve login per Alice, then re-check login.');
});
test('substitute leaves an unresolved key untouched rather than blanking it', () => {
  assert.equal(LoopParams.substitute('Use ${a} and ${b}.', { a: 'x' }), 'Use x and ${b}.');
});
