package dev.aarso.search

import java.time.ZoneId

/**
 * Turns free English into the structured query grammar [QueryParser] already understands, then
 * hands the result to [QueryParser.parse].
 *
 * ## Why a pre-pass, not a second parser
 *
 * "images from last week" and `type:image modified:last-week` should end up as the *same*
 * [ParsedQuery] — same chips, same diagnostics, same round trip through [QueryChip.text]. Writing
 * a second AST for natural language would mean two chip renderers, two diagnostic vocabularies and
 * two evaluators that must agree forever. Instead [interpret] rewrites the free-text run into
 * canonical `key:value` syntax and delegates; every downstream consumer — [ChipBuilder],
 * [FacetEvaluator], [Matcher] — sees an ordinary [ParsedQuery] and does not know natural language
 * was ever involved.
 *
 * ## What is recognized, and the two fields this module assumes
 *
 * A kind noun ("photos", "pdfs") becomes `type:<value>` via the host's [NaturalVocabulary]; a date
 * phrase ("today", "last month", "since october", "2024") becomes a `modified:` facet; a size word
 * or comparison ("large", "over 10mb") becomes a `size:` facet. The field *keys* `type` and
 * `modified` are hardcoded here rather than supplied — unlike [FieldRegistry], which is
 * deliberately app-agnostic, this module's whole job is producing text a *specific kind* of host
 * (one with a file/kind facet and a modified-date facet) can use. A host that names those fields
 * differently gets [Diagnostic.UnknownField] on every date and kind phrase it types, same as any
 * other unrecognized key — degraded, never thrown.
 *
 * `size:` values and comparators are emitted as bare text (`size:>25mb`) with no unit validation
 * here; unit parsing is [FacetField.matches]'s job, same as it is for a user who typed `size:>25mb`
 * directly.
 *
 * ## Structured input is never touched
 *
 * A `key:value` facet, a `"quoted phrase"`, and `AND`/`OR`/`NOT`/`|` are recognized at the token
 * level and passed through byte-for-byte — this module only rewrites a *run of bare words* between
 * (or around) them. So `ext:pdf photos from last week` interprets the natural-language half and
 * leaves `ext:pdf` exactly as typed, and a query built entirely of structured syntax is handed to
 * [QueryParser.parse] with its original text unchanged, not even re-joined with different spacing —
 * see [rewrite]'s early-return. That matters for a search box: rewriting text the user did not ask
 * to have rewritten would move their cursor out from under them for no reason.
 *
 * ## What "recognized" means operationally
 *
 * [interpret] never fails to produce a query. A word run that matches nothing recognized passes
 * through as plain [QueryNode.Term]s — "an unknown verb stays a term" is not a special case, it is
 * every word this file does not have an opinion about. The filler words ("from", "in", "of", "the",
 * "my", "files", "show", "me", "find") are the one exception with a rule of their own: they are
 * dropped only when they sit immediately next to a fragment this file *did* recognize ("show me
 * pdfs" drops "show" and "me"; "show" alone, matching nothing beside it, stays a term) — a filler
 * word is noise around a real filter, not noise on its own.
 */
object NaturalQuery {

    /**
     * @param registry passed straight through to [QueryParser.parse] — see that function's KDoc
     *   for why it has no default. The rewritten text is parsed against the same vocabulary the
     *   caller would have used for a hand-typed structured query, so a host whose registry lacks
     *   `type`/`modified` degrades exactly as described in the class KDoc rather than being told
     *   twice.
     * @param ctx the clock a relative date phrase ("today", "since january") resolves against.
     * @param zone the zone "today"/"this month" are questions about.
     */
    fun interpret(
        rawText: String,
        vocabulary: NaturalVocabulary,
        registry: FieldRegistry<*>,
        ctx: EvalContext,
        zone: ZoneId,
    ): ParsedQuery = QueryParser.parse(rewrite(rawText, vocabulary), registry, ctx, zone)

    // ============================== rewrite ==============================

