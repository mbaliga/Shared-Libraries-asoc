/* LoopRunDriver — the §9 lifecycle around GraphRunner. Port of Kotlin LoopRunDriver, with the state machine live
   (events are emitted as states are entered, not reconstructed afterwards) so a UI can show WAITING_USER, SUSPENDED
   and CANCELLING as they happen.

   CREATED > PREFLIGHT > WAITING_BINDING > WAITING_AUTHORITY > READY > RUNNING > { WAITING_USER | SUSPENDED }* > terminal

   The two seams `resolveBinding` / `resolveAuthority` default to "auto-resolve", as in Kotlin: hosts wire them to a
   real binding / authority prompt. Every transition is checked against the §9 table (an illegal one throws, mirroring
   Kotlin's RunEvent constructor), and the result is a LoopRun that validates against loop-run.schema.json. */

import { GraphRunner, resolveExecutorKey, effectiveLimits } from './graph-runner.js';
import { RecordingGatewayPolicy, ReplayGatewayPolicy, conditionGatewayPolicy } from './gateway.js';
import { RunLedger } from './ledger.js';
import { isValidTransition, isTerminal } from './states.js';
import { validateDefinition, verifierMatches } from './validate.js';
import { semanticDigest } from './digest.js';
import * as Params from './params.js';

const iso = (ms) => new Date(ms).toISOString();
let counter = 0;
const rid = (p) => p + (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID().replace(/-/g, '').slice(0, 20) : `${Date.now().toString(36)}${(counter++).toString(36)}`);

export class IllegalTransitionError extends Error { constructor(a, b) { super(`'${a}' -> '${b}' is not a valid LOOP_ENGINEERING_SPEC_V2.1 §9 transition`); this.name = 'IllegalTransitionError'; } }

const BUDGET_CATEGORY = {
  steps: 'BUDGET_STEPS_EXHAUSTED', wall: 'BUDGET_WALL_CLOCK_EXHAUSTED', tokens: 'BUDGET_TOKENS_EXHAUSTED',
  cost: 'BUDGET_COST_EXHAUSTED', toolCalls: 'BUDGET_TOOL_CALLS_EXHAUSTED',
};
const FAILURE_CATEGORY = {
  FAILED_SAFE: 'NODE_FAILURE_NO_SIDE_EFFECTS', FAILED_SIDE_EFFECTS_POSSIBLE: 'NODE_FAILURE_SIDE_EFFECTS_POSSIBLE',
  TARGET_STATE_UNKNOWN: 'SIDE_EFFECT_OUTCOME_UNCONFIRMED', STOPPED_POLICY: 'POLICY_BOUNDARY', CANCELLED: 'CANCELLATION_REQUESTED',
  STOPPED_BUDGET: 'BUDGET_STEPS_EXHAUSTED',
};
const EXTERNAL = new Set(['PERSISTENT_LOCAL', 'EXTERNAL_VISIBLE', 'DESTRUCTIVE']);
const SIDE_EFFECT_STATE = { FAILED_SIDE_EFFECTS_POSSIBLE: 'PARTIAL', TARGET_STATE_UNKNOWN: 'UNKNOWN' };

/** Required-verifier evaluation for a run that reached a terminal node (§9 rows SUCCEEDED_*). */
export function evaluateVerification(def, verifierResults) {
  const nodes = new Map((def.nodes || []).map((n) => [n.nodeId, n]));
  const required = [];
  for (const v of def.verificationPolicy?.verifiers || []) if (v.requiredForSuccess) {
    const matching = [...nodes.values()].filter((n) => n.category === 'VERIFIER' && verifierMatches(v, n));
    required.push({ id: v.verifierId, nodeIds: matching.map((n) => n.nodeId) });
  }
  for (const n of nodes.values()) if (n.category === 'VERIFIER' && n.verificationRequirements?.requiredForSuccess && !required.some((r) => r.nodeIds.includes(n.nodeId))) {
    required.push({ id: n.nodeId, nodeIds: [n.nodeId] });
  }
  const results = required.map((r) => ({ ...r, passed: r.nodeIds.length > 0 && r.nodeIds.some((id) => verifierResults[id]?.passed) }));
  return { required: results, allPassed: results.length > 0 && results.every((r) => r.passed) };
}

export class LoopRunDriver {
  /**
   * @param {object} cfg
   * @param {Record<string,Function>} cfg.executors
   * @param {object} [cfg.gatewayPolicy]  default conditionGatewayPolicy; always wrapped by a RecordingGatewayPolicy
   * @param {Function} [cfg.tokenCounter]
   * @param {Function} [cfg.idGenerator]  () => run id
   * @param {Function} [cfg.now]          () => epoch ms (injectable clock)
   * @param {Function} [cfg.sleep]
   */
  constructor({ executors = {}, gatewayPolicy = conditionGatewayPolicy, tokenCounter = null, idGenerator = () => rid('run_'), now = Date.now, sleep = null, cancelGraceMs } = {}) {
    Object.assign(this, { executors, gatewayPolicy, tokenCounter, idGenerator, now, sleep, cancelGraceMs });
  }

