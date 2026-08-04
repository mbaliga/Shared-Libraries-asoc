package dev.aarso.search

/**
 * The default ranking function: **term coverage, weighted by the best field that matched, plus a
 * recency boost.**
 *
 * ```
 * score = termCoverage * fieldWeight + recencyWeight * (1 / (1 + ageDays))
 *
 *   termCoverage = (# distinct query terms matched in ANY field) / (# distinct query terms)
 *   fieldWeight  = max(weight of each field that matched)          // default 1.0 per field
 *   ageDays      = max(0, now - doc.timestampMillis) / 86_400_000  // see MILLIS_PER_DAY below
 * ```
 *
 * It is deliberately legible rather than clever. A user can be told "it matched more of what you
 * typed, in a more important field, more recently" and that sentence is the whole algorithm.
 * There is no term frequency, no inverse document frequency, no length normalization, and no
 * corpus statistics of any kind — which also means a document can be scored in isolation, with no
 * index-wide state, which is what lets Fylz rank a live filesystem walk and Foto Xplorr rank
 * inside a Compose composition.
 *
 * ## Do not "improve" this without a new golden corpus
 *
 * Every one of the following is a deliberate, load-bearing property carried over unchanged from
 * the engine this was generalised from, and every one of them looks like a bug on first reading.
 * Changing any of them silently reorders results for every existing host, with no compile error
 * and no test failure unless a ranking-golden test exists for the new behaviour:
 *
 *  - **Coverage is a union across fields, not per-field.** A term found in `title` and a
 *    different term found in `body` give full coverage, exactly as if both had hit one field.
 *  - **[fieldWeight] is the maximum over matched fields, not a sum and not an average.** One
 *    `title` hit re-weights *every* matched term, including terms that only appeared in `body`.
 *    (The original's binary title-vs-content rule is the two-field case of this.)
 *  - **Matching is substring, not token-boundary.** `normalize(fieldText).contains(term)`. So
 *    `cat` matches `concatenate` and `ai` matches `said`. This is surprising, it is occasionally
 *    wrong, and it is also what makes prefix-as-you-type feel instant without a prefix index.
 *    [Segmenter] exists for the callers that want real tokens; this scorer does not use it.
 *  - **The recency term uses fractional days.** See [MILLIS_PER_DAY].
 *
 * @param fieldWeights per-field weights, keyed by [SearchDoc.text] field name. The Android-IDE
 *   configuration is `{title: 2.0}` with a [defaultWeight] of `1.0`, which reproduces the
 *   original `TITLE_WEIGHT`/`CONTENT_WEIGHT` pair exactly.
 * @param defaultWeight weight for a matched field absent from [fieldWeights].
 * @param recencyWeight scale of the recency boost added on top of the weighted coverage. `0.5`
 *   means a document touched today scores about half a fully-covered content match above one
 *   touched a year ago.
 */
