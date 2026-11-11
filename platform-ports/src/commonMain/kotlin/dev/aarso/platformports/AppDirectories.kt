package dev.aarso.platformports

/** The host families app-directory conventions differ on. */
enum class HostOs { LINUX, MACOS, WINDOWS, OTHER }

/** Where an app keeps its files on this host. Paths are plain strings in the host's own syntax. */
data class AppDirectories(
    val config: String,
    val data: String,
    val cache: String,
    val logs: String,
)

/** Resolves [AppDirectories] for the running host. */
interface AppDirectoriesProvider : PortComponent {
    fun resolve(): Reading<AppDirectories>
}

/**
 * Pure resolution of per-user app directories from the host's conventions: XDG on Linux,
 * `~/Library` on macOS, `%APPDATA%`/`%LOCALAPPDATA%` on Windows. [env] is the environment as a
 * map so tests need no real environment. Nothing is created on disk.
 *
 * @throws IllegalArgumentException if [appId] is blank or could escape its folder.
 */
fun resolveAppDirectories(
    os: HostOs,
    appId: String,
    home: String,
    env: Map<String, String>,
): AppDirectories {
    require(appId.isNotBlank()) { "appId must not be blank" }
    require(appId.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } && appId != "." && appId != "..") {
        "appId must be a single folder name: $appId"
    }
    require(home.isNotBlank()) { "home must not be blank" }
    return when (os) {
        HostOs.WINDOWS -> {
            val roaming = env.absoluteOrNull("APPDATA", windows = true) ?: join('\\', home, "AppData", "Roaming")
            val local = env.absoluteOrNull("LOCALAPPDATA", windows = true) ?: join('\\', home, "AppData", "Local")
            AppDirectories(
                config = join('\\', roaming, appId),
                data = join('\\', local, appId, "data"),
                cache = join('\\', local, appId, "cache"),
                logs = join('\\', local, appId, "logs"),
            )
        }
        HostOs.MACOS -> AppDirectories(
            config = join('/', home, "Library", "Application Support", appId),
            data = join('/', home, "Library", "Application Support", appId),
            cache = join('/', home, "Library", "Caches", appId),
            logs = join('/', home, "Library", "Logs", appId),
        )
        HostOs.LINUX, HostOs.OTHER -> AppDirectories(
            config = join('/', env.absoluteOrNull("XDG_CONFIG_HOME") ?: join('/', home, ".config"), appId),
            data = join('/', env.absoluteOrNull("XDG_DATA_HOME") ?: join('/', home, ".local", "share"), appId),
            cache = join('/', env.absoluteOrNull("XDG_CACHE_HOME") ?: join('/', home, ".cache"), appId),
            logs = join('/', env.absoluteOrNull("XDG_STATE_HOME") ?: join('/', home, ".local", "state"), appId, "logs"),
        )
    }
}

private fun join(sep: Char, vararg parts: String): String =
    parts.mapIndexed { i, p -> if (i == 0) p.trimEnd(sep) else p.trim(sep) }.joinToString(sep.toString())

/** XDG says relative values must be ignored; the same guard is applied to the Windows variables. */
private fun Map<String, String>.absoluteOrNull(key: String, windows: Boolean = false): String? {
    val v = this[key]?.takeIf { it.isNotBlank() } ?: return null
    val absolute = if (windows) v.length > 2 && v[1] == ':' && (v[2] == '\\' || v[2] == '/') else v.startsWith("/")
    return v.takeIf { absolute }
}
