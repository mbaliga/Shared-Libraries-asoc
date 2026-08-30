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

// 1.4.0 — the look-and-feel pass the owner asked for after device testing, plus the two
// display bugs that pass turned up: an outlined pill that painted its label in the fill colour
// (an empty-looking button, now pinned by PillColorsTest) and no window-inset handling at all
// (the crash mark clipped by the status bar). The screen now works from one design scale
// (CrashRecoveryLook.kt) instead of ad-hoc numbers, answers presses, and moves on the same
// 320ms eased curve as the rest of the constellation. Still plain android.widget: the
// zero-dependency guarantee is what makes this screen survivable, and it is not negotiable.
//
// 1.3.0 — adds ApplicationExitInfo-backed detection (captureExitDeath): native crashes and
// ANR kills, which the JVM handler can never see, now surface on the recovery screen too.
// Found the hard way: a native crash during Foto Xplorr's launch produced an unbreakable
// crash loop where the OS showed "keeps stopping" and our recovery screen never could.
// (1.2.0 was the first release from this home: hyle-design-system@c586f8f + previewIntent
// merged forward from the never-merged 33b0faa. See MIGRATION.md.)
val crashRecoveryVersion = "1.5.0"

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