    /**
     * Rewrites [rawText] into canonical query syntax, or returns it verbatim when nothing in it
     * needed rewriting.
     *
     * The verbatim return is deliberate, not an optimization: [splitPreservingQuotesAndGroups]
     * followed by a rejoin would collapse runs of whitespace even when every token passed through
     * unchanged, which is an invisible edit to a query the user typed exactly as they meant it.
     * [rewriteRun] reports whether it changed anything; only then is the token list rejoined.
     */
    private fun rewrite(rawText: String, vocabulary: NaturalVocabulary): String {
        val tokens = splitPreservingQuotesAndGroups(rawText)
        if (tokens.isEmpty()) return rawText

        var changed = false
        val out = mutableListOf<String>()
        var run = mutableListOf<String>()

        fun flushRun() {
            if (run.isEmpty()) return
            val (rewritten, runChanged) = rewriteRun(run, vocabulary)
            if (runChanged) changed = true
            out.addAll(rewritten)
            run = mutableListOf()
        }

        for (token in tokens) {
            if (isStructuredToken(token)) {
                flushRun()
                out.add(token)
            } else {
                run.add(token)
            }
        }
        flushRun()

        return if (changed) out.joinToString(" ") else rawText
    }

    /**
     * One contiguous run of bare words (no facets, quotes or boolean keywords among them) →
     * canonical tokens plus whether anything in the run actually changed.
     *
     * A greedy left-to-right scan: at each position, [tryMatchFragment] either consumes one or more
     * words into a single canonical facet, or declines and the word passes through unchanged. There
     * is no backtracking — "large" is committed to `size:>25mb` the moment it is seen, never
     * reconsidered because a later word would have made a different reading more sensible. That is
     * the same trade [QueryParser]'s own recursive-descent grammar makes, and for the same reason:
     * predictability. A user watching chips update as they type needs "what did it just do with
     * that word" to have one answer, not one that depends on what they type next.
     */
    private fun rewriteRun(words: List<String>, vocabulary: NaturalVocabulary): Pair<List<String>, Boolean> {
        val out = mutableListOf<String>()
        var changed = false
        var i = 0
        while (i < words.size) {
            val fragment = tryMatchFragment(words, i, vocabulary)
            if (fragment == null) {
                out.add(words[i])
                i++
                continue
            }
            // A filler immediately before a fragment that turned out real ("show" in "show pdfs")
            // is noise the fragment explains away — strip every trailing one, not just the last,
            // so "show me pdfs" loses both.
            while (out.isNotEmpty() && isFiller(out.last())) {
                out.removeAt(out.lastIndex)
                changed = true
            }
            out.add(fragment.canonical)
            changed = true
            i = fragment.nextIndex
            while (i < words.size && isFiller(words[i])) {
                i++
                changed = true
            }
        }
        return out to changed
    }

    private val FILLER_WORDS = setOf("from", "in", "of", "the", "my", "files", "show", "me", "find")
    private fun isFiller(word: String): Boolean = word.lowercase() in FILLER_WORDS

    /** One recognized fragment: the canonical text it becomes, and the index of the first word
     *  after it — which may be more than one past [Fragment]'s start, e.g. "last week" or
     *  "bigger than 10mb" each consume more than one input word for one output facet. */
    private class Fragment(val canonical: String, val nextIndex: Int)

    /**
     * Tries every recognized shape at [words]\[[i]\], in order from most specific to most general.
     * Order matters only where two shapes could both start at the same word — a bare month name and
     * a `since <month>` phrase never compete for the same starting word, but checking the two-word
     * date phrases ("this week") before the single-word ones ("today") means a stray "week" right
     * after "this" is never independently reconsidered as something else.
     */
    private fun tryMatchFragment(words: List<String>, i: Int, vocabulary: NaturalVocabulary): Fragment? =
        matchRelativeDatePhrase(words, i)
            ?: matchNamedDay(words, i)
            ?: matchDatePreposition(words, i)
            ?: matchBareDate(words, i)
            ?: matchSizeComparison(words, i)
            ?: matchSizeWord(words, i)
            ?: matchKind(words, i, vocabulary)

    // ---- date ----

    private val RELATIVE_DATE_UNITS = setOf("week", "month", "year")

