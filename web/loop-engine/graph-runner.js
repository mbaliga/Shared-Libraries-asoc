/* GraphRunner — executes a LoopDefinition v2 graph. Port of Kotlin GraphRunner (docs: LOOP_ENGINEERING_SPEC_V2.1 §4,
   §5, §17, §18), generalised from "one Generator per task" to the eight node categories executed through
   host-supplied executors:

     executors: { [nodeId | implementation.capabilityId | implementation.slotId | category]: async (node, input, ctx) => output }

   Resolution order per node: nodeId, then implementation ref (capabilityId / slotId / subloopDefinitionRef), then
   category. The engine never calls a model, tool or network itself; hosts do (sovereignty: BYOK stays in the host).

   Like the Kotlin runner it is pure orchestration and returns every stop — cancel, budget, cap, failure — as a normal
   result carrying the steps that completed, never as a thrown error. */

import * as Params from './params.js';
import { makeScope, textOf } from './conditions.js';
import { conditionGatewayPolicy } from './gateway.js';

/** An executor throws these to steer the terminal state of the run (§9). */
export class PolicyBoundaryError extends Error { constructor(m) { super(m); this.name = 'PolicyBoundaryError'; } }
export class SideEffectsPossibleError extends Error { constructor(m) { super(m); this.name = 'SideEffectsPossibleError'; } }
export class TargetStateUnknownError extends Error { constructor(m) { super(m); this.name = 'TargetStateUnknownError'; } }
export class TimeoutError extends Error { constructor(m) { super(m); this.name = 'TimeoutError'; } }

const EXTERNAL = new Set(['PERSISTENT_LOCAL', 'EXTERNAL_VISIBLE', 'DESTRUCTIVE']);
const WAITING = new Set(['HUMAN_DECISION', 'WAIT_EVENT_GATE']);

export function isAbortError(e) { return !!e && (e.name === 'AbortError' || e.code === 'ABORT_ERR'); }

export function resolveExecutorKey(node, executors) {
  const keys = [node.nodeId, node.implementation?.capabilityId, node.implementation?.slotId, node.implementation?.subloopDefinitionRef, node.category];
  for (const k of keys) if (k && typeof executors[k] === 'function') return k;
  return null;
}

export const defaultControl = { enter() {}, async checkpoint() {}, isCancelled: () => false };

/** limits derived from the definition's budgets, tightened by an optional per-run override
 *  { maxSteps, maxWallMs, maxTokensTotal, maxCost, maxToolCalls } (Kotlin LoopBudget). 0 for tokens/cost/toolCalls
 *  means "none permitted"; 0 wall-clock means "no wall-clock limit declared". */
export function effectiveLimits(def, override) {
  const b = def.budgets || {};
  const min = (a, c) => (a == null ? c : c == null ? a : Math.min(a, c));
  return {
    steps: min(Number.isFinite(b.steps) ? b.steps : null, override?.maxSteps ?? null),
    wallMs: min(b.wallClockSeconds > 0 ? b.wallClockSeconds * 1000 : null, override?.maxWallMs ?? null),
    tokens: min(Number.isFinite(b.tokens) ? b.tokens : null, override?.maxTokensTotal ?? null),
    cost: min(typeof b.cost?.amount === 'number' ? b.cost.amount : null, override?.maxCost ?? null),
    toolCalls: min(Number.isFinite(b.toolCalls) ? b.toolCalls : null, override?.maxToolCalls ?? null),
  };
}

function stepOutputText(o) { return typeof o === 'string' ? o : textOf(o); }

export class GraphRunner {
  /**
   * @param {object} cfg
   * @param {Record<string,Function>} cfg.executors
   * @param {object} [cfg.gatewayPolicy]   { choose(ctx) -> {edge,outcome,via}|null }
   * @param {Function} [cfg.tokenCounter]  async (node, input, output) => {inputTokens,outputTokens,estimated}|null
   * @param {Function} [cfg.now]           injectable ms clock (budgets, durations deterministic under test)
   * @param {Function} [cfg.sleep]         async (ms, signal) for retry backoff
   * @param {number}   [cfg.cancelGraceMs] how long to wait for an in-flight executor to settle after cancel
   */
  constructor({ executors = {}, gatewayPolicy = conditionGatewayPolicy, tokenCounter = null, now = Date.now, sleep = null, cancelGraceMs = 25 } = {}) {
    this.executors = executors; this.gatewayPolicy = gatewayPolicy; this.tokenCounter = tokenCounter; this.now = now;
    this.cancelGraceMs = cancelGraceMs;
    this.sleep = sleep || ((ms, signal) => new Promise((res) => {
      if (!ms) return res();
      const t = setTimeout(res, ms); signal?.addEventListener('abort', () => { clearTimeout(t); res(); }, { once: true });
    }));
  }

