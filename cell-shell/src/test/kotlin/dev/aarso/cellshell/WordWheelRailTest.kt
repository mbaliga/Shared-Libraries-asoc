package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word wheel's fade is its only position indicator — there is no scrollbar and no highlight
 * box — so the falloff curve is not decoration, it is the affordance. These assertions pin the
 * three properties that make it readable, ported alongside the function itself from Foto Xplorr's
 * already-tested `hyle/DestinationRail.kt`.
 */
class WordWheelRailTest {

    /** Distances well past any real rail, to prove the floor never erodes on a long run. */
    private val farOut = 0..40

    @Test
    fun `selected row is fully opaque at any distance`() {
        // The selected flag wins outright: a rail whose selection is scrolled off the focus line
        // must still draw that row at full strength, or the user loses track of where they are.
        for (distance in farOut) {
            assertEquals(
                "selected row at distance $distance should be opaque",
                1f,
                railItemAlpha(distance, isSelected = true),
                0f,
            )
        }
    }

    @Test
    fun `alpha falls off monotonically with distance`() {
        for (distance in 1..40) {
            val nearer = railItemAlpha(distance - 1, isSelected = false)
            val further = railItemAlpha(distance, isSelected = false)
            assertTrue(
                "alpha must not rise going outward: $distance gave $further after $nearer",
                further <= nearer,
            )
        }
    }

    @Test
    fun `falloff is strict until it reaches the floor`() {
        // Monotonicity alone would be satisfied by a flat line. The rows near the focus line have
        // to be distinguishable from each other, which is what makes the fade legible as position.
        for (distance in 1..4) {
            val nearer = railItemAlpha(distance - 1, isSelected = false)
            val further = railItemAlpha(distance, isSelected = false)
            assertTrue(
                "alpha must actually drop at distance $distance: $nearer -> $further",
                further < nearer,
            )
        }
    }

    @Test
    fun `floor holds at every distance out to forty`() {
        // The extremes of the run stay dim but readable. A row that fades to nothing has left the
        // wheel, and the rail stops reading as a continuous run of words.
        for (distance in farOut) {
            val alpha = railItemAlpha(distance, isSelected = false)
            assertTrue("distance $distance fell below the floor: $alpha", alpha >= FLOOR)
            assertTrue("distance $distance is not a legal alpha: $alpha", alpha <= 1f)
        }
        // And it is genuinely a floor, not merely a lower bound the curve approaches: four rows
        // out and beyond, every row is at exactly the same readable minimum.
        for (distance in 4..40) {
            assertEquals(
                "distance $distance should sit exactly on the floor",
                FLOOR,
                railItemAlpha(distance, isSelected = false),
                TOLERANCE,
            )
        }
    }

    @Test
    fun `unselected row on the focus line is the brightest unselected value`() {
        assertEquals(0.62f, railItemAlpha(0, isSelected = false), TOLERANCE)
        assertEquals(0.52f, railItemAlpha(1, isSelected = false), TOLERANCE)
    }

    @Test
    fun `fractional reading agrees with the integer curve at every whole row`() {
        // [wheelAlpha] is what the rail actually draws; it must be the same curve, not a second
        // opinion about it. Distance 0 is the focus line's own occupant, hence the selected value.
        assertEquals(1f, wheelAlpha(0f), TOLERANCE)
        for (distance in 1..40) {
            val expected = railItemAlpha(distance, isSelected = false)
            assertEquals("whole row $distance", expected, wheelAlpha(distance.toFloat()), TOLERANCE)
            assertEquals("whole row -$distance", expected, wheelAlpha(-distance.toFloat()), TOLERANCE)
        }
    }

    @Test
    fun `fractional reading is continuous and monotonic mid-drag`() {
        // The point of the fractional reading: weight and alpha have to change while the finger is
        // still down. Sampling at a tenth of a row must never step or reverse.
        var previous = wheelAlpha(0f)
        var step = 1
        while (step <= 400) {
            val alpha = wheelAlpha(step / 10f)
            assertTrue("alpha rose at ${step / 10f}: $previous -> $alpha", alpha <= previous + TOLERANCE)
            assertTrue("alpha broke the floor at ${step / 10f}: $alpha", alpha >= FLOOR - TOLERANCE)
            previous = alpha
            step++
        }
    }

    private companion object {
        const val FLOOR = 0.22f
        const val TOLERANCE = 1e-4f
    }
}
