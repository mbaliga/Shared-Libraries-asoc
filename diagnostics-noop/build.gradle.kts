// :diagnostics-noop — the release-variant substitute. Same public API as :diagnostics-android
// (asserted by scripts/check-noop-parity.py, function-for-function and parameter-for-
// parameter), every call a no-op. Depends only on :diagnostics-core for the shared
// Report/Level/Profile wire types the no-op facade's signatures still reference.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val diagnosticsGroup = "dev.aarso"
val diagnosticsNoopArtifact = "diagnostics-noop"
val diagnosticsVersion = "0.3.0"

group = diagnosticsGroup
version = diagnosticsVersion

android {
    namespace = "dev.aarso.diagnostics.noop"
    compileSdk = 36

    defaultConfig {
        // Lower than diagnostics-android/-overlay's minSdk=24 on purpose: the release-variant
        // substitute should never be the thing that forces a host's minSdk up.
        minSdk = 21
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
                groupId = diagnosticsGroup
                artifactId = diagnosticsNoopArtifact
                version = diagnosticsVersion
            }
        }
    }
}

dependencies {
    api(project(":diagnostics-core")) // shared Report/Level/Profile types only
}
