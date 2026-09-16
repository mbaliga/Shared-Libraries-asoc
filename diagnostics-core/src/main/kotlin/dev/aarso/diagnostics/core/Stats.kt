package dev.aarso.diagnostics.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * All the maths that produces a number in a report. Deliberately boring, deliberately testable, and
 * deliberately source-agnostic — [seriesStats] does not know or care whether it is aggregating UI
 * frames, audio callbacks, BLE inter-sample intervals or HTTP requests.
 */
object Stats {

    /**
     * Nearest-rank percentile. p is 0..1.
     *
     * Nearest-rank rather than interpolated: these are sampled populations, not estimates of a
     * continuous distribution, and a p99 that names an actual observed value is easier to reconcile
     * against the worst-observations table than an interpolated value that never occurred.
     */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    data class Fit(val slope: Double, val intercept: Double, val r2: Double)

    /** Ordinary least squares. Slope is in y-units per x-unit; R² travels with it. */
    fun linearFit(xs: List<Double>, ys: List<Double>): Fit {
        require(xs.size == ys.size) { "x/y length mismatch" }
        val n = xs.size
        if (n < 2) return Fit(0.0, ys.firstOrNull() ?: 0.0, 0.0)
        val mx = mean(xs); val my = mean(ys)
        var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            sxx += dx * dx
            sxy += dx * (ys[i] - my)
        }
        if (sxx == 0.0) return Fit(0.0, my, 0.0)
        val slope = sxy / sxx
        val intercept = my - slope * mx
        var ssRes = 0.0; var ssTot = 0.0
        for (i in 0 until n) {
            val pred = intercept + slope * xs[i]
            ssRes += (ys[i] - pred) * (ys[i] - pred)
            ssTot += (ys[i] - my) * (ys[i] - my)
        }
        return Fit(slope, intercept, if (ssTot == 0.0) 0.0 else max(0.0, 1.0 - ssRes / ssTot))
    }

    // ---------------------------------------------------------------- series

    fun seriesStats(obs: List<Observation>, spec: SeriesSpec): SeriesStats {
        if (obs.isEmpty()) return SeriesStats.EMPTY
        val v = obs.map { it.valueMs }
        val over = if (spec.resolved) v.count { it > spec.overrunAtMs } else 0
        val severe = spec.severeMs?.let { s -> v.count { it > s } } ?: 0
        return SeriesStats(
            count = v.size,
            p50 = percentile(v, 0.50),
            p90 = percentile(v, 0.90),
            p95 = percentile(v, 0.95),
            p99 = percentile(v, 0.99),
            max = v.max(),
            mean = mean(v),
            overrunCount = over,
            overrunRatePct = over * 100.0 / v.size,
            severeCount = severe,
        )
    }

    /**
     * Full aggregation for one series, including the split by bucket and the phase means.
     *
     * Phase means deliberately EXCLUDE severe observations. One 812 ms layout outlier otherwise
     * rewrites the phase attribution for every merely-slow observation, which hides the pattern
     * that actually reproduces. Severe observations are reported individually instead.
     */
    fun seriesReport(
        obs: List<Observation>,
        spec: SeriesSpec,
        durationSec: Double,
        windows: List<Pair<String, (Observation) -> Boolean>> = emptyList(),
    ): SeriesReport {
        val whole = seriesStats(obs, spec)
        val nonSevere = spec.severeMs?.let { s -> obs.filter { it.valueMs <= s } } ?: obs
        val worst = obs.sortedByDescending { it.valueMs }.take(spec.worstCount)
        val worstNonSevere = worst.filter { o -> spec.severeMs?.let { o.valueMs <= it } ?: true }

        val phaseMeans = if (worstNonSevere.isEmpty()) emptyMap() else {
            val keys = worstNonSevere.flatMap { it.phases.keys }.distinct()
            keys.associateWith { k -> mean(worstNonSevere.map { it.phases[k] ?: 0.0 }) }
        }

        return SeriesReport(
            spec = spec,
            durationSec = durationSec,
            whole = whole,
            windows = windows.map { (label, pred) ->
                NamedStats(label, seriesStats(obs.filter(pred), spec))
            },
            buckets = obs.groupBy { it.bucket }.filterKeys { it != null }.map { (b, o) ->
                BucketStats(b!!, seriesStats(o, spec), o.firstOrNull { it.first }?.valueMs)
            }.sortedByDescending { it.stats.count },
            worst = worst,
            phaseMeans = phaseMeans,
        ).also { if (nonSevere.isEmpty() && obs.isNotEmpty()) Unit }
    }

    // ---------------------------------------------------------------- trends

    /**
     * Trend for one sampled gauge. The slope is per minute so it reads at human scale, and the R²
     * travels with it: a steep slope with a poor fit is noise, not a finding, and the verdict engine
     * refuses to raise an alarm without both.
     */
    fun trendSeries(name: String, tSec: List<Double>, values: List<Double>): TrendSeries {
        if (values.isEmpty()) return TrendSeries(name, 0.0, 0.0, 0.0, 0.0, 0.0)
        val fit = linearFit(tSec, values)
        return TrendSeries(
            name = name,
            start = values.first(),
            peak = values.max(),
            end = values.last(),
            slopePerMin = fit.slope * 60.0,
            r2 = fit.r2,
        )
    }

    fun spanStat(name: String, durationsMs: List<Double>): SpanStat {
        val n = durationsMs.size
        return SpanStat(
            name = name,
            count = n,
            p50Ms = percentile(durationsMs, 0.50),
            // A p95 from fewer than 20 observations is not a p95. Report it as absent.
            p95Ms = if (n >= 20) percentile(durationsMs, 0.95) else null,
            maxMs = durationsMs.maxOrNull() ?: 0.0,
        )
    }

    // ---------------------------------------------------------------- budget helpers
    // Each app type derives its budget from something different. Stated once, here, so no source
    // has to invent one — and so a wrong budget is a bug in one visible place.

    /** UI / wallpaper / IME: the display's ACTUAL refresh rate. */
    fun vsyncBudget(refreshHz: Double): Double = if (refreshHz > 0) 1000.0 / refreshHz else 0.0

    /** Audio: one callback must complete inside the buffer it is filling. */
    fun audioBudget(bufferFrames: Int, sampleRateHz: Int): Double =
        if (sampleRateHz > 0) bufferFrames * 1000.0 / sampleRateHz else 0.0

    /** Camera / sensor streams: the inter-arrival period implied by the capture rate. */
    fun rateBudget(hz: Double): Double = if (hz > 0) 1000.0 / hz else 0.0

    // ---------------------------------------------------------------- formatting

    fun f(v: Double, dp: Int = 2): String = String.format("%.${dp}f", v)

    /** Thousands-separated with a thin space, e.g. 14 382 — reads cleanly in monospace. */
    fun grp(v: Long): String {
        val s = v.toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return sb.toString()
    }

    fun grp(v: Int): String = grp(v.toLong())

    fun mmss(sec: Double): String {
        val t = sec.roundToInt()
        return "%02d:%02d".format(t / 60, t % 60)
    }
}
