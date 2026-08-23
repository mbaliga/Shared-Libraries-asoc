package dev.aarso.feedback

import android.content.Intent

/**
 * Builds the chooser through which the USER delivers a draft — mail, messenger, notes,
 * anything on their device that accepts text, or nothing if they back out. This module never
 * sends: it has no network permission to send with, which is the point.
 */
object FeedbackShare {

    /**
     * A plain-text share chooser carrying [FeedbackDraft.render] as the body and
     * [FeedbackDraft.subject] as the subject for mail-shaped targets. The caller starts it
     * like any activity intent; the system chooser makes the delivery channel the user's
     * explicit pick, every time — no default channel is remembered anywhere.
     */
    fun chooser(draft: FeedbackDraft, title: String = "Send feedback with…"): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, draft.subject())
            putExtra(Intent.EXTRA_TEXT, draft.render())
        }
        return Intent.createChooser(send, title)
    }
}
