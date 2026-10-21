package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the scrubber's labels, ticks and bubble land for each [ScrubberEdge].
 *
 * The RIGHT cases are golden values: they are the numbers the strip computed before the `edge`
 * parameter existed (Fylz and Foto Xplorr's grid depend on them), so any drift here is a change to
 * a shipped app, not a refactor. The other edges are pinned to be their mirror images.
 */
class ScrubberEdgeTest {

    // A 44dp strip at 2x density, on a 1000px list: thickness 88px, inset 30px, gap 24px.
    private val length = 1000f
    private val thickness = 88f
    private val inset = 30f
    private val gap = 24f

    // ── shape ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `left and right run vertically and top and bottom run horizontally`() {
        assertTrue(ScrubberEdge.LEFT.runsVertically)
        assertTrue(ScrubberEdge.RIGHT.runsVertically)
        assertFalse(ScrubberEdge.TOP.runsVertically)
        assertFalse(ScrubberEdge.BOTTOM.runsVertically)
    }

    @Test
    fun `only the side edges ask the system for gesture exclusion`() {
        assertTrue(ScrubberEdge.LEFT.excludesSystemGestures)
        assertTrue(ScrubberEdge.RIGHT.excludesSystemGestures)
        assertFalse(ScrubberEdge.TOP.excludesSystemGestures)
        assertFalse(ScrubberEdge.BOTTOM.excludesSystemGestures)
    }

    @Test
    fun `a vertical strip keeps the old 14dp pitch and a horizontal one uses 40dp`() {
        assertEquals(14f, labelPitchDp(ScrubberEdge.RIGHT), 0f)
        assertEquals(14f, labelPitchDp(ScrubberEdge.LEFT), 0f)
        assertEquals(40f, labelPitchDp(ScrubberEdge.TOP), 0f)
        assertEquals(40f, labelPitchDp(ScrubberEdge.BOTTOM), 0f)
    }

    // ── track and ticks ────────────────────────────────────────────────────────────────────

    @Test
    fun `RIGHT track is where it always was and ticks point left`() {
        // Before: trackX = size.width - TRACK_INSET, tick start = trackX - tickLength.
        assertEquals(88f - 14f, trackAcrossPx(ScrubberEdge.RIGHT, thickness, 14f), 0f)
        assertEquals(-1f, ScrubberEdge.RIGHT.inwardSign, 0f)
        assertEquals(64f, 74f + ScrubberEdge.RIGHT.inwardSign * 10f, 0f)
    }

    @Test
    fun `the track hugs the outer edge and ticks point to the inside`() {
        assertEquals(14f, trackAcrossPx(ScrubberEdge.LEFT, thickness, 14f), 0f)
        assertEquals(14f, trackAcrossPx(ScrubberEdge.TOP, thickness, 14f), 0f)
        assertEquals(74f, trackAcrossPx(ScrubberEdge.BOTTOM, thickness, 14f), 0f)
        assertEquals(1f, ScrubberEdge.LEFT.inwardSign, 0f)
        assertEquals(1f, ScrubberEdge.TOP.inwardSign, 0f)
        assertEquals(-1f, ScrubberEdge.BOTTOM.inwardSign, 0f)
    }

    // ── labels ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `RIGHT label placement is the old one`() {
        // Before: x = labelRight - width with labelRight = widthPx - inset, and
        // y = round(centerY - h/2) clamped to 0..(height - h).
        val p = labelPlacement(ScrubberEdge.RIGHT, 40, 20, 500f, length, thickness, inset)
        assertEquals(StripPlacement(x = 58 - 40, y = 490), p)
        // The first stop sits half a row past the top; the last half a row past the bottom.
        assertEquals(0, labelPlacement(ScrubberEdge.RIGHT, 40, 20, 3f, length, thickness, inset).y)
        assertEquals(980, labelPlacement(ScrubberEdge.RIGHT, 40, 20, 998f, length, thickness, inset).y)
        assertEquals(58, labelMaxWidthPx(ScrubberEdge.RIGHT, thickness, inset, 80f))
    }

    @Test
    fun `LEFT labels start at the inset and are centred along y`() {
        val p = labelPlacement(ScrubberEdge.LEFT, 40, 20, 500f, length, thickness, inset)
        assertEquals(StripPlacement(x = 30, y = 490), p)
        assertEquals(58, labelMaxWidthPx(ScrubberEdge.LEFT, thickness, inset, 80f))
    }

    @Test
    fun `TOP labels hang from the inset and are centred along x`() {
        val p = labelPlacement(ScrubberEdge.TOP, 40, 20, 500f, length, thickness, inset)
        assertEquals(StripPlacement(x = 480, y = 30), p)
    }

    @Test
    fun `BOTTOM labels sit above the inset and are centred along x`() {
        val p = labelPlacement(ScrubberEdge.BOTTOM, 40, 20, 500f, length, thickness, inset)
        assertEquals(StripPlacement(x = 480, y = 58 - 20), p)
    }

    @Test
    fun `horizontal labels clamp inside the strip at both ends`() {
        for (edge in listOf(ScrubberEdge.TOP, ScrubberEdge.BOTTOM)) {
            assertEquals(0, labelPlacement(edge, 40, 20, 5f, length, thickness, inset).x)
            assertEquals(960, labelPlacement(edge, 40, 20, 995f, length, thickness, inset).x)
        }
    }

    @Test
    fun `a horizontal label may measure one pitch wide`() {
        assertEquals(80, labelMaxWidthPx(ScrubberEdge.TOP, thickness, inset, 80f))
        assertEquals(80, labelMaxWidthPx(ScrubberEdge.BOTTOM, thickness, inset, 80f))
    }

    @Test
    fun `a label wider than the strip pins to zero instead of going negative`() {
        assertEquals(0, labelPlacement(ScrubberEdge.TOP, 400, 20, 100f, 300f, thickness, inset).x)
        assertEquals(0, labelPlacement(ScrubberEdge.RIGHT, 40, 400, 100f, 300f, thickness, inset).y)
    }

    // ── bubble ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `RIGHT bubble is the old one`() {
        // Before: x = -width - gap, y = round(touchY - h/2) clamped to 0..(height - h).
        val p = bubblePlacement(ScrubberEdge.RIGHT, 100, 50, 500f, length, thickness, gap)
        assertEquals(StripPlacement(x = -124, y = 475), p)
        assertEquals(0, bubblePlacement(ScrubberEdge.RIGHT, 100, 50, 0f, length, thickness, gap).y)
        assertEquals(950, bubblePlacement(ScrubberEdge.RIGHT, 100, 50, 1000f, length, thickness, gap).y)
    }

    @Test
    fun `the bubble opens away from the track`() {
        // LEFT -> right of the strip, TOP -> below it, BOTTOM -> above it.
        assertEquals(
            StripPlacement(x = 88 + 24, y = 475),
            bubblePlacement(ScrubberEdge.LEFT, 100, 50, 500f, length, thickness, gap),
        )
        assertEquals(
            StripPlacement(x = 450, y = 88 + 24),
            bubblePlacement(ScrubberEdge.TOP, 100, 50, 500f, length, thickness, gap),
        )
        assertEquals(
            StripPlacement(x = 450, y = -50 - 24),
            bubblePlacement(ScrubberEdge.BOTTOM, 100, 50, 500f, length, thickness, gap),
        )
    }

    @Test
    fun `a horizontal bubble clamps to the strip's ends`() {
        for (edge in listOf(ScrubberEdge.TOP, ScrubberEdge.BOTTOM)) {
            assertEquals(0, bubblePlacement(edge, 100, 50, 10f, length, thickness, gap).x)
            assertEquals(900, bubblePlacement(edge, 100, 50, 990f, length, thickness, gap).x)
        }
    }

    // ── shared mapping ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the index mapping and tick positions do not depend on the edge`() {
        // These helpers take a length and nothing else, so one set of numbers serves every edge.
        assertEquals(0, indexForFraction(0f, 100))
        assertEquals(99, indexForFraction(1f, 100))
        assertEquals(5f, itemCenterY(0, 100, 1000f), 0f)
        assertEquals(995f, itemCenterY(99, 100, 1000f), 0f)
    }

    @Test
    fun `a 40dp pitch thins a dense run of months and keeps the first label`() {
        // Twelve months over a 360dp-wide strip (30dp each) at 1x: every second one survives.
        val stops = (0 until 12).map { ScrubberStop("m$it", it * 10) }
        val vertical = thinnedLabels(stops, 120, 360f, 14f)
        val horizontal = thinnedLabels(stops, 120, 360f, 40f)
        assertEquals(stops.size, vertical.size)
        assertEquals(stops.first(), horizontal.first())
        assertTrue(horizontal.size < vertical.size)
        // And no two kept labels are closer than the pitch.
        val xs = horizontal.map { itemCenterY(it.itemIndex, 120, 360f) }
        for (i in 1 until xs.size) assertTrue(xs[i] - xs[i - 1] >= 40f)
    }
}
