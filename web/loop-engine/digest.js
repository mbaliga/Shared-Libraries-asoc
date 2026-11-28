/* Semantic digest of a LoopDefinition — semantic-digest.v1.json / canonicalization.v1.json (fb-loop-canon-1).
   Steps: drop every key in exclusionListV1 at any depth (presentation, layout, x, y, notes); sort object keys by
   UTF-16 code unit; NFC-normalise strings; no insignificant whitespace; SHA-256 of the UTF-8 bytes as
   'sha256:<hex>'. The spec forbids bare IEEE-754 doubles in a definition (integers and decimal strings only): this
   implementation canonicalises a non-integer number by its shortest JSON form and reports it in `warnings` rather
   than throwing, because budgets such as cost.amount are doubles in the upstream fixtures. */

import { semanticDigestRegistry } from './schemas.js';

export const EXCLUDED_KEYS = semanticDigestRegistry.exclusionListV1;

export function canonicalize(value, warnings = [], path = '') {
  if (value === null || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number') {
    if (!Number.isInteger(value)) warnings.push(`non-integer number at ${path || '/'} (canonicalised as ${JSON.stringify(value)})`);
    return JSON.stringify(value);
  }
  if (typeof value === 'string') return JSON.stringify(value.normalize('NFC'));
  if (Array.isArray(value)) return '[' + value.map((v, i) => canonicalize(v, warnings, `${path}/${i}`)).join(',') + ']';
  const keys = Object.keys(value).filter((k) => !EXCLUDED_KEYS.includes(k) && value[k] !== undefined)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  return '{' + keys.map((k) => JSON.stringify(k.normalize('NFC')) + ':' + canonicalize(value[k], warnings, `${path}/${k}`)).join(',') + '}';
}

export async function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const subtle = globalThis.crypto && globalThis.crypto.subtle;
  if (!subtle) throw new Error('crypto.subtle is unavailable (needs a secure context in browsers, Node >= 19)');
  const buf = await subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** { digest: 'sha256:…', version: 'fb-semantic-digest-1', warnings } */
export async function semanticDigest(definition) {
  const warnings = [];
  const text = canonicalize(definition, warnings);
  return { digest: 'sha256:' + (await sha256Hex(text)), version: semanticDigestRegistry.semanticDigestVersion, warnings };
}
