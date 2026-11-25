/* Gateway policies — ports of Kotlin GatewayPolicy / ConditionGatewayPolicy / RecordingGatewayPolicy, reshaped for
   the v2 definition model where a gateway is a first-class object (LOOP_ENGINEERING_SPEC_V2.1 §5):

     choose({ node, gateway, output, outgoing, scope })  ->  { edge, outcome, via } | null

   `outgoing` are the node's outgoing EdgeDefinitions; `gateway` the GatewayDefinition their `gatewayId` names (or
   null for a plain edge). `via` is 'single' | 'condition' | 'default'. A policy returning null means "no way on"
   and the run stops (FAILED_SAFE). */

import { evalCondition, makeScope, CondError } from './conditions.js';

export class GatewayEvaluationError extends Error {
  constructor(msg) { super(msg); this.name = 'GatewayEvaluationError'; }
}

export class ReplayDivergenceError extends Error {
  constructor(msg) { super(msg); this.name = 'ReplayDivergenceError'; }
}

function edgeFor(outcome, gateway, outgoing, node) {
  return outgoing.find((e) => e.gatewayId === gateway.gatewayId && e.toNodeId === outcome.targetNodeId && (e.label == null || e.label === outcome.label)) ||
    outgoing.find((e) => e.toNodeId === outcome.targetNodeId) ||
    { edgeId: `${gateway.gatewayId}:${outcome.label}`, fromNodeId: node.nodeId, toNodeId: outcome.targetNodeId, label: outcome.label, gatewayId: gateway.gatewayId, synthetic: true };
}

/** Outcomes by ascending priority; a null priority sorts after every number; declaration order breaks ties. */
export function orderedOutcomes(gateway) {
  return (gateway.outcomes || []).map((o, i) => ({ o, i }))
    .sort((a, b) => ((a.o.priority ?? Infinity) - (b.o.priority ?? Infinity)) || (a.i - b.i)).map((x) => x.o);
}

/** The default policy. Typed conditions (conditions.js) are evaluated against the source node's output; the first
 *  matching outcome by priority wins; a null/empty condition always matches; otherwise the declared default path. */
export const conditionGatewayPolicy = {
  choose({ node, gateway, output, outgoing, scope }) {
    if (!outgoing.length) return null;
    if (!gateway) return outgoing.length === 1 ? { edge: outgoing[0], outcome: null, via: 'single' } : null;
    if (outgoing.length === 1 && !(gateway.outcomes || []).length) return { edge: outgoing[0], outcome: null, via: 'single' };
    const sc = scope || makeScope({ output });
    for (const o of orderedOutcomes(gateway)) {
      let hit;
      if (o.conditionExpression == null || o.conditionExpression === '') hit = true;
      else {
        try { hit = evalCondition(o.conditionExpression, sc); }
        catch (e) {
          if (e instanceof CondError) throw new GatewayEvaluationError(`gateway '${gateway.gatewayId}' outcome '${o.label}': ${e.message}`);
          throw e;
        }
      }
      if (hit) return { edge: edgeFor(o, gateway, outgoing, node), outcome: o, via: 'condition' };
    }
    const d = gateway.defaultOrTerminalFailurePath;
    if (d) return { edge: edgeFor(d, gateway, outgoing, node), outcome: d, via: 'default' };
    return null;
  },
};

/** Wraps a policy and records every genuine "choose for me" decision: a node with more than one outgoing edge.
 *  A single-exit node is not a choice and is never recorded (Kotlin RecordingGatewayPolicy). */
export class RecordingGatewayPolicy {
  constructor(inner = conditionGatewayPolicy) { this.inner = inner; this._recorded = []; }
  get recorded() { return this._recorded.map((r) => ({ ...r, alternativeRefs: [...r.alternativeRefs] })); }
  choose(ctx) {
    const picked = this.inner.choose(ctx);
    if (!picked) return null;
    if (ctx.outgoing.length > 1) {
      this._recorded.push({
        nodeId: ctx.node.nodeId,
        gatewayId: ctx.gateway ? ctx.gateway.gatewayId : null,
        chosenEdgeRef: picked.edge.edgeId,
        alternativeRefs: ctx.outgoing.filter((e) => e.edgeId !== picked.edge.edgeId).map((e) => e.edgeId),
      });
    }
    return picked;
  }
}

/** Deterministic replay: replays a recorded sequence of gateway decisions instead of evaluating conditions.
 *  Throws ReplayDivergenceError if the run reaches a different gateway than the recording, or the recorded edge is
 *  not available. Single-exit nodes are not recorded, so they fall through to the inner policy. */
export class ReplayGatewayPolicy {
  constructor(recorded, inner = conditionGatewayPolicy) { this.queue = recorded.map((r) => ({ ...r })); this.inner = inner; this.consumed = 0; }
  get exhausted() { return this.queue.length === 0; }
  choose(ctx) {
    if (ctx.outgoing.length <= 1) return this.inner.choose(ctx);
    const next = this.queue.shift();
    if (!next) throw new ReplayDivergenceError(`replay: unexpected gateway decision at '${ctx.node.nodeId}' (recording exhausted)`);
    if (next.nodeId !== ctx.node.nodeId) throw new ReplayDivergenceError(`replay: expected gateway at '${next.nodeId}', run reached '${ctx.node.nodeId}'`);
    const edge = ctx.outgoing.find((e) => e.edgeId === next.chosenEdgeRef);
    if (!edge) throw new ReplayDivergenceError(`replay: recorded edge '${next.chosenEdgeRef}' is not an exit of '${ctx.node.nodeId}'`);
    this.consumed++;
    const outcome = ctx.gateway ? ([...(ctx.gateway.outcomes || []), ctx.gateway.defaultOrTerminalFailurePath].filter(Boolean)
      .find((o) => o.targetNodeId === edge.toNodeId && (edge.label == null || o.label === edge.label)) || null) : null;
    return { edge, outcome, via: 'replay' };
  }
}
