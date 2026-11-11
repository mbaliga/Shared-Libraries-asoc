# evidence-schema

Versioned, portable JSON Schemas for evidence exchange across the constellation, plus a small dependency-free JVM validator.
Pure JVM and resources: no Android, no network, no telemetry.

| Schema | File | What it is |
|---|---|---|
| Evidence record, provenance | `evidence/v1/evidence-record.schema.json`, `provenance.schema.json` | Baseline / Crocodyl / Ebbflow evidence and event exchange. |
| **`platform-evidence/1`** | `evidence/v1/platform-evidence.schema.json` | One row of a per-platform device checklist, or one piece of build/test evidence about a platform claim (porting program foundation item F11). |

## platform-evidence/1

The `label` field is an evidence label from `PORTING_PROGRAM.md` section 2, written verbatim (`CI (hosted VM) evidence`,
`NEEDS-DEVICE-VALIDATION`, `CI-APPROX — NOT DEVICE EVIDENCE` as the real em dash, and so on; abbreviations such as `NDV` are
rejected) or `NOT-APPLICABLE (<reason>)` with a `note`. `item` is a checklist row id (`DV-AD-01`) from
`docs/device-checklists/`. `outcome` is one of:

- `open`: nothing run. Label must be `NEEDS-DEVICE-VALIDATION`, `NEEDS-OWNER-VALIDATION`, `PLAN` or `CI-ONLY / NOT RUN`.
- `recorded`: something ran and `output` is pasted, but it does **not** close the row. This is the only result a hosted runner,
  emulator, simulator, VM, container or headless browser can produce.
- `pass` / `fail`: reserved for the owner on a real device. Requires `recordedBy: "owner"`, `device`, `command` and `output`, and
  the label must be `NEEDS-DEVICE-VALIDATION` or `NEEDS-OWNER-VALIDATION`.

That reading of `pass`/`fail` is this schema's rule, derived from directive I-4 (environment honesty); section 2 does not define a
"verified" label and none is invented here. Additional properties are rejected, so a record cannot grow a telemetry endpoint.

## Validator

`dev.aarso.evidence.PlatformEvidenceValidator.validate(json): List<String>` returns violations (empty means valid). It implements
only the JSON Schema keywords this schema uses and throws on an unsupported keyword in the schema, rather than ignoring it.
It checks data shape only; it cannot tell whether any claim is true.

```bash
./gradlew :evidence-schema:test
```

`PlatformEvidenceTest` also checks that the schema's label list equals section 2 verbatim, and that every
`docs/device-checklists/DEVICE_CHECKLIST_*.md` exists, has unique sequential row ids, and has every row at `NEEDS-DEVICE-VALIDATION`.

## Evidence for this module

`CONTAINER-BUILD-ONLY`: the tests above run on a JVM in a container. Nothing was run on a phone, a tablet, a Mac, a Windows PC or
a Linux desktop; the device checklists are templates and every row is open.
