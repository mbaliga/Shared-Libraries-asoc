package dev.aarso.crashrecovery

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A device-only crash, captured once and reread on the next launch. This module is a
 * reliability utility, not a design-system dependency — it has no dependency on `:hyle`
 * and imposes no visual language, so apps with their own visual identity (that must
 * never depend on Hyle) can still take this one dependency (see Personal-Tracker
 * DECISIONS.md D-O).
 *
 * The report is structured so the recovery screen can show it in readable sections
 * (Error / App / Device / Stack trace) and a one-line [plainLanguage] summary — while the
 * exact same text a user would read is what gets shared, word for word (see [render]).
 * [encode]/[decode] persist every field so the next launch can rebuild those sections
 * without re-parsing prose.
 */
data class CrashReport(
    val appLabel: String,
    val whenMillis: Long,
    val threadName: String,
    val excType: String,
    val excMessage: String?,
    val plainLanguage: String,
    val device: DeviceInfo,
    val trace: String,
) {
    /** `ExceptionType: message` (message omitted if blank) — the one line worth reading first. */
    val headline: String
        get() = if (!excMessage.isNullOrBlank()) "$excType: $excMessage" else excType

    data class DeviceInfo(
        val appVersionName: String?,
        val appVersionCode: Long?,
        val osSdkInt: Int,
        val deviceManufacturer: String,
        val deviceModel: String,
        val packageName: String = "",
        val installSource: String? = null,
        val freeMemMb: Long? = null,
        val totalMemMb: Long? = null,
    )

    /** The full human-readable report — what gets shared or copied, word for word. */
    fun render(): String = renderReport(
        appLabel = appLabel,
        whenMillis = whenMillis,
        threadName = threadName,
        excType = excType,
        excMessage = excMessage,
        plainLanguage = plainLanguage,
        device = device,
        trace = trace,
    )

    /**
     * Persistence encoding (v2): a machine-parseable header of `key\tvalue` lines, a
     * `---TRACE---` marker, then the raw (multi-line) stack trace. [decode] rebuilds every
     * structured field from this; a file that doesn't match is still handled best-effort.
     */
    fun encode(): String = buildString {
        append(MAGIC).append('\n')
        fun kv(k: String, v: String?) {
            append(k).append('\t').append((v ?: "").replace("\n", " ")).append('\n')
        }
        kv("appLabel", appLabel)
        kv("whenMillis", whenMillis.toString())
        kv("threadName", threadName)
        kv("excType", excType)
        kv("excMessage", excMessage)
        kv("plainLanguage", plainLanguage)
        kv("versionName", device.appVersionName)
        kv("versionCode", device.appVersionCode?.toString())
        kv("packageName", device.packageName)
        kv("installSource", device.installSource)
        kv("osSdkInt", device.osSdkInt.toString())
        kv("manufacturer", device.deviceManufacturer)
        kv("model", device.deviceModel)
        kv("freeMemMb", device.freeMemMb?.toString())
        kv("totalMemMb", device.totalMemMb?.toString())
        append(TRACE_MARKER).append('\n')
        append(trace)
    }

    companion object {
        private const val MAGIC = "CRASHv2"
        private const val TRACE_MARKER = "---TRACE---"

        /**
         * Two crashes within this window count as consecutive — the same failure recurring,
         * not two unrelated incidents. A crash-loop (crash → Continue → relaunch → crash) turns
         * over in seconds, so a minute comfortably catches it while a crash days apart resets.
         */
        const val STREAK_WINDOW_MS = 60_000L

        /**
         * The consecutive-crash count after a new crash at [nowMillis], given the previous
         * [prevCount] captured at [prevMillis]. Increments only when the new crash lands inside
         * [windowMs] of the last one; otherwise it's a fresh incident (count 1). A backwards
         * clock (now before prev) is treated as fresh, never as a continuation.
         *
         * Pure and side-effect-free so the loop-detection rule is unit-testable without Android.
         */
        fun nextStreakCount(
            prevCount: Int,
            prevMillis: Long,
            nowMillis: Long,
            windowMs: Long = STREAK_WINDOW_MS,
        ): Int = if (prevCount > 0 && prevMillis > 0 && (nowMillis - prevMillis) in 0..windowMs) {
            prevCount + 1
        } else {
            1
        }

        /** First line worth reading: `ExceptionType: message` (message omitted if blank). */
        fun headlineOf(throwable: Throwable): String {
            val type = throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }
            val message = throwable.message?.takeIf { it.isNotBlank() }
            return if (message != null) "$type: $message" else type
        }

        fun typeOf(throwable: Throwable): String =
            throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }

        fun stackTraceOf(throwable: Throwable): String =
            StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        /**
         * A calm, non-technical sentence for the top of the screen — keyed on the failure
         * kind, with a generic fallback. Deliberately app-agnostic: it never guesses what the
         * app was doing, only what the platform did about it.
         */
        fun plainLanguageFor(throwable: Throwable): String {
            var t: Throwable? = throwable
            // Unwrap common wrappers to reach the real cause.
            while (t?.cause != null && (t is java.lang.RuntimeException && t.javaClass == java.lang.RuntimeException::class.java)) {
                t = t.cause
            }
            val cause = t ?: throwable
            return when {
                cause is OutOfMemoryError ->
                    "The app needed more memory than your device could give it, and Android had to stop it."
                cause is StackOverflowError ->
                    "The app got stuck repeating itself until it ran out of room, so Android stopped it."
                cause is java.lang.NullPointerException ->
                    "The app expected something to be there that wasn't, and had to close."
                cause is java.util.concurrent.TimeoutException ->
                    "Part of the app took too long to respond, so it was stopped."
                cause is java.io.IOException ->
                    "The app had trouble reading or writing data and had to close."
                cause is SecurityException ->
                    "The app tried to do something it didn't have permission for, and had to close."
                cause is Error ->
                    "The app hit a low-level error it couldn't recover from and had to close."
                else ->
                    "The app ran into an unexpected error and had to close."
            }
        }

        fun of(
            appLabel: String,
            whenMillis: Long,
            threadName: String,
            throwable: Throwable,
            device: DeviceInfo,
        ): CrashReport = CrashReport(
            appLabel = appLabel,
            whenMillis = whenMillis,
            threadName = threadName,
            excType = typeOf(throwable),
            excMessage = throwable.message?.takeIf { it.isNotBlank() },
            plainLanguage = plainLanguageFor(throwable),
            device = device,
            trace = stackTraceOf(throwable),
        )

        /**
         * A report for a process death the JVM handler never saw — a native crash or an ANR
         * kill, read back from [android.app.ApplicationExitInfo] on the next launch (see
         * `CrashRecovery.captureExitDeath`). There is no Java stack for these by definition;
         * [description] is whatever the OS recorded (a signal like `SIGSEGV`, or the ANR
         * subject line), so the "trace" section states honestly where the detail lives rather
         * than pretending a synthetic frame list is one.
         */
        fun ofExitDeath(
            appLabel: String,
            reasonLabel: String,
            description: String?,
            whenMillis: Long,
            device: DeviceInfo,
        ): CrashReport = CrashReport(
            appLabel = appLabel,
            whenMillis = whenMillis,
            threadName = "?",
            excType = reasonLabel,
            excMessage = description?.takeIf { it.isNotBlank() },
            plainLanguage = when {
                reasonLabel.startsWith("Native") ->
                    "$appLabel was stopped by a crash inside one of its native components — " +
                        "the kind Android records but apps cannot catch in the moment."
                reasonLabel.startsWith("App not responding") ->
                    "$appLabel stopped responding and Android closed it."
                else -> "$appLabel was closed by a crash."
            },
            device = device,
            trace = buildString {
                append("No Java stack trace exists for this kind of death.\n")
                append("Recorded by Android (ApplicationExitInfo):\n")
                append("  reason: ").append(reasonLabel).append('\n')
                append("  detail: ").append(description?.takeIf { it.isNotBlank() } ?: "(none recorded)")
            },
        )

        /**
         * Decode [encode]'s v2 format into a display-ready [Decoded] with every structured
         * field. Best-effort: any text that doesn't match (an older/foreign writer) still
         * yields a usable pair — the whole text as [Decoded.fullReport] and its first line as
         * [Decoded.headline] — so a decode quirk never hides a real crash behind a blank screen.
         */
        fun decode(persisted: String): Decoded {
            if (persisted.startsWith(MAGIC)) {
                runCatching { return decodeV2(persisted) }
            }
            // Fallback: legacy "headline\n\nfull" shape, or foreign text.
            val separator = "\n\n"
            val splitAt = persisted.indexOf(separator)
            val headline: String
            val full: String
            if (splitAt >= 0) {
                headline = persisted.substring(0, splitAt)
                full = persisted.substring(splitAt + separator.length)
            } else {
                headline = persisted.lines().firstOrNull().orEmpty()
                full = persisted
            }
            return Decoded(
                appLabel = "App",
                headline = headline,
                plainLanguage = "The app ran into an unexpected error and had to close.",
                excType = headline.substringBefore(":").ifBlank { headline },
                excMessage = headline.substringAfter(":", "").trim().ifBlank { null },
                threadName = "",
                whenMillis = null,
                versionName = null,
                versionCode = null,
                packageName = null,
                installSource = null,
                osSdkInt = null,
                deviceManufacturer = null,
                deviceModel = null,
                freeMemMb = null,
                totalMemMb = null,
                trace = full,
                fullReport = full,
            )
        }

        private fun decodeV2(persisted: String): Decoded {
            val markerIdx = persisted.indexOf("\n$TRACE_MARKER")
            val headerPart = if (markerIdx >= 0) persisted.substring(0, markerIdx) else persisted
            val trace = if (markerIdx >= 0) {
                persisted.substring(markerIdx + 1).removePrefix(TRACE_MARKER).removePrefix("\n")
            } else ""
            val kv = HashMap<String, String>()
            headerPart.lineSequence().drop(1).forEach { line ->
                val tab = line.indexOf('\t')
                if (tab >= 0) kv[line.substring(0, tab)] = line.substring(tab + 1)
            }
            fun s(k: String): String? = kv[k]?.takeIf { it.isNotEmpty() }
            val excType = s("excType") ?: "Error"
            val excMessage = s("excMessage")
            val headline = if (!excMessage.isNullOrBlank()) "$excType: $excMessage" else excType
            val device = DeviceInfo(
                appVersionName = s("versionName"),
                appVersionCode = s("versionCode")?.toLongOrNull(),
                osSdkInt = s("osSdkInt")?.toIntOrNull() ?: 0,
                deviceManufacturer = s("manufacturer") ?: "?",
                deviceModel = s("model") ?: "?",
                packageName = s("packageName") ?: "",
                installSource = s("installSource"),
                freeMemMb = s("freeMemMb")?.toLongOrNull(),
                totalMemMb = s("totalMemMb")?.toLongOrNull(),
            )
            val whenMillis = s("whenMillis")?.toLongOrNull()
            val appLabel = s("appLabel") ?: "App"
            val plain = s("plainLanguage") ?: "The app ran into an unexpected error and had to close."
            val fullReport = renderReport(
                appLabel = appLabel,
                whenMillis = whenMillis ?: 0L,
                threadName = s("threadName") ?: "",
                excType = excType,
                excMessage = excMessage,
                plainLanguage = plain,
                device = device,
                trace = trace,
            )
            return Decoded(
                appLabel = appLabel,
                headline = headline,
                plainLanguage = plain,
                excType = excType,
                excMessage = excMessage,
                threadName = s("threadName") ?: "",
                whenMillis = whenMillis,
                versionName = device.appVersionName,
                versionCode = device.appVersionCode,
                packageName = device.packageName.ifBlank { null },
                installSource = device.installSource,
                osSdkInt = device.osSdkInt.takeIf { it > 0 },
                deviceManufacturer = device.deviceManufacturer,
                deviceModel = device.deviceModel,
                freeMemMb = device.freeMemMb,
                totalMemMb = device.totalMemMb,
                trace = trace,
                fullReport = fullReport,
            )
        }

        /** The single source of truth for the shareable text — used by [render] and [decode]. */
        internal fun renderReport(
            appLabel: String,
            whenMillis: Long,
            threadName: String,
            excType: String,
            excMessage: String?,
            plainLanguage: String,
            device: DeviceInfo,
            trace: String,
        ): String = buildString {
            // A fresh SimpleDateFormat per call — it's not thread-safe, and this can run from a
            // crash handler on whatever thread just crashed, so no shared/static instance.
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val whenText = if (whenMillis > 0L) format.format(Date(whenMillis)) else "unknown"
            append("=== Crash report — ").append(appLabel).append(" ===\n")
            append("Generated on-device. No identifiers included.\n\n")
            append("What happened: ").append(plainLanguage).append("\n\n")
            append("Error:   ").append(excType).append('\n')
            append("Message: ").append(excMessage?.takeIf { it.isNotBlank() } ?: "(none)").append('\n')
            append("Thread:  ").append(threadName.ifBlank { "?" }).append('\n')
            append("When:    ").append(whenText).append(" (device time)\n\n")
            append("App:     ").append(appLabel).append(' ')
            append(device.appVersionName ?: "?").append(" (").append(device.appVersionCode?.toString() ?: "?").append(")\n")
            append("Package: ").append(device.packageName.ifBlank { "?" }).append('\n')
            append("Source:  ").append(device.installSource ?: "Unknown").append("\n\n")
            append("Device:  ").append(device.deviceManufacturer).append(' ').append(device.deviceModel)
            append(", Android SDK ").append(device.osSdkInt).append('\n')
            if (device.freeMemMb != null && device.totalMemMb != null) {
                append("Memory:  ").append(device.freeMemMb).append(" MB free of ")
                append(device.totalMemMb).append(" MB at crash\n")
            }
            append('\n')
            append(trace)
        }

        /**
         * Sample content for previewing the recovery screen without a real crash (see
         * [CrashRecovery.previewIntent]). "PREVIEW" appears in the headline, the plain-language
         * summary and the full report text, so a screenshot — or an accidentally-shared preview
         * report — can never be mistaken for a real crash.
         *
         * Every field is a literal: this reads nothing from disk and queries nothing about the
         * device, so previewing can never leak real device metadata into a shared report.
         */
        fun samplePreview(appLabel: String): Decoded {
            val headline = "IllegalStateException: this is a PREVIEW — no real crash occurred"
            val trace = buildString {
                append("java.lang.IllegalStateException: this is a PREVIEW — no real crash occurred\n")
                append("\tat dev.aarso.crashrecovery.CrashRecovery.previewIntent(CrashRecovery.kt)\n")
                append("\tat ").append(appLabel).append(" (preview trigger — not an actual stack trace)\n")
            }
            return Decoded(
                appLabel = appLabel,
                headline = headline,
                plainLanguage = "PREVIEW — nothing actually went wrong.",
                excType = "java.lang.IllegalStateException",
                excMessage = "this is a PREVIEW — no real crash occurred",
                threadName = "main",
                whenMillis = null,
                versionName = null,
                versionCode = null,
                packageName = null,
                installSource = null,
                osSdkInt = null,
                deviceManufacturer = null,
                deviceModel = null,
                freeMemMb = null,
                totalMemMb = null,
                trace = trace,
                fullReport = buildString {
                    append("=== Crash report — ").append(appLabel).append(" — PREVIEW ===\n")
                    append("This is a PREVIEW of the recovery screen. No crash occurred, and no\n")
                    append("real device information is included.\n\n")
                    append("What happened: PREVIEW — nothing actually went wrong.\n\n")
                    append("Error:   java.lang.IllegalStateException\n")
                    append("Message: this is a PREVIEW — no real crash occurred\n")
                    append("Thread:  main\n")
                    append("When:    (preview — no timestamp)\n\n")
                    append("App:     ").append(appLabel).append(" (preview)\n")
                    append("Package: (preview)\n")
                    append("Source:  (preview)\n\n")
                    append("Device:  (preview)\n\n")
                    append(trace)
                },
            )
        }
    }

    /** What the recovery UI needs — a plain-language summary, structured fields, and the shareable text. */
    data class Decoded(
        val appLabel: String,
        val headline: String,
        val plainLanguage: String,
        val excType: String,
        val excMessage: String?,
        val threadName: String,
        val whenMillis: Long?,
        val versionName: String?,
        val versionCode: Long?,
        val packageName: String?,
        val installSource: String?,
        val osSdkInt: Int?,
        val deviceManufacturer: String?,
        val deviceModel: String?,
        val freeMemMb: Long?,
        val totalMemMb: Long?,
        val trace: String,
        val fullReport: String,
    ) {
        /** `Yesterday, 21:42`-style is UI's job; here we expose a stable device-time string. */
        fun whenText(): String {
            val ms = whenMillis ?: return "unknown"
            val format = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())
            return format.format(Date(ms))
        }

        /** Short `d MMM, HH:mm` for the compact meta line on the main pane. */
        fun whenShort(): String {
            val ms = whenMillis ?: return ""
            val format = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            return format.format(Date(ms))
        }

        fun versionLabel(): String? =
            versionName?.let { v -> "$v" + (versionCode?.let { " ($it)" } ?: "") }
    }
}
