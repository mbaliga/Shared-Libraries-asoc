package dev.aarso.modelbench

/**
 * The result envelope, one instance per [BenchmarkSuite.run] call. Field-for-field, this mirrors
 * `schema/modelbench-report.v1.schema.json` (also on the classpath as a resource) — see that file
 * for the wire-format grammar and the house conventions it follows (schemaVersion pinned to major
 * 1, a producer{app,version} block, per the style of Android-IDE-core's schemas under
 * `schemas/integrations`). [dev.aarso.modelbench.json.toJson] serializes this to that exact shape.
 */
data class ModelBenchReport(
    val schemaVersion: String = "1.0.0",
    val reportId: String,
    val generatedAt: String,
    val producer: ProducerRef,
    val device: DeviceProvenance,
    val engine: EngineInfo,
    val model: ModelRef,
    val runs: List<BenchmarkRun>,
)

/** Who produced this report — the app/module, not the device. */
data class ProducerRef(
    val app: String,
    val version: String,
)

/**
 * Run provenance. All fields beyond [deviceModel] are best-effort and frequently null — thermal
 * status and governor are only readable at all on some devices, and none of them are readable
 * from this JVM-only build environment. A JVM-host run should supply a synthetic/placeholder
 * [deviceModel] rather than leave it blank.
 */
data class DeviceProvenance(
    val deviceModel: String,
    val androidRelease: String? = null,
    val thermalStatus: String? = null,
    val governor: String? = null,
)

/** Identifies the engine build under test. */
data class EngineInfo(
    val name: String,
    val version: String,
)

/** Derived metrics for one sweep point. See [BenchmarkSuite] for exactly how each is computed. */
data class RunMetrics(
    val timeToFirstTokenMs: Double?,
    val promptProcessingTokPerSec: Double,
    val decodeTokPerSec: Double,
    val peakRssDeltaBytes: Long?,
    val loadTimeMs: Double,
)

/** One context-length sweep point's full result. */
data class BenchmarkRun(
    val contextLength: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val metrics: RunMetrics,
    val thermal: ThermalSample? = null,
)