    /** "this week" / "last month" / "last year" — see [RelativeDate]'s `this-*`/`last-*` tokens. */
    private fun matchRelativeDatePhrase(words: List<String>, i: Int): Fragment? {
        val w0 = words[i].lowercase()
        if (w0 != "this" && w0 != "last") return null
        val w1 = words.getOrNull(i + 1)?.lowercase() ?: return null
        if (w1 !in RELATIVE_DATE_UNITS) return null
        return Fragment("modified:$w0-$w1", i + 2)
    }

    private fun matchNamedDay(words: List<String>, i: Int): Fragment? {
        val w = words[i].lowercase()
        if (w != "today" && w != "yesterday") return null
        return Fragment("modified:$w", i + 1)
    }

    /** "since october", "before 2024", "from january 2024", "in march" — the preposition picks the
     *  operator; "from"/"in" narrow to the named month or year, "since" is a lower bound and
     *  "before" an upper one. Falls through (returns `null`) when nothing date-shaped follows, which
     *  leaves "from"/"in" to the ordinary filler-word rule instead — see the class KDoc. */
    private fun matchDatePreposition(words: List<String>, i: Int): Fragment? {
        val opPrefix = when (words[i].lowercase()) {
            "since" -> ">="
            "before" -> "<"
            "from", "in" -> ""
            else -> return null
        }
        val value = matchDateValue(words, i + 1) ?: return null
        return Fragment("modified:$opPrefix${value.text}", i + 1 + value.tokensConsumed)
    }

    /** A bare month or year with no leading preposition — "report 2024", "december sales". */
    private fun matchBareDate(words: List<String>, i: Int): Fragment? {
        val value = matchDateValue(words, i) ?: return null
        return Fragment("modified:${value.text}", i + value.tokensConsumed)
    }

    private class ConsumedValue(val text: String, val tokensConsumed: Int)

    /** A month name with an optional trailing 4-digit year ("december", "december-2024"), or a
     *  bare 4-digit year on its own. Matches [RelativeDate]'s token shapes exactly, via
     *  [RelativeDate.monthIndexOrNull] so the two files cannot recognize a different set of months. */
    private fun matchDateValue(words: List<String>, at: Int): ConsumedValue? {
        val w = words.getOrNull(at) ?: return null
        if (RelativeDate.monthIndexOrNull(w) != null) {
            val year = words.getOrNull(at + 1)?.takeIf { BARE_YEAR.matches(it) }
            return if (year != null) ConsumedValue("${w.lowercase()}-$year", 2) else ConsumedValue(w.lowercase(), 1)
        }
        if (BARE_YEAR.matches(w)) return ConsumedValue(w, 1)
        return null
    }

    private val BARE_YEAR = Regex("^\\d{4}$")

    // ---- size ----

    private val GT_WORDS = setOf("over", "above")
    private val LT_WORDS = setOf("under", "below")

    /** "over 10mb", "bigger than 10 mb", "under 500kb". The two-word comparators ("bigger than",
     *  "smaller than") are checked as a pair up front; the rest are single trigger words. A value
     *  with no unit is not matched here ("over 9000" stays plain words) — this module's size
     *  grammar is always "amount and unit", never a bare number, so a bare number after a
     *  comparator is more likely someone counting something than sizing a file. */
    private fun matchSizeComparison(words: List<String>, i: Int): Fragment? {
        val w0 = words[i].lowercase()
        val w1 = words.getOrNull(i + 1)?.lowercase()
        val (opPrefix, valueStart) = when {
            w0 == "bigger" && w1 == "than" -> ">" to i + 2
            w0 == "smaller" && w1 == "than" -> "<" to i + 2
            w0 in GT_WORDS -> ">" to i + 1
            w0 in LT_WORDS -> "<" to i + 1
            else -> return null
        }
        val value = matchSizeValue(words, valueStart) ?: return null
        return Fragment("size:$opPrefix${value.text}", valueStart + value.tokensConsumed)
    }

    private fun matchSizeWord(words: List<String>, i: Int): Fragment? {
        val canonical = when (words[i].lowercase()) {
            "large" -> "size:>25mb"
            "huge" -> "size:>100mb"
            "small" -> "size:<1mb"
            "tiny" -> "size:<100kb"
            else -> return null
        }
        return Fragment(canonical, i + 1)
    }

