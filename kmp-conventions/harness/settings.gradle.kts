// A STANDALONE Gradle build (not included by the repository's root settings.gradle.kts) that proves
// the kmp-conventions data and snippet work: it resolves the ruled pin set, configures Kotlin
// Multiplatform + Compose Multiplatform on whatever host runs it, declares Apple targets behind the
// mac-host guard, compiles a @Composable, and runs a jvmTest.
//
//   kmp-conventions/harness/run.sh [non-ios|ios|both]
//
// Select the pin set with -PpinSet=non-ios (default) or -PpinSet=ios.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

val pinSet = providers.gradleProperty("pinSet").orElse("non-ios").get()
require(pinSet == "non-ios" || pinSet == "ios") { "pinSet must be non-ios or ios, was '$pinSet'" }

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("kmpPins") {
            from(files("../catalogs/kmp-$pinSet.versions.toml"))
        }
    }
}

rootProject.name = "kmp-conventions-harness"

// The README form, pasted verbatim between the BEGIN/END convention markers.
include(":inline")
// Opt-in probe: declares Apple targets WITHOUT the guard, to record what the host does with them.
if (providers.gradleProperty("unguardedProbe").isPresent) include(":unguarded")
