package dev.aarso.platformports

import android.content.Context

/**
 * ANDROID ACTUAL: NOT COMPILED OR RUN IN THIS REPO'S CONTAINER (no Android SDK, no device).
 *
 * Only app directories are implemented, from [Context]'s standard folders. Everything else is a
 * stub that says so: the Android Keystore tier, the picker, the share sheet, the power/thermal/
 * metered probes and RFCOMM are still to be written and owner-verified on a device.
 */
object AndroidPlatformPorts {
    private const val STUB =
        "The Android version of this part is not written yet. It is a stub and does nothing."

    fun create(context: Context): PlatformPorts {
        val app = context.applicationContext
        val dirs = object : AppDirectoriesProvider {
            override val name = "app-directories"
            override val isStub = false
            override val stubNotice: String? = null

            override fun resolve(): Reading<AppDirectories> {
                val files = app.filesDir.absolutePath
                return Reading.Known(
                    AppDirectories(
                        config = files,
                        data = files,
                        cache = app.cacheDir.absolutePath,
                        logs = files + "/logs",
                    ),
                )
            }
        }
        val stubs = Stubs.allStub(STUB)
        return PlatformPorts(
            appDirectories = dirs,
            secrets = stubs.secrets,
            filePicker = stubs.filePicker,
            shareSheet = stubs.shareSheet,
            power = stubs.power,
            thermal = stubs.thermal,
            network = stubs.network,
            rfcomm = stubs.rfcomm,
        )
    }
}

/** Without a [Context] nothing on Android can be real; use [AndroidPlatformPorts.create]. */
actual fun defaultPlatformPorts(appId: String): PlatformPorts =
    Stubs.allStub("On Android, build the ports with AndroidPlatformPorts.create(context). This default is a stub.")
