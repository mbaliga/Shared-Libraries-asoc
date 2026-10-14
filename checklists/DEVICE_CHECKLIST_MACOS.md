# Device checklist - macOS (template)

Status of this file: **TEMPLATE.** Every row below is `NEEDS-DEVICE-VALIDATION` (PORTING_PLAN.md
S0.3, F11). Nothing here has been run on a Mac. A row changes only when a person has
run it on the device and recorded evidence in the **Result** and **Evidence** columns.

**No Mac is on record** (OQ-5, OQ-31): the owner chose an Apple-silicon Mac mini on 2026-10-07 and has not bought it. Until
then no row here is checkable, and every row remains `NEEDS-DEVICE-VALIDATION` (device checks are `NEEDS-OWNER-VALIDATION`
in the plan). Hosted `macos-latest` CI shows only that the JVM tests pass and a window constructs (`CI (hosted VM)`).

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
| MAC-01 | Swing recovery window renders: title, headline, plain-language summary, collapsed stack trace. | Trigger the preview. Expected: all four parts visible and legible; trace collapsed until opened. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 (not built yet) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-02 | Copy places the shared text on the clipboard, identical to what the window shows. | Press Copy, paste into a text editor. Expected: the paste equals the displayed report word for word. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-03 | Share / mail: opens the system mail handler, else falls back to the clipboard; a payload too long for the mail link goes to clipboard or a saved file and the window says so. | Press Share with and without a registered mail client; then with a very long trace. Expected: never silently truncated. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-04 | Reset asks for confirmation before doing anything. | Press Reset. Expected: a confirm step; cancelling changes nothing. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-05 | Continue closes the window and relaunches a clean instance (real crash only). | Recover from a real crash, press Continue. Expected: the app relaunches. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-06 | PREVIEW safety P1-P5 hold in the window (specs/crash-report-format.md section 8). | Open the preview. Expected: PREVIEW is visible in headline, summary and shared text (P1); no real device data (P2); Reset does nothing (P3); Continue only closes the window (P4); no real pending report is consumed (P5). | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-07 | HiDPI: the window is sharp and correctly sized at the display's scale factor. | Open the window at 100% and at the display's native scale. Expected: no blur, no clipped text. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-08 | Headless fallback: with no display, the report path is printed to stderr. | Run with no display available. Expected: a line on stderr naming the saved report file; no crash of the handler. | An Apple-silicon Mac (OQ-31; not yet bought); needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-09 | The app data directory resolves to the per-user Application Support location. | Capture a report. Expected: the file appears under the user's Application Support area. | An Apple-silicon Mac; needs S1.2 | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-10 | cell-shell: the four-edge rooms feel on a resizable mouse window (open UX question, PORTING_PLAN.md section 8 item 3). | Drag from each edge with the mouse; resize the window. Expected: records the owner's verdict on whether the pattern is followed verbatim or adapted (dock and rail was Clavis's choice). | A desktop session; needs S1.3 (not built); `NEEDS-OWNER-VALIDATION` for the verdict | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-11 | feedback delivery: mail through the default handler, then clipboard. | Press Send feedback. Expected: the draft is shown in full first; delivery goes through the user's chosen channel; nothing leaves the device unless the user launches it. | needs S1.1 (not built) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-12 | A real crash is recovered (PT:D-P). | In a consumer build that has wired crash-recovery, force a real uncaught failure, relaunch. Expected: the recovery surface appears with the report; Continue returns to a clean app; nothing was sent anywhere. | Owner on the device; a consumer app that wires recovery. D-P: a recovery claim 'isn't real until it's been watched recovering from an actual crash on a real device' | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |
| MAC-13 | Colour carries no meaning alone in any new window or page (owner is red-green colourblind; PORTING_PLAN.md section 3 and section 8 item 15): every state has a word or a shape as well. | Open the window / page in each state (normal, destructive confirm, preview). Expected: each state is identifiable with colour ignored, by label or shape. | The same device; a greyscale or colour-blind simulation may help but is not a substitute for the owner's eyes (`NEEDS-OWNER-VALIDATION`) | `NEEDS-DEVICE-VALIDATION` | `[ ]` | |

## Not covered here

- Signing and notarisation: consumer-owned.
