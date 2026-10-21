package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions that keep the staged bottom room honest after the fact: re-settling when the peek
 * stop moves, ignoring a stale flick, and telling a wobbly tap from a drag. All pure, for the usual
 * reason -- none of them can be seen in a screenshot and all of them are felt.
 */
class PeekRobustnessTest {

    private fun assertStop(expected: Float, actual: Float?) {
        assertTrue("expected $expected, was $actual", actual != null)
        assertEquals(expected.toDouble(), actual!!.toDouble(), 1e-6)
    }

    // ── peekResettleTarget ────────────────────────────────────────────────────────────────

    @Test
    fun `a room parked on the old peek follows the stop to its new place`() {
        assertStop(-0.30f, peekResettleTarget(-0.20f, 0.20f, 0.30f))
        assertStop(-0.12f, peekResettleTarget(-0.20f, 0.20f, 0.12f))
    }

    @Test
    fun `a room a hair off the old stop still counts as parked on it`() {
        val off = SpatialMotion.STOP_EPSILON / 2f
        assertStop(-0.30f, peekResettleTarget(-(0.20f + off), 0.20f, 0.30f))
        assertNull(peekResettleTarget(-(0.20f + SpatialMotion.STOP_EPSILON * 2f), 0.20f, 0.30f))
    }

    @Test
    fun `a stop that did not really move leaves the room alone`() {
        assertNull(peekResettleTarget(-0.20f, 0.20f, 0.20f))
        assertNull(peekResettleTarget(-0.20f, 0.20f, 0.20005f))
    }

    @Test
    fun `closed full and a room left elsewhere are not nudged`() {
        assertNull(peekResettleTarget(0f, 0.20f, 0.30f))
        assertNull(peekResettleTarget(-1f, 0.20f, 0.30f))
        assertNull(peekResettleTarget(-0.60f, 0.20f, 0.30f))
        // The positive side is the TOP room.
        assertNull(peekResettleTarget(0.20f, 0.20f, 0.30f))
    }

    @Test
    fun `a peek that goes away closes a room parked on it`() {
        assertStop(0f, peekResettleTarget(-0.20f, 0.20f, 0f))
        assertStop(0f, peekResettleTarget(-0.20f, 0.20f, Float.NaN))
    }

    @Test
    fun `a peek that no longer fits closes a room parked on it`() {
        // peekReveal answers 0 when the strip cannot fit, e.g. after rotating to landscape.
        val noFit = peekReveal(126f + 252f, 945f, 189f)
        assertEquals(0.0, noFit.toDouble(), 0.0)
        assertStop(0f, peekResettleTarget(-0.20f, 0.20f, noFit))
    }

    @Test
    fun `with no old peek there is nothing to follow`() {
        assertNull(peekResettleTarget(-0.20f, 0f, 0.30f))
        assertNull(peekResettleTarget(-0.20f, Float.NaN, 0.30f))
    }

    @Test
    fun `rotating to a taller window moves the stop and the room follows`() {
        val band = 189f
        val strip = 126f + 252f
        val portrait = peekReveal(strip, 2400f, band)
        val tall = peekReveal(strip, 3200f, band)
        assertTrue(tall < portrait)
        assertStop(-tall, peekResettleTarget(-portrait, portrait, tall))
    }

    // ── releaseDelta ──────────────────────────────────────────────────────────────────────

    private val flick = SpatialMotion.FLICK_VELOCITY_PX + 3f

    @Test
    fun `a fresh delta is the release velocity`() {
        assertEquals(-flick.toDouble(), releaseDelta(-flick, 1000L, 1000L).toDouble(), 0.0)
        assertEquals(-flick.toDouble(), releaseDelta(-flick, 1000L, 1000L + SpatialMotion.STALE_MOVE_MS).toDouble(), 0.0)
    }

    @Test
    fun `a delta older than the stale window is no velocity`() {
        assertEquals(0.0, releaseDelta(-flick, 1000L, 1000L + SpatialMotion.STALE_MOVE_MS + 1L).toDouble(), 0.0)
        assertEquals(0.0, releaseDelta(flick, 1000L, 5000L).toDouble(), 0.0)
    }

    @Test
    fun `an unknown time trusts the delta as before`() {
        assertEquals(-flick.toDouble(), releaseDelta(-flick, -1L, 5000L).toDouble(), 0.0)
        assertEquals(-flick.toDouble(), releaseDelta(-flick, 1000L, -1L).toDouble(), 0.0)
        assertEquals(-flick.toDouble(), releaseDelta(-flick, -1L, -1L).toDouble(), 0.0)
    }

    @Test
    fun `pull up then hold still then lift settles on the strip not full`() {
        val peek = 0.2f
        // Parked on the strip after a quick pull: the last delta was a flick up...
        val stale = -flick
        assertStop(-1f, settleTargetStaged(-peek, stale, peek))
        // ...but the finger sat there for half a second before lifting.
        val delta = releaseDelta(stale, lastMoveUptime = 1000L, releaseUptime = 1500L)
        assertStop(-peek, settleTargetStaged(-peek, delta, peek))
    }

    @Test
    fun `a real flick up from the strip still opens full`() {
        val peek = 0.2f
        val delta = releaseDelta(-flick, lastMoveUptime = 1000L, releaseUptime = 1016L)
        assertStop(-1f, settleTargetStaged(-peek, delta, peek))
    }

    // ── beyondTouchSlop ───────────────────────────────────────────────────────────────────

    @Test
    fun `jitter inside touch slop is still a tap`() {
        assertFalse(beyondTouchSlop(0f, 0f, 8f))
        assertFalse(beyondTouchSlop(1f, -2f, 8f))
        assertFalse(beyondTouchSlop(5f, 5f, 8f))
        assertFalse(beyondTouchSlop(8f, 0f, 8f))
    }

    @Test
    fun `travel past touch slop in a straight line is a drag`() {
        assertTrue(beyondTouchSlop(8.1f, 0f, 8f))
        assertTrue(beyondTouchSlop(0f, -9f, 8f))
        assertTrue(beyondTouchSlop(6f, 6f, 8f))
    }
}