  /** Convenience: the LoopRun only (Kotlin `run`). */
  async run(def, objective, opts = {}) { return (await this.start(def, objective, opts).promise).run; }
  /** { run, result, ledger, recording } */
  execute(def, objective, opts = {}) { return this.start(def, objective, opts).promise; }

  /**
   * Starts a run and returns a live handle { runId, on, off, cancel, suspend, resume, state, events, ledger, promise }.
   * opts: sourceRef, bindingProfileRef, hardCap, params, budget, resolveBinding, resolveAuthority, authorize, onStep,
   *       signal, replay (a recording from an earlier run — see replay.js), projectId
   */
  start(def, objective, opts = {}) {
    const now = this.now;
    const runId = this.idGenerator();
    const listeners = new Map();
    const ledger = new RunLedger(now);
    const eventLog = [];
    const userInterventions = [];
    let state = null;
    let evtN = 0;
    let suspendRequested = false; let resumeWaiter = null; let cancelRequested = false;
    const ac = new AbortController();
    if (opts.signal) { if (opts.signal.aborted) ac.abort(); else opts.signal.addEventListener('abort', () => { cancelRequested = true; ac.abort(); }, { once: true }); }

    const emitEvent = (type, data = {}) => {
      ledger.append(type, data);
      for (const fn of [...(listeners.get(type) || []), ...(listeners.get('*') || [])]) { try { fn({ type, runId, ...data }); } catch { /* a listener must not break a run */ } }
    };
    const on = (type, fn) => { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(fn); return () => off(type, fn); };
    const off = (type, fn) => listeners.get(type)?.delete(fn);

    const enter = (to, description, nodeId = null) => {
      if (state !== null && !isValidTransition(state, to)) throw new IllegalTransitionError(state, to);
      if (state === null && to !== 'CREATED') throw new IllegalTransitionError('(none)', to);
      const ev = { eventId: `evt_${runId}_${++evtN}`, occurredAtUtc: iso(now()), toState: to, description, fromState: state, nodeId };
      eventLog.push(ev);
      const from = state; state = to;
      emitEvent('state', { from, to, description, nodeId });
    };

    const control = {
      enter: (to, d, nodeId) => {
        if (to === 'WAITING_USER') emitEvent('wait', { nodeId });
        enter(to, d, nodeId);
        if (to === 'RUNNING' && nodeId) userInterventions.push({ interventionId: `int_${userInterventions.length + 1}`, occurredAtUtc: iso(now()), kind: 'HUMAN_DECISION_SUPPLIED', nodeId });
      },
      isCancelled: () => cancelRequested,
      checkpoint: async () => {
        if (suspendRequested && !cancelRequested) {
          enter('SUSPENDED', 'Operational pause requested.');
          await new Promise((res) => { resumeWaiter = res; });
          resumeWaiter = null;
          if (!cancelRequested) enter('RUNNING', 'Run resumed.');
        }
      },
    };

    const handle = {
      runId, on, off, ledger,
      get state() { return state; },
      get events() { return eventLog.slice(); },
      cancel(reason = 'cancelled by user') {
        if (isTerminal(state) || cancelRequested) return;
        cancelRequested = true;
        userInterventions.push({ interventionId: `int_${userInterventions.length + 1}`, occurredAtUtc: iso(now()), kind: 'CANCELLATION_REQUESTED', details: reason });
        ac.abort(); resumeWaiter?.();
      },
      suspend() { if (state === 'RUNNING') { suspendRequested = true; userInterventions.push({ interventionId: `int_${userInterventions.length + 1}`, occurredAtUtc: iso(now()), kind: 'SUSPEND_REQUESTED' }); } },
      resume() { if (suspendRequested) { suspendRequested = false; userInterventions.push({ interventionId: `int_${userInterventions.length + 1}`, occurredAtUtc: iso(now()), kind: 'RESUME_REQUESTED' }); resumeWaiter?.(); } },
      promise: null,
    };

    handle.promise = (async () => {
      const params = opts.params || {};
      const sourceRef = opts.sourceRef || { draftId: 'draft-local', draftRevisionNumber: 0 };
      const bindingProfileRef = opts.bindingProfileRef || { objectId: 'binding-local' };
      const createdAt = iso(now());
      const digest = await semanticDigest(def);
      enter('CREATED', 'Run initiated.');
      enter('PREFLIGHT', 'Preflight: static validation, budget declaration, parameters, executors.');

      const build = (terminalReason, result, extra = {}) => ({
        schemaVersion: '1.0.0', runId, definitionDigest: digest.digest, sourceRef, bindingProfileRef, runState: state, createdAtUtc: createdAt,
        parameters: params, targetDecisions: [], eventLog: eventLog.map((e) => ({ ...e })), nodeAttempts: (result?.attempts || []).map((a) => ({
          nodeId: a.nodeId, attemptNumber: a.attemptNumber, startedAtUtc: iso(a.startedAtMs), endedAtUtc: a.endedAtMs != null ? iso(a.endedAtMs) : null,
          outcome: a.outcome, nodeCategory: a.nodeCategory,
        })), userInterventions, outputs: result && (result.reachedEnd || result.steps.length) ? {
          primaryResult: result.finalOutput, artifacts: [], receipts: [], warnings: [],
          humanReadableSummary: (typeof result.finalOutput === 'string' ? result.finalOutput : JSON.stringify(result.finalOutput ?? '')).slice(0, 500) || null,
        } : null, terminalReason, authorityGrants: [], sideEffectState: extra.sideEffectState ?? 'NONE', updatedAtUtc: iso(now()),
      });
      const done = (terminalReason, result, extra) => {
        const run = build(terminalReason, result, extra);
        const out = { run, result, ledger, recording: recorder ? makeRecording(digest.digest, opts, objective, result, recorder) : null, handle };
        emitEvent('done', { runState: state, terminalReason });
        return out;
      };

      // --- preflight
      const report = validateDefinition(def);
      const problems = [];
      if (!report.valid) problems.push(...report.findings.filter((f) => f.severity === 'ERROR').map((f) => `${f.code}: ${f.message}`));
      const missing = Params.missing(def, objective, params);
      if (missing.length) problems.push(`Missing params: ${missing.join(', ')}`);
      const execs = opts.replay ? replayExecutors(def, opts.replay) : this.executors;
      for (const n of def.nodes || []) if (!n.config?.passthrough && !resolveExecutorKey(n, execs)) problems.push(`No executor bound for node '${n.nodeId}'`);
      emitEvent('preflight', { findings: report.findings, missing });
      let recorder = null;
      if (problems.length) {
        enter('FAILED_SAFE', `Preflight failed: ${problems.length} problem(s).`);
        return done({ category: 'VALIDATION_FAILURE', detail: missing.length && problems.length === 1 ? `Missing params: ${missing.join(', ')}` : problems.join('; ') }, null);
      }

      enter('WAITING_BINDING', 'Awaiting execution-target binding.');
      if (!(await (opts.resolveBinding ? opts.resolveBinding() : true))) {
        enter('CANCELLING', 'Binding could not be resolved; cancelling.');
        enter('CANCELLED', 'Run cancelled: no execution target bound.');
        return done({ category: 'CANCELLATION_REQUESTED', detail: 'Execution-target binding was not resolved.' }, null);
      }
      enter('WAITING_AUTHORITY', 'Awaiting authority grant.');
      if (!(await (opts.resolveAuthority ? opts.resolveAuthority() : true))) {
        userInterventions.push({ interventionId: 'int_authority', occurredAtUtc: iso(now()), kind: 'AUTHORITY_GRANT_DECLINED' });
        enter('CANCELLING', 'Authority not granted; cancelling.');
        enter('CANCELLED', 'Run cancelled: authority grant declined.');
        return done({ category: 'CANCELLATION_REQUESTED', detail: 'Authority grant was declined.' }, null);
      }
      userInterventions.push({ interventionId: 'int_authority', occurredAtUtc: iso(now()), kind: 'AUTHORITY_GRANT_APPROVED' });
      enter('READY', 'Ready to run.');
      enter('RUNNING', 'Execution started.');

      // --- run
      let policy = opts.replay ? new ReplayGatewayPolicy(opts.replay.gateways, this.gatewayPolicy) : this.gatewayPolicy;
      recorder = new RecordingGatewayPolicy(policy);
      const executors = execs;
      const runner = new GraphRunner({ executors, gatewayPolicy: recorder, tokenCounter: opts.replay ? null : this.tokenCounter, now, sleep: this.sleep, cancelGraceMs: this.cancelGraceMs });
      const result = await runner.run(def, objective, {
        hardCap: opts.hardCap ?? 24, params, budget: opts.budget || null, signal: ac.signal, control, authorize: opts.authorize || null,
        onStep: opts.onStep || null, emit: (t, d) => emitEvent(t, d),
      });
      const stop = result.stop;
      const detail = result.stoppedBecause;

      // classify (§9 terminal rows)
      if (stop.kind === 'end') {
        const v = evaluateVerification(def, result.verifierResults);
        if (v.allPassed) { enter('SUCCEEDED_VERIFIED', 'Run stopped: reached end; every required verifier passed.'); return done({ category: 'ALL_REQUIRED_VERIFIERS_PASSED', detail }, result, { sideEffectState: 'COMPLETED' }); }
        if (def.verificationPolicy?.requireVerificationForSuccess && def.terminalStateMapping?.unverifiedSuccessAllowed === false) {
          enter('FAILED_SAFE', 'Run reached its end but required verification is incomplete and unverified success is not allowed.');
          return done({ category: 'REQUIRED_VERIFIER_INCOMPLETE', detail: `${detail}; verification incomplete` }, result);
        }
        enter('SUCCEEDED_UNVERIFIED', 'Run stopped: reached end; verification incomplete or none required.');
        return done({ category: 'REQUIRED_VERIFIER_INCOMPLETE', detail }, result, { sideEffectState: 'COMPLETED' });
      }
      if (stop.kind === 'cancelled') {
        enter('CANCELLING', 'Cancellation requested; stopping in-flight work.');
        const inf = stop.inFlight;
        const unsafe = inf && !inf.settled && EXTERNAL.has(inf.sideEffectClass);
        if (unsafe) { enter('TARGET_STATE_UNKNOWN', `In-flight node '${inf.nodeId}' could not be confirmed stopped.`, inf.nodeId); return done({ category: 'CANCELLATION_TARGET_UNCONFIRMED', detail: `node '${inf.nodeId}' (${inf.sideEffectClass}) still in flight` }, result, { sideEffectState: 'UNKNOWN' }); }
        enter('CANCELLED', 'Run cancelled mid-execution.');
        return done({ category: 'CANCELLATION_REQUESTED', detail }, result);
      }
      if (stop.kind === 'budget' || stop.kind === 'cap') { enter('STOPPED_BUDGET', `Run stopped: ${detail}.`); return done({ category: BUDGET_CATEGORY[stop.dimension || 'steps'], detail }, result); }
      if (stop.kind === 'policy') { enter('STOPPED_POLICY', `Run stopped: ${detail}.`); return done({ category: 'POLICY_BOUNDARY', detail }, result); }
      if (stop.kind === 'failure') {
        const st = stop.state || 'FAILED_SAFE';
        if (st === 'CANCELLED') { enter('CANCELLING', `Node '${stop.nodeId}' failed; mapped to cancellation.`, stop.nodeId); enter('CANCELLED', `Run stopped: ${detail}.`, stop.nodeId); }
        else enter(st, `Run stopped: ${detail}.`, stop.nodeId);
        return done({ category: FAILURE_CATEGORY[st] || 'NODE_FAILURE_NO_SIDE_EFFECTS', detail }, result, { sideEffectState: SIDE_EFFECT_STATE[st] ?? 'NONE' });
      }
      // params | invalid | dead-end
      enter('FAILED_SAFE', `Run stopped: ${detail}.`);
      return done({ category: 'VALIDATION_FAILURE', detail }, result);
    })();
    return handle;
  }
}

