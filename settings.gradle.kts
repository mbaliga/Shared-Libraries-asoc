pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Shared libraries for the "a system of cells" constellation.
//
// Consumed exactly like Hyle (Personal-Tracker DECISIONS.md D-A): a git submodule pinned by
// the consumer + `includeBuild("shared-libraries")`. Gradle substitutes any `dev.aarso:<name>`
// dependency with the matching project here, so consumers never need a Maven registry.
//
// AGP LOCKSTEP (D-Q): this build contains Android library modules, so its AGP version
// participates in every consumer's composite build graph. Consumers MUST pin the same AGP
// (currently 8.9.1) or Gradle hard-fails with "Using multiple versions of the Android Gradle
// plugin ... is not allowed". That is the same constraint hyle-design-system already imposes,
// so a consumer that already composites Hyle takes on nothing new here.
rootProject.name = "AsocSharedLibraries"

// Search + indexing. :search-core is deliberately plain `kotlin("jvm")` — no Android, no
// coroutines, no storage engine — so it is testable on the JVM and reusable by any host.
include(":search-core")
include(":search-testkit")

// The constellation's navigation + motion shell: the fonebrew spatial pattern, the
// word-wheel rail and the edge scrubber, so every app moves the same way rather than each
// re-deriving it (owner: "the navigation pattern of fonebrew ... followed everywhere").
include(":cell-shell")

// Reliability. Relocated from mbaliga/Hyle-Design-System, where D-O originally placed it, so
// that apps forbidden from depending on Hyle (D-L: Animalcules, Clackpad) no longer have to
// carry the entire Hyle submodule to get a utility that has zero Hyle dependency by design.
include(":crash-recovery")

// The opt-in feedback utility for experimental features. No backend and no telemetry exist in
// this constellation, so it composes a user-readable draft and hands delivery to a chooser the
// user launches — it cannot send anything itself. Zero Hyle dependency, no Compose: each app
// renders its own prompt.
include(":feedback")

// NOTE: `word-graph/` is intentionally absent from this file.
//
// It is a plain asset package (graph.html + vendored AntV G6 + WordNet-derived TSVs), not a
// Gradle project, and consumers take it by pointing an asset source-set at the directory
// rather than by `includeBuild` + a dev.aarso coordinate. That is deliberate: an Android
// library module would put THIS build's AGP into every consumer's composite build graph and
// force the lockstep above (currently 8.9.1). Clackpad, its first consumer, is pinned to AGP
// 8.13.2 — the floor for compileSdk 36, which Play requires for updates from 2026-08-31 — so
// under the lockstep it could not take this at all. Shipping no plugin sidesteps the whole
// constraint. See word-graph/README.md.
