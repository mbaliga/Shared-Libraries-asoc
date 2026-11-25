import test from 'node:test';
import assert from 'node:assert/strict';
import { checkCondition, evalCondition, makeScope } from '../index.js';

const ev = (src, output, extra = {}) => evalCondition(src, makeScope({ output, ...extra }));

test('comparisons, boolean operators, precedence and parentheses', () => {
  assert.equal(ev("decision == 'approved'", { decision: 'approved' }), true);
  assert.equal(ev('n >= 3 && n < 10', { n: 5 }), true);
  assert.equal(ev('a || b && c', { a: true, b: false, c: false }), true);
  assert.equal(ev('(a || b) && c', { a: true, b: false, c: false }), false);
  assert.equal(ev('!ok', { ok: false }), true);
  assert.equal(ev('status in ["a","b"]', { status: 'b' }), true);
  assert.equal(ev('x != null', { x: 1 }), true);
  assert.equal(ev('missing == null', {}), true);
});
test('dotted paths, indices, params and round', () => {
  assert.equal(ev('report.score > 7', { report: { score: 9 } }), true);
  assert.equal(ev('list[1] == "b"', { list: ['a', 'b'] }), true);
  assert.equal(ev('params.mode == "x"', {}, { params: { mode: 'x' } }), true);
  assert.equal(ev('round >= 3', {}, { round: 3 }), true);
});
test('string helpers and the legacy `approved` sugar', () => {
  assert.equal(ev('text contains "needs"', 'it needs work'), true);
  assert.equal(ev('approved', 'APPROVE looks good'), true);
  assert.equal(ev('approved', '  approve'), true);
  assert.equal(ev('approved', 'needs work'), false);
  assert.equal(ev('approved', { approved: false }), false); // an own field wins over the sugar
});
test('static check accepts well-typed and rejects malformed or ill-typed conditions', () => {
  assert.equal(checkCondition('a == 1 && b').ok, true);
  assert.deepEqual(checkCondition('a == 1 && b').identifiers, ['a', 'b']);
  for (const bad of ['a ==', '(a', "'x' < 3", '3', '"s"', 'a b', 'a == == b', 'true < false', "'unterminated"]) assert.equal(checkCondition(bad).ok, false, bad);
});
test('conditions cannot call functions or assign — the grammar has neither', () => {
  for (const bad of ['alert(1)', 'a = 1', 'a.constructor()', 'x => y']) assert.equal(checkCondition(bad).ok, false, bad);
});
