# Device checklist - iOS / iPadOS (template)

Status of this file: **TEMPLATE.** Every row below is `NEEDS-DEVICE-VALIDATION` (PORTING_PLAN.md
S0.3, F11). Nothing here has been run on an iPhone or iPad. A row changes only when a person has
run it on the device and recorded evidence in the **Result** and **Evidence** columns.

Device named by the plan: the iPad Pro M4 (OQ-2). Apple builds need a macOS host and Xcode (the owner's Mac mini is not
yet bought, OQ-31). A simulator run (`SIMULATOR`) is useful and is **not** device evidence. Rows depend on workstreams not
yet built (Phase 2: S2.1-S2.3); see **Needs**.

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
| IOS-01 | The unhandled-exception hook writes the common crash report under Application Support. | Force an uncaught Kotlin exception in a consumer build. Expected: a report file exists, in the shared encoding (specs/crash-report-format.md section 5). | iPad Pro M4 (OQ-2); needs S2.1; a macOS build host | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-02 | The consumer's recovery view appears on the next launch. | Relaunch after the crash above. Expected: the consumer-owned UIKit/SwiftUI view shows the report; Share/Copy work; PREVIEW safety P1-P5 hold. | iPad Pro M4; needs S2.1 and a consumer view | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-03 | Known gap, confirmed: traps, signals and jetsam kills produce no report. | Cause a Swift trap or a memory kill. Expected: no report (the hook sees uncaught Kotlin exceptions only). Records the gap; it is accepted or not by the owner (PORTING_PLAN.md section 8 item 4). | iPad Pro M4 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-04 | CoreMotion shake triggers refresh as on Android (peak-train rule). | Shake the device in a cell-shell host. Expected: refresh fires on a deliberate shake, not on handling. | iPad Pro M4; needs S2.1 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-05 | cell-shell four-edge drag against iOS system gestures. | Drag from the left edge and the bottom edge. Expected: records whether the system gestures compete with the LEFT and BOTTOM rooms and whether edge-deferral works. | iPad Pro M4; needs S1.3 and S2.1 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-06 | feedback: the share sheet carries the draft; the opt-in ledger persists in NSUserDefaults. | Press Send feedback. Expected: the full draft is shown first; the system share sheet receives exactly that text; opt-in survives relaunch; nothing leaves the device unless the user chooses a target. | iPad Pro M4; needs S2.1 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-07 | A real crash is recovered (PT:D-P). | In a consumer build that has wired crash-recovery, force a real uncaught failure, relaunch. Expected: the recovery surface appears with the report; Continue returns to a clean app; nothing was sent anywhere. | Owner on the device; a consumer app that wires recovery. D-P: a recovery claim 'isn't real until it's been watched recovering from an actual crash on a real device' | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| IOS-08 | Colour carries no meaning alone in any new window or page (owner is red-green colourblind; PORTING_PLAN.md section 3 and section 8 item 15): every state has a word or a shape as well. | Open the window / page in each state (normal, destructive confirm, preview). Expected: each state is identifiable with colour ignored, by label or shape. | The same device; a greyscale or colour-blind simulation may help but is not a substitute for the owner's eyes (`NEEDS-OWNER-VALIDATION`) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |

## Not covered here

- App Store signing and submission: owner-held (OQ-31).
- `search-core` behaviour on the simulator: `SIMULATOR` evidence, not a device row.
