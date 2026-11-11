package dev.aarso.cellshell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The open park (owner, 2026-11-07, Fotoz's navigation room): the card stays large and lit beside
 * the room instead of shrinking to a band. What is pinned is the geometry the shell draws and the
 * drag that has to keep the card under the finger.
 */
class OpenParkTest {

    private val w = 1000f

    @Test
    fun `the card's near edge lands where the room ends`() {
        val park = OpenPark(keepFraction = 0.47f, scale = 0.80f)
        val tx = openParkDistance(w, park)
        // Scaled about its centre, the card's left edge sits at w(1 - s)/2; moved by tx it must
        // land at w(1 - keep), the right edge of the room.
        val nearEdge = w * (1f - park.scale) / 2f + tx
        assertEquals(w * (1f - park.keepFraction), nearEdge, 0.01f)
    }

    @Test
    fun `the default open park keeps about half the window and a large card`() {
        val park = OpenPark()
        assertEquals(0.47f, park.keepFraction, 0f)
        assertEquals(0.80f, park.scale, 0f)
        // 530 - 100 = 430 px of travel on a 1000 px window: well short of the width.
        assertEquals(430f, openParkDistance(w, park), 0.01f)
    }

    @Test
    fun `the open park is rounder than the contract park and keeps the shell floor unless told`() {
        val park = OpenPark()
        assertEquals(true, park.cornerDp > SpatialMotion.PARK_CORNER_DP)
        assertEquals(false, park.floor.isSpecified)
        assertEquals(true, OpenPark(floor = Color(0xFFD9DBDE)).floor.isSpecified)
    }

    @Test
    fun `out of range parks are refused`() {
        assertThrows(IllegalArgumentException::class.java) { OpenPark(keepFraction = 0f) }
        assertThrows(IllegalArgumentException::class.java) { OpenPark(keepFraction = 0.95f) }
        assertThrows(IllegalArgumentException::class.java) { OpenPark(scale = 0.3f) }
        assertThrows(IllegalArgumentException::class.java) { OpenPark(scale = 1.2f) }
        assertThrows(IllegalArgumentException::class.java) { OpenPark(cornerDp = -1f) }
        assertThrows(IllegalArgumentException::class.java) { OpenPark(cornerDp = 80f) }
    }

    @Test
    fun `a left open park drags over its own travel so the card stays under the finger`() {
        // Opening from home towards the left room, and anywhere on the left side, divides by the
        // card's travel.
        assertEquals(430f, horizontalTravelPx(0f, 12f, 1000, 430f), 0f)
        assertEquals(430f, horizontalTravelPx(0.4f, -12f, 1000, 430f), 0f)
    }

    @Test
    fun `everything else keeps the width as its travel`() {
        // No open park: unchanged for every consumer.
        assertEquals(1000f, horizontalTravelPx(0.4f, 12f, 1000, 0f), 0f)
        // The right side, and a drag from home towards the right room.
        assertEquals(1000f, horizontalTravelPx(-0.4f, 12f, 1000, 430f), 0f)
        assertEquals(1000f, horizontalTravelPx(0f, -12f, 1000, 430f), 0f)
        // Before measuring, a sane extent rather than zero.
        assertEquals(1f, horizontalTravelPx(0f, -1f, 0, 430f), 0f)
    }
}
