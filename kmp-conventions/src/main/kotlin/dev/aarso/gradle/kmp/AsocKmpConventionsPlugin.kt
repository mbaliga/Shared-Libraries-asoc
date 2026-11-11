package dev.aarso.gradle.kmp

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `dev.aarso.kmp-conventions`. Apply AFTER `org.jetbrains.kotlin.multiplatform` (the consumer's
 * pin set picks the Kotlin version; see catalog/asoc.versions.toml). Adds:
 *  - `asocTargets { android(); jvm(); ios(); wasm() }`
 *  - `verifyNoAndroidImports`  (wired into `check`)
 *  - `verifyDependencyLicences` (wired into `check`; permissive-only allowlist)
 */
class AsocKmpConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
            throw GradleException(
                "dev.aarso.kmp-conventions needs org.jetbrains.kotlin.multiplatform applied first " +
                    "(Kotlin ${Pins.KOTLIN_CLASSIC} with no iOS target, ${Pins.KOTLIN_IOS} with one)",
            )
        }
        val kotlinVersion = KotlinAccess.version(project)
        if (kotlinVersion.substringBefore('-') !in setOf(Pins.KOTLIN_CLASSIC, Pins.KOTLIN_IOS)) {
            project.logger.warn(
                "kmp-conventions: Kotlin $kotlinVersion is neither pin (${Pins.KOTLIN_CLASSIC} / ${Pins.KOTLIN_IOS}); " +
                    "a deviation needs a written reason in the repo (OQ-17 / OQ-38)",
            )
        }
        project.extensions.create("asocTargets", AsocTargetsExtension::class.java, project)

        val ban = project.tasks.register("verifyNoAndroidImports", AndroidImportBan::class.java) {
            // Everything except android* source sets. Resolved lazily, after targets are declared.
            sources.from(project.provider {
                KotlinAccess.sourceSetDirs(project)
                    .filter { !it.first.startsWith("android", ignoreCase = true) }
                    .flatMap { it.second }
            })
        }
        val lic = project.tasks.register("verifyDependencyLicences", LicenceCheck::class.java) {
            configurationNames.convention(
                listOf("commonMainImplementationDependenciesMetadata", "jvmRuntimeClasspath", "wasmJsRuntimeClasspath"),
            )
            exceptions.convention(emptyList())
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(ban, lic) }
    }
}
