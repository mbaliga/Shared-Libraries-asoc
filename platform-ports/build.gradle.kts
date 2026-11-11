// :platform-ports — Kotlin Multiplatform expect/actual library for the host things every app
// needs and every platform does differently: secret storage that REPORTS its tier (OQ-22), app
// directories, file picker and share sheet, power/thermal/metered probes, Bluetooth RFCOMM.
// Every part that is not real on a host is a stub that carries the sentence a UI must show.
//
// Targets: jvm (real, tested here), android (only if an Android SDK is present; see
// android-target.gradle.kts), iosArm64/iosSimulatorArm64/iosX64 (stubs; Kotlin/Native skips
// them on Linux, so they are never compiled in the authoring container).
//
// Dependencies: none beyond the Kotlin stdlib and kotlin-test. No coroutines, no Compose, no
// network, no telemetry.
plugins {
    // No version here: resolved from the Kotlin Gradle plugin the root build already has on its
    // classpath (the kotlin.jvm / kotlin.android aliases, all at libs.versions.kotlin).
    id("org.jetbrains.kotlin.multiplatform")
    // On the classpath always, applied only when an Android SDK is present (android-target.gradle.kts),
    // so that script's Android DSL compiles on a runner that has an SDK. AGP 8.9.1 is the lockstep version.
    id("com.android.library") version "8.9.1" apply false
    `maven-publish`
}

group = "dev.aarso"
version = "0.1.0"

fun androidSdkPresent(): Boolean {
    if (!System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()) return true
    val lp = rootProject.file("local.properties")
    return lp.isFile && lp.readLines().any { it.trim().startsWith("sdk.dir=") }
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Android target: only when an Android SDK is present, so a bare-JDK build never touches AGP's
// SDK checks. Not exercised in the authoring container (no SDK); CI on a runner with an SDK is
// the first thing that compiles it.
if (androidSdkPresent()) {
    pluginManager.apply("com.android.library")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        androidTarget {
            publishLibraryVariants("release")
        }
    }

    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "dev.aarso.platformports"
        compileSdk = 36
        defaultConfig {
            minSdk = 24
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
