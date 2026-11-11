# Device checklist template: macOS

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

Device: an Apple-silicon Mac on macOS 15 or later (the owner intends to buy one; OQ-31, not yet bought). Intel is best-effort until September 2027. Until a Mac exists every row is open and hosted-runner results are `CI (hosted VM) evidence` only. Source: asystemofmodels `desktop/packaging/macos/docs/DEVICE_CHECKLIST_MACOS.md` and PORTING_PROGRAM.md section 4.4. Commands run in an ordinary Terminal unless marked.

## Install, Gatekeeper and signing

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-MAC-01 | UNSIGNED build behaviour under Gatekeeper | Download the `.dmg`, drag to Applications, open. | Gatekeeper refuses or warns as expected for an UNSIGNED build; the README's bypass steps (if any) work and are honest about the risk. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-02 | Developer-ID signed and notarised build (once a job exists) | `spctl --assess --type execute -vv /Applications/<app>.app`; `stapler validate`. | Accepted, source Notarized Developer ID. NOT TESTABLE until the signing job exists (OQ-3). | NEEDS-DEVICE-VALIDATION |
| DV-MAC-03 | Hardened runtime and entitlements | `codesign -d --entitlements - /Applications/<app>.app` | Only the entitlements the README lists. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-04 | Drag-install and remove | Delete the app; list `~/Library/Application Support/<app>` and Login Items. | No process, login item or LaunchAgent left behind unless the README says so. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-05 | Apple silicon and Intel | `file` the main binary; run natively on arm64. | arm64 native; Intel recorded as found or not tested. | NEEDS-DEVICE-VALIDATION |

## Keys, privacy and services

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-MAC-06 | Keychain tier | Save a test key; inspect in Keychain Access. | Stored in the login keychain with the access control the README states; the UI names the tier. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-07 | Secure Enclave key (if used) | Create, persist and sign with an SE key from the signed helper on macOS 15 and later. | Sign/verify succeed with no entitlements; a copy of the blob is recorded for the 'not bound to the app' limit. A VM reporting no Secure Enclave leaves this row open. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-08 | Local Network prompt on first LAN dial | Fresh install; trigger a LAN dial. | Prompt names the app; denial is shown honestly in the UI. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-09 | SMAppService agent (if used) | Register the login item/agent from the app; check System Settings > Login Items. | Appears with the app's name; enabling/disabling works. NOT TESTABLE until the bundle exists. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-10 | Sleep assertion (if used) | `pmset -g assertions` while the app holds it; kill -9 the app; check again. | Assertion listed with the app's reason string; released when the app dies. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-11 | TCC prompts match the README | Exercise every feature; watch for permission prompts (Files, Camera, Microphone, Accessibility). | Only prompts the README names, each with a plain-language reason. | NEEDS-DEVICE-VALIDATION |

## Interface: menu bar, window, keyboard

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-MAC-12 | Menu bar and Cmd shortcuts | Walk the menu bar; use Cmd+Q, Cmd+W, Cmd+, (settings), Cmd+K (palette). | Standard menus present; documented shortcuts work. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-13 | Window resize, full screen, Stage Manager | Resize to minimum, enter full screen, Split View, Stage Manager. | Layout reflows; state kept. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-14 | Retina and external display | Move the window between a Retina and an external display. | Sharp on both; no scale glitches. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-15 | Trackpad and mouse | Hover, right-click, two-finger scroll, pinch if used. | Hover states are not colour-only; context menus appear. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-16 | Keyboard focus and VoiceOver | Tab through controls with Full Keyboard Access on; VoiceOver pass. | Visible focus by shape; labels read; status announced in words. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-17 | Dark mode and Increase Contrast | Toggle both. | Meaning-colours keep their shape/word companions. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-MAC-18 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on macOS; launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-19 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-20 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-21 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-22 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-23 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-MAC-24 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Signatures and notarisation: no job exists; every build is UNSIGNED, not for release.
- Anything a hosted macOS runner printed: `CI (hosted VM) evidence`, never closing a row.
