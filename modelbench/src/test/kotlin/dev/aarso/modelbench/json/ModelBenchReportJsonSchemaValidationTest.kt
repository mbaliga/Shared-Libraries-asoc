package dev.aarso.modelbench.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.aarso.modelbench.BenchmarkSuite
import dev.aarso.modelbench.ContextSweepPoint
import dev.aarso.modelbench.DeviceProvenance
import dev.aarso.modelbench.ModelRef
import dev.aarso.modelbench.ProducerRef
import dev.aarso.modelbench.SyntheticEngine
import dev.aarso.modelbench.ThermalSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real gate for `ModelBenchReport.toJson()`: loads the actual
 * `schema/modelbench-report.v1.schema.json` resource this module ships and validates real
 * output against it with a standalone JSON Schema implementation — not just eyeballing that the
 * writer and the schema agree.
 */
class ModelBenchReportJsonSchemaValidationTest {

    private val mapper = ObjectMapper()
    private val schema: JsonSchema = run {
        val schemaStream = requireNotNull(
            javaClass.classLoader.getResourceAsStream("schema/modelbench-report.v1.schema.json"),
        ) { "schema/modelbench-report.v1.schema.json not found on the test classpath" }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaStream)
    }

    private fun validate(json: String): Set<com.networknt.schema.ValidationMessage> {
        val node: JsonNode = mapper.readTree(json)
        return schema.validate(node)
    }

    @Test
    fun `a full sweep report validates cleanly against modelbench-report v1`() {
        val engine = SyntheticEngine(
            loadNanos = 100_000_000L,
            promptTokenNanos = listOf(1_000_000L),
            decodeTokenNanos = listOf(2_000_000L),
            rssBytesSequence = listOf(1_000_000L, 1_200_000L, 1_400_000L),
        )
        val suite = BenchmarkSuite(engine, thermalSampler = { ThermalSample(status = "NOMINAL", batteryPercent = 80) })

        val report = suite.run(
            model = ModelRef(ggufName = "tiny-test.gguf", quant = "Q4_K_M", paramsB = 1.1),
            sweep = listOf(
                ContextSweepPoint(contextLength = 512, promptTokens = 64, maxGenerateTokens = 32),
                ContextSweepPoint(contextLength = 2048, promptTokens = 256, maxGenerateTokens = 64),
            ),
            device = DeviceProvenance(deviceModel = "jvm-host", androidRelease = null, thermalStatus = null, governor = null),
            producer = ProducerRef(app = "modelbench", version = "0.1.0"),
            reportId = "report-schema-1",
            generatedAt = "2026-08-28T12:00:00Z",
        )

        val messages = validate(report.toJson())
        assertTrue("expected no violations, got: $messages", messages.isEmpty())
    }

    @Test
    fun `null-heavy fields (no RSS, no thermal, zero generated tokens) still validate`() {
        val engine = SyntheticEngine() // no rssBytesSequence -> every RSS sample is null
        val suite = BenchmarkSuite(engine) // default ThermalSampler.NONE -> thermal is null

        val report = suite.run(
            model = ModelRef(ggufName = "x.gguf", quant = "Q8_0"),
            sweep = listOf(ContextSweepPoint(contextLength = 128, promptTokens = 0, maxGenerateTokens = 0)),
            device = DeviceProvenance(deviceModel = "jvm-host"),
            producer = ProducerRef(app = "modelbench", version = "0.1.0"),
            reportId = "report-schema-2",
            generatedAt = "2026-08-28T12:00:00Z",
        )

        val messages = validate(report.toJson())
        assertTrue("expected no violations, got: $messages", messages.isEmpty())
    }

    @Test
    fun `a report missing a required field is rejected -- proves the validator is not vacuous`() {
        // Hand-built JSON missing the required top-level "engine" block.
        val invalidJson = """
            {
              "schemaVersion": "1.0.0",
              "reportId": "r1",
              "generatedAt": "2026-08-28T12:00:00Z",
              "producer": {"app": "modelbench", "version": "0.1.0"},
              "device": {"deviceModel": "jvm-host"},
              "model": {"ggufName": "x.gguf", "quant": "Q8_0"},
              "runs": [
                {
                  "contextLength": 128,
                  "promptTokens": 0,
                  "generatedTokens": 0,
                  "metrics": {"promptProcessingTokPerSec": 0.0, "decodeTokPerSec": 0.0, "loadTimeMs": 0.0}
                }
              ]
            }
        """.trimIndent()

        val messages = validate(invalidJson)
        assertTrue("expected a violation for the missing 'engine' field", messages.isNotEmpty())
    }

    @Test
    fun `an unsupported schemaVersion major is rejected`() {
        val invalidJson = """
            {
              "schemaVersion": "2.0.0",
              "reportId": "r1",
              "generatedAt": "2026-08-28T12:00:00Z",
              "producer": {"app": "modelbench", "version": "0.1.0"},
              "device": {"deviceModel": "jvm-host"},
              "engine": {"name": "synthetic", "version": "1.0.0"},
              "model": {"ggufName": "x.gguf", "quant": "Q8_0"},
              "runs": [
                {
                  "contextLength": 128,
                  "promptTokens": 0,
                  "generatedTokens": 0,
                  "metrics": {"promptProcessingTokPerSec": 0.0, "decodeTokPerSec": 0.0, "loadTimeMs": 0.0}
                }
              ]
            }
        """.trimIndent()

        val messages = validate(invalidJson)
        assertTrue("expected a pattern violation on schemaVersion '2.0.0'", messages.isNotEmpty())
    }

    @Test
    fun `key order matches the schema's declared property order for readability`() {
        val engine = SyntheticEngine()
        val suite = BenchmarkSuite(engine)
        val report = suite.run(
            model = ModelRef(ggufName = "x.gguf", quant = "Q8_0"),
            sweep = listOf(ContextSweepPoint(contextLength = 128, promptTokens = 1, maxGenerateTokens = 1)),
            device = DeviceProvenance(deviceModel = "jvm-host"),
            producer = ProducerRef(app = "modelbench", version = "0.1.0"),
            reportId = "report-schema-3",
            generatedAt = "2026-08-28T12:00:00Z",
        )
        val json = report.toJson()
        val schemaVersionIdx = json.indexOf("\"schemaVersion\"")
        val reportIdIdx = json.indexOf("\"reportId\"")
        val runsIdx = json.indexOf("\"runs\"")
        assertTrue(schemaVersionIdx in 0 until reportIdIdx)
        assertTrue(reportIdIdx < runsIdx)
    }
}