class CoverageRecencyScorer(
    private val fieldWeights: Map<FieldName, Double>,
    private val defaultWeight: Double = 1.0,
    private val recencyWeight: Double = 0.5,
) : Scorer {

    /**
     * Score [doc] against the already-normalized [terms]. Returns `null` when no term matched any
     * field — the document is not a hit and callers drop it.
     *
     * [Scored.highlights] is left empty here: locating matches costs a second pass over every
     * field with an offset map behind it ([Normalizer.findMatches]), and a caller ranking a large
     * corpus only needs highlights for the handful of hits it will actually draw. [rank] fills
     * them in for the results it returns.
     *
     * Field text is normalized on every call, as in the original. A host with a persistent index
     * that already stores normalized text should keep that text in [SearchDoc.text] — normalizing
     * an already-normalized string is idempotent, so this stays correct and gets cheaper.
     */
    override fun score(doc: SearchDoc, terms: List<String>, ctx: EvalContext, explain: Boolean): Scored? {
        val parts = computeParts(doc, terms, ctx) ?: return null
        return Scored(
            doc = doc,
            score = parts.total,
            matchedFields = parts.matchedFields,
            highlights = emptyList(),
            explanation = if (explain) parts.toExplanation() else null,
        )
    }

    /**
     * Score every document in [docs], drop the non-hits, and return them ranked.
     *
     * The ordering is **score descending, then [SearchDoc.timestampMillis] descending, then
     * [SearchDoc.id] ascending** — the original's exact tiebreak chain. The third key is what
     * makes the order total: without it, two documents with the same score and the same timestamp
     * would come back in corpus order, and "corpus order" is a filesystem walk for one host and a
     * database cursor for another. Ties have to resolve identically on every host or a golden
     * ranking test is not portable.
     *
     * [Scored.highlights] is populated for each returned hit, over the fields that actually
     * matched, in [SearchDoc.text] iteration order.
     */
    fun rank(
        docs: Iterable<SearchDoc>,
        terms: List<String>,
        ctx: EvalContext,
        explain: Boolean = false,
    ): List<Scored> {
        if (terms.isEmpty()) return emptyList()

        val hits = ArrayList<Scored>()
        for (doc in docs) {
            val parts = computeParts(doc, terms, ctx) ?: continue
            val highlights = ArrayList<Highlight>()
            for ((field, raw) in doc.text) {
                if (field !in parts.matchedFields) continue
                for (range in Normalizer.findMatches(raw, terms, ctx.locale)) {
                    highlights.add(Highlight(field, range))
                }
            }
            hits.add(
                Scored(
                    doc = doc,
                    score = parts.total,
                    matchedFields = parts.matchedFields,
                    highlights = highlights,
                    explanation = if (explain) parts.toExplanation() else null,
                ),
            )
        }

        return hits.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.doc.timestampMillis }
                .thenBy { it.doc.id },
        )
    }

    // -------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------

    /** Everything both entry points need, computed once. */
    private class Parts(
        val termCoverage: Double,
        val matchedTermCount: Int,
        val distinctQueryTermCount: Int,
        val fieldWeight: Double,
        /** The full recency *contribution*, i.e. `recencyWeight * 1/(1 + ageDays)` — not the bare
         *  decay factor — so that `termCoverage * fieldWeight + recencyFactor == total`. */
        val recencyFactor: Double,
        val total: Double,
        val matchedFields: Set<FieldName>,
    ) {
        fun toExplanation(): Explanation = Explanation(
            termCoverage = termCoverage,
            matchedTermCount = matchedTermCount,
            distinctQueryTermCount = distinctQueryTermCount,
            appliedFieldWeight = fieldWeight,
            recencyFactor = recencyFactor,
            total = total,
        )
    }

    private fun computeParts(doc: SearchDoc, terms: List<String>, ctx: EvalContext): Parts? {
        if (terms.isEmpty()) return null
        // `distinct()` preserves first-occurrence order, and the size of *this* list is the
        // coverage denominator. Note an empty term stays in the denominator but can never match
        // (the `isNotEmpty()` guard below), so a query that somehow produced one is penalised
        // rather than crashing — original behaviour, preserved.
        val distinct = terms.distinct()

        // Normalize each field once, not once per term.
        val normalized = LinkedHashMap<FieldName, String>(doc.text.size)
        for ((field, raw) in doc.text) normalized[field] = Normalizer.normalize(raw, ctx.locale)

        var matchedTermCount = 0
        val matchedFields = LinkedHashSet<FieldName>()
        for (term in distinct) {
            if (term.isEmpty()) continue
            var hitSomewhere = false
            for ((field, text) in normalized) {
                // Substring, not token-boundary. See the class KDoc.
                if (text.contains(term)) {
                    hitSomewhere = true
                    matchedFields.add(field)
                }
            }
            // Coverage counts the term once no matter how many fields it landed in: it is the
            // UNION across fields, which is why this increment is outside the field loop.
            if (hitSomewhere) matchedTermCount++
        }
        if (matchedTermCount == 0) return null

        val termCoverage = matchedTermCount.toDouble() / distinct.size.toDouble()
        // MAX, not sum: one hit in a heavy field lifts the whole document, including the terms
        // that only appeared in light fields. matchedFields is non-empty here by construction.
        val fieldWeight = matchedFields.maxOf { fieldWeights[it] ?: defaultWeight }

        // Future timestamps clamp to age 0 rather than producing a boost above `recencyWeight`.
        val ageDays = (ctx.nowMillis - doc.timestampMillis).coerceAtLeast(0L) / MILLIS_PER_DAY
        val recencyFactor = recencyWeight * (1.0 / (1.0 + ageDays))

        return Parts(
            termCoverage = termCoverage,
            matchedTermCount = matchedTermCount,
            distinctQueryTermCount = distinct.size,
            fieldWeight = fieldWeight,
            recencyFactor = recencyFactor,
            total = termCoverage * fieldWeight + recencyFactor,
            matchedFields = matchedFields,
        )
    }

    companion object {
        /**
         * Milliseconds per day, as a **`Double`** — so `ageMillis / MILLIS_PER_DAY` is a floating
         * division and `ageDays` is *fractional*. A document six hours old has `ageDays = 0.25`
         * and a recency factor of `1/1.25 = 0.8`, not `1/1 = 1.0`.
         *
         * This is not a typo and must not be "tidied" into `86_400_000L`. Integer division would
         * flatten every document touched within the last 24 hours to an identical recency term,
         * which collapses precisely the tier of results a search-as-you-type UI spends most of its
         * time ranking — and it would do so with no compile error and no test failure. Recency is
         * a smooth decay by design; keeping it smooth all the way down to sub-day ages is the
         * whole reason this constant is a `Double`.
         */
        private const val MILLIS_PER_DAY: Double = 24.0 * 60.0 * 60.0 * 1000.0
    }
}
