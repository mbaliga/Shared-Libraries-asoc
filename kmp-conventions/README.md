# kmp-conventions (F5)

A Gradle convention plugin (`dev.aarso.kmp-conventions`) plus a settings plugin
(`dev.aarso.kmp-conventions.settings`) for every Kotlin Multiplatform repo in the constellation.
It is an **included build**, not a module of the root build: consumers add
`pluginManagement { includeBuild("<path>/kmp-conventions") }` (PT:D-A, directive I-6).

## What it gives you

| Piece | What |
|---|---|
| `asocTargets { android(); jvm(); ios(); wasm() }` | Opt-in target declarations. `android()` is a logged no-op unless the Android signal is set. `ios()` refuses Kotlin below 2.2.21. |
| `verifyNoAndroidImports` | Fails on any `android.*` import (or fully qualified `android.x.Y`) in every non-`android*` source set (commonMain, commonTest, jvmMain, jvmTest, ...). `androidx.*` is allowed. Comments and strings are ignored. Wired into `check`. |
| `verifyDependencyLicences` | Reads the POM (and parent chain) of each resolved component on `jvmRuntimeClasspath` etc. and fails on any licence that is not permissive (Apache, MIT, BSD, ISC, zlib, Unlicense/CC0) or is missing. GPL, LGPL, MPL, EPL, CDDL fail. `exceptions` takes `group:name` for a POM that omits its licence; each needs a written reason in the repo. Wired into `check`. Checks what Maven metadata declares; it is not a legal review. |
| `asoc.androidSdkAvailable()` / `asoc.includeIfAndroid(":app")` (settings plugin) | hnm_playground's rule: Android is on only with `ENABLE_ANDROID=1` or a `local.properties` containing `sdk.dir`. `ANDROID_HOME` is deliberately not sniffed, because hosted runners preset it. |
| `catalog/asoc.versions.toml` | One version catalog, two pin sets (below). |

## Two pin sets (OQ-17 Option B, OQ-38)

| Set | Kotlin | Compose Multiplatform | Use when |
|---|---|---|---|
| `*-classic` | 2.1.20 | 1.8.2 | the repo ships no iOS target |
| `*-ios` | 2.2.21 | 1.9.3 | the repo ships an iOS target (the whole build moves; a Gradle build has one Kotlin) |

AGP 8.9.1 is shared and stays in lockstep with Hyle (PT:D-Q). The plugin does not choose Kotlin for
you: apply `kotlin("multiplatform")` at the pinned version yourself; the plugin warns if it is neither pin.
Neither pair has been run in a real consumer yet (2.1.20 against Xcode 26 is untested).

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("shared-libraries/kmp-conventions") }
plugins { id("dev.aarso.kmp-conventions.settings") }
dependencyResolutionManagement { versionCatalogs { create("asoc") {
    from(files("shared-libraries/kmp-conventions/catalog/asoc.versions.toml")) } } }
asoc.includeIfAndroid(":androidApp")

// build.gradle.kts
plugins { alias(asoc.plugins.kotlin.multiplatform.classic); id("dev.aarso.kmp-conventions") }
asocTargets { android(); jvm() }
```

## Proof

`./verify.sh` runs the plugin's unit tests, the passing `sample/` (JVM only), and the failing
`sample-negative/` (a commonMain `import android.util.Log`, and a CDDL/GPL dependency).

## Honest limits

- Verified here: JVM only, Linux, Gradle 8.14.3, JDK 17. No Android SDK, Mac, Windows or device.
- `android()` with a real SDK, `ios()` linking and any Xcode step are **not exercised**. `ios()` and
  `wasm()` were only checked to configure (Kotlin 2.2.21, Linux), not compiled or run.
- Not included (named in PORTING_PROGRAM F5 but not built here): jpackage and XcodeGen head conventions.
- KGP is reached by reflection (`KotlinAccess`) because an included-build plugin and the consumer's
  KGP live in sibling classloaders; a future KGP that renames `getSourceSets`, `getKotlin`, `jvm`,
  `wasmJs`, `browser`, `androidTarget` or `iosX64` would break it, and the sample would show it.
- The licence check resolves POMs at execution time, so it is not configuration-cache compatible.
- No SPDX headers: Shared-Libraries uses none.
