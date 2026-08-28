package dev.aarso.modelbench

/**
 * Battery/thermal sampling hook. Real sensors (PowerManager.getCurrentThermalStatus,
 * BatteryManager) are device-side only — this container has no device to read them from — so
 * this is an interface a host app implements, plus [NONE] for JVM-only runs. [BenchmarkSuite]
 * calls [sample] at most once per sweep point.
 */
fun interface ThermalSampler {
    fun sample(): ThermalSample?

    companion object {
        /** The default: no sampler, every run's `thermal` field is null. */
        val NONE = ThermalSampler { null }
    }
}

/** One thermal/battery reading. Mirrors the `ThermalSample` def of `modelbench-report.v1`. */
data class ThermalSample(
    val status: String,
    val batteryPercent: Int? = null,
    val atElapsedNanos: Long? = null,
)
