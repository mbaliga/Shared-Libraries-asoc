# loop-engine — Fonebrew's loop engine, on the web

A dependency-free ES-module port of the loop engine in Fonebrew's `domain/loop` (`mbaliga/android-ide-core`, commit
`f23f6e491f449fc8acf3ee77adc0d44916dde945`, the read-only source of truth for loop semantics). It loads and statically
validates a **LoopDefinition v2**, runs it through host-supplied executors, drives the **§9 run state machine**, and
keeps an append-only ledger. No runtime dependencies, no bundler, no network: the engine never calls a model, tool or
person itself — the host does, through executors. Consumed by copying, like the other `web/` modules.

```js
import { LoopRunDriver, validateDefinition, replayRun } from './loop-engine/index.js';

const report = validateDefinition(def);              // { valid, findings: [{ code, severity, message, objectId… }] }
const driver = new LoopRunDriver({
  executors: {                                       // nodeId | capabilityId | slotId | category  ->  fn
    MODEL_INFERENCE: async (node, input, ctx) => { ctx.reportUsage({ tokensIn: 120, tokensOut: 60 }); return await callMyModel(input.system, input.user, ctx.signal); },
    VERIFIER:        async (node, input, ctx) => ({ passed: true }),
    HUMAN_DECISION:  async (node, input, ctx) => await askThePerson(node, ctx.signal),
  },
});
const handle = driver.start(def, 'objective', { params: {}, budget: { maxSteps: 8 } });
handle.on('*', (e) => console.log(e.type, e));       // state, node:started, node:finished, gateway, budget, verifier, done…
const { run, result, ledger, recording } = await handle.promise;   // run is a loop-run.schema.json LoopRun
await replayRun(def, recording);                     // deterministic replay, no executors consulted
```

## Files

| File | What it is | Kotlin counterpart |
|---|---|---|
| `index.js` | Public surface (re-exports everything below). | — |
| `validate.js` | JSON Schema layer + the `LOOP-*` semantic rules (table below), deterministic findings. | rule registry `loop-validation-rules.v1.json` |
| `jsonschema.js` | A small draft-2020-12-subset validator (`$ref`, `if/then`, `oneOf`, …) enough for the two schemas. | — |
| `conditions.js` | Typed gateway conditions: tokenizer, parser, static type check, evaluator. | `ConditionGatewayPolicy` |
| `gateway.js` | `conditionGatewayPolicy`, `RecordingGatewayPolicy`, `ReplayGatewayPolicy`. | `GatewayPolicy`, `ConditionGatewayPolicy`, `RecordingGatewayPolicy` |
| `graph-runner.js` | `GraphRunner`: walks the graph through executors; budgets, hard cap, retries, timeouts, cancellation. | `GraphRunner` |
| `run-driver.js` | `LoopRunDriver`: the live §9 state machine around the runner; builds the `LoopRun`. | `LoopRunDriver` |
| `states.js` | The 17 run states, transition table, terminal reason categories, event-log checker. | `contracts/kotlin/LoopRuntimeContracts.kt` |
| `ledger.js` | `RunLedger` (append-only), `toLedgerEntries` + `PricingBook`, `toTreeNodes`. | `GraphRunLedger`, `GraphRunLog`, `PricingBook` |
| `replay.js` | `replayRun`, `compareRuns`. | `RecordingGatewayPolicy` (used for replay) |
| `params.js` | `${key}` placeholders; refuse-to-start when one is unbound. | `LoopParams` |
| `loop-config.js` | The canonical proposer/critic loop as an editable config <-> LoopDefinition. | `LoopConfig`, `LoopConfigBpmn` |
| `digest.js` | Semantic digest (`sha256:…`): exclusion list, canonical JSON, NFC. | `semantic-digest.v1.json` |
| `schemas/` + `schemas.js` | The copied schemas, registries and fixtures, and their embedded form. See `schemas/SOURCE.md`. | `schemas/loops/**`, `fixtures/loops/**` |

`npm test` runs 125 tests with `node --test` (Node 20+). To refresh the schemas, re-copy them, update
`schemas/SOURCE.md`, then run `node tools/embed-schemas.mjs` (a test fails if `schemas.js` drifts from the JSON).

