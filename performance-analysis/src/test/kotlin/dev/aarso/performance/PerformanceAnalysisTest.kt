package dev.aarso.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceAnalysisTest {
    @Test
    fun measuresPercentilesAndSerializesAStableReport() {
        var now = 0L
        val clock = NanoClock { now += 10L; now }
        val stats = PerformanceAnalyzer(clock).measure("search", warmupIterations = 1, measuredIterations = 4) { }

        assertEquals(listOf(10L, 10L, 10L, 10L), stats.samplesNanos)
        assertEquals(10L, stats.p50Nanos)
        assertEquals(10L, stats.p95Nanos)
        assertTrue(PerformanceReport("test", "abc123", listOf(stats)).toJson().contains("\"libraryRevision\":\"abc123\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyMeasurementSet() {
        PerformanceAnalyzer().measure("invalid", measuredIterations = 0) { }
    }
}
