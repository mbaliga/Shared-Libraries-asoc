// :interaction-mode — the constellation-wide choice between the conventional "Regular" chrome
// and the experimental spatial "asoc" rooms/gesture pattern. Per the 2026-09-15 ruling: the
// rooms model remains somewhat experimental while it is being perfected, so every app in the
// constellation lets the user choose between the two rather than defaulting everyone onto
// whichever is newest. Naming is exactly "Regular" and "asoc" (lowercase) at the UI layer.
//
// Same posture as :crash-recovery (the structural precedent this module follows): a small,
// dependency-free API, plain `android.content.SharedPreferences` (part of the SDK, not
// androidx) rather than a datastore/coroutines dependency, and deliberately no UI — the picker
// itself lives in Hyle (`dev.aarso.hyle`), not here. See src/test's FakeSharedPreferences for
// why PrefsInteractionModeStore is unit-testable on a plain JVM with no Robolectric: it's a
// hand-rolled double for a platform *interface*, so no Android runtime is ever invoked.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val interactionModeGroup = "dev.aarso"
val interactionModeArtifact = "interaction-mode"
val interactionModeVersion = "0.1.0"

// Project coordinate — required for Gradle composite-build (`includeBuild`) dependency
// substitution, which is how every consumer resolves this module.
group = interactionModeGroup
version = interactionModeVersion

android {
    namespace = "dev.aarso.interactionmode"
    compileSdk = 36

    defaultConfig {
        // Matches :crash-recovery's minSdk (the lowest current consumer) — no reason for this
        // module to force a higher floor than the reliability module already sets.
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
                groupId = interactionModeGroup
                artifactId = interactionModeArtifact
                version = interactionModeVersion
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
