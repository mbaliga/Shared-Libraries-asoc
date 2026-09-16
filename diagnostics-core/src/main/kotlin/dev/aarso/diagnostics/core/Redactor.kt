package dev.aarso.diagnostics.core

/**
 * Runs over every free-text string that reaches the file. A log line can contain anything the app
 * put in it, and this report is meant to be shareable — so secrets are removed before the file
 * exists, not before it is shared.
 *
 * Placeholders are TYPED on purpose: a reader needs to know a value was present and withheld, not
 * that it was absent. `<redacted:token>` and "no token here" are very different facts.
 *
 * Runs at export rather than at capture, so the in-memory ring stays cheap.
 *
 * Note the division of labour with [PayloadRule]: this removes things that should never appear;
 * payload rules ASSERT that certain things did not appear, and fail the report if they did. The
 * first is a filter, the second is a check. Signal data from the EEG work gets both.
 */
open class Redactor(private val rules: List<Rule> = defaultRules()) {

    data class Rule(val name: String, val regex: Regex)

    open fun redact(s: String): String {
        var out = s
        for (r in rules) out = r.regex.replace(out, "<redacted:${r.name}>")
        return out
    }

    fun redact(e: LogEntry): LogEntry = e.copy(message = redact(e.message), tag = redact(e.tag))

    companion object {
        fun default() = Redactor()

        fun defaultRules(): List<Rule> = listOf(
            // Order matters: most specific shapes first, so a JWT is not eaten by the generic
            // long-token rule and mislabelled.
            Rule("jwt", Regex("""\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\b""")),
            Rule("bearer", Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{12,}""")),
            Rule("api-key", Regex("""\b(?:sk|pk|rk|api|key)[-_][A-Za-z0-9]{16,}\b""")),
            Rule("google-key", Regex("""\bAIza[0-9A-Za-z_\-]{30,}\b""")),
            Rule("aws-key", Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""")),
            Rule("email", Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")),
            Rule("assignment", Regex("""(?i)\b(?:password|passwd|secret|token|api[_-]?key)\s*[=:]\s*\S+""")),
            // "data" (the literal /data/data/<pkg>/... app-private-storage alias -- shows up
            // constantly in Room/SQLite "unable to open database file" errors and crash traces)
            // sits alongside emulated/N and user/N as a third valid segment after /data/, not
            // covered by the original two-branch version of this rule.
            Rule("user-path", Regex("""/(?:data/(?:emulated/\d+|user/\d+|data)|storage/(?:emulated/\d+|user/\d+))/[A-Za-z0-9._\-]+/[^\s"']*""")),
            Rule("ipv4", Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")),
        )
    }
}
