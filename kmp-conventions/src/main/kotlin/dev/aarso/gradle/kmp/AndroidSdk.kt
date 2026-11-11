package dev.aarso.gradle.kmp

import java.io.File

/**
 * Android is opt-in by an EXPLICIT signal, not by sniffing ANDROID_HOME: hosted CI runners ship
 * the SDK preset, and sniffing would drag a full Android toolchain into a JVM-only build.
 * Same rule as hnm_playground's settings.gradle.kts `androidSdkAvailable()`:
 *   - ENABLE_ANDROID=1 in the environment (the APK workflow sets it), or
 *   - a local.properties at the root containing `sdk.dir` (local development).
 */
object AndroidSdk {
    fun available(rootDir: File, env: Map<String, String?> = System.getenv()): Boolean {
        if (env["ENABLE_ANDROID"] == "1") return true
        val localProps = File(rootDir, "local.properties")
        return localProps.exists() && localProps.readText().contains("sdk.dir")
    }
}
