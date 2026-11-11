# Device checklist template: iOS (iPhone)

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

Device: the owner's iPhone (model and iOS version recorded per row). No Mac exists yet (OQ-31); until it does, builds come from hosted macOS lanes on public repos and installation depends on the owner's route (OQ-2), so every row is open. Simulator results are `SIMULATOR` evidence and never close a row. Source: PORTING_PROGRAM.md section 4.3; the Local Network, Keychain and backup rows follow asystemofmodels' Apple notes. iPad-specific rows are in `DEVICE_CHECKLIST_IPADOS.md`.

## Lifecycle, background and power

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IOS-01 | Background grace (about 30 s) | Start a foreground task, press Home, watch for 60 s with a timestamped log. | The app stops work cleanly inside the grace period, writes down what was cut off, and never repeats a request by itself on return. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-02 | No daemon or background server | After backgrounding, check Settings > Battery and the Local Network list; run a capture. | No listener, no background network, no background task beyond the documented grace. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-03 | Metal work stops on resign-active | Start a GPU-heavy foreground task; swipe to the app switcher. | GPU work stops at resign-active; no background GPU use. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-04 | Thermal and Low Power Mode | Run a sustained task; enable Low Power Mode. | The app backs off before the OS throttles hard; no heat/battery abuse (App Review 2.4.2). | NEEDS-DEVICE-VALIDATION |
| DV-IOS-05 | Interruptions | Incoming call, Siri, Control Center, rotate, lock during a task. | State survives; no crash; no data lost. | NEEDS-DEVICE-VALIDATION |

## Privacy, keys and files

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IOS-06 | Keychain tier | Save a test key; check how it is stored. | Accessible `WhenUnlockedThisDeviceOnly`, not synchronisable; the UI names the tier. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-07 | Excluded from backup | Create a ledger/model file; inspect the file's `isExcludedFromBackup`; make an iCloud/Finder backup and inspect. | Ledgers and large models are excluded, so iCloud Backup is not a silent egress. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-08 | Local Network prompt (device-only) | First LAN dial after a fresh install. | The prompt names the app and the reason string; a denied dial is shown honestly in the UI and recorded. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-09 | Privacy manifest and reasons | Check the built app's `PrivacyInfo.xcprivacy` against the APIs actually used. | Declared reasons match use; no tracking domains. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-10 | Third-party AI consent (App Review 5.1.2(i)) | Trigger any cloud send. | Consent is asked before personal data leaves; the payload is shown first. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-11 | Large download prompt (App Review 4.2.3(ii)) | Trigger a model or asset download. | The user is told the size and agrees before it starts. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-12 | Files app and document browser | Export a project; open it from the Files app and a native editor. | Opens without conversion. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-13 | TestFlight crash reports disclosure (I-1 exception) | Read the in-app privacy/about page. | It names that Apple collects TestFlight/App Store crash reports and the app transmits none itself; no analytics SDK is present. | NEEDS-DEVICE-VALIDATION |

## Interface

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IOS-14 | Dynamic Type and Bold Text | Set the largest accessibility size; open every screen. | No clipped or overlapping text; status still readable. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-15 | VoiceOver | Walk the main flow with VoiceOver on. | Every control has a label; status is announced in words, not colour. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-16 | Dark mode and Increase Contrast | Toggle both. | Meaning-colours keep their shape/word companions. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-17 | Safe areas, Dynamic Island, notch | Rotate and run on the device's cutouts. | No content under hardware cutouts. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-18 | Keyboard extension memory ceiling (if the app ships one) | Use the extension for 10 minutes; watch for jetsam termination. | Stays under the observed ceiling (community-reported about 48 to 60 MB); recorded. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-IOS-19 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on iOS (TestFlight or an Xcode install); launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-20 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-21 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-22 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-23 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-24 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-IOS-25 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- App Store submission, signing and notarisation: no signing job exists in CI; every build is UNSIGNED, not for release.
- Anything a Simulator run shows: `SIMULATOR`, not device evidence.
