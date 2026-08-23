package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parked card's face, pinned.
 *
 * [parkVeil] is the answer to a reported fault: at rest in a room, the 72dp of home the shell
 * deliberately leaves on screen was still legible as a *second UI* — a column of filenames cut
 * mid-word beside a locations room, a slab of bottom chrome floating over an actions room. The
 * scrim alone never hid it, so the properties below are the ones that must not quietly regress.
 */
class ParkVeilTest {

    private fun assertVeil(expected: Float, lift: Float) =
        assertEquals("lift=$lift", expected.toDouble(), parkVeil(lift).toDouble(), 1e-5)

    @Test
    fun `home is untouched until the release would no longer bring it back`() {
        // The whole retreat half of the gesture is a peek at the room WITH home still readable:
        // let go anywhere in here and home is where you land, so hiding it would be a lie about
        // where you are. The boundary is settleTarget's own, not a second number beside it.
        assertVeil(0f, 0f)
        assertVeil(0f, 0.25f)
        assertVeil(0f, SpatialMotion.SETTLE_THRESHOLD)
    }

    @Test
    fun `the face is fully closed at full park`() {
        // The property the fault was actually about. Anything short of 1 leaves host content
        // readable in the band, which is the "two UIs at once" the owner reported.
        assertVeil(1f, 1f)
    }

    @Test
    fun `it closes evenly across the committed half of the travel`() {
        // Linear, so a drag held mid-flight sits at a believable opacity rather than snapping
        // closed the instant it crosses the line.
        val mid = (SpatialMotion.SETTLE_THRESHOLD + 1f) / 2f
        assertVeil(0.5f, mid)
    }

    @Test
    fun `it never leaves the alpha range, however the lift is driven`() {
        // lift is maxOf(abs(h), abs(v)) and both axes are clamped to [-1, 1], but an alpha
        // outside [0, 1] throws in Color.copy rather than clipping, so the guard is worth having
        // against a host that drives the controller somewhere unexpected.
        for (lift in listOf(-3f, -1f, -0.001f, 0f, 0.5f, 0.999f, 1f, 1.4f, 12f)) {
            val veil = parkVeil(lift)
            assertTrue("lift=$lift gave $veil", veil in 0f..1f)
        }
    }

    @Test
    fun `it only ever increases with the lift`() {
        // Monotone: the face must not flicker back open partway through a settle.
        var previous = -1f
        for (step in 0..100) {
            val veil = parkVeil(step / 100f)
            assertTrue("step=$step went backwards: $previous -> $veil", veil >= previous)
            previous = veil
        }
    }
}
