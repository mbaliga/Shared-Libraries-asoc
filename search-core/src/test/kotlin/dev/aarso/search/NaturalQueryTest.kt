package dev.aarso.search

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NaturalQuery] delegates to [QueryParser.parse] after rewriting, so most of these assertions
 * compare interpreting a natural-language string against parsing its hand-written canonical
 * equivalent directly ([assertInterpretsSameAs]) — the two are supposed to be indistinguishable to
 * every downstream consumer, and that comparison is the most direct way to say so.
 *
 * The registry below stands in for a Fylz-shaped host: a `type` facet (aliased `kind`), a
 * `modified` date facet (aliased `date`/`when`) and a `size` number facet — the three keys
 * [NaturalQuery] hardcodes. `size`/`type` [isBacked] answers stay unconditionally true; whether a
 * value is actually indexed is a host concern this file has nothing to say about.
 */
class NaturalQueryTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // Thursday, so "this week" and "last week" land on distinct, unambiguous Monday boundaries.
    private val now: Long = ZonedDateTime.of(2026, 7, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val ctx = EvalContext(nowMillis = now)

    private val registry: FieldRegistry<Any?> = FieldRegistry(
        listOf(
            TestFacet<Any?>(key = "type", valueKind = FacetValueKind.ENUM, aliases = setOf("kind")),
            TestFacet<Any?>(key = "modified", valueKind = FacetValueKind.DATE, aliases = setOf("date", "when")),
            TestFacet<Any?>(key = "size", valueKind = FacetValueKind.NUMBER),
        ),
    )

    private val vocabulary = NaturalVocabulary(
        kinds = mapOf(
            "photos" to "image", "pictures" to "image", "images" to "image",
            "videos" to "video", "movies" to "video", "clips" to "video",
            "songs" to "audio", "music" to "audio", "audio" to "audio",
            "pdfs" to "pdf",
            "docs" to "document", "documents" to "document",
            "archives" to "archive", "zips" to "archive",
            "folders" to "folder", "directories" to "folder",
            "screenshots" to "screenshot",
        ),
    )

    private fun interpret(text: String) = NaturalQuery.interpret(text, vocabulary, registry, ctx, zone)

    private fun canonical(text: String) = QueryParser.parse(text, registry, ctx, zone)

    /** The main contract: a natural-language string and its hand-written structured equivalent
     *  must parse to the same tree — same facets, same terms, same nesting. */
    private fun assertInterpretsSameAs(naturalText: String, canonicalText: String) {
        assertEquals(canonical(canonicalText).root, interpret(naturalText).root)
    }

    // ---- kind nouns ----

    @Test fun `a kind noun becomes a type facet`() {
        assertInterpretsSameAs("photos", "type:image")
        assertInterpretsSameAs("movies", "type:video")
        assertInterpretsSameAs("pdfs", "type:pdf")
    }

    @Test fun `kind synonyms resolve to the same facet value`() {
        assertEquals(interpret("photos").root, interpret("pictures").root)
        assertEquals(interpret("photos").root, interpret("images").root)
    }

    @Test fun `kind nouns match singular and plural via the naive s strip`() {
        assertInterpretsSameAs("photo", "type:image")
        assertInterpretsSameAs("photos", "type:image")
        assertInterpretsSameAs("video", "type:video")
    }

    @Test fun `kind noun matching is case-insensitive`() {
        assertInterpretsSameAs("PHOTOS", "type:image")
        assertInterpretsSameAs("Screenshots", "type:screenshot")
    }

    @Test fun `a word not in the vocabulary is left as a plain term`() {
        val q = interpret("cats")
        assertEquals(QueryNode.Term("cats", false), q.root)
        assertTrue(q.facets.isEmpty())
    }

    // ---- date phrases ----

    @Test fun `today and yesterday become modified facets`() {
        assertInterpretsSameAs("today", "modified:today")
        assertInterpretsSameAs("yesterday", "modified:yesterday")
    }

    @Test fun `this and last combine with week, month or year`() {
        assertInterpretsSameAs("this week", "modified:this-week")
        assertInterpretsSameAs("last week", "modified:last-week")
        assertInterpretsSameAs("this month", "modified:this-month")
        assertInterpretsSameAs("last month", "modified:last-month")
        assertInterpretsSameAs("this year", "modified:this-year")
        assertInterpretsSameAs("last year", "modified:last-year")
    }

    @Test fun `since a month is a GTE modified facet`() {
        assertInterpretsSameAs("since october", "modified:>=october")
    }

    @Test fun `before a month is an LT modified facet`() {
        assertInterpretsSameAs("before october", "modified:<october")
    }

    @Test fun `from or in a month is a bare modified facet`() {
        assertInterpretsSameAs("from october", "modified:october")
        assertInterpretsSameAs("in october", "modified:october")
    }

    @Test fun `a bare year is a modified facet on its own`() {
        assertInterpretsSameAs("in 2024", "modified:2024")
    }

    @Test fun `report 2024 is a date facet plus a leftover term`() {
        // Named in the design: the year is recognized even with no preposition in front of it,
        // and the unrelated word beside it is not swallowed.
        assertInterpretsSameAs("report 2024", "report modified:2024")
        val q = interpret("report 2024")
        assertEquals(listOf(QueryNode.Facet("modified", Op.EQ, "2024")), q.facets)
    }

    @Test fun `a preposition combines with a month and an explicit trailing year`() {
        assertInterpretsSameAs("since january 2024", "modified:>=january-2024")
    }

    // ---- size ----

    @Test fun `bare size words become fixed size facets`() {
        assertInterpretsSameAs("large", "size:>25mb")
        assertInterpretsSameAs("huge", "size:>100mb")
        assertInterpretsSameAs("small", "size:<1mb")
        assertInterpretsSameAs("tiny", "size:<100kb")
    }

    @Test fun `over and above are GT size comparisons`() {
        assertInterpretsSameAs("over 10mb", "size:>10mb")
        assertInterpretsSameAs("above 10 mb", "size:>10mb")
    }

    @Test fun `under and below are LT size comparisons`() {
        assertInterpretsSameAs("under 500kb", "size:<500kb")
        assertInterpretsSameAs("below 500 kb", "size:<500kb")
    }

    @Test fun `bigger than and smaller than are two-word comparators`() {
        assertInterpretsSameAs("bigger than 10 mb", "size:>10mb")
        assertInterpretsSameAs("smaller than 2gb", "size:<2gb")
    }

    @Test fun `a size comparator with no following unit is not recognized`() {
        // The grammar is always "amount and unit" — a bare number reads as a count, not a size.
        // (Not a 4-digit number: that would independently match the bare-year date fragment below.)
        val q = interpret("over 50")
        assertEquals("over 50", q.rawText)
        assertTrue(q.facets.isEmpty())
    }

    // ---- combined natural-language queries ----

    @Test fun `photos from last week combines a kind and a date`() {
        assertInterpretsSameAs("photos from last week", "type:image modified:last-week")
    }

    @Test fun `large videos combines a size word and a kind`() {
        assertInterpretsSameAs("large videos", "size:>25mb type:video")
    }

    @Test fun `pdfs I downloaded yesterday leaves the unknown verb as a term`() {
        assertInterpretsSameAs("pdfs I downloaded yesterday", "type:pdf I downloaded modified:yesterday")
    }

    // ---- filler words ----

    @Test fun `filler words adjacent to a recognized fragment are dropped`() {
        assertInterpretsSameAs("show me pdfs", "type:pdf")
        assertInterpretsSameAs("find my documents", "type:document")
        assertInterpretsSameAs("files from october", "modified:october")
    }

    @Test fun `a filler word with nothing recognized beside it stays a term`() {
        val q = interpret("show reports")
        assertEquals("show reports", q.rawText)
        assertEquals(QueryNode.And(listOf(QueryNode.Term("show", false), QueryNode.Term("reports", false))), q.root)
    }

    // ---- structured input and purity ----

    @Test fun `already-structured facets pass through with the natural-language part still interpreted`() {
        assertInterpretsSameAs("ext:pdf photos from last week", "ext:pdf type:image modified:last-week")
    }

    @Test fun `a fully structured query is not rewritten at all`() {
        val text = "ext:pdf size:>10mb -has:starred"
        assertEquals(text, interpret(text).rawText)
    }

    @Test fun `a quoted phrase is never treated as natural language`() {
        val text = "\"large videos\""
        val q = interpret(text)
        assertEquals(text, q.rawText)
        assertEquals(QueryNode.Phrase("large videos"), q.root)
    }

    @Test fun `unrecognized input is passed through unchanged`() {
        val text = "asdkjaskjd qwerty"
        assertEquals(text, interpret(text).rawText)
    }

    @Test fun `a negated word is left for the grammar to negate, not reinterpreted`() {
        val q = interpret("-videos")
        assertEquals("-videos", q.rawText)
        assertEquals(QueryNode.Not(QueryNode.Term("videos", false)), q.root)
    }

    @Test fun `an empty or blank query interprets to no constraint at all`() {
        assertEquals(null, interpret("").root)
        assertEquals(null, interpret("   ").root)
    }

    @Test fun `interpretation is visible through chips like any other parse`() {
        val q = interpret("photos from last week")
        assertEquals(2, q.chips.size)
        assertEquals(ChipKind.FACET, q.chips[0].kind)
        assertEquals("type", q.chips[0].key)
        assertEquals(ChipKind.FACET, q.chips[1].kind)
        assertEquals("modified", q.chips[1].key)
    }
}
