// :crash-recovery — a shared reliability utility with ZERO dependency on the Hyle design
// system (no Compose, no Material, plain `android.widget` views only; colours are plain
// `@ColorInt Int` via CrashRecoveryStyle, never Hyle tokens). That independence is the whole
// point: apps that must never depend on Hyle (Personal-Tracker DECISIONS.md D-L — Animalcules,
// Clackpad) can still take this one dependency.
//
// RELOCATED from mbaliga/Hyle-Design-System, where D-O originally put it. D-O's reasoning was
// sound at the time, but it left D-L apps having to carry the entire Hyle submodule to reach a
// module that deliberately has nothing to do with Hyle. This repo is the neutral home that
// removes that contradiction. hyle-design-system keeps a compile-time tombstone at the old
// coordinate so consumers get an actionable error instead of an unresolved dependency.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val crashRecoveryGroup = "dev.aarso"
val crashRecoveryArtifact = "crash-recovery"

// 1.2.0 — first release from the new home. Content is hyle-design-system@c586f8f (1.1.0) with
// previewIntent/samplePreview merged forward from the never-merged 33b0faa branch, which
// Android-IDE-core was pinned to and calls from SettingsRoom.kt. Those two histories had
// diverged; neither was a superset. See MIGRATION.md.
val crashRecoveryVersion = "1.2.0"

// Project coordinate — required for Gradle composite-build (`includeBuild`) dependency
// substitution, which is how every consumer resolves this module.
group = crashRecoveryGroup
version = crashRecoveryVersion

android {
    namespace = "dev.aarso.crashrecovery"
    compileSdk = 36

    defaultConfig {
        // The lowest minSdk among current consumers (Animalcules) — a library's minSdk only
        // needs to be <= the lowest consumer's, never forces anyone's minSdk up.
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = crashRecoveryGroup
                artifactId = crashRecoveryArtifact
                version = crashRecoveryVersion
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
