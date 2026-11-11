package dev.aarso.evidence

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformEvidenceTest {
    // PORTING_PROGRAM.md section 2, verbatim. — is the em dash.
    private val sectionTwoLabels = listOf(
        "LAB", "CI (hosted VM) evidence", "EMULATOR EVIDENCE", "SIMULATOR",
        "CI-APPROX — NOT DEVICE EVIDENCE", "SIMULATED — NOT DEVICE EVIDENCE",
        "VIRTUALIZED — NOT DEVICE EVIDENCE", "SYNTHETIC", "CI-ONLY / NOT RUN",
        "NEEDS-DEVICE-VALIDATION", "NEEDS-OWNER-VALIDATION", "PLAN", "CONTAINER-BUILD-ONLY", "BROWSER-HEADLESS",
    )

    private fun rec(
        label: String = "NEEDS-DEVICE-VALIDATION",
        outcome: String = "open",
        item: String = "DV-AD-01",
        extra: String = "",
    ): String =
        """{"schema":"platform-evidence/1","id":"r1","repo":"example/app","platform":"android-desktop",
            "item":"$item","label":"$label","outcome":"$outcome","recordedAt":"2026-11-11T10:00:00Z"$extra}"""

    private val ownerRun = ""","recordedBy":"owner","command":"adb shell dumpsys package x","output":"resizeable=true",
        "device":{"model":"Example tablet","os":"Android 16"}"""

    private fun assertValid(json: String) = assertEquals(emptyList<String>(), PlatformEvidenceValidator.validate(json))

    private fun assertInvalid(json: String, fragment: String) {
        val errors = PlatformEvidenceValidator.validate(json)
        assertTrue("expected an error containing '$fragment', got $errors", errors.any { fragment in it })
    }

    @Test fun schemaLabelListMatchesSectionTwoVerbatim() {
        @Suppress("UNCHECKED_CAST")
        val defs = PlatformEvidenceValidator.schema()["\$defs"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val anyOf = defs.getValue("label")["anyOf"] as List<Map<String, Any?>>
        assertEquals(sectionTwoLabels, anyOf[0]["enum"])
        assertEquals("^NOT-APPLICABLE \\(.+\\)\$", anyOf[1]["pattern"])
    }

    @Test fun everySectionTwoLabelIsAcceptedWhereItsRulesAllow() {
        sectionTwoLabels.forEach { label ->
            val outcome = if (label.startsWith("NEEDS-") || label == "PLAN" || label == "CI-ONLY / NOT RUN") "open" else "recorded"
            val extra = if (outcome == "recorded") ""","output":"x"""" else ""
            assertValid(rec(label = label, outcome = outcome, extra = extra))
        }
        assertValid(rec(label = "NOT-APPLICABLE (no windowing on this platform)", outcome = "recorded", extra = ""","note":"n/a","output":"-""""))
    }

    @Test fun openRowIsValid() = assertValid(rec())

    @Test fun ownerDevicePassIsValid() {
        assertValid(rec(outcome = "pass", extra = ownerRun))
        assertValid(rec(label = "NEEDS-OWNER-VALIDATION", outcome = "fail", extra = ownerRun))
    }

    @Test fun abbreviationsAndInventedLabelsAreRejected() {
        assertInvalid(rec(label = "NDV"), "$.label")
        assertInvalid(rec(label = "ci (hosted vm) evidence", outcome = "recorded", extra = ""","output":"x""""), "$.label")
        assertInvalid(rec(label = "VERIFIED"), "$.label")
        assertInvalid(rec(label = "NOT-APPLICABLE ()", outcome = "recorded"), "$.label")
    }

    @Test fun nonDeviceEvidenceCanNeverClosePassOrFail() {
        listOf("CI (hosted VM) evidence", "EMULATOR EVIDENCE", "SIMULATOR", "VIRTUALIZED — NOT DEVICE EVIDENCE", "CONTAINER-BUILD-ONLY")
            .forEach { assertInvalid(rec(label = it, outcome = "pass", extra = ownerRun), "$") }
    }

    @Test fun passNeedsDeviceCommandOutputAndTheOwner() {
        assertInvalid(rec(outcome = "pass"), "missing required 'device'")
        assertInvalid(rec(outcome = "pass", extra = ownerRun.replace("owner", "ci")), "recordedBy")
        assertInvalid(rec(outcome = "pass", extra = ownerRun.replace("\"output\":\"resizeable=true\",", "")), "missing required 'output'")
    }

    @Test fun openRowCannotCarryAnEvidenceLabel() {
        assertInvalid(rec(label = "SIMULATOR", outcome = "open"), "$")
    }

    @Test fun recordedNeedsOutputAndNotApplicableNeedsANote() {
        assertInvalid(rec(label = "SYNTHETIC", outcome = "recorded"), "missing required 'output'")
        assertInvalid(rec(label = "NOT-APPLICABLE (x)", outcome = "recorded", extra = ""","output":"-""""), "missing required 'note'")
    }

    @Test fun structuralErrorsAreReported() {
        assertInvalid(rec(item = "DV-XX-01"), "$.item")
        assertInvalid(rec(item = "DV-AD-1"), "$.item")
        assertInvalid(rec(extra = ""","telemetryEndpoint":"https://example.invalid""""), "unknown property 'telemetryEndpoint'")
        assertInvalid(rec().replace("2026-11-11T10:00:00Z", "yesterday"), "date-time")
        assertInvalid(rec().replace("android-desktop", "amiga"), "$.platform")
        assertInvalid(rec().replace("platform-evidence/1", "platform-evidence/2"), "$.schema")
        assertInvalid("""{"schema":"platform-evidence/1"}""", "missing required 'id'")
        assertInvalid("not json", "not valid JSON")
        assertInvalid("""{"a":1,"a":2}""", "not valid JSON")
    }

    @Test fun bundledSchemaUsesOnlySupportedKeywords() {
        // validate() throws on an unsupported keyword; exercising a full record walks every subschema.
        assertValid(rec(outcome = "pass", extra = ownerRun + ""","artefacts":["draft-release-1"],"commit":"abc1234""""))
    }

    // ---- the checklist templates ----

    private val checklistDir = File("../docs/device-checklists")
    private val platforms = mapOf(
        "UT" to "UT", "LINUX" to "LX", "IOS" to "IOS", "IPADOS" to "IPAD",
        "MACOS" to "MAC", "WINDOWS" to "WIN", "ANDROID_DESKTOP" to "AD",
    )

    @Test fun everyChecklistExistsAndEveryRowStartsOpen() {
        platforms.forEach { (name, prefix) ->
            val file = File(checklistDir, "DEVICE_CHECKLIST_$name.md")
            assertTrue("missing ${file.path}", file.isFile)
            val rows = file.readLines(Charsets.UTF_8).filter { it.startsWith("| DV-") }
            assertTrue("$name has no rows", rows.size >= 15)
            val ids = rows.map { it.split("|")[1].trim() }
            assertEquals("$name: duplicate ids", ids.size, ids.toSet().size)
            ids.forEachIndexed { i, id -> assertEquals("DV-$prefix-%02d".format(i + 1), id) }
            rows.forEach { row ->
                assertTrue("$name: row not NEEDS-DEVICE-VALIDATION: $row", row.trimEnd().endsWith("| NEEDS-DEVICE-VALIDATION |"))
            }
            val text = file.readText(Charsets.UTF_8)
            assertTrue("$name: no 'no device' honesty line", "Nothing here was run on any device" in text)
            // Escaped pipes aside, every row has the five cells of the template.
            rows.forEach { row -> assertEquals(row, 5, row.split(Regex("(?<!\\\\)\\|")).size - 2) }
        }
    }

    @Test fun everyChecklistRowIdIsAcceptedByTheSchemaItemPattern() {
        platforms.forEach { (name, _) ->
            File(checklistDir, "DEVICE_CHECKLIST_$name.md").readLines(Charsets.UTF_8)
                .filter { it.startsWith("| DV-") }
                .map { it.split("|")[1].trim() }
                .forEach { id -> assertValid(rec(item = id)) }
        }
    }

    @Test fun androidDesktopCoversResizeMouseAndKeyboard() {
        val text = File(checklistDir, "DEVICE_CHECKLIST_ANDROID_DESKTOP.md").readText(Charsets.UTF_8)
        listOf("freeform", "Hover states", "Right-click", "Scroll wheel", "Tab order", "shortcuts").forEach {
            assertTrue("missing '$it'", text.contains(it, ignoreCase = true))
        }
    }
}
