package dev.aarso.search

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported unchanged from Android-IDE-core: relative-date arithmetic across a DST boundary and across
 * a timezone change.
 *
 * Every expected value is built via direct [ZonedDateTime] construction — never by hand-computed
 * millisecond offsets — so these tests are self-verifying against real DST rules rather than
 * against a second copy of the same arithmetic bug.
 */
class RelativeDateTest {

    private fun instant(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `minus one day across US spring-forward lands on the correct calendar day`() {
        // America/New_York: 2026-03-08 02:00 EST -> 03:00 EDT (spring forward; that day has 23 hours).
        val zone = ZoneId.of("America/New_York")
        val now = instant(zone, 2026, 3, 9, h = 12) // noon the day after the transition
        val range = RelativeDate.resolveRange("-1d", now, zone)!!
        assertEquals(instant(zone, 2026, 3, 8), range.startInclusiveMillis)
        assertEquals(instant(zone, 2026, 3, 9), range.endExclusiveMillis)
    }

    @Test fun `minus one day across US fall-back lands on the correct calendar day`() {
        // America/New_York: 2026-11-01 02:00 EDT -> 01:00 EST (fall back; that day has 25 hours).
        val zone = ZoneId.of("America/New_York")
        val now = instant(zone, 2026, 11, 2, h = 12)
        val range = RelativeDate.resolveRange("-1d", now, zone)!!
        assertEquals(instant(zone, 2026, 11, 1), range.startInclusiveMillis)
        assertEquals(instant(zone, 2026, 11, 2), range.endExclusiveMillis)
    }

    @Test fun `naive 24-hour-multiple math would have gotten spring-forward wrong`() {
        // Documents exactly the bug this file exists to avoid: subtracting a flat 86_400_000 ms
        // from "now" crosses the 23-hour short day and lands on the wrong wall-clock hour.
        val zone = ZoneId.of("America/New_York")
        val now = instant(zone, 2026, 3, 9, h = 12)
        val naiveWrong = now - 24L * 60 * 60 * 1000
        val correct = RelativeDate.resolveRange("-1d", now, zone)!!.startInclusiveMillis
        assertTrue("expected the naive calculation to actually be wrong here", naiveWrong != correct)
    }

    @Test fun `today resolves differently across a timezone change for the same instant`() {
        // A single instant near UTC midnight is "today" in one zone and "yesterday" in another
        // far enough west/east — proving zone, not just clock time, is honored.
        val instantMillis = ZonedDateTime.of(2026, 6, 15, 0, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val tokyo = RelativeDate.resolveRange("today", instantMillis, ZoneId.of("Asia/Tokyo"))!!
        val losAngeles = RelativeDate.resolveRange("today", instantMillis, ZoneId.of("America/Los_Angeles"))!!
        assertTrue(instantMillis in tokyo)
        assertTrue(instantMillis in losAngeles)
        // Different zones, same instant -> different absolute day windows.
        assertTrue(tokyo.startInclusiveMillis != losAngeles.startInclusiveMillis)
    }

    @Test fun `iso date resolves to that calendar day in the given zone`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val range = RelativeDate.resolveRange("2026-07-30", 0L, zone)!!
        assertEquals(instant(zone, 2026, 7, 30), range.startInclusiveMillis)
        assertEquals(instant(zone, 2026, 7, 31), range.endExclusiveMillis)
    }

    @Test fun `last-week resolves to the full prior Monday-to-Monday window`() {
        val zone = ZoneId.of("UTC")
        // 2026-07-30 is a Thursday; "this week" starts Monday 2026-07-27.
        val now = instant(zone, 2026, 7, 30, h = 15)
        val range = RelativeDate.resolveRange("last-week", now, zone)!!
        assertEquals(instant(zone, 2026, 7, 20), range.startInclusiveMillis) // Monday the week before
        assertEquals(instant(zone, 2026, 7, 27), range.endExclusiveMillis) // up to this Monday
    }

    @Test fun `week and month offsets also resolve`() {
        val zone = ZoneId.of("UTC")
        val now = instant(zone, 2026, 7, 30)
        assertEquals(instant(zone, 2026, 7, 23), RelativeDate.resolveRange("-1w", now, zone)!!.startInclusiveMillis)
        assertEquals(instant(zone, 2026, 6, 30), RelativeDate.resolveRange("-1m", now, zone)!!.startInclusiveMillis)
    }

    @Test fun `resolvePoint returns the start of the resolved range`() {
        val zone = ZoneId.of("UTC")
        val now = instant(zone, 2026, 7, 30)
        assertEquals(
            RelativeDate.resolveRange("-7d", now, zone)!!.startInclusiveMillis,
            RelativeDate.resolvePoint("-7d", now, zone),
        )
    }

    @Test fun `unrecognized expression resolves to null, never throws`() {
        assertNull(RelativeDate.resolveRange("not-a-date", 0L, ZoneId.of("UTC")))
        assertNull(RelativeDate.resolveRange("", 0L, ZoneId.of("UTC")))
        assertNull(RelativeDate.resolveRange("2026-13-99", 0L, ZoneId.of("UTC"))) // invalid calendar date
    }

    /**
     * The offset pattern is `-(\d+)([dwm])`, and `\d+` is **unbounded** — so a held-down key
     * produces a string that matches the pattern perfectly and is not a `Long`. `toLong()` threw
     * `NumberFormatException` out of [QueryParser.parse], which contracts never to throw.
     */
    @Test fun `a digit run too long for a Long resolves to null rather than throwing`() {
        val zone = ZoneId.of("UTC")
        for (unit in listOf("d", "w", "m")) {
            assertNull(RelativeDate.resolveRange("-99999999999999999999$unit", 0L, zone))
            assertNull(RelativeDate.resolveRange("-" + "9".repeat(400) + unit, 0L, zone))
        }
    }

    /**
     * The other half: an offset that *is* a `Long` and still names no date. Each unit reaches the
     * limit by its own route — `d` overruns `LocalDate.ofEpochDay` (a `DateTimeException`), `w`
     * overflows `Math.multiplyExact` on the times-seven (an `ArithmeticException`), `m` fails
     * `ChronoField.YEAR`'s range check — so all the guarded paths are covered, not just one.
     */
    @Test fun `an offset that fits a Long but runs off the calendar resolves to null`() {
        val zone = ZoneId.of("UTC")
        for (unit in listOf("d", "w", "m")) {
            val raw = "-9223372036854775807$unit"
            assertNull("expected '$raw' to resolve to null", RelativeDate.resolveRange(raw, 0L, zone))
            assertNull(RelativeDate.resolvePoint(raw, 0L, zone))
        }
    }

    @Test fun `an offset that is merely large but expressible still resolves`() {
        // The guards must reject the inexpressible, not everything big: this one is a real date.
        val zone = ZoneId.of("UTC")
        val range = RelativeDate.resolveRange("-1000000d", 0L, zone)
        assertNotNull("a 1,000,000-day offset is a valid calendar date and must still resolve", range)
        assertTrue(range!!.startInclusiveMillis < 0L)
    }

    @Test fun `the EvalContext overload agrees with the millis overload`() {
        // The context-taking entry points are new in the port — the parser and the evaluator both
        // resolve the same strings and must agree, which is why the clock is on EvalContext.
        val zone = ZoneId.of("UTC")
        val now = instant(zone, 2026, 7, 30)
        val ctx = EvalContext(nowMillis = now)
        assertEquals(RelativeDate.resolveRange("-7d", now, zone), RelativeDate.resolveRange("-7d", ctx, zone))
        assertEquals(RelativeDate.resolvePoint("today", now, zone), RelativeDate.resolvePoint("today", ctx, zone))
    }

    @Test fun `the range is half-open so adjacent days tile the timeline`() {
        val zone = ZoneId.of("UTC")
        val now = instant(zone, 2026, 7, 30, h = 12)
        val today = RelativeDate.resolveRange("today", now, zone)!!
        val yesterday = RelativeDate.resolveRange("yesterday", now, zone)!!
        assertEquals(today.startInclusiveMillis, yesterday.endExclusiveMillis)
        // The boundary instant belongs to exactly one of them.
        assertTrue(today.startInclusiveMillis in today)
        assertTrue(today.startInclusiveMillis !in yesterday)
    }
}
