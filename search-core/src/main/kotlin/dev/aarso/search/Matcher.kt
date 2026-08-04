package dev.aarso.search

import java.util.Locale
import java.util.regex.PatternSyntaxException

/**
 * A **synchronous, allocation-light predicate** over one subject and its projected document:
 * "does this thing match this query, right now, on this thread, with no I/O".
 *
 * ## Why this type exists
 *
 * It has no counterpart in the engine this module was generalised from, and it is the single most
 * important thing this module adds. That engine could only answer "does this match" by going
 * through an FTS index: a `Flow` of results, asynchronously, from storage. Foto Xplorr filters its
 * library *inside Compose composition* — a `derivedStateOf` recomputing on every keystroke over an
 * in-memory list — and you cannot collect a `Flow` inside `derivedStateOf`. Anything asynchronous
 * there means either a frame of stale results or a coroutine per keystroke; both are visible.
 *
 * So [matches] is an ordinary function returning an ordinary `Boolean`. It reads no clock (the
 * clock is on [EvalContext]), touches no storage, compiles nothing it has not already cached, and
 * is safe to call once per item in a tight loop over tens of thousands of items.
 *
 * ## How it differs from [FacetEvaluator]
 *
 * [FacetEvaluator] answers only the facet half and treats every lexical leaf as neutral `true`,
 * because in an FTS-backed pipeline the text constraint was already applied upstream. This class
 * has no upstream: it evaluates *both* halves in one walk — facets against [registry], text against
 * [SearchDoc.text] — so:
 *
 *  - [QueryNode.Not] is a **plain negation**, with no neutrality guard. [FacetEvaluator] needs that
 *    guard because a `Not` over a lexical subtree would otherwise invert a neutral `true` into
 *    `false` and hide everything. Here every leaf has a real opinion, so negating any of them is
 *    meaningful and the guard would be wrong — it would make `-draft` a no-op.
 *  - The **lossy disjunction is gone**. `gradle OR starred:true` genuinely means "matches gradle,
 *    or is starred". [FacetEvaluator.hasLossyDisjunction] exists for pipelines that must split the
 *    two halves; a host using this class never needs it.
 *
 * ## Why [baseFilter] is a constructor parameter
 *
 * Because a host's non-negotiable predicate must not be something a call site can forget. Foto
 * Xplorr has a privacy-visibility rule — hidden and locked albums are excluded from search results
 * unless the user is inside them — and if that rule were an argument to [matches], then every
 * present and future call site would be one omission away from leaking a hidden photo into a
 * search result. Making it structural means there is exactly one place it is decided, at
 * construction, and no way to call [matches] without it.
 *
 * It also composes the right way round: [baseFilter] is evaluated **first and independently of the
 * query**, so it cannot be negated, OR'd around, or otherwise reached by anything the user types.
 * A user cannot construct a query string that turns it off.
 *
 * @param registry the host's facet vocabulary. Use the same registry the query was parsed with, or
 *   facets the parser accepted will evaluate to `false` here — see [FacetEvaluator.matchesFacet].
 * @param scorer used only by [score]; [matches] never consults it.
 * @param baseFilter a structural precondition every subject must satisfy regardless of the query.
 *   Defaults to "everything passes". Keep it cheap — it runs once per subject per call.
 */
