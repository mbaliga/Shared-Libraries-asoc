# kmp-conventions - the ruled KMP pin sets and the Apple-target guard (F5)

PORTING_PLAN.md F5 / Phase 3. **Status: the conventions ship; the conversions wait.**

## Read this first: what is and is not allowed by this directory

**Applying these conventions to `:hyle`, `:cell-shell` or `:crash-recovery` (or `:feedback`) is
BLOCKED on the owner's OQ-17 "Also" approvals** - converting those modules to
`kotlin("multiplatform")` (PORTING_PLAN.md PD-1) is an open owner decision that the OQ-17 toolchain
ruling did **not** answer. The ruling cleared the *pin*, not the *conversion*. Until the owner
approves:

- nothing in this repository applies these catalogs, and no module build file, `settings.gradle.kts`,
  `gradle/libs.versions.toml` or root `build.gradle.kts` was changed to reference this directory;
- this repository's own Kotlin stays at **2.1.0** (it moves only together with Hyle, D-Q lockstep;
  the program's do-not-regress pins - Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / Room 2.7.1 / SQLDelight 2.1.0 /
  Compose BoM 2025.05.01 / llama.cpp 5343f450 - are untouched);
- `verify-pins.py` runs a **tripwire** that fails the build if a module starts using
  `multiplatform`, or if anything outside this directory mentions `kmpPins` / `kmp-conventions`. The
  failure is deliberate: whoever has the approval removes or relaxes that tripwire in the same change
  that cites it.

Also not decided here, because it is not this directory's to decide: **which pin set a given repo
uses.** OQ-38 moves "a repo that ships an iOS target" to the iOS set as a whole (a Gradle build has
one Kotlin version); that reading is not researched, and the bump cost for repos pinned lower is in
no figure. This repository moves only with Hyle (PD-2).

## What is here

| Path | What |
|---|---|
| `catalogs/kmp-non-ios.versions.toml` | **OQ-17 option B (2026-10-06):** Kotlin **2.1.20**, Compose Multiplatform **1.8.2**; 2.4.x deferred. For repos that do not ship an iOS target |
| `catalogs/kmp-ios.versions.toml` | **OQ-38 (2026-10-07):** Kotlin **2.2.21**, Compose Multiplatform **1.9.3**, first iOS proof on an explicitly selected **Xcode 26.x** (`xcode-major = "26"`, informational). For repos that ship an iOS target |
| `verify-pins.py` | Asserts the catalogs equal those rulings exactly (no extra pin may slip in; `--self-test` breaks them one way at a time), that the README snippet matches the harness, and the tripwires above |
| `harness/` | A **standalone** Gradle build (not included by the root settings) that proves a pin set resolves and the convention below configures and tests cleanly on the host. `harness/run.sh` |

Deliberately **not** in the catalogs: AGP, Gradle and JDK. They are carried by the D-Q lockstep (AGP
8.9.1, Gradle >= 8.14.3, JDK 17 toolchain) and are not part of either ruling.

## Using a catalog (when a repo has the approval)

In the consumer's `settings.gradle.kts`, next to its own default catalog:

```kotlin
dependencyResolutionManagement {
    versionCatalogs {
        create("kmpPins") {
            from(files("shared-libraries/kmp-conventions/catalogs/kmp-non-ios.versions.toml"))
        }
    }
}
```

and in a module:

```kotlin
plugins {
    alias(kmpPins.plugins.kotlin.multiplatform)
    alias(kmpPins.plugins.kotlin.plugin.compose)       // Compose Multiplatform modules only
    alias(kmpPins.plugins.compose.multiplatform)       // Compose Multiplatform modules only
}
```

A repo on the iOS set points at `kmp-ios.versions.toml` instead. The harness does exactly this for
both files.

## The Apple-target convention: declare iOS targets only on a Mac host

Linux and Windows configuration (CI lanes, this planning container) must never see Apple targets.
Wrap the declarations in a `HostManager.hostIsMac` guard, inside the module's existing `kotlin { }`
block:

<!-- BEGIN convention -->
```kotlin
import org.jetbrains.kotlin.konan.target.HostManager

kotlin {
    jvm()

    // BEGIN convention (kmp-conventions/verify-pins.py checks README.md carries these exact lines)
    if (HostManager.hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }
    // END convention
}
```
<!-- END convention -->

The three guarded lines are checked, by `verify-pins.py`, to be character-for-character the lines in
`harness/inline/build.gradle.kts`, which `harness/run.sh` configures and tests under both pin sets.

