plugins {
    kotlin("multiplatform") version "2.1.20" // classic pin set (no iOS target); see catalog/asoc.versions.toml
    id("dev.aarso.kmp-conventions")
}

asocTargets {
    android() // logged no-op without the SDK signal
    jvm()
}

// Second negative: a dual CDDL/GPLv2 dependency must trip verifyDependencyLicences.
kotlin.sourceSets.getByName("jvmMain").dependencies { implementation("com.sun.mail:javax.mail:1.6.2") }