class Matcher<S>(
    private val registry: FieldRegistry<S>,
    private val scorer: Scorer,
    private val baseFilter: (S) -> Boolean = { true },
) {

    /**
     * Whether [subject]/[doc] satisfies [baseFilter] and every constraint in [parsed].
     *
     * A `null` [ParsedQuery.root] — an empty or unparseable query — matches everything that passes
     * [baseFilter]. An empty search box is an unfiltered browse, not a filter matching nothing.
     *
     * ### Text matching semantics
     *
     * Deliberately identical to [CoverageRecencyScorer]'s, so that a subject this returns `true`
     * for is a subject the scorer will also score above zero, and the filtered set and the ranked
     * set cannot disagree:
     *
     *  - **Substring, not token boundary**, over [Normalizer.normalize]d text. `cat` matches
     *    `concatenate`. Surprising, occasionally wrong, and exactly what makes an as-you-type box
     *    feel instant with no prefix index. Use [Segmenter] if you need real tokens.
     *  - Consequently [QueryNode.Term.prefix] is a no-op here: `gradl*` and `gradl` already match
     *    the same documents under substring matching. The flag is preserved on the AST for hosts
     *    that compile to a real index ([QueryCompiler.compileFts]), where it is not a no-op.
     *  - A [QueryNode.Phrase] matches as a contiguous run, which falls out of the same substring
     *    test: normalization collapses runs of whitespace to single spaces on both sides.
     *  - [QueryNode.Semantic] degrades to a plain lexical match, so `?deadlock` still finds
     *    documents containing "deadlock". Announcing that the semantic backend is missing is the
     *    host's job ([Diagnostic.SemanticUnavailable]); silently matching nothing is not.
     *  - [QueryNode.Regex] runs against the **raw, un-normalized** field text, because a pattern
     *    like `/[A-Z]{3}/` is asking about the text as written and would never match a lowercased,
     *    NFKC-folded copy of it. An invalid pattern matches nothing — the module's hard rule is
     *    that a half-typed query never throws, and `/[a` is what every regex looks like mid-typing.
     *
     * Only [SearchDoc.text] is searched. [SearchDoc.display] is shown with a hit and never matched;
     * that separation is the whole reason the two maps are distinct.
     */
    fun matches(subject: S, doc: SearchDoc, parsed: ParsedQuery, ctx: EvalContext): Boolean {
        // First, unconditionally, and out of reach of anything the user typed. See the class KDoc.
        if (!baseFilter(subject)) return false
        val root = parsed.root ?: return true
        val prep = prepared(root, ctx.locale)
        // Allocated only when the query actually has text in it: a pure-facet query
        // (`label:cat`, the common case in a filter UI) never normalizes a single field.
        val fields = if (prep.hasText) NormalizedFields(doc.text, ctx.locale) else NormalizedFields.NONE
        return eval(root, subject, doc, prep, fields, ctx)
    }

    /**
     * [matches], then rank — `null` when the subject does not match at all.
     *
     * The two entry points agree by construction: `score(...) != null` if and only if
     * `matches(...)`. That matters because a [Scorer] can honestly decline to score something this
     * class considers a match — a facet-only query has no terms to cover, and `a OR starred:true`
     * can match via the facet alone — and returning `null` there would silently drop rows the
     * filter accepted. Those cases return a [Scored] with a score of `0.0` and no matched fields
     * instead: a genuine match with nothing to rank it *by*. Order such results with a key of your
     * own ([SearchDoc.timestampMillis] is the usual one); do not read the zero as "poor match".
     *
     * Terms handed to the scorer come from [QueryCompiler.lexicalTerms] — facet and regex syntax
     * stripped, negated terms excluded — normalized the way the scorer expects.
     *
     * [Scored.highlights] is whatever the scorer chose to populate; [CoverageRecencyScorer.score]
     * leaves it empty on purpose. Call [Normalizer.findMatches] over the handful of results you
     * actually draw rather than paying for highlight offsets across the whole corpus.
     */
    fun score(subject: S, doc: SearchDoc, parsed: ParsedQuery, ctx: EvalContext): Scored? {
        if (!matches(subject, doc, parsed, ctx)) return null
        val terms = QueryCompiler.lexicalTerms(parsed.root, ctx.locale)
        if (terms.isEmpty()) return unranked(doc)
        return scorer.score(doc, terms, ctx) ?: unranked(doc)
    }

    /** A match with nothing to rank it by. See [score]. */
    private fun unranked(doc: SearchDoc) = Scored(doc = doc, score = 0.0, matchedFields = emptySet())

    // -------------------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------------------

    /**
     * The boolean walk. Children are visited by index rather than with `all`/`any`: this runs once
     * per subject in a filter that recomputes on every keystroke, and an iterator per boolean node
     * per subject is allocation the caller cannot see and cannot avoid.
     */
    private fun eval(
        node: QueryNode,
        subject: S,
        doc: SearchDoc,
        prep: Prepared,
        fields: NormalizedFields,
        ctx: EvalContext,
    ): Boolean = when (node) {
        is QueryNode.And -> {
            var ok = true
            val children = node.children
            for (i in children.indices) {
                if (!eval(children[i], subject, doc, prep, fields, ctx)) { ok = false; break }
            }
            ok
        }
        is QueryNode.Or -> {
            var ok = false
            val children = node.children
            for (i in children.indices) {
                if (eval(children[i], subject, doc, prep, fields, ctx)) { ok = true; break }
            }
            ok
        }
        // A plain negation, unlike FacetEvaluator's guarded one — see the class KDoc.
        is QueryNode.Not -> !eval(node.child, subject, doc, prep, fields, ctx)
        is QueryNode.Facet -> FacetEvaluator.matchesFacet(subject, node, registry, ctx)
        // The normalized needle was computed once when the query was prepared, not per subject.
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic ->
            fields.contains(prep.needles[node])
        is QueryNode.Regex -> matchesRegex(prep.patterns[node], doc)
    }

    /** `null` = a pattern that would not compile. It matches nothing, and never throws. */
    private fun matchesRegex(regex: Regex?, doc: SearchDoc): Boolean {
        if (regex == null) return false
        for (raw in doc.text.values) if (regex.containsMatchIn(raw)) return true
        return false
    }

    // -------------------------------------------------------------------------------------
    // Per-query preparation
    // -------------------------------------------------------------------------------------

    /**
     * Everything derived from the *query* rather than from the subject: the normalized form of each
     * text leaf, and the compiled form of each regex leaf.
     *
     * Both are per-query constants, and computing them inside the per-subject walk is the obvious
     * way to make this class quietly quadratic — a keystroke over a 50k-item library would
     * normalize the same needle 50k times and recompile the same regex 50k times. [Regex] in
     * particular is expensive enough that compiling it per item is the difference between a
     * responsive filter and a dropped frame.
     */
    private class Prepared(
        val root: QueryNode,
        val locale: Locale,
        /** Normalized needle per [QueryNode.Term]/[QueryNode.Phrase]/[QueryNode.Semantic] node. */
        val needles: Map<QueryNode, String>,
        /** Compiled pattern per [QueryNode.Regex] node; `null` value = did not compile. */
        val patterns: Map<QueryNode, Regex?>,
        /** Whether any text leaf exists, so a pure-facet query can skip normalizing fields. */
        val hasText: Boolean,
    )

    /**
     * A one-entry memo of the last prepared query.
     *
     * One entry is the right size because of how this class is used: a filter pass applies *one*
     * query to *many* subjects, so the hit rate is 100% after the first subject, and the entry
     * turns over exactly once per keystroke. A larger cache would hold onto every intermediate
     * query a user typed on their way to the one they meant.
     *
     * `@Volatile` on an immutable holder rather than a lock: two threads racing here recompute the
     * same value and one wins, which costs a duplicate tree walk in the worst case and cannot
     * produce a wrong answer. Locking a per-item predicate would cost more, every time.
     */
    @Volatile
    private var cache: Prepared? = null

    private fun prepared(root: QueryNode, locale: Locale): Prepared {
        val hit = cache
        // Identity first: a re-parse of unchanged text is a different tree, but a single filter
        // pass reuses one tree, which is the case worth making free.
        if (hit != null && hit.locale == locale && (hit.root === root || hit.root == root)) return hit
        val needles = HashMap<QueryNode, String>()
        val patterns = HashMap<QueryNode, Regex?>()
        collect(root, locale, needles, patterns)
        return Prepared(root, locale, needles, patterns, hasText = needles.isNotEmpty())
            .also { cache = it }
    }

    private fun collect(
        node: QueryNode,
        locale: Locale,
        needles: MutableMap<QueryNode, String>,
        patterns: MutableMap<QueryNode, Regex?>,
    ) {
        when (node) {
            is QueryNode.And -> node.children.forEach { collect(it, locale, needles, patterns) }
            is QueryNode.Or -> node.children.forEach { collect(it, locale, needles, patterns) }
            is QueryNode.Not -> collect(node.child, locale, needles, patterns)
            is QueryNode.Term -> needles[node] = Normalizer.normalize(node.text, locale)
            is QueryNode.Phrase -> needles[node] = Normalizer.normalize(node.text, locale)
            is QueryNode.Semantic -> needles[node] = Normalizer.normalize(node.text, locale)
            is QueryNode.Regex -> patterns[node] = compile(node)
            is QueryNode.Facet -> Unit
        }
    }

    /** Compilation failure is a `null`, never an exception — see [matches]'s note on `/[a`. */
    private fun compile(node: QueryNode.Regex): Regex? = try {
        if (node.ignoreCase) Regex(node.pattern, RegexOption.IGNORE_CASE) else Regex(node.pattern)
    } catch (_: PatternSyntaxException) {
        null
    }

    /**
     * The document's text fields, normalized at most once per [matches] call and only if the query
     * has text in it at all.
     *
     * Normalization is the expensive part of a lexical match (NFKC plus case folding plus
     * whitespace collapse), and a query with three terms would otherwise pay for it three times
     * over every field of every subject.
     */
    private class NormalizedFields(
        private val raw: Map<FieldName, String>,
        private val locale: Locale,
    ) {
        private var folded: Array<String>? = null

        fun contains(needle: String?): Boolean {
            // A null needle means the node was not in the prepared map, which the walk above makes
            // unreachable; an empty needle is a query like `""`. Neither constrains anything, and
            // neither is a reason to reject a subject.
            if (needle.isNullOrEmpty()) return true
            val fields = folded ?: Array(raw.size) { "" }.also { array ->
                var i = 0
                for (text in raw.values) array[i++] = Normalizer.normalize(text, locale)
                folded = array
            }
            for (i in fields.indices) if (fields[i].contains(needle)) return true
            return false
        }

        companion object {
            /** Used when the query has no text leaves, so nothing will ever ask it anything. */
            val NONE = NormalizedFields(emptyMap(), Locale.ROOT)
        }
    }
}
