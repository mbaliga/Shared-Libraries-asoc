package dev.aarso.modelbench

/**
 * The seam a real inference-engine adapter implements. This module ships NO native code and NO
 * llama.cpp integration — that lands with the asystemofmodels router, in that repo, driving this
 * interface. Here the seam exists so [BenchmarkSuite]'s timing/throughput math can be fully
 * JVM-tested today against [SyntheticEngine], with zero device/emulator dependency.
 *
 * Implementations are used strictly load -> (processPrompt / generateTokens)* -> unloadModel,
 * once per [BenchmarkSuite.run] call. [BenchmarkSuite] does not call these methods concurrently.
 */
interface EngineAdapter {
    /** Stable engine identifier, e.g. "llama.cpp" for the eventual real adapter, "synthetic" here. */
    val engineName: String

    /** The engine build/version string, opaque to this module. */
    val engineVersion: String

    /** Loads [model] into the engine. Must be called before [processPrompt] or [generateTokens]. */
    fun loadModel(model: ModelRef): LoadOutcome

    /**
     * Feeds [promptTokens] tokens of prompt through the engine (prefill). [onToken] is invoked
     * once per token processed, in order, with the cumulative elapsed time since this call began
     * — [BenchmarkSuite] derives prompt-processing throughput from the outcome's totals, not from
     * the callback stream, so a real adapter may invoke [onToken] as coarsely or finely as it can
     * measure.
     */
    fun processPrompt(promptTokens: Int, onToken: (tokenIndex: Int, elapsedNanos: Long) -> Unit): PromptProcessOutcome

    /**
     * Generates up to [maxTokens] tokens (decode). [onToken] is invoked once per token generated,
     * in order, with the cumulative elapsed time since this call began — [BenchmarkSuite] reads
     * the first callback's `elapsedNanos` as time-to-first-token, so a real adapter MUST invoke
     * [onToken] for token index 0 as soon as that token is actually available, not batched with
     * later tokens.
     */
    fun generateTokens(maxTokens: Int, onToken: (tokenIndex: Int, elapsedNanos: Long) -> Unit): GenerationOutcome

    /** Releases the loaded model. Called once per [BenchmarkSuite.run], after the last sweep point. */
    fun unloadModel()

    /**
     * Peak resident-set size in bytes observed since [loadModel], if the platform can report it.
     * [BenchmarkSuite] samples this right after [loadModel] and again after each sweep point, and
     * reports the delta — so a monotonically-increasing "peak so far" reading is fine; it does not
     * need to reset between samples. Returns null when unavailable (true for [SyntheticEngine] and
     * any plain JVM host — RSS sampling is a device-side concern this module only defines a hook
     * for).
     */
    fun peakResidentSetBytes(): Long?
}

/** Identifies the model under test. Mirrors the `model` block of `modelbench-report.v1`. */
data class ModelRef(
    val ggufName: String,
    val quant: String,
    val paramsB: Double? = null,
)

/** Result of [EngineAdapter.loadModel]. */
data class LoadOutcome(
    val elapsedNanos: Long,
    val success: Boolean,
    val error: String? = null,
)

/** Result of [EngineAdapter.processPrompt]. */
data class PromptProcessOutcome(
    val tokensProcessed: Int,
    val totalElapsedNanos: Long,
)

/** Result of [EngineAdapter.generateTokens]. */
data class GenerationOutcome(
    val tokensGenerated: Int,
    val totalElapsedNanos: Long,
)

/** One point in a context-length sweep: how much prompt to prefill, then how much to generate. */
data class ContextSweepPoint(
    val contextLength: Int,
    val promptTokens: Int,
    val maxGenerateTokens: Int,
)
