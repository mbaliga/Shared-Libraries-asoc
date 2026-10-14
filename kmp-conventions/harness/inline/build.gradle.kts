import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(kmpPins.plugins.kotlin.multiplatform)
    alias(kmpPins.plugins.kotlin.plugin.compose)
    alias(kmpPins.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    // BEGIN convention (kmp-conventions/verify-pins.py checks README.md carries these exact lines)
    if (HostManager.hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }
    // END convention

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:${kmpPins.versions.compose.multiplatform.get()}")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
