package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrubber's two pure decisions: where the finger is pointing, and what to call that place.
 *
 * Both are worth pinning because both fail quietly. A mapping that is off by one at the ends
 * means the strip can never quite reach the newest photo; a stop lookup that picks the nearest
 * label instead of the containing one means the bubble says "April" while the grid shows March.
 * Neither produces a crash, a log line, or a bug report — just an app that feels wrong.
 */
class ScrubberMappingTest {

    // ── indexForFraction ──────────────────────────────────────────────────────────────────

    @Test
    fun `the ends of the strip are the ends of the list`() {
        assertEquals(0, indexForFraction(0f, 100))
        assertEquals(99, indexForFraction(1f, 100))
    }

    @Test
    fun `a finger past either end clamps instead of wrapping`() {
        // A drag that keeps going after the strip has run out should pin to the end. Wrapping, or
        // an exception, would both be worse than the obvious thing.
        assertEquals(0, indexForFraction(-0.4f, 50))
        assertEquals(0, indexForFraction(-1000f, 50))
        assertEquals(49, indexForFraction(1.4f, 50))
        assertEquals(49, indexForFraction(1000f, 50))
    }

    @Test
    fun `an empty list has nothing to point at`() {
        // itemCount 0 must not divide, throw, or hand back -1 for the host to scroll to.
        assertEquals(0, indexForFraction(0f, 0))
        assertEquals(0, indexForFraction(0.5f, 0))
        assertEquals(0, indexForFraction(1f, 0))
        assertEquals(0, indexForFraction(-2f, 0))
        // A negative count is nonsense, but it is nonsense the host can produce mid-refresh.
        assertEquals(0, indexForFraction(0.5f, -3))
    }

    @Test
    fun `a single item swallows the whole strip`() {
        for (step in 0..20) {
            assertEquals(
                "fraction ${step / 20f} on a one-item list",
                0,
                indexForFraction(step / 20f, 1),
            )
        }
    }

    @Test
    fun `each item owns an equal band of travel`() {
        // Four items, four quarters. The boundaries belong to the band they open, not the one
        // they close, so 0.25 is the second item rather than the first.
        assertEquals(0, indexForFraction(0.0f, 4))
        assertEquals(0, indexForFraction(0.24f, 4))
        assertEquals(1, indexForFraction(0.25f, 4))
        assertEquals(2, indexForFraction(0.5f, 4))
        assertEquals(3, indexForFraction(0.75f, 4))
        assertEquals(3, indexForFraction(0.99f, 4))
    }

    @Test
    fun `the mapping never leaves the list`() {
        // The property the callers actually rely on: whatever comes in, the result is a legal
        // index. onScrubTo feeds this straight into a list scroll.
        val counts = listOf(1, 2, 7, 99, 1000)
        for (count in counts) {
            for (step in -5..25) {
                val index = indexForFraction(step / 20f, count)
                assert(index in 0 until count) {
                    "fraction ${step / 20f} of $count items gave $index"
                }
            }
        }
    }

    // ── stopForIndex ──────────────────────────────────────────────────────────────────────

    private val months = listOf(
        ScrubberStop("Mar", 0),
        ScrubberStop("Apr", 12),
        ScrubberStop("May", 40),
    )

    @Test
    fun `no stops means no label`() {
        assertNull(stopForIndex(emptyList(), 0))
        assertNull(stopForIndex(emptyList(), 900))
    }

    @Test
    fun `an index before the first stop has no label`() {
        // Fylz's date sort can leave undated files ahead of the first dated stop. The bubble is
        // expected to hide rather than to borrow the first stop's name.
        assertNull(stopForIndex(months, -1))
        assertNull(stopForIndex(listOf(ScrubberStop("Apr", 12)), 11))
    }

    @Test
    fun `a stop owns every index from itself up to the next one`() {
        assertEquals("Mar", stopForIndex(months, 0)?.label)
        assertEquals("Mar", stopForIndex(months, 11)?.label)
        assertEquals("Apr", stopForIndex(months, 12)?.label)
        assertEquals("Apr", stopForIndex(months, 39)?.label)
        assertEquals("May", stopForIndex(months, 40)?.label)
    }

    @Test
    fun `the containing stop wins over the nearer one`() {
        // The point of the whole function. Index 39 is one item from April's end and 27 from its
        // start, so "nearest stop" would say May — but the user is still looking at April.
        assertEquals("Apr", stopForIndex(months, 39)?.label)
        // And with the gap the other way round, to prove it is not just leaning left by accident.
        val lopsided = listOf(ScrubberStop("A", 0), ScrubberStop("B", 100))
        assertEquals("A", stopForIndex(lopsided, 99)?.label)
        assertEquals("B", stopForIndex(lopsided, 100)?.label)
    }

    @Test
    fun `the last stop runs to the end of the list`() {
        // There is no stop after the final one, so it must keep answering for everything below it
        // rather than falling off and leaving the bubble blank at the bottom of the strip.
        assertEquals("May", stopForIndex(months, 41)?.label)
        assertEquals("May", stopForIndex(months, 10_000)?.label)
    }

    @Test
    fun `a single stop covers everything from its own index onward`() {
        val only = listOf(ScrubberStop("G", 5))
        assertNull(stopForIndex(only, 4))
        assertEquals("G", stopForIndex(only, 5)?.label)
        assertEquals("G", stopForIndex(only, 5000)?.label)
    }

    @Test
    fun `stops sharing a start index resolve to the later one`() {
        // Size sort can produce empty bands — two labels beginning at the same item. Picking the
        // last keeps the answer consistent with the strip, which draws the later label on top.
        val bands = listOf(
            ScrubberStop("1 MB", 0),
            ScrubberStop("10 MB", 4),
            ScrubberStop("100 MB", 4),
        )
        assertEquals("100 MB", stopForIndex(bands, 4)?.label)
        assertEquals("1 MB", stopForIndex(bands, 3)?.label)
    }
}
