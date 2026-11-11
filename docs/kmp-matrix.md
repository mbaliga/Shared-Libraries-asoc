# kmp-matrix.yml: reusable CI matrix

`.github/workflows/kmp-matrix.yml` is a `workflow_call` template for Kotlin Multiplatform repos
(porting program item F9). A caller picks the lanes its target list needs.

## Status, stated plainly

This is a **template that has never been run**. The container it was written in has no Mac,
no Windows host, no Android SDK, no Xcode, no Docker daemon and no Ubuntu Touch device.
Verified here: the YAML parses and `actionlint` reports no findings, and the Windows path
lint script was exercised against a throwaway git tree (it rejected `a:b.txt` and `aux.kt`,
and passed a clean tree). Everything else is `CI-ONLY / NOT RUN`. When a lane does run on a
hosted runner, its evidence is `CI (hosted VM) evidence` and nothing stronger; the
simulator, headless-browser and arm64 lanes are labelled as such in each job summary. No lane
is device evidence.

## Rules the template enforces

- **No Actions artifact uploads** (R6). Release binaries belong in draft GitHub Releases,
  which this template does not create.
- **Package lane: `main` and tags only.** Pull requests run tests and compile only. The
  `package` job also refuses `pull_request` events. Output is unsigned and discarded.
- **Sign lane: disabled template.** The `sign` job has a condition that is never true and
  references no secret. Signing waits for OQ-3 and OQ-4.
- **OQ-20: macOS and Windows lanes need `repo-public: true`.** `jvm-windows`, `jvm-macos`,
  `ios-sim` and `ios-device-build` are skipped otherwise. Private repos get Linux lanes only.
- **R3 Windows path lint** (`windows-path-lint`) runs before `jvm-windows`. It rejects tracked
  paths containing `: < > | ? * " \`, reserved device names (CON, PRN, AUX, NUL, COM0-9,
  LPT0-9, with or without extension) and components ending in a dot or space. It does not
  check `-text` on byte-exact fixtures; reviewers must.
- No telemetry and no network use beyond fetching toolchains and dependencies. Nothing binds a
  port, downloads a model or handles key material.
- Actions are pinned **by tag**, with a TODO to pin each to a full commit SHA, because the
  SHAs could not be resolved here. Do not invent SHAs. Callers should reference this workflow
  itself by SHA (`uses: <owner>/Shared-Libraries-asoc/.github/workflows/kmp-matrix.yml@<sha>`).

## Inputs

All inputs are optional.

| Input | Type | Default | Meaning |
|---|---|---|---|
| `lanes` | string | `jvm-linux` | Comma-separated, no spaces. Any of `jvm-linux`, `jvm-linux-arm`, `jvm-windows`, `jvm-macos`, `ios-sim`, `ios-device-build`, `wasm-browser`, `ut-click`, `ut-arm64-smoke`, `package`. |
| `repo-public` | boolean | `false` | True only for public repos. Gates every macOS and Windows lane (OQ-20), and the arm lanes unless `allow-arm-on-private`. |
| `allow-arm-on-private` | boolean | `false` | Run `jvm-linux-arm` and `ut-arm64-smoke` on a private repo (arm runners are free only on public repos; you accept the cost). |
| `java-version` | string | `17` | Temurin JDK major version. |
| `gradle-args` | string | empty | Extra Gradle arguments for every Gradle lane. |
| `jvm-task` | string | `jvmTest` | Gradle task(s) for the `jvm-*` lanes. |
| `wasm-task` | string | `wasmJsBrowserTest` | Gradle task(s) for `wasm-browser`. |
| `ios-sim-task` | string | `iosSimulatorArm64Test` | Gradle task(s) for `ios-sim`. |
| `ios-project` | string | empty | Path to an existing `.xcodeproj`. Empty skips `ios-device-build`. The template does not run XcodeGen. |
| `ios-scheme` | string | empty | Xcode scheme for `ios-device-build`. |
| `package-task` | string | empty | Gradle task(s) for `package`. Empty skips the lane. |
| `clickable-image` | string | empty | Clickable image for `ut-click`. Must be digest-pinned (`name@sha256:...`); a tag-only value fails the lane. Empty skips it. The digest is not known here and is not invented. |
| `clickable-args` | string | `build --arch arm64` | Arguments passed to `clickable` inside the image. |
| `ut-smoke-task` | string | empty | Gradle task(s) for `ut-arm64-smoke`. Empty skips it. |
| `lint-windows-paths` | boolean | `true` | Run the path lint before `jvm-windows`. Leave on. |

Task and argument inputs reach the shell through `env:`, not by interpolation into the script.
They are word-split, so they cannot contain quoted arguments with spaces.

## Lanes

| Lane | Runner | Runs when |
|---|---|---|
| `windows-path-lint` | ubuntu-24.04 | `jvm-windows` selected, public, lint on |
| `jvm-linux` | ubuntu-24.04 | selected |
| `jvm-linux-arm` | ubuntu-24.04-arm | selected, public or `allow-arm-on-private` |
| `jvm-windows` | windows-2025 | selected, public, lint passed |
| `jvm-macos` | macos-15 | selected, public |
| `ios-sim` | macos-15 | selected, public. `SIMULATOR`, not a device. |
| `ios-device-build` | macos-15 | selected, public, project and scheme set. Unsigned (`CODE_SIGNING_ALLOWED=NO`), compile only. |
| `wasm-browser` | ubuntu-24.04 | selected. `BROWSER-HEADLESS`. |
| `ut-click` | ubuntu-24.04 | selected, image set. Container build only; everything on a UT device stays `NEEDS-DEVICE-VALIDATION`. |
| `ut-arm64-smoke` | ubuntu-24.04-arm | selected, task set, public or `allow-arm-on-private`. `CI-APPROX - NOT DEVICE EVIDENCE`. |
| `package` | ubuntu-24.04 | selected, task set, not a PR, ref is `main` or a tag. |
| `sign` | none | never (disabled template) |

## Example caller

```yaml
name: CI
on: [push, pull_request]
jobs:
  matrix:
    uses: OWNER/Shared-Libraries-asoc/.github/workflows/kmp-matrix.yml@SHA  # pin by SHA
    with:
      lanes: jvm-linux,wasm-browser
      repo-public: false
```

## Not covered

- asom's "root guards" named in F9 are repo-specific and are not in this template; asom's own
  build keeps them.
- Android lanes are out of scope (no Android SDK here); each repo's existing `ci.yml` keeps them.
- Runner image labels (`macos-15`, `windows-2025`, `ubuntu-24.04-arm`) were chosen from memory
  of GitHub's hosted set and could not be checked against GitHub here.