## Definition model

A LoopDefinition v2 as in `schemas/loop-definition.v2.schema.json`: `nodes` (eight categories), `edges`
(`fromNodeId`, `toNodeId`, `label`, `gatewayId`), `gateways` (`outcomes` with `label`, `conditionExpression`,
`priority`, `targetNodeId`; `exhaustive`; `defaultOrTerminalFailurePath`), `budgets`, `verificationPolicy`,
`terminalStateMapping`. Presentation keys (`presentation`, `layout`, `x`, `y`, `notes`) never affect the digest.

Engine conventions that live in `additionalProperties` space (so the schema stays untouched):

| Field | Meaning |
|---|---|
| `node.config.systemPrompt` | The node's instruction; `${key}` placeholders are substituted and scanned. |
| `node.config.role`, `.name`, `.model` | Display role, display name, model id recorded on the step (Kotlin `ext["role"]`, `ext["model"]`). |
| `node.config.passthrough: true` | Structural node (start/end marker): no executor, not a step, not counted in budgets. |
| `node.config.iterations`, `.mock`, `.objective` | Used by `loop-config.js` and the studio only. |

## Node execution

`executors[key](node, input, ctx) -> output`. Key resolution per node: `nodeId`, then `implementation.capabilityId` /
`slotId` / `subloopDefinitionRef`, then `category`.

