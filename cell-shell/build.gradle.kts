// :cell-shell — the constellation's navigation and motion shell.
//
// The owner's direction was "the navigation pattern of fonebrew ... followed everywhere",
// so the pattern lives in ONE place rather than being re-derived per app. Everything here
// is ported from Android-IDE-core's ui/spatial/SpatialRoot.kt, which is the reference
// implementation, with its motion constants preserved exactly — a 320ms cubic-bezier
// settle, finger-driven 1:1 drags, lift-and-part parking. Changing those numbers changes
// how every app in the constellation feels, so treat them as a contract, not defaults.
//
// Compose, unlike :crash-recovery (which is deliberately dependency-free so it cannot die
// of a dependency failure). This module is ordinary UI: it may depend on Compose because
// nothing it does has to survive the app being broken.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

val cellShellGroup = "dev.aarso"
val cellShellArtifact = "cell-shell"
val cellShellVersion = "0.1.0"

group = cellShellGroup
version = cellShellVersion

android {
    namespace = "dev.aarso.cellshell"
    compileSdk = 36

    defaultConfig {
        // Matches the lowest consumer that will adopt the shell. A library's minSdk only
        // needs to be <= its consumers', never forces theirs up.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(17) }

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = cellShellGroup
                artifactId = cellShellArtifact
                version = cellShellVersion
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    // Foundation + animation only: no material3. The shell must not impose a design system
    // on its hosts — Fylz and Foto Xplorr theme themselves, and apps under D-L must be able
    // to use it without inheriting anyone else's look.
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    // Lifecycle is not a design system, so it does not breach the rule above. ShakeToRefresh
    // holds an accelerometer listener and has to drop it when the host is backgrounded, which
    // means it needs a LifecycleOwner. Nothing else in the module touches lifecycle.
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}
