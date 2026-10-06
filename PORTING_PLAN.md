# Shared Libraries (a system of cells) — multi-platform porting plan

> Part of the constellation-wide porting program (`Personal-Tracker/PORTING_PROGRAM.md`, 2026-10-06).
> Status: **PLAN — nothing in this document has been built.** Every claim about a target platform is
> labelled with its evidence class (§0). This file is owned by the lead planning session; a platform
> track updates only its own §4 row. This repo has no PROGRESS or STATE file: the README module table's
> `Platform` column is its only state record, so a track that changes a module's reach also edits that cell.
> Ubuntu Touch → Linux desktop → iOS/iPadOS → macOS → Windows is the owner's order.

## 0. Evidence labels (never dropped)

`LAB` · `CI (hosted VM) evidence` · `EMULATOR EVIDENCE` · `SIMULATOR` · `CI-APPROX — NOT DEVICE EVIDENCE` ·
`SIMULATED — NOT DEVICE EVIDENCE` · `VIRTUALIZED — NOT DEVICE EVIDENCE` · `SYNTHETIC` · `CI-ONLY / NOT RUN` ·
`NEEDS-DEVICE-VALIDATION` (NDV) · `NEEDS-OWNER-VALIDATION` (NOV) · `PLAN` (this document) ·
`NOT-APPLICABLE (<reason>)` · `CONTAINER-BUILD-ONLY` (JVM x86_64 compile and tests in the planning container,
nothing else) · `BROWSER-HEADLESS`. Tables below write `CI (hosted VM)` for the first. Every row in §4 is `PLAN`
today. The planning container has no Android SDK, no Swift, no Clickable and no device, so no step below has
been run.

## 1. What this repo is, in porting terms

- **Product.** The constellation's neutral home for cross-app Kotlin libraries that are not design-system
  adoption, so apps that must never depend on Hyle (Personal-Tracker D-L: Animalcules, Clackpad) can still take
  them (`README.md`, "Why this repo exists"). Six independently versioned coordinates under `dev.aarso`:
  `search-core` 0.2.0, `search-testkit` 0.1.0 (empty), `crash-recovery` 1.4.0, `cell-shell` 0.1.0,
  `feedback` 0.1.0, `evidence-schema` 0.1.0. Gradle root project `AsocSharedLibraries` (`settings.gradle.kts`).
- **State.** Active: 31 commits, 2026-08-02 to 2026-09-12 (`git log --format=%ad --date=short`). Live
  consumers pin it as a submodule and `includeBuild`: Foto-Xplorr (`shared-libraries` @ `396cbbb`) and
  Fyl-Manager (@ `7a6ff2d`) both depend on `dev.aarso:crash-recovery:1.4.0` and `dev.aarso:cell-shell:0.1.0`.
  `search-core` and `feedback` have no consumer on disk (the repo reader's sweep of the constellation
  checkouts; I re-checked only Foto-Xplorr and Fyl-Manager). Targets today: Android library (AAR) and plain JVM only.
  No KMP plugin, no desktop, iOS, Windows, macOS or Ubuntu Touch artefact of any kind.
- **Stack.** Kotlin 2.1.0, AGP 8.9.1, Gradle wrapper 8.14.3, JDK 17 toolchain (foojay resolver), Gradle Kotlin
  DSL, JUnit 4.13.2, `maven-publish` per module (`publishToMavenLocal`); consumed by dependency substitution,
  no registry (D-A). UI: Compose foundation, animation and ui only in `:cell-shell` (BOM 2025.05.01, lifecycle
  2.8.7); plain `android.widget` in `:crash-recovery`; none elsewhere. Native dependencies: none. Licence:
  Apache-2.0 for the whole repo (`LICENSE`), no SPDX headers. CI: `.github/workflows/ci.yml` (all module test
  tasks plus `assembleRelease` for the three AARs) and `cleanup-artifacts.yml` (every six hours).
- **Size (measured 2026-10-06).** 48 Kotlin files, 11,709 lines (25 main, 23 test), 362 `@Test` methods
  (search-core 278, cell-shell 54, crash-recovery 22, feedback 6, evidence-schema 2). Commands:
  `find . -name '*.kt' -not -path './.git/*' | wc -l`; `... -print0 | xargs -0 cat | wc -l`;
  `grep -r '@Test' <module>/src/test | wc -l`. Every test is host-JVM: no Robolectric, no instrumentation.

## 2. Portable core vs platform-bound layers

