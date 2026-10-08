package dev.aarso.crashrecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    private val device = CrashReport.DeviceInfo(
        appVersionName = "1.2.3",
        appVersionCode = 42L,
        osSdkInt = 34,
        deviceManufacturer = "Nubia",
        deviceModel = "RedMagic 11 Pro",
        packageName = "com.asystemofcells.app",
        installSource = "Play Store",
        freeMemMb = 212L,
        totalMemMb = 12288L,
    )

    @Test
    fun `headline includes exception type and message`() {
        val headline = CrashReport.headlineOf(IllegalStateException("container not ready"))
        assertEquals("IllegalStateException: container not ready", headline)
    }

    @Test
    fun `headline omits message when blank`() {
        val headline = CrashReport.headlineOf(RuntimeException())
        assertEquals("RuntimeException", headline)
    }

    @Test
    fun `plain-language maps known failure kinds and falls back generically`() {
        assertTrue(CrashReport.plainLanguageFor(OutOfMemoryError()).contains("memory"))
        assertTrue(CrashReport.plainLanguageFor(StackOverflowError()).contains("repeating"))
        assertTrue(CrashReport.plainLanguageFor(NullPointerException()).contains("wasn't"))
        assertEquals(
            "The app ran into an unexpected error and had to close.",
            CrashReport.plainLanguageFor(IllegalStateException("boom")),
        )
    }

    @Test
    fun `render includes summary, structured fields, headline, and trace`() {
        val report = CrashReport.of(
            appLabel = "Runout",
            whenMillis = 0L,
            threadName = "main",
            throwable = IllegalStateException("boom"),
            device = device,
        )
        val rendered = report.render()

        assertTrue(rendered.startsWith("=== Crash report — Runout ==="))
        assertTrue(rendered.contains("What happened: "))
        assertTrue(rendered.contains("Error:   IllegalStateException"))
        assertTrue(rendered.contains("Message: boom"))
        assertTrue(rendered.contains("Thread:  main"))
        assertTrue(rendered.contains("App:     Runout 1.2.3 (42)"))
        assertTrue(rendered.contains("Package: com.asystemofcells.app"))
        assertTrue(rendered.contains("Source:  Play Store"))
        assertTrue(rendered.contains("Nubia RedMagic 11 Pro, Android SDK 34"))
        assertTrue(rendered.contains("Memory:  212 MB free of 12288 MB at crash"))
        assertTrue(rendered.contains("at dev.aarso.crashrecovery.CrashReportTest"))
    }

    @Test
    fun `render shows (none) when the exception has no message`() {
        val report = CrashReport.of(
            appLabel = "Runout",
            whenMillis = 0L,
            threadName = "main",
            throwable = RuntimeException(),
            device = device,
        )
        assertTrue(report.render().contains("Message: (none)"))
    }

    @Test
    fun `encode then decode round-trips every structured field`() {
        val report = CrashReport.of(
            appLabel = "Clackpad",
            whenMillis = 1_700_000_000_000L,
            threadName = "GLThread 482",
            throwable = NullPointerException("keymap missing"),
            device = device,
        )

        val decoded = CrashReport.decode(report.encode())

        assertEquals("NullPointerException: keymap missing", decoded.headline)
        assertEquals("Clackpad", decoded.appLabel)
        assertEquals("NullPointerException", decoded.excType)
        assertEquals("keymap missing", decoded.excMessage)
        assertEquals("GLThread 482", decoded.threadName)
        assertEquals(1_700_000_000_000L, decoded.whenMillis)
        assertEquals("1.2.3", decoded.versionName)
        assertEquals(42L, decoded.versionCode)
        assertEquals("com.asystemofcells.app", decoded.packageName)
        assertEquals("Play Store", decoded.installSource)
        assertEquals(34, decoded.osSdkInt)
        assertEquals("RedMagic 11 Pro", decoded.deviceModel)
        assertEquals(212L, decoded.freeMemMb)
        assertEquals(12288L, decoded.totalMemMb)
        assertTrue(decoded.trace.contains("at dev.aarso.crashrecovery.CrashReportTest"))
        // The shareable text the UI shows equals what render() produces.
        assertEquals(report.render(), decoded.fullReport)
    }

    @Test
    fun `decode is best-effort on a foreign or malformed string`() {
        val decoded = CrashReport.decode("just some text with no separator")

        assertEquals("just some text with no separator", decoded.headline)
        assertEquals("just some text with no separator", decoded.fullReport)
        assertNull(decoded.whenMillis)
    }

    @Test
    fun `decode handles an empty string without throwing`() {
        val decoded = CrashReport.decode("")

        assertEquals("", decoded.headline)
        assertEquals("", decoded.fullReport)
    }

    @Test
    fun `consecutive crashes inside the window accumulate but a distant one resets`() {
        val w = CrashReport.STREAK_WINDOW_MS
        // first crash ever (no prior)
        assertEquals(1, CrashReport.nextStreakCount(prevCount = 0, prevMillis = 0, nowMillis = 1_000))
        // within the window (inclusive boundary) -> continuation
        assertEquals(2, CrashReport.nextStreakCount(prevCount = 1, prevMillis = 1_000, nowMillis = 1_000 + w))
        // a rapid third repeat
        assertEquals(3, CrashReport.nextStreakCount(prevCount = 2, prevMillis = 5_000, nowMillis = 5_010))
        // just outside the window -> a fresh incident
        assertEquals(1, CrashReport.nextStreakCount(prevCount = 5, prevMillis = 1_000, nowMillis = 1_000 + w + 1))
        // clock went backwards -> never treated as a continuation
        assertEquals(1, CrashReport.nextStreakCount(prevCount = 3, prevMillis = 10_000, nowMillis = 5_000))
    }

    @Test
    fun `decode reads a legacy headline-blankline-body file`() {
        val legacy = "IllegalStateException: old\n\nIllegalStateException: old\nsome older body text"
        val decoded = CrashReport.decode(legacy)
        assertEquals("IllegalStateException: old", decoded.headline)
        assertTrue(decoded.fullReport.contains("some older body text"))
    }

    // ---- samplePreview (CrashRecovery.previewIntent) -------------------------------------
    // Merged forward from hyle-design-system@33b0faa, a branch that never reached that repo's
    // main but which Android-IDE-core pins and calls from SettingsRoom.kt. Rebuilt against the
    // richer Decoded that main introduced later; these pin the safety properties that matter.

    @Test
    fun `sample preview is unmistakably marked as a preview in every user-visible field`() {
        val preview = CrashReport.samplePreview("Aarso")
        assertTrue("headline must say PREVIEW", preview.headline.contains("PREVIEW"))
        assertTrue("summary must say PREVIEW", preview.plainLanguage.contains("PREVIEW"))
        assertTrue("shared text must say PREVIEW", preview.fullReport.contains("PREVIEW"))
        // fullReport is what actually leaves the device via Share; a recipient must not be
        // able to mistake it for a real crash report.
        assertTrue(preview.fullReport.contains("No crash occurred"))
    }

    @Test
    fun `sample preview carries no real device or app metadata`() {
        val preview = CrashReport.samplePreview("Fylz")
        assertNull(preview.whenMillis)
        assertNull(preview.versionName)
        assertNull(preview.versionCode)
        assertNull(preview.packageName)
        assertNull(preview.installSource)
        assertNull(preview.osSdkInt)
        assertNull(preview.deviceManufacturer)
        assertNull(preview.deviceModel)
        assertNull(preview.freeMemMb)
        assertNull(preview.totalMemMb)
    }

    @Test
    fun `sample preview uses the caller's app label and degrades gracefully with no timestamp`() {
        val preview = CrashReport.samplePreview("Foto Xplorr")
        assertEquals("Foto Xplorr", preview.appLabel)
        assertTrue(preview.fullReport.contains("Foto Xplorr"))
        assertTrue(preview.trace.contains("java.lang.IllegalStateException"))
        assertTrue(preview.trace.contains("previewIntent"))
        // The UI calls these unconditionally — with no timestamp they must not throw.
        assertEquals("unknown", preview.whenText())
        assertEquals("", preview.whenShort())
        assertNull(preview.versionLabel())
    }

    // ---- ofExitDeath (ApplicationExitInfo-backed deaths: native crash / ANR) --------------

    @Test
    fun `exit-death report is honest about having no Java stack`() {
        val report = CrashReport.ofExitDeath(
            appLabel = "Foto Xplorr",
            reasonLabel = "Native crash",
            description = "SIGSEGV",
            whenMillis = 1_722_800_000_000,
            device = device,
        )
        assertTrue(report.trace.contains("No Java stack trace exists"))
        assertTrue(report.trace.contains("SIGSEGV"))
        assertEquals("Native crash: SIGSEGV", report.headline)
        assertTrue(report.plainLanguage.contains("native"))
    }

    @Test
    fun `exit-death report round-trips through encode-decode like any other report`() {
        val report = CrashReport.ofExitDeath(
            appLabel = "Fylz",
            reasonLabel = "App not responding (ANR)",
            description = "Input dispatching timed out",
            whenMillis = 1_722_800_000_000,
            device = device,
        )
        val decoded = CrashReport.decode(report.encode())
        assertEquals("App not responding (ANR)", decoded.excType)
        assertEquals("Input dispatching timed out", decoded.excMessage)
        assertTrue(decoded.fullReport.contains("ApplicationExitInfo"))
        assertEquals(1_722_800_000_000, decoded.whenMillis)
    }

    @Test
    fun `exit-death report degrades gracefully when the OS recorded no description`() {
        val report = CrashReport.ofExitDeath(
            appLabel = "App",
            reasonLabel = "Crash",
            description = null,
            whenMillis = 1_722_800_000_000,
            device = device,
        )
        assertEquals("Crash", report.headline)
        assertTrue(report.trace.contains("(none recorded)"))
    }

    // ---- nextAttemptCount / attemptCeilingExceeded / identityOf -------------------------
    // The pure loop-breaking rule behind maybeShowRecovery()'s hard N-attempt ceiling: the
    // SAME pending report must not survive more than CrashReport.MAX_RECOVERY_ATTEMPTS launches
    // unresolved, independent of the (much tighter) STREAK_WINDOW_MS used for the Reset button.

    @Test
    fun `attempt count restarts at 1 for a brand-new identity`() {
        assertEquals(1, CrashReport.nextAttemptCount(prevIdentity = null, prevCount = 0, identity = "a"))
        assertEquals(1, CrashReport.nextAttemptCount(prevIdentity = "a", prevCount = 5, identity = "b"))
    }

    @Test
    fun `attempt count accumulates across launches while the identity is unchanged`() {
        assertEquals(2, CrashReport.nextAttemptCount(prevIdentity = "a", prevCount = 1, identity = "a"))
        assertEquals(3, CrashReport.nextAttemptCount(prevIdentity = "a", prevCount = 2, identity = "a"))
        assertEquals(4, CrashReport.nextAttemptCount(prevIdentity = "a", prevCount = 3, identity = "a"))
    }

    @Test
    fun `attempt ceiling is not exceeded within the default budget but is past it`() {
        assertFalse(CrashReport.attemptCeilingExceeded(1))
        assertFalse(CrashReport.attemptCeilingExceeded(CrashReport.MAX_RECOVERY_ATTEMPTS))
        assertTrue(CrashReport.attemptCeilingExceeded(CrashReport.MAX_RECOVERY_ATTEMPTS + 1))
    }

    @Test
    fun `attempt ceiling honours a custom maxAttempts`() {
        assertFalse(CrashReport.attemptCeilingExceeded(attemptCount = 2, maxAttempts = 2))
        assertTrue(CrashReport.attemptCeilingExceeded(attemptCount = 3, maxAttempts = 2))
    }

    @Test
    fun `a simulated maybeShowRecovery loop shows recovery up to the ceiling, then stops`() {
        // The same report (one identity) hits maybeShowRecovery() four times in a row without
        // ever being resolved -- the real app itself is what's persistently broken.
        val identity = CrashReport.identityOf(whenMillis = 1_000L, rawText = "trace")
        var prevIdentity: String? = null
        var prevCount = 0
        val wouldShowRecovery = (1..4).map {
            val next = CrashReport.nextAttemptCount(prevIdentity, prevCount, identity)
            prevIdentity = identity
            prevCount = next
            !CrashReport.attemptCeilingExceeded(next)
        }
        assertEquals(listOf(true, true, true, false), wouldShowRecovery)
    }

    @Test
    fun `identity prefers whenMillis over the report text`() {
        // Two reports captured at the exact same instant are the same crash as far as the
        // ceiling cares, even if (hypothetically) their text differed downstream.
        val idA = CrashReport.identityOf(whenMillis = 1_000L, rawText = "one report")
        val idB = CrashReport.identityOf(whenMillis = 1_000L, rawText = "a totally different report")
        assertEquals(idA, idB)
    }

    @Test
    fun `identity falls back to hashing the report text when whenMillis is unavailable`() {
        val idA = CrashReport.identityOf(whenMillis = null, rawText = "report A")
        val idB = CrashReport.identityOf(whenMillis = null, rawText = "report B")
        assertNotEquals(idA, idB)
        assertEquals(idA, CrashReport.identityOf(whenMillis = null, rawText = "report A"))
    }
}
