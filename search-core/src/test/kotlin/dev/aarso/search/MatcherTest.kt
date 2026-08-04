package dev.aarso.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Matcher] — the synchronous predicate that has no counterpart in the engine this module was
 * generalised from, and therefore no ported suite either.
 *
 * What is pinned here is what the class *claims* about itself and what a reviewer found it was not
 * doing: that query-derived work happens once per query rather than once per subject, that [score]
 * and [matches] agree by construction, and that the two halves it evaluates in one walk — facets
 * and text — behave the way the class KDoc says.
 *
 * The subject type is [Conversation] and the vocabulary [TestVocabulary.registry], so a query means
 * the same thing here as it does in `FacetEvaluatorTest` and the assertions can be read against
 * that file.
 */
class MatcherTest {

    private companion object {
        val TITLE = FieldName("title")
        val BODY = FieldName("body")
    }

    private val registry = TestVocabulary.registry
    private val ctx = TestVocabulary.CTX
    private val scorer = CoverageRecencyScorer(fieldWeights = mapOf(TITLE to 2.0))

    private fun parse(query: String) = QueryParser.parse(query, registry, ctx, TestVocabulary.ZONE)

    private fun doc(id: String, title: String = "", body: String = "") = SearchDoc(
        id = DocId(id),
        timestampMillis = TestVocabulary.NOW_MILLIS,
        text = mapOf(TITLE to title, BODY to body),
    )

    /**
     * Records the exact `terms` **list instance** it was handed. Identity, not content, is what
     * distinguishes "derived once and memoized" from "derived again per subject", and content
     * alone cannot tell them apart.
     */
    private class RecordingScorer : Scorer {
        val seen = mutableListOf<List<String>>()
        override fun score(doc: SearchDoc, terms: List<String>, ctx: EvalContext, explain: Boolean): Scored? {
            seen.add(terms)
            return Scored(doc = doc, score = 1.0, matchedFields = emptySet())
        }
    }

    // ---- query-derived work happens once, not once per subject ----

    /**
     * **A defect fixed here, pinned.** [Matcher] memoizes everything derived from the query in its
     * `Prepared` holder — normalized needles, compiled regexes — precisely so a filter pass over a
     * large library does that work once. [Matcher.score] then called `QueryCompiler.lexicalTerms`
     * directly on every invocation: a tree walk plus a `normalize` of the whole query, once per
     * subject, in a ranked loop. That is the exact work `Prepared` exists to avoid.
     */
    @Test fun `score derives its terms once per query, not once per subject`() {
        val recording = RecordingScorer()
        val matcher = Matcher(registry, recording)
        val parsed = parse("gradle cache")
        val docs = (1..25).map { doc("d$it", title = "gradle build cache $it") }

        for (d in docs) assertNotNull(matcher.score(Conversation(), d, parsed, ctx))

        assertEquals(docs.size, recording.seen.size)
        assertEquals(listOf("gradle", "cache"), recording.seen.first())
        val distinctInstances = recording.seen.distinctBy { System.identityHashCode(it) }.size
        assertEquals(
            "expected one memoized term list across ${docs.size} subjects, got $distinctInstances",
            1,
            distinctInstances,
        )
    }

    @Test fun `a facet-only query never asks the scorer for terms it does not have`() {
        val recording = RecordingScorer()
        val matcher = Matcher(registry, recording)
        val parsed = parse("is:starred")

        // A genuine match with nothing to rank it by: score 0.0, not null, and not a scorer call.
        val hit = matcher.score(Conversation(starred = true), doc("d", title = "anything"), parsed, ctx)
        assertNotNull(hit)
        assertEquals(0.0, hit!!.score, 0.0)
        assertTrue("the scorer must not be consulted when there are no terms", recording.seen.isEmpty())

        assertNull(matcher.score(Conversation(starred = false), doc("d", title = "anything"), parsed, ctx))
    }

    @Test fun `an empty query matches everything and ranks nothing`() {
        val matcher = Matcher(registry, scorer)
        val hit = matcher.score(Conversation(), doc("d", title = "gradle"), parse("   "), ctx)
        assertNotNull(hit)
        assertEquals(0.0, hit!!.score, 0.0)
    }

