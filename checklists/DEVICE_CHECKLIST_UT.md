# Device checklist - Ubuntu Touch (template)

Status of this file: **TEMPLATE.** Every row below is `NEEDS-DEVICE-VALIDATION` (PORTING_PLAN.md
S0.3, F11). Nothing here has been run on an Ubuntu Touch device. A row changes only when a person has
run it on the device and recorded evidence in the **Result** and **Evidence** columns.

No Ubuntu Touch device is on record for 24.04 (OQ-1). The owner's OnePlus 6 is read as 20.04-only while the program
targets 24.04; the owner chose a labelled "S-UT1 (focal)" pre-spike on 2026-10-07 (OQ-37) and a device decision afterwards.
Until that decision every row is blocked on hardware as well as on work not yet built.

## How to fill this in

1. Copy this file into the consumer's own evidence folder (or fill it in place on a branch) and record the
   **commit under test**, the **device** (make, model, OS version) and the **date**. A checklist
   without those three is not evidence.
2. Work the rows in order. Each row has steps and an expected outcome. Record the **Result** as
   one of the words below, always the word first; the glyph is an aid, never the only signal (the
   owner is red-green colourblind, so no result is ever carried by colour alone):

   | Result | Glyph | Meaning |
   |---|---|---|
   | `PASS` | `[+]` | Ran on the device; outcome matched |
   | `FAIL` | `[x]` | Ran on the device; outcome did not match (write what happened) |
   | `BLOCKED` | `[-]` | Could not run (missing hardware, feature not built yet); say why |
   | `NEEDS-DEVICE-VALIDATION` | `[ ]` | Not run yet. This is the state of every row in the template |

3. **Evidence vocabulary** (PORTING_PLAN.md section 0): a hosted CI run is `CI (hosted VM)`; a
   simulator or emulator run is `SIMULATOR` / `EMULATOR EVIDENCE`; neither is device evidence.
   Only a run on the physical device named above turns a row to `PASS`.
4. Do not edit the *Item* or *Needs* columns to make a row easier to pass. A row that cannot be
   met as written is `BLOCKED` or `FAIL`, with the reason.

## Header to fill in

| Field | Value |
|---|---|
| Commit under test | |
| Device (make / model) | |
| OS and version | |
| Tester | |
| Date | |

## Rows

| Id | Item | Steps and expected outcome | Needs | Status | Result | Evidence / notes |
|---|---|---|---|---|---|---|
| UT-01 | Lomiri owns the left, right and top edges (ASSUMPTION carried from the repo reader, not sourced in the program's UT brief). | At S-UT1, drag from each screen edge inside a test app. Expected: records which edges the shell consumes. Decides whether the four-edge rooms pattern can be reproduced. | A UT device (OQ-1) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-02 | A QML implementation of the cell-shell motion contract reproduces specs/fixtures/cell-shell-motion.v1.json. | Run the fixture cases against the QML code on the device. Expected: every case matches within the spec's tolerances. | A UT device; a consumer QML implementation (none exists) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-03 | A QML crash-report page produces text identical to specs/fixtures/crash-report-format.v1.json (`rendered`). | Build the page from the spec, render the fixture's fully populated report. Expected: byte-identical text. | A UT device; a consumer QML page (none exists) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-04 | AppArmor confinement: the report is written under the app's own data directory and sharing goes through content-hub only. | Capture a report; share it. Expected: write succeeds in the confined location; share works through content-hub; no other path is writable. | A UT device | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-05 | QML content-hub share of a feedback draft (specs/feedback-draft.md). | Press Send feedback. Expected: the full draft is shown first; content-hub lists the user's targets; nothing is sent without the user choosing. | A UT device; a consumer QML page (none exists) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-06 | The jlink runtime from ut/ launches on the device (S-UT1). | Install a click carrying the runtime built by ut/jlink/build-runtime.sh; run `bin/java -version` through the app's launcher. Expected: it runs; glibc symbol ceiling is not exceeded. | A UT device; a click (no app identifier exists before a NAMES.md row, rule R11) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-07 | A click built for framework ubuntu-touch-24.04-1.x installs and starts. | Install the click. Expected: it installs on a 24.04 device and is refused on a 20.04 device (the framework names differ). | A 24.04 UT device (program prerequisite P4) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-08 | A real crash is recovered (PT:D-P). | In a consumer build that has wired crash-recovery, force a real uncaught failure, relaunch. Expected: the recovery surface appears with the report; Continue returns to a clean app; nothing was sent anywhere. | Owner on the device; a consumer app that wires recovery. D-P: a recovery claim 'isn't real until it's been watched recovering from an actual crash on a real device' | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| UT-09 | Colour carries no meaning alone in any new window or page (owner is red-green colourblind; PORTING_PLAN.md section 3 and section 8 item 15): every state has a word or a shape as well. | Open the window / page in each state (normal, destructive confirm, preview). Expected: each state is identifiable with colour ignored, by label or shape. | The same device; a greyscale or colour-blind simulation may help but is not a substitute for the owner's eyes (`NEEDS-OWNER-VALIDATION`) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |

## Not covered here

- Look-and-feel of any QML page: owned by the consumer.
- Performance and battery of a JVM-cored click: S-UT1 and later.
- Whether Waydroid makes native UT work moot for consumers (OQ-21).