  /**
   * Run `def` toward `objective`. Options mirror the Kotlin signature: hardCap (engine safety net, default 24, always
   * applies), params, budget (user intent, checked before dispatching each step), onStep (awaited once per completed
   * step, in order). Extras: signal (AbortSignal), control (run state hooks from LoopRunDriver), emit(type,data),
   * authorize(node, ctx) -> boolean (false = STOPPED_POLICY).
   * @returns {Promise<GraphRunResult>}
   */
  async run(def, objective, { hardCap = 24, params = {}, budget = null, onStep = null, signal = null, control = defaultControl, emit = () => {}, authorize = null } = {}) {
    const now = this.now;
    const runStart = now();
    const elapsed = () => now() - runStart;
    const steps = []; const attempts = []; const verifierResults = {}; const visits = {}; const outputs = {};
    const usage = { tokensIn: null, tokensOut: null, tokens: 0, cost: 0, toolCalls: 0 };
    const limits = effectiveLimits(def, budget);

    const finish = (stop, extra = {}) => {
      const ins = steps.map((s) => s.tokensIn).filter((x) => x != null);
      const outs = steps.map((s) => s.tokensOut).filter((x) => x != null);
      return {
        steps, stoppedBecause: stop.reason, reachedEnd: stop.kind === 'end', stop,
        totalTokensIn: ins.length ? ins.reduce((a, b) => a + b, 0) : null,
        totalTokensOut: outs.length ? outs.reduce((a, b) => a + b, 0) : null,
        totalCost: usage.cost, toolCalls: usage.toolCalls, elapsedMs: elapsed(),
        get finalOutput() { return steps.length ? steps[steps.length - 1].output : ''; },
        attempts, verifierResults, outputs, limits, ...extra,
      };
    };

    const missing = Params.missing(def, objective, params);
    if (missing.length) return finish({ kind: 'params', reason: `missing params: ${missing.join(', ')}` });
    const resolvedObjective = Params.substitute(objective, params);

    const byId = new Map((def.nodes || []).map((n) => [n.nodeId, n]));
    let current = byId.get(def.startNodeId);
    if (!current) return finish({ kind: 'invalid', reason: 'no start event' });
    const terminals = new Set(def.terminalNodeIds || []);

    let transcript = 'Objective:\n' + resolvedObjective + '\n';
    let lastOutput = '';
    let lastRole = null;
    const cancelled = () => !!(signal && signal.aborted) || control.isCancelled();

    const budgetStop = (node) => {
      if (limits.steps != null && steps.length >= limits.steps) return 'steps';
      if (limits.wallMs != null && elapsed() >= limits.wallMs) return 'wall';
      const zero = (lim, used, blocks) => lim === 0 ? (used > 0 || blocks) : (lim != null && used >= lim);
      if (zero(limits.tokens, usage.tokens, node.category === 'MODEL_INFERENCE')) return 'tokens';
      if (zero(limits.cost, usage.cost, false)) return 'cost';
      if (zero(limits.toolCalls, usage.toolCalls, node.category === 'TYPED_TOOL')) return 'toolCalls';
      return null;
    };
    const snapshot = () => ({ steps: steps.length, tokens: usage.tokens, cost: usage.cost, toolCalls: usage.toolCalls, elapsedMs: elapsed(), limits });

    while (current) {
      await control.checkpoint();
      if (cancelled()) return finish({ kind: 'cancelled', reason: 'cancelled' });
      const node = current;

      if (node.config?.passthrough) {
        emit('node:passed', { nodeId: node.nodeId });
      } else {
        if (steps.length >= hardCap) return finish({ kind: 'cap', reason: `hit step cap (${hardCap})`, dimension: 'steps' });
        const dim = budgetStop(node);
        if (dim) return finish({ kind: 'budget', reason: `budget:${dim}`, dimension: dim });
        const key = resolveExecutorKey(node, this.executors);
        if (!key) return finish({ kind: 'failure', reason: `no executor for node '${node.nodeId}'`, nodeId: node.nodeId, state: 'FAILED_SAFE', message: 'no executor bound' });

        visits[node.nodeId] = (visits[node.nodeId] || 0) + 1;
        const role = node.config?.role || node.config?.name || node.nodeId;
        const system = Params.substitute(node.config?.systemPrompt ?? node.explanation ?? '', params);
        const user = transcript + `\nProduce your contribution as ${role}.`;
        const input = {
          objective: resolvedObjective, params, role, system, user, transcript, last: lastOutput, lastRole,
          outputs: { ...outputs }, round: visits[node.nodeId], stepIndex: steps.length,
        };

        if (authorize && !(await authorize(node, { steps, usage: snapshot() }))) {
          return finish({ kind: 'policy', reason: `policy: node '${node.nodeId}' not authorised`, nodeId: node.nodeId });
        }

        emit('node:started', { nodeId: node.nodeId, category: node.category, stepIndex: steps.length, round: visits[node.nodeId], role });
        const waiting = WAITING.has(node.category);
        if (waiting) control.enter('WAITING_USER', `Waiting for ${node.category === 'HUMAN_DECISION' ? 'a decision' : 'an event'} at '${node.nodeId}'.`, node.nodeId);

        const stepUsage = { tokensIn: null, tokensOut: null, cost: 0, toolCalls: null, estimated: null };
        const ctx = {
          signal, params, objective: resolvedObjective, node, round: visits[node.nodeId], role, now, emit,
          get usage() { return snapshot(); }, outputs,
          reportUsage(u = {}) {
            if (u.tokensIn != null) stepUsage.tokensIn = (stepUsage.tokensIn ?? 0) + u.tokensIn;
            if (u.tokensOut != null) stepUsage.tokensOut = (stepUsage.tokensOut ?? 0) + u.tokensOut;
            if (u.cost != null) stepUsage.cost += u.cost;
            if (u.toolCalls != null) stepUsage.toolCalls = (stepUsage.toolCalls ?? 0) + u.toolCalls;
            if (u.estimated != null) stepUsage.estimated = stepUsage.estimated === false ? false : u.estimated;
          },
        };

        const run = await this.#attempt(node, key, input, ctx, attempts, now, signal, control);
        if (waiting && !run.cancelled) control.enter('RUNNING', `Node '${node.nodeId}' resolved; resuming.`, node.nodeId);
        if (run.cancelled) return finish({ kind: 'cancelled', reason: 'cancelled', inFlight: { nodeId: node.nodeId, sideEffectClass: node.sideEffectClass, settled: run.settled } });
        if (run.error) {
          const e = run.error;
          const mapped = def.terminalStateMapping?.nodeFailureEscalations?.find((x) => x.nodeId === node.nodeId)?.onFailure || node.onFailureTerminalState || 'FAILED_SAFE';
          let state = mapped;
          if (e instanceof PolicyBoundaryError) state = 'STOPPED_POLICY';
          else if (e instanceof TargetStateUnknownError) state = 'TARGET_STATE_UNKNOWN';
          else if (e instanceof SideEffectsPossibleError) state = 'FAILED_SIDE_EFFECTS_POSSIBLE';
          emit('node:failed', { nodeId: node.nodeId, message: e.message, state });
          return finish({ kind: state === 'STOPPED_POLICY' ? 'policy' : 'failure', reason: `node failed: ${node.nodeId}: ${e.message}`, nodeId: node.nodeId, state, message: e.message });
        }

        const output = typeof run.output === 'string' ? run.output.trim() : run.output;
        let tIn = stepUsage.tokensIn, tOut = stepUsage.tokensOut, est = stepUsage.estimated;
        if (this.tokenCounter && tIn == null && tOut == null) {
          const tk = await this.tokenCounter(node, input, output);
          if (tk) { tIn = tk.inputTokens; tOut = tk.outputTokens; est = tk.estimated; }
        }
        if (tIn != null || tOut != null) usage.tokens += (tIn ?? 0) + (tOut ?? 0);
        usage.cost += stepUsage.cost;
        usage.toolCalls += stepUsage.toolCalls ?? (node.category === 'TYPED_TOOL' ? 1 : 0);

        const step = {
          index: steps.length, nodeId: node.nodeId, role, category: node.category, model: node.config?.model || null, output,
          tokensIn: tIn, tokensOut: tOut, cost: stepUsage.cost || null, toolCalls: stepUsage.toolCalls ?? (node.category === 'TYPED_TOOL' ? 1 : null),
          durationMs: run.durationMs, estimated: est ?? true, round: visits[node.nodeId], attempt: run.attempt,
        };
        steps.push(step);
        outputs[node.nodeId] = output;
        if (node.category === 'VERIFIER') {
          const passed = output === true || (output && typeof output === 'object' && (output.passed === true || output.ok === true));
          verifierResults[node.nodeId] = { passed: !!passed, stepIndex: step.index, output };
          emit('verifier', { nodeId: node.nodeId, passed: !!passed });
        }
        emit('node:finished', { step });
        emit('budget', snapshot());
        if (onStep) await onStep(step);
        transcript += `\n${role}:\n${stepOutputText(output)}\n`;
        lastOutput = output; lastRole = role;
      }

      if (terminals.has(node.nodeId)) return finish({ kind: 'end', reason: 'reached end', terminalNodeId: node.nodeId });

      const outgoing = (def.edges || []).filter((e) => e.fromNodeId === node.nodeId);
      const gwId = outgoing.find((e) => e.gatewayId)?.gatewayId;
      const gateway = gwId ? (def.gateways || []).find((g) => g.gatewayId === gwId) || null : null;
      let picked;
      try {
        picked = this.gatewayPolicy.choose({ node, gateway, output: lastOutput, outgoing, scope: makeScope({ output: lastOutput, params, round: visits[node.nodeId] || 0, usage: snapshot() }) });
      } catch (e) {
        return finish({ kind: 'failure', reason: `gateway error at '${node.nodeId}': ${e.message}`, nodeId: node.nodeId, state: 'FAILED_SAFE', message: e.message });
      }
      if (!picked) {
        const reason = gateway ? `gateway gap: no outcome matched at '${gateway.gatewayId}'` : outgoing.length ? `ambiguous outgoing edges at '${node.nodeId}'` : 'no outgoing edge';
        return finish({ kind: 'dead-end', reason });
      }
      if (gateway || outgoing.length > 1) {
        emit('gateway', {
          nodeId: node.nodeId, gatewayId: gateway?.gatewayId ?? null, edgeId: picked.edge.edgeId, label: picked.outcome?.label ?? picked.edge.label ?? null,
          via: picked.via, alternatives: outgoing.filter((e) => e.edgeId !== picked.edge.edgeId).map((e) => e.edgeId), toNodeId: picked.edge.toNodeId,
        });
      }
      current = byId.get(picked.edge.toNodeId);
      if (!current) return finish({ kind: 'dead-end', reason: `edge '${picked.edge.edgeId}' points at unknown node` });
    }
    return finish({ kind: 'dead-end', reason: 'no outgoing edge' });
  }

