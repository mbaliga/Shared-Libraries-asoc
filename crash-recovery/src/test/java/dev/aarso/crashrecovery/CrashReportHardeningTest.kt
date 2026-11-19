package dev.aarso.crashrecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportHardeningTest {

    private val device = CrashReport.DeviceInfo(null, null, 34, "M", "D")

    @Test
    fun `a trace past the cap keeps its head and its tail and says what was dropped`() {
        val trace = "HEAD\n" + "\tat a.B.c(B.kt:1)\n".repeat(20_000) + "Caused by: TAIL"
        val capped = CrashReport.capTrace(trace)
        assertTrue(capped.length < CrashReport.MAX_TRACE_CHARS + 100)
        assertTrue(capped.startsWith("HEAD"))
        assertTrue("the Caused by chain lives at the tail", capped.endsWith("Caused by: TAIL"))
        assertTrue(capped.contains("characters omitted"))
    }

    @Test
    fun `a trace under the cap is untouched`() {
        assertEquals("short", CrashReport.capTrace("short"))
    }

    @Test
    fun `a StackOverflowError report is bounded`() {
        fun recurse(n: Int): Int = recurse(n + 1) + 1
        val so = try { recurse(0); null } catch (e: StackOverflowError) { e }!!
        val r = CrashReport.of("A", 1L, "main", so, device)
        assertTrue(r.trace.length <= CrashReport.MAX_TRACE_CHARS + 100)
        assertTrue(r.encode().length < CrashReport.MAX_TRACE_CHARS + 2_000)
    }

    @Test
    fun `an enormous message is capped`() {
        val r = CrashReport.of("A", 1L, "main", IllegalStateException("x".repeat(100_000)), device)
        assertEquals(CrashReport.MAX_MESSAGE_CHARS, r.excMessage!!.length)
    }

    @Test
    fun `the sanitizer sees the message and the trace and its output is what is stored`() {
        val e = IllegalStateException("token=sk-SECRET")
        val r = CrashReport.of("A", 1L, "main", e, device, sanitizer = { it.replace("sk-SECRET", "[x]") })
        assertFalse(r.excMessage!!.contains("SECRET"))
        assertFalse(r.trace.contains("SECRET"))
        assertFalse(r.encode().contains("SECRET"))
        assertFalse(r.render().contains("SECRET"))
    }

    @Test
    fun `a sanitizer that throws loses the text, not the report and not the secret`() {
        val e = IllegalStateException("token=sk-SECRET")
        val r = CrashReport.of("A", 1L, "main", e, device, sanitizer = { error("boom") })
        assertEquals("[redaction failed]", r.excMessage)
        assertFalse(r.encode().contains("SECRET"))
    }

    @Test
    fun `an exit-death description goes through the sanitizer too`() {
        val r = CrashReport.ofExitDeath("A", "App not responding (ANR)", "Input dispatching timed out (hunter2)", 1L, device,
            sanitizer = { it.replace("hunter2", "[x]") })
        assertFalse(r.render().contains("hunter2"))
    }
}
