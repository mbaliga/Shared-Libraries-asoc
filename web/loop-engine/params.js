/* `${key}` placeholder substitution for a parameterized run — port of Kotlin LoopParams.
   Param specs come from scanning the definition's objective plus every node's `config.systemPrompt`. A run refuses
   to start when a key has no value (never a silent empty substitution). */

const PLACEHOLDER = /\$\{([^}]+)\}/g;

export function placeholdersIn(text) {
  const out = [];
  for (const m of String(text ?? '').matchAll(PLACEHOLDER)) if (!out.includes(m[1])) out.push(m[1]);
  return out;
}

/** Every distinct placeholder in `objective` and each node's `config.systemPrompt`, first-seen order.
 *  Node names / explanations are display text and are never templated. */
export function scan(definition, objective) {
  const keys = [];
  const add = (t) => { for (const k of placeholdersIn(t)) if (!keys.includes(k)) keys.push(k); };
  add(objective);
  for (const n of definition.nodes || []) if (typeof n.config?.systemPrompt === 'string') add(n.config.systemPrompt);
  return keys;
}

export function missing(definition, objective, params = {}) {
  return scan(definition, objective).filter((k) => !Object.prototype.hasOwnProperty.call(params, k));
}

/** A key absent from `params` is left untouched; callers must have refused to start via `missing` first. */
export function substitute(text, params = {}) {
  return String(text ?? '').replace(PLACEHOLDER, (m, k) => (Object.prototype.hasOwnProperty.call(params, k) ? String(params[k]) : m));
}

export const LoopParams = { placeholdersIn, scan, missing, substitute };
