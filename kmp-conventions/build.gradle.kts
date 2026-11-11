plugins {
    `kotlin-dsl`
}

group = "dev.aarso"
version = "0.1.0"

dependencies {
    // No KGP dependency on purpose. The plugin is loaded by a sibling classloader of the consumer's
    // kotlin("multiplatform"), so KGP types are not visible here; everything KGP-side goes through
    // KotlinAccess (reflection). The consumer picks Kotlin 2.1.20 or 2.2.21; see catalog/.
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("kmpConventions") {
            id = "dev.aarso.kmp-conventions"
            implementationClass = "dev.aarso.gradle.kmp.AsocKmpConventionsPlugin"
        }
        create("kmpConventionsSettings") {
            id = "dev.aarso.kmp-conventions.settings"
            implementationClass = "dev.aarso.gradle.kmp.AsocKmpSettingsPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The catalog test reads the TOML next to this build.
    systemProperty("asoc.catalog", layout.projectDirectory.file("catalog/asoc.versions.toml").asFile.absolutePath)
}
