package dev.aarso.gradle.kmp

import org.gradle.api.GradleException
import org.gradle.api.Project

/** The `asocTargets { android(); jvm(); ios(); wasm() }` DSL. Each call is opt-in per repo. */
open class AsocTargetsExtension(private val project: Project) {
    private val kmp get() = KotlinAccess.extension(project)

    /**
     * Declares the Android target only when the SDK signal is present (see [AndroidSdk]).
     * Without it this is a logged no-op, so a JVM-only build stays green. With it, the consumer
     * must have applied `com.android.library` itself (AGP is pinned in the catalog; this plugin
     * does not choose it).
     */
    fun android() {
        if (!AndroidSdk.available(project.rootDir)) {
            project.logger.lifecycle(
                "asocTargets: android() skipped in ${project.path} (no ENABLE_ANDROID=1, no sdk.dir in local.properties)",
            )
            return
        }
        if (!project.pluginManager.hasPlugin("com.android.library")) {
            throw GradleException(
                "asocTargets.android(): the Android SDK signal is set but com.android.library is not applied in ${project.path}",
            )
        }
        KotlinAccess.call(kmp, "androidTarget")
    }

    fun jvm() {
        KotlinAccess.call(kmp, "jvm")
    }

    /**
     * iOS targets. Refused below Kotlin ${Pins.KOTLIN_IOS}: a repo that ships iOS takes the whole
     * iOS pin set (OQ-38), it does not mix a 2.1.20 build with iOS targets.
     * Only DECLARED here; linking and running need macOS with Xcode, which this plugin never claims to cover.
     */
    fun ios() {
        val v = KotlinAccess.version(project)
        if (!Pins.supportsIos(v)) {
            throw GradleException(
                "asocTargets.ios() needs Kotlin ${Pins.KOTLIN_IOS} or later (OQ-38 iOS pin set); this build uses $v",
            )
        }
        listOf("iosX64", "iosArm64", "iosSimulatorArm64").forEach { KotlinAccess.call(kmp, it) }
    }

    /** wasmJs with a browser host. (No-arg `browser()`: the lambda overloads are Kotlin-typed.) */
    fun wasm() {
        val target = KotlinAccess.call(kmp, "wasmJs")!!
        KotlinAccess.call(target, "browser")
    }
}
