# Spec: consuming `evidence-schema` without the JVM

Status: **DRAFT for owner review** (PORTING_PLAN.md S0.2; section 4 "evidence-schema consumed as raw
JSON"). Source: `evidence-schema` 0.1.0 on `main` (`evidence/v1/evidence-record.schema.json`,
`evidence/v1/provenance.schema.json`, JSON Schema draft 2020-12). That module is not yet on the
`fonebrew-development-clzu43` line; this note was written from `origin/main` and ships no
fixture and no test, because the plan names no test vectors for it and the schemas are themselves
the data.

## 1. What the module is

Two JSON Schema documents describing an *evidence record* and its *provenance*, versioned by
directory (`evidence/v1/`). Today they are packaged as a JVM resources jar with one string-check
test; only that **packaging** is JVM-specific. The schemas are platform-neutral data.

## 2. How a non-JVM consumer takes it

Consume the two `.schema.json` files as **raw JSON** from the shared-libraries checkout. Do not wait
for, and do not generate, a platform binding (whether to also ship generated Swift or C++ types
beside the schemas is an open owner question, PORTING_PLAN.md section 8 item 9).

| Consumer | Take |
|---|---|
| Ubuntu Touch app (QML / Python / C++) | Bundle the two files in the click; validate with a local draft-2020-12 validator. How the files reach the app is the open non-Gradle sharing question (OQ-24): this spec does not choose a mechanism |
| iOS / iPadOS app | Add the two files to the app bundle as resources |
| Desktop JVM | Either the resources jar through `includeBuild`, or the raw files |

Whatever the route, copy **both** files, at the same relative path (`evidence/v1/`), because the
record schema references the provenance schema by the relative reference `provenance.schema.json`.

## 3. Rules every consumer MUST follow

1. **Resolve `$ref` locally.** `$id` is `https://schemas.aarso.dev/evidence/v1/...`. That URL is an
   *identifier*, not a download location. A validator MUST be configured to resolve
   `provenance.schema.json` from the bundled file and MUST NOT fetch anything over the network. This
   is program rule I-1 (no phoning home), applied to a validator's default behaviour, which in
   some libraries is to dereference `$id` URLs.
2. **Treat `v1` as immutable.** A change to the record shape is a new directory (`v2/`), never an
   edit of `v1/`. A consumer pins the directory it was built against.
3. **Respect `privacyClass`.** The vocabulary is `PUBLIC`, `PERSONAL`, `PRIVATE`, `MEDICAL`. A
   record's class decides where it may be stored or shown; the consumer's storage and share paths
   are bound by it. (The schema states the vocabulary; the handling policy is the consumer's and is
   not specified here.)
4. **Keep the trust fields.** `contentHash`, `schemaVersion`, `observedAt`, `ingestedAt`, `timezone`
   and `source` are required by the schema; a consumer that drops them has stopped producing
   evidence records.

## 4. Vocabulary the schemas pin (for orientation, not a substitute for the files)

- `recordType`: `ENTITY`, `OBSERVATION`, `EVENT`, `STATE_ESTIMATE`, `CLAIM`, `RETRACTION`
- `recordClass`: `RAW`, `DERIVED`, `INFERRED`, `DECLARED`
- `resolution`: `RAW_SAMPLE`, `WINDOW`, `EVENT`, `SESSION`, `DAY`, `PERIOD`
- `evidenceGrade`: `E0` ... `E5` or null
- provenance `sourceKind`: `APP`, `DEVICE`, `IMPORT`, `PERSON`, `MODEL`

## 5. Evidence

| Claim | Class |
|---|---|
| The schemas are valid JSON and name the vocabulary above | read from `origin/main` while writing this note; the module's own test string-checks them (`CONTAINER-BUILD-ONLY` on `main`, not run on this line) |
| A given consumer validates real records against them | `NOT-APPLICABLE` here; owned by the consumer |

## 6. Named follow-ups

- Whether `platform-evidence/1` (F11) sits beside, not inside, `evidence/v1/` (PORTING_PLAN.md
  section 7, owner confirms placement).
- A conformance fixture (valid and invalid records) once the owner decides `search-testkit` or
  `evidence-schema` is the home for cross-platform conformance data.
