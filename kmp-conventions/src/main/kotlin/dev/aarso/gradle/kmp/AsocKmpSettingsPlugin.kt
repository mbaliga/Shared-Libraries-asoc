package dev.aarso.gradle.kmp

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/** `asoc.androidSdkAvailable()` and `asoc.includeIfAndroid(":androidApp")` for settings scripts. */
abstract class AsocKmpSettingsExtension(private val settings: Settings) {
    fun androidSdkAvailable(): Boolean = AndroidSdk.available(settings.rootDir)

    /** Includes the given Android-only modules only when [androidSdkAvailable]; otherwise silently skips. */
    fun includeIfAndroid(vararg paths: String) {
        if (androidSdkAvailable()) settings.include(*paths)
    }
}

class AsocKmpSettingsPlugin : Plugin<Settings> {
    override fun apply(target: Settings) {
        target.extensions.create("asoc", AsocKmpSettingsExtension::class.java, target)
    }
}
