package dev.aarso.cellshell

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swivel park (owner, 2026-08-14: *"add the swivel while keeping the slight shrink"*) turns
 * the parked card about its hinge edge. Turning a surface foreshortens it, and what it
 * foreshortens is the grab band the shell guarantees stays on screen — so the compensation is
 * not cosmetic, it is what keeps the band touchable. These pin that arithmetic, which is the
 * only part of the swivel that can be checked without a device.
 */
class SwivelParkTest {

    @Test
    fun `no rotation foreshortens nothing`() {
        assertEquals(1f, swivelForeshorten(0f), 1e-6f)
    }

    @Test
    fun `foreshortening falls off as the card turns away`() {
        val gentle = swivelForeshorten(10f)
        val steep = swivelForeshorten(45f)
        assertTrue("a turned card presents less of itself", gentle < 1f)
        assertTrue("turning further presents less still", steep < gentle)
    }

    @Test
    fun `the shells swivel angle leaves the band comfortably touchable`() {
        // This deliberately does NOT pin a tolerance on the angle itself -- the angle is a look,
        // and it was raised 10 -> 22 degrees on owner direction. What must hold at any angle the
        // shell ships is the invariant underneath it: even WITHOUT the compensation
        // SpatialShell applies, the foreshortened band stays above the 48dp minimum touch target
        // documented in SpatialShell's BAND_DP. Pinning the look would have failed this test for
        // a change that was intended; pinning the invariant fails it only for one that is unsafe.
        val kept = swivelForeshorten(SpatialMotion.PARK_SWIVEL_DEG)
        val bandDp = 72f
        assertTrue(
            "a ${SpatialMotion.PARK_SWIVEL_DEG} degree swivel leaves ${bandDp * kept}dp, under the 48dp minimum",
            bandDp * kept > 48f,
        )
        // And the angle must stay well clear of the camera plane, past which pointer mapping
        // through the perspective divide stops being meaningful (see PARK_CAMERA_DISTANCE_CARDS).
        assertTrue("swivel must stay under 32 degrees", SpatialMotion.PARK_SWIVEL_DEG < 32f)
    }

    @Test
    fun `compensating the park travel restores the promised band exactly`() {
        // The shell divides the band by the foreshortening before handing it to parkDistance.
        // Applying the rotation to that widened band must land back on the original.
        val band = 72f
        val degrees = SpatialMotion.PARK_SWIVEL_DEG
        val widened = band / swivelForeshorten(degrees)
        val seenAfterRotation = widened * swivelForeshorten(degrees)
        assertEquals(band, seenAfterRotation, 1e-3f)
        assertTrue("compensation must widen, never narrow", widened > band)
    }

    @Test
    fun `the compensation is a no-op when the card is not swivelled`() {
        // SLIDE hosts must get byte-identical travel to what they had before ParkStyle existed.
        val band = 72f
        assertEquals(
            parkDistance(2400f, scale = 0.9f, bandPx = band),
            parkDistance(2400f, scale = 0.9f, bandPx = band / swivelForeshorten(0f)),
            1e-4f,
        )
    }

    @Test
    fun `foreshortening is clamped so an edge-on card can never invert the travel`() {
        // Guards a division by ~0 in the shell's band compensation.
        assertTrue(swivelForeshorten(90f) > 0f)
        assertTrue(swivelForeshorten(180f) > 0f)
        assertTrue(abs(swivelForeshorten(90f)) >= 0.01f)
    }

    @Test
    fun `both park styles exist and slide is the default-compatible one`() {
        assertEquals(2, ParkStyle.entries.size)
        assertTrue(ParkStyle.entries.contains(ParkStyle.SLIDE))
        assertTrue(ParkStyle.entries.contains(ParkStyle.SWIVEL))
    }
}
