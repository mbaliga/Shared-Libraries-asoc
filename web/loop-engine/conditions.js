/* Typed gateway conditions (LOOP_ENGINEERING_SPEC_V2.1 §5). `GatewayOutcome.conditionExpression` is a string; this
   module defines the small expression language the engine evaluates, with a static type check so a malformed or
   ill-typed condition is a validation finding, not a runtime surprise.

   Grammar (no function calls, no assignment, no side effects — a condition can only read):
     expr    := or
     or      := and ( '||' and )*
     and     := not ( '&&' not )*
     not     := '!' not | cmp
     cmp     := atom ( ('=='|'!='|'<'|'<='|'>'|'>=') atom | 'in' list | 'contains' atom | 'startsWith' atom )?
     atom    := number | 'string' | "string" | true | false | null | path | '(' expr ')'
     path    := ident ( '.' ident | '[' number ']' )*
     list    := '[' atom ( ',' atom )* ']'
   Scope: the gateway source node's output (object fields are top-level identifiers; also `output`, `text`),
   `params`, `round` (visit count of the source node), `usage` (steps/tokens/cost so far).
   Legacy sugar from Fonebrew's ConditionGatewayPolicy: the identifier `approved` is true when the output text begins
   with "APPROVE" (case-insensitive) unless the output object has its own `approved` field. */

const KW = { true: true, false: false, null: null };

export function tokenize(src) {
  const toks = []; let i = 0;
  const s = String(src);
  while (i < s.length) {
    const c = s[i];
    if (/\s/.test(c)) { i++; continue; }
    const two = s.slice(i, i + 2);
    if (['==', '!=', '<=', '>=', '&&', '||'].includes(two)) { toks.push({ t: 'op', v: two, at: i }); i += 2; continue; }
    if ('<>!()[],.'.includes(c)) { toks.push({ t: c === '.' ? 'dot' : 'op', v: c, at: i }); i++; continue; }
    if (c === '"' || c === "'") {
      let j = i + 1; let out = '';
      while (j < s.length && s[j] !== c) { if (s[j] === '\\' && j + 1 < s.length) j++; out += s[j]; j++; }
      if (j >= s.length) throw new CondError('unterminated string', i);
      toks.push({ t: 'str', v: out, at: i }); i = j + 1; continue;
    }
    if (/[0-9]/.test(c) || (c === '-' && /[0-9]/.test(s[i + 1] || ''))) {
      const m = /^-?\d+(\.\d+)?/.exec(s.slice(i)); toks.push({ t: 'num', v: Number(m[0]), at: i }); i += m[0].length; continue;
    }
    if (/[A-Za-z_$]/.test(c)) {
      const m = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(s.slice(i)); toks.push({ t: 'id', v: m[0], at: i }); i += m[0].length; continue;
    }
    throw new CondError(`unexpected character '${c}'`, i);
  }
  return toks;
}

export class CondError extends Error { constructor(msg, at) { super(`${msg} at ${at}`); this.name = 'CondError'; this.at = at; } }

export function parse(src) {
  const toks = tokenize(src); let p = 0;
  const peek = () => toks[p]; const next = () => toks[p++];
  const isOp = (v) => peek() && peek().t === 'op' && peek().v === v;
  const isId = (v) => peek() && peek().t === 'id' && peek().v === v;
  function expect(v) { if (!isOp(v)) throw new CondError(`expected '${v}'`, peek() ? peek().at : src.length); next(); }
  function or() { let l = and(); while (isOp('||')) { next(); l = { k: 'or', l, r: and() }; } return l; }
  function and() { let l = not(); while (isOp('&&')) { next(); l = { k: 'and', l, r: not() }; } return l; }
  function not() { if (isOp('!')) { next(); return { k: 'not', e: not() }; } return cmp(); }
  function cmp() {
    const l = atom();
    const t = peek();
    if (t && t.t === 'op' && ['==', '!=', '<', '<=', '>', '>='].includes(t.v)) { next(); return { k: 'cmp', op: t.v, l, r: atom() }; }
    if (isId('in')) {
      next(); expect('['); const items = [atom()];
      while (isOp(',')) { next(); items.push(atom()); }
      expect(']'); return { k: 'in', l, items };
    }
    if (isId('contains') || isId('startsWith')) { const op = next().v; return { k: op, l, r: atom() }; }
    return l;
  }
  function atom() {
    const t = next();
    if (!t) throw new CondError('unexpected end', src.length);
    if (t.t === 'num') return { k: 'lit', v: t.v, ty: 'number' };
    if (t.t === 'str') return { k: 'lit', v: t.v, ty: 'string' };
    if (t.t === 'op' && t.v === '(') { const e = or(); expect(')'); return e; }
    if (t.t === 'id') {
      if (Object.prototype.hasOwnProperty.call(KW, t.v)) return { k: 'lit', v: KW[t.v], ty: t.v === 'null' ? 'null' : 'boolean' };
      const path = [t.v];
      for (;;) {
        if (peek() && peek().t === 'dot') { next(); const id = next(); if (!id || id.t !== 'id') throw new CondError('expected identifier after .', t.at); path.push(id.v); }
        else if (isOp('[')) { next(); const n = next(); if (!n || n.t !== 'num') throw new CondError('expected index', t.at); expect(']'); path.push(n.v); }
        else break;
      }
      return { k: 'path', path };
    }
    throw new CondError(`unexpected '${t.v}'`, t.at);
  }
  const ast = or();
  if (p < toks.length) throw new CondError(`unexpected '${toks[p].v}'`, toks[p].at);
  return ast;
}

