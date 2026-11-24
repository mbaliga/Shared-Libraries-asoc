/* A small JSON Schema (draft 2020-12 subset) validator, enough for loop-definition.v2 and loop-run schemas.
   Supported: $ref (local '#/…' and '#'), type (string or array), enum, const, required, properties,
   additionalProperties (boolean or schema), items, minItems, minLength, minimum, pattern, oneOf, anyOf, allOf, not,
   if/then/else, format (date-time only). Unknown keywords are ignored (annotations: description, default, $comment…).
   Not a general-purpose validator: no patternProperties, $dynamicRef, remote refs, unevaluated*, or uniqueItems —
   none of which the loop schemas use. Returns [{ path, message, keyword }]. */

function typeOf(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'number') return Number.isInteger(v) ? 'integer' : 'number';
  return typeof v;
}
function isType(v, t) {
  const a = typeOf(v);
  return a === t || (t === 'number' && a === 'integer');
}
function deepEqual(a, b) { return JSON.stringify(a) === JSON.stringify(b); }

const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

export function validate(schema, value, root = schema) {
  const errors = [];
  walk(schema, value, '', errors, root);
  return errors;
}

function resolve(ref, root) {
  if (ref === '#') return root;
  if (!ref.startsWith('#/')) throw new Error(`unsupported $ref ${ref}`);
  let cur = root;
  for (const part of ref.slice(2).split('/')) cur = cur[decodeURIComponent(part.replace(/~1/g, '/').replace(/~0/g, '~'))];
  if (cur === undefined) throw new Error(`unresolved $ref ${ref}`);
  return cur;
}

function walk(schema, value, path, errors, root) {
  if (schema === true || schema === undefined) return;
  if (schema === false) { errors.push({ path, message: 'schema is false', keyword: 'false' }); return; }
  const err = (message, keyword) => errors.push({ path: path || '/', message, keyword });

  if (schema.$ref) walk(resolve(schema.$ref, root), value, path, errors, root);

  if (schema.type !== undefined) {
    const ts = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!ts.some((t) => isType(value, t))) { err(`expected ${ts.join('|')}, got ${typeOf(value)}`, 'type'); return; }
  }
  if (schema.enum && !schema.enum.some((e) => deepEqual(e, value))) err(`must be one of ${schema.enum.join(', ')}`, 'enum');
  if ('const' in schema && !deepEqual(schema.const, value)) err(`must equal ${JSON.stringify(schema.const)}`, 'const');

  if (typeof value === 'string') {
    if (schema.minLength != null && [...value].length < schema.minLength) err(`shorter than ${schema.minLength}`, 'minLength');
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) err(`does not match ${schema.pattern}`, 'pattern');
    if (schema.format === 'date-time' && !DATE_TIME.test(value)) err('not an RFC 3339 date-time', 'format');
  }
  if (typeof value === 'number') {
    if (schema.minimum != null && value < schema.minimum) err(`below minimum ${schema.minimum}`, 'minimum');
  }
  if (Array.isArray(value)) {
    if (schema.minItems != null && value.length < schema.minItems) err(`fewer than ${schema.minItems} items`, 'minItems');
    if (schema.items) value.forEach((v, i) => walk(schema.items, v, `${path}/${i}`, errors, root));
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const r of schema.required || []) if (!(r in value)) err(`missing required property '${r}'`, 'required');
    const props = schema.properties || {};
    for (const [k, v] of Object.entries(value)) {
      if (k in props) walk(props[k], v, `${path}/${k}`, errors, root);
      else if (schema.additionalProperties === false) errors.push({ path: `${path}/${k}`, message: 'additional property not allowed', keyword: 'additionalProperties' });
      else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') walk(schema.additionalProperties, v, `${path}/${k}`, errors, root);
    }
  }
  if (schema.allOf) for (const s of schema.allOf) walk(s, value, path, errors, root);
  if (schema.anyOf && !schema.anyOf.some((s) => validate(s, value, root).length === 0)) err('does not match any allowed shape', 'anyOf');
  if (schema.oneOf) {
    const n = schema.oneOf.filter((s) => validate(s, value, root).length === 0).length;
    if (n !== 1) err(n === 0 ? 'does not match any allowed shape' : 'matches more than one shape', 'oneOf');
  }
  if (schema.not && validate(schema.not, value, root).length === 0) err('matches a forbidden shape', 'not');
  if (schema.if) {
    if (validate(schema.if, value, root).length === 0) { if (schema.then) walk(schema.then, value, path, errors, root); }
    else if (schema.else) walk(schema.else, value, path, errors, root);
  }
}
