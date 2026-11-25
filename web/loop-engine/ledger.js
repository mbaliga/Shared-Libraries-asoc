/* Run ledger and run log.
   - RunLedger: the append-only runtime record of everything a run did (state changes, node starts/finishes, gateway
     decisions, budget snapshots). Entries are frozen on append; the only operation is `append`. This is the web
     equivalent of the tamper-evident intent behind Kotlin's ledger; it is in-memory and the host persists it.
   - toLedgerEntries: port of Kotlin GraphRunLedger — one cost/usage entry per completed step.
   - toTreeNodes: port of Kotlin GraphRunLog — a run as a detached sub-tree (objective root, one node per step). */

export class RunLedger {
  #entries = [];
  #now;
  constructor(now = Date.now) { this.#now = now; }
  append(type, data = {}) {
    const entry = Object.freeze({ seq: this.#entries.length, atMs: this.#now(), type, ...data });
    this.#entries.push(entry);
    return entry;
  }
  get length() { return this.#entries.length; }
  get entries() { return this.#entries.slice(); }
  ofType(type) { return this.#entries.filter((e) => e.type === type); }
  toJSON() { return this.#entries.slice(); }
}

// ---- pricing (Kotlin PricingBook / UsagePricing)

export const ON_DEVICE_PRICING = Object.freeze({ centsPer1kInput: 0, centsPer1kOutput: 0 });
export const CONSERVATIVE_DEFAULT_PRICING = Object.freeze({ centsPer1kInput: 1, centsPer1kOutput: 3 });

export class PricingBook {
  constructor(byModel = {}, fallback = CONSERVATIVE_DEFAULT_PRICING) { this.byModel = { ...byModel }; this.fallback = fallback; }
  /** An explicit entry, else ON_DEVICE for a non-`cloud:` id, else the fallback. */
  priceFor(modelId) {
    if (Object.prototype.hasOwnProperty.call(this.byModel, modelId)) return this.byModel[modelId];
    return String(modelId).startsWith('cloud:') ? this.fallback : ON_DEVICE_PRICING;
  }
  isExplicit(modelId) { return Object.prototype.hasOwnProperty.call(this.byModel, modelId); }
  with(modelId, pricing) { return new PricingBook({ ...this.byModel, [modelId]: pricing }, this.fallback); }
  without(modelId) { const m = { ...this.byModel }; delete m[modelId]; return new PricingBook(m, this.fallback); }
  withFallback(pricing) { return new PricingBook(this.byModel, pricing); }
}

/** Integer minor units, each direction floored separately (22*300/1000 -> 6, 6*1500/1000 -> 9: 15). */
export function moneyMinor(tokensIn, tokensOut, pricing) {
  return Math.floor((tokensIn * pricing.centsPer1kInput) / 1000) + Math.floor((tokensOut * pricing.centsPer1kOutput) / 1000);
}

/** One LedgerEntry per completed step, correlated 1:1 with `treeNodes` (objective root first, then one per step).
 *  Cloud (estimated === false) steps are priced through `pricingBook`; on-device steps are never priced; a model
 *  that cannot be resolved to a tokenizer id is priced as on-device — never an invented number. */
export function toLedgerEntries(result, treeNodes, { loopId = null, runId, projectId = null, timestampMillis = 0, pricingBook = new PricingBook(), resolveTokenizerId = (id) => id } = {}) {
  const stepNodes = treeNodes.slice(1);
  return result.steps.map((step, i) => {
    const tier = step.estimated ? 'ON_DEVICE' : 'CLOUD';
    let estCostMinor = 0;
    if (tier === 'CLOUD') {
      const tid = resolveTokenizerId(step.model);
      const pricing = tid != null ? pricingBook.priceFor(tid) : ON_DEVICE_PRICING;
      estCostMinor = moneyMinor(step.tokensIn ?? 0, step.tokensOut ?? 0, pricing);
    }
    return {
      timestampMillis, projectId, chatId: runId, nodeId: stepNodes[i]?.id ?? `${runId}:${step.index}`,
      model: step.model ?? step.role, provider: tier === 'ON_DEVICE' ? 'on-device' : 'cloud', tier,
      inputTokens: Math.max(0, step.tokensIn ?? 0), outputTokens: Math.max(0, step.tokensOut ?? 0),
      estCostMinor: Math.max(0, estCostMinor), latencyMs: Math.max(0, step.durationMs), estimated: step.estimated,
      status: 'COMPLETE', surface: 'loop', loopId,
    };
  });
}

export const TAG = Object.freeze({ RUN: 'loopRunId', LOOP: 'loopId', ROLE: 'nodeRole', STEP: 'stepIndex', NODE: 'nodeId', STOPPED: 'stoppedBecause' });

/** Detached sub-tree: objective root (parentId null) then one assistant node per step, chained in order. */
export function toTreeNodes(objective, result, { loopRunId, loopId = null, now = 0, idGen }) {
  const tag = (role, extra = {}) => ({ [TAG.RUN]: loopRunId, ...(loopId != null ? { [TAG.LOOP]: loopId } : {}), [TAG.ROLE]: role, ...extra });
  const nodes = [];
  const root = { id: idGen(), parentId: null, role: 'user', content: objective, createdAt: now, metadata: tag('objective', { [TAG.STOPPED]: result.stoppedBecause }) };
  nodes.push(root);
  let parent = root.id;
  for (const step of result.steps) {
    const n = {
      id: idGen(), parentId: parent, role: 'assistant', content: typeof step.output === 'string' ? step.output : JSON.stringify(step.output),
      modelId: step.model, createdAt: now, metadata: tag(step.role, { [TAG.STEP]: String(step.index), [TAG.NODE]: step.nodeId }),
    };
    nodes.push(n); parent = n.id;
  }
  return nodes;
}
