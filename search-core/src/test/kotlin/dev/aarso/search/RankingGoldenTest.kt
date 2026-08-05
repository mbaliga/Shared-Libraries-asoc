package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The ranking parity gate.** A fixed corpus, fixed queries, and the exact ranked output pinned by
 * id order and by score to [EPSILON].
 *
 * ## Why this exists and what it is for
 *
 * [CoverageRecencyScorer] carries four properties that each look like a bug on first reading and
 * are each load-bearing: coverage unions across fields, field weight is the *maximum* over matched
 * fields, matching is substring rather than token-boundary, and the recency term uses *fractional*
 * days. Its own KDoc says so, and then says the thing that made this file necessary:
 *
 * > Do not "improve" this without a new golden corpus. […] Changing any of them silently reorders
 * > results for every existing host, with no compile error and no test failure unless a
 * > ranking-golden test exists for the new behaviour.
 *
 * There was no such test upstream. Every ranking assertion in the engine this was ported from was
 * *relative* — "a title hit beats a content hit", "fuller coverage beats partial" — which is
 * satisfied by an unbounded family of scoring functions, most of which return a different order for
 * a corpus of more than two documents. This file asserts absolute numbers instead, so that
 * "tidying" `MILLIS_PER_DAY` into a `Long`, or making `fieldWeight` a sum, or adding term
 * frequency, fails here loudly instead of reordering five downstream apps quietly.
 *
 * ## The corpus
 *
 * Fifteen hand-written documents, fixed forever ([CORPUS]). Each expected score below is written as
 * the arithmetic that produces it, not as a decimal literal, so a reader can check the claim
 * against the formula in [CoverageRecencyScorer]'s KDoc without a calculator:
 *
 * ```
 * score = termCoverage * max(weight of each matched field) + 0.5 * 1/(1 + ageDays)
 * ```
 *
 * with `{title: 2.0}` over a default of `1.0` — the Android-IDE configuration, which is the
 * original `TITLE_WEIGHT`/`CONTENT_WEIGHT` pair exactly.
 *
 * Ages are whole days from [NOW], so every recency factor is an exact binary fraction and the
 * epsilon is about float comparison hygiene rather than about accumulated error.
 */
class RankingGoldenTest {

    private companion object {
        /** 2025-01-01T00:00:00Z. */
        const val NOW = 1_735_689_600_000L
        const val DAY = 24L * 60L * 60L * 1000L

        val TITLE = FieldName("title")
        val BODY = FieldName("body")

        /** Tight on purpose. Every expected value is exactly representable; this is not slack. */
        const val EPSILON = 1e-12

        /** `0.5 * 1/(1 + ageDays)`, the recency contribution for a whole-day age. */
        fun recency(ageDays: Int): Double = 0.5 * (1.0 / (1.0 + ageDays.toDouble()))
    }

    private val ctx = EvalContext(nowMillis = NOW)
    private val scorer = CoverageRecencyScorer(fieldWeights = mapOf(TITLE to 2.0))

    private fun doc(id: String, title: String, body: String, ageDays: Int) = SearchDoc(
        id = DocId(id),
        timestampMillis = NOW - ageDays * DAY,
        text = mapOf(TITLE to title, BODY to body),
    )

