// PROBE ONLY (-PunguardedProbe=true): the naive declaration the convention exists to avoid.
plugins {
    alias(kmpPins.plugins.kotlin.multiplatform)
    alias(kmpPins.plugins.kotlin.plugin.compose)
    alias(kmpPins.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:${kmpPins.versions.compose.multiplatform.get()}")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
