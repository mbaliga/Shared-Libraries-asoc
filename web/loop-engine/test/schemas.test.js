import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import * as embedded from '../schemas.js';
import { validateJsonSchema, loopRunSchema, checkEventSequence, RUN_STATES, TERMINAL, NON_TERMINAL, isTerminal, isValidTransition, TERMINAL_REASON_CATEGORIES, validateDefinition, canonicalize, semanticDigest } from '../index.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const rd = (p) => JSON.parse(readFileSync(join(root, p), 'utf8'));

test('schemas.js embeds exactly the copied schema files (run `node tools/embed-schemas.mjs` after re-copying)', () => {
  assert.deepEqual(embedded.loopDefinitionSchema, rd('schemas/loop-definition.v2.schema.json'));
  assert.deepEqual(embedded.loopRunSchema, rd('schemas/loop-run.schema.json'));
  assert.deepEqual(embedded.validationRules, rd('schemas/registries/loop-validation-rules.v1.json'));
  assert.deepEqual(embedded.semanticDigestRegistry, rd('schemas/registries/semantic-digest.v1.json'));
});

test('SOURCE.md names the source commit', () => { assert.match(readFileSync(join(root, 'schemas/SOURCE.md'), 'utf8'), /f23f6e491f449fc8acf3ee77adc0d44916dde945/); });

test('the §9 state roster: 17 states, 9 non-terminal then 8 terminal', () => {
  assert.equal(RUN_STATES.length, 17); assert.equal(NON_TERMINAL.length, 9); assert.equal(TERMINAL.length, 8);
  assert.deepEqual(new Set(RUN_STATES), new Set(rd('schemas/loop-run.schema.json').$defs.RunState.enum));
  assert.deepEqual(new Set(TERMINAL_REASON_CATEGORIES), new Set(rd('schemas/loop-run.schema.json').$defs.TerminalReasonCategory.enum));
  assert.ok(TERMINAL.every(isTerminal) && !NON_TERMINAL.some(isTerminal));
  // terminal states have no way out; WAITING_USER is non-terminal (Amendment §2)
  for (const t of TERMINAL) for (const s of RUN_STATES) assert.equal(isValidTransition(t, s), false);
  assert.equal(isTerminal('WAITING_USER'), false);
});

test('loop-run fixtures: valid ones validate, invalid ones do not, adversarial ones are schema-valid but illegal', () => {
  const dir = (d) => readdirSync(join(root, 'schemas/fixtures/loop-run', d)).filter((f) => f.endsWith('.json'));
  for (const f of dir('valid')) { const j = rd(`schemas/fixtures/loop-run/valid/${f}`); assert.deepEqual(validateJsonSchema(loopRunSchema, j), [], f); assert.deepEqual(checkEventSequence(j.eventLog.map((e, i) => ({ fromState: i === 0 ? null : undefined, ...e }))).filter((p) => !/fromState/.test(p)), [], f); }
  for (const f of dir('invalid')) assert.ok(validateJsonSchema(loopRunSchema, rd(`schemas/fixtures/loop-run/invalid/${f}`)).length > 0, f);
  for (const f of dir('adversarial')) assert.deepEqual(validateJsonSchema(loopRunSchema, rd(`schemas/fixtures/loop-run/adversarial/${f}`)), [], f);
  const illegal = rd('schemas/fixtures/loop-run/adversarial/02-illegal-state-transition-in-event-log.adversarial.json');
  assert.ok(checkEventSequence(illegal.eventLog).some((p) => /illegal transition/.test(p)));
});

test('canonicalisation: sorted keys, excluded presentation keys at any depth, NFC, stable across key order', async () => {
  const a = { b: 1, a: { p: 2, q: 3, x: 9, y: 9, notes: 'drop', presentation: { q: 1 } }, layout: 5 };
  const b = { a: { q: 3, p: 2 }, b: 1 };
  assert.equal(canonicalize(a), '{"a":{"p":2,"q":3},"b":1}'); // x, y, notes, presentation, layout are layout keys (exclusionListV1)
  assert.equal((await semanticDigest(a)).digest, (await semanticDigest(b)).digest);
  assert.equal(canonicalize({ s: 'é' }), canonicalize({ s: 'é' }));
  assert.match((await semanticDigest(b)).digest, /^sha256:[0-9a-f]{64}$/);
});

test('mini JSON Schema validator: keywords the loop schemas rely on', () => {
  const s = { type: 'object', required: ['a'], properties: { a: { type: 'integer', minimum: 1 }, b: { enum: ['x', 'y'] }, c: { type: ['string', 'null'], pattern: '^z' } }, additionalProperties: false,
    allOf: [{ if: { properties: { a: { const: 5 } }, required: ['a'] }, then: { required: ['b'] } }] };
  const v = (x) => validateJsonSchema(s, x).length;
  assert.equal(v({ a: 1 }), 0); assert.equal(v({}), 1); assert.equal(v({ a: 0 }), 1); assert.equal(v({ a: 1.5 }), 1);
  assert.equal(v({ a: 1, b: 'q' }), 1); assert.equal(v({ a: 1, c: null }), 0); assert.equal(v({ a: 1, c: 'abc' }), 1);
  assert.equal(v({ a: 1, extra: 1 }), 1); assert.equal(v({ a: 5 }), 1); assert.equal(v({ a: 5, b: 'x' }), 0);
});

test('a definition that survives JSON round trip validates identically', () => {
  const d = rd('schemas/fixtures/loop-definition.v2/valid/02-branching-subloop-gateway-verifier.json');
  assert.deepEqual(validateDefinition(JSON.parse(JSON.stringify(d))), validateDefinition(d));
});