Why a guard when the unguarded form does not fail: on Kotlin 2.1.20 an unguarded declaration is only
a *configuration-time warning* ("Disabled Kotlin/Native Targets"), not an error - observed in the
KMP spike (2026-10-14) and again by the harness's `--unguarded-probe` (below). The guard silences the
warning, avoids registering dead tasks, and protects against a `commonMain` dependency that has no
Apple variants. `HostManager` is on the Kotlin Gradle plugin's own classpath, so the import resolves
wherever the multiplatform plugin does; do not replace it with an OS-name string check.

**Only the inline form works.** A first draft shipped the guard as a script plugin
(`apply(from = ".../apple-targets.gradle.kts")`). The harness showed it does not compile under
either Kotlin version: the applied script's classpath does not contain the Kotlin Gradle plugin's
types ("Unresolved reference: kotlin" on `org.jetbrains.kotlin.konan.target.HostManager`), so it was
removed rather than shipped broken. Delivering the convention as a precompiled script plugin in an
included build (`pluginManagement { includeBuild }`, the plan's F5 delivery idea) is the natural next
step and is **unverified**.

## What was run, and what it proves

All of it in the planning container: Linux x86_64, Gradle 8.14.3, foojay-provisioned Temurin 17, run
on 2026-10-14 (`harness/run.sh both --probe-compile`, network needed, a few minutes after the first
download). Evidence class `CONTAINER-BUILD-ONLY`; no Apple host and no Apple SDK was involved.

| Check | Result |
|---|---|
| `python3 -I kmp-conventions/verify-pins.py` and `--self-test` | green |
| **Non-iOS set**: Kotlin 2.1.20 + CMP 1.8.2, guarded convention | Gradle exit 0; **no** "Disabled Kotlin/Native Targets" warning; resolved Kotlin plugin == catalog (`2.1.20`); `:inline:jvmTest` ran (a `@Composable` compiled against `org.jetbrains.compose.runtime:1.8.2`) |
| **iOS set**: Kotlin 2.2.21 + CMP 1.9.3, guarded convention | Gradle exit 0; no warning; resolved Kotlin plugin == catalog (`2.2.21`); `:inline:jvmTest` ran (against `runtime:1.9.3`) |
| Unguarded probe, Kotlin 2.1.20 | exit 0 with **one** "Disabled Kotlin/Native Targets" warning (iosArm64, iosSimulatorArm64); `compileKotlinIosArm64` is **SKIPPED**. A warning, not an error - same as the KMP spike |
| Unguarded probe, Kotlin 2.2.21 | exit 0, **no warning**; `compileKotlinIosArm64` is **not skipped**: it downloaded the Kotlin/Native 2.2.21 distribution and produced a klib on Linux (`harness/unguarded/build/classes/kotlin/iosArm64/main/klib/`) |

### A finding worth the owner's attention (not acted on)

At Kotlin **2.2.21** a Linux host no longer treats Apple targets as disabled for *compilation*: it
compiled the iosArm64 klib of a pure-`commonMain` module with no Xcode. At 2.1.20 the same
declaration is skipped. Consequences, none of them verified beyond that one compile:

- The guard's original reason ("silence the disabled-targets warning") weakens on the iOS pin set;
  its remaining reasons (no dead tasks, no Apple targets in a non-Mac configuration, protection from
  `commonMain` dependencies without Apple variants) still hold, so **the guard stays the convention**.
- Compiling pure common code for iOS might therefore be checkable on a Linux runner at the iOS pin
  set. That would be a cheap pre-check, not a substitute for the macOS lane: linking, tests, the
  simulator and anything using `platform.*` (UIKit, Foundation) APIs were not tried, and the
  Apple platform libraries are not expected to be available on a Linux host.
- It is a different cost profile too: the Kotlin/Native 2.2.21 distribution is a ~0.8 GB download.

## What this does not prove (named gaps)

- **Nothing about iOS.** No Apple target was configured, compiled or tested; the guard is exactly what
  keeps them out. Real iOS compilation and tests need a macOS runner and Xcode (`SIMULATOR` evidence,
  then `NEEDS-DEVICE-VALIDATION`).
- **Android + KMP is unexercised.** The harness has no `androidTarget()`. Whether this repository's AGP
  8.9.1 (D-Q) is happy with Kotlin 2.2.21 on an Android target is **unverified**; the plan flags AGP
  9.x as a separate question (PD-2).
- **Compose Multiplatform is exercised on the JVM target only** (a `@Composable` compiles against
  `org.jetbrains.compose.runtime`; no UI runs).
- **`commonMain` dependencies without Apple variants** (the case the guard also protects against) were
  not exercised.
- **Delivery of the convention** to consumers (included build / precompiled plugin / copy-paste) is
  undecided and unverified; copy-paste of the snippet above is the only verified form.
- **Which repos take which set** is not decided (see above).
