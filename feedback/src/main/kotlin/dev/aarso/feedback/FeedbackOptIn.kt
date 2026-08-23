package dev.aarso.feedback

import android.content.Context

/**
 * The opt-in ledger. Feedback prompts are OFF until the user turns them on, and a feature's
 * prompt appears at most once unless the user re-invites it — an experimental feature earns
 * one polite ask, never a nag loop.
 *
 * Stores only booleans keyed by feature id, in the app's own private preferences. Nothing here
 * is feedback content and nothing ever leaves the device.
 */
class FeedbackOptIn(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Whether the user has opted into feedback prompts at all. Default false. */
    fun enabled(): Boolean = preferences.getBoolean(ENABLED, false)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(ENABLED, value).apply()
    }

    /**
     * Whether the prompt for [featureId] should show now: opted in, and this feature has not
     * already asked. Callers show the prompt exactly when this is true and then record
     * [markAsked] whatever the user chose — dismissal is an answer.
     */
    fun shouldPrompt(featureId: String): Boolean =
        enabled() && !preferences.getBoolean(askedKey(featureId), false)

    fun markAsked(featureId: String) {
        preferences.edit().putBoolean(askedKey(featureId), true).apply()
    }

    /** Re-invites one feature's prompt (a "give feedback again" affordance in settings). */
    fun resetAsked(featureId: String) {
        preferences.edit().remove(askedKey(featureId)).apply()
    }

    private fun askedKey(featureId: String) = "$ASKED_PREFIX$featureId"

    private companion object {
        const val PREFERENCES_NAME = "aarso_feedback_optin"
        const val ENABLED = "enabled"
        const val ASKED_PREFIX = "asked:"
    }
}
