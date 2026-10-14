# specs/ - reusable specifications with golden fixtures

PORTING_PLAN.md S0.2. This directory is the sanctioned **Ubuntu Touch contribution of this repo**
(PD-4: spec-only reuse; no Kotlin runs in a Click app) and is equally useful to a Swift or any other
port. Each spec describes behaviour that must not drift between implementations, and ships
**golden fixtures** taken from the pure functions' existing test vectors, so an implementation in
another language can prove conformance against the same cases the Kotlin build is held to.

**Status: DRAFT for owner review.** The plan's done-when for S0.2 is "specs reviewed by the owner;
tests green". The tests are green (see below); the owner review has not happened.

| Spec | Describes | Fixture | Guarded by |
|---|---|---|---|
| [`cell-shell-motion.md`](cell-shell-motion.md) | Settle decision, parking geometry, scrubber index mapping, motion constants | [`fixtures/cell-shell-motion.v1.json`](fixtures/cell-shell-motion.v1.json) | `cell-shell/.../SpecFixturesTest.kt` (runs in `:cell-shell:test`) |
| [`crash-report-format.md`](crash-report-format.md) | Report data model, shared text, v2 encoding, plain-language mapping, streaks, exit-death reports, the five PREVIEW safety properties | [`fixtures/crash-report-format.v1.json`](fixtures/crash-report-format.v1.json) | `crash-recovery/.../SpecFixturesTest.kt` (runs in `:crash-recovery:test`) |
| [`feedback-draft.md`](feedback-draft.md) | Draft render and subject; the no-hidden-data property | [`fixtures/feedback-draft.v1.json`](fixtures/feedback-draft.v1.json) | `feedback/.../SpecFixturesTest.kt` - **inert on this branch** (see below) |
| [`evidence-schema-consumption.md`](evidence-schema-consumption.md) | Consuming the two JSON Schemas as raw JSON off the JVM | none (the schemas are the data) | nothing to guard |

## How the fixtures guard drift

The fixtures are read by new test classes in each module's **existing** test source set, so the
existing `ci.yml` steps (`:cell-shell:test`, `:crash-recovery:test`) run them with no edit to any
build or CI file. A test fails if a case no longer produces its recorded `expect`, if the fixture has
fewer cases than the suite requires (removing a vector is a contract change), or if the fixture's
`spec`/`version` header is wrong. (Mutation-checked: changing a recorded value in either fixture
fails the matching tests; see "Verification" below.)

**Caveat - Gradle caching can hide a fixture-only change.** The fixtures live in `specs/fixtures/`,
outside every module, and Phase 0 may not touch the build files that could declare them as test
inputs. So a change to a fixture *alone* can leave a module's test task `UP-TO-DATE` or `FROM-CACHE`
(this repo turns the build cache on), and the existing `ci.yml` steps would then pass without
re-checking it. This was found, not assumed: the first mutation run of this work passed because of
it. The guard for that is the `spec-fixtures` job in `.github/workflows/platform-kits.yml`, which runs
the spec tests with `--no-build-cache --rerun`. Locally, always run them as shown below.

The tests read the fixture by walking up from the Gradle working directory to `specs/fixtures/`, and
parse it with a small test-only JSON reader (`SpecJson.kt`). That reader is deliberately a copy in
each module (Phase 0 may not touch module build files, and Android unit tests cannot use
`org.json`); [`check-json-helper-sync.sh`](check-json-helper-sync.sh) keeps the copies identical
(`--self-test` exercises the check itself).

## Running the guards locally

```bash
./gradlew --no-daemon --no-build-cache \
  :cell-shell:testDebugUnitTest --rerun --tests '*SpecFixturesTest*' \
  :crash-recovery:testDebugUnitTest --rerun --tests '*SpecFixturesTest*'
```

## Regenerating a fixture (explicit opt-in only)

A fixture is a contract, not scratch output. A changed `expect` means the behaviour users see (or the
way every app moves) has changed, and that is an owner decision. The only sanctioned way to rewrite
one is:

```bash
SPEC_FIXTURES_REGENERATE=1 ./gradlew --no-daemon --no-build-cache :cell-shell:testDebugUnitTest --rerun --tests '*SpecFixturesTest*'
git diff specs/fixtures        # review every changed value
./gradlew --no-daemon --no-build-cache :cell-shell:testDebugUnitTest --rerun --tests '*SpecFixturesTest*'   # must pass WITHOUT the switch
```

With the switch on, the test **rewrites the file from the code's current behaviour and then fails
on purpose**, so a CI run with the switch left on can never go green and a regenerated file cannot be
committed unreviewed. Inputs are never regenerated, only the recorded outputs. (`--rerun` is needed
because the switch is an environment variable, which Gradle does not treat as a task input.)

## Verification done (2026-10-14, planning container)

- Each guard passes against hand-derived expectations written *before* any regeneration; then a
  regeneration from the code's actual behaviour produced **semantically identical** files in all three
  cases (an independent cross-check of the hand derivation) and is byte-idempotent. The committed
  files are the regenerated, canonical form so a future regeneration diff shows only changed values.
- Mutation check: changing five recorded values in the cell-shell fixture and six in the
  crash-report fixture made exactly the matching tests fail (5 of 8 and 6 of 10), and restoring them
  made everything pass again.
- `feedback`: verified green (4 tests) in a scratch copy of `origin/main`; regeneration there is also
  semantically identical.
- Not verified: any non-Kotlin implementation, and any behaviour on a device.

## Known state of this branch

- **`feedback` is not on this branch.** The `:feedback` and `:evidence-schema` modules exist on
  `main`, which has diverged from `fonebrew-development-clzu43`. The feedback spec and fixture
  are complete and the guard test is checked in at its final path
  (`feedback/src/test/kotlin/dev/aarso/feedback/SpecFixturesTest.kt`), but with no
  `feedback/build.gradle.kts` here Gradle never compiles it, so it is **inert until the lines
  merge**. It was verified green against a scratch copy of `origin/main` (not committed anywhere).
  It cannot collide with `main`: it adds files `main` does not have.
- **`evidence-schema` has no fixture** - the plan names none, and the schemas are themselves data.
- **Not yet specced** (named follow-ups in each spec): `WordWheelRail` and `ShakePeakTrain` vectors,
  `item_center_y`, the quarantine/salvage semantics, platform delivery channels.
- **Owner review** of all four documents is outstanding, as are the open questions they cite
  (PORTING_PLAN.md section 8 items 3, 4, 5, 9).

## Evidence labels

The fixtures prove that the **Kotlin implementation** reproduces them (`CONTAINER-BUILD-ONLY`: JVM
unit tests). Whether a QML, Swift or other implementation conforms is `NOT-APPLICABLE` in this repo
and is `NEEDS-DEVICE-VALIDATION` on a device, owned by the consumer. Nothing here is device evidence.
