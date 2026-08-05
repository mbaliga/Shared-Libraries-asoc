package dev.aarso.crashrecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The regression guard for the empty button.
 *
 * On device, "View the full report" rendered as an outlined pill with no text in it. The cause
 * was that the outlined variant ignored the colour it was handed, hardcoded its fill to paper,
 * and painted its label in the caller's second argument — which at that call site was also
 * paper. Nothing about the code looked wrong; the two colours only collided at one call site,
 * and only for one variant.
 *
 * So the property, not the call site, is what is pinned here: for any inputs at all, a pill's
 * label is never the same colour as its fill. A button whose label cannot be read is not a
 * styling nit on this screen — it is the recovery surface itself appearing broken to someone who
 * has just watched the app crash.
 */
class PillColorsTest {

    private companion object {
        const val INK = 0xFF191611.toInt()
        const val PAPER = 0xFFF7F4EE.toInt()
        const val ACCENT = 0xFF5E48E8.toInt()
        const val DANGER = 0xFFC0453A.toInt()
    }

    @Test
    fun `the outlined variant paints its label in its tint, not in onTint`() {
        // The exact failing call: pillButton("View the full report", ink, paper, filled = false).
        assertEquals(INK, PillColors.label(tint = INK, onTint = PAPER, filled = false))
    }

    @Test
    fun `the outlined variant's label is never its fill`() {
        val paints = listOf(INK, PAPER, ACCENT, DANGER)
        for (tint in paints) {
            for (onTint in paints) {
                for (paper in paints) {
                    // A tint equal to the surface is a caller error and no colour rule can save
                    // it; every other combination must produce a readable label.
                    if (tint == paper) continue
                    assertNotEquals(
                        "tint=$tint onTint=$onTint paper=$paper",
                        PillColors.fill(tint, paper, filled = false),
                        PillColors.label(tint, onTint, filled = false),
                    )
                }
            }
        }
    }

    @Test
    fun `the filled variant's label is never its fill`() {
        // The same property from the other side. Here onTint is what the caller promised is
        // readable on the tint, so the only way to break it is to paint the label the tint.
        assertEquals(ACCENT, PillColors.fill(ACCENT, PAPER, filled = true))
        assertEquals(PAPER, PillColors.label(ACCENT, PAPER, filled = true))
        assertNotEquals(
            PillColors.fill(ACCENT, PAPER, filled = true),
            PillColors.label(ACCENT, PAPER, filled = true),
        )
    }

    @Test
    fun `onTint has no effect at all on the outlined variant`() {
        // The old signature's real sin: it implied onTint mattered here. It does not, and a
        // caller who passes anything for it should get the same button.
        val a = PillColors.label(tint = DANGER, onTint = PAPER, filled = false)
        val b = PillColors.label(tint = DANGER, onTint = ACCENT, filled = false)
        assertEquals(a, b)
    }

    @Test
    fun `the outlined variant sits on the surface and carries its tint in the stroke`() {
        assertEquals(PAPER, PillColors.fill(tint = DANGER, paper = PAPER, filled = false))
        assertEquals(DANGER, PillColors.stroke(tint = DANGER, filled = false))
    }

    @Test
    fun `the filled variant has no stroke`() {
        // Drawing one would double the control's weight against the outlined variant beside it.
        assertNull(PillColors.stroke(tint = ACCENT, filled = true))
    }
}
