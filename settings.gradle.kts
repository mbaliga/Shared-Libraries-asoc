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

// On-device evidence collection across all seven app types in the constellation (ui / ime /
// wallpaper / audio / vision-pipeline / stream / service) — timing, memory, thermal, start-up,
// environment facts, and structural invariants, exported to one shareable Markdown report. Same
// zero-Compose, zero-Material posture as :crash-recovery, for the same reason (D-L apps).
// See docs/DIAGNOSTICS_MODULE_SPEC.md.
include(":diagnostics-core")
include(":diagnostics-android")
include(":diagnostics-overlay")
include(":diagnostics-noop")
