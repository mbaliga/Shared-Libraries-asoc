package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Android-IDE-core's `LexicalTextTest`.
 *
 * [QueryCompiler.lexicalText] backs two things that must never see facet syntax: relevance
 * re-scoring, and the text handed to a find bar when a search result is opened. Handing
 * `starred:true` to a lexical [Scorer] would rank documents for containing the words "starred"
 * and "true".
 *
 * The second half of this file is new. The port added [QueryCompiler.lexicalTerms] because
 * returning raw substrings from a function named "the text a scorer wants" was a footgun:
 * [CoverageRecencyScorer] matches with `normalize(fieldText).contains(term)`, so an un-normalized
 * term silently fails to match anything the moment the user types a capital letter — as a zero
 * score, not an error. There is now one way to get scorer-ready terms, and these tests are what
 * keep it correct.
 */
class LexicalTextTest {

    private val registry = TestVocabulary.registry

    private fun root(query: String) =
        QueryParser.parse(query, registry, TestVocabulary.CTX, TestVocabulary.ZONE).root

    private fun lexical(query: String) = QueryCompiler.lexicalText(root(query))

    private fun terms(query: String) = QueryCompiler.lexicalTerms(root(query))

    @Test fun `plain terms pass through`() {
        assertEquals("gradle build", lexical("gradle build"))
    }

    @Test fun `facets are stripped`() {
        assertEquals("gradle", lexical("gradle is:starred"))
        assertEquals("gradle", lexical("is:starred gradle -is:archived"))
    }

    @Test fun `a facet-only query has no text to find`() {
        assertEquals("", lexical("is:starred after:-7d"))
    }

    @Test fun `a phrase contributes its words`() {
        assertEquals("build cache", lexical("\"build cache\""))
    }

    @Test fun `regex is stripped — a pattern is not text to highlight`() {
        assertEquals("gradle", lexical("gradle /dr\\w+/"))
        assertEquals("", lexical("/dr\\w+/"))
    }

    @Test fun `a negated term is excluded — it is a thing to avoid, not to find`() {
        assertEquals("gradle", lexical("gradle -maven"))
    }

    @Test fun `semantic text is kept as its plain-lexical fallback`() {
        assertEquals("gradle", lexical("?gradle"))
    }

    @Test fun `OR branches both contribute`() {
        assertEquals("gradle maven", lexical("(gradle OR maven)"))
    }

    @Test fun `an empty query yields empty text`() {
        assertEquals("", lexical("   "))
    }

    // ---- lexicalTerms: the scorer-ready shape ----

    @Test fun `lexicalTerms normalizes, so a capitalised query still matches documents`() {
        assertEquals(listOf("gradle", "cafe"), terms("Gradle CAFE"))
        // The property that matters: what comes out is what the scorer's substring test expects.
        val doc = SearchDoc(
            id = DocId("d"),
            timestampMillis = 0L,
            text = mapOf(FieldName("body") to "gradle cafe notes"),
        )
        val scorer = CoverageRecencyScorer(fieldWeights = emptyMap())
        assertTrue(scorer.score(doc, terms("Gradle CAFE"), EvalContext(nowMillis = 0L)) != null)
    }

    @Test fun `lexicalTerms splits a phrase into its words`() {
        // A phrase is checked for contiguity by the filter; the scorer's job is coverage, and a
        // two-word phrase legitimately counts for two.
        assertEquals(listOf("build", "cache"), terms("\"build cache\""))
    }

    @Test fun `lexicalTerms of a facet-only query is empty, not a list of facet words`() {
        assertTrue(terms("is:starred after:-7d").isEmpty())
    }

    @Test fun `lexicalTerms folds a fullwidth query onto its ascii form`() {
        assertEquals(listOf("123"), terms("１２３"))
    }
}
