package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking half of the original `LexicalSearchTest` — `score` and `search`, which lived on the
 * `LexicalSearch` object there and live on [CoverageRecencyScorer] here.
 *
 * Everything is `nowMillis`-relative and deterministic: a fixed [NOW] stands in for the clock, so
 * recency and tie-breaking are reproducible.
 *
 * ## How the original's two tiers are expressed here
 *
 * The original modelled a document as a fixed pair of text tiers — `title`, and `snippet + " " +
 * body` — with a binary weight (`TITLE_WEIGHT` 2.0 if any term hit the title, else
 * `CONTENT_WEIGHT` 1.0) and a `MatchedIn` enum reporting which tier won. This module has a named
 * map of fields and a weight per field, of which that is exactly the two-field case: weights
 * `{title: 2.0}` with a default of `1.0` reproduce the original numbers to the bit, and
 * [Scored.matchedFields] carries strictly more information than `MatchedIn` did (it names every
 * field that matched, not just the winning tier).
 *
 * So `assertEquals(MatchedIn.TITLE, hit.matchedIn)` becomes `assertEquals(setOf(TITLE),
 * hit.matchedFields)`, and nothing else about these tests moved.
 */
class CoverageRecencyScorerTest {

    private companion object {
        /** Fixed "now" so age-based scoring is deterministic. 2025-01-01T00:00:00Z. */
        const val NOW = 1_735_689_600_000L
        const val DAY = 24L * 60L * 60L * 1000L

        val TITLE = FieldName("title")
        val BODY = FieldName("body")
    }

    private val ctx = EvalContext(nowMillis = NOW)

    /** `{title: 2.0}` over a default of `1.0` — the original TITLE_WEIGHT/CONTENT_WEIGHT pair. */
    private val scorer = CoverageRecencyScorer(fieldWeights = mapOf(TITLE to 2.0))

    private fun doc(
        id: String,
        title: String = "",
        body: String = "",
        ageDays: Long = 0,
    ) = SearchDoc(
        id = DocId(id),
        timestampMillis = NOW - ageDays * DAY,
        text = mapOf(TITLE to title, BODY to body),
    )

    private fun search(docs: List<SearchDoc>, query: String, explain: Boolean = false) =
        scorer.rank(docs, Normalizer.tokenizeQuery(query), ctx, explain)

    private fun score(doc: SearchDoc, terms: List<String>) = scorer.score(doc, terms, ctx)?.score

    // ---- score ----

    @Test fun `score is null when nothing matches`() {
        val d = doc("a", title = "weather", body = "sunny today")
        assertNull(scorer.score(d, listOf("kotlin"), ctx))
    }

    @Test fun `title match outscores content-only match`() {
        val titled = doc("t", title = "kotlin coroutines", body = "misc", ageDays = 0)
        val contented = doc("c", title = "misc", body = "kotlin coroutines", ageDays = 0)
        val st = score(titled, listOf("kotlin"))!!
        val sc = score(contented, listOf("kotlin"))!!
        assertTrue("title $st should beat content $sc", st > sc)
    }

    @Test fun `more term coverage scores higher`() {
        val both = doc("b", body = "alpha beta together", ageDays = 0)
        val one = doc("o", body = "alpha only", ageDays = 0)
        val sBoth = score(both, listOf("alpha", "beta"))!!
        val sOne = score(one, listOf("alpha", "beta"))!!
        assertTrue("full coverage $sBoth should beat partial $sOne", sBoth > sOne)
    }

    @Test fun `recency boosts an equal text match`() {
        val fresh = doc("f", body = "alpha", ageDays = 0)
        val stale = doc("s", body = "alpha", ageDays = 365)
        val sFresh = score(fresh, listOf("alpha"))!!
        val sStale = score(stale, listOf("alpha"))!!
        assertTrue("fresh $sFresh should beat stale $sStale", sFresh > sStale)
    }

    @Test fun `future timestamp clamps to age zero`() {
        val future = doc("fut", body = "alpha", ageDays = -10) // timestamp in the future
        val nowDoc = doc("now", body = "alpha", ageDays = 0)
        assertEquals(score(nowDoc, listOf("alpha"))!!, score(future, listOf("alpha"))!!, 1e-9)
    }

