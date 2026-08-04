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
        if (pending(activity) == null) return false
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
