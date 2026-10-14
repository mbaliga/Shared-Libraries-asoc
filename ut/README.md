# ut/ - Ubuntu Touch enabler kit (F7 slice)

PORTING_PLAN.md F7 ("ubuntu-touch-shell") and the S-UT1 spike. **Status: DRAFT, nothing here has run
on a device, and no click has ever been built from it.**

## Two honest caveats - read before using any of this

1. **The non-Gradle sharing mechanism is OPEN** (OQ-24; reserved decision D-Y). This repository shares
   code with consumers as a git submodule plus Gradle `includeBuild`. A Click app, a QML app or a
   Python app has no Gradle build to composite, so *how a consumer obtains this kit is undecided*.
   This kit does not choose a mechanism and wires nothing into any consumer. Do not read the
   directory layout as a delivery decision.
2. **Which repos may add `ubuntu-touch/` directories awaits the owner's approval** (the Ubuntu Touch
   brief, line 227). This kit is the **reusable substance, not a rollout**: nobody has been told to
   adopt it, and no consumer repo has been touched.

Also standing: **rule R11** - no application identifier appears in any manifest before a `NAMES.md`
row exists for it. Everything here that could name an app is an `@TOKEN@`, and a lint enforces it
(below). The only literal names in this directory are obviously fake test fixtures (`.invalid`).

## What is in the kit

| Path | What | Verified how |
|---|---|---|
| `jlink/build-runtime.sh` | The recipe for an **aarch64 Temurin 21 jlink runtime**, as a runnable script (download with SHA-256 check, cross-link, verify architecture and GLIBC ceiling, write a manifest) | `jlink/selftest.sh` offline (fakes, no download); and one real run, recorded below |
| `templates/clickable.yaml.in` | DRAFT Clickable build description; framework `ubuntu-touch-24.04-1.x` | `lint/ut-lint.py --templates` (R11, structure) |
| `templates/manifest.json.in` | DRAFT click manifest, tokens only | same |
| `templates/app.apparmor.in` | DRAFT AppArmor manifest, common policy groups only, tokens only | same |
| `tools/render.py` | A stand-in for Clickable's `@TOKEN@` substitution so the lint can be exercised without Clickable | `lint/selftest.sh` |
| `lint/ut-lint.py` | Lints a **rendered** manifest/AppArmor pair (JSON validity, framework, hooks, policy-group allowlist) and the **templates** (R11) | its own `--self-test` and `lint/selftest.sh` (the latter drives the real templates through the renderer) |

Run the self-tests (no network, no Clickable, no device, no JDK download):

```bash
bash ut/lint/selftest.sh        # lint + renderer, driven through the real templates
bash ut/jlink/selftest.sh       # the jlink recipe's plumbing against fakes
```

Both are also run by `.github/workflows/platform-kits.yml`.

## 1. The jlink runtime recipe

```bash
ut/jlink/build-runtime.sh --out build/ut-runtime                  # downloads, verifies, links, verifies
ut/jlink/build-runtime.sh --dry-run                               # prints the plan and the exact jlink command
ut/jlink/build-runtime.sh --modules "$(jdeps --print-module-deps --ignore-missing-deps app.jar)"
ut/jlink/build-runtime.sh --jdk-archive OpenJDK21U-*.tar.gz --sha256 <hex>     # offline, checksum mandatory
ut/jlink/build-runtime.sh --verify build/ut-runtime               # re-check an existing runtime
```

It needs a **host JDK 21** (the same major as the target - jlink cross-linking requires it), `curl`,
`tar`, `python3`, and binutils `readelf`. Nothing is installed and nothing runs as root. The Adoptium
API supplies both the download link and the checksum, and the archive is rejected on mismatch. It then
refuses an archive whose `bin/java` is not AArch64, links with
`--strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-6`, and verifies the
result: every ELF file's GLIBC requirement against a ceiling (default **2.17**), and the machine type.

**Run for real in the planning container (2026-10-14):**