function makeRecording(digest, opts, objective, result, recorder) {
  return {
    format: 'loop-recording/1', definitionDigest: digest, objective, params: opts.params || {},
    steps: (result?.steps || []).map((s) => ({ nodeId: s.nodeId, output: s.output, tokensIn: s.tokensIn, tokensOut: s.tokensOut, cost: s.cost, toolCalls: s.toolCalls, estimated: s.estimated, durationMs: s.durationMs })),
    gateways: recorder.recorded, stoppedBecause: result?.stoppedBecause ?? null,
    verifierResults: result?.verifierResults ?? {},
  };
}

/** Executors that answer from a recording: per node, the recorded outputs in order. Verifier results replay too. */
function replayExecutors(def, recording) {
  const per = new Map();
  for (const s of recording.steps) { if (!per.has(s.nodeId)) per.set(s.nodeId, []); per.get(s.nodeId).push(s); }
  const ex = {};
  for (const n of def.nodes) {
    ex[n.nodeId] = async (node, _input, ctx) => {
      const q = per.get(node.nodeId);
      const s = q && q.shift();
      if (!s) { const e = new Error(`replay: no recorded output left for node '${node.nodeId}'`); e.name = 'ReplayDivergenceError'; throw e; }
      ctx.reportUsage({ tokensIn: s.tokensIn ?? undefined, tokensOut: s.tokensOut ?? undefined, cost: s.cost ?? undefined, estimated: s.estimated });
      return s.output;
    };
  }
  return ex;
}

export { effectiveLimits };