/** Static check. Returns { ok, error?, identifiers }. A condition must be boolean-valued at the root: a bare path
 *  is allowed (truthiness), a bare literal that is not boolean is rejected; comparing two literals of different
 *  types with an ordering operator is rejected. */
export function check(src) {
  let ast;
  try { ast = parse(src); } catch (e) { return { ok: false, error: e.message, identifiers: [] }; }
  const ids = new Set();
  let error = null;
  const ty = (n) => {
    switch (n.k) {
      case 'lit': return n.ty;
      case 'path': ids.add(String(n.path[0])); return 'any';
      case 'not': ty(n.e); return 'boolean';
      case 'and': case 'or': ty(n.l); ty(n.r); return 'boolean';
      case 'in': ty(n.l); n.items.forEach(ty); return 'boolean';
      case 'contains': case 'startsWith': ty(n.l); ty(n.r); return 'boolean';
      case 'cmp': {
        const a = ty(n.l), b = ty(n.r);
        const ordering = ['<', '<=', '>', '>='].includes(n.op);
        if (a !== 'any' && b !== 'any' && a !== b && (ordering || (a !== 'null' && b !== 'null'))) error = error || `type mismatch: ${a} ${n.op} ${b}`;
        if (ordering && (a === 'boolean' || b === 'boolean' || a === 'null' || b === 'null')) error = error || `operator ${n.op} needs number or string operands`;
        return 'boolean';
      }
      default: return 'any';
    }
  };
  const root = ty(ast);
  if (!error && root !== 'boolean' && root !== 'any') error = `condition must be boolean, got ${root}`;
  return error ? { ok: false, error, identifiers: [...ids] } : { ok: true, identifiers: [...ids] };
}

export function textOf(output) {
  if (output == null) return '';
  return typeof output === 'string' ? output : JSON.stringify(output);
}

function lookup(scope, path) {
  let [head, ...rest] = path;
  let v;
  if (Object.prototype.hasOwnProperty.call(scope.fields, head)) v = scope.fields[head];
  else if (head === 'output') v = scope.output;
  else if (head === 'text') v = scope.text;
  else if (head === 'params') v = scope.params;
  else if (head === 'round') v = scope.round;
  else if (head === 'usage') v = scope.usage;
  else if (head === 'approved') v = /^\s*approve/i.test(scope.text);
  else v = undefined;
  for (const k of rest) { if (v == null) return undefined; v = v[k]; }
  return v;
}

export function makeScope({ output, params = {}, round = 0, usage = {} } = {}) {
  const fields = output && typeof output === 'object' && !Array.isArray(output) ? output : {};
  return { fields, output, text: textOf(output), params, round, usage };
}

export function evaluate(ast, scope) {
  const ev = (n) => {
    switch (n.k) {
      case 'lit': return n.v;
      case 'path': return lookup(scope, n.path);
      case 'not': return !ev(n.e);
      case 'and': return !!ev(n.l) && !!ev(n.r);
      case 'or': return !!ev(n.l) || !!ev(n.r);
      case 'in': { const v = ev(n.l); return n.items.some((i) => ev(i) === v); }
      case 'contains': { const l = ev(n.l), r = ev(n.r); return Array.isArray(l) ? l.includes(r) : typeof l === 'string' && l.includes(String(r)); }
      case 'startsWith': { const l = ev(n.l), r = ev(n.r); return typeof l === 'string' && l.toLowerCase().startsWith(String(r).toLowerCase()); }
      case 'cmp': {
        const a = ev(n.l), b = ev(n.r);
        switch (n.op) {
          case '==': return a === b || (a == null && b == null);
          case '!=': return !(a === b || (a == null && b == null));
          case '<': return a < b; case '<=': return a <= b; case '>': return a > b; case '>=': return a >= b;
        }
      }
    }
    return false;
  };
  return !!ev(ast);
}

const cache = new Map();
/** Parse (cached) and evaluate `src` against a node output. Throws CondError on a malformed expression. */
export function evalCondition(src, scopeInput) {
  let ast = cache.get(src);
  if (!ast) { ast = parse(src); cache.set(src, ast); }
  return evaluate(ast, scopeInput.fields ? scopeInput : makeScope(scopeInput));
}
