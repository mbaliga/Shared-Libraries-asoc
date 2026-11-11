# Device checklist template: Linux desktop, Steam Deck and Redmagic-Edge

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

Devices: the owner's Steam Deck (SteamOS, USER mode only), a Dell laptop (Ubuntu or Fedora) and any other Linux machine. Source: asystemofmodels `desktop/docs/DEVICE_CHECKLIST_LINUX.md` (DV-D, DV-L, DV-B, DV-P rows) and PORTING_PROGRAM.md section 4.2. Commands run as the user shown; `deck` is the default SteamOS user.

## Steam Deck (SteamOS; USER mode: no root-managed install survives a SteamOS update)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-LX-01 | Game and the app share a uid | During a game, over SSH: `ps -o user= -p $(pgrep -f -n steamapps)`. | `deck`: the USER unit has no sandbox against a game; recorded. | NEEDS-DEVICE-VALIDATION |
| DV-LX-02 | Mode switch keeps a user service | `systemctl --user start <app>`; switch Game -> Desktop -> Game; `systemctl --user show <app> -p ActiveEnterTimestamp` before and after, with and without `loginctl enable-linger deck`. | Timestamp unchanged, or the restart is recorded. | NEEDS-DEVICE-VALIDATION |
| DV-LX-03 | Linger survives a SteamOS update | After one SteamOS update: `loginctl show-user deck -p Linger`. | `Linger=yes`. | NEEDS-DEVICE-VALIDATION |
| DV-LX-04 | Install survives a SteamOS update | After one update: `ls -l ~/.local/opt/<app>/current ~/.local/bin/<app> ~/.config/systemd/user/<app>.service`. | All three present (SteamOS updates do not touch `$HOME`). | NEEDS-DEVICE-VALIDATION |
| DV-LX-05 | Real sleep with an inhibitor held | Docked, Game Mode, with the app holding a delay lock; press power; check `<app> status --json` afterwards. | The Deck is actually asleep (fan off, SSH unreachable); the app drained before sleep. A block lock would keep it awake: record that. | NEEDS-DEVICE-VALIDATION |
| DV-LX-06 | Per-process GPU counter | `cat /sys/class/drm/card*/device/gpu_busy_percent`; `grep drm-engine /proc/$(pgrep -f <app>)/fdinfo/*`. | A number and `drm-engine-gfx` lines; without them any GPU-contention rule is off. | NEEDS-DEVICE-VALIDATION |
| DV-LX-07 | Vulkan device and heap budget | `<app> doctor gpu` (or `vulkaninfo --summary`). | Device `RADV VANGOGH` and the heap budget the README claims. | NEEDS-DEVICE-VALIDATION |
| DV-LX-08 | Gaming Mode under gamescope | Add the app as a non-Steam game; launch in Game Mode; use controller and touch. | Window fits 1280x800; focus, controller navigation and exit work. | NEEDS-DEVICE-VALIDATION |
| DV-LX-09 | Desktop Mode install via Discover or tarball | Install by the route the README gives; launch. | Starts; menu entry present; Wayland/X11 session recorded. | NEEDS-DEVICE-VALIDATION |
| DV-LX-10 | Firewall state | `sudo nft list ruleset` | Recorded as found (the Deck ships no rules). | NEEDS-DEVICE-VALIDATION |
| DV-LX-11 | Swap and cgroup limits | `cat /sys/fs/cgroup$(systemctl --user show <app> -p ControlGroup --value)/memory.swap.max` | As the unit file claims (e.g. `0`); memory delegation to the user manager recorded. | NEEDS-DEVICE-VALIDATION |

