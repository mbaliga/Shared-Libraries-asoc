package dev.aarso.diagnostics.core

/**
 * The generic measurement vocabulary. THIS is the file that made the module work across the
 * portfolio rather than only for Activity-hosted UI apps.
 *
 * The realisation behind it: an audio callback overrunning its buffer period, a camera frame
 * missing its capture cadence, a BLE sample arriving late, and a UI frame missing vsync are the
 * SAME statistical object — a timed observation judged against a budget. They differ only in where
 * the observation comes from and what the budget is derived from. So the aggregation, the
 * percentiles, the overrun rate, the verdicts and the report layout are written once here and every
 * source reuses them.
 *
 * What is genuinely per-app-type is not the maths. It is:
 *   - where the budget comes from (vsync / buffer period / capture rate / SLA),
 *   - what an overrun is CALLED (jank / underrun / dropped frame / slow request),
 *   - and which invariants must hold (see Invariants.kt).
 */

// ---------------------------------------------------------------- observations

/**
 * One timed observation. [phases] generalises the FrameMetrics breakdown: for a UI frame the keys
 * are draw/layout/sync/…, for a vision pipeline they are camera/inference/dsp/render. Same table,
 * same dominance analysis, different vocabulary.
 */
data class Observation(
    val tSec: Double,
    val valueMs: Double,
    /** Screen, endpoint, stage, scene — whatever this series splits by. */
    val bucket: String? = null,
    val phases: Map<String, Double> = emptyMap(),
    val first: Boolean = false,
)

// ---------------------------------------------------------------- specs

/**
 * What kind of thing the series measures. This distinction is not cosmetic — it changes what counts
 * as a failure.
 *
 * DURATION is work that must FIT INSIDE a budget: a frame, an audio callback, an inference pass.
 * Exceeding the budget by any amount is the failure, so the overrun threshold is the budget itself.
 *
 * INTERVAL is the spacing between arrivals: camera frames at 30 fps, EEG samples at 256 Hz. These
 * jitter around the nominal period by construction, so roughly half of them exceed it on a
 * perfectly healthy stream. Judging an interval series against its own nominal period produces a
 * permanent ~50 % "late" rate — a metric that is not merely useless but actively harmful, because a
 * report that always fails is a report nobody reads. The meaningful failure for an interval is a
 * GAP: an arrival substantially later than nominal, which is why these carry a tolerance multiple.
 */
enum class SeriesKind { DURATION, INTERVAL }

data class SeriesTargets(
    val overrunRatePctGood: Double = 5.0,
    val overrunRatePctWarn: Double = 15.0,
    val p99BudgetMultipleGood: Double = 2.0,
    val p99BudgetMultipleWarn: Double = 4.0,
    val severeCountGood: Int = 0,
    val severeCountWarn: Int = 2,
)

/**
 * The description of one measured stream.
 *
 * [budgetMs] is often not knowable until runtime — it comes from the display's actual refresh rate,
 * the negotiated audio buffer, the camera's capture cadence. Profiles therefore declare a spec with
 * [budgetMs] = 0 meaning "unresolved", and the source resolves it via [resolve]. A series that is
 * still unresolved at report time is reported as not-measured rather than judged against a
 * fabricated budget — assuming 16.67 ms is exactly the class of quiet wrongness this module exists
 * to prevent.
 */
