package dev.aarso.modelbench.json

import dev.aarso.modelbench.BenchmarkRun
import dev.aarso.modelbench.ModelBenchReport
import dev.aarso.modelbench.ThermalSample

/**
 * Serializes [this] to the `modelbench-report.v1` wire shape — see
 * `schema/modelbench-report.v1.schema.json` (also bundled on the classpath under
 * `schema/modelbench-report.v1.schema.json`) for the grammar this must match, and
 * `ModelBenchReportJsonSchemaValidationTest` for the test that actually validates output
 * against it rather than trusting this function by inspection.
 */
fun ModelBenchReport.toJson(): String = jObj(
    "schemaVersion" to jStr(schemaVersion),
    "reportId" to jStr(reportId),
    "generatedAt" to jStr(generatedAt),
    "producer" to jObj(
        "app" to jStr(producer.app),
        "version" to jStr(producer.version),
    ),
    "device" to jObj(
        "deviceModel" to jStr(device.deviceModel),
        "androidRelease" to jStrOrNull(device.androidRelease),
        "thermalStatus" to jStrOrNull(device.thermalStatus),
        "governor" to jStrOrNull(device.governor),
    ),
    "engine" to jObj(
        "name" to jStr(engine.name),
        "version" to jStr(engine.version),
    ),
    "model" to jObj(
        "ggufName" to jStr(model.ggufName),
        "quant" to jStr(model.quant),
        "paramsB" to jDoubleOrNull(model.paramsB),
    ),
    "runs" to jArr(runs.map { it.toJsonValue() }),
).render()

private fun BenchmarkRun.toJsonValue(): JsonValue = jObj(
    "contextLength" to jInt(contextLength),
    "promptTokens" to jInt(promptTokens),
    "generatedTokens" to jInt(generatedTokens),
    "metrics" to jObj(
        "timeToFirstTokenMs" to jDoubleOrNull(metrics.timeToFirstTokenMs),
        "promptProcessingTokPerSec" to jDouble(metrics.promptProcessingTokPerSec),
        "decodeTokPerSec" to jDouble(metrics.decodeTokPerSec),
        "peakRssDeltaBytes" to jLongOrNull(metrics.peakRssDeltaBytes),
        "loadTimeMs" to jDouble(metrics.loadTimeMs),
    ),
    "thermal" to (thermal?.toJsonValue() ?: JsonValue.JNull),
)

private fun ThermalSample.toJsonValue(): JsonValue = jObj(
    "status" to jStr(status),
    "batteryPercent" to jIntOrNull(batteryPercent),
    "atElapsedNanos" to jLongOrNull(atElapsedNanos),
)
