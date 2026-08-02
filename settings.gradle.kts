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

// Shared-Libraries-asoc -- shared library modules consumed by other mbaliga/* apps via git
// submodule + Gradle includeBuild (the constellation's one sharing mechanism, Personal-Tracker
// DECISIONS.md D-A). crash-recovery is the first module: relocated from Hyle-Design-System --
// see Personal-Tracker snapshots/shared-libraries-reorg-proposal-2026-08-02.md for why, and
// what's still pending before consumers repoint here.
rootProject.name = "SharedLibrariesAsoc"
include(":crash-recovery")
