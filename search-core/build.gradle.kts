// :search-core — the app-agnostic heart of on-device search.
//
// Deliberately plain `kotlin("jvm")`: no Android, no coroutines, no storage engine. That is a
// design constraint, not an accident.
//   - No Android, so the whole engine is testable on a normal JVM with no emulator, and the
//     included build stays usable by a host that has not aligned its AGP yet.
//   - No coroutines, so nothing here can accidentally depend on a dispatcher or a scope;
//     streaming and cancellation belong to a later module that wraps this one.
//   - No storage engine, so the same matching and ranging logic serves a persistent FTS index
//     (Android-IDE-core), a live filesystem walk with no index at all (Fylz), and a synchronous
//     in-memory predicate evaluated during Compose composition (Foto Xplorr).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val searchCoreGroup = "dev.aarso"
val searchCoreArtifact = "search-core"
val searchCoreVersion = "0.2.0"

// Project coordinate — required for Gradle composite-build (`includeBuild`) dependency
// substitution, which is how every consumer resolves this module.
group = searchCoreGroup
version = searchCoreVersion

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // This is a library others compile against; an accidental unstable-API leak here
        // becomes a compile error in five downstream repos.
        allWarningsAsErrors.set(false)
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = searchCoreGroup
            artifactId = searchCoreArtifact
            version = searchCoreVersion
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
