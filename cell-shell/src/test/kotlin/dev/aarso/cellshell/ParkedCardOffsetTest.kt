package dev.aarso.cellshell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The host's vertical offset for the open-parked card: only while the left open park shows. */
class ParkedCardOffsetTest {
    @Test
    fun `the offset is added while the left open park shows`() {
        assertEquals(130, combinedParkTy(100, true, 30.4f))
        assertEquals(70, combinedParkTy(100, true, -30f))
    }

    @Test
    fun `the offset is ignored in every other state`() {
        assertEquals(100, combinedParkTy(100, false, 500f))
    }

    @Test
    fun `a bad host value counts as zero`() {
        assertEquals(100, combinedParkTy(100, true, Float.NaN))
        assertEquals(100, combinedParkTy(100, true, Float.POSITIVE_INFINITY))
    }
}