    /**
     * The fixed corpus. **Do not edit these documents** — edit them and every expectation below
     * becomes a different claim, which is the failure mode this file exists to prevent. Add new
     * documents at the end if a new property needs pinning.
     */
    private val corpus: List<SearchDoc> = listOf(
        // -- 1-2: the same terms in different tiers, for max-field-weight --
        doc("d01-gradle-title", "Gradle build cache", "settings invalidated", ageDays = 0),
        doc("d02-gradle-body", "Release notes", "gradle build cache misses", ageDays = 0),

        // -- 3-5: union coverage. d03 splits the two terms across two fields; d04 and d05 put
        //         both in one field. d03 and d04 must score IDENTICALLY.
        doc("d03-union", "alpha notes", "beta appendix", ageDays = 1),
        doc("d04-title-pair", "alpha beta", "unrelated", ageDays = 1),
        doc("d05-body-pair", "plain", "alpha beta", ageDays = 1),

        // -- 6-7: substring, not token boundary. "cat" is inside "Concatenate".
        doc("d06-concat", "Concatenate helpers", "string utilities", ageDays = 2),
        doc("d07-cat", "Cat photos", "a cat sat on the mat", ageDays = 2),

        // -- 8-10: unicode whose NFKC form differs in length or in code points from the original,
        //          for highlight coordinates.
        doc("d08-ligature", "the ﬁle is cached", "offset map", ageDays = 3),
        doc("d09-cjk", "東京駅で会った", "こんにちは世界", ageDays = 3),
        doc("d10-wide", "Ａ１２３ wide", "ｶﾞ wide form", ageDays = 4),

        // -- 11-13: a deliberate three-way tie, listed out of id order. Same text, same timestamp;
        //           only the id can separate them.
        doc("tie-c", "zeta", "tiebreak corpus", ageDays = 10),
        doc("tie-a", "zeta", "tiebreak corpus", ageDays = 10),
        doc("tie-b", "zeta", "tiebreak corpus", ageDays = 10),

        // -- 14-15: equal score, DIFFERENT timestamps. Full coverage on an older document exactly
        //           cancels partial coverage on a newer one (1.0 + 0.25 == 0.75 + 0.5), so only the
        //           timestamp rung of the tiebreak chain can order these — and it must beat the id
        //           rung, which would sort them the other way round.
        doc("ts-full-older", "no keywords", "omega sigma kappa lambda", ageDays = 1),
        doc("ts-part-newer", "no keywords", "omega sigma kappa", ageDays = 0),
    )

    private fun rank(query: String, explain: Boolean = false) =
        scorer.rank(corpus, Normalizer.tokenizeQuery(query), ctx, explain)

