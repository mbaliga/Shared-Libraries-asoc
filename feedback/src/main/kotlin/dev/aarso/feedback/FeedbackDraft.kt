package dev.aarso.feedback

/**
 * One piece of context a feedback draft carries, as a labelled line the user reads before
 * sending. Every fact is EXPLICIT: the caller constructs each one, the prompt UI shows all of
 * them, and [FeedbackDraft.render] emits exactly these and nothing else. There is no device
 * fingerprint, no identifier, no collected-behind-the-scenes anything — a fact the user was
 * not shown cannot exist, by construction.
 */
data class FeedbackFact(val label: String, val value: String) {
    init {
        require(label.isNotBlank()) { "A fact needs a label." }
    }
}

/**
 * A feedback draft for one experimental or new feature.
 *
 * @param appName the app as the user knows it ("Fylz"), never a package id.
 * @param appVersion the user-visible version string.
 * @param feature the feature this feedback is about, as shown in the prompt ("Deep previews").
 * @param message the user's own words, verbatim.
 * @param facts optional labelled context, each explicitly supplied and shown before sending.
 */
data class FeedbackDraft(
    val appName: String,
    val appVersion: String,
    val feature: String,
    val message: String,
    val facts: List<FeedbackFact> = emptyList(),
) {
    init {
        require(appName.isNotBlank()) { "The app name is required." }
        require(feature.isNotBlank()) { "The feature name is required." }
    }

    /**
     * The complete, final payload as plain text — what the share sheet receives IS what this
     * returns, and what this returns is only what the fields above hold. Deterministic: same
     * draft, same text, no clocks or randomness (a timestamp is the mail client's business,
     * and the user's).
     */
    fun render(): String = buildString {
        appendLine("$appName $appVersion — feedback on: $feature")
        appendLine()
        appendLine(message.trim())
        if (facts.isNotEmpty()) {
            appendLine()
            appendLine("Included details:")
            facts.forEach { fact -> appendLine("- ${fact.label}: ${fact.value}") }
        }
    }.trimEnd()

    /** A subject line for mail-shaped deliveries. */
    fun subject(): String = "$appName feedback: $feature"
}