| Module / file | Role | Portability | Main LOC | Notes |
|---|---|---|---|---|
| `search-core/` (12 files) | Query parser, matcher, ranking, text folding, relative dates, scope stack | pure Kotlin on the JDK; not common Kotlin | 3,192 | Zero `android.*`, no coroutines, no storage. JDK-only: `java.text.BreakIterator` (`Segmenter.kt:74`, already behind `Segmenter.BoundarySource`), `java.text.Normalizer` NFKC (`Text.kt`), `java.time` (`RelativeDate.kt`, `QueryParser.kt:63`, `NaturalQuery.kt:70`), `java.util.Locale`, `PatternSyntaxException` (`Matcher.kt:3`). Runs on any desktop JDK 17 as it stands. |
| `search-testkit/` | Declared: conformance fixtures for the search contracts | nothing to port | 0 | `build.gradle.kts` only; no sources, no tests. CI runs it vacuously. README presents it as real (a README/CI inaccuracy, §8 item 8). |
| `evidence-schema/` | Two JSON Schemas (draft 2020-12), `evidence/v1/` | data is platform-neutral | 0 Kotlin | Packaged as a JVM resources jar; one string-check test. No `maven-publish` in its build file. Only the packaging is JVM-specific. |
| `crash-recovery/.../CrashReport.kt` | Report format, headline, plain-language text, encode/decode, preview-safety | pure JVM, not common Kotlin | 450 | No `android.*` import. JVM-only: `SimpleDateFormat` at lines 336, 436, 443; `printStackTrace(PrintWriter)` at line 130. Pinned by `CrashReportTest`. This is the part worth sharing. |
| `CrashRecoveryLook.kt`, `CrashRecoveryStyle.kt` | Scale constants; palette | common-ready | 138 + 87 | Style has one `android.graphics.Color.parseColor` (line 85). |
| `CrashRecovery.kt` | Handler install, capture, streak, `captureExitDeath`, `previewIntent` | android-bound | 278 | `Application`, `filesDir`, `PackageManager`, `ActivityManager`, `ApplicationExitInfo`, `Log`. |
| `CrashRecoveryActivity.kt`, manifest, `res/drawable` | The recovery screen | android-bound | 968 | 32 `android.*` imports; Views on a bare `Activity` by doctrine. |
| `cell-shell/` motion and layout (4 files) | `SpatialMotion`, `SpatialShell`, `WordWheelRail`, `EdgeTimelineScrubber` | Compose, common by import path | 1,939 | Every Compose API is believed to exist in Compose Multiplatform common except `systemGestureExclusion` (`SpatialShell.kt:463`, `EdgeTimelineScrubber.kt:329`). Checked by import path only; Compose Multiplatform is not on this repo's classpath. |
| `cell-shell/.../ShakeToRefresh.kt` | Accelerometer peak-train refresh | android-bound | 143 | The only `android.*` code in `cell-shell` (`SensorManager`, `SystemClock`, `LocalContext`). `ShakePeakTrain` (line 101) is pure and takes `nowMillis`. |
| `feedback/.../FeedbackDraft.kt` | Pure, deterministic draft render | common Kotlin as written | 56 | The share payload is this text. |
| `FeedbackOptIn.kt`, `FeedbackShare.kt` | Opt-in ledger; share chooser | android-bound | 49 + 26 | Two seams: `getSharedPreferences` (line 16), `Intent.ACTION_SEND` chooser (lines 19-24). |

Platform-bound APIs that matter:

| API | Where | Porting impact |
|---|---|---|
| `ApplicationExitInfo` (native crash, ANR; API 30+) | `CrashRecovery.kt` `captureExitDeath`; `CrashReport.ofExitDeath` | No equivalent on desktop JVM, iOS or Ubuntu Touch. Becomes an `androidMain`-only capability; a documented feature gap elsewhere. |
| Uncaught-exception handler, `filesDir`, device-info block | `CrashRecovery.kt` | `Thread.setDefaultUncaughtExceptionHandler` is a plain JDK call (not exercised off the Android path here). App data dir, version and memory need an injected provider. iOS needs `setUnhandledExceptionHook` (sees Kotlin exceptions only; not Swift traps, signals or jetsam kills). |
| Reset (`clearApplicationUserData`), relaunch, clipboard, share/mail | `CrashRecoveryActivity.kt` | Each is an OS service. Desktop: delete data dir, `ProcessBuilder` relaunch, AWT clipboard, mail via `Desktop` or `xdg-open`. iOS: `UIActivityViewController`, `UIPasteboard`, no programmatic relaunch. |
| `SensorManager` accelerometer | `ShakeToRefresh.kt` | `expect`/`actual` source: Android as today, iOS CoreMotion, desktop no-op. Pull-to-refresh is retired by owner direction (2026-08-05), so desktop then has no refresh gesture: an owner decision (§8 item 3). |
| `systemGestureExclusion` | `SpatialShell.kt:463`, `EdgeTimelineScrubber.kt:329` | `expect` modifier, non-Android actuals are identity. Consequence: the four-edge pattern competes with OS edge gestures on iOS and Ubuntu Touch (§4). |
| `SharedPreferences`; `Intent` chooser | `feedback` | Opt-in store: `java.util.prefs` or a file on desktop, `NSUserDefaults` on iOS. Delivery stays user-launched. |
| `BreakIterator`, NFKC, `java.time`, `Locale` in public signatures | `search-core` | None for JVM desktop. For iOS: `BoundarySource` actual, NFKC actual, `kotlinx-datetime`. `ZoneId` and `Locale` appear in public signatures, so the commonMain pass changes public API (§6 S2.2). |

## 3. Binding rules this port must not break

- **No telemetry, no network, ever** in `:feedback` (`settings.gradle.kts`: "it cannot send anything itself";
  `feedback/build.gradle.kts`: "no network permission to even ask for, no queue, no retry, no identifier") and `:crash-recovery`
  (`CrashRecovery.kt` KDoc: "nothing here is ever sent anywhere"). Any actual keeps delivery user-launched and the
  payload fully visible; program directive I-1.
- **Apache-2.0** for the whole repo (`LICENSE`). No GPL code is linked (program I-11) and nothing a port adds may
  carry a licence incompatible with Apache-2.0; dependency licences are recorded as they are added.
- **D-A:** git submodule + `includeBuild` with dependency substitution on `group:name` is the one sharing
  mechanism; declared versions are documentation, not enforcement (`README.md`, "Versioning"); Maven publishing is a
  later, owner-gated upgrade. Non-Gradle sharing has no ruled mechanism yet (program I-6, OQ-24).
