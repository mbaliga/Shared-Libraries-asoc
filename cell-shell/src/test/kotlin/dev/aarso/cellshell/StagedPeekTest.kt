package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom room's second stage, and the edge-zone arithmetic, pinned.
 *
 * Both are pure for the usual reason: a settle that lands one stop off, or a zone that starts 48dp
 * too low, is something people feel and never report. The zone tests use non-zero insets on
 * purpose -- with zero insets the old and new arithmetic are identical, which is exactly how the
 * bug lived unnoticed.
 */
class StagedPeekTest {

    private val peek = 0.2f
    private val flick = SpatialMotion.FLICK_VELOCITY_PX + 1f

    private fun assertStop(expected: Float, actual: Float) =
        assertEquals(expected.toDouble(), actual.toDouble(), 1e-6)

    // ── settleTargetStaged: slow release snaps to the nearest stop ───────────────────────

    @Test
    fun `a slow release near closed closes`() {
        assertStop(0f, settleTargetStaged(-0.05f, 0f, peek))
    }

    @Test
    fun `a slow release in the lower part settles on the peek`() {
        assertStop(-peek, settleTargetStaged(-0.15f, -0.5f, peek))
        assertStop(-peek, settleTargetStaged(-0.25f, 0.5f, peek))
        assertStop(-peek, settleTargetStaged(-peek, 0f, peek))
    }

    @Test
    fun `a slow release in the upper part settles on full`() {
        assertStop(-1f, settleTargetStaged(-0.7f, 0.5f, peek))
        assertStop(-1f, settleTargetStaged(-1f, 0f, peek))
    }

    @Test
    fun `boundaries are strict so standing on one retreats to the lower stop`() {
        assertStop(0f, settleTargetStaged(-peek / 2f, 0f, peek))
        assertStop(-peek, settleTargetStaged(-(1f + peek) / 2f, 0f, peek))
        assertStop(-peek, settleTargetStaged(-(peek / 2f + 0.01f), 0f, peek))
        assertStop(-1f, settleTargetStaged(-((1f + peek) / 2f + 0.01f), 0f, peek))
    }

    // ── settleTargetStaged: velocity ──────────────────────────────────────────────────────

    @Test
    fun `a flick up below the peek lands on the peek not on full`() {
        assertStop(-peek, settleTargetStaged(-0.05f, -flick, peek))
    }

    @Test
    fun `a flick up from the peek opens full`() {
        assertStop(-1f, settleTargetStaged(-peek, -flick, peek))
        assertStop(-1f, settleTargetStaged(-(peek - SpatialMotion.STOP_EPSILON / 2f), -flick, peek))
    }

    @Test
    fun `a flick up between the peek and full opens full`() {
        assertStop(-1f, settleTargetStaged(-0.5f, -flick, peek))
    }

    @Test
    fun `a flick down closes from the peek and from full`() {
        assertStop(0f, settleTargetStaged(-peek, flick, peek))
        assertStop(0f, settleTargetStaged(-0.5f, flick, peek))
        assertStop(0f, settleTargetStaged(-0.95f, flick, peek))
    }

    @Test
    fun `the flick threshold itself is not a flick`() {
        val t = SpatialMotion.FLICK_VELOCITY_PX
        // Slow rule at 0.1 is the midpoint-plus side of the closed/peek boundary: peek.
        assertStop(-peek, settleTargetStaged(-0.11f, -t, peek))
        assertStop(-peek, settleTargetStaged(-0.5f, t, peek))
    }

    @Test
    fun `a touch that never moved stays home`() {
        assertStop(0f, settleTargetStaged(0f, 0f, peek))
        assertStop(0f, settleTargetStaged(-0.0005f, -50f, peek))
    }

    // ── settleTargetStaged: no peek means today's behaviour ───────────────────────────────

    @Test
    fun `an unusable peek is exactly the two stop settle`() {
        val values = listOf(-1f, -0.9f, -0.6f, -0.5f, -0.4f, -0.05f, 0f, 0.3f, 0.6f, 1f)
        val deltas = listOf(-5f, -0.5f, 0f, 0.5f, 5f)
        for (bad in listOf(0f, -0.3f, 0.01f, 0.51f, 1f, 2f, Float.NaN)) {
            for (v in values) for (d in deltas) {
                assertStop(settleTarget(v, d), settleTargetStaged(v, d, bad))
            }
        }
    }

