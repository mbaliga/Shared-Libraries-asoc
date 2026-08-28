// :modelbench — v1 scaffolding for local-model benchmarking. Pure `kotlin("jvm")`, same
// posture as :search-core/:diagnostics-core: no Android, so the timing/throughput math is
// testable on a normal JVM with no device or emulator, and this module's own JVM tests are
// the actual evidence for its correctness (this build environment has no phone to run
// llama.cpp on).
//
// EngineAdapter (EngineAdapter.kt) is a SEAM ONLY here — no llama.cpp, no native code, no
// vendored model files. A SyntheticEngine (scripted, deterministic token timings) drives
// BenchmarkSuite's own test suite and doubles as a dev/CI stand-in. A real llama.cpp-backed
// adapter is planned to land with the asystemofmodels router, in that repo, implementing this
// interface — not here.
//
// Results serialize to modelbench-report.v1 (schema/modelbench-report.v1.schema.json, bundled
// as a resource) via a small hand-rolled JSON writer (json/JsonValue.kt) — no serialization
// library on the shipped classpath, matching this module's zero-runtime-dependency siblings.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val modelbenchGroup = "dev.aarso"
val modelbenchArtifact = "modelbench"
val modelbenchVersion = "0.1.0"

group = modelbenchGroup
version = modelbenchVersion

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
            groupId = modelbenchGroup
            artifactId = modelbenchArtifact
            version = modelbenchVersion
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json.schema.validator)
}