    private val NUMBER_WITH_UNIT = Regex("^\\d+(\\.\\d+)?[a-zA-Z]+$")
    private val NUMBER_ONLY = Regex("^\\d+(\\.\\d+)?$")
    private val UNIT_WORD = Regex("^[a-zA-Z]{1,3}$")

    /** "10mb" as one token, or "10 mb" as two — a unit is required either way; see
     *  [matchSizeComparison]'s note on bare numbers. */
    private fun matchSizeValue(words: List<String>, at: Int): ConsumedValue? {
        val w = words.getOrNull(at) ?: return null
        if (NUMBER_WITH_UNIT.matches(w)) return ConsumedValue(w.lowercase(), 1)
        if (NUMBER_ONLY.matches(w)) {
            val unit = words.getOrNull(at + 1)?.takeIf { UNIT_WORD.matches(it) } ?: return null
            return ConsumedValue("$w${unit.lowercase()}", 2)
        }
        return null
    }

    // ---- kind ----

    private fun matchKind(words: List<String>, i: Int, vocabulary: NaturalVocabulary): Fragment? {
        val type = vocabulary.typeFor(words[i]) ?: return null
        return Fragment("type:$type", i + 1)
    }

    // ============================== tokenizing ==============================

    private val FACET_SHAPE = Regex("^-?[A-Za-z_][A-Za-z0-9_]*:.*")

    /** A token this file leaves untouched: it is already the structured grammar, or a keyword that
     *  changes how neighboring tokens combine. Deliberately conservative — a false negative here
     *  (treating structured syntax as a bare word) can misparse the query; a false positive (leaving
     *  a recognizable word untouched) only means one fewer chip, never a wrong one. */
    private fun isStructuredToken(token: String): Boolean =
        token.startsWith("\"") ||
            token.startsWith("(") ||
            token.startsWith("/") ||
            token.startsWith("?") ||
            token.equals("AND", ignoreCase = true) ||
            token.equals("OR", ignoreCase = true) ||
            token.equals("NOT", ignoreCase = true) ||
            token == "|" ||
            FACET_SHAPE.matches(token)

    /**
     * Whitespace-splits [text], except a `"..."` quoted span or a `(...)` parenthesized group stays
     * one token even though it contains spaces — otherwise `key:"two words"` would split into
     * `key:"two` and `words"`, each independently unrecognizable as the facet they came from, and a
     * structured `(a OR b)` group would scatter across the bare-word runs on either side of it.
     *
     * An unterminated quote or an unbalanced `(` simply runs to the end of the string rather than
     * throwing — this is a pre-pass ahead of [QueryParser], whose own tokenizer already tolerates
     * exactly that malformed input and will emit the real diagnostic; this scan only needs to not
     * crash on the way there.
     */
    private fun splitPreservingQuotesAndGroups(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                    i++
                }
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n && text[i] != '"') i++
                    if (i < n) i++ // consume the closing quote, if there is one
                    sb.append(text, start, i)
                }
                c == '(' -> {
                    val start = i
                    var depth = 1
                    i++
                    while (i < n && depth > 0) {
                        if (text[i] == '(') depth++
                        if (text[i] == ')') depth--
                        i++
                    }
                    sb.append(text, start, i)
                }
                else -> { sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

/**
 * The host's kind-noun vocabulary: [kinds] maps a synonym a user might type ("photos", "movies") to
 * the `type:` facet value it means ("image", "video"). Lookup ([typeFor]) is case-insensitive and
 * tolerates the word being singular or plural on *either* side — a naive trailing-`s` strip, so a
 * host need only write "photos" once and both "photo" and "photos" resolve, without needing every
 * singular form spelled out in [kinds].
 */
data class NaturalVocabulary(val kinds: Map<String, String>) {

    private val bySingularKey: Map<String, String> =
        kinds.entries.associate { (k, v) -> singularize(k.lowercase()) to v }

    internal fun typeFor(word: String): String? = bySingularKey[singularize(word.lowercase())]
}

/** Trailing-`s` strip, deliberately naive — see [NaturalVocabulary]. */
private fun singularize(word: String): String =
    if (word.length > 1 && word.endsWith("s")) word.dropLast(1) else word
