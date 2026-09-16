package dev.aarso.diagnostics.crash

import android.content.Context
import dev.aarso.diagnostics.core.CrashRecord
import java.io.File

/**
 * One-way, optional bridge to `:crash-recovery`.
 *
 * Neither module knows about the other at compile time — this reads the file if it is there and
 * shrugs if it is not. The payoff is the case that makes the whole module worth building: a report
 * carrying both the crash AND the preceding window of timing, memory and log history, which is hard
 * to reconstruct any other way.
 *
 * The format assumption is deliberately minimal (line 1 timestamp, line 2 throwable, rest frames)
 * so a change on the crash-recovery side degrades to "no crash record" rather than to a malformed
 * report.
 */
internal object CrashLink {

    private const val CRASH_CLASS = "dev.aarso.crashrecovery.CrashStore"
    private const val FALLBACK_FILE = "crash/last_crash.txt"

    fun isPresent(): Boolean = runCatching { Class.forName(CRASH_CLASS) }.isSuccess

    fun lastCrash(context: Context): CrashRecord? {
        runCatching {
            val c = Class.forName(CRASH_CLASS)
            val instance = c.getDeclaredField("INSTANCE").get(null)
            val m = c.getMethod("lastCrashText", Context::class.java)
            (m.invoke(instance, context) as? String)?.let { return parse(it) }
        }
        val f = File(context.filesDir, FALLBACK_FILE)
        if (!f.exists()) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    private fun parse(text: String): CrashRecord? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        return CrashRecord(
            occurredAtIso = lines[0].trim(),
            throwable = lines[1].trim(),
            topFrames = lines.drop(2).map { it.trim().removePrefix("at ") }.take(12),
        )
    }
}
