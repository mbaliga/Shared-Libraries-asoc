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
    // Bookkeeping for the hard N-attempt ceiling on a single pending report (see
    // bumpAttemptAndCheckCeiling) — separate from the streak file, which counts something
    // different (consecutive crashes close together in time, not repeated recovery launches
    // for the same unresolved report).
    private const val ATTEMPT_FILE_NAME = "crash_recovery_attempts.txt"
    // The durable "recovery screen started building its own UI and hasn't finished" marker
    // (see markRecoveryEntryStarted). SharedPreferences + commit() rather than a plain file:
    // this has to survive a death that happens WHILE it's being written far more reliably than
    // this module's other files need to, since it exists specifically to detect that kind of
    // death on the next launch.
    private const val ENTRY_PREFS_NAME = "dev.aarso.crashrecovery.entry"
    private const val ENTRY_KEY = "recovery_entry_in_progress"

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
        // A cleared report has nothing left to count attempts against — forget it too, so a
        // genuinely new crash later starts its own ceiling at 1 rather than inheriting this
        // one's count (identityOf would already prevent that, but there's no reason to keep
        // the file around either).
        clearAttemptCeiling(context)
    }

    // --- durable "recovery screen started its own onCreate" marker ---

    private fun entryPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(ENTRY_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Call at the very top of [CrashRecoveryActivity.onCreate], before anything that could
     * throw. `commit()`, not `apply()`, on purpose: this marker only earns its keep if it is
     * actually on disk before the risky UI-building work below it runs, even if the process
     * dies immediately after — an `apply()` write can still be queued, not yet persisted, when
     * that happens.
     */
    fun markRecoveryEntryStarted(context: Context) {
        runCatching { entryPrefs(context).edit().putBoolean(ENTRY_KEY, true).commit() }
    }

    /**
     * Call once [CrashRecoveryActivity]'s own UI has actually finished — `setContentView`
     * succeeded, or the user resolved the report via Continue/Discard/Reset.
     */
    fun clearRecoveryEntryMarker(context: Context) {
        runCatching { entryPrefs(context).edit().putBoolean(ENTRY_KEY, false).commit() }
    }

    /**
     * True when a PRIOR launch set [markRecoveryEntryStarted] and never reached
     * [clearRecoveryEntryMarker] — proof the recovery screen itself didn't even finish starting
     * up last time (e.g. a native crash during view inflation that no `runCatching` inside it
     * could catch). Showing it again would just repeat the same failure.
     */
    fun recoveryEntryMarkerIsStale(context: Context): Boolean =
        runCatching { entryPrefs(context).getBoolean(ENTRY_KEY, false) }.getOrDefault(false)

    // --- hard N-attempt ceiling for a single pending report ---

    private fun attemptFile(context: Context): File = File(context.applicationContext.filesDir, ATTEMPT_FILE_NAME)

    private fun readAttempt(context: Context): Pair<String?, Int> = runCatching {
        val parts = attemptFile(context).takeIf { it.exists() }?.readText()?.split('\t') ?: return null to 0
        (parts.getOrNull(0)?.takeIf { it.isNotEmpty() }) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }.getOrDefault(null to 0)

    /**
     * Bumps the attempt count for [identity] (the currently pending report — see
     * [CrashReport.identityOf]) and returns whether the ceiling is now exceeded. See
     * [CrashReport.nextAttemptCount] / [CrashReport.attemptCeilingExceeded] for the pure rule.
     */
    private fun bumpAttemptAndCheckCeiling(context: Context, identity: String): Boolean = runCatching {
        val (prevIdentity, prevCount) = readAttempt(context)
        val next = CrashReport.nextAttemptCount(prevIdentity, prevCount, identity)
        attemptFile(context).writeText("$identity\t$next")
        CrashReport.attemptCeilingExceeded(next)
    }.getOrDefault(false)

    /** Forget the attempt ceiling bookkeeping — folded into [clear] since it tracks attempts
     * against a specific pending report, and a cleared report has none left to track. */
    fun clearAttemptCeiling(context: Context) {
        runCatching { attemptFile(context).delete() }
    }

    /** A stable identity for whatever report is currently pending on disk, or null if none. */
    private fun pendingIdentity(context: Context): String? = runCatching {
        val text = file(context).takeIf { it.exists() }?.readText() ?: return null
        CrashReport.identityOf(CrashReport.decode(text).whenMillis, text)
    }.getOrNull()

    // --- shared "give up on recovery" remediation ---

    /**
     * The one remediation shared by every loop-breaker below: clear the pending report, clear
     * the streak, clear the entry marker, optionally relaunch the real app, and always run
     * [then] last. Plain function references rather than Android types on purpose, so the
     * sequencing — and its failure tolerance — is unit-testable without a device: a crash
     * INSIDE the remediation itself (any one of these throwing) must never be able to stop the
     * rest of it, matching this module's own "every operation is runCatching-guarded" rule. If
     * this fix's own cleanup became a new crash-loop surface, it would defeat the entire point.
     */
    internal fun giveUpOnRecovery(
        clearReport: () -> Unit,
        clearStreak: () -> Unit,
        clearEntryMarker: () -> Unit,
        relaunch: (() -> Unit)? = null,
        then: () -> Unit = {},
    ) {
        runCatching { clearReport() }
        runCatching { clearStreak() }
        runCatching { clearEntryMarker() }
        if (relaunch != null) runCatching { relaunch() }
        then()
    }

    /**
     * Call first thing in your launcher Activity's `onCreate`. If a crash is pending, this
     * starts [CrashRecoveryActivity] and **finishes the calling activity** — it was the one
     * that (or whose process) crashed last time, so it's left in a half-initialized state
     * (no `setContent`/`setContentView` called); finishing it means "Continue" on the
     * recovery screen relaunches a clean instance instead of returning to a blank one.
     * Returns `true` when recovery was shown (the caller should `return` immediately without
     * building its real UI), `false` when there's nothing to recover from.
     *
     * Two independent loop-breakers can also make this return `false` even though a report
     * *is* pending, each giving up on recovery and clearing the report so the real app loads
     * instead:
     *  - [recoveryEntryMarkerIsStale] — the recovery screen's own `onCreate` didn't even finish
     *    on the prior launch, so showing it again would repeat that same failure.
     *  - the N-attempt ceiling — the SAME report is still pending after
     *    [CrashReport.MAX_RECOVERY_ATTEMPTS] calls to this function, so the real app itself is
     *    assumed to be what's persistently broken, not this screen.
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

        // The recovery screen itself didn't even finish starting up last time (see
        // markRecoveryEntryStarted) — showing it again would just repeat the SAME failure on a
        // loop with no in-app escape, since its own Reset button is built by the very onCreate
        // call that's failing. Fall through to the real app instead.
        if (recoveryEntryMarkerIsStale(activity)) {
            giveUpOnRecovery(
                clearReport = { clear(activity) },
                clearStreak = { clearStreak(activity) },
                clearEntryMarker = { clearRecoveryEntryMarker(activity) },
            )
            return false
        }

        // Hard ceiling, independent of CrashReport.STREAK_WINDOW_MS: the SAME report still
        // unresolved after repeated launches means the real app itself keeps crashing, not just
        // this screen — the streak window alone can't catch that (a user reopening minutes
        // apart never re-enters a 60s window at all).
        val identity = pendingIdentity(activity)
        if (identity != null && bumpAttemptAndCheckCeiling(activity, identity)) {
            giveUpOnRecovery(
                clearReport = { clear(activity) },
                clearStreak = { clearStreak(activity) },
                clearEntryMarker = { clearRecoveryEntryMarker(activity) },
            )
            return false
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