data class SeriesSpec(
    val id: String,
    val title: String,
    val budgetMs: Double = 0.0,
    val budgetLabel: String = "unresolved",
    /** What an overrun is called in this domain. Reports read wrong if a dropped audio buffer is called "jank". */
    val overrunWord: String = "overrun",
    val kind: SeriesKind = SeriesKind.DURATION,
    /**
     * Overrun threshold as a multiple of [budgetMs]. 1.0 for DURATION series — work either fits or
     * it does not. Above 1.0 for INTERVAL series, where jitter around the nominal period is normal
     * and only a real gap is a defect.
     */
    val overrunMultiple: Double = 1.0,
    /** Threshold for a categorically-different failure (a frozen frame, a stalled request). Null if the domain has none. */
    val severeMs: Double? = null,
    val severeWord: String = "severe",
    val bucketLabel: String? = null,
    val phaseOrder: List<String> = emptyList(),
    val targets: SeriesTargets = SeriesTargets(),
    val worstCount: Int = 10,
    /** Lower budget = better here? Always true so far, but stated rather than assumed. */
    val lowerIsBetter: Boolean = true,
) {
    val resolved: Boolean get() = budgetMs > 0.0

    /** The value an observation must exceed to count as an overrun. */
    val overrunAtMs: Double get() = budgetMs * overrunMultiple

    /** How the overrun rule reads in the report, so the threshold is never implicit. */
    val overrunRule: String get() = when (kind) {
        SeriesKind.DURATION -> "> ${Stats.f(overrunAtMs)} ms (the budget)"
        SeriesKind.INTERVAL -> "> ${Stats.f(overrunAtMs)} ms " +
            "(${Stats.f(overrunMultiple, 2)} x the ${Stats.f(budgetMs)} ms nominal interval — " +
            "jitter around nominal is expected, a gap is not)"
    }

    fun resolve(budgetMs: Double, budgetLabel: String): SeriesSpec =
        copy(budgetMs = budgetMs, budgetLabel = budgetLabel)
}

// ---------------------------------------------------------------- aggregates

data class SeriesStats(
    val count: Int,
    val p50: Double,
    val p90: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
    val mean: Double,
    val overrunCount: Int,
    val overrunRatePct: Double,
    val severeCount: Int,
) {
    companion object { val EMPTY = SeriesStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0) }
}

data class NamedStats(val label: String, val stats: SeriesStats)

data class BucketStats(val bucket: String, val stats: SeriesStats, val firstMs: Double?)

data class SeriesReport(
    val spec: SeriesSpec,
    val durationSec: Double,
    val whole: SeriesStats,
    val windows: List<NamedStats> = emptyList(),
    val buckets: List<BucketStats> = emptyList(),
    val worst: List<Observation> = emptyList(),
    /** Mean phase durations over the worst NON-severe observations. */
    val phaseMeans: Map<String, Double> = emptyMap(),
) {
    /** Observations per second — "fps" for a frame series, "requests/s" for a service one. */
    val rate: Double get() = if (durationSec > 0) whole.count / durationSec else 0.0
}

// ---------------------------------------------------------------- trends (sampled gauges)

data class TrendSeries(
    val name: String,
    val start: Double,
    val peak: Double,
    val end: Double,
    val slopePerMin: Double,
    val r2: Double,
)

/**
 * A group of sampled gauges sharing a unit. Memory is one group; a stream profile contributes a
 * signal-quality group; a service profile contributes a queue-depth group. Same trend analysis.
 */
data class TrendGroup(
    val id: String,
    val title: String,
    val unit: String,
    val sampleCount: Int,
    val sampleIntervalMs: Long,
    val series: List<TrendSeries>,
    val notes: List<String> = emptyList(),
    /** Below this R² a slope is reported as inconclusive rather than raised as a finding. */
    val minR2: Double = 0.70,
    val slopeGoodPerMin: Double = 0.5,
    val slopeWarnPerMin: Double = 2.0,
) {
    fun byName(n: String): TrendSeries? = series.firstOrNull { it.name == n }
}

// ---------------------------------------------------------------- facts & counters

/**
 * A discrete environment fact: which inference delegate was actually used, whether the low-latency
 * audio path was granted, the negotiated sample rate. Facts are what invariants mostly assert over,
 * and they are the single highest-value addition in this rework — a silent GPU→CPU delegate
 * fallback is invisible to every timing metric until you already have a problem.
 */
data class Fact(val key: String, val value: String, val note: String? = null)

data class CounterValue(val name: String, val value: Long, val note: String? = null)
