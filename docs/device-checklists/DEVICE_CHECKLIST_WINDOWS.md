# Device checklist template: Windows 10/11

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

Device: the owner's Windows PC (x64 primary; arm64 secondary; Windows 11 23H2 or later). Source: asystemofmodels `desktop/packaging/windows/docs/DEVICE_CHECKLIST_WINDOWS.md` and PORTING_PROGRAM.md section 4.5. Commands run in an ordinary PowerShell unless marked (elevated). Hosted-runner results are `CI (hosted VM) evidence` and never close a row.

## Install, SmartScreen and uninstall

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-WIN-01 | UNSIGNED installer under SmartScreen | Download the `.msi`/`.exe`; run it. | SmartScreen warns as expected for an UNSIGNED build; the README's steps are honest about it. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-02 | MSI install and uninstall | Install; `Get-Package`/Apps & features lists it; uninstall; check `%ProgramFiles%\<app>`, `%LOCALAPPDATA%\<app>` and services. | Clean install; uninstall removes the program and leaves user data as the README says; no service remains. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-03 | winget manifest (once published) | `winget install --manifest <dir>` on a clean user. | Installs the same bits as the MSI. NOT TESTABLE until a manifest exists (R11). | NEEDS-DEVICE-VALIDATION |
| DV-WIN-04 | Path and filename handling | Create a project at a path with spaces, non-ASCII, and a long path (>260 chars with long paths on and off). | Opens and saves; failures are messages, not crashes. Reserved names and `:<>\|?*"` are refused cleanly. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-05 | x64 and arm64 | Run the matching build on each machine owned. | Starts natively; the unavailable arch is recorded as not tested. | NEEDS-DEVICE-VALIDATION |

## Keys, firewall and power

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-WIN-06 | Key tier (Platform Crypto Provider / DPAPI / file) | Save a test key. On a TPM 2.0 machine run the PCP integration test. | The UI names the tier; a TPM-backed key signs and verifies; a machine without TPM falls back honestly. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-07 | Firewall prompt and rules | Start any listener (if the app has one) with no rules; then with the printed rule applied (elevated). | The first-listen prompt appears with the app's name; a dismissed prompt creates block rules that the app detects and names; nothing is opened silently. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-08 | Non-English Windows fails closed | Run the firewall probe on a German or French Windows. | An unrecognised output keeps the listener closed with a message. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-09 | Sleep, lid and Modern Standby | (elevated) `powercfg /requests` while the app works; close the lid; press Sleep; let the display time out. | The app's request is listed only while it works; it drains on display-off and suspend. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-10 | Presence and idle | Use the machine normally with the app armed. | Never acts while you type; honours idle; a full-screen game or gamepad-only session is handled as the README says. | NEEDS-DEVICE-VALIDATION |

## Interface: window, DPI, keyboard

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-WIN-11 | DPI and multi-monitor | Set 100%, 150%, 200%; move between monitors of different scale. | Sharp; no clipped windows; correct size after moving. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-12 | Snap layouts and resize | Snap left/right/quarters; resize to minimum; maximise. | Layout reflows; state kept. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-13 | Keyboard focus and shortcuts | Tab through controls; Alt+F4, Ctrl+K, documented shortcuts. | Visible focus by shape; shortcuts work. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-14 | Mouse and touch | Hover, right-click, wheel; touch if the device has it. | Hover not colour-only; context menu appears. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-15 | Narrator and High Contrast | Walk the main flow with Narrator; turn on a High Contrast theme. | Labels read; status in words; meaning survives High Contrast. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-16 | Dark mode | Toggle the system theme. | Follows or the README says it does not. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-WIN-17 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on Windows; launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-18 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-19 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-20 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-21 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-22 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-WIN-23 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Authenticode signing and SmartScreen reputation: no signing job exists; every build is UNSIGNED, not for release.
- Anything a hosted Windows runner printed: `CI (hosted VM) evidence`, never closing a row.
