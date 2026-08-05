package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pull-to-backup — and the copy its tests used to pin ("PULL TO CREATE BACKUP") — was retired by
 * owner direction (2026-08-05): the pull-down space belongs to [SpatialShell]'s top-room reveal,
 * so no other gesture may claim it and no instructional copy may sit in a gesture space. Its
 * replacement is the shake gesture; the decision logic is pinned here with the same rigor the
 * retired copy had, so the gesture cannot silently drift.
 *
 * These assertions moved here from Foto Xplorr alongside [ShakePeakTrain] itself. They are the
 * only test of the gesture there is: everything above [ShakePeakTrain] is sensor plumbing that
 * needs a device to exercise, so if the train's arithmetic is not pinned, nothing is.
 */
class ShakeToRefreshTest {

    private fun train() = ShakePeakTrain(
        thresholdG = 2.4f,
        required = 3,
        windowMs = 900L,
        separationMs = 90L,
        cooldownMs = 2_000L,
    )

    @Test
    fun `one bump is not a shake`() {
        val t = train()
        assertEquals(false, t.onSample(3.5f, 1_000))
    }

    @Test
    fun `three separated peaks inside the window fire exactly once`() {
        val t = train()
        assertEquals(false, t.onSample(3.0f, 1_000))
        assertEquals(false, t.onSample(3.0f, 1_200))
        assertTrue(t.onSample(3.0f, 1_400))
    }

    @Test
    fun `sub-threshold samples never count`() {
        val t = train()
        assertEquals(false, t.onSample(1.0f, 1_000))
        assertEquals(false, t.onSample(2.3f, 1_200))
        assertEquals(false, t.onSample(1.9f, 1_400))
        // Two real peaks after the noise still are not enough on their own.
        assertEquals(false, t.onSample(3.0f, 1_600))
        assertEquals(false, t.onSample(3.0f, 1_800))
    }

    @Test
    fun `a rapid burst collapses into one peak`() {
        val t = train()
        // Samples 10ms apart are one swing of the hand, not three: no fire.
        assertEquals(false, t.onSample(3.0f, 1_000))
        assertEquals(false, t.onSample(3.2f, 1_010))
        assertEquals(false, t.onSample(3.1f, 1_020))
    }

    @Test
    fun `peaks spread wider than the window never accumulate`() {
        val t = train()
        assertEquals(false, t.onSample(3.0f, 1_000))
        assertEquals(false, t.onSample(3.0f, 2_000))
        // 1_000 has aged out of the 900ms window by now; only 2_000 and 3_000 remain.
        assertEquals(false, t.onSample(3.0f, 3_000))
    }

    @Test
    fun `the cooldown swallows an over-enthusiastic shake`() {
        val t = train()
        t.onSample(3.0f, 1_000)
        t.onSample(3.0f, 1_200)
        assertTrue(t.onSample(3.0f, 1_400))
        // Still shaking: inside the 2s refractory period nothing fires...
        assertEquals(false, t.onSample(3.5f, 1_600))
        assertEquals(false, t.onSample(3.5f, 1_800))
        // ...and afterwards a fresh full train is required.
        assertEquals(false, t.onSample(3.0f, 3_500))
        assertEquals(false, t.onSample(3.0f, 3_700))
        assertTrue(t.onSample(3.0f, 3_900))
    }
}
