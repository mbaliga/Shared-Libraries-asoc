// :platform-ports is deliberately NOT included from the root settings.gradle.kts: it is a
// Kotlin Multiplatform module with Kotlin/Native targets, and the root build is composited into
// every consumer's build, so adding it there would put a KMP plugin and iOS targets into builds
// that have not asked for them. Build and test it on its own from this directory; a consumer
// that wants it adds `includeBuild("shared-libraries/platform-ports")`.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.1.20"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "platform-ports"