    @Test
    fun `the positive side is never staged`() {
        // A TOP room sits at positive v and keeps the two stop settle even when a peek is set.
        for (v in listOf(0.1f, 0.4f, 0.6f, 1f)) for (d in listOf(-5f, 0f, 5f)) {
            assertStop(settleTarget(v, d), settleTargetStaged(v, d, peek))
        }
    }

    @Test
    fun `every staged target is one of the three stops`() {
        val stops = setOf(0.0, (-peek).toDouble(), -1.0)
        var v = -1f
        while (v <= 0f) {
            for (d in listOf(-5f, -1f, 0f, 1f, 5f)) {
                val t = settleTargetStaged(v, d, peek).toDouble()
                assertTrue("v=$v d=$d -> $t", stops.any { kotlin.math.abs(it - t) < 1e-6 })
            }
            v += 0.013f
        }
    }

    // ── roomStageFor: agrees with the slow settle on every boundary ───────────────────────

    @Test
    fun `the stage is the stop a slow release would settle to`() {
        var r = 0f
        while (r <= 1f) {
            val settled = -settleTargetStaged(-r, 0f, peek)
            val stage = roomStageFor(r, peek)
            val expected = when {
                settled >= 0.999f -> RoomStage.FULL
                settled > 0f -> RoomStage.PEEK
                else -> RoomStage.CLOSED
            }
            assertEquals("r=$r", expected, stage)
            r += 0.007f
        }
    }

    @Test
    fun `without a peek the stage is closed or full at the settle threshold`() {
        assertEquals(RoomStage.CLOSED, roomStageFor(0f, 0f))
        assertEquals(RoomStage.CLOSED, roomStageFor(SpatialMotion.SETTLE_THRESHOLD, 0f))
        assertEquals(RoomStage.FULL, roomStageFor(SpatialMotion.SETTLE_THRESHOLD + 0.01f, 0f))
        assertEquals(RoomStage.CLOSED, roomStageFor(0.3f, Float.NaN))
    }

    @Test
    fun `the stage clamps a reveal outside zero to one`() {
        assertEquals(RoomStage.CLOSED, roomStageFor(-0.4f, peek))
        assertEquals(RoomStage.FULL, roomStageFor(1.7f, peek))
    }

    // ── peek geometry ─────────────────────────────────────────────────────────────────────

    // 420dpi-ish phone: 72dp band = 189px.
    private val band = 189f

    @Test
    fun `the peek reveal counts the strip from below the band`() {
        // The room's content area is r * h - band tall on screen, so a strip s needs (s + band) / h.
        for (h in listOf(1600f, 2400f, 3200f)) for (strip in listOf(150f, 300f, 450f)) {
            val r = peekReveal(strip, h, band)
            assertEquals("h=$h strip=$strip", ((strip + band) / h).toDouble(), r.toDouble(), 1e-6)
            // The usable height the room really has at that reveal is exactly the strip.
            assertEquals("h=$h strip=$strip", strip.toDouble(), (r * h - band).toDouble(), 1e-3)
        }
    }

    @Test
    fun `degenerate input means no peek`() {
        assertEquals(0.0, peekReveal(0f, 2400f, band).toDouble(), 0.0)
        assertEquals(0.0, peekReveal(-5f, 2400f, band).toDouble(), 0.0)
        assertEquals(0.0, peekReveal(300f, 1f, band).toDouble(), 0.0)
        assertFalse(isStagedPeek(peekReveal(0f, 2400f, band)))
        assertTrue(isStagedPeek(peekReveal(300f, 2400f, band)))
    }

    @Test
    fun `a strip that cannot fit is no peek rather than a clamped one`() {
        // Exactly half the window fits; one pixel more does not and used to be silently clamped.
        val h = 2400f
        val fits = SpatialMotion.MAX_PEEK_REVEAL * h - band
        assertEquals(SpatialMotion.MAX_PEEK_REVEAL.toDouble(), peekReveal(fits, h, band).toDouble(), 1e-6)
        assertEquals(0.0, peekReveal(fits + 1f, h, band).toDouble(), 0.0)
        assertEquals(0.0, peekReveal(5000f, h, band).toDouble(), 0.0)
        // A short window (landscape phone, 360dp tall = 945px) cannot take a 96dp strip + nav bar.
        assertEquals(0.0, peekReveal(126f + 252f, 945f, band).toDouble(), 0.0)
        assertTrue(peekReveal(126f + 252f, 2400f, band) > 0f)
    }

    @Test
    fun `the band is part of the public contract`() {
        assertEquals(72.0, SpatialMotion.BAND_DP.toDouble(), 0.0)
    }

