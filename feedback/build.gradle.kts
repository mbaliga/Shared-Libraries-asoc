// :feedback — the constellation's opt-in feedback utility for experimental and new features.
//
// The constraint that shapes everything here is house doctrine: no backend, no telemetry, no
// phoning home. So this module cannot send anything. It composes a feedback draft the user can
// READ IN FULL, and hands it to a share/mail chooser the USER launches — delivery is theirs,
// by whatever channel they pick, or not at all. There is no network permission to even ask
// for, no queue, no retry, no identifier. What the user sees in the draft is the entire
// payload; the render function is pure and pinned by tests to contain nothing it was not
// given.
//
// Zero Hyle dependency, same rule as :crash-recovery and for the same reason: apps that must
// never depend on Hyle can still take this. No Compose either — each app renders its own
// prompt in its own design language; this module is the draft, the opt-in ledger, and the
// chooser intent.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val feedbackGroup = "dev.aarso"
val feedbackArtifact = "feedback"

// 0.1.0 — first cut: FeedbackDraft (pure), FeedbackOptIn (per-feature ledger, default OFF),
// FeedbackShare (chooser intent builder). API surface deliberately small until two apps
// (Fylz, Foto Xplorr) have adopted it and the shape has been felt.
val feedbackVersion = "0.1.0"

group = feedbackGroup
version = feedbackVersion

android {
    namespace = "dev.aarso.feedback"
    compileSdk = 36

    defaultConfig {
        // Matches :crash-recovery's floor so the lowest-minSdk consumers can take it.
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
                groupId = feedbackGroup
                artifactId = feedbackArtifact
                version = feedbackVersion
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
