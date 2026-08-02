// :crash-recovery -- a shared reliability utility, deliberately NOT part of any design-system
// module. It has zero dependency on design tokens (no Compose, no Material, plain
// `android.widget` views only) so apps with their own visual identity that must never
// depend on a shared design system (Animalcules, Clackpad -- Personal-Tracker DECISIONS.md
// D-L) can still take this one dependency. See D-O for why this module exists as a separate
// artifact rather than inside a design-system module or duplicated per-app, and
// snapshots/shared-libraries-reorg-proposal-2026-08-02.md for why it now lives in this repo.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val crashRecoveryGroup = "dev.aarso"
val crashRecoveryArtifact = "crash-recovery"
val crashRecoveryVersion = "1.1.0"

// Project coordinate -- required for Gradle composite-build (`includeBuild`) dependency
// substitution, same mechanism as the design system's own modules.
group = crashRecoveryGroup
version = crashRecoveryVersion

android {
    namespace = "dev.aarso.crashrecovery"
    compileSdk = 36

    defaultConfig {
        // The lowest minSdk among current consumers (Animalcules) -- a library's minSdk only
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