`input` = `{ objective, params, role, system, user, transcript, last, lastRole, outputs, round, stepIndex }` — the Kotlin
runner's system/user/transcript, plus the latest output per node. `ctx` = `{ signal, params, node, round, attempt,
now, emit, usage, outputs, reportUsage({ tokensIn, tokensOut, cost, toolCalls, estimated }) }`. A string result is
trimmed (as in Kotlin); any other JSON value is kept as is. A `tokenCounter(node, input, output)` option, as in Kotlin,
supplies counts when an executor reports none. Token totals are `null`, never a fabricated 0, when nothing counted.

Categories: deterministic transformation, model inference, typed tool (counts one tool call unless it reports its own),
human decision and wait/event gate (the run sits in `WAITING_USER` while the executor is pending), bounded subloop
(executor-driven; see deferred), **verifier** (result `true` / `{ passed: true }` is recorded per node and decides
`SUCCEEDED_VERIFIED`), artifact import/export.

## Gateways

Conditions are typed expressions evaluated against the source node's output (fields are top-level identifiers; also
`output`, `text`, `params`, `round`, `usage`): comparisons, `&& || !`, `in [..]`, `contains`, `startsWith`, dotted paths
and indices. No calls, no assignment. `approved` is Fonebrew's legacy sugar (output starts with `APPROVE`). Outcomes
are tried by ascending `priority`; the first match wins; otherwise `defaultOrTerminalFailurePath`; otherwise the run
stops safely (`gateway gap`). A single exit needs no gateway. Static validation parses and type-checks every condition.

## Budgets and stop behaviour (§18)

Effective limits = the definition's `budgets` tightened by the per-run `budget` option
(`{ maxSteps, maxWallMs, maxTokensTotal, maxCost, maxToolCalls }`). They are checked **before dispatching each step**,
so the over-run step never starts (Kotlin behaviour; checking again "after the receipt" is equivalent because the
next thing that can consume budget is the next dispatch). Reasons: `budget:steps|wall|tokens|cost|toolCalls`.
`hardCap` (default 24) is the engine safety net that always applies. `tokens`, `cost` and `toolCalls` of 0 mean none
permitted (a model node cannot start under a 0 token budget); `wallClockSeconds` of 0 means no wall-clock limit.

## Run state machine (§9)

`CREATED > PREFLIGHT > WAITING_BINDING > WAITING_AUTHORITY > READY > RUNNING`, then `WAITING_USER` (human/wait nodes),
`SUSPENDED` (`handle.suspend()` / `resume()`, at the next step boundary), `CANCELLING` (`handle.cancel()` or an
`AbortSignal`), and the eight terminal states. Every transition is checked against the table; an illegal one throws.

| Stop | Terminal state | Category |
|---|---|---|
| terminal node reached, every required verifier passed | `SUCCEEDED_VERIFIED` | `ALL_REQUIRED_VERIFIERS_PASSED` |
| terminal node reached, otherwise | `SUCCEEDED_UNVERIFIED` (or `FAILED_SAFE` if verification is required and unverified success is not allowed) | `REQUIRED_VERIFIER_INCOMPLETE` |
| budget / step cap | `STOPPED_BUDGET` | `BUDGET_*_EXHAUSTED` |
| `authorize()` false, or executor throws `PolicyBoundaryError` | `STOPPED_POLICY` | `POLICY_BOUNDARY` |
| executor throws | the node's `onFailureTerminalState` (or `terminalStateMapping.nodeFailureEscalations`) | `NODE_FAILURE_*` |
| `SideEffectsPossibleError` / `TargetStateUnknownError` | `FAILED_SIDE_EFFECTS_POSSIBLE` / `TARGET_STATE_UNKNOWN` | `NODE_FAILURE_SIDE_EFFECTS_POSSIBLE` / `SIDE_EFFECT_OUTCOME_UNCONFIRMED` |
| cancelled, in-flight work stopped | `CANCELLED` (via `CANCELLING`) | `CANCELLATION_REQUESTED` |
| cancelled while an external-effect node could not be confirmed stopped | `TARGET_STATE_UNKNOWN` | `CANCELLATION_TARGET_UNCONFIRMED` |
| preflight problem (validation, missing params, no executor) | `FAILED_SAFE` | `VALIDATION_FAILURE` |

## Validation rules implemented

`LOOP-ID-001`, `LOOP-GRAPH-001/002/003/004`, `LOOP-SCHEMA-001/002`, `LOOP-CAP-001` (only when the host passes
`availableCapabilities`), `LOOP-BUDGET-001/002`, `LOOP-VERIFY-001/002`, `LOOP-COMPAT-001`. Findings from the JSON
Schema layer carry `code: 'SCHEMA'`. Package-level rules (`LOOP-PKG-*`, `LOOP-TEST-*`, `LOOP-LINEAGE-*`, `LOOP-ID-002`,
`LOOP-COMPAT-002`) concern packages, installs and updates, not a bare definition, and are not implemented.

## Ledger, log, replay

`handle.ledger` is a `RunLedger`: frozen entries with a dense `seq`, `append` as the only operation. `toLedgerEntries`
and `toTreeNodes` are the `GraphRunLedger` / `GraphRunLog` ports (cloud steps priced through `PricingBook`, on-device
and unresolvable models never priced). `execute()` also returns a `recording` (every node output with its usage, every
gateway decision); `replayRun(def, recording)` reproduces the run without calling any executor, and throws
`ReplayDivergenceError` if the definition's digest changed or behaviour diverges.

## What was not ported (and why)

| Deferred | Reason |
|---|---|
| BPMN 2.0 XML read/write (`BpmnArchive`), `GraphEdit`, `Distiller`, `DocumentExtract` | BPMN is Fonebrew's old notation; the v2 JSON is the canonical definition. See `MIGRATION-loop-engine.md`. |
| Loop packages, catalog, install/import presenters, templates, sync, `authoring/*` phone presenters | Package/transfer/UI-presenter layers, not the engine. |
| `RunLog` (council `RunResult` tree) | Superseded by `GraphRunLog`; needs the council types. |
| Real subloop execution (`BOUNDED_SUBLOOP`) | Validated (`LOOP-BUDGET-002`) and runnable as an executor-driven node (the host may call `LoopRunDriver` recursively); no engine-managed recursion or budget reservation (§17) yet. |
| `ESCALATE_TO_USER` on timeout, budget-extension approval, authority receipts | Need a host approval surface; timeouts treat it as `FAIL_SAFE`. |
| Binding profiles and capability grants | `resolveBinding` / `resolveAuthority` are host seams, as in Kotlin. |
| Verified-success tied to artifacts and inputs (§9) | Verifier results are tied to the run; artifact/input digests are not modelled. |

Per LOOP_WEB_STUDIO_SPEC §7 the browser is not the authoritative runtime: this engine is for simulation, tests and
host-controlled flows (Forge's design loop); a phone run remains the receipt-bearing run.
