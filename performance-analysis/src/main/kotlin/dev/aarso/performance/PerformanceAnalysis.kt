package dev.aarso.performance

import kotlin.math.ceil

/** A monotonic clock seam keeps reports deterministic in tests and safe across wall-clock changes. */
fun interface NanoClock {
    fun nowNanos(): Long
}

data class PerformanceStats(
    val name: String,
    val warmupIterations: Int,
    val measuredIterations: Int,
    val samplesNanos: List<Long>,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(warmupIterations >= 0) { "warmupIterations must be >= 0" }
        require(measuredIterations > 0) { "measuredIterations must be > 0" }
        require(samplesNanos.size == measuredIterations) { "one sample is required per iteration" }
        require(samplesNanos.all { it >= 0 }) { "samples cannot be negative" }
    }

    val minNanos: Long get() = samplesNanos.minOrNull() ?: 0L
    val maxNanos: Long get() = samplesNanos.maxOrNull() ?: 0L
    val meanNanos: Double get() = samplesNanos.average()
    val p50Nanos: Long get() = percentile(0.50)
    val p95Nanos: Long get() = percentile(0.95)
    val p99Nanos: Long get() = percentile(0.99)
    val throughputPerSecond: Double
        get() = if (meanNanos == 0.0) Double.POSITIVE_INFINITY else 1_000_000_000.0 / meanNanos

    /** Stable, compact JSON with no serialization dependency in the shared library. */
    fun toJson(): String = buildString {
        append('{')
        append("\"name\":\"").append(name.jsonEscaped()).append("\",")
        append("\"warmupIterations\":").append(warmupIterations).append(',')
        append("\"measuredIterations\":").append(measuredIterations).append(',')
        append("\"minNanos\":").append(minNanos).append(',')
        append("\"p50Nanos\":").append(p50Nanos).append(',')
        append("\"p95Nanos\":").append(p95Nanos).append(',')
        append("\"p99Nanos\":").append(p99Nanos).append(',')
        append("\"maxNanos\":").append(maxNanos).append(',')
        append("\"meanNanos\":").append(meanNanos).append(',')
        append("\"throughputPerSecond\":").append(throughputPerSecond)
        append('}')
    }

    private fun percentile(rank: Double): Long {
        val sorted = samplesNanos.sorted()
        val index = ceil(rank * sorted.lastIndex).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}

class PerformanceAnalyzer(
    private val clock: NanoClock = NanoClock { System.nanoTime() },
) {
    fun measure(
        name: String,
        warmupIterations: Int = 3,
        measuredIterations: Int = 20,
        operation: () -> Unit,
    ): PerformanceStats {
        require(warmupIterations >= 0) { "warmupIterations must be >= 0" }
        require(measuredIterations > 0) { "measuredIterations must be > 0" }
        repeat(warmupIterations) { operation() }
        val samples = buildList(measuredIterations) {
            repeat(measuredIterations) {
                val start = clock.nowNanos()
                operation()
                add(clock.nowNanos() - start)
            }
        }
        return PerformanceStats(name, warmupIterations, measuredIterations, samples)
    }
}

data class PerformanceReport(
    val suite: String,
    val libraryRevision: String,
    val stats: List<PerformanceStats>,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"suite\":\"").append(suite.jsonEscaped()).append("\",")
        append("\"libraryRevision\":\"").append(libraryRevision.jsonEscaped()).append("\",")
        append("\"measurements\":[")
        stats.joinTo(this, separator = ",") { it.toJson() }
        append("]}")
    }
}

private fun String.jsonEscaped(): String = buildString(length) {
    for (character in this@jsonEscaped) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
