package dev.aarso.diagnostics.interop

import dev.aarso.diagnostics.Diagnostics
import dev.aarso.diagnostics.core.ClackMetricLine

/**
 * Bridges Clackpad's existing `ClackMetric` logcat convention into this module's span table,
 * without Clackpad changing anything about how it logs. Clackpad already emits these lines in
 * debug builds behind `BuildConfig.CLACKPAD_DEBUG_METRICS`; this adapter is fed each line — from
 * wherever the host obtains it: shelling out to `logcat -s ClackMetric` (an app may read its own
 * process's log entries without any permission), a `LogcatReceiver`, or a test harness replaying a
 * captured log — and turns matching lines into spans on the active session: the same span table
 * `Diagnostics.span { }` / `beginSpan` already populate, and the one [dev.aarso.diagnostics.core.MarkdownReporter]
 * already redacts and renders as "Custom spans".
 *
 * This is deliberately NOT a [dev.aarso.diagnostics.MetricSource] that owns a callback: there is no
 * portable, permission-free "a logcat line just arrived" callback to subscribe to, so the host
 * decides how lines reach [ingest]. See `docs/DIAGNOSTICS_MODULE_SPEC.md` §9 for the intended
 * wiring in Clackpad specifically, and for the caveat that the exact line format here is inferred
 * from the stated convention, not read from Clackpad's own source.
 *
 * PRIVACY: only the span NAME and DURATION cross into the report. Any further `key=value`
 * attributes on the source line are available on [ClackMetricLine.Span.attrs] but are deliberately
 * NOT forwarded — this is the ingestion point for a privacy-first keyboard's own debug log lines,
 * and a future attribute Clackpad logs alongside a span must not gain a path into a file this
 * module exists to make shareable, just because the parser happened to notice it. If a genuine
 * need for an attribute shows up, it must be added here explicitly and by name, redacted the same
 * way a bucket or a fact value is — never forwarded wholesale.
 */
object ClackMetricAdapter {

    /**
     * Feed one raw logcat line (brief, threadtime, or bare `TAG: message` form — see
     * [ClackMetricLine.parseLogcatLine]). Returns true if it was a `ClackMetric` span line and was
     * recorded on the currently active session; false for a foreign tag, a malformed line, or no
     * active session — all three are silent no-ops, not errors, since a metrics bridge must never
     * be the thing that crashes a debug build.
     */
    fun ingest(logcatLine: String): Boolean {
        val span = ClackMetricLine.parseLogcatLine(logcatLine) ?: return false
        Diagnostics.recordSpan(span.name, span.durationMs)
        return true
    }

    /** Feed an already-tag-stripped message body, e.g. from a `LogcatReceiver`'s own message field. */
    fun ingestMessage(message: String): Boolean {
        val span = ClackMetricLine.parseMessage(message) ?: return false
        Diagnostics.recordSpan(span.name, span.durationMs)
        return true
    }
}
