package dev.aarso.diagnostics.core

/**
 * Parser/formatter for the `ClackMetric` logcat-line convention Clackpad already uses in debug
 * builds (gated on `BuildConfig.CLACKPAD_DEBUG_METRICS`) to emit span timings — `touch2char`,
 * `cold_start`, `float_build`, `recovery_capture`, and whatever else Clackpad adds later.
 * Clackpad is the intended first consumer of this module, and the owner's stated constraint is
 * explicit: ingest Clackpad's EXISTING spans without Clackpad changing anything about how it logs.
 *
 * Lives in diagnostics-core, not diagnostics-android, for the same pure-JVM-first reason as
 * everything else here — parsing a line of text has nothing to do with `android.*`, so it is
 * unit-tested off-device like the rest of the maths. See [dev.aarso.diagnostics.interop.ClackMetricAdapter]
 * in diagnostics-android for the half of this that touches a live [Session][dev.aarso.diagnostics.Session].
 *
 * ASSUMED WIRE FORMAT — this repo does not have Clackpad attached, so this is inferred from the
 * stated convention (tag `ClackMetric`, `scripts/parse_metrics.sh` already parses it, span names
 * are `snake_case` identifiers), not read from Clackpad's own source. See
 * `docs/DIAGNOSTICS_MODULE_SPEC.md` §9 for that caveat spelled out. The assumed shape is the
 * ordinary Android metric-logging convention — a `name=value` pair as the message, `ms`-suffix
 * optional:
 *
 *     Log.d("ClackMetric", "touch2char=37.4ms")
 *     Log.d("ClackMetric", "cold_start=612ms")
 *
 * which appears in `adb logcat` output as (brief format):
 *
 *     D/ClackMetric( 8123): touch2char=37.4ms
 *
 * or (threadtime format, `adb logcat -v threadtime`):
 *
 *     08-15 09:41:03.118  8123  8123 D ClackMetric: touch2char=37.4ms
 *
 * The parser is deliberately permissive about the framing around that core `name=value` token —
 * brief, threadtime, or an already-tag-stripped bare message — because it has to survive whichever
 * `adb logcat -v <format>` a host captured with, not dictate one. If Clackpad's actual line shape
 * turns out to differ (extra framing, a different separator), [parseMessage] is the single seam to
 * adjust; [parseLogcatLine] and the Android-side adapter do not need to change.
 */
object ClackMetricLine {

    /** The logcat tag Clackpad's debug builds use for these lines. */
    const val TAG = "ClackMetric"

    /** One parsed span: a name and a duration in milliseconds, plus any further attributes on the line. */
    data class Span(val name: String, val durationMs: Double, val attrs: Map<String, String> = emptyMap())

    // The span itself: name=value, VALUE MUST BE NUMERIC, optionally "ms"-suffixed. Name is a bare
    // identifier (letters, digits, underscore, dot) so it matches touch2char / cold_start /
    // float_build / recovery_capture and anything shaped like them without being so loose it eats
    // framing punctuation.
    private val SPAN_TOKEN = Regex("""^([A-Za-z][A-Za-z0-9_.]*)=(-?\d+(?:\.\d+)?)(?:ms)?$""")

    // A trailing attribute: name=value, value is any non-whitespace run and need not be numeric
    // ("build=debug" is a legitimate attribute; "api=34" is too). Deliberately more permissive
    // than [SPAN_TOKEN] -- it exists to be collected and then DROPPED by the adapter, never to be
    // judged as a duration.
    private val ATTR_TOKEN = Regex("""^([A-Za-z][A-Za-z0-9_.]*)=(\S+)$""")

    /**
     * Parse an already-extracted message body (the part after "TAG: "). The FIRST whitespace-
     * separated token must be the span itself (`name=<numeric>[ms]`); any further tokens shaped
     * like `key=value` are collected as attributes. Null if the first token is not a numeric
     * `name=value`, which is the correct outcome for a `ClackMetric` line this format does not
     * anticipate — silently dropping an unparseable metric line is preferable to inventing a span
     * for it, or to misreading an attribute as the span because of token order.
     */
    fun parseMessage(message: String): Span? {
        val tokens = message.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val first = SPAN_TOKEN.find(tokens.first()) ?: return null
        val durationMs = first.groupValues[2].toDoubleOrNull() ?: return null
        val attrs = tokens.drop(1)
            .mapNotNull { ATTR_TOKEN.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
        return Span(first.groupValues[1], durationMs, attrs)
    }

    // Brief:      "D/ClackMetric( 8123): touch2char=37.4ms"
    private val BRIEF = Regex("""^[VDIWEF]/([^(]+)\(\s*\d+\):\s?(.*)$""")

    // Threadtime: "08-15 09:41:03.118  8123  8123 D ClackMetric: touch2char=37.4ms"
    private val THREADTIME = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+):\s?(.*)$""")

    // Bare:       "ClackMetric: touch2char=37.4ms" -- no level/pid framing at all.
    private val BARE = Regex("""^(\S+):\s?(.*)$""")

    /**
     * Parse one raw `adb logcat` line in brief, threadtime, or bare `TAG: message` form. Returns
     * null both for a malformed line AND for a well-formed line carrying a different tag — a
     * foreign tag is not a parse failure, it is correctly not a `ClackMetric` span.
     */
    fun parseLogcatLine(line: String): Span? {
        BRIEF.find(line)?.let { m -> return if (m.groupValues[1].trim() == TAG) parseMessage(m.groupValues[2]) else null }
        THREADTIME.find(line)?.let { m -> return if (m.groupValues[1] == TAG) parseMessage(m.groupValues[2]) else null }
        BARE.find(line)?.let { m -> return if (m.groupValues[1] == TAG) parseMessage(m.groupValues[2]) else null }
        return null
    }

    /**
     * Canonical formatter for the message half only (no logcat framing) — round-trips with
     * [parseMessage]. Useful for tests and for any future constellation app adopting the same
     * convention on purpose rather than by inference.
     */
    fun format(span: Span): String {
        val head = "${span.name}=${formatMs(span.durationMs)}ms"
        if (span.attrs.isEmpty()) return head
        return head + " " + span.attrs.entries.joinToString(" ") { (k, v) -> "$k=$v" }
    }

    private fun formatMs(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}
