# packaging

Per-OS packaging **templates** for the constellation (porting program item F10), plus `lint.py`,
which renders them with sample placeholders and checks they parse. Two sentences of scope: these
files are starting points with `@@PLACEHOLDER@@` slots for each app to fill; none of them has been
run against its real toolchain.

## Status, stated plainly

**No template here is known to build an installer.** This environment has no Windows, no Mac, no
phone, no Flatpak/nFPM/WiX/XcodeGen/Clickable toolchain. What is verified is limited to: each
rendered file is well-formed XML / YAML / JSON / plist / shell / Ruby (`python3 -I packaging/lint.py`),
and a few policy checks hold (below). Evidence label: `CONTAINER-BUILD-ONLY`. Every real build,
signing and install step is `NEEDS-DEVICE-VALIDATION` or `CI-ONLY / NOT RUN`. Tool-specific keys
(Clickable, UT manifest framework string, WiX `Files` group naming, winget schema version, the
Flathub runtime version) were written from memory and are unverified; each is a placeholder or
carries a comment saying so.

## Layout

| Directory | Templates |
|---|---|
| `linux/` | `jpackage.args.in`, `nfpm.yaml.in` (deb, rpm), `flatpak/` (manifest consuming a prebuilt app-image, desktop file, metainfo), `tarball/install.sh.in` (user-level, no root, no network) |
| `windows/` | `Product.wxs.in` (WiX v4), `winget/` (version, installer, locale manifests) |
| `macos/` | `entitlements.plist.in` (placeholder set; the real set is spike S-M3's verdict), `Info.plist.in`, `notarize.sh.in` (disabled unless `ASOC_NOTARIZE_ENABLE=1`), `homebrew/cask.rb.in` |
| `ios/` | `project.yml.in` (XcodeGen), `PrivacyInfo.xcprivacy.in`; bundle ids are `com.asoc.<appname>` |
| `ubuntu-touch/` | `clickable.yaml.in`, `manifest.json.in`, `apparmor.in` (renders to `apparmor.json`; common policy groups only) |

Not in this step: the three Apple lints, `tauri.conf.json`, the web-wallpaper kit (other F10 parts).

## Rules the templates follow

- Unsigned and unreleased (R6): every commentable template carries the header `TEMPLATE - UNSIGNED -
  NOT FOR RELEASE`. No signing material in the repo; the iOS team id is `${ASOC_APPLE_TEAM_ID}` from
  the CI environment, `CODE_SIGNING_ALLOWED` defaults to `NO`, and `notarize.sh` reads its identity
  and API key only from `ASOC_*` environment variables.
- No telemetry (I-1): no analytics or crash-reporting entries anywhere; the Apple privacy manifest
  declares no tracking and no collected data; no `usermetrics` policy group on Ubuntu Touch; the
  Flatpak and macOS templates grant no network permission by default (an app adds it, and names
  why, in its own README). The I-1 Apple crash-report exception, if used, is named in each app's own
  README and store listing, not here.
- No per-platform identifier is real (R11): the click name, Flatpak id, bundle id, WiX upgrade
  GUID and winget id are placeholders until each has a `NAMES.md` row. Lint samples are obviously
  fake.
- Colour never carries meaning (I-3): these files contain no UI colour.
- Permissive licences only (I-11): no GPL tooling is linked or vendored; nFPM, XcodeGen, WiX and
  Clickable are used as external tools, not copied.

## Using it

```
python3 -I packaging/lint.py                      # parse every template with sample values
python3 -I packaging/lint.py --emit OUT --values values.json
```

`--emit` writes rendered files to `OUT` (outside `packaging/`), refuses without `--values`, and
rejects any value that does not match its pattern (no quotes, angle brackets or newlines reach the
output). Placeholders and their patterns are declared at the top of `lint.py`; adding a template
means adding a row to `TEMPLATES` (lint fails on an unlisted `*.in`). Consumers should run this
generator into their own tree rather than copy-vendoring templates (I-6).
