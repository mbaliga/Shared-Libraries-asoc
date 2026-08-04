package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folding and match-location half of the original `LexicalSearchTest` — `normalize`,
 * `tokenizeQuery` and `findMatches`, which lived on the `LexicalSearch` object there and live on
 * [Normalizer] here.
 *
 * We do not assert on script-specific *rendering*; for the multilingual cases we assert that a
 * substring is **found** (non-empty ranges) and, in the ranking suites, that a matching document
 * outranks a non-matching one.
 *
 * ## The one deliberate behaviour change pinned here
 *
 * The original `findMatches` had a documented escape hatch: when NFKC changed a string's length it
 * abandoned original coordinates and returned ranges over the *normalized* text instead. The port
 * replaces that with a real offset map, so ranges are now always in original coordinates — see
 * [Highlight]. The `ligature before the match` tests below are new; under the original engine they
 * would have failed, which is the point of adding them.
 */
class NormalizerTest {

    // ---- normalize ----

    @Test fun `normalize folds case and collapses whitespace`() {
        assertEquals("hello world", Normalizer.normalize("  HELLO   World  "))
    }

    @Test fun `normalize keeps non-ascii devanagari intact`() {
        val s = "नमस्ते"
        // Script-aware: not stripped, and stable under normalize.
        assertEquals(Normalizer.normalize(s), Normalizer.normalize(s))
        assertTrue(Normalizer.normalize("  $s  ").contains("नमस्ते"))
    }

    @Test fun `normalize folds compatibility forms (fullwidth digits)`() {
        // NFKC maps full-width digits to ASCII.
        assertEquals("123", Normalizer.normalize("１２３"))
    }

    @Test fun `normalize is idempotent`() {
        // Load-bearing for hosts with a persistent index: CoverageRecencyScorer normalizes field
        // text on every call, so a host that already stores normalized text must not be corrupted
        // by the second pass.
        val once = Normalizer.normalize("  Café  ＲÉSUMÉ  ﬁle ")
        assertEquals(once, Normalizer.normalize(once))
    }

    // ---- tokenizeQuery ----

    @Test fun `tokenizeQuery splits and drops blanks`() {
        assertEquals(listOf("alpha", "beta"), Normalizer.tokenizeQuery("  Alpha   BETA "))
    }

    @Test fun `tokenizeQuery of blank is empty`() {
        assertTrue(Normalizer.tokenizeQuery("    ").isEmpty())
        assertTrue(Normalizer.tokenizeQuery("").isEmpty())
    }

    // ---- findMatches ----

    @Test fun `findMatches returns case-insensitive original ranges`() {
        val text = "The Quick Brown Fox"
        val ranges = Normalizer.findMatches(text, listOf("quick"))
        assertEquals(1, ranges.size)
        // "Quick" begins at index 4 in the ORIGINAL text.
        assertEquals(4, ranges[0].first)
        assertEquals("Quick", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test fun `findMatches finds devanagari substring`() {
        val text = "मैंने कल नमस्ते कहा"
        val ranges = Normalizer.findMatches(text, Normalizer.tokenizeQuery("नमस्ते"))
        assertTrue("expected a Devanagari match", ranges.isNotEmpty())
    }

    @Test fun `findMatches finds japanese substring`() {
        val text = "今日はこんにちは世界"
        val ranges = Normalizer.findMatches(text, Normalizer.tokenizeQuery("こんにちは"))
        assertTrue("expected a Japanese match", ranges.isNotEmpty())
    }

    @Test fun `findMatches merges adjacent ranges`() {
        val ranges = Normalizer.findMatches("aaaa", listOf("aa"))
        // Overlapping/adjacent occurrences collapse into one span.
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].first)
        assertEquals(3, ranges[0].last)
    }

    @Test fun `findMatches empty when no match`() {
        assertTrue(Normalizer.findMatches("hello", listOf("zzz")).isEmpty())
    }

    // ---- original coordinates when NFKC changes length (new; the documented fix) ----

    @Test fun `ligature before the match does not shift the reported range`() {
        // U+FB01 LATIN SMALL LIGATURE FI folds to two characters, so every normalized index after
        // it is one greater than the original index. The match must still be reported where the
        // text actually is.
        val text = "the ﬁle is cached"
        val ranges = Normalizer.findMatches(text, listOf("cache"))
        assertEquals(1, ranges.size)
        assertEquals("cache", text.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals(11..15, ranges[0])
    }

    @Test fun `a match landing on the ligature itself covers the single source character`() {
        val text = "the ﬁle is cached"
        val ranges = Normalizer.findMatches(text, listOf("fi"))
        assertEquals(1, ranges.size)
        // Both normalized characters map back onto the one original ligature.
        assertEquals("ﬁ", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test fun `halfwidth katakana pair reports the two source characters it folded from`() {
        // U+FF76 + U+FF9E (halfwidth KA + voiced sound mark) -> ガ, two characters becoming one.
        val text = "ｶﾞ wide form"
        val ranges = Normalizer.findMatches(text, listOf("ガ"))
        assertEquals(1, ranges.size)
        assertEquals("ｶﾞ", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test fun `fullwidth run before the match keeps original coordinates`() {
        // Length is preserved here (each full-width form folds 1:1) but the string is not
        // NFKC-normalized, so this exercises the offset map rather than the fast path.
        val text = "Ａ１２３ wide"
        val ranges = Normalizer.findMatches(text, listOf("wide"))
        assertEquals(1, ranges.size)
        assertEquals("wide", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test fun `collapsed whitespace run maps back onto every space it swallowed`() {
        val text = "alpha     beta"
        val ranges = Normalizer.findMatches(text, listOf("alpha beta"))
        assertEquals(1, ranges.size)
        // The single normalized space stands for all five original ones, so the span covers them.
        assertEquals(text, text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test fun `every reported range is in bounds of the original text`() {
        // The property the escape hatch broke: a range must index the string it was asked about.
        val corpus = listOf(
            "the ﬁle is cached", "ｶﾞ wide form", "Ａ１２３ wide", "item ① done",
            "नमस्ते दुनिया", "東京駅で会った", "  spaced   out  ", "Café RÉSUMÉ",
        )
        val terms = listOf("file", "wide", "1", "ガ", "नमस्ते", "東京", "spaced", "cafe", "a")
        for (text in corpus) {
            for (range in Normalizer.findMatches(text, terms)) {
                assertTrue("range $range out of bounds for '$text'", range.first >= 0)
                assertTrue("range $range out of bounds for '$text'", range.last < text.length)
                assertTrue("range $range inverted for '$text'", range.first <= range.last)
            }
        }
    }
}
