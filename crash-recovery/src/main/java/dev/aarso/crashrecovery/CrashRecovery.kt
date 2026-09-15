package dev.aarso.crashrecovery

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * The shared crash-recovery utility: **install** an uncaught-exception handler that
 * captures a device-only launch/runtime crash to a file (CI never sees these — CI runs
 * unit tests, never launches the app), then **recover** on the next launch by showing
 * [CrashRecoveryActivity] instead of the app's real content.
 *
 * Every operation is `runCatching`-guarded so the handler can never itself crash, and
 * nothing here is ever sent anywhere — the report lives in the app's private files dir
 * until the user explicitly shares or copies it from the recovery screen.
 *
 * Usage — call once from [Application.onCreate], before constructing anything that could
 * itself throw:
 * ```
 * CrashRecovery.install(this, appLabel = "Runout")
 * ```
 * then, first thing in the launcher `Activity.onCreate`:
 * ```
 * if (CrashRecovery.maybeShowRecovery(this, appLabel = "Runout")) return
 * ```
 */
object CrashRecovery {
    private const val FILE_NAME = "crash_recovery_report.txt"
    // Consecutive-crash streak, kept separate from the report so tapping Continue (which
    // clears the report) leaves the streak intact — that's what lets a crash-loop be detected
    // across the Continue -> relaunch -> re-crash cycle. Only a genuinely later crash (outside
    // the window) or an explicit reset starts it over.
    private const val STREAK_FILE_NAME = "crash_recovery_streak.txt"
    // Watermark for ApplicationExitInfo-derived reports (see pendingExitDeath): the timestamp of
    // the newest historical exit we have already surfaced, so a native crash or ANR is reported
    // exactly once and never re-shown on every subsequent launch.
    private const val EXIT_SEEN_FILE_NAME = "crash_recovery_exit_seen.txt"
    // A historical exit older than this is stale context, not news — don't resurface it.
    private const val EXIT_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    // Quarantine (non-destructive reset): the app's state is moved into this dir under dataDir
    // instead of being wiped, and a tiny meta file tracks it so it can be restored or purged.
    private const val QUARANTINE_DIR = "cr_quarantine"
    private const val QUARANTINE_META_FILE = "crash_recovery_quarantine.txt"
    // Clean launches after a quarantine before its set-aside copy is auto-purged. Two clean
    // opens ≈ "the app works again" — enough to reclaim the disk without dropping the safety net
    // during the crash-loop window.
    private const val HEALTHY_LAUNCHES = 2

    /**
     * Optional host bridge for saving/restoring the app's real data off-device (see
     * [CrashSalvager]). Volatile: set from the app's main thread, read from the recovery screen.
     */
    @Volatile
    private var salvager: CrashSalvager? = null

    /** Register (or clear) the app's data salvager. Call once, e.g. from `Application.onCreate`. */
    fun setSalvager(salvager: CrashSalvager?) {
        this.salvager = salvager
    }

    /** The registered salvager, if any — the recovery screen uses it to offer backup/restore. */
    fun salvager(): CrashSalvager? = salvager