- **D-Q AGP lockstep:** every `includeBuild` consumer pins AGP 8.9.1 and Gradle >= 8.14.3. `gradle/libs.versions.toml`:
  AGP and Kotlin are "Pinned to match mbaliga/Hyle-Design-System exactly ... Do not bump either independently of Hyle".
  Adding `kotlin("multiplatform")` with an Android target keeps the lockstep for every consumer.
- **D-L and D-O: zero Hyle dependency in every module**, structural not stylistic. A port introduces neither
  `dev.aarso:hyle` nor Material.
- **Zero-dependency recovery screen** (D-O; `crash-recovery/build.gradle.kts` 1.4.0 note: "not negotiable"): the
  recovery screen must not depend on the UI stack that may have crashed. This governs every platform's screen.
- **D-P:** a crash-recovery claim "isn't real until it's been watched recovering from an actual crash on a real
  device". Environment honesty is constellation-wide (program I-4): no device claim from hosted CI.
- **cell-shell motion constants are a contract** (`SpatialMotion.kt` KDoc: "an app that quietly tunes these has
  broken the thing this module exists to guarantee"; `SpatialMotionTest`). Pull-to-refresh retired, shake replaces it;
  "no instructional copy may sit in a gesture space" (`ShakeToRefreshTest`). Compose foundation, animation and ui
  only, no material3; lifecycle is the single permitted non-Compose dependency (`cell-shell/build.gradle.kts`).
- **search-core constraints:** no Android, no coroutines, no storage engine; no runtime probing for better
  implementations ("Here the choice is the host's, made once at wiring time", `Segmenter.kt`); the `startOriginal`/`endOriginal` offset contract and the canonical
  ordering score desc, timestamp desc, id asc (`Scoring.kt`) must not change.
- **Independent versioning:** "a search change must not force a crash-recovery bump". Keep the submodule pin and
  the declared version honest with each other.
- **CI contract (`ci.yml`):** JVM tests gate every push and the three AARs must `assembleRelease` ("guards the
  composite-build contract every consumer relies on"). A port keeps that path green and adds new workflow files only.
- **Program rules R1 to R4, R6, R11:** disjoint directories or source sets; the existing gate stays green; new CI
  files only, SHA-pinned actions, a Windows-checkout path lint before any `windows-*` lane; pure core first;
  nothing released, nothing stored on PRs (Actions artifact storage is exhausted); no per-platform identifier before a
  NAMES.md row.
- **Colour never carries meaning alone** (the owner is red-green colourblind). This neutral repo is not named in
  OQ-26; any new window or QML page applies the rule by default (shape and word with colour). Confirm in §8 item 15.

## 4. Target matrix (owner's order)

| Target | Feasibility | Approach | Blockers | Effort (eng-weeks, estimate) | Evidence today |
|---|---|---|---|---|---|
| Ubuntu Touch | reframe: specifications the QML apps implement; no Kotlin reuse | Publish four specs with exported test vectors under `specs/`: cell-shell motion contract; CrashReport format plus the five PREVIEW safety properties; FeedbackDraft render and subject, with content-hub as the share channel; evidence-schema consumed as raw JSON. A JVM-cored click (F7) may take `crash-recovery`'s headless capture path. Optional, not counted: `search-core` as a Kotlin/Native `linuxArm64` shared library for a Qt plugin | No JVM or Compose in a Click app; Lomiri owns the left, right and top edges (`ASSUMPTION` carried from the repo reader, not sourced in the program's UT brief: confirm at S-UT1), so the four-edge rooms pattern cannot be reproduced; no `ApplicationExitInfo` analogue; AppArmor confines report location and sharing (content-hub only); no UT device on record (OQ-1); owner acceptance of spec-only reuse (§8 item 5) | 2 (specs and fixtures). The native-library experiment is unestimated, likely 4+ | PLAN |
| Linux desktop | moderate | `search-core` and `evidence-schema` run unchanged on JDK 17. `feedback`, `crash-recovery`, `cell-shell` become `kotlin("multiplatform")` with `androidTarget()` plus `jvm()`: `ShakeToRefresh` and `systemGestureExclusion` behind `expect`/`actual` (desktop actuals no-op); crash-recovery gets a headless `jvmMain` capture path plus a Swing/AWT recovery window (JDK toolkit only, no Compose, honouring D-O); feedback gets `java.util.prefs`/file opt-in and mail/clipboard share. Android code stays as it is. Packaging is each consumer's job (this repo ships source via `includeBuild`) | Owner approval of the conversion (§8 item 1) and the Kotlin pin for `cell-shell` (OQ-17); recovery-screen toolkit (OQ-27); F2 waits on PT:D-P device verification; no accelerometer and no share sheet, so refresh and feedback delivery need owner-decided replacements; rooms pattern on a resizable mouse window is an open UX question (Clavis D-10 chose dock and rail); `ci.yml` task names may change under KMP (§6 S1.4) | 5 (about 0.5 JVM CI legs, 0.5 to 1 feedback, 2 cell-shell, 2 crash-recovery) | PLAN |
| iOS / iPadOS | moderate | Add `iosArm64` and `iosSimulatorArm64` to the converted modules. cell-shell under Compose Multiplatform iOS with CoreMotion shake and a no-op exclusion; feedback with `NSUserDefaults` and `UIActivityViewController`; crash-recovery `iosMain` writes the common report from `setUnhandledExceptionHook` and exposes the "show on next launch" decision, while the screen is a UIKit/SwiftUI view owned by the consumer (reference view in F10's shell template); `search-core` gets the true commonMain pass (`kotlinx-datetime`, NFKC actual, `BoundarySource` actual via CFStringTokenizer or `NSString` word enumeration). `evidence-schema`: raw JSON in the bundle | macOS build host and Xcode (none here; a `macos-latest` runner and owner-held signing); the CMP-iOS recipe must first be proven in the simulator on Clavis; iOS capture sees uncaught Kotlin exceptions only; left-edge and bottom-edge system gestures may compete with the LEFT and BOTTOM rooms (depends on the host shell; unknown until a device); `search-core`'s 278 tests need `kotlin.test` to run on the simulator; no iOS consumer exists, so on-device behaviour is NDV (OQ-2 for the iPad) | 7 (about 2-3 search-core, 1 cell-shell, 2 crash-recovery, 0.5 feedback, 0.5 CI) | PLAN |
| macOS | straight | The same `jvm()` artefacts; delta is a `macos-latest` lane running JVM tests and the desktop actuals (AWT clipboard, mail, Swing window construction, data-dir resolution). Native `macosArm64` is not recommended for libraries at this stage | Same Kotlin and CMP gate as Linux; signing and notarisation are consumer-owned; no Mac on record, so device checks are NOV (OQ-5) | 1 | PLAN |
| Windows | straight | The same `jvm()` artefacts; delta is a `windows-2025` lane after the path lint (R3), checking `%APPDATA%`-style data-dir resolution, the Swing window and `ProcessBuilder` relaunch, and the mail/clipboard fallback. `gradlew.bat` is already checked in | Same Kotlin and CMP gate; `mailto:` needs a registered mail client (clipboard fallback is in the doctrine's spirit); path lint and `-text` on byte-exact fixtures first; signing is consumer-owned | 1 | PLAN |

Per-module reach (what each target would get). Effort is not repeated here; the §4 figures are the program's.

| Module | Ubuntu Touch | Linux, macOS, Windows (JVM) | iOS / iPadOS |
|---|---|---|---|
| `search-core` | none directly; usable inside F7's headless JVM core (S-UT1 pending) | native-fit, unchanged | needs the commonMain pass |
| `evidence-schema` | raw JSON | raw JSON or resources jar | raw JSON in the bundle |
| `crash-recovery` | spec plus headless capture path; page is QML | `jvmMain` handler plus Swing window | `iosMain` hook; consumer-owned view |
| `cell-shell` | spec only | CMP `jvm()` actuals | CMP iOS actuals |
| `feedback` | spec (draft format) plus QML content-hub | `jvmMain` actuals | `iosMain` actuals |

The five rows sum to 16 engineer-weeks across the five targets, overlapping: macOS and Windows ride the Linux
work. These are the program's estimates (`Personal-Tracker/PORTING_PROGRAM.md` §5) and are not verified. The
foundation items hosted here beyond the five existing modules (F4 to F11, §7) are not added to them; the program
puts F7 to F12 together at about 10 to 15 engineer-weeks program-wide, outside any row.

## 5. Tier and sequencing

**Tier G (gate): shared infrastructure.** The repo has no end-user value of its own and cannot be a flagship, but it
is a hard prerequisite: Foto-Xplorr and Fyl-Manager build their navigation on `cell-shell` and their reliability on
`crash-recovery`; Clavis (already a KMP and Compose Multiplatform app) declined `cell-shell` in Clavis D-10
("reopen if cell-shell gains a KMP target") and takes `crash-recovery` and `feedback` on Android only (D-11). Convert once (Android plus JVM
first, iOS second) rather than per platform, because the Android-bound surface is small and isolated (five files; two
seams in `cell-shell`, two in `feedback`, a clean pure/platform split in `crash-recovery`). Ubuntu Touch is a
reframe whatever the tier. **Repo gate before any wave** (program §5): owner approval for `kotlin("multiplatform")`
(a new PT:DECISIONS entry, PD-1 below) and the Kotlin bump (OQ-17); F2 after PT:D-P's device verification (OQ-27).

| Wave (program §7) | What this repo contributes | Repo-local gate before the wave starts |
|---|---|---|
| **P-0 Foundation** | F2, F3, F5, F6 (after OQ-22), F9, F10, F11 homes; F7 templates and the S-UT1 click; F8 pin if hosted here; the Windows path lint | OQ-17 ruled; PD-1 filed; F2 only after PT:D-P device verification closes (this plan cannot see whether it has); this repo's `ci.yml` green on `main` |
| **P-UT a** (web, Python, Godot clicks) | F7's webapp-container template (`ut/`); the four specs and fixtures (S0.2), which need no click | Spec-only reuse accepted (§8 item 5); a UT device or an explicit CI-only waiver (OQ-1); `NAMES.md` rows before any click id (R11) |
| **P-UT b** (JVM-cored QML clicks) | F7's jlink recipe, bridge and the single S-UT1 verdict; `crash-recovery`'s headless capture path for nooz and csapp | S-UT1 passed or waived (OQ-1); this repo's own P-LX core green (S1.2); F7 |
| **P-LX Linux desktop** | JVM legs now; feedback, crash-recovery, cell-shell conversion (S1.x). Consumers' desktop ports (Foto-Xplorr Phase 9, Fonebrew core) wait on F3 | PD-1 approved; Kotlin pin decided for `cell-shell`; consumer rehearsal on Foto-Xplorr and Fyl-Manager green (R2) |
| **P-iOS** | iOS targets on all converted modules; `search-core` commonMain; `ios.yml` | P-LX conversion merged; CMP-iOS recipe proven on Clavis in the simulator; Apple route (OQ-2) for device checks |
| **P-mac** | `desktop-macos.yml` exists from S0.1 (R10: shared work lands in the earliest wave hosted CI can verify); this wave adds the `jvm()` actuals checks of S1.4 on that lane and opens the macOS device gates | P-LX binaries; device gates stay NOV until OQ-5 |
| **P-win** | `desktop-windows.yml` exists from S0.1 after the path lint; this wave adds the `jvm()` actuals checks on that lane and the Windows device gates | Path lint merged first; device gates `CI (hosted VM)` only once the Dell leaves Windows (OQ-5) |

## 6. Work breakdown

Placement is disjoint (R1): new top-level directories `specs/`, `checklists/`, `scripts/`, `ut/`, `packaging/` and
new workflow files only (R3). Existing files that change, all only after PD-1 and each through the repo's normal PR
process (R1, since they move Android source sets): module build files, `gradle/libs.versions.toml`, root
`build.gradle.kts` (plugin declarations); in Phase 1, content-preserving `git mv` of today's `src/main` and `src/test`
sources into `commonMain` / `androidMain` / `jvmCommonMain` / their test source sets (S1.1-S1.3), plus the
import-and-annotation swap to `kotlin.test` in cell-shell tests (S1.3); in Phase 2, the source edits named in S2.2 and
S2.3; in Phase 3, `settings.gradle.kts` (new `include` lines). `ci.yml` is untouched except as §8 item 1 allows.
Actions are SHA-pinned in new workflows (the SHAs are resolved when the workflow is written; none is given here). No
new workflow uploads artifacts (R6).

### Phase 0 — additive, no existing build file touched

| Step | Work | Where | CI file | Done when |
|---|---|---|---|---|
| S0.1 | Windows-checkout path lint (`:` `<>\|?*"`, reserved names; `-text` on byte-exact fixtures), then JVM legs running the existing `:search-core:test` and `:evidence-schema:test` on `ubuntu-24.04-arm`, `macos-latest`, `windows-2025` (x86_64 Linux is already `ci.yml`'s `ubuntu-latest` job; I did not observe a run). Spike inside the step: today even those tests run in a job that installed the Android SDK, and whether this build configures without one is unverified; options are installing the SDK per OS or `--configure-on-demand` (root plugins are `apply false`); F5's conditional Android inclusion is the clean answer but edits `settings.gradle.kts` (Phase 3) | `scripts/windows-path-lint.sh`; `.github/workflows/desktop-linux.yml`, `desktop-macos.yml`, `desktop-windows.yml` | those three files | Green hosted runs on all three OSes: `CI (hosted VM)`. Not run here. |
| S0.2 | `specs/`: `cell-shell-motion.md`, `crash-report-format.md` (incl. the five PREVIEW properties from `MIGRATION.md`), `feedback-draft.md`, `evidence-schema-consumption.md`, and `specs/fixtures/*.json` taken from the pure functions' test vectors (`SpatialMotionTest`, `ScrubberMappingTest`, `CrashReportTest`, `FeedbackDraftTest`). Fixtures are golden files that new tests in each module's existing test source set read and assert against, regenerated only by an explicit opt-in switch, so the existing `ci.yml` steps already guard drift with no edit | `specs/`; new test classes beside the existing ones | none new (existing steps) | Specs reviewed by the owner; tests green. Whether a QML implementation conforms is `NOT-APPLICABLE` here and NDV on a device, owned by the consumer. |
| S0.3 | `checklists/DEVICE_CHECKLIST_{UT,LINUX,IOS,MACOS,WINDOWS}.md` templates (F11) with this repo's NDV rows (table below) | `checklists/` | none | Files exist; every row `NEEDS-DEVICE-VALIDATION`. |

### Phase 1 — Linux, macOS, Windows (gated by PD-1; the Kotlin pin gates S1.3 only)

| Step | Work | Where | Done when |
|---|---|---|---|
| S1.1 | `:feedback` to `kotlin("multiplatform")`: `FeedbackDraft` and `FeedbackFact` to `commonMain` (`git mv`); `FeedbackOptIn` and `FeedbackShare` become `expect`/`actual` (Android actual unchanged; `jvm()` actual: `java.util.prefs` or a file; mail through `Desktop` with an `xdg-open` fallback, then clipboard). Never silently truncate: a payload too long for a `mailto:` body goes to clipboard or a saved file and the UI says so. Keep an Android-only `FeedbackOptIn(context)` constructor (no adopter yet, so source compatibility is cheap) | `feedback/src/{commonMain,androidMain,jvmMain}` | `FeedbackDraftTest` green on `jvm()` and Android unit; `:feedback:assembleRelease` green |
| S1.2 | `:crash-recovery` to KMP. Route: an intermediate `jvmCommon` source set shared by `androidMain` and `jvmMain` holds `CrashReport` unchanged (it is JVM-only), so the D-P-gated Android behaviour is not rewritten; `CrashRecoveryLook`/`Style` to `commonMain` (`Color.parseColor` stays as the Android actual, or a common hex parser must accept everything callers pass today). `androidMain` keeps `CrashRecovery`, `CrashRecoveryActivity`, manifest, drawables as they are. `jvmMain`: headless `install`/capture/pending-report decision taking the app directory as a plain parameter (no dependency on F6, preserving D-O), and a Swing/AWT window in a separate package that the headless path never references (check with `jdeps` so a jlinked headless JVM can omit `java.desktop`). Window parity: Share/Copy/Continue/Reset-with-confirm/collapsed trace, all five PREVIEW properties, headless fallback (print the report path to stderr) | `crash-recovery/src/{commonMain,jvmCommonMain,androidMain,jvmMain}` | `CrashReportTest` and `PillColorsTest` unchanged and green on `jvm()` and Android unit; window construction smoke test under Xvfb on Linux: `CI (hosted VM)` for "constructs", NDV for look and behaviour |
| S1.3 | `:cell-shell` to Compose Multiplatform. Needs Kotlin >= 2.1.20 and CMP 1.8.x per Clavis D-2 (OQ-17). `commonMain` holds the four layout files and `ShakePeakTrain`; `expect` modifier for gesture exclusion (Android actual = `systemGestureExclusion`, others identity); `expect` accelerometer source (Android as today, desktop no-op). Public API of 0.1.0 unchanged: consumers need no import edit. Test migration to `kotlin.test` is import and annotation only; any changed expected value is a contract break and is rejected | `cell-shell/src/{commonMain,androidMain,jvmMain}` | 54 tests green on `jvm()` and Android unit with assertions byte-identical; release AAR still assembles |
| S1.4 | Keep the gate green without editing `ci.yml` (R3). Task names change under KMP (`:search-core:test` style names are not guaranteed to exist), and each module's `afterEvaluate` `MavenPublication("release")` block would collide with KMP's own publications. Add alias tasks and rewrite the publishing blocks in the module files; if aliasing is impossible, a one-line `ci.yml` edit is a decision for the owner (§8 item 1). Extend the three `desktop-*.yml` files with `jvmTest` of the converted modules | module `build.gradle.kts` files; `desktop-*.yml` | Every step name in `ci.yml` still resolves and passes; the three desktop lanes green: `CI (hosted VM)` |
| S1.5 | Rehearsal against the two live consumers (Foto-Xplorr @ `396cbbb`, Fyl-Manager @ `7a6ff2d`): each builds with the converted submodule pin (variant resolution of a KMP library from a plain Android module through `includeBuild`; a Kotlin mismatch across the composite (README's AGP table lists Fyl-Manager on Kotlin 2.1.20 against this repo's 2.1.0, flagged only for its missing wrapper); consumer targets named `desktop` matching `jvm()` by platform type). All unverified. Outside this container's reach | consumer CI | Both consumers' own gates green before the pin moves (R2) |

### Phase 2 — iOS / iPadOS (after Phase 1 merges and the CMP-iOS recipe is proven on Clavis)

| Step | Work | Done when |
|---|---|---|
| S2.1 | Add `iosArm64` and `iosSimulatorArm64` to `feedback`, `crash-recovery`, `cell-shell`. `iosMain`: `NSUserDefaults` and `UIActivityViewController`; CoreMotion shake and no-op exclusion (UIKit screen-edge gesture deferral is a candidate, unverified); `setUnhandledExceptionHook` writing the report under Application Support, plus the pending-report decision. Reference SwiftUI recovery view lives in F10's shell template, not as a module dependency | `ios.yml` on `macos-latest`: compile and `iosSimulatorArm64Test`: `SIMULATOR` for what runs there. Cannot run in this container. |
| S2.2 | `:search-core` to KMP `commonMain` with `jvm()` retained: `expect`/`actual` NFKC; `BoundarySource` actual; `kotlinx-datetime` for `RelativeDate`, `QueryParser`, `NaturalQuery`. `ZoneId` and `java.util.Locale` are in public signatures, so this is an API change; options are `expect class` with a JVM `actual typealias` (source-compatible, unverified) or a version bump. Cheapest before the first adopter (§8 item 7). `RelativeDateTest`'s "different zones, same instant" and `RankingGoldenTest` must stay green; tests needing JDK-only calls (`Locale.setDefault` in `NormalizerTest`) stay in `jvmTest` | Same assertions green on `jvm()` and the simulator |
| S2.3 | `CrashReport` to true `commonMain`: replace the three `SimpleDateFormat` uses and `PrintWriter` with a common date formatter and `stackTraceToString()`; the retained Android and JVM tests prove no output drift | `CrashReportTest` unchanged and green on all three targets |

### Phase 3 — foundation additions (proposals; each needs its own owner step)

`kmp-conventions/` (F5, an included build; delivery through `pluginManagement { includeBuild }` is unverified),
`platform-ports/` (F6 module, after OQ-22; `crash-recovery` and `feedback` do not depend on it), `ut/` (F7, after
OQ-1 and OQ-24), `packaging/` (F10), `.github/workflows/kmp-matrix.yml` (F9, `workflow_call`; the desktop and iOS
files above become its first callers), `native-engines/` (F8, only if the owner picks this repo as its home).
`asom-client-kmp/` (F4) is not scheduled: it waits on asom's own rulings (OQ-7d, program I-9).

NDV rows this repo creates for the device checklists (S0.3), none verified:

| Item | Needs |
|---|---|
| Swing recovery window: render, Copy, mail fallback, Reset, Continue, preview safety; HiDPI | Steam Deck Desktop Mode and Redmagic-Edge (Linux); the Dell while it is Windows; macOS is NOV (no Mac, OQ-5) |
| A real crash recovered on each host (PT:D-P) | Owner on device |
| iOS: hook writes the report, consumer view appears next launch; CoreMotion shake | iPad Pro M4 (OQ-2) |
| `cell-shell` four-edge drag against iOS system gestures; desktop mouse feel | iPad; desktop session |
| `feedback` mail/`xdg-open`/clipboard per OS; QML content-hub on Ubuntu Touch | Each OS; a UT device (OQ-1) |

## 7. Shared foundation this repo consumes or provides

**Provides (homes per program §6; all proposals until the owner approves):**

| Item | What lands here | Gate |
|---|---|---|
| F2 crash-recovery KMP | S1.2, S2.1, S2.3; Android screen kept; Swing and SwiftUI screens per OQ-27 | OQ-27; after PT:D-P device verification |
| F3 cell-shell CMP | S1.3, S2.1 | OQ-17 |
| F4 asom-client | Unscheduled; separate coordinate so `feedback` and `crash-recovery` consumers never inherit a network permission | OQ-7d; asom rulings |
| F5 kmp-conventions | `kmp-conventions/` | OQ-17 |
| F6 platform-ports | `platform-ports/` | OQ-22 |
| F7 ubuntu-touch-shell | `ut/` | OQ-1, OQ-24 |
| F8 native-engines pin | Home undecided (here or a dedicated repo; not asom) | OQ-24 |
| F9 CI matrix | `kmp-matrix.yml` | OQ-20 |
| F10 packaging templates | `packaging/` | OQ-24 |
| F11 evidence and checklists | `checklists/`; a `platform-evidence/1` record placed beside, not inside, the existing `evidence/v1/` data schemas | none; owner confirms placement (§8 item 9) |

**Consumes:** the OQ-17 toolchain pin and the Hyle lockstep (no Hyle code, no Hyle dependency); NAMES.md rows
before any identifier (R11); Personal-Tracker DECISIONS entries for PD-1 to PD-4; CI policy from OQ-20 (this repo is
public, so hosted lanes are free here, which is why it can host the cross-OS proof private consumers cannot run).

**Who needs it from here:** Foto-Xplorr Phase 9 and Fonebrew core (F3); Foto-Xplorr, Fylz (Android), Fonebrew,
Clavis and BOS (F2); nooz and csapp via F7; Fylz gets the QML specs only; Clavis takes `cell-shell` nowhere (D-10, D-11).

**Proposed decisions (PROPOSALS for the owner; ids unassigned, filed in `Personal-Tracker/DECISIONS.md`):**
- **PD-1.** Convert `:feedback`, `:crash-recovery`, `:cell-shell` to `kotlin("multiplatform")` (`androidTarget()` plus `jvm()`, iOS later); `:search-core` stays plain JVM until the iOS wave; `:evidence-schema` stays a resources jar. The AGP lockstep (D-Q) is unchanged.
- **PD-2.** This repo and Hyle-Design-System move to a new Kotlin pin in one step; this repo never leads (the catalog's own rule). The plan is written to hold under any of OQ-17's Options A, B or C and does not choose. Repo-local effect: only `cell-shell` needs Compose Multiplatform. Option B (Kotlin 2.1.20, CMP 1.8.x) is the set Clavis already uses. Option A's AGP 9.4.1 would move the D-Q pin for every consumer, and whether `androidTarget()` is declared the same under AGP 9.x is unknown here.
- **PD-3.** Recovery-screen toolkit: Android views unchanged; Swing/AWT on JVM; consumer-owned UIKit/SwiftUI on iOS; QML on Ubuntu Touch from the spec; never Compose.
- **PD-4.** Spec-only reuse is the sanctioned Ubuntu Touch contribution of this repo (`specs/` with golden fixtures).

## 8. Open questions for the owner

1. **Approve the KMP conversion (PD-1)?** It changes the build shape every consumer composites and keeps the AGP lockstep. Also: if KMP renames the test tasks `ci.yml` calls and aliases cannot cover it, may a one-line `ci.yml` change be made? Blocks: S1.1 to S1.5, F2, F3, F5, every consumer's desktop or iOS port. (Program OQ-17, second half.)
2. **Kotlin pin (OQ-17):** does Hyle move with this repo, and to which option? Blocks: `cell-shell` CMP (S1.3), F3, F5.
3. **cell-shell off Android** (repo-local; no program OQ id): keep the four-edge rooms verbatim ("followed everywhere") although iOS and Lomiri compete for edges, or adapt per platform (Clavis chose dock and rail)? What replaces shake-to-refresh where there is no accelerometer, given pull-to-refresh is retired? Blocks: the UX of S1.3 and S2.1 actuals, not their compile.
4. **Recovery-screen toolkit (OQ-27, PD-3):** Swing/AWT and consumer-owned SwiftUI, or Compose (simpler, but the dependency class D-O rejected)? Is losing `ApplicationExitInfo` native-crash and ANR detection off Android acceptable? Is the iOS guarantee (uncaught Kotlin exceptions only) acceptable? On desktop, show the window immediately or only on next launch (behaviour of exceptions on the AWT event thread is unverified)? Blocks: F2, S1.2, S2.1.
5. **Ubuntu Touch:** accept spec-only reuse, or fund the Kotlin/Native `linuxArm64` experiment? Which UT device will you own (OQ-1)? Does Waydroid (OQ-21) make UT work for consumers moot? Blocks: S0.2 sign-off, F7's S-UT1.
6. **F2 timing:** has PT:D-P's device verification closed? The repo reader found BOS_launcher, hnm_playground, Form-analyser and Animalcules without crash-recovery wiring on their `main`. Blocks: F2 (OQ-27 sequencing).
7. **`search-core` first host:** it has zero adopters on disk (Android-IDE-core still keeps its own copy). Which ported app adopts it first, and should the commonMain pass (a public-API change) land before that adoption? Blocks: S2.2.
8. **`search-testkit` is empty** but listed by README and run by CI. Implement it (a natural home for cross-platform conformance fixtures) or delete the row and module before the porting branch is cut? Blocks: the README accuracy fix; S0.2 fixture home.
9. **`evidence-schema` off the JVM:** do Baseline, Crocodyl and Ebbflow consume the raw JSON from the submodule, or do you want generated Swift or C++ types beside the schemas? May F11's `platform-evidence/1` record sit in this module, in its own path? Blocks: iOS and UT consumption note; F11.
10. **Clackpad pin `8200557`** is not on `main` or any fetched ref here, and expects a plugin-free `word-graph/` asset package. Is it an unmerged branch that must land first (a Gradle-free sharing shape relevant to UT and Xcode consumers), or stale? Blocks: the non-Gradle sharing recipe (OQ-24).
11. **Sharing for non-Gradle artefacts and prebuilt binaries (OQ-24)** and **identifiers (OQ-25).** Blocks: F7, F8, F9, F10; any click or bundle id.
12. **Android-IDE-core** still consumes crash-recovery 1.0.0 from the old Hyle submodule (`core-engine/build.gradle.kts:154`; `MIGRATION.md` unapplied; Studio inherits via `includeBuild("core")`). Land that before the KMP rework so it is not carried on two coordinates? Blocks: F2 consumer set.
13. **nooz** carries its own `CrashRecovery` copy. Fold it in during the port or leave it? Blocks: nothing here; F2 adoption list.
14. **CI visibility and minutes (OQ-20):** keep this repo public so the five-OS lanes stay free? Secret custody (OQ-22) for F6, and Apple and Mac availability (OQ-2, OQ-5) for device checks. Blocks: S0.1, S2.1 lanes; F6; macOS and iOS device gates.
15. **Colour rule scope (OQ-26 does not name this repo):** confirm the violet/cyan-plus-shape rule applies to this neutral repo's new windows and pages. Blocks: S1.2 window design.
16. **Registry:** `Personal-Tracker/CONSTELLATION.md` (last updated 2026-07-01) has no row for this repo. Will you add it, or should a later change? This plan edits nothing there.

## 9. Sources read

In this repo: `README.md`, `MIGRATION.md`, `LICENSE`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/ci.yml`,
`.github/workflows/cleanup-artifacts.yml`; `search-core/build.gradle.kts` and `src/main/kotlin/dev/aarso/search/`
(`Segmenter.kt`, `Text.kt`, `RelativeDate.kt`, `QueryParser.kt`, `NaturalQuery.kt`, `Matcher.kt`, `Model.kt`);
`search-testkit/build.gradle.kts`; `crash-recovery/build.gradle.kts`, `src/main/AndroidManifest.xml`,
`src/main/java/dev/aarso/crashrecovery/` (`CrashRecovery.kt`, `CrashRecoveryActivity.kt`, `CrashReport.kt`,
`CrashRecoveryStyle.kt`, `CrashRecoveryLook.kt`), `src/test/.../CrashReportTest.kt`; `cell-shell/build.gradle.kts`,
`src/main/kotlin/dev/aarso/cellshell/` (`SpatialMotion.kt`, `SpatialShell.kt`, `ShakeToRefresh.kt`,
`EdgeTimelineScrubber.kt`, `WordWheelRail.kt`) and the six test files; `feedback/build.gradle.kts`,
`src/main/kotlin/dev/aarso/feedback/` (`FeedbackDraft.kt`, `FeedbackOptIn.kt`, `FeedbackShare.kt`),
`FeedbackDraftTest.kt`; `evidence-schema/build.gradle.kts`, `src/main/resources/evidence/v1/*.schema.json`,
`EvidenceSchemaTest.kt`.

Outside this repo (read by the repo reader on 2026-10-06; DECISIONS, Clavis and program text re-checked while
writing): `Personal-Tracker/DECISIONS.md` (D-A, D-L, D-O, D-P, D-Q), `STATE.md`, `NAMES.md`, `CONSTELLATION.md`,
`PORTING_PROGRAM.md` and `porting/platforms/*.md`; `Clavis/docs/DECISIONS.md` (D-2, D-10, D-11, D-13),
`docs/build-platform.md`, `docs/10-build-plan.md`, `shared/build.gradle.kts`, `composeApp/build.gradle.kts`,
`settings.gradle.kts`; `Foto-Xplorr` and `Fyl-Manager` (`settings.gradle.kts`, `app/build.gradle.kts`,
`.gitmodules`); `Clackpad` (`.gitmodules`, `clackpad/app/build.gradle.kts`); `Android-IDE-core`
(`core-engine/build.gradle.kts`, `settings.gradle.kts`); `Android-IDE-Studio/settings.gradle.kts`;
`Hyle-Design-System/README.md` (crash-recovery section); `nooz/app/src/main/kotlin/xyz/mdhv/riverwip/RiverApplication.kt`.