  /** Runs one node with timeout, retries and cancellation. */
  async #attempt(node, key, input, ctx, attempts, now, signal, control) {
    const exec = this.executors[key];
    const maxRetries = node.retryPolicy?.maxRetries ?? 0;
    const timeoutS = node.timeoutPolicy?.timeoutSeconds;
    const base = (node.retryPolicy?.baseDelaySeconds ?? 0) * 1000;
    let lastError = null;
    for (let n = 0; n <= maxRetries; n++) {
      const rec = { nodeId: node.nodeId, attemptNumber: attempts.filter((a) => a.nodeId === node.nodeId).length + 1, startedAtMs: now(), nodeCategory: node.category, outcome: null, endedAtMs: null };
      attempts.push(rec);
      const started = now();
      let timer; let settled = false;
      const p = Promise.resolve().then(() => exec(node, input, { ...ctx, attempt: n + 1 })).finally(() => { settled = true; });
      p.catch(() => {});
      const racers = [p.then((v) => ({ v }), (e) => ({ e }))];
      if (timeoutS > 0) racers.push(new Promise((res) => { timer = setTimeout(() => res({ e: new TimeoutError(`node '${node.nodeId}' timed out after ${timeoutS}s`), timeout: true }), timeoutS * 1000); }));
      let abortListener;
      if (signal) racers.push(new Promise((res) => {
        abortListener = () => res({ aborted: true });
        if (signal.aborted) abortListener(); else signal.addEventListener('abort', abortListener, { once: true });
      }));
      const r = await Promise.race(racers);
      clearTimeout(timer);
      if (signal && abortListener) signal.removeEventListener('abort', abortListener);
      if (r.aborted || (r.e && isAbortError(r.e))) {
        // cooperative executors settle within the grace window; non-cooperative ones are reported as unsettled
        if (!settled) await Promise.race([p.catch(() => {}), this.sleep(this.cancelGraceMs)]);
        rec.outcome = 'FAILED'; rec.endedAtMs = now();
        return { cancelled: true, settled };
      }
      rec.endedAtMs = now();
      if (r.e) {
        lastError = r.e;
        // a timeout retries only when the node says onTimeout=RETRY; ESCALATE_TO_USER is deferred (treated as FAIL_SAFE)
        const retryable = n < maxRetries && !(r.e instanceof PolicyBoundaryError) && !(r.e instanceof TargetStateUnknownError) &&
          !(r.e instanceof SideEffectsPossibleError) && (!r.timeout || node.timeoutPolicy?.onTimeout === 'RETRY');
        rec.outcome = r.timeout ? 'TIMED_OUT' : retryable ? 'RETRIED' : 'FAILED';
        if (!retryable) break;
        const delay = node.retryPolicy?.backoff === 'EXPONENTIAL' ? base * 2 ** n : node.retryPolicy?.backoff === 'FIXED' ? base : 0;
        ctx.emit('node:retry', { nodeId: node.nodeId, attempt: n + 1, delayMs: delay, message: r.e.message });
        await this.sleep(delay, signal);
        continue;
      }
      rec.outcome = 'SUCCEEDED';
      return { output: r.v, durationMs: now() - started, attempt: n + 1 };
    }
    return { error: lastError };
  }
}
