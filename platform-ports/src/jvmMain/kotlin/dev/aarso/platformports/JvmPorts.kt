package dev.aarso.platformports

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Host family from `os.name`. Pure; the string is injected so tests need no real OS. */
fun hostOsFrom(osName: String): HostOs {
    val n = osName.lowercase()
    return when {
        n.contains("win") && !n.contains("darwin") -> HostOs.WINDOWS
        n.contains("mac") || n.contains("darwin") -> HostOs.MACOS
        n.contains("linux") || n.contains("nux") -> HostOs.LINUX
        else -> HostOs.OTHER
    }
}

/** Real: reads `os.name`, `user.home` and the environment, creates nothing. */
class JvmAppDirectories(
    private val appId: String,
    private val osName: String = System.getProperty("os.name") ?: "",
    private val home: String = System.getProperty("user.home") ?: "",
    private val env: Map<String, String> = System.getenv(),
) : AppDirectoriesProvider {
    override val name = "app-directories"
    override val isStub = false
    override val stubNotice: String? = null

    override fun resolve(): Reading<AppDirectories> =
        if (home.isBlank()) {
            Reading.Unavailable("The JVM did not report a home folder.")
        } else {
            Reading.Known(resolveAppDirectories(hostOsFrom(osName), appId, home, env))
        }
}

/**
 * Implemented against Swing's [JFileChooser]; in a headless JVM it returns Unavailable. The dialog
 * path has been compiled but not exercised in this repo's container (no display).
 */
class SwingFilePicker : FilePicker {
    override val name = "file-picker"
    override val isStub = false
    override val stubNotice: String? = null

    override fun pick(request: PickRequest): PickOutcome {
        if (GraphicsEnvironment.isHeadless()) return PickOutcome.Unavailable("No display is available for a file dialog.")
        val chooser = JFileChooser().apply {
            dialogTitle = request.title
            isMultiSelectionEnabled = request.allowMultiple
            val exts = request.normalizedExtensions
            if (exts.isNotEmpty()) fileFilter = FileNameExtensionFilter(exts.joinToString(", "), *exts.toTypedArray())
        }
        val result = if (request.mode == PickMode.SAVE) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return PickOutcome.Cancelled
        val files = if (request.allowMultiple) chooser.selectedFiles.toList() else listOf(chooser.selectedFile)
        return PickOutcome.Picked(files.map { it.absolutePath })
    }
}

/** Linux battery from `/sys/class/power_supply`. [root] is injectable for tests. Real on Linux only. */
class LinuxSysfsPowerProbe(private val root: Path = Paths.get("/sys/class/power_supply")) : PowerProbe {
    override val name = "power-probe"
    override val isStub = false
    override val stubNotice: String? = null

    override fun read(): Reading<PowerState> {
        if (!Files.isDirectory(root)) return Reading.Unavailable("This host exposes no /sys/class/power_supply.")
        val battery = Files.list(root).use { s ->
            s.filter { readText(it.resolve("type")) == "Battery" }.sorted().findFirst().orElse(null)
        } ?: return Reading.Unavailable("No battery found; this may be a desktop on mains power.")
        val percent = readText(battery.resolve("capacity"))?.toIntOrNull()?.takeIf { it in 0..100 }
        val charging = when (readText(battery.resolve("status"))) {
            "Charging", "Full" -> true
            "Discharging", "Not charging" -> false
            else -> null
        }
        return Reading.Known(PowerState(percent, charging))
    }
}

/** Linux temperature from `/sys/class/thermal/thermal_zone*`; reports the hottest zone. */
class LinuxSysfsThermalProbe(private val root: Path = Paths.get("/sys/class/thermal")) : ThermalProbe {
    override val name = "thermal-probe"
    override val isStub = false
    override val stubNotice: String? = null

    override fun read(): Reading<ThermalState> {
        if (!Files.isDirectory(root)) return Reading.Unavailable("This host exposes no /sys/class/thermal.")
        val temps = Files.list(root).use { s ->
            s.filter { it.fileName.toString().startsWith("thermal_zone") }
                .map { readText(it.resolve("temp"))?.toLongOrNull() }
                .toList()
        }.filterNotNull().map { it / 1000.0 }.filter { it in -40.0..200.0 }
        val hottest = temps.maxOrNull() ?: return Reading.Unavailable("No thermal zone reported a temperature.")
        return Reading.Known(ThermalState(thermalLevelFor(hottest), hottest))
    }
}

private fun readText(p: Path): String? = try {
    if (Files.isRegularFile(p)) Files.readString(p).trim() else null
} catch (_: java.io.IOException) {
    null
}

/** Plain-JVM candidates for the secret store, best tier first, so a UI can show what it did not get. */
object JvmSecretStores {
    const val OS_KEYCHAIN_NOTICE: String =
        "OS keychain storage (Keychain, Credential Manager, Secret Service) is not built yet. " +
            "Keys use a file instead, with a weaker guarantee."

    /** A stub that exists so the missing tier is visible rather than silently skipped. */
    fun osKeychainStub(): SecretStore = Stubs.secretStore(OS_KEYCHAIN_NOTICE)

    fun appPrivateFile(dataDir: String): SecretStore =
        AppPrivateFileSecretStore(Paths.get(dataDir, "secrets.v1"))

    fun passphraseFile(dataDir: String, passphrase: CharArray): SecretStore =
        PassphraseFileSecretStore(Paths.get(dataDir, "secrets.v1.enc"), passphrase)
}

private const val NO_SHARE_SHEET =
    "Desktop Java has no system share sheet. Save the file and send it yourself."

private const val NO_METERED =
    "A desktop JVM cannot tell whether a connection is metered. Ask the user before large downloads."

private const val NO_RFCOMM =
    "Bluetooth RFCOMM is not implemented on this JVM build. It needs a native Bluetooth stack that is not wired up."

/** JVM defaults; see [PlatformPorts]. The secret store here is the app-private file tier. */
actual fun defaultPlatformPorts(appId: String): PlatformPorts {
    val dirs = JvmAppDirectories(appId)
    val resolved = dirs.resolve().valueOrNull()
    val secrets = if (resolved != null) {
        JvmSecretStores.appPrivateFile(resolved.data)
    } else {
        Stubs.secretStore("No data folder could be found, so keys cannot be saved.")
    }
    return PlatformPorts(
        appDirectories = dirs,
        secrets = secrets,
        filePicker = SwingFilePicker(),
        shareSheet = Stubs.shareSheet(NO_SHARE_SHEET),
        power = LinuxSysfsPowerProbe(),
        thermal = LinuxSysfsThermalProbe(),
        network = Stubs.networkProbe(NO_METERED),
        rfcomm = Stubs.rfcomm(NO_RFCOMM),
    )
}
