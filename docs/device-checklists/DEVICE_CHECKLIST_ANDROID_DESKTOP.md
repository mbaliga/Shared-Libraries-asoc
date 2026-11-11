# Device checklist template: Android on a desktop-class surface

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

Devices: the Android phone with an external display in desktop windowing mode, an Android tablet with a hardware keyboard and mouse, and any desktop-class Android surface the owner has (DeX-style mode, Android desktop windowing, ChromeOS-style resizable windows). It covers the Android app when it runs as a resizable window with keyboard and mouse; it is not a separate port. Emulator desktop profiles are `EMULATOR EVIDENCE` and never close a row. Android runs the app already (so these are checks of behaviour, not of installation); there is no Android SDK or device in the builder's container, so every row is open. Commands via `adb shell` unless marked.

## Resize and freeform windowing

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-AD-01 | Resizable activity declared | `adb shell dumpsys package <package> \| grep -i resiz`; `adb shell dumpsys activity activities \| grep -i resizeMode`. | The main activity is resizable (not `resizeableActivity=false`); no forced letterbox. | NEEDS-DEVICE-VALIDATION |
| DV-AD-02 | Drag-resize across width classes | In freeform mode, drag the window edge from compact through medium to expanded and back. | Layout switches at the declared size classes without a restart; no clipped controls; no blank frame. | NEEDS-DEVICE-VALIDATION |
| DV-AD-03 | State survives configuration change on resize | Type in a field, scroll, open a dialog; resize; rotate the display. | Text, scroll position, dialog and selection are kept; no task restarts, no data lost. | NEEDS-DEVICE-VALIDATION |
| DV-AD-04 | Minimum size | Shrink to the smallest the system allows. | Honours the declared minimum or reflows; nothing unreachable. | NEEDS-DEVICE-VALIDATION |
| DV-AD-05 | Move, snap, maximise, minimise, close | Use the window caption/title bar buttons; snap left/right; maximise; minimise and restore. | State kept in each; the caption bar does not cover content (insets honoured). | NEEDS-DEVICE-VALIDATION |
| DV-AD-06 | Multiple windows / instances | Launch a second window of `<app>` (if the README allows). | Either two independent windows work, or the second is refused with a plain message; no shared-state corruption. | NEEDS-DEVICE-VALIDATION |
| DV-AD-07 | Display connect / disconnect and density change | Plug and unplug an external display mid-session; change display density. | The app moves or reflows without crashing; state kept. | NEEDS-DEVICE-VALIDATION |
| DV-AD-08 | Orientation requests ignored in a window | Rotate the host display with the app windowed. | No forced portrait lock inside a landscape window; layout adapts. | NEEDS-DEVICE-VALIDATION |
| DV-AD-09 | Window title and task identity | Check the title/caption and the recents entry. | Shows the app's name and the open document where applicable. | NEEDS-DEVICE-VALIDATION |

## Mouse and trackpad

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-AD-10 | Hover states | Move the pointer across every interactive element. | Each shows a visible hover state that is not colour alone (outline, underline or shape change); tooltips appear where documented. | NEEDS-DEVICE-VALIDATION |
| DV-AD-11 | Right-click context menu | Right-click items, text and the canvas. | A context menu appears with the same actions as long-press; Escape dismisses it. | NEEDS-DEVICE-VALIDATION |
| DV-AD-12 | Scroll wheel and two-finger scroll | Wheel over lists, text and the canvas; Shift+wheel for horizontal. | Scrolls at a sane speed in the right direction; no scroll-through to a layer beneath. | NEEDS-DEVICE-VALIDATION |
| DV-AD-13 | Ctrl + wheel / pinch zoom (if the app zooms) | Ctrl+wheel and trackpad pinch over the zoomable surface. | Zooms around the pointer; limits honoured. | NEEDS-DEVICE-VALIDATION |
| DV-AD-14 | Click, double-click, drag, middle-click | Select by drag; double-click to edit/open; middle-click where documented. | Behaviour matches the README; drag-select text works; no accidental long-press on a held click. | NEEDS-DEVICE-VALIDATION |
| DV-AD-15 | Pointer cursor shapes | Hover text, links, resize handles, drag handles. | I-beam, hand and resize cursors appear where expected. | NEEDS-DEVICE-VALIDATION |
| DV-AD-16 | Drag and drop files | Drag a file from the system file manager onto the window and out of it. | Imports/exports as documented; refused types are refused with a message. | NEEDS-DEVICE-VALIDATION |

## Keyboard, focus and shortcuts

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-AD-17 | Tab order and visible focus ring | Press Tab and Shift+Tab through every screen. | Order follows reading order; the focused element has a visible ring by shape/outline, not colour alone; no focus trap. | NEEDS-DEVICE-VALIDATION |
| DV-AD-18 | Activate and navigate from the keyboard | Enter and Space activate buttons; arrow keys move in lists, tabs and menus. | Every action reachable by mouse is reachable by keyboard. | NEEDS-DEVICE-VALIDATION |
| DV-AD-19 | Escape and back | Esc closes the innermost dialog, menu or sheet; Alt+Left / back works as documented. | Predictable; never discards unsaved work silently. | NEEDS-DEVICE-VALIDATION |
| DV-AD-20 | Documented shortcuts | Ctrl+K (palette), Ctrl+C/V/X/Z/Y, Ctrl+S, Ctrl+F, Ctrl+W, plus any the README lists. | Each works; none collides with a system shortcut without a note. | NEEDS-DEVICE-VALIDATION |
| DV-AD-21 | Alt+Tab and window focus restore | Switch away and back; resize then Alt+Tab. | Focus returns to the element that had it; text caret and selection kept. | NEEDS-DEVICE-VALIDATION |
| DV-AD-22 | Hardware keyboard and the on-screen keyboard | Focus a text field with a physical keyboard attached. | The soft keyboard does not cover the field or steal focus; IME composition (e.g. a non-Latin layout) works. | NEEDS-DEVICE-VALIDATION |
| DV-AD-23 | Text fields: selection and clipboard | Select with Shift+arrows, Ctrl+A, copy and paste across windows and apps. | Selection and clipboard work; paste of rich text is plain unless documented. | NEEDS-DEVICE-VALIDATION |
| DV-AD-24 | Screen reader with keyboard | TalkBack with a keyboard: navigate the main flow. | Every control has a label; status is announced in words, not colour. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-AD-25 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on Android in a desktop-class window; launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-AD-26 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-AD-27 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-AD-28 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-AD-29 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-AD-30 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-AD-31 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Phone-only touch behaviour: covered by the app's own device checklist.
- Emulator desktop profiles: `EMULATOR EVIDENCE`, never closing a row.
