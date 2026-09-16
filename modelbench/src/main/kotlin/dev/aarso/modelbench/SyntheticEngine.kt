package dev.aarso.modelbench

/**
 * A deterministic, scripted [EngineAdapter] — no model, no native code, no I/O. Drives
 * [BenchmarkSuite]'s own JVM test suite, and doubles as a dev/CI stand-in on any machine with no
 * llama.cpp build (this container included).
 *
 * Per-token latency is scripted as a list of nanosecond deltas; if fewer deltas are given than
 * tokens requested, the script cycles. This keeps a caller from having to hand-write one entry
 * per token for a long sweep while still being exactly deterministic (each call with the same
 * arguments produces the same outcome).
 */
class SyntheticEngine(
    override val engineName: String = "synthetic",
    override val engineVersion: String = "1.0.0",
    private val loadNanos: Long = 0L,
    private val loadSucceeds: Boolean = true,
    private val loadError: String? = null,
    private val promptTokenNanos: List<Long> = listOf(0L),
    private val decodeTokenNanos: List<Long> = listOf(0L),
    /**
     * Values [EngineAdapter.peakResidentSetBytes] returns, one per call, in order; the last
     * value repeats once exhausted. Empty (the default) means "RSS unavailable", matching a
     * plain JVM host.
     */
    private val rssBytesSequence: List<Long?> = emptyList(),
) : EngineAdapter {
    private var loaded = false
    private var rssCallIndex = 0

    override fun loadModel(model: ModelRef): LoadOutcome {
        loaded = loadSucceeds
        return LoadOutcome(elapsedNanos = loadNanos, success = loadSucceeds, error = loadError)
    }

    override fun processPrompt(
        promptTokens: Int,
        onToken: (tokenIndex: Int, elapsedNanos: Long) -> Unit,
    ): PromptProcessOutcome {
        check(loaded) { "loadModel must succeed before processPrompt" }
        require(promptTokens >= 0) { "promptTokens must be >= 0, was $promptTokens" }
        var elapsed = 0L
        for (index in 0 until promptTokens) {
            elapsed += scriptedStep(promptTokenNanos, index)
            onToken(index, elapsed)
        }
        return PromptProcessOutcome(tokensProcessed = promptTokens, totalElapsedNanos = elapsed)
    }

    override fun generateTokens(
        maxTokens: Int,
        onToken: (tokenIndex: Int, elapsedNanos: Long) -> Unit,
    ): GenerationOutcome {
        check(loaded) { "loadModel must succeed before generateTokens" }
        require(maxTokens >= 0) { "maxTokens must be >= 0, was $maxTokens" }
        var elapsed = 0L
        for (index in 0 until maxTokens) {
            elapsed += scriptedStep(decodeTokenNanos, index)
            onToken(index, elapsed)
        }
        return GenerationOutcome(tokensGenerated = maxTokens, totalElapsedNanos = elapsed)
    }

    override fun unloadModel() {
        loaded = false
    }

    override fun peakResidentSetBytes(): Long? {
        if (rssBytesSequence.isEmpty()) return null
        val value = rssBytesSequence[minOf(rssCallIndex, rssBytesSequence.size - 1)]
        rssCallIndex++
        return value
    }

    private fun scriptedStep(script: List<Long>, index: Int): Long {
        if (script.isEmpty()) return 0L
        return script[index % script.size]
    }
}
