package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feel of the navigation, pinned.
 *
 * [settleTarget] and [parkDistance] are the two pure pieces of the shell's motion, and they are
 * pure precisely so this file can exist: "it settles wrong" and "I can't get back" are faults
 * nobody files a bug report about — they just find the app annoying and stop trusting it. The
 * numbers here are the constellation's contract, not this module's preferences, so a change that
 * breaks these tests is a change to how every app in the constellation feels.
 */
class SpatialMotionTest {

    /** JUnit's float overload is the deprecated exact one; settle targets are exact ends anyway. */
    private fun assertSettles(expected: Float, actual: Float) =
        assertEquals(expected.toDouble(), actual.toDouble(), 1e-6)

    // ── settleTarget: distance ────────────────────────────────────────────────────────────

    @Test
    fun `a slow drag past halfway completes`() {
        assertSettles(1f, settleTarget(0.6f, lastDelta = 0.5f))
        assertSettles(-1f, settleTarget(-0.6f, lastDelta = -0.5f))
    }

    @Test
    fun `a slow drag short of halfway retreats`() {
        assertSettles(0f, settleTarget(0.4f, lastDelta = 0.5f))
        assertSettles(0f, settleTarget(-0.4f, lastDelta = -0.5f))
    }

    @Test
    fun `exactly halfway retreats rather than completing`() {
        // The threshold is strict: a room opens when the finger has committed PAST the halfway
        // line, so the ambiguous case falls back to where the user already was.
        assertSettles(0f, settleTarget(SpatialMotion.SETTLE_THRESHOLD, lastDelta = 0f))
        assertSettles(0f, settleTarget(-SpatialMotion.SETTLE_THRESHOLD, lastDelta = 0f))
    }

    // ── settleTarget: flick ───────────────────────────────────────────────────────────────

    @Test
    fun `a flick outward opens from barely any travel`() {
        // This is the whole point of the velocity branch: a fast short flick from the edge should
        // open the room, not snap back because the finger never crossed the halfway line.
        val flick = SpatialMotion.FLICK_VELOCITY_PX + 1f
        assertSettles(1f, settleTarget(0.05f, lastDelta = flick))
        assertSettles(-1f, settleTarget(-0.05f, lastDelta = -flick))
    }

    @Test
    fun `a flick back home dismisses an almost fully open room`() {
        // Direction beats distance in both directions, or the gesture would be one-way: a room
        // at 90% flicked back toward home must close, not spring open again.
        val flick = SpatialMotion.FLICK_VELOCITY_PX + 1f
        assertSettles(0f, settleTarget(0.9f, lastDelta = -flick))
        assertSettles(0f, settleTarget(-0.9f, lastDelta = flick))
    }

    @Test
    fun `the flick threshold itself is not a flick`() {
        // At exactly the threshold the slow-drag rule applies, so 0.4 retreats even though the
        // finger was moving outward.
        assertSettles(0f, settleTarget(0.4f, lastDelta = SpatialMotion.FLICK_VELOCITY_PX))
    }

    // ── settleTarget: rest ────────────────────────────────────────────────────────────────

    @Test
    fun `a touch that never moved stays home`() {
        // A tap on the home surface must not drift a room open on the strength of sensor noise.
        assertSettles(0f, settleTarget(0f, lastDelta = 0f))
        assertSettles(0f, settleTarget(0.0005f, lastDelta = 50f))
        assertSettles(0f, settleTarget(-0.0005f, lastDelta = -50f))
    }

    @Test
    fun `a fully open room left alone stays open`() {
        // The settle runs on release from any position, including one the user dragged to and
        // held. Landing back at 0 here would close rooms the user had just finished opening.
        assertSettles(1f, settleTarget(1f, lastDelta = 0f))
        assertSettles(-1f, settleTarget(-1f, lastDelta = 0f))
    }

    // ── parkDistance ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the parked card always leaves its grab band on screen`() {
        // The band is the only way back that does not involve a system gesture, so this is the
        // property that matters: whatever the scale, exactly the band's width of card is still
        // on screen once it has parked.
        val extent = 1080f
        val band = 56f
        for (scale in listOf(1f, 0.95f, 0.9f, 0.85f)) {
            val travelled = parkDistance(extent, scale, band)
            // The scaled card's near edge starts inset by half the shrinkage, then travels.
            val nearEdgeAfterParking = extent * (1f - scale) / 2f + travelled
            val stillVisible = extent - nearEdgeAfterParking
            assertEquals("scale=$scale", band.toDouble(), stillVisible.toDouble(), 0.01)
        }
    }

    @Test
    fun `a shrunken card is parked less far than a full-size one`() {
        // Scaling shrinks the card's half-width too, so parking it by the full extent would push
        // it entirely off screen — the reason this is a function and not just `extent - band`.
        val full = parkDistance(1080f, scale = 1f, bandPx = 56f)
        val shrunk = parkDistance(1080f, scale = 1f - SpatialMotion.PARK_SCALE_DROP, bandPx = 56f)
        assertTrue("$shrunk should be less than $full", shrunk < full)
    }

    @Test
    fun `a wider band parks the card less far, one to one`() {
        val narrow = parkDistance(1080f, scale = 0.9f, bandPx = 40f)
        val wide = parkDistance(1080f, scale = 0.9f, bandPx = 60f)
        assertEquals(20.0, (narrow - wide).toDouble(), 0.01)
    }

    // ── the constants themselves ──────────────────────────────────────────────────────────

    @Test
    fun `the settle does not overshoot`() {
        // Explicitly pinned because it is the single most-often "improved" decision in this file.
        // The reference implementation is emphatic: a room is a place you arrive at, not
        // something that bounces into position. 320ms, eased, no spring.
        assertEquals(320L, SpatialMotion.settleSpec.durationMillis.toLong())
        assertEquals(0L, SpatialMotion.settleSpec.delay.toLong())
    }

    @Test
    fun `vertical travel is shorter than horizontal`() {
        // Top and bottom rooms open over a fraction of the height: a full-height drag to reach a
        // recovery drawer is a chore, and the thumb does not comfortably span it.
        assertTrue(SpatialMotion.VERTICAL_TRAVEL_FRACTION in 0.5f..1f)
        assertTrue(SpatialMotion.VERTICAL_TRAVEL_FRACTION < 1f)
    }
}
