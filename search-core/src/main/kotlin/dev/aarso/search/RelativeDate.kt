package dev.aarso.search

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Resolves date expressions in a query — ISO dates (`2024-01-31`), the named days
 * (`today`, `yesterday`, `last-week`), and relative offsets (`-7d`, `-2w`, `-3m`).
 *
 * ## Two properties this file exists to guarantee
 *
 * **1. Determinism.** Every entry point takes the current time as a parameter — as an
 * [EvalContext] or as raw epoch millis — and *nothing here ever reads a clock*. That is why
 * [EvalContext] carries `nowMillis` at all. A search engine whose date facets are tested against
 * `System.currentTimeMillis()` has tests that pass until they are run at 23:59:58, and a UI whose
 * `during:today` window silently rolls over between the parse and the evaluation of one keystroke.
 *
 * **2. DST safety.** `-7d` is computed as "the calendar date 7 days before today, in this zone"
 * via [LocalDate.minusDays] and then converted to an instant via [LocalDate.atStartOfDay] — both
 * wall-clock/calendar operations that stay correct across a DST transition. Subtracting
 * `7 * 86_400_000L` milliseconds instead lands on the wrong wall-clock hour whenever a DST change
 * falls inside the window, which shifts the day boundary and silently includes or excludes a
 * day's worth of results.
 *
 * The [ZoneId] is a parameter for the same reason the clock is: "today" is a question about a
 * place, and a host that indexes documents stamped in UTC while its user reads them in Auckland
 * has to be able to say which one it means.
 */
object RelativeDate {

    /** An inclusive-start, exclusive-end epoch-millis window. Half-open on purpose: two adjacent
     *  day ranges then tile the timeline with no instant belonging to both or to neither. */
    data class Range(val startInclusiveMillis: Long, val endExclusiveMillis: Long) {
        operator fun contains(epochMillis: Long): Boolean =
            epochMillis in startInclusiveMillis until endExclusiveMillis
    }

    private val RELATIVE_OFFSET = Regex("^-(\\d+)([dwm])$")
    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** The whole calendar day/week [raw] denotes, or `null` if it is not a date expression at
     *  all — which is how the parser decides to emit [Diagnostic.InvalidDate]. */
    fun resolveRange(raw: String, ctx: EvalContext, zone: ZoneId): Range? =
        resolveRange(raw, ctx.nowMillis, zone)

    /** A single threshold instant, for a `before:`/`after:`-shaped comparison — the *start* of
     *  the referenced day, so `after:2024-01-31` includes all of the 31st. */
    fun resolvePoint(raw: String, ctx: EvalContext, zone: ZoneId): Long? =
        resolvePoint(raw, ctx.nowMillis, zone)

    /** Millis-taking overload, for callers that hold a clock reading rather than a context. */
    fun resolveRange(raw: String, nowMillis: Long, zone: ZoneId): Range? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when {
            raw.equals("today", ignoreCase = true) -> dayRange(today, zone)
            raw.equals("yesterday", ignoreCase = true) -> dayRange(today.minusDays(1), zone)
            raw.equals("last-week", ignoreCase = true) -> {
                // The *previous* whole week, not "the last seven days" — a user asking for
                // last week on a Monday morning means the week that just ended, not a window
                // that is one day long.
                val startOfThisWeek = today.with(DayOfWeek.MONDAY)
                val startOfLastWeek = startOfThisWeek.minusWeeks(1)
                Range(
                    startOfLastWeek.atStartOfDay(zone).toInstant().toEpochMilli(),
                    startOfThisWeek.atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
            ISO_DATE.matches(raw) -> parseIsoDate(raw)?.let { dayRange(it, zone) }
            else -> RELATIVE_OFFSET.matchEntire(raw)?.let { m ->
                val amount = m.groupValues[1].toLong()
                val point = when (m.groupValues[2]) {
                    "d" -> today.minusDays(amount)
                    "w" -> today.minusWeeks(amount)
                    "m" -> today.minusMonths(amount)
                    else -> return null
                }
                dayRange(point, zone)
            }
        }
    }

    /** Millis-taking overload of [resolvePoint]. */
    fun resolvePoint(raw: String, nowMillis: Long, zone: ZoneId): Long? =
        resolveRange(raw, nowMillis, zone)?.startInclusiveMillis

    /** [LocalDate.parse] throws on `2024-02-31`; the whole module's contract is that malformed
     *  input produces a diagnostic, so the throw is converted to a `null` here at the boundary. */
    private fun parseIsoDate(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun dayRange(date: LocalDate, zone: ZoneId): Range {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return Range(start, end)
    }
}
