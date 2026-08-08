// :diagnostics-android — MetricSource plugins, session lifecycle, export, crash link, ADB
// trigger. No Compose, no Material — plain Android APIs only (Window/FrameMetrics,
// InputMethodService window attach, WallpaperService callbacks, AAudio/CameraX/BLE push
// sources), so a host never inherits a design system to get this module. Depends on
// :diagnostics-core for the aggregation/verdict/report engine.
//
// compileSdk is 36 here (not the 0.2.0 upstream draft's 35) to match this repo's other Android
// library, :crash-recovery, which the AGP-lockstep constraint (D-Q, root settings.gradle.kts)
// already proves works against AGP 8.9.1 in this exact composite build.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val diagnosticsGroup = "dev.aarso"
val diagnosticsAndroidArtifact = "diagnostics-android"
val diagnosticsVersion = "0.2.0"

group = diagnosticsGroup
version = diagnosticsVersion

android {
    namespace = "dev.aarso.diagnostics"
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
                groupId = diagnosticsGroup
                artifactId = diagnosticsAndroidArtifact
                version = diagnosticsVersion
            }
        }
    }
}

dependencies {
    api(project(":diagnostics-core"))
    implementation(libs.androidx.core.ktx) // FileProvider only
}
