# Device checklist template: Ubuntu Touch

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

Device: the owner's UBports phone or tablet on Ubuntu Touch 24.04-2.x (24.04-1.x where a row says so). Source: asystemofmodels `ubuntu-touch/docs/DEVICE_CHECKLIST_UT.md` (DV-UT01 to DV-UT16) and PORTING_PROGRAM.md section 4.1. The single S-UT1 verdict (can a bundled JVM or native core run under Lomiri confinement) is DV-UT-01 and is decided once for the whole constellation. Setup once: install the click with `clickable install` over adb, or `pkcon install-local --allow-untrusted <file>`; it is UNSIGNED and not for release. For SSH rows enable SSH under Developer Mode.

## Confinement and lifecycle

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-UT-01 | S-UT1: the bundled core runs under real AppArmor confinement | Install the click, open `<app>`, run its self-test. Then over SSH: `sudo dmesg \| grep 'apparmor="DENIED"' \| grep <click-id>`. | A self-test block that ends in success (TLS 1.3 where used, the app's vectors, storage append, RSS recorded). No denial breaks it; list any other denials found. | NEEDS-DEVICE-VALIDATION |
| DV-UT-02 | A 24.04-1.x click on a 24.04-2.x device | Install and launch on a 2.x device; `click list \| grep <click-id>`. | Installs and starts (recorded as an assumption until run). | NEEDS-DEVICE-VALIDATION |
| DV-UT-03 | The child process freezes with the app | Start `<app>`, switch to another app, then `ps -o pid,stat,cmd -C java` (or the core's process name) over SSH. | State `T` within about 2 s of leaving the foreground. | NEEDS-DEVICE-VALIDATION |
| DV-UT-04 | The lifecycle signal is delivered before the stop | Compare the app's interrupted state with the moment of the freeze using a timestamped log or the status line. | The state change arrives before SIGSTOP; if not, the app's watchdog covered it and the gap is recorded. | NEEDS-DEVICE-VALIDATION |
| DV-UT-05 | Screen lock and windowed mode | On a tablet with keyboard and touchpad, lock the screen mid-session; also run windowed. | The app treats it as locked (no new network dials); windowed resize keeps state. | NEEDS-DEVICE-VALIDATION |
| DV-UT-06 | App-held display wake lock | During a long foreground task, leave the screen untouched for 3 minutes. | The screen stays on while the task runs and releases within about 1 s of its end. | NEEDS-DEVICE-VALIDATION |

## Network, files and keys

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-UT-07 | Quiescence while open | 30 minutes with `<app>` open on its idle screen, capture at the gateway. Positive control: open a screen that dials. | No SYN to any address while idle; the positive control shows within 5 s. | NEEDS-DEVICE-VALIDATION |
| DV-UT-08 | No listening socket | Over SSH while `<app>` is running: `ss -ltnup`. | No socket owned by `<app>` (unless the README names one). | NEEDS-DEVICE-VALIDATION |
| DV-UT-09 | Metered data | Cellular data on, Wi-Fi off. | The app makes no optional dial on a metered link. | NEEDS-DEVICE-VALIDATION |
| DV-UT-10 | Key-file readability from Terminal (the stated limit) | Terminal app: `stat ~/.local/share/<click-id>/<key file>`. | Readable by the user: the limit is real and the app states it (no OS keystore tier exists on this platform). | NEEDS-DEVICE-VALIDATION |
| DV-UT-11 | Firewall state | `sudo nft list ruleset; sudo iptables -S` | Recorded as found. | NEEDS-DEVICE-VALIDATION |
| DV-UT-12 | Data directory and backups | List `~/.local/share/<click-id>` and `~/.config/<click-id>`; export the project. | Only the files the README names; the export opens in a native tool. | NEEDS-DEVICE-VALIDATION |

## Packaging, store policy and UI

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-UT-13 | Click review | Run `click-review` on the built click; install on the device. | No errors; each warning is explained in the repo. Recorded as found. | NEEDS-DEVICE-VALIDATION |
| DV-UT-14 | Install-path signature enforcement | Try installing a click with a corrupted debsig, if the tooling allows. | Recorded as found. | NEEDS-DEVICE-VALIDATION |
| DV-UT-15 | Orientation, text scale and dark theme | Rotate; raise system text size; switch the theme. | Layout holds; no clipped controls; status still readable without colour. | NEEDS-DEVICE-VALIDATION |
| DV-UT-16 | On-screen keyboard and focus | Open every text field; type; rotate with the keyboard up. | Field stays visible above the keyboard; focus order is logical. | NEEDS-DEVICE-VALIDATION |
| DV-UT-17 | Libertine spike (optional) | In a Libertine noble container run the desktop build's self-test. | Recorded as found. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-UT-18 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on Ubuntu Touch; launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-UT-19 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-UT-20 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-UT-21 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-UT-22 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-UT-23 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-UT-24 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Anything about OpenStore signing or review: every click here is UNSIGNED, not for release.
- Background behaviour: Lomiri freezes a backgrounded app; the app claims no background service on this platform.
