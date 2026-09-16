// :modelbench-ui — minimal Compose UI over :modelbench's report model: a run list and a run
// detail screen. NOT a runnable app module — this repo ships libraries only (see :cell-shell,
// :crash-recovery, :diagnostics-*; there is no `com.android.application` module anywhere here).
// A host app (Fonebrew, Studio) embeds these composables the same way it embeds :cell-shell.
//
// Foundation + animation only, no material3 — same rule as :cell-shell: a benchmarking screen
// must not impose a design system on whichever host embeds it. Hyle adoption is deferred to
// whenever this repo settles a shared Hyle-consumption route (none of :cell-shell/:diagnostics-*
// take one yet either); until then this stays dependency-light plain Compose, per the task
// brief's own fallback instruction.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

val modelbenchUiGroup = "dev.aarso"
val modelbenchUiArtifact = "modelbench-ui"
val modelbenchUiVersion = "0.1.0"

group = modelbenchUiGroup
version = modelbenchUiVersion

android {
    namespace = "dev.aarso.modelbench.ui"
    compileSdk = 36

    defaultConfig {
        // Matches :cell-shell's rationale: the lowest consumer that will adopt this screen.
        // No consumer is wired up yet, so this tracks that sibling module's floor rather than
        // inventing a new one.
        minSdk = 26
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
                groupId = modelbenchUiGroup
                artifactId = modelbenchUiArtifact
                version = modelbenchUiVersion
            }
        }
    }
}

dependencies {
    api(project(":modelbench"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
}