| | |
|---|---|
| Target JDK | Temurin `jdk-21.0.12.1+1`, linux/aarch64, SHA-256 `23e37e02...c773e223` (checked) |
| Host jlink | 21.0.12.1 (x86_64) cross-linking |
| Modules | `java.base,java.logging,jdk.unsupported` (this kit's default; the module list of the earlier proof is not recorded) |
| Result | **39,476 KiB (about 38.6 MiB)**, 15 ELF files, all `AArch64`, **max `GLIBC_2.17`**, no `GLIBCXX` requirement |

That is the same size class and the same glibc ceiling the program records for its one earlier proof
(39 MB, `GLIBC_2.17`). It is **`CONTAINER-BUILD-ONLY`**: the runtime was linked and *inspected*, never
started. Whether it runs inside a click on a Lomiri device, and whether the glibc on a 24.04 device is
new enough, is S-UT1 and `NEEDS-DEVICE-VALIDATION` (`checklists/DEVICE_CHECKLIST_UT.md`).

Things the run taught, kept in the script as comments so they are not rediscovered:

- Do **not** pass `--strip-debug`. In JDK 21 it drives the native-symbol stripper through the host's
  `objcopy`, which cannot read foreign AArch64 ELF files; it prints a screenful of errors and strips
  nothing. Temurin's native libraries already ship stripped; the result was the same size either way.
- Leave `java.desktop` out of a headless core. S1.2's Swing window lives in a separate package so a
  jlinked runtime can omit it.
- The default module list is a starting point. Derive the real one from the consumer's jar with `jdeps`.

## 2. The templates

All three are DRAFT, tokens only. Two kinds of token:

| Token | Owner | Notes |
|---|---|---|
| `@CLICK_FRAMEWORK@`, `@CLICK_ARCH@` (manifest), `@APPARMOR_POLICY@` (AppArmor) | **Clickable** substitutes them at packaging time | Documented in the UBports 24.04 porting guide (https://forums.ubports.com/post/89959). `@CLICK_ARCH@` is Clickable's own convention from its default templates; the guide does not mention it |
| `@CLICK_NAME@`, `@APP_TITLE@`, `@APP_VERSION@`, `@APP_DESCRIPTION@`, `@APP_MAINTAINER@`, `@APP_HOOK@`, `@POLICY_GROUPS@`, `@CLICKABLE_BUILDER@`, `@APP_EXEC_NAME@` | the **consumer** | `@POLICY_GROUPS@` expands to a quoted, comma-separated list. No value may be a real name until a `NAMES.md` row exists (R11) |

Framework: `ubuntu-touch-24.04-1.x` (the brief's target; Clickable 8.4.0 or later is needed to upload
24.04-1.x builds to OpenStore). Applications built for it do not run on 20.04 devices and the device
refuses to install the click. The owner's OnePlus 6 is read as 20.04-only (OQ-37), so a labelled
"S-UT1 (focal)" pre-spike would need a different framework string, which is a decision, not a default.
The lint takes `--framework` for that case; the focal framework name itself was **not** verified here.

Policy groups: **common groups only.** The allowlist is the 16 groups the UBports AppArmor page
(https://docs.ubports.com/en/latest/appdev/platform/apparmor.html) lists as common usage
(`accounts audio camera connectivity content_exchange content_exchange_source keep-display-on location
microphone networking nfc push-notification-client sensors usermetrics video webview`); the page's 13
**reserved** groups (manual OpenStore review) are refused by name. That list was read through a
summarising fetch on 2026-10-14: re-confirm it against the page before relying on it.

Unverified, and stated so in the files:

- Clickable is **not installed** here. The `clickable.yaml.in` keys (`clickable_minimum_required`,
  `framework`, `builder`, `kill`) come from documentation and the forum guide; `clickable build` has
  never been run against them. `builder` is a token because which builder packages a JVM-cored click
  is what S-UT1 has to find out.
- The numeric value Clickable puts in `policy_version` for the 24.04 framework was **not** observed.
  The lint checks its *shape* only (`"1.0"`, `24.04`); pin it with `--policy-version` once seen.
- There is **no `.desktop` template.** A hook needs one, and its `Exec`/`Icon` lines depend on app
  identifiers, so it waits for a `NAMES.md` row (named follow-up).

## 3. The lint

```bash
python3 -I ut/lint/ut-lint.py --manifest manifest.json --apparmor app.apparmor [--framework F] [--policy-version V]
python3 -I ut/lint/ut-lint.py --templates ut/templates      # the R11 guard on the templates themselves
python3 -I ut/lint/ut-lint.py --self-test
```

On a rendered pair it fails on: invalid or duplicate-key JSON; any unrendered `@TOKEN@`; missing or
malformed manifest keys; a framework other than the expected one; hooks that lack `apparmor`/`desktop`
or do not name the AppArmor file you passed; AppArmor keys beyond `policy_groups`/`policy_version`
(so no `template`, `read_path`, `write_path`); and any policy group outside the common allowlist.
It *notes* (never fails) groups that widen the network or telemetry posture, because constellation rule
I-1 means a consumer should be able to say why it needs `networking`, `push-notification-client` or
`usermetrics`.

On the templates it fails if a literal appears where a name could go (keys, string values, the
`kill:`/`builder:` lines), or if a Clickable-owned token is replaced by a literal.

What it does **not** prove: that OpenStore accepts the click, that the AppArmor profile confines what
you think, or that anything runs on a device.

## 4. Named follow-ups (not done here)

- The QML <-> JVM bridge and the single S-UT1 verdict (needs a device, `NAMES.md` rows and the OQ-37
  device decision).
- A `.desktop` template; the F7 webapp-container template for the P-UT a wave.
- Confirming the Lomiri edge-ownership `ASSUMPTION` (PORTING_PLAN.md section 4) at S-UT1.
- Observing Clickable's rendered `policy_version`, and the focal framework string, if the focal
  pre-spike proceeds.
- The sharing mechanism (OQ-24) and the per-repo `ubuntu-touch/` approval (brief L227) - owner decisions.

## Evidence summary

| Claim | Class |
|---|---|
| Recipe links a Temurin 21 aarch64 runtime of ~39 MB with a GLIBC_2.17 ceiling | `CONTAINER-BUILD-ONLY` (one real run, 2026-10-14) |
| Recipe plumbing, lint and renderer behave as documented | `CONTAINER-BUILD-ONLY` (self-tests) |
| Templates produce a click that builds, installs or passes OpenStore | not run: `NEEDS-DEVICE-VALIDATION` |
| The runtime starts on a Lomiri device | not run: `NEEDS-DEVICE-VALIDATION` |