    /** The contract [Matcher.score] states outright: `score(...) != null` iff `matches(...)`. */
    @Test fun `score and matches agree on every shape of query`() {
        val matcher = Matcher(registry, scorer)
        val docs = listOf(
            doc("a", title = "gradle"),
            doc("b", title = "maven"),
            doc("c", body = "gradle build cache"),
        )
        val queries = listOf(
            "gradle", "-gradle", "is:starred", "gradle is:starred", "   ",
            "(gradle OR maven)", "\"build cache\"", "/gradl\\w+/", "?gradle", "turns:>0",
        )
        for (query in queries) {
            val parsed = parse(query)
            for (d in docs) {
                val subject = Conversation(starred = d.id == DocId("a"), turnCount = 3)
                assertEquals(
                    "score/matches disagree for '$query' on ${d.id}",
                    matcher.matches(subject, d, parsed, ctx),
                    matcher.score(subject, d, parsed, ctx) != null,
                )
            }
        }
    }

    // ---- the fixes above, seen from the entry point a host actually filters through ----

    @Test fun `the two spellings of an unbacked exclusion agree here too`() {
        val matcher = Matcher(registry, scorer)
        val d = doc("d", title = "gradle")
        for (subject in listOf(Conversation(), Conversation(hasImage = true), Conversation(hasCode = true))) {
            assertTrue(matcher.matches(subject, d, parse("has:!=video"), ctx))
            assertEquals(
                "the two spellings disagree for $subject",
                matcher.matches(subject, d, parse("-has:video"), ctx),
                matcher.matches(subject, d, parse("has:!=video"), ctx),
            )
        }
    }

    @Test fun `a query typed in Turkish capitals matches text folded at index time`() {
        // The whole pipeline for the hazard NormalizerTest pins at the folding level: a document
        // indexed by a `tr` host, filtered by a query this Matcher folds. There is no longer a
        // locale on EvalContext for the two to disagree about.
        val matcher = Matcher(registry, scorer)
        val indexed = Segmenter.tokenizeForIndex(
            "IŞIK Istanbul",
            Locale.forLanguageTag("tr"),
            Segmenter.JavaTextBoundarySource,
        )
        val d = SearchDoc(DocId("tr"), TestVocabulary.NOW_MILLIS, mapOf(BODY to indexed))

        assertTrue(matcher.matches(Conversation(), d, parse("IŞIK"), ctx))
        assertTrue(matcher.matches(Conversation(), d, parse("Istanbul"), ctx))
        assertTrue(matcher.matches(Conversation(), d, parse("işik"), ctx))
    }

    // ---- the two properties that separate this class from FacetEvaluator ----

    @Test fun `negation is plain here, unlike FacetEvaluator's guarded one`() {
        val matcher = Matcher(registry, scorer)
        val gradle = doc("g", title = "gradle notes")
        // `-maven` is neutral in FacetEvaluator (the lexical pass owns it) and a real predicate
        // here, because this class evaluates both halves in one walk.
        assertTrue(matcher.matches(Conversation(), gradle, parse("-maven"), ctx))
        assertTrue(!matcher.matches(Conversation(), gradle, parse("-gradle"), ctx))
        assertTrue(FacetEvaluator.matches(Conversation(), parse("-gradle").root, registry, ctx))
    }

    @Test fun `baseFilter is evaluated first and is out of reach of anything the user types`() {
        val matcher = Matcher(registry, scorer, baseFilter = { it.archived.not() })
        val d = doc("d", title = "gradle")
        assertTrue(matcher.matches(Conversation(), d, parse("gradle"), ctx))
        // No query can turn it off — not by negating it, not by OR-ing around it.
        for (query in listOf("gradle", "-is:archived", "is:archived", "(gradle OR is:archived)")) {
            assertTrue(
                "'$query' must not reach past baseFilter",
                !matcher.matches(Conversation(archived = true), d, parse(query), ctx),
            )
        }
    }
}