    // ── edge zones, with real insets ──────────────────────────────────────────────────────

    // 420dpi-ish phone: 56dp edge = 147px, 48dp nav bar = 126px, 24dp status bar = 63px.
    private val h = 2400f
    private val edge = 147f

    @Test
    fun `with no inset the bottom zone is the window's last edge band`() {
        assertTrue(inBottomEdgeZone(h - 1f, h, 0f, edge))
        assertTrue(inBottomEdgeZone(h - edge, h, 0f, edge))
        assertFalse(inBottomEdgeZone(h - edge - 1f, h, 0f, edge))
    }

    @Test
    fun `a three button bar shifts the whole zone above the bar`() {
        val bar = 126f
        // The bar's own pixels are not an origin...
        assertFalse(inBottomEdgeZone(h - 1f, h, bar, edge))
        assertFalse(inBottomEdgeZone(h - bar + 1f, h, bar, edge))
        // ...the zone is a full edge band ending at the bar...
        assertTrue(inBottomEdgeZone(h - bar, h, bar, edge))
        assertTrue(inBottomEdgeZone(h - bar - edge, h, bar, edge))
        assertFalse(inBottomEdgeZone(h - bar - edge - 1f, h, bar, edge))
        // ...so none of it is lost, where the old zone kept only edge - bar = 21px of it.
        var reachable = 0
        for (y in 0..h.toInt()) if (inBottomEdgeZone(y.toFloat(), h, bar, edge)) reachable++
        assertEquals(edge.toInt() + 1, reachable)
    }

    @Test
    fun `a gesture bar inset moves the zone the same way`() {
        val pill = 96f
        assertFalse(inBottomEdgeZone(h - 10f, h, pill, edge))
        assertTrue(inBottomEdgeZone(h - pill - 10f, h, pill, edge))
        assertTrue(inBottomEdgeZone(h - pill - edge + 10f, h, pill, edge))
    }

    @Test
    fun `a negative inset is treated as none`() {
        assertEquals(inBottomEdgeZone(h - 5f, h, 0f, edge), inBottomEdgeZone(h - 5f, h, -40f, edge))
    }

    @Test
    fun `the top zone starts below the status bar and the host reserve`() {
        val status = 63f
        val reserve = 100f
        assertFalse(inTopEdgeZone(10f, status, edge))
        assertTrue(inTopEdgeZone(status, status, edge))
        assertTrue(inTopEdgeZone(status + edge, status, edge))
        assertFalse(inTopEdgeZone(status + edge + 1f, status, edge))
        assertFalse(inTopEdgeZone(status + 50f, status + reserve, edge))
        assertTrue(inTopEdgeZone(status + reserve + 50f, status + reserve, edge))
    }

    @Test
    fun `a bottom reserve lifts the whole zone above the host's own surface`() {
        val bar = 126f
        val reserve = 147f // a 56dp filmstrip
        // No reserve is today's zone, unchanged.
        assertEquals(inBottomEdgeZone(h - bar - 10f, h, bar, edge), inBottomEdgeZone(h - bar - 10f, h, bar, edge, 0f))
        // The reserve's own pixels are not an origin...
        assertFalse(inBottomEdgeZone(h - bar - 1f, h, bar, edge, reserve))
        assertFalse(inBottomEdgeZone(h - bar - reserve + 1f, h, bar, edge, reserve))
        // ...the zone is a full edge band ending at the reserve...
        assertTrue(inBottomEdgeZone(h - bar - reserve, h, bar, edge, reserve))
        assertTrue(inBottomEdgeZone(h - bar - reserve - edge, h, bar, edge, reserve))
        assertFalse(inBottomEdgeZone(h - bar - reserve - edge - 1f, h, bar, edge, reserve))
        var reachable = 0
        for (y in 0..h.toInt()) if (inBottomEdgeZone(y.toFloat(), h, bar, edge, reserve)) reachable++
        assertEquals(edge.toInt() + 1, reachable)
    }

    @Test
    fun `a negative reserve is treated as none`() {
        assertEquals(inBottomEdgeZone(h - 200f, h, 126f, edge), inBottomEdgeZone(h - 200f, h, 126f, edge, -50f))
    }

    @Test
    fun `the top and bottom zones never overlap on a normal screen`() {
        for (y in 0..h.toInt()) {
            val yy = y.toFloat()
            assertFalse(inTopEdgeZone(yy, 63f, edge) && inBottomEdgeZone(yy, h, 126f, edge))
        }
    }
}