    private fun assertRanking(query: String, vararg expected: Pair<String, Double>) {
        val hits = rank(query)
        assertEquals(
            "ids for query '$query'",
            expected.map { it.first },
            hits.map { it.doc.id.value },
        )
        for ((i, pair) in expected.withIndex()) {
            assertEquals("score for '${pair.first}' in query '$query'", pair.second, hits[i].score, EPSILON)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Golden rankings
    // ---------------------------------------------------------------------------------------

    /**
     * Pins **max-field-weight** and **substring matching** in one query.
     *
     * `d01` carries both terms in the 2.0-weighted title; `d02` carries both in the 1.0-weighted
     * body; `d08` matches only `cache`, and only because `cached` contains it.
     */
    @Test fun `golden — gradle cache`() {
        assertRanking(
            "gradle cache",
            "d01-gradle-title" to 1.0 * 2.0 + recency(0), // 2.5
            "d02-gradle-body" to 1.0 * 1.0 + recency(0), // 1.5
            "d08-ligature" to 0.5 * 2.0 + recency(3), // 1.125
        )
    }

    /**
     * Pins **union coverage across fields** and, at the same time, **max not sum and not average**.
     *
     * `d03-union` has `alpha` in the title and `beta` in the body. If coverage were computed per
     * field it would score 0.5 in each and rank below `d04-title-pair`; if the field weight were a
     * sum it would apply 3.0 and rank above it; if it were an average it would apply 1.5 and rank
     * below. It scores *exactly* what `d04-title-pair` scores, and only the id separates them.
     */
    @Test fun `golden — alpha beta`() {
        assertRanking(
            "alpha beta",
            "d03-union" to 1.0 * 2.0 + recency(1), // 2.25 — union coverage, max weight
            "d04-title-pair" to 1.0 * 2.0 + recency(1), // 2.25 — identical; id breaks the tie
            "d05-body-pair" to 1.0 * 1.0 + recency(1), // 1.25
        )
    }

    /**
     * Pins **substring-not-token** hard: a match inside `Concatenate` is worth precisely as much as
     * a match on the standalone word `Cat`, and a second hit in the body adds nothing at all
     * (coverage is per-term, not per-occurrence). Same score, same timestamp — id decides.
     */
    @Test fun `golden — cat matches concatenate and scores identically`() {
        assertRanking(
            "cat",
            "d06-concat" to 1.0 * 2.0 + recency(2), // 2.1666…
            "d07-cat" to 1.0 * 2.0 + recency(2), // 2.1666…
        )
    }

    /** Pins the **id-ascending** rung: identical score, identical timestamp, corpus order c-a-b. */
    @Test fun `golden — three-way tie resolves by id ascending`() {
        assertRanking(
            "zeta",
            "tie-a" to 1.0 * 2.0 + recency(10),
            "tie-b" to 1.0 * 2.0 + recency(10),
            "tie-c" to 1.0 * 2.0 + recency(10),
        )
    }

    /**
     * Pins the **timestamp-descending** rung, and pins that it outranks the id rung.
     *
     * Both documents score 1.25. `ts-full-older` sorts first by id; `ts-part-newer` sorts first by
     * timestamp. The timestamp wins, so an implementation that dropped or reordered the middle key
     * of the chain fails here even though both documents' scores are still correct.
     */
    @Test fun `golden — equal scores resolve by timestamp before id`() {
        assertRanking(
            "omega sigma kappa lambda",
            "ts-part-newer" to 0.75 * 1.0 + recency(0), // 1.25 — 3 of 4 terms, but newest
            "ts-full-older" to 1.0 * 1.0 + recency(1), // 1.25 — all 4 terms, a day older
        )
        // Guard the guard: assert the two really are tied, so this test cannot silently degrade
        // into "one of them just scores higher".
        val hits = rank("omega sigma kappa lambda")
        assertEquals(hits[0].score, hits[1].score, EPSILON)
        assertTrue(hits[0].doc.timestampMillis > hits[1].doc.timestampMillis)
        assertTrue(hits[0].doc.id > hits[1].doc.id)
    }

    /** CJK matches in the script it was typed in, with no transliteration and no language guessing. */
    @Test fun `golden — CJK query`() {
        assertRanking("こんにちは", "d09-cjk" to 1.0 * 1.0 + recency(3)) // body tier
        assertRanking("東京駅", "d09-cjk" to 1.0 * 2.0 + recency(3)) // title tier
    }

    /** A full-width query folds onto half-width indexed text, and vice versa. */
    @Test fun `golden — fullwidth query folds onto the same document`() {
        assertRanking("123", "d10-wide" to 1.0 * 2.0 + recency(4))
        assertRanking("１２３", "d10-wide" to 1.0 * 2.0 + recency(4))
    }

    /** A query that matches nothing is empty, not a corpus dump. */
    @Test fun `golden — a query matching nothing ranks nothing`() {
        assertTrue(rank("thisappearsnowhere").isEmpty())
        assertTrue(rank("   ").isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Highlights, in ORIGINAL coordinates
    // ---------------------------------------------------------------------------------------

    /**
     * The coordinate guarantee [Highlight] documents, on the input that used to break it.
     *
     * `d08-ligature`'s title is `the ﬁle is cached`. The `ﬁ` at original index 4 is a single
     * character that NFKC expands to two, so every normalized index from there on is one greater
     * than the original index: `cache` sits at normalized 12 but at original 11.
     *
     * The engine this was ported from returned `12..16` here — in-bounds, and pointing at `ached`.
     * That was its documented escape hatch for length-changing normalization, and it misfired on
     * exactly the scripts most likely to need highlighting. This asserts the fix.
     */
    @Test fun `golden — highlight lands in original coordinates past a ligature`() {
        val hit = rank("gradle cache").single { it.doc.id == DocId("d08-ligature") }
        val title = hit.doc.text.getValue(TITLE)
        val highlight = hit.highlights.single()

        assertEquals(TITLE, highlight.field)
        assertEquals(11..15, highlight.range)
        assertEquals("cache", title.substring(highlight.range.first, highlight.range.last + 1))
        // The normalized coordinates the old engine would have returned, spelled out so the
        // regression is legible if this ever flips back.
        assertEquals("ached", title.substring(12, 17))
    }

    /** Two characters folding into one: the highlight covers both source characters, not one. */
    @Test fun `golden — highlight over a halfwidth katakana pair covers both source characters`() {
        val hit = rank("ガ").single()
        assertEquals(DocId("d10-wide"), hit.doc.id)
        val body = hit.doc.text.getValue(BODY)
        val highlight = hit.highlights.single { it.field == BODY }
        assertEquals("ｶﾞ", body.substring(highlight.range.first, highlight.range.last + 1))
    }

    /** The length-preserving common case still lines up, and multiple spans stay separate. */
    @Test fun `golden — two disjoint matches in one field stay two highlights`() {
        val hit = rank("gradle cache").first()
        assertEquals(DocId("d01-gradle-title"), hit.doc.id)
        val title = hit.doc.text.getValue(TITLE)
        assertEquals(listOf(0..5, 13..17), hit.highlights.map { it.range })
        assertEquals("Gradle", title.substring(0, 6))
        assertEquals("cache", title.substring(13, 18))
    }

    /** Highlights are produced only for fields that actually matched. */
    @Test fun `golden — highlights cover every matched field and no others`() {
        val hit = rank("alpha beta").single { it.doc.id == DocId("d03-union") }
        assertEquals(setOf(TITLE, BODY), hit.matchedFields)
        assertEquals(setOf(TITLE, BODY), hit.highlights.map { it.field }.toSet())

        val bodyOnly = rank("alpha beta").single { it.doc.id == DocId("d05-body-pair") }
        assertEquals(setOf(BODY), bodyOnly.matchedFields)
        assertEquals(setOf(BODY), bodyOnly.highlights.map { it.field }.toSet())
    }

    /** No highlight may index outside the field it belongs to — over the whole corpus at once. */
    @Test fun `golden — every highlight of every hit is in bounds of its own field`() {
        val queries = listOf(
            "gradle cache", "alpha beta", "cat", "zeta", "omega sigma kappa lambda",
            "こんにちは", "東京駅", "123", "ガ", "wide", "the", "a",
        )
        for (query in queries) {
            for (hit in rank(query)) {
                for (highlight in hit.highlights) {
                    val field = hit.doc.text.getValue(highlight.field)
                    assertTrue(
                        "'$query' -> ${hit.doc.id}.${highlight.field}: ${highlight.range} outside 0..${field.length - 1}",
                        highlight.range.first >= 0 &&
                            highlight.range.last < field.length &&
                            highlight.range.first <= highlight.range.last,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Explanation parity
    // ---------------------------------------------------------------------------------------

    /** The explanation must be an account of the score that was actually returned, term for term. */
    @Test fun `golden — explanation reproduces the pinned score exactly`() {
        val hit = rank("gradle cache", explain = true).single { it.doc.id == DocId("d08-ligature") }
        val e = hit.explanation!!
        assertEquals(1, e.matchedTermCount)
        assertEquals(2, e.distinctQueryTermCount)
        assertEquals(0.5, e.termCoverage, EPSILON)
        assertEquals(2.0, e.appliedFieldWeight, EPSILON)
        assertEquals(recency(3), e.recencyFactor, EPSILON)
        assertEquals(1.125, e.total, EPSILON)
        assertEquals(hit.score, e.total, EPSILON)
    }

    /**
     * Pins the **fractional** day in the recency term, which is the property most likely to be
     * "tidied" away: `MILLIS_PER_DAY` is a `Double`, so a six-hour-old document decays to
     * `1/1.25`, not to `1/1`. Integer division would flatten every document touched in the last
     * day onto one value — precisely the tier a search-as-you-type UI spends its time ranking.
     */
    @Test fun `golden — recency decays smoothly below one day`() {
        val sixHours = SearchDoc(
            id = DocId("six-hours"),
            timestampMillis = NOW - DAY / 4,
            text = mapOf(BODY to "alpha"),
        )
        val fresh = SearchDoc(
            id = DocId("fresh"),
            timestampMillis = NOW,
            text = mapOf(BODY to "alpha"),
        )
        val hits = scorer.rank(listOf(sixHours, fresh), listOf("alpha"), ctx)
        assertEquals(listOf("fresh", "six-hours"), hits.map { it.doc.id.value })
        assertEquals(1.0 + 0.5, hits[0].score, EPSILON)
        assertEquals(1.0 + 0.5 * (1.0 / 1.25), hits[1].score, EPSILON)
    }
}