    /** Installs the handler. Chains to any previously-installed handler so this composes. */
    fun install(app: Application, appLabel: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { capture(app, appLabel, throwable, thread.name) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * For a failure that happens synchronously during your own init (e.g. a DI container
     * that throws in its constructor) — call this from a `catch` block instead of letting
     * it propagate, so the recovery screen has a trace even though nothing crashed the
     * process outright.
     */
    fun captureInitError(context: Context, appLabel: String, throwable: Throwable) {
        runCatching { capture(context, appLabel, throwable, Thread.currentThread().name) }
    }

    private fun capture(context: Context, appLabel: String, throwable: Throwable, threadName: String) {
        val now = System.currentTimeMillis()
        val report = CrashReport.of(
            appLabel = appLabel,
            whenMillis = now,
            threadName = threadName,
            throwable = throwable,
            device = deviceInfo(context),
        )
        file(context).writeText(report.encode())
        bumpStreak(context, now)
        android.util.Log.e("CrashRecovery", "captured crash for $appLabel", throwable)
    }

    // --- consecutive-crash streak (for loop-gated recovery affordances) ---

    private fun bumpStreak(context: Context, nowMillis: Long) {
        runCatching {
            val (prevCount, prevMillis) = readStreak(context)
            val next = CrashReport.nextStreakCount(prevCount, prevMillis, nowMillis)
            streakFile(context).writeText("$next\t$nowMillis")
        }
    }

    private fun readStreak(context: Context): Pair<Int, Long> = runCatching {
        val parts = streakFile(context).takeIf { it.exists() }?.readText()?.split('\t') ?: return 0 to 0L
        (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault(0 to 0L)

    /**
     * How many times the app has crashed in a row (crashes within [CrashReport.STREAK_WINDOW_MS]
     * of each other). `1` on a first/isolated crash, `>= 2` once a crash has recurred after the
     * user already tried to Continue — the signal a recovery screen uses to offer a reset only
     * when it's actually warranted.
     */
    fun consecutiveCount(context: Context): Int = readStreak(context).first

    /** Forget the streak — after a reset, or when a host knows the app has recovered cleanly. */
    fun clearStreak(context: Context) {
        runCatching { streakFile(context).delete() }
    }

    // --- non-destructive reset: quarantine the app's data instead of wiping it ---

    /**
     * Reset the app to a clean state WITHOUT deleting anything: move its on-disk state aside
     * (see [Quarantine]) so the next launch boots fresh, while every byte stays recoverable via
     * [restoreQuarantine]. The set-aside copy auto-purges once the app has run healthy (see
     * [notifyHealthyLaunch]). This is the reset the recovery screen performs — never a bare wipe.
     *
     * Returns true if data was set aside. The caller should relaunch the app afterwards.
     */
    fun quarantineAndReset(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        clear(app)        // drop the report + advance the exit watermark
        clearStreak(app)
        val dataDir = app.dataDir
        val root = File(dataDir, QUARANTINE_DIR)
        val stamp = System.currentTimeMillis().toString()
        // Keep our own bookkeeping dir out of the move so recovery state survives the reset.
        val dest = Quarantine.stash(dataDir, root, stamp, keep = emptySet()) ?: return false
        // `files/` (which held our report/streak/watermark) was just stashed; recreate the fresh
        // bookkeeping so the clean boot isn't immediately re-flagged as a crash to recover from.
        app.filesDir.mkdirs()
        runCatching { exitSeenFile(app).writeText(System.currentTimeMillis().toString()) }
        quarantineMetaFile(app).writeText("${dest.name}\t${System.currentTimeMillis()}\t0")
        true
    }.getOrDefault(false)

    /** True if a recoverable set-aside copy exists from a prior [quarantineAndReset]. */
    fun hasRecoverableData(context: Context): Boolean {
        val meta = readQuarantineMeta(context) ?: return false
        return File(File(context.applicationContext.dataDir, QUARANTINE_DIR), meta.stamp).exists()
    }

    /** Total bytes currently held in quarantine (for a human-readable summary), or 0. */
    fun recoverableSizeBytes(context: Context): Long = runCatching {
        Quarantine.sizeBytes(File(context.applicationContext.dataDir, QUARANTINE_DIR))
    }.getOrDefault(0L)

    /**
     * Put the set-aside data back where it was, replacing the clean state, and clear the
     * quarantine. Returns true on success. The caller should relaunch the app afterwards.
     */
    fun restoreQuarantine(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val root = File(app.dataDir, QUARANTINE_DIR)
        val stamp = Quarantine.latest(root) ?: return false
        val ok = Quarantine.restore(stamp, app.dataDir)
        Quarantine.purge(root)
        runCatching { quarantineMetaFile(app).delete() }
        ok
    }.getOrDefault(false)

    /** Permanently drop the set-aside copy (the user chose not to keep it). */
    fun discardQuarantine(context: Context) {
        val app = context.applicationContext
        Quarantine.purge(File(app.dataDir, QUARANTINE_DIR))
        runCatching { quarantineMetaFile(app).delete() }
    }

    /**
     * Record that the app launched cleanly. After [HEALTHY_LAUNCHES] such launches following a
     * quarantine, its set-aside copy is auto-purged to reclaim disk — the app is evidently
     * working again. Called automatically from [maybeShowRecovery] when there's nothing to
     * recover; a host can also call it from a known-good point.
     */
    fun notifyHealthyLaunch(context: Context) {
        runCatching {
            val meta = readQuarantineMeta(context) ?: return
            val next = meta.cleanLaunches + 1
            if (next >= HEALTHY_LAUNCHES) {
                discardQuarantine(context)
            } else {
                quarantineMetaFile(context).writeText("${meta.stamp}\t${meta.created}\t$next")
            }
        }
    }

    private data class QMeta(val stamp: String, val created: Long, val cleanLaunches: Int)

    private fun readQuarantineMeta(context: Context): QMeta? = runCatching {
        val parts = quarantineMetaFile(context).takeIf { it.exists() }?.readText()?.split('\t')
            ?: return null
        QMeta(
            stamp = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null,
            created = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
            cleanLaunches = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Long = info.versionCode.toLong()

    private fun deviceInfo(context: Context): CrashReport.DeviceInfo = runCatching {
        val app = context.applicationContext
        val pm = app.packageManager
        val pkg = app.packageName
        val info = pm.getPackageInfo(pkg, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
        val (freeMb, totalMb) = memoryMb(app)
        CrashReport.DeviceInfo(
            appVersionName = info.versionName,
            appVersionCode = versionCode,
            osSdkInt = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER ?: "?",
            deviceModel = Build.MODEL ?: "?",
            packageName = pkg,
            installSource = installSource(app, pkg),
            freeMemMb = freeMb,
            totalMemMb = totalMb,
        )
    }.getOrDefault(CrashReport.DeviceInfo(null, null, Build.VERSION.SDK_INT, "?", "?"))

    /** Free / total device RAM in MB at capture time — the metadata an OOM report lives or dies by. */
    private fun memoryMb(context: Context): Pair<Long?, Long?> = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.availMem / (1024 * 1024)) to (mi.totalMem / (1024 * 1024))
    }.getOrDefault(null to null)

    /** A human-readable install origin ("Play Store", "Sideloaded", …), never an identifier. */
    @Suppress("DEPRECATION")
    private fun installSource(context: Context, pkg: String): String? = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(pkg).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(pkg)
        }
        when (installer) {
            null -> "Sideloaded"
            "com.android.vending" -> "Play Store"
            "com.amazon.venezia" -> "Amazon Appstore"
            "com.sec.android.app.samsungapps" -> "Galaxy Store"
            "org.fdroid.fdroid" -> "F-Droid"
            "com.google.android.packageinstaller",
            "com.android.packageinstaller" -> "Sideloaded"
            else -> installer
        }
    }.getOrNull()

    /** Non-null if a crash was captured and not yet cleared. */
    fun pending(context: Context): CrashReport.Decoded? = runCatching {
        file(context).takeIf { it.exists() }?.readText()?.let(CrashReport::decode)
    }.getOrNull()

    /**
     * Deaths the JVM handler can never see — **native crashes** (a `SIGSEGV` inside a bundled
     * `.so`; the uncaught-exception handler simply never runs, so no report file is written)
     * and **ANR kills**. Before this existed, such a death produced the worst possible outcome:
     * the OS shows its own "keeps stopping" dialog, our recovery screen never appears (there is
     * no report to show), and a native crash *during launch* becomes an unbreakable crash loop
     * the user can only escape by clearing app data blind.
     *
     * Android 11+ records every process death in [android.app.ApplicationExitInfo]; this reads
     * that history and synthesizes a report for the newest death worth surfacing. Only
     * crash-shaped reasons qualify — `REASON_CRASH_NATIVE`, `REASON_ANR`, and `REASON_CRASH`
     * when no JVM report was written (handler died before persisting). Background low-memory
     * kills are routine process churn, not crashes, and are deliberately ignored.
     *
     * A watermark file makes each death surface exactly once, and anything older than 7 days is
     * treated as stale context rather than news. Below API 30 this returns null — the JVM-crash
     * path is unchanged and still works everywhere.
     */
    fun captureExitDeath(context: Context, appLabel: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 30) return false
        // A pending JVM report is richer than anything the exit history can add — and writing
        // over it would destroy a real stack trace. First come, first served.
        if (pending(context) != null) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val history = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val seenUpTo = runCatching { exitSeenFile(context).readText().trim().toLong() }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val death = history.firstOrNull { info ->
            info.timestamp > seenUpTo &&
                now - info.timestamp < EXIT_MAX_AGE_MILLIS &&
                when (info.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                    android.app.ApplicationExitInfo.REASON_ANR,
                    // The handler died before persisting its own report (pending() was null
                    // above) — the bare exit record is all that remains; better than silence.
                    android.app.ApplicationExitInfo.REASON_CRASH,
                    -> true
                    else -> false
                }
        } ?: return false
        // Advance the watermark BEFORE writing the report: even if the write fails, this death
        // must never become its own recurring report on every subsequent launch.
        runCatching { exitSeenFile(context).writeText(death.timestamp.toString()) }
        val reasonLabel = when (death.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            android.app.ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
            else -> "Crash"
        }
        val report = CrashReport.ofExitDeath(
            appLabel = appLabel,
            reasonLabel = reasonLabel,
            description = death.description,
            whenMillis = death.timestamp,
            device = deviceInfo(context),
        )
        // Persist through the SAME file as a JVM crash: CrashRecoveryActivity re-reads
        // pending() itself, so routing through the one store means the whole recovery flow
        // (show, share, copy, clear, streak-gated reset) works for these deaths unchanged.
        file(context).writeText(report.encode())
        bumpStreak(context, death.timestamp)
        true
    }.getOrDefault(false)

    private fun exitSeenFile(context: Context): File =
        File(context.applicationContext.filesDir, EXIT_SEEN_FILE_NAME)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        // Mark every death up to now as already seen. A JVM crash also leaves an OS exit record
        // (REASON_CRASH); without this, the next launch's captureExitDeath would rediscover the
        // very crash the user just dismissed and re-show recovery in a loop. Advancing the
        // watermark on dismissal (Continue / Discard / reset) is what makes "Continue" stick.
        runCatching { exitSeenFile(context).writeText(System.currentTimeMillis().toString()) }
    }

    /**
     * Call first thing in your launcher Activity's `onCreate`. If a crash is pending, this
     * starts [CrashRecoveryActivity] and **finishes the calling activity** — it was the one
     * that (or whose process) crashed last time, so it's left in a half-initialized state
     * (no `setContent`/`setContentView` called); finishing it means "Continue" on the
     * recovery screen relaunches a clean instance instead of returning to a blank one.
     * Returns `true` when recovery was shown (the caller should `return` immediately without
     * building its real UI), `false` when there's nothing to recover from.
     */
    fun maybeShowRecovery(
        activity: Activity,
        appLabel: String,
        style: CrashRecoveryStyle = CrashRecoveryStyle.Default,
        contactEmail: String? = null,
    ): Boolean {
        if (pending(activity) == null) {
            // No JVM report — check whether the OS recorded a death the handler couldn't see
            // (native crash, ANR). If it did, this synthesizes and persists a report through
            // the same store, and recovery proceeds identically.
            if (!captureExitDeath(activity, appLabel)) {
                notifyHealthyLaunch(activity) // a clean launch counts toward auto-purging any quarantine
                return false
            }
            if (pending(activity) == null) {
                notifyHealthyLaunch(activity)
                return false
            }
        }
        activity.startActivity(CrashRecoveryActivity.intent(activity, appLabel, style, contactEmail))
        activity.finish()
        return true
    }

    /**
     * Launches the recovery screen with sample content — no real crash, no disk read or write —
     * so the UX (tone, layout, Share/Copy) can be reviewed without having to actually crash the
     * app. Wire this to a debug-only affordance (e.g. a long-press on the version number in an
     * About/Settings screen); it should never be reachable from a release build's normal UI.
     *
     * Share and Copy work for real in preview, so the shared text can be reviewed as users would
     * receive it. Reset and Continue are deliberately inert (see [CrashRecoveryActivity]), so
     * previewing the screen can never wipe app data or restart anything.
     */
    fun previewIntent(
        context: Context,
        appLabel: String,
        style: CrashRecoveryStyle = CrashRecoveryStyle.Default,
        contactEmail: String? = null,
    ): Intent = CrashRecoveryActivity.intent(context, appLabel, style, contactEmail, preview = true)

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    private fun streakFile(context: Context): File = File(context.applicationContext.filesDir, STREAK_FILE_NAME)

    private fun quarantineMetaFile(context: Context): File =
        File(context.applicationContext.filesDir, QUARANTINE_META_FILE)
}
