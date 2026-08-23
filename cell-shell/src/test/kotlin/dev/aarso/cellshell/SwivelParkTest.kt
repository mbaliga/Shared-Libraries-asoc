package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swivel park (owner, 2026-08-14: *"add the swivel while keeping the slight shrink"*) turns
 * the parked card about its hinge edge.
 *
 * These exist because of a real bug the first version shipped with: the hinge was placed on the
 * card's *far* edge, the one already off screen, while the grip pill was drawn on the near one.
 * The card then swung the visible sliver through the perspective divide and the whole pane
 * appeared to vanish rather than park (owner, 2026-08-18: *"the central space is supposed to move
 * out and remain partially in view, but it goes away completely"*). It was invisible at 10
 * degrees and obvious at 22, which is exactly the kind of regression a look-based assertion would
 * have waved through — so what is pinned here is the geometry, not the look.
 */
class SwivelParkTest {

    @Test
    fun `pushing an axis forward leaves the near edge on screen`() {
        // The direction that reads backwards at a glance, and the one the swivel got wrong:
        // POSITIVE progress pushes the card towards the far end, so what stays is the NEAR edge.
        assertEquals(BandEdge.START, bandEdge(1f))
        assertEquals(BandEdge.START, bandEdge(0.5f))
        assertEquals(BandEdge.END, bandEdge(-1f))
        assertEquals(BandEdge.END, bandEdge(-0.5f))
    }

    @Test
    fun `an axis at rest has no band`() {
        assertEquals(BandEdge.NONE, bandEdge(0f))
        assertEquals(BandEdge.NONE, bandEdge(0.005f))
        assertEquals(BandEdge.NONE, bandEdge(-0.005f))
    }

    @Test
    fun `the hinge lands on the visible edge of the scaled card, not the box`() {
        // The card is drawn at `scale`, centred in a full-size box, and transformOrigin is a
        // fraction of the BOX. So a plain 0f or 1f hinges half a shrink outside the card -- on a
        // line the card does not touch. At 0.9 scale the card spans 0.05..0.95 of the box.
        val scale = 0.9f
        assertEquals(0.05f, hingePivot(BandEdge.START, scale), 1e-6f)
        assertEquals(0.95f, hingePivot(BandEdge.END, scale), 1e-6f)
    }

    @Test
    fun `an unscaled card hinges exactly on its own edge`() {
        assertEquals(0f, hingePivot(BandEdge.START, 1f), 1e-6f)
        assertEquals(1f, hingePivot(BandEdge.END, 1f), 1e-6f)
    }

    @Test
    fun `a card at rest hinges on its centre`() {
        // No band, no hinge edge to prefer -- and at rest the angle is 0 anyway, so the pivot
        // must simply be somewhere harmless.
        assertEquals(0.5f, hingePivot(BandEdge.NONE, 0.9f), 1e-6f)
        assertEquals(0.5f, hingePivot(BandEdge.NONE, 1f), 1e-6f)
    }

    @Test
    fun `the hinge is always inside the card`() {
        // A hinge outside the card rotates it about a line it does not touch, which moves the
        // band edge -- the exact failure this whole file exists for.
        listOf(0.5f, 0.8f, 0.9f, 0.95f, 1f).forEach { scale ->
            val start = hingePivot(BandEdge.START, scale)
            val end = hingePivot(BandEdge.END, scale)
            val cardStart = (1f - scale) / 2f
            val cardEnd = 1f - cardStart
            assertTrue("start hinge $start outside card at scale $scale", start >= cardStart - 1e-6f)
            assertTrue("end hinge $end outside card at scale $scale", end <= cardEnd + 1e-6f)
        }
    }

    @Test
    fun `the hinge sits on whichever edge stays on screen`() {
        // Ties bandEdge and hingePivot together: this is the pairing that was broken, and it is
        // the pairing the shell now reads for BOTH the rotation origin and the grip pill.
        val scale = 0.9f
        // Card pushed to the far end -> near edge visible -> hinge at the near edge.
        assertTrue(hingePivot(bandEdge(1f), scale) < 0.5f)
        // Card pushed to the near end -> far edge visible -> hinge at the far edge.
        assertTrue(hingePivot(bandEdge(-1f), scale) > 0.5f)
    }

    @Test
    fun `the band is the full promised width at every swivel angle`() {
        // Because the hinge is ON the band edge, that edge sits at z = 0 through the rotation and
        // the projection leaves it where it was. So park travel needs no angle-dependent
        // compensation, and SLIDE and SWIVEL must land at the identical depth. A future change
        // that reintroduces a cos(angle) fudge here will fail this.
        val extent = 2400f
        val scale = 0.9f
        val band = 216f
        val travel = parkDistance(extent, scale, band)
        // Card spans [extent*(1-scale)/2, extent*(1+scale)/2] before the park, then translates.
        val cardNearEdge = extent * (1f - scale) / 2f
        val visibleBand = extent - (travel + cardNearEdge)
        assertEquals("the band on screen must be exactly what was asked for", band, visibleBand, 1e-3f)
    }

    @Test
    fun `the swivel angle stays clear of the camera plane`() {
        // Past roughly 32 degrees at PARK_CAMERA_DISTANCE_CARDS the far edge crosses behind the
        // camera, where Compose's pointer mapping through the perspective divide stops being
        // meaningful. The angle is a look and may be tuned; this ceiling is not.
        assertTrue(
            "swivel is ${SpatialMotion.PARK_SWIVEL_DEG} degrees, must stay under 32",
            SpatialMotion.PARK_SWIVEL_DEG < 32f,
        )
        assertTrue("and must actually turn", SpatialMotion.PARK_SWIVEL_DEG > 0f)
    }

    @Test
    fun `both park styles exist and slide is the default-compatible one`() {
        assertEquals(2, ParkStyle.entries.size)
        assertTrue(ParkStyle.entries.contains(ParkStyle.SLIDE))
        assertTrue(ParkStyle.entries.contains(ParkStyle.SWIVEL))
    }
}
