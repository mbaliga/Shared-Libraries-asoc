package dev.aarso.crashrecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Golden-file guard for `specs/crash-report-format.md` (PORTING_PLAN.md S0.2): the report format
 * and the PREVIEW safety properties, as vectors in `specs/fixtures/crash-report-format.v1.json`
 * that a non-Kotlin implementation (a QML page, a Swift view) can run against its own code.
 *
 * The fixture is contract, not scratch. A changed expected value is a change to what users see
 * and share, and must be a deliberate, reviewed decision. The only way to rewrite the fixture is
 * the opt-in `SPEC_FIXTURES_REGENERATE=1` (with `--rerun`); that run still FAILS so the new file
 * is reviewed (`git diff specs/fixtures`) and the suite rerun without the switch.
 *
 * Timestamps render in the device's default time zone (that is part of the format), so cases that
 * carry a `timezone` run with it set as the JVM default and restore it afterwards.
 */
class SpecFixturesTest {

    private val name = "crash-report-format.v1.json"

    private fun Map<String, Any?>.s(key: String): String = this[key] as String
    private fun Map<String, Any?>.sOrNull(key: String): String? = this[key] as String?
    private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as Double?)?.toLong()
    private fun Map<String, Any?>.intOrNull(key: String): Int? = (this[key] as Double?)?.toInt()

    private fun root() = SpecJson.load(name)
    private fun cases(root: Any?, section: String): List<MutableMap<String, Any?>> =
        SpecJson.arr(SpecJson.obj(root)[section]).map { SpecJson.obj(it) }

    private fun device(root: Any?, key: String): CrashReport.DeviceInfo {
        val d = SpecJson.obj(SpecJson.obj(SpecJson.obj(root)["devices"])[key])
        return CrashReport.DeviceInfo(
            appVersionName = d.sOrNull("app_version_name"),
            appVersionCode = d.longOrNull("app_version_code"),
            osSdkInt = d.intOrNull("os_sdk_int")!!,
            deviceManufacturer = d.s("device_manufacturer"),
            deviceModel = d.s("device_model"),
            packageName = d.s("package_name"),
            installSource = d.sOrNull("install_source"),
            freeMemMb = d.longOrNull("free_mem_mb"),
            totalMemMb = d.longOrNull("total_mem_mb"),
        )
    }

    /** Runs [body] with [zone] as the JVM default time zone, then restores the previous one. */
    private fun <T> inZone(zone: String, body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try { return body() } finally { TimeZone.setDefault(previous) }
    }

    private fun throwable(kind: String, cause: Throwable? = null): Throwable = when (kind) {
        "OutOfMemoryError" -> OutOfMemoryError()
        "StackOverflowError" -> StackOverflowError()
        "NullPointerException" -> NullPointerException()
        "TimeoutException" -> java.util.concurrent.TimeoutException()
        "IOException" -> java.io.IOException()
        "SecurityException" -> SecurityException()
        "AssertionError" -> AssertionError()
        "IllegalStateException" -> IllegalStateException(cause)
        "RuntimeException" -> RuntimeException(cause)
        else -> throw AssertionError("fixture names an exception kind this test cannot build: $kind")
    }

    /** Mirrors the fixture's `wrappers` list: outermost first, `kind` innermost. */
    private fun build(kind: String, wrappers: List<String>): Throwable {
        var t = throwable(kind)
        for (w in wrappers.reversed()) t = throwable(w, t)
        return t
    }

    private fun checkSize(section: String, size: Int, min: Int) =
        assertTrue("$section: fixture has $size cases, expected at least $min", size >= min)

    // ---- constants ------------------------------------------------------------------------

    @Test
    fun `format constants match the fixture`() {
        val (_, root) = root()
        val c = SpecJson.obj(SpecJson.obj(root)["constants"])
        assertEquals(c["streak_window_ms"], CrashReport.STREAK_WINDOW_MS.toDouble())
        // MAGIC and TRACE_MARKER are private; they are observable in the first line of encode()
        // and as the marker line, which the `report` cases below pin byte for byte.
        val sample = SpecJson.obj(cases(root, "report")[0]["input"])
        val encoded = CrashReport(
            sample.s("app_label"), 1L, "t", "E", null, "p", device(root, "full"), "trace",
        ).encode()
        assertEquals(c["magic"], encoded.lineSequence().first())
        assertTrue(encoded.contains("\n" + c["trace_marker"] + "\n"))
    }

    // ---- headline / plain language / streak -----------------------------------------------

    @Test
    fun `headline matches the fixture`() {
        val (file, root) = root()
        val cases = cases(root, "headline")
        fun actual(c: Map<String, Any?>) = CrashReport(
            "A", 0L, "t", c.s("exc_type"), c.sOrNull("exc_message"), "p", device(root, "minimal"), "",
        ).headline
        if (SpecJson.regenerating()) { cases.forEach { it["expect"] = actual(it) }; SpecJson.saveRegeneratedAndFail(file, root) }
        checkSize("headline", cases.size, 4)
        cases.forEach { assertEquals(it.s("name"), it["expect"], actual(it)) }
    }

    @Test
    fun `plainLanguageFor matches the fixture`() {
        val (file, root) = root()
        val cases = cases(root, "plain_language")
        fun actual(c: Map<String, Any?>): String {
            val wrappers = SpecJson.arr(c["wrappers"]).map { it as String }
            return CrashReport.plainLanguageFor(build(c.s("kind"), wrappers))
        }
        if (SpecJson.regenerating()) { cases.forEach { it["expect"] = actual(it) }; SpecJson.saveRegeneratedAndFail(file, root) }
        checkSize("plain_language", cases.size, 11)
        cases.forEach { assertEquals(it.s("name"), it["expect"], actual(it)) }
    }

    @Test
    fun `nextStreakCount matches the fixture`() {
        val (file, root) = root()
        val cases = cases(root, "streak")
        fun actual(c: Map<String, Any?>) = CrashReport.nextStreakCount(
            prevCount = c.intOrNull("prev_count")!!,
            prevMillis = c.longOrNull("prev_millis")!!,
            nowMillis = c.longOrNull("now_millis")!!,
        ).toDouble()
        if (SpecJson.regenerating()) { cases.forEach { it["expect"] = actual(it) }; SpecJson.saveRegeneratedAndFail(file, root) }
        checkSize("streak", cases.size, 5)
        cases.forEach { assertEquals(it.s("name"), it["expect"] as Double, actual(it), 0.0) }
    }

    // ---- encode / render / decode ---------------------------------------------------------

    private fun reportOf(root: Any?, input: Map<String, Any?>) = CrashReport(
        appLabel = input.s("app_label"),
        whenMillis = input.longOrNull("when_millis")!!,
        threadName = input.s("thread_name"),
        excType = input.s("exc_type"),
        excMessage = input.sOrNull("exc_message"),
        plainLanguage = input.s("plain_language"),
        device = device(root, input.s("device")),
        trace = input.s("trace"),
    )

    private fun decodedMap(d: CrashReport.Decoded): Map<String, Any?> = linkedMapOf(
        "headline" to d.headline,
        "app_label" to d.appLabel,
        "exc_type" to d.excType,
        "exc_message" to d.excMessage,
        "thread_name" to d.threadName,
        "when_millis" to d.whenMillis?.toDouble(),
        "version_name" to d.versionName,
        "version_code" to d.versionCode?.toDouble(),
        "package_name" to d.packageName,
        "install_source" to d.installSource,
        "os_sdk_int" to d.osSdkInt?.toDouble(),
        "device_manufacturer" to d.deviceManufacturer,
        "device_model" to d.deviceModel,
        "free_mem_mb" to d.freeMemMb?.toDouble(),
        "total_mem_mb" to d.totalMemMb?.toDouble(),
    )

    @Test
    fun `encode, render and decode match the fixture`() {
        val (file, root) = root()
        val cases = cases(root, "report")
        checkSize("report", cases.size, 2)
        for (c in cases) {
            val input = SpecJson.obj(c["input"])
            val report = reportOf(root, input)
            inZone(c.s("timezone")) {
                val encoded = report.encode()
                val rendered = report.render()
                val decoded = CrashReport.decode(encoded)
                if (SpecJson.regenerating()) {
                    c["expect"] = linkedMapOf("encoded" to encoded, "rendered" to rendered, "decoded" to decodedMap(decoded))
                } else {
                    val expect = SpecJson.obj(c["expect"])
                    val label = c.s("name")
                    assertEquals("$label: encoded", expect["encoded"], encoded)
                    assertEquals("$label: rendered", expect["rendered"], rendered)
                    assertEquals("$label: decoded", SpecJson.obj(expect["decoded"]), decodedMap(decoded))
                    // Invariants the spec states, independent of the literal text above:
                    assertEquals("$label: shared text is what render() produces", rendered, decoded.fullReport)
                    assertEquals("$label: trace survives the round trip", report.trace, decoded.trace)
                }
            }
        }
        if (SpecJson.regenerating()) SpecJson.saveRegeneratedAndFail(file, root)
    }

    @Test
    fun `exit-death reports match the fixture`() {
        val (file, root) = root()
        val cases = cases(root, "exit_death")
        fun actual(c: Map<String, Any?>): Map<String, Any?> {
            val r = CrashReport.ofExitDeath(
                appLabel = c.s("app_label"),
                reasonLabel = c.s("reason_label"),
                description = c.sOrNull("description"),
                whenMillis = c.longOrNull("when_millis")!!,
                device = device(root, c.s("device")),
            )
            return linkedMapOf(
                "headline" to r.headline,
                "plain_language" to r.plainLanguage,
                "thread_name" to r.threadName,
                "trace" to r.trace,
            )
        }
        if (SpecJson.regenerating()) { cases.forEach { it["expect"] = actual(it) }; SpecJson.saveRegeneratedAndFail(file, root) }
        checkSize("exit_death", cases.size, 4)
        cases.forEach { assertEquals(it.s("name"), SpecJson.obj(it["expect"]), actual(it)) }
    }

    @Test
    fun `decode falls back gracefully as the fixture says`() {
        val (file, root) = root()
        val cases = cases(root, "decode_fallback")
        fun actual(c: Map<String, Any?>): Map<String, Any?> {
            val d = CrashReport.decode(c.s("persisted"))
            return linkedMapOf(
                "app_label" to d.appLabel,
                "headline" to d.headline,
                "plain_language" to d.plainLanguage,
                "exc_type" to d.excType,
                "exc_message" to d.excMessage,
                "when_millis" to d.whenMillis?.toDouble(),
                "full_report" to d.fullReport,
            )
        }
        if (SpecJson.regenerating()) { cases.forEach { it["expect"] = actual(it) }; SpecJson.saveRegeneratedAndFail(file, root) }
        checkSize("decode_fallback", cases.size, 3)
        cases.forEach { assertEquals(it.s("name"), SpecJson.obj(it["expect"]), actual(it)) }
    }

    // ---- the PREVIEW safety properties ----------------------------------------------------

    private val machineChecked = setOf("P1", "P2", "P5")

    @Test
    fun `preview matches the fixture and holds its machine-checkable safety properties`() {
        val (_, root) = root()
        val p = SpecJson.obj(SpecJson.obj(root)["preview"])
        val labels = SpecJson.arr(p["app_labels"]).map { it as String }
        assertTrue("fixture needs at least two labels", labels.size >= 2)
        val markers = SpecJson.obj(p["required_markers"])
        val nullFields = SpecJson.arr(p["null_fields"]).map { it as String }
        assertEquals("all ten metadata fields are listed", 10, nullFields.size)

        for (label in labels) {
            val d = CrashReport.samplePreview(label)
            // Literal content
            assertEquals(p["headline"], d.headline)
            assertEquals(p["plain_language"], d.plainLanguage)
            assertEquals(p["exc_type"], d.excType)
            assertEquals(p["exc_message"], d.excMessage)
            assertEquals(p["thread_name"], d.threadName)
            assertEquals(label, d.appLabel)
            val trace = (p["trace_template"] as String).replace("{app_label}", label)
            assertEquals("trace for $label", trace, d.trace)
            val full = (p["full_report_template"] as String).replace("{app_label}", label).replace("{trace}", trace)
            assertEquals("full report for $label", full, d.fullReport)

            // P1: PREVIEW is unmistakable in every user-visible field.
            fun need(field: String, text: String) =
                SpecJson.arr(markers[field]).forEach { m -> assertTrue("P1: $field must contain '$m'", text.contains(m as String)) }
            need("headline", d.headline); need("plain_language", d.plainLanguage); need("full_report", d.fullReport)

            // P2: no real device or app metadata.
            val values = mapOf(
                "when_millis" to d.whenMillis, "version_name" to d.versionName, "version_code" to d.versionCode,
                "package_name" to d.packageName, "install_source" to d.installSource, "os_sdk_int" to d.osSdkInt,
                "device_manufacturer" to d.deviceManufacturer, "device_model" to d.deviceModel,
                "free_mem_mb" to d.freeMemMb, "total_mem_mb" to d.totalMemMb,
            )
            assertEquals("every metadata field is covered", values.keys, nullFields.toSet())
            values.forEach { (k, v) -> assertNull("P2: $k must be null in a preview", v) }

            // P5 (purity half): same label, identical value; nothing is read from anywhere else.
            assertEquals("P5: deterministic for $label", d, CrashReport.samplePreview(label))
        }
    }

    @Test
    fun `the fixture lists exactly the five preview properties and says which are machine-checked`() {
        val (_, root) = root()
        val props = SpecJson.arr(SpecJson.obj(SpecJson.obj(root)["preview"])["properties"]).map { SpecJson.obj(it) }
        assertEquals(listOf("P1", "P2", "P3", "P4", "P5"), props.map { it["id"] })
        assertEquals(
            "the properties this suite checks are exactly the ones the fixture marks machine_checked",
            machineChecked,
            props.filter { it["machine_checked"] == true }.map { it["id"] as String }.toSet(),
        )
        props.filter { it["machine_checked"] == false }.forEach {
            assertTrue("${it["id"]}: an unchecked property must say what evidence it needs",
                (it["evidence"] as String).contains("NEEDS-DEVICE-VALIDATION"))
        }
    }

    @Test
    fun `the fixture identifies itself`() {
        val (_, root) = root()
        val m = SpecJson.obj(root)
        assertEquals("crash-report-format", m["spec"])
        assertEquals(1.0, m["version"] as Double, 0.0)
    }
}