    @Test fun `mixed case and diacritic query still matches`() {
        val d = doc("m", title = "Café RÉSUMÉ", body = "x")
        assertNotNull(scorer.score(d, Normalizer.tokenizeQuery("café résumé"), ctx))
    }

    // ---- rank (the original's `search`) ----

    @Test fun `empty query returns empty`() {
        val docs = listOf(doc("a", title = "alpha"))
        assertTrue(search(docs, "   ").isEmpty())
    }

    @Test fun `no match returns empty`() {
        val docs = listOf(doc("a", title = "alpha", body = "beta"))
        assertTrue(search(docs, "gamma").isEmpty())
    }

    @Test fun `rank puts a title hit first and names the field it matched`() {
        val docs = listOf(
            doc("content", title = "notes", body = "kotlin flow"),
            doc("title", title = "kotlin flow", body = "notes"),
        )
        val hits = search(docs, "kotlin")
        assertEquals(2, hits.size)
        assertEquals(DocId("title"), hits[0].doc.id)
        assertEquals(setOf(TITLE), hits[0].matchedFields)
        assertTrue(hits[0].highlights.isNotEmpty())
        assertEquals(setOf(BODY), hits[1].matchedFields)
    }

    @Test fun `rank orders by recency for equal text matches`() {
        val docs = listOf(
            doc("old", body = "alpha", ageDays = 30),
            doc("new", body = "alpha", ageDays = 0),
        )
        val hits = search(docs, "alpha")
        assertEquals(DocId("new"), hits[0].doc.id)
        assertEquals(DocId("old"), hits[1].doc.id)
    }

    @Test fun `devanagari query ranks the matching doc above the non-matching one`() {
        val docs = listOf(
            doc("hi", title = "नमस्ते दुनिया", body = "बातचीत"),
            doc("en", title = "hello world", body = "conversation"),
        )
        val hits = search(docs, "नमस्ते")
        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals(DocId("hi"), hits[0].doc.id)
        // The non-matching English doc must not appear.
        assertTrue(hits.none { it.doc.id == DocId("en") })
    }

    @Test fun `japanese query finds the matching doc`() {
        val docs = listOf(
            doc("jp", title = "こんにちは世界", body = "テスト"),
            doc("en", title = "goodbye", body = "test"),
        )
        val hits = search(docs, "こんにちは")
        assertEquals(1, hits.size)
        assertEquals(DocId("jp"), hits[0].doc.id)
    }

    @Test fun `tie-breaking is deterministic by id when score and recency are equal`() {
        val docs = listOf(
            doc("c", body = "alpha", ageDays = 5),
            doc("a", body = "alpha", ageDays = 5),
            doc("b", body = "alpha", ageDays = 5),
        )
        assertEquals(listOf("a", "b", "c"), search(docs, "alpha").map { it.doc.id.value })
    }

    // ---- the tiebreak chain is exported, not private to rank ----

    /**
     * [RANKING_ORDER] exists because [Scorer] is an interface: a host implementing it gets a
     * per-document [Scored] and no ordering at all, and the chain it invents will get the first key
     * right and the other two wrong — invisibly, since results still come back ranked plausibly.
     *
     * These assert the two things a host must not have to rediscover: that the exported comparator
     * *is* the one [rank] uses, and that timestamp outranks id (the rung most likely to be dropped,
     * and the one whose absence only shows up on an exact score tie).
     */
    @Test fun `the exported comparator is the order rank actually produces`() {
        val docs = listOf(
            doc("c", body = "alpha", ageDays = 5),
            doc("a", body = "alpha", ageDays = 5),
            doc("b", body = "alpha", ageDays = 5),
            doc("newest", title = "alpha", ageDays = 0),
            doc("partial", body = "alpha beta", ageDays = 2),
        )
        val ranked = search(docs, "alpha")
        assertEquals(ranked, ranked.shuffled(kotlin.random.Random(7)).sortedWith(RANKING_ORDER))
    }

    @Test fun `the exported comparator puts timestamp above id, as rank does`() {
        val older = Scored(doc("a", body = "x", ageDays = 1), score = 1.0, matchedFields = emptySet())
        val newer = Scored(doc("z", body = "x", ageDays = 0), score = 1.0, matchedFields = emptySet())
        // Equal scores: id would say a-then-z, timestamp says z-then-a, and timestamp wins.
        assertEquals(listOf(newer, older), listOf(older, newer).sortedWith(RANKING_ORDER))
        assertEquals(listOf(newer, older), listOf(newer, older).sortedWith(RANKING_ORDER))
    }

