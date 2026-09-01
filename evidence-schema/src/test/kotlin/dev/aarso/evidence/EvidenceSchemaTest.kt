package dev.aarso.evidence

import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceSchemaTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)) { "Missing $path" }.readText()

    @Test fun envelopePinsTrustFieldsAndResolutionLawVocabulary() {
        val schema = resource("evidence/v1/evidence-record.schema.json")
        listOf(
            "contentHash", "privacyClass", "schemaVersion", "observedAt", "ingestedAt",
            "RAW_SAMPLE", "WINDOW", "EVENT", "SESSION", "DAY", "PERIOD",
            "E0", "E1", "E2", "E3", "E4", "E5", "retractsId",
        ).forEach { assertTrue("missing $it", schema.contains("\"$it\"")) }
    }

    @Test fun provenancePinsSourceAndClockUncertainty() {
        val schema = resource("evidence/v1/provenance.schema.json")
        listOf("sourceId", "sourceKind", "appVersion", "deviceModel", "clockUncertaintyMs")
            .forEach { assertTrue("missing $it", schema.contains("\"$it\"")) }
    }
}
