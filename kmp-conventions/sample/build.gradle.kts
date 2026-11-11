plugins {
    kotlin("multiplatform") version "2.1.20" // classic pin set (no iOS target); see catalog/asoc.versions.toml
    id("dev.aarso.kmp-conventions")
}

asocTargets {
    android() // logged no-op without the SDK signal
    jvm()
}
