// :diagnostics-overlay — the profile-aware floating bubble/panel. Plain Views (not Compose),
// deliberately: it must composite correctly over a GLSurfaceView or a live-wallpaper preview,
// and no app should have to inherit a design system to get reliability tooling. Depends on
// :diagnostics-android for the session/source plumbing it renders.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val diagnosticsGroup = "dev.aarso"
val diagnosticsOverlayArtifact = "diagnostics-overlay"
val diagnosticsVersion = "0.2.0"

group = diagnosticsGroup
version = diagnosticsVersion

android {
    namespace = "dev.aarso.diagnostics.overlay"
    compileSdk = 36

    defaultConfig {
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
                artifactId = diagnosticsOverlayArtifact
                version = diagnosticsVersion
            }
        }
    }
}

dependencies {
    implementation(project(":diagnostics-android"))
    // Deliberately NO Compose and NO Material. Plain Views composite correctly over a
    // GLSurfaceView and a live-wallpaper preview, and no app should inherit a design system in
    // order to get reliability tooling.
}
