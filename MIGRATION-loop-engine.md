# Moving Fonebrew's `domain/loop` into a pure-JVM `:loop-engine` module here

Status: plan only. Nothing in `mbaliga/android-ide-core` was touched; the web port in `web/loop-engine/` is the
reference for the shape. Source: `core-engine/src/main/java/dev/fonebrew/domain/loop/` at commit
`f23f6e491f449fc8acf3ee77adc0d44916dde945`.

## Target

`:loop-engine` (`com.asystemofcells:loop-engine`), plain `kotlin("jvm")` like `:search-core`: no Android, no org.json, no
storage; `kotlinx-coroutines-core` is the one allowed dependency (the runner is `suspend`). Consumers include it with
`includeBuild("shared-libraries")` as for every other module here. Its tests are the existing Kotlin tests, unchanged
in meaning (the web port's tests are a line-for-line port of them).

## What moves, what stays

| Moves into `:loop-engine` | Why it is portable |
|---|---|
| `GraphRunner`, `GraphStep`, `GraphRunResult`, `StepTokens`, `GatewayPolicy`, `ConditionGatewayPolicy` | Pure orchestration over a graph and a `Generator`. |
| `LoopBudget`, `LoopParams`, `RecordingGatewayPolicy` | No imports beyond the graph types. |
| `LoopRunDriver` | Needs the contracts below. |
| `GraphRunLog`, `GraphRunLedger` | Need `MessageNode`, the ledger and the pricing types below. |
| `LoopConfig` / `LoopConfigBpmn` | Over the graph types only. |
| `Loop` / `LoopLifecycle` | No dependencies at all. |

Stays in Fonebrew (app / data / UI concerns): `LoopCatalog*`, `LoopTemplateCatalog`, `LoopPackageCodec`,
`LoopImportPresenter`, `LoopInstallationDriver`, `LoopEmptyStatePresenter`, `Distiller`, `DocumentExtract`, `RunLog`
(council types), `authoring/*` (phone presenters), `GraphEdit` (editing, over the same graph types — move with the
graph if the editor is shared). `LoopStore`, `LoopSyncRepo` and all `ui/loops/*` stay put.

## Dependencies to cut

`domain/loop` reaches into four neighbours. Each has a one-way cut:

### 1. `domain/bpmn` (`BpmnGraph`, `BpmnNode`, `BpmnEdge`, `BpmnNodeKind`, `Bounds`) — used by 8 files

Two options; take the first.

1. **Move `BpmnGraph.kt` into `:loop-engine`** as `com.asystemofcells.loop.graph` (it is pure data classes, no imports). Leave
   `BpmnArchive` (XML, `org.w3c.dom`) in Fonebrew and have it depend on the module. Fonebrew keeps a typealias file in
   `dev.fonebrew.domain.bpmn` so call sites compile unchanged.
2. **Retarget the engine to the v2 `LoopDefinition`** (the web port's model: nodes with eight categories, `edges`,
   first-class gateways). Then `GraphRunner` no longer needs BPMN at all, and BPMN becomes an import/export adapter
   (`BpmnArchive` -> `LoopDefinition`). This is the better end state and is what `web/loop-engine` already does; do it
   after step 1 so the move itself stays mechanical.

### 2. `domain/council` (`Generator`, `Expert`, `RunResult`, `PatternLibrary`, `ReferencePattern`)

* `GraphRunner` needs only `Generator`, a one-method `fun interface` (`suspend fun complete(system, user): String`).
  **Move that interface** into `:loop-engine` and make `domain.council.Generator` a typealias. In the v2 model it
  generalises to the web port's executor `(node, input, ctx) -> output`.
* `Expert` / `RunResult` are used by `RunLog` (canonical proposer/critic log) and `PatternLibrary` by `LoopCatalog`;
  both stay in Fonebrew. Nothing in the moving set references them.

### 3. `contracts` (`dev.fonebrew.contracts.loops.*`, `contracts.common.*`) — only `LoopRunDriver`

`LoopRunDriver` is the only mover that touches contracts: `LoopRun`, `RunState`, `RunEvent`, `NodeAttempt`,
`NodeAttemptOutcome`, `RunOutputs`, `TerminalReason`, `TerminalReasonCategory`, `BaseRevisionRef`, `DurableObjectRef`.
All come from `contracts/kotlin/LoopRuntimeContracts.kt` (+ `LoopAuthoringContracts.kt`, `LoopActivationContracts.kt`
for the two refs). That file is stdlib + `java.time` only by its own rule. **Move the runtime contracts file as a unit**
into `:loop-engine` (package `com.asystemofcells.loop.contracts`), with the two ref types and `ArtifactRef`/`SideEffectState`
from `CommonContracts.kt` that it imports; keep the Fonebrew packages as typealiases. The other loop contracts
(package, marketplace, compatibility, lineage) are not needed by the engine and stay.

### 4. `domain/contracts` (`Digest`, `IdGenerator`), `domain/ledger`, `domain/cost`, `domain/MessageNode`, `domain/Role`

* `Digest.ofUtf8` and `IdGenerator.generate` (used by `LoopRunDriver`): inject them. `LoopRunDriver` already takes
  `idGenerator` and `now`; add `digest: (String) -> String` the same way (default a 6-line SHA-256 in the module).
  The web port uses semantic digest over canonical JSON instead of `graph.toString()`, which is also the fix for
  `definitionDigest` not being stable in Kotlin today (it hashes `BpmnGraph.toString()`).
* `GraphRunLedger` needs `LedgerEntry`, `LedgerCapture.loopStep`, `Tier`, `PricingBook`, `UsagePricing`, `UsageReport`.
  Do not move the ledger/cost packages. Instead split `GraphRunLedger` in two: the engine keeps a neutral
  `StepUsage` projection (model, tier, tokens, latency, estimated), and Fonebrew keeps a one-file adapter that maps it to
  `LedgerEntry` and prices it. (The web port ships its own `PricingBook` because it has no ledger package to reuse.)
* `GraphRunLog` needs `MessageNode` and `Role`. Same treatment: the engine emits a neutral node record; Fonebrew's
  adapter builds `MessageNode`. Alternatively move `MessageNode` + `Role` (two plain data types) if more consumers want them.

## Steps

1. Create `:loop-engine` (`kotlin("jvm")`, `jvmToolchain(17)`, coroutines dep, `com.asystemofcells:loop-engine:0.1.0`) and add it to
   `settings.gradle.kts` and the README table. Copy the tests with the code.
2. Move the graph types, `Generator`, `LoopBudget`, `LoopParams`, `GatewayPolicy` family, `GraphRunner`; add typealiases
   in Fonebrew. Run Fonebrew's loop tests against the included build: they must pass unchanged.
3. Move the runtime contracts file and `LoopRunDriver` with the injected `digest`.
4. Split `GraphRunLedger` / `GraphRunLog` as described; keep the Fonebrew adapters.
5. Bump the Fonebrew submodule pin, delete the moved files, keep typealiases for one release, then remove them.
6. Only then retarget the runner to `LoopDefinition` v2 (option 2 above), adding the web port's gateway model, verifier
   evaluation and replay, so the two engines stay behaviourally identical (the web tests are the cross-check; the
   Kotlin side should add a shared JSON fixture suite under `fixtures/loops/` that both run).

## Behaviour the two engines should agree on (differences today)

These are deliberate in the web port and should be adopted on the Kotlin side when it moves to v2:

* Static validation runs at `PREFLIGHT` (Kotlin only checks params).
* Terminal success evaluates required verifiers: `SUCCEEDED_VERIFIED` is reachable (Kotlin always returns
  `SUCCEEDED_UNVERIFIED`, flagged in its own comments).
* `WAITING_USER`, `SUSPENDED` and `TARGET_STATE_UNKNOWN`-on-unconfirmed-cancel are driven live; the Kotlin driver
  reconstructs the state log after the run.
* Budgets include cost and tool calls; `LoopBudget` has only tokens, steps and wall-clock.
* Gateways are first-class with priorities, typed conditions and an explicit default path.
