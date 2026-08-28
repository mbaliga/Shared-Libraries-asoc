package dev.aarso.modelbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkSuiteTest {

    private val model = ModelRef(ggufName = "tiny-test.gguf", quant = "Q4_K_M", paramsB = 1.1)
    private val device = DeviceProvenance(deviceModel = "jvm-host")
    private val producer = ProducerRef(app = "modelbench", version = "0.1.0")

    @Test
    fun `derives tok-per-second and time-to-first-token from scripted timings`() {
        val engine = SyntheticEngine(
            loadNanos = 500_000_000L, // 500ms
            promptTokenNanos = listOf(1_000_000L), // 1ms/token -> 1000 tok/s
            decodeTokenNanos = listOf(2_000_000L), // 2ms/token -> 500 tok/s, TTFT 2ms
        )
        val suite = BenchmarkSuite(engine)

        val report = suite.run(
            model = model,
            sweep = listOf(ContextSweepPoint(contextLength = 2048, promptTokens = 100, maxGenerateTokens = 50)),
            device = device,
            producer = producer,
            reportId = "report-1",
            generatedAt = "2026-08-28T00:00:00Z",
        )

        assertEquals(1, report.runs.size)
        val run = report.runs.single()
        assertEquals(2048, run.contextLength)
        assertEquals(100, run.promptTokens)
        assertEquals(50, run.generatedTokens)
        assertEquals(2.0, run.metrics.timeToFirstTokenMs!!, 0.0)
        assertEquals(1000.0, run.metrics.promptProcessingTokPerSec, 1e-9)
        assertEquals(500.0, run.metrics.decodeTokPerSec, 1e-9)
        assertEquals(500.0, run.metrics.loadTimeMs, 0.0)
        assertNull(run.metrics.peakRssDeltaBytes)
        assertEquals("synthetic", report.engine.name)
        assertEquals("modelbench", report.producer.app)
        assertEquals("1.0.0", report.schemaVersion)
    }

    @Test
    fun `zero generated tokens leaves time-to-first-token null and decode throughput zero`() {
        val engine = SyntheticEngine(decodeTokenNanos = listOf(2_000_000L))
        val suite = BenchmarkSuite(engine)

        val report = suite.run(
            model = model,
            sweep = listOf(ContextSweepPoint(contextLength = 512, promptTokens = 10, maxGenerateTokens = 0)),
            device = device,
            producer = producer,
            reportId = "report-2",
            generatedAt = "2026-08-28T00:00:00Z",
        )

        val run = report.runs.single()
        assertEquals(0, run.generatedTokens)
        assertNull(run.metrics.timeToFirstTokenMs)
        assertEquals(0.0, run.metrics.decodeTokPerSec, 0.0)
    }

    @Test
    fun `zero prompt tokens yields zero prompt throughput, never NaN`() {
        val engine = SyntheticEngine(promptTokenNanos = listOf(1_000_000L))
        val suite = BenchmarkSuite(engine)

        val report = suite.run(
            model = model,
            sweep = listOf(ContextSweepPoint(contextLength = 512, promptTokens = 0, maxGenerateTokens = 5)),
            device = device,
            producer = producer,
            reportId = "report-3",
            generatedAt = "2026-08-28T00:00:00Z",
        )

        val run = report.runs.single()
        assertEquals(0, run.promptTokens)
        assertEquals(0.0, run.metrics.promptProcessingTokPerSec, 0.0)
        assertFalse(run.metrics.promptProcessingTokPerSec.isNaN())
    }


    @Test
    fun `load happens once per sweep -- loadTimeMs repeats identically across every point`() {
        val engine = SyntheticEngine(loadNanos = 250_000_000L)
        val suite = BenchmarkSuite(engine)

        val report = suite.run(
            model = model,
            sweep = listOf(
                ContextSweepPoint(contextLength = 512, promptTokens = 10, maxGenerateTokens = 5),
                ContextSweepPoint(contextLength = 1024, promptTokens = 20, maxGenerateTokens = 5),
                ContextSweepPoint(contextLength = 2048, promptTokens = 40, maxGenerateTokens = 5),
            ),
            device = device,
            producer = producer,
            reportId = "report-4",
            generatedAt = "2026-08-28T00:00:00Z",
        )

        assertEquals(3, report.runs.size)
        assertTrue(report.runs.all { it.metrics.loadTimeMs == 250.0 })
        assertEquals(listOf(512, 1024, 2048), report.runs.map { it.contextLength })
    }

    @Test
    fun `peak RSS delta is sampled per point against the post-load baseline`() {
        val engine = SyntheticEngine(rssBytesSequence = listOf(1_000_000L, 1_500_000L, 1_800_000L))
        val suite = BenchmarkSuite(engine)

        val report = suite.run(
            model = model,
            sweep = listOf(
                ContextSweepPoint(contextLength = 512, promptTokens = 10, maxGenerateTokens = 5),
                ContextSweepPoint(contextLength = 1024, promptTokens = 10, maxGenerateTokens = 5),
            ),
            device = device,
            producer = producer,
            reportId = "report-5",
            generatedAt = "2026-08-28T00:00:00Z",
        )

        assertEquals(500_000L, report.runs[0].metrics.peakRssDeltaBytes)
        assertEquals(800_000L, report.runs[1].metrics.peakRssDeltaBytes)
    }

    @Test
    fun `empty sweep is rejected`() {
        val suite = BenchmarkSuite(SyntheticEngine())
        assertThrows(IllegalArgumentException::class.java) {
            suite.run(
                model = model,
                sweep = emptyList(),
                device = device,
                producer = producer,
                reportId = "report-6",
                generatedAt = "2026-08-28T00:00:00Z",
            )
        }
    }

    @Test
    fun `a failed model load aborts the run instead of reporting bogus metrics`() {
        val engine = SyntheticEngine(loadSucceeds = false, loadError = "out of memory")
        val suite = BenchmarkSuite(engine)
        val error = assertThrows(IllegalStateException::class.java) {
            suite.run(
                model = model,
                sweep = listOf(ContextSweepPoint(contextLength = 512, promptTokens = 10, maxGenerateTokens = 5)),
                device = device,
                producer = producer,
                reportId = "report-7",
                generatedAt = "2026-08-28T00:00:00Z",
            )
        }
        assertTrue(error.message!!.contains("out of memory"))
    }

    @Test
    fun `thermal sampler is consulted once per point`() {
        var calls = 0
        val sampler = ThermalSampler {
            calls++
            ThermalSample(status = "NOMINAL", batteryPercent = 90 - calls)
        }
        val suite = BenchmarkSuite(SyntheticEngine(), thermalSampler = sampler)

        val report = suite.run(
            model = model,
            sweep = listOf(
                ContextSweepPoint(contextLength = 512, promptTokens = 1, maxGenerateTokens = 1),
                ContextSweepPoint(contextLength = 1024, promptTokens = 1, maxGenerateTokens = 1),
            ),
            device = device,
            producer = producer,
            reportId = "report-8",
            generatedAt = "2026-08-28T00:00:00Z",
        )

        assertEquals(2, calls)
        assertEquals("NOMINAL", report.runs[0].thermal?.status)
        assertEquals(89, report.runs[0].thermal?.batteryPercent)
        assertEquals(88, report.runs[1].thermal?.batteryPercent)
    }
}
