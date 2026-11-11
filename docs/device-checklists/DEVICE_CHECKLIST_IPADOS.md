# Device checklist template: iPadOS (iPad Pro M4)

> TEMPLATE. Copy this file into the app repo's `docs/`, replace every `<app>`, `<id>` and `<ver>` placeholder, delete rows the app
> does not have (keep the ids of the rows you keep), and add app-specific rows with the next free id. Seeded from asystemofmodels'
> own device checklists; the program is `PORTING_PROGRAM.md` (Personal-Tracker), foundation item F11.

**Every row below is `NEEDS-DEVICE-VALIDATION` until the owner pastes real output.** Nothing here was run on any device by the
builder of this template. Container, emulator, simulator, VM and hosted-runner results (`CI (hosted VM) evidence`,
`EMULATOR EVIDENCE`, `SIMULATOR`, `VIRTUALIZED — NOT DEVICE EVIDENCE`, ...) never close a row. The "Expect" column is what a pass
looks like, not a result. A row that names an `<app>` command or screen that does not exist yet stays open and says so.

How to record a row: paste the command, the full output, the device (model, OS and build) and the date next to the row, then change
the Status cell. Leave a failing or surprising result in place; a fix that hides it is worse than the result. For a machine-readable
copy, emit a `platform-evidence/1` record (`evidence-schema/src/main/resources/evidence/v1/platform-evidence.schema.json`) whose
`item` is the row id. `pass` and `fail` outcomes are accepted only from the owner with the label `NEEDS-DEVICE-VALIDATION` or
`NEEDS-OWNER-VALIDATION`.

Directives these rows check: no telemetry and no phoning home (I-1); keys held in the strongest tier the host offers, with the UI
reporting the tier (I-2); colour never carries meaning alone (I-3); nothing claimed that was not run (I-4); plain files that open
in native tools (I-5).

Device: the owner's iPad Pro M4 (the only Apple device on record; confirm in OQ-2). iPadOS is its own column (PORTING_PROGRAM.md section 5A.12). Run the shared iOS rows first (`DEVICE_CHECKLIST_IOS.md`); this file adds only what an iPad changes: windowing, keyboard, pointer, and the background-GPU entitlement. Every row is open.

## Windowing and layout

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IPAD-01 | Split View and Slide Over | Open `<app>` beside another app, then in Slide Over; drag the divider through every width class. | Layout reflows at each size class without a relaunch; state is kept; no clipped controls. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-02 | Stage Manager resize | Enable Stage Manager; resize the window freely, then to its minimum. | Honours the declared minimum size; content reflows; no stuck layout after resize. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-03 | Multiple windows | Open a second `<app>` window (if the README allows it) and edit in both. | Either two independent scenes work, or the second is refused with a plain message; no data corruption. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-04 | External display | Connect a display (mirror and extended). | Recorded as found; the app does not crash on connect or disconnect. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-05 | Orientation and rotation | Rotate in each of the four orientations during a task. | State survives; layout correct. | NEEDS-DEVICE-VALIDATION |

## Keyboard, pointer, Pencil

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IPAD-06 | Hardware keyboard: Tab and focus ring | Magic/Smart Keyboard: Tab through every control; hold Cmd for the shortcut overlay. | Focus is visible by shape (ring/outline), not colour alone; the README's shortcuts appear in the overlay and work. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-07 | Keyboard shortcuts | Cmd+K (palette), Cmd+C/V/Z, Esc to dismiss, arrow-key navigation. | Each documented shortcut works; Esc closes the innermost layer. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-08 | Pointer hover and right-click | Trackpad: hover each control; two-finger click for context menus; two-finger scroll. | Hover states are visible and not colour-only; context menu appears; scroll is smooth. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-09 | Apple Pencil (if drawing is a feature) | Draw with Pencil; check pressure/tilt, palm rejection, hover (M-series). | Strokes follow the Pencil; palm rejection works; recorded. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-10 | Drag and drop | Drag a file in from Files and out to Files. | Imports/exports; refused types are refused with a message. | NEEDS-DEVICE-VALIDATION |

## Background and power (iPad-specific)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IPAD-11 | Background GPU entitlement | Check whether `com.apple.developer.background-tasks.continued-processing.gpu` (or the entitlement in force) is honoured on this iPad; run GPU work then background the app. | Recorded as found; reported false on iPhone 15/16 Pro and true on iPads by developer reports only. The app does not rely on it unless this row passes. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-12 | Thermal on a sustained task | Run a long foreground task on battery and on charge. | No throttling cliff; the app backs off first. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-13 | "Designed for iPad" on Apple-silicon Mac | If a Mac exists: install the iPad build from the Mac App Store/TestFlight and open it. | Opens and is usable with pointer and keyboard; NOT TESTABLE until the Mac exists (OQ-31). Record the macOS version. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IPAD-14 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on iPadOS; launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-15 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-16 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-17 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-18 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-19 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-IPAD-20 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Signing, TestFlight and App Store steps: nothing here is signed or released.
- Simulator-only results: `SIMULATOR`, never closing.
