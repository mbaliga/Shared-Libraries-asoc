# Schema and fixture provenance

Copied verbatim, unmodified, from the Fonebrew repo `mbaliga/android-ide-core` at commit
`f23f6e491f449fc8acf3ee77adc0d44916dde945` (read-only source of truth for loop semantics).

| Here | Source path |
|---|---|
| `loop-definition.v2.schema.json` | `schemas/loops/loop-definition.v2.schema.json` |
| `loop-run.schema.json` | `schemas/loops/loop-run.schema.json` |
| `registries/loop-validation-rules.v1.json` | `schemas/loops/registries/loop-validation-rules.v1.json` |
| `registries/semantic-digest.v1.json` | `schemas/loops/registries/semantic-digest.v1.json` |
| `registries/canonicalization.v1.json` | `schemas/loops/registries/canonicalization.v1.json` |
| `fixtures/loop-definition.v2/**` | `fixtures/loops/loop-definition.v2/**` |
| `fixtures/loop-run/**` | `fixtures/loops/loop-run/**` |

These files are `LICENSE-PENDING` upstream (see Fonebrew `docs/non_ratified/LICENSE_PENDING.md`). Do not edit them
here; re-copy from the source when the source changes and update the commit above.
The engine loads them through `web/loop-engine/schemas.js`, which embeds the two schemas and the rule registry so the
browser needs no fetch.
