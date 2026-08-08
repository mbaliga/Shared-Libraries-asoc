package dev.aarso.diagnostics.core

/**
 * Context and report types. The measurement vocabulary lives in Series.kt; this file is the
 * surrounding structure — who ran what, on which device, and what came out.
 *
 * NOTHING in this module may import android.*. Every number that reaches a report is computed here
 * so it is unit-testable off-device, per the pure-JVM-first module law.
 */

const val SCHEMA = "diag/2"

// ---------------------------------------------------------------- context

data class SessionInfo(
    val id: String,
    val label: String?,
    val startedAtIso: String,
    val durationSec: Double,
    val trigger: Trigger,
    val attributes: Map<String, String> = emptyMap(),
    /** True when this report was rebuilt from a journal after the process died. See Journal.kt. */
    val recovered: Boolean = false,
)

enum class Trigger { AUTO, OVERLAY, SETTINGS, ADB_BROADCAST, API, RECOVERED }

data class AppInfo(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val debuggable: Boolean,
    val gitSha: String? = null,
    val gitDirty: Boolean = false,
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val codename: String,
    val soc: String?,
    val androidRelease: String,
    val apiLevel: Int,
    val abis: List<String>,
    val totalRamMb: Long,
    val availRamMbAtStart: Long,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    /** ACTUAL current refresh rate, not the mode maximum. */
    val refreshHz: Double,
    val panelMaxHz: Double,
    val rooted: Boolean,
) {
    val budgetMs: Double get() = if (refreshHz > 0) 1000.0 / refreshHz else 0.0
}

// ---------------------------------------------------------------- thermal / power

enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

data class ThermalWindow(val tSec: Double, val status: ThermalStatus, val durationSec: Double)

data class PowerSection(
    val windows: List<ThermalWindow>,
    val batteryStartPct: Int?,
    val batteryEndPct: Int?,
    val charging: Boolean,
    val powerSave: Boolean,
    val headroomStart: Double? = null,
    val headroomEnd: Double? = null,
) {
    val worst: ThermalStatus
        get() = windows.maxByOrNull { it.status.ordinal }?.status ?: ThermalStatus.UNKNOWN

    val throttledSec: Double
        get() = windows.filter { it.status.ordinal >= ThermalStatus.MODERATE.ordinal }
            .sumOf { it.durationSec }

    /**
     * Drain in percent per hour, or null when the session was too short for the figure to mean
     * anything. Long-running profiles (a wallpaper, an inference daemon) are the ones that care.
     */
    fun drainPctPerHour(durationSec: Double, minSec: Double = 300.0): Double? {
        if (durationSec < minSec || charging) return null
        val s = batteryStartPct ?: return null
        val e = batteryEndPct ?: return null
        if (s <= e) return 0.0
        return (s - e) * 3600.0 / durationSec
    }
}

// ---------------------------------------------------------------- lifecycle

data class StartupPhase(val name: String, val deltaMs: Double, val cumulativeMs: Double)

data class StartupSection(
    val coldToFirstFrameMs: Double?,
    val phases: List<StartupPhase>,
    val warmMs: Double?,
    val hotMs: Double?,
)

data class SpanStat(
    val name: String,
    val count: Int,
    val p50Ms: Double,
    val p95Ms: Double?,
    val maxMs: Double,
)

data class Mark(val tSec: Double, val name: String, val auto: Boolean = false)

// ---------------------------------------------------------------- logs / crash

enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timeIso: String,
    val level: Level,
    val tag: String,
    val message: String,
    val thread: String? = null,
) {
    val levelChar: Char
        get() = when (level) {
            Level.VERBOSE -> 'V'; Level.DEBUG -> 'D'; Level.INFO -> 'I'
            Level.WARN -> 'W'; Level.ERROR -> 'E'
        }
}

data class CrashRecord(
    val occurredAtIso: String,
    val throwable: String,
    val topFrames: List<String>,
    val moduleName: String = ":crash-recovery",
)

data class NotMeasured(val what: String, val why: String)

// ---------------------------------------------------------------- the report

data class Report(
    val schema: String = SCHEMA,
    val profileId: String,
    val profileTitle: String,
    val session: SessionInfo,
    val app: AppInfo,
    val device: DeviceInfo,
    val series: List<SeriesReport> = emptyList(),
    val trends: List<TrendGroup> = emptyList(),
    val facts: List<Fact> = emptyList(),
    val counters: List<CounterValue> = emptyList(),
    val power: PowerSection? = null,
    val startup: StartupSection? = null,
    val spans: List<SpanStat> = emptyList(),
    val marks: List<Mark> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val logRingCapacity: Int = 512,
    val crash: CrashRecord? = null,
    val crashModulePresent: Boolean = false,
    val notMeasured: List<NotMeasured> = emptyList(),
) {
    fun series(id: String): SeriesReport? = series.firstOrNull { it.spec.id == id }
    fun trend(id: String): TrendGroup? = trends.firstOrNull { it.id == id }
    fun fact(key: String): String? = facts.firstOrNull { it.key == key }?.value
    fun counter(name: String): Long? = counters.firstOrNull { it.name == name }?.value
}
