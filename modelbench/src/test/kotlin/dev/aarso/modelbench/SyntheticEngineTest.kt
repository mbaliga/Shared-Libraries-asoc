package dev.aarso.modelbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyntheticEngineTest {

    private val model = ModelRef(ggufName = "x.gguf", quant = "Q4_0")

    @Test
    fun `scripted per-token latencies cycle when fewer are given than tokens requested`() {
        val engine = SyntheticEngine(promptTokenNanos = listOf(1L, 2L, 3L))
        engine.loadModel(model)

        val callbacks = mutableListOf<Pair<Int, Long>>()
        val outcome = engine.processPrompt(7) { index, elapsed -> callbacks.add(index to elapsed) }

        // cycle 1,2,3,1,2,3,1 -> cumulative 1,3,6,7,9,12,13
        assertEquals(listOf(1L, 3L, 6L, 7L, 9L, 12L, 13L), callbacks.map { it.second })
        assertEquals((0..6).toList(), callbacks.map { it.first })
        assertEquals(7, outcome.tokensProcessed)
        assertEquals(13L, outcome.totalElapsedNanos)
    }

    @Test
    fun `two fresh instances with identical scripts produce identical outcomes`() {
        fun runOnce(): GenerationOutcome {
            val engine = SyntheticEngine(decodeTokenNanos = listOf(5L, 7L))
            engine.loadModel(model)
            return engine.generateTokens(5) { _, _ -> }
        }
        assertEquals(runOnce(), runOnce())
    }

    @Test
    fun `processPrompt before loadModel is rejected`() {
        val engine = SyntheticEngine()
        assertThrows(IllegalStateException::class.java) {
            engine.processPrompt(1) { _, _ -> }
        }
    }

    @Test
    fun `unloadModel then a new call is rejected`() {
        val engine = SyntheticEngine()
        engine.loadModel(model)
        engine.unloadModel()
        assertThrows(IllegalStateException::class.java) {
            engine.generateTokens(1) { _, _ -> }
        }
    }

    @Test
    fun `RSS sequence repeats its last value once exhausted`() {
        val engine = SyntheticEngine(rssBytesSequence = listOf(100L, 200L))
        assertEquals(100L, engine.peakResidentSetBytes())
        assertEquals(200L, engine.peakResidentSetBytes())
        assertEquals(200L, engine.peakResidentSetBytes())
        assertEquals(200L, engine.peakResidentSetBytes())
    }

    @Test
    fun `RSS is null when no sequence was scripted`() {
        val engine = SyntheticEngine()
        assertEquals(null, engine.peakResidentSetBytes())
    }
}
