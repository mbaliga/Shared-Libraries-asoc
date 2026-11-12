package dev.aarso.viz

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    private fun f(n: Double) = VizFormat.compact(n, Locale.US)

    @Test fun smallNumbersKeepTheirDigits() {
        assertEquals("94", f(94.0))
        assertEquals("3.14", f(3.14159))
        assertEquals("0.5", f(0.5))
        assertEquals("98.1", f(98.1))
        assertEquals("12.3", f(12.34))
        assertEquals("0", f(0.0))
        assertEquals("-4", f(-4.0))
    }

    @Test fun thousandsAreGroupedUnderTenThousand() {
        assertEquals("1,234.5", f(1234.5))
        assertEquals("9,999", f(9999.0))
    }

    @Test fun largeNumbersAreCompact() {
        assertEquals("10K", f(10000.0))
        assertEquals("12.3K", f(12345.0))
        assertEquals("1.2M", f(1_234_567.0))
        assertEquals("2B", f(2_000_000_000.0))
        assertEquals("-12.3K", f(-12345.0))
    }

    @Test fun roundingCarriesIntoTheNextUnit() {
        assertEquals("1M", f(999_999.0))
    }

    @Test fun nonFiniteReadsLikeJavaScript() {
        assertEquals("NaN", f(Double.NaN))
        assertEquals("Infinity", f(Double.POSITIVE_INFINITY))
    }

    @Test fun separatorsFollowTheLocale() {
        assertEquals("12,3K", VizFormat.compact(12345.0, Locale.GERMANY))
    }
}