## Laptop or desktop (Ubuntu or Fedora)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-LX-12 | Inventory | `cat /etc/os-release; lspci \| grep -Ei 'vga\|3d'; ls /dev/tpmrm0` | OS, GPU and TPM presence recorded; decides which package row matters. | NEEDS-DEVICE-VALIDATION |
| DV-LX-13 | Session type and HiDPI | Run under Wayland and under X11 (if both exist); set 100%, 150%, 200% scale. | Renders sharp; no clipped windows; pointer and keyboard focus work in both. | NEEDS-DEVICE-VALIDATION |
| DV-LX-14 | deb or rpm install, nothing runs | `sudo apt install ./<app>_<ver>_amd64.deb` (or `sudo dnf install ./<app>-<ver>.x86_64.rpm`); `systemctl is-enabled <app>; systemctl is-active <app>`. | `disabled` and `inactive`; no service user shell; the self-test run as your own user passes. | NEEDS-DEVICE-VALIDATION |
| DV-LX-15 | Remove keeps state, removes code | `sudo apt remove <app>`; `ls /opt/<app> /var/lib/<app>`. | `/opt/<app>` gone; state kept as the postremove note says. | NEEDS-DEVICE-VALIDATION |
| DV-LX-16 | Flatpak or tarball route | Install by the route the README names; run `<app>` from the menu and from a terminal. | Starts; sandbox permissions match the manifest; no extra permission requested. | NEEDS-DEVICE-VALIDATION |
| DV-LX-17 | Secret Service tier | Save a test key; `secret-tool search` for it (GNOME Keyring / KWallet). | The UI names the tier used; with no keyring running it names the file tier. | NEEDS-DEVICE-VALIDATION |
| DV-LX-18 | Real suspend and resume | With the app running, close the lid on AC and on battery; resume. | The app drains before sleep and does not serve in the resume window; nothing stays awake because of the app. | NEEDS-DEVICE-VALIDATION |
| DV-LX-19 | Thermal and fan under sustained load | Run a sustained workload; watch the app's reported band and listen. | Thresholds hold the app before the machine is uncomfortable; owner judges noise. | NEEDS-DEVICE-VALIDATION |
| DV-LX-20 | Keyboard, pointer and focus | Tab through every screen; right-click, wheel, hover states. | Visible focus that is not colour alone; shortcuts documented in the README work. | NEEDS-DEVICE-VALIDATION |

## Common rows (every platform)

| ID | Check | Exact method | Expect (a pass) | Status |
|---|---|---|---|---|
| DV-LX-21 | Clean install, first launch | Install the UNSIGNED build of `<app>` `<ver>` the way a user would on Linux; launch it; note launch time and any prompt. | The app opens to its first screen; every permission or OS prompt is one the README lists, in the README's order; nothing is requested that the README does not name. | NEEDS-DEVICE-VALIDATION |
| DV-LX-22 | Quiet at idle (I-1) | Leave `<app>` open and idle for 30 minutes with a packet capture at the router or gateway (the device cannot be trusted to capture itself). Positive control: trigger one user action that is allowed to touch the network. | Zero connections while idle and at launch. The positive control is visible in the capture, so an empty capture is not a broken capture. | NEEDS-DEVICE-VALIDATION |
| DV-LX-23 | Key tier is reported (I-2) | Open the screen that reports key storage and save one test key (or create the node identity). | The UI names the tier actually used (hardware / OS keystore / file) and it matches what this device offers; a file tier says so in words. | NEEDS-DEVICE-VALIDATION |
| DV-LX-24 | Colour is never the only signal (I-3) | Switch the OS to greyscale (or use a colour-blindness filter for red-green); walk every screen that shows status, selection or severity. | Every status still reads: a shape, an icon or a word accompanies every colour. Nothing relies on red against green. | NEEDS-DEVICE-VALIDATION |
| DV-LX-25 | Plain files open natively (I-5) | Export the project/data the app writes; open it in the platform's native tool named in the README. | The export opens without conversion and without a proprietary wrapper. | NEEDS-DEVICE-VALIDATION |
| DV-LX-26 | Crash recovery screen | Trigger the debug crash preview (or a real crash) and relaunch. | The recovery screen appears on next launch, shows the captured report, and nothing is sent anywhere. | NEEDS-DEVICE-VALIDATION |
| DV-LX-27 | Uninstall leaves nothing running | Uninstall; then list processes, services, scheduled jobs and the app data directory. | No process or job remains; data directory handling matches the README (kept or removed, said plainly). | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Signatures: every package here is UNSIGNED, not for release.
- SteamOS in CI: a hosted container with an Arch userland is a proxy only and is labelled `VIRTUALIZED — NOT DEVICE EVIDENCE`.
