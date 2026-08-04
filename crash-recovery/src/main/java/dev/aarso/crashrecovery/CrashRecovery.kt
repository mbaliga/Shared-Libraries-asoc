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
            if (!captureExitDeath(activity, appLabel)) return false
            if (pending(activity) == null) return false
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
}
