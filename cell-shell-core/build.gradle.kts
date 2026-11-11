// :cell-shell-core -- the platform-neutral half of :cell-shell.
//
// Plain `kotlin("jvm")`, exactly like :search-core: no Android, no Compose, no coroutines. It
// holds the arithmetic that decides how the navigation FEELS (settle target, park geometry,
// scrub mapping, wheel falloff, shake detection) so it can be tested on a bare JDK and later
// become commonMain of a Kotlin Multiplatform :cell-shell (porting program item F3).
//
// Not yet a `kotlin("multiplatform")` module: converting needs the owner's approval (OQ-17,
// "approval to convert :cell-shell not answered"). Moving the sources into commonMain later is
// a directory move, nothing in them is JVM-specific.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val cellShellCoreGroup = "dev.aarso"
val cellShellCoreArtifact = "cell-shell-core"
val cellShellCoreVersion = "0.1.0"

// Project coordinate -- required for composite-build (`includeBuild`) substitution.
group = cellShellCoreGroup
version = cellShellCoreVersion

kotlin { jvmToolchain(17) }

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = cellShellCoreGroup
            artifactId = cellShellCoreArtifact
            version = cellShellCoreVersion
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
