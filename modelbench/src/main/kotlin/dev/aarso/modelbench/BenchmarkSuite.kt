package dev.aarso.modelbench

/**
 * Runs one [EngineAdapter] through a context-length sweep and derives the metrics
 * `modelbench-report.v1` reports. All math here is pure and deterministic given the adapter's
 * outcomes, which is what makes it fully JVM-testable against [SyntheticEngine] with no device.
 *
 * Metric definitions (each computed once per sweep point):
 *  - **time-to-first-token**: elapsed time from the start of [EngineAdapter.generateTokens] to
 *    its token-index-0 callback. Null if a point generates zero tokens.
 *  - **prompt-processing tok/s**: `tokensProcessed / (promptProcessOutcome.totalElapsedNanos /
 *    1e9)`. Zero (not NaN/Infinity) when the elapsed time is zero, e.g. an empty prompt.
 *  - **decode tok/s**: same shape, over [GenerationOutcome].
 *  - **peak RSS delta**: [EngineAdapter.peakResidentSetBytes] sampled right after `loadModel`,
 *    subtracted from the same call sampled again after the sweep point finishes. Null unless the
 *    adapter can report RSS on both samples.
 *  - **load time**: [LoadOutcome.elapsedNanos] from the single `loadModel` call this sweep made,
 *    repeated on every point's `metrics.loadTimeMs` (load happens once per sweep, not per point).
 *  - **thermal**: [ThermalSampler.sample] called once per point, after generation.
 */
class BenchmarkSuite(
    private val engine: EngineAdapter,
    private val thermalSampler: ThermalSampler = ThermalSampler.NONE,
) {
    /**
     * Loads [model], runs every point of [sweep] in order, then unloads. Throws
     * [IllegalArgumentException] if [sweep] is empty and [IllegalStateException] if
     * [EngineAdapter.loadModel] reports failure — a benchmark run over a model that didn't load
     * has nothing meaningful to report.
     */
    fun run(
        model: ModelRef,
        sweep: List<ContextSweepPoint>,
        device: DeviceProvenance,
        producer: ProducerRef,
        reportId: String,
        generatedAt: String,
    ): ModelBenchReport {
        require(sweep.isNotEmpty()) { "sweep must contain at least one point" }

        val load = engine.loadModel(model)
        check(load.success) { "loadModel failed for ${model.ggufName}: ${load.error}" }
        val rssAfterLoad = engine.peakResidentSetBytes()

        val runs = try {
            sweep.map { point -> runPoint(point, load, rssAfterLoad) }
        } finally {
            engine.unloadModel()
        }

        return ModelBenchReport(
            reportId = reportId,
            generatedAt = generatedAt,
            producer = producer,
            device = device,
            engine = EngineInfo(name = engine.engineName, version = engine.engineVersion),
            model = model,
            runs = runs,
        )
    }

    private fun runPoint(point: ContextSweepPoint, load: LoadOutcome, rssAfterLoad: Long?): BenchmarkRun {
        val prompt = engine.processPrompt(point.promptTokens) { _, _ -> }

        var firstTokenNanos: Long? = null
        val generation = engine.generateTokens(point.maxGenerateTokens) { tokenIndex, elapsedNanos ->
            if (tokenIndex == 0) firstTokenNanos = elapsedNanos
        }

        val thermal = thermalSampler.sample()
        val rssAfterPoint = engine.peakResidentSetBytes()
        val rssDelta = if (rssAfterLoad != null && rssAfterPoint != null) rssAfterPoint - rssAfterLoad else null

        val promptSeconds = prompt.totalElapsedNanos / NANOS_PER_SECOND
        val decodeSeconds = generation.totalElapsedNanos / NANOS_PER_SECOND

        return BenchmarkRun(
            contextLength = point.contextLength,
            promptTokens = prompt.tokensProcessed,
            generatedTokens = generation.tokensGenerated,
            metrics = RunMetrics(
                timeToFirstTokenMs = firstTokenNanos?.let { it / NANOS_PER_MILLI },
                promptProcessingTokPerSec = tokPerSec(prompt.tokensProcessed, promptSeconds),
                decodeTokPerSec = tokPerSec(generation.tokensGenerated, decodeSeconds),
                peakRssDeltaBytes = rssDelta,
                loadTimeMs = load.elapsedNanos / NANOS_PER_MILLI,
            ),
            thermal = thermal,
        )
    }

    private fun tokPerSec(tokens: Int, seconds: Double): Double = if (seconds > 0.0) tokens / seconds else 0.0

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
