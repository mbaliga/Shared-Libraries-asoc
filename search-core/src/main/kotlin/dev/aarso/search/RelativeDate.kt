package dev.aarso.search

import java.time.DateTimeException
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
 *
 * ## And one it inherits
 *
 * **Totality.** [QueryParser.parse] calls into here to decide whether a date expression is
 * well-formed, so every path that cannot produce a date must return `null` — never throw. That
 * covers both shapes of bad input this file can be handed: a string that is not a date at all
 * (`someday`, `2024-02-31`) and one that matches a pattern but names an instant the calendar
 * cannot express (`-99999999999999999999d`, `-9223372036854775807d`). `null` becomes
 * [Diagnostic.InvalidDate] beside the search box; an exception becomes a crash on a keystroke.
 *
 * ## Calendar-unit tokens (`this-month`, bare years, month names)
 *
 * `this-week`/`this-month`/`this-year` and their `last-` counterparts are whole calendar-unit
 * windows anchored on `today`, computed with [LocalDate.minusMonths]/`plusMonths` rather than a
 * fixed day count for the same DST/calendar-length reason `-7d` is — a month is not a fixed
 * number of days, and `last-month` from March 31st must land on the whole of February, not on
 * "31 days ago".
 *
 * A bare month name (`december`) carries no year, so it resolves to **the most recent occurrence
 * that is not in the future**: if that month has already started this year (including the
 * current month itself), it means this year's; otherwise last year's. `january` typed on any day
 * in January means the current month, not a year-old one. An explicit trailing year
 * (`december-2024`) skips that inference entirely.
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
    private val BARE_YEAR = Regex("^\\d{4}$")

    /** Declaration order is the 1-based month index; see [monthIndexOrNull]. */
    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )
    private val MONTH_PATTERN = Regex(
        "^(" + MONTH_NAMES.joinToString("|") + ")(?:-(\\d{4}))?$",
        RegexOption.IGNORE_CASE,
    )

    /** 1-based month index for an English month name, case-insensitive; `null` if [name] is not
     *  one of the twelve. Exposed so a natural-language pre-pass can recognize a month word
     *  without keeping its own copy of this list to drift out of step with [MONTH_PATTERN]. */
    internal fun monthIndexOrNull(name: String): Int? {
        val idx = MONTH_NAMES.indexOf(name.lowercase())
        return if (idx >= 0) idx + 1 else null
    }

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
            raw.equals("this-week", ignoreCase = true) -> {
                val startOfThisWeek = today.with(DayOfWeek.MONDAY)
                Range(
                    startOfThisWeek.atStartOfDay(zone).toInstant().toEpochMilli(),
                    startOfThisWeek.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
            raw.equals("this-month", ignoreCase = true) -> monthRange(today.year, today.monthValue, zone)
            raw.equals("last-month", ignoreCase = true) -> {
                // `minusMonths`, not `monthValue - 1`: January's last month is December of the
                // *previous* year, and plain subtraction lands on month zero.
                val lastMonth = today.minusMonths(1)
                monthRange(lastMonth.year, lastMonth.monthValue, zone)
            }
            raw.equals("this-year", ignoreCase = true) -> yearRange(today.year, zone)
            raw.equals("last-year", ignoreCase = true) -> yearRange(today.year - 1, zone)
            BARE_YEAR.matches(raw) -> yearRange(raw.toInt(), zone)
            ISO_DATE.matches(raw) -> parseIsoDate(raw)?.let { dayRange(it, zone) }
            else -> MONTH_PATTERN.matchEntire(raw)?.let { m ->
                val month = MONTH_NAMES.indexOf(m.groupValues[1].lowercase()) + 1
                val explicitYear = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                val year = explicitYear ?: run {
                    // No year named: the most recent occurrence not in the future — see the class
                    // KDoc. A month that has already started this year (or is the current one)
                    // counts as "not in the future"; one that has not arrived yet belongs to last
                    // year.
                    if (month <= today.monthValue) today.year else today.year - 1
                }
                monthRange(year, month, zone)
            } ?: RELATIVE_OFFSET.matchEntire(raw)?.let { m ->
                // `\d+` is an *unbounded* digit run: `-99999999999999999999d` matches the pattern
                // perfectly and is not a Long. `toLong()` here threw NumberFormatException straight
                // out of QueryParser.parse, on a string a user can type by holding down a key.
                val amount = m.groupValues[1].toLongOrNull() ?: return null
                try {
                    val point = when (m.groupValues[2]) {
                        "d" -> today.minusDays(amount)
                        "w" -> today.minusWeeks(amount)
                        "m" -> today.minusMonths(amount)
                        else -> return null
                    }
                    dayRange(point, zone)
                } catch (_: DateTimeException) {
                    // `-9223372036854775807d` parses as a Long and then runs off the end of the
                    // proleptic calendar. Same class of input, same answer: not a date.
                    null
                } catch (_: ArithmeticException) {
                    // The overflow checks inside LocalDate.plusDays / plusWeeks (Math.addExact,
                    // Math.multiplyExact) and inside Instant.toEpochMilli raise this rather than
                    // DateTimeException. Both are "that offset is not expressible", not a bug.
                    null
                }
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

    /** [month] is 1-based. No overflow guard: every caller derives [year] either from a 4-digit
     *  match ([BARE_YEAR], [MONTH_PATTERN]'s year group) or from [LocalDate.getYear]/`minusMonths`
     *  on an already-valid `today`, none of which can land outside `LocalDate`'s range. */
    private fun monthRange(year: Int, month: Int, zone: ZoneId): Range {
        val start = LocalDate.of(year, month, 1)
        val end = start.plusMonths(1)
        return Range(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            end.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    /** Same overflow argument as [monthRange]. */
    private fun yearRange(year: Int, zone: ZoneId): Range {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year + 1, 1, 1)
        return Range(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            end.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}