    @Test fun `multi-term query coverage orders results`() {
        val docs = listOf(
            doc("full", body = "alpha beta", ageDays = 0),
            doc("half", body = "alpha gamma", ageDays = 0),
        )
        val hits = search(docs, "alpha beta")
        assertEquals(DocId("full"), hits[0].doc.id)
        assertTrue(hits[0].score > hits[1].score)
    }

    // ---- coverage is a union across fields; weight is the max over them ----

    @Test fun `coverage unions across fields`() {
        // One term in the title and a different term in the body is FULL coverage, exactly as if
        // both had landed in one field. Load-bearing and surprising — see CoverageRecencyScorer.
        val split = doc("split", title = "alpha", body = "beta", ageDays = 0)
        val together = doc("together", title = "alpha beta", body = "", ageDays = 0)
        assertEquals(score(together, listOf("alpha", "beta"))!!, score(split, listOf("alpha", "beta"))!!, 1e-12)
    }

    @Test fun `field weight is the max over matched fields, not a sum or an average`() {
        val bothFields = doc("both", title = "alpha", body = "alpha", ageDays = 0)
        val titleOnly = doc("title", title = "alpha", body = "", ageDays = 0)
        // A body hit alongside the title hit must not add to, or dilute, the title's 2.0.
        assertEquals(score(titleOnly, listOf("alpha"))!!, score(bothFields, listOf("alpha"))!!, 1e-12)
    }

    @Test fun `matching is substring, not token boundary`() {
        // `cat` matches `concatenate`. Occasionally wrong, and exactly what makes prefix-as-you-type
        // feel instant with no prefix index.
        assertNotNull(scorer.score(doc("c", body = "concatenate"), listOf("cat"), ctx))
        assertNotNull(scorer.score(doc("s", body = "said"), listOf("ai"), ctx))
    }

    // ---- explain ----

    @Test fun `explain defaults to false and leaves explanation null`() {
        val docs = listOf(doc("a", title = "kotlin flow", body = "notes"))
        assertNull(search(docs, "kotlin")[0].explanation)
    }

    @Test fun `explain populates explanation only when requested`() {
        val docs = listOf(doc("a", title = "kotlin flow", body = "notes"))
        assertNotNull(search(docs, "kotlin", explain = true)[0].explanation)
    }

    @Test fun `explanation parts recombine into the score within tolerance`() {
        val docs = listOf(
            doc("t", title = "gradle build cache misses", body = "cache cache cache invalidated", ageDays = 6),
        )
        val hit = search(docs, "build cache misses", explain = true).single()
        val e = hit.explanation!!
        assertEquals(hit.score, e.termCoverage * e.appliedFieldWeight + e.recencyFactor, 1e-9)
        assertEquals(hit.score, e.total, 1e-9)
    }

    @Test fun `explanation reports the coverage fraction it used`() {
        val docs = listOf(doc("a", title = "misc", body = "cache invalidated"))
        val e = search(docs, "cache missing", explain = true).single().explanation!!
        assertEquals(1, e.matchedTermCount)
        assertEquals(2, e.distinctQueryTermCount)
        assertEquals(0.5, e.termCoverage, 1e-12)
    }

    @Test fun `explanation echoes the weight of the heaviest field that matched`() {
        val titled = listOf(doc("a", title = "kotlin", body = "x"))
        assertEquals(2.0, search(titled, "kotlin", explain = true).single().explanation!!.appliedFieldWeight, 1e-9)
        val contented = listOf(doc("b", title = "x", body = "kotlin"))
        assertEquals(1.0, search(contented, "kotlin", explain = true).single().explanation!!.appliedFieldWeight, 1e-9)
    }

    @Test fun `explanation over a multilingual doc still recombines correctly`() {
        val docs = listOf(doc("hi", title = "नमस्ते दुनिया", body = "बातचीत", ageDays = 3))
        val hit = search(docs, "नमस्ते", explain = true).single()
        val e = hit.explanation!!
        assertEquals(hit.score, e.termCoverage * e.appliedFieldWeight + e.recencyFactor, 1e-9)
    }
}
