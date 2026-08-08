package dev.aarso.diagnostics.collect

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.aarso.diagnostics.core.*
import java.io.File

/**
 * The cross-cutting collectors — memory, thermal, device context, start-up. These apply to every
 * profile, unlike the per-app-type sources, which is why they are not MetricSources.
 */

internal data class MemorySample(
    val tSec: Double,
    val pssTotalMb: Double,
    val pssDalvikMb: Double,
    val pssNativeMb: Double,
    val pssGraphicsMb: Double,
    val pssOtherMb: Double,
    val javaHeapUsedMb: Double,
    val javaHeapMaxMb: Double,
    val systemAvailMb: Double,
    val lowMemory: Boolean,
)

/**
 * `Debug.getMemoryInfo` is the expensive call and it runs once per interval on the collector
 * thread — never on the main thread and never per observation.
 */
internal class MemoryCollector(private val app: Application) {

    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun sample(tSec: Double): MemorySample {
        val dbg = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()

        // getMemoryStat is API 23+ and supplies the graphics split, which is where the wallpaper
        // and GL-derived apps actually spend. Below that the split is unavailable, not zero.
        fun stat(k: String): Double =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                (dbg.getMemoryStat(k)?.toDoubleOrNull() ?: 0.0) / 1024.0
            else 0.0

        val dalvik = dbg.dalvikPss / 1024.0
        val native = dbg.nativePss / 1024.0
        val graphics = stat("summary.graphics")
        val total = dbg.totalPss / 1024.0

        return MemorySample(
            tSec = tSec,
            pssTotalMb = total,
            pssDalvikMb = dalvik,
            pssNativeMb = native,
            pssGraphicsMb = graphics,
            pssOtherMb = (total - dalvik - native - graphics).coerceAtLeast(0.0),
            javaHeapUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
            javaHeapMaxMb = rt.maxMemory() / 1048576.0,
            systemAvailMb = sys.availMem / 1048576.0,
            lowMemory = sys.lowMemory,
        )
    }
}

/**
 * Exists mainly to protect conclusions rather than to report a number. A capture taken at MODERATE
 * or worse describes a throttled device, and the verdict engine downgrades confidence on that
 * basis. API 29+; below that the status is absent and printed as absent.
 */
internal class ThermalCollector(private val app: Application) {

    private val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private var batteryStart: Int? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start(onChange: (ThermalStatus) -> Unit) {
        batteryStart = batteryPct()
        if (!supported) return
        onChange(map(pm.currentThermalStatus))
        listener = PowerManager.OnThermalStatusChangedListener { onChange(map(it)) }
            .also { pm.addThermalStatusListener(it) }
    }

    fun stop() {
        if (supported) listener?.let { pm.removeThermalStatusListener(it) }
        listener = null
    }

    fun section(transitions: List<Pair<Double, ThermalStatus>>, durationSec: Double): PowerSection? {
        if (!supported && transitions.isEmpty()) return null
        val windows = transitions.sortedBy { it.first }.mapIndexed { i, (t, s) ->
            val end = transitions.getOrNull(i + 1)?.first ?: durationSec
            ThermalWindow(t, s, (end - t).coerceAtLeast(0.0))
        }
        return PowerSection(
            windows = windows,
            batteryStartPct = batteryStart,
            batteryEndPct = batteryPct(),
            charging = isCharging(),
            powerSave = pm.isPowerSaveMode,
            headroomEnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                runCatching { pm.getThermalHeadroom(0).toDouble() }.getOrNull() else null,
        )
    }

    private fun map(v: Int): ThermalStatus = when (v) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.UNKNOWN
    }

    private fun battery(): Intent? =
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryPct(): Int? = battery()?.let {
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    private fun isCharging(): Boolean = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        ?: false
}

internal object DeviceContext {

