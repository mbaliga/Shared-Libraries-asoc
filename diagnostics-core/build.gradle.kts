// :diagnostics-core — pure JVM. No Android Gradle plugin, no android.* on the classpath: the
// module CANNOT reference the SDK even by accident, which is the point of the pure-JVM-first
// law (same posture as :search-core) — correctness-critical logic (percentiles, verdicts,
// invariants, redaction, reporting) stays testable off-device and CI stays SDK-free.
//
// Aggregation, verdicts, and report rendering for on-device evidence collection across the
// constellation's seven app types (ui/ime/wallpaper/audio/vision-pipeline/stream/service). See
// docs/DIAGNOSTICS_MODULE_SPEC.md for the design rationale.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val diagnosticsGroup = "dev.aarso"
val diagnosticsCoreArtifact = "diagnostics-core"
val diagnosticsVersion = "0.3.0"

group = diagnosticsGroup
version = diagnosticsVersion

kotlin { jvmToolchain(17) }

// This module's own test suite uses kotlin.test on the JUnit Platform (kotlin("test")),
// unlike :search-core/:crash-recovery's plain JUnit4 (libs.junit) — a per-module choice, not a
// repo-wide convention change. Gradle supports mixed test runners across modules in one build
// with no conflict.
dependencies { testImplementation(kotlin("test")) }

tasks.test { useJUnitPlatform() }

// Enforces the pure-JVM law structurally, not just by convention: fails the build if any
// source file under this module imports android.* or androidx.*.
val checkNoAndroidImports by tasks.registering {
    group = "verification"
    doLast {
        val offenders = fileTree("src") { include("**/*.kt") }
            .filter { it.readText().contains(Regex("""^import\s+android[.x]""", RegexOption.MULTILINE)) }
            .map { it.relativeTo(projectDir).path }
        if (offenders.isNotEmpty())
            throw GradleException("android.* import in a pure-JVM module: $offenders")
    }
}
tasks.named("check") { dependsOn(checkNoAndroidImports) }

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = diagnosticsGroup
            artifactId = diagnosticsCoreArtifact
            version = diagnosticsVersion
        }
    }
}
