package dev.aarso.interactionmode

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [ModeDefaults.defaultFor] across all four combinations of its two booleans — see the
 * KDoc on that function for why the `hasExplicitChoice = true` combinations matter too, not
 * just the "real" fresh-install cases.
 */
class ModeDefaultsTest {

    @Test
    fun `fresh install, no legacy signal, defaults to REGULAR`() {
        assertEquals(
            InteractionMode.REGULAR,
            ModeDefaults.defaultFor(hasExplicitChoice = false, legacySignal = false),
        )
    }

    @Test
    fun `fresh-ish install with a legacy signal defaults to ASOC for continuity`() {
        assertEquals(
            InteractionMode.ASOC,
            ModeDefaults.defaultFor(hasExplicitChoice = false, legacySignal = true),
        )
    }

    @Test
    fun `an explicit choice with no legacy signal resolves to REGULAR`() {
        assertEquals(
            InteractionMode.REGULAR,
            ModeDefaults.defaultFor(hasExplicitChoice = true, legacySignal = false),
        )
    }

    @Test
    fun `an explicit choice is never overridden by a legacy signal`() {
        assertEquals(
            InteractionMode.REGULAR,
            ModeDefaults.defaultFor(hasExplicitChoice = true, legacySignal = true),
        )
    }
}