    fun read(app: Application): DeviceInfo {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") val display = wm.defaultDisplay
        val dm = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        // The ACTUAL current refresh rate, not the mode maximum. A 120 Hz panel running at 60 for a
        // battery saver changes every vsync-derived budget and silently invalidates a comparison
        // against an earlier run — the most common way to misread this data.
        val current = display.refreshRate.toDouble()
        val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            display.supportedModes.maxOfOrNull { it.refreshRate.toDouble() } ?: current else current

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            codename = Build.DEVICE,
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = mi.totalMem / 1048576,
            availRamMbAtStart = mi.availMem / 1048576,
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            densityDpi = dm.densityDpi,
            refreshHz = current,
            panelMaxHz = max,
            rooted = looksRooted(),
        )
    }

    fun appInfo(app: Application): AppInfo {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        val debuggable = (app.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return AppInfo(
            applicationId = app.packageName,
            versionName = pi.versionName ?: "?",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong(),
            buildType = if (debuggable) "debug" else "release",
            debuggable = debuggable,
            gitSha = buildConfigString(app, "GIT_SHA"),
            gitDirty = buildConfigString(app, "GIT_DIRTY") == "true",
        )
    }

    /** Reflective, so the module needs no knowledge of the app's BuildConfig class. */
    private fun buildConfigString(app: Application, field: String): String? = runCatching {
        Class.forName("${app.packageName}.BuildConfig").getField(field).get(null) as? String
    }.getOrNull()

    /** Best-effort and reported as such — context for reading the numbers, not a security check. */
    private fun looksRooted(): Boolean = listOf(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/su", "/su/bin/su",
    ).any { File(it).exists() }
}

/**
 * Cold/warm/hot start from process start plus the first activity's lifecycle. Attaches at install()
 * so it can see the first Activity.onCreate — which means install() belongs at the top of
 * Application.onCreate, not lazily somewhere later.
 *
 * Profiles with no user-visible start-up (service, stream) disable the corresponding targets, so an
 * absent value there is expected rather than a gap.
 */
internal object StartupTracker : android.app.Application.ActivityLifecycleCallbacks {

    private var processStartMs = 0L
    private var appOnCreateMs = 0L
    private var firstActivityCreateMs = 0L
    private var firstResumeMs = 0L
    private var firstFrameMs = 0L
    private var activityCount = 0
    private var warmMs: Double? = null
    private var hotMs: Double? = null
    private var resumeStartMs = 0L

    fun attach(app: android.app.Application) {
        appOnCreateMs = SystemClock.elapsedRealtime()
        processStartMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            Process.getStartElapsedRealtime() else appOnCreateMs
        app.registerActivityLifecycleCallbacks(this)
    }

    fun onFirstFrame() { if (firstFrameMs == 0L) firstFrameMs = SystemClock.elapsedRealtime() }

    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
        if (firstActivityCreateMs == 0L) firstActivityCreateMs = SystemClock.elapsedRealtime()
        activityCount++
    }

    override fun onActivityStarted(a: android.app.Activity) {
        resumeStartMs = SystemClock.elapsedRealtime()
    }

    override fun onActivityResumed(a: android.app.Activity) {
        val now = SystemClock.elapsedRealtime()
        if (firstResumeMs == 0L) { firstResumeMs = now; onFirstFrame(); return }
        // Warm: the process lived but the activity was recreated. Hot: both survived.
        val delta = (now - resumeStartMs).toDouble()
        if (activityCount > 1) warmMs = delta else hotMs = delta
    }

    override fun onActivityPaused(a: android.app.Activity) {}
    override fun onActivityStopped(a: android.app.Activity) {}
    override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
    override fun onActivityDestroyed(a: android.app.Activity) { activityCount-- }

    fun section(): StartupSection? {
        if (firstFrameMs == 0L) return StartupSection(null, emptyList(), warmMs, hotMs)
        fun d(a: Long, b: Long) = (b - a).toDouble()
        val phases = buildList {
            add(StartupPhase("Process start → Application.onCreate entry",
                d(processStartMs, appOnCreateMs), d(processStartMs, appOnCreateMs)))
            if (firstActivityCreateMs > 0) add(StartupPhase("→ first Activity.onCreate",
                d(appOnCreateMs, firstActivityCreateMs), d(processStartMs, firstActivityCreateMs)))
            if (firstResumeMs > 0) add(StartupPhase("Activity.onCreate → onResume",
                d(firstActivityCreateMs, firstResumeMs), d(processStartMs, firstResumeMs)))
            add(StartupPhase("→ first frame drawn",
                d(maxOf(firstResumeMs, appOnCreateMs), firstFrameMs), d(processStartMs, firstFrameMs)))
        }
        return StartupSection(d(processStartMs, firstFrameMs), phases, warmMs, hotMs)
    }
}
