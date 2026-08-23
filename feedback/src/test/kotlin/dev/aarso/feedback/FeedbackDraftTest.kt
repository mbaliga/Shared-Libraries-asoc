package dev.aarso.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDraftTest {

    private fun draft(facts: List<FeedbackFact> = emptyList()) = FeedbackDraft(
        appName = "Fylz",
        appVersion = "1.0.0-alpha01",
        feature = "Deep previews",
        message = "The spreadsheet grid drops the header row on tall files.",
        facts = facts,
    )

    @Test
    fun `render carries exactly the given fields and nothing else`() {
        val rendered = draft(
            facts = listOf(FeedbackFact("Android version", "16"), FeedbackFact("Device", "Pixel 9")),
        ).render()

        assertTrue(rendered.contains("Fylz 1.0.0-alpha01 — feedback on: Deep previews"))
        assertTrue(rendered.contains("The spreadsheet grid drops the header row on tall files."))
        assertTrue(rendered.contains("- Android version: 16"))
        assertTrue(rendered.contains("- Device: Pixel 9"))
        // The whole payload is the sum of its parts: strip every provided fragment and only
        // the fixed section labels and list punctuation may remain. This is the no-hidden-data
        // promise in executable form.
        val residue = rendered
            .replace("Fylz", "")
            .replace("1.0.0-alpha01", "")
            .replace("Deep previews", "")
            .replace("The spreadsheet grid drops the header row on tall files.", "")
            .replace("Android version", "")
            .replace("16", "")
            .replace("Device", "")
            .replace("Pixel 9", "")
        val allowed = setOf("—", "feedback", "on:", "Included", "details:", "-", ":")
        residue.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
            assertTrue("Unexpected content in render: '$token'", token in allowed)
        }
    }

    @Test
    fun `render is deterministic`() {
        assertEquals(draft().render(), draft().render())
    }

    @Test
    fun `no facts means no details section`() {
        assertFalse(draft().render().contains("Included details"))
    }

    @Test
    fun `message whitespace is trimmed but content preserved verbatim`() {
        val rendered = FeedbackDraft(
            appName = "Fylz",
            appVersion = "1",
            feature = "F",
            message = "  keep [this] exactly & <as-is>  \n",
        ).render()
        assertTrue(rendered.contains("keep [this] exactly & <as-is>"))
    }

    @Test
    fun `subject names the app and feature`() {
        assertEquals("Fylz feedback: Deep previews", draft().subject())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a fact without a label is refused`() {
        FeedbackFact(label = " ", value = "x")
    }
}
