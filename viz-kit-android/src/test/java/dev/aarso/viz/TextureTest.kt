package dev.aarso.viz

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextureTest {
    private val ink = 0xFF0C0C0C.toInt()
    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    private fun coverage(b: Bitmap): Double = pixels(b).sumOf { Color.alpha(it) / 255.0 } / (b.width * b.height)

    @Test fun solidAndFadeAreNotTiled() {
        val p = TexturePainter(ink, 1f, 3f)
        assertNull(p.tile(Texture.SOLID)); assertNull(p.tile(Texture.FADE))
        for (t in Viz.ORDER - listOf(Texture.SOLID, Texture.FADE)) assertNotNull(t.key, p.tile(t))
    }

    @Test fun everyTileIsIdenticalOnEveryRun() {
        for (t in Viz.ORDER) {
            val a = TexturePainter(ink, 1f, 3f).tile(t) ?: continue
            val b = TexturePainter(ink, 1f, 3f).tile(t)!!
            assertArrayEquals(t.key, pixels(a), pixels(b))
        }
    }

    @Test fun theStippleIsSeededNotRandom() {
        val a = TexturePainter(ink, 1f, 3f, seed = 7).tile(Texture.STIPPLE)!!
        val b = TexturePainter(ink, 1f, 3f, seed = 7).tile(Texture.STIPPLE)!!
        val c = TexturePainter(ink, 1f, 3f, seed = 8).tile(Texture.STIPPLE)!!
        assertArrayEquals(pixels(a), pixels(b))
        assertFalse(pixels(a).contentEquals(pixels(c)))
        assertEquals(TexturePainter.STIPPLE_SEED, 7L)
    }

    @Test fun tilesCoverAboutWhatVizJsDraws() {
        // Ink area as a fraction of the tile, from viz.js's own numbers.
        val p = TexturePainter(ink, 1f, 4f)
        assertEquals("lines 0.3", 0.3, coverage(p.tile(Texture.LINES)!!), 0.03)
        assertEquals("checks 0.5", 0.5, coverage(p.tile(Texture.CHECKS)!!), 0.03)
        assertEquals("dots 2*pi*1.5^2/81", 2 * Math.PI * 2.25 / 81, coverage(p.tile(Texture.DOTS)!!), 0.01)
        assertEquals("hatch 0.46", 0.46, coverage(p.tile(Texture.HATCH)!!), 0.04)
        assertEquals("cross ~2*0.18", 0.33, coverage(p.tile(Texture.CROSS)!!), 0.05)
        assertEquals("stipple pi*0.85^2/4.5^2", Math.PI * 0.7225 / 20.25, coverage(p.tile(Texture.STIPPLE)!!), 0.015)
    }

    @Test fun scaleScalesThePitch() {
        val one = TexturePainter(ink, 1f, 2f).tileSize(Texture.DOTS)
        val two = TexturePainter(ink, 2f, 2f).tileSize(Texture.DOTS)
        assertEquals(9f, one, 0f); assertEquals(18f, two, 0f)
        assertEquals(128, Color.alpha(TexturePainter.withAlpha(ink, 0.5f)))
    }

    @Test fun theTilesAreTheInk() {
        val night = 0xFFF2EFE9.toInt()
        val px = pixels(TexturePainter(night, 1f, 3f).tile(Texture.CHECKS)!!).filter { Color.alpha(it) == 255 }
        assertTrue(px.isNotEmpty()); assertTrue(px.all { it == night })
    }
}
