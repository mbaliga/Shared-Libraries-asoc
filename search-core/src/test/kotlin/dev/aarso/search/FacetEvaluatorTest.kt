package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Android-IDE-core's `FacetEvaluatorTest`.
 *
 * The original evaluated against a fixed ten-field `FacetSubject` struct with a `when` over the
 * closed `Field` enum — `Field.IS` read `subject.starred`, `Field.TURNS` compared
 * `subject.turnCount`, and seven of the eighteen fields were hard-coded to `false`. Every one of
 * those branches is an app-specific fact, so the whole function was unusable by a second host.
 *
 * Here the subject is a type parameter and each per-field decision lives on [FacetField.matches],
 * which the host implements — see [TestVocabulary], which rebuilds exactly that vocabulary. What
 * remains under test in this file is what is genuinely *not* app-specific and what every host would
 * otherwise have to rediscover: the boolean walk, the negation guard, the neutral-lexical-leaf
 * rule, the unbacked-value zero, and range decomposition.
 *
 * Every assertion below is the original's, with `Field.X` replaced by the string key and the
 * `(nowMillis, zone)` pair replaced by an [EvalContext] plus a zone the date fields close over.
 */
class FacetEvaluatorTest {

    private val registry = TestVocabulary.registry
    private val ctx = TestVocabulary.CTX
    private val now = TestVocabulary.NOW_MILLIS
    private val day = TestVocabulary.DAY_MILLIS

    private fun subject(
        starred: Boolean = false,
        archived: Boolean = false,
        projectId: String? = null,
        modelIds: List<String> = emptyList(),
        turnCount: Long = 0,
        branchCount: Long = 0,
        hasImage: Boolean = false,
        hasCode: Boolean = false,
        costMinor: Long = 0,
        updatedAtMillis: Long = now,
    ) = Conversation(
        starred, archived, projectId, modelIds, turnCount, branchCount,
        hasImage, hasCode, costMinor, updatedAtMillis,
    )

    private fun parse(query: String) =
        QueryParser.parse(query, registry, ctx, TestVocabulary.ZONE)

    private fun matches(query: String, subject: Conversation) =
        FacetEvaluator.matches(subject, parse(query).root, registry, ctx)

    // ---- is: / has: ----

    @Test fun `is starred`() {
        assertTrue(matches("is:starred", subject(starred = true)))
        assertFalse(matches("is:starred", subject(starred = false)))
    }

    @Test fun `is archived`() {
        assertTrue(matches("is:archived", subject(archived = true)))
        assertFalse(matches("is:archived", subject(archived = false)))
    }

    @Test fun `is orphan means no project`() {
        assertTrue(matches("is:orphan", subject(projectId = null)))
        assertTrue(matches("is:orphan", subject(projectId = "  ")))
        assertFalse(matches("is:orphan", subject(projectId = "Aarso")))
    }

    @Test fun `has image and has code`() {
        assertTrue(matches("has:image", subject(hasImage = true)))
        assertFalse(matches("has:image", subject(hasCode = true)))
        assertTrue(matches("has:code", subject(hasCode = true)))
    }

    @Test fun `an unbacked facet matches nothing rather than everything`() {
        assertFalse(matches("tool:bash", subject(starred = true)))
        assertFalse(matches("is:unread", subject()))
        assertFalse(matches("has:artifact", subject()))
    }

    @Test fun `a facet whose key this registry does not know is false, never a silent pass`() {
        // A tree built against a *different* registry, or by hand. Matching everything here would
        // turn a typo into "no filter at all".
        val alien = QueryNode.Facet("ext", Op.EQ, "pdf")
        assertFalse(FacetEvaluator.matches(subject(starred = true), alien, registry, ctx))
    }

    // ---- the unbacked short-circuit is signed, not absolute ----

    /**
     * **A defect fixed in the port, pinned here.** The unbacked short-circuit returned `false` for
     * every operator. That is only sound for the positive ones: if nothing indexed carries `video`,
     * then `has:video` is universally false and `has:!=video` is universally **true**. Returning
     * `false` for both made two spellings of one predicate — `has:!=video` and `-has:video` —
     * disagree with each other, one matching nothing and the other matching everything.
     */
    @Test fun `the two spellings of an unbacked exclusion agree, on every subject`() {
        val subjects = listOf(
            subject(),
            subject(hasImage = true),
            subject(hasCode = true),
            subject(starred = true, archived = true, hasImage = true, hasCode = true),
        )
        for (s in subjects) {
            assertTrue("`has:!=video` must match everything, since nothing carries `video`", matches("has:!=video", s))
            assertEquals(
                "the two spellings disagree for $s",
                matches("-has:video", s),
                matches("has:!=video", s),
            )
        }
    }

    @Test fun `a backed value under != is decided by the host, not short-circuited`() {
        // `turns` is always backed, so the NE carve-out must not reach it: the host's comparison
        // is still the thing that answers.
        assertTrue(matches("turns:!=5", subject(turnCount = 6)))
        assertFalse(matches("turns:!=5", subject(turnCount = 5)))
    }

    @Test fun `an unrecognized key stays false even under !=`() {
        // The carve-out is about an unbacked *value*, never about an unknown *key*. A key this
        // registry cannot resolve has no opinion to invert, and matching everything would still
        // turn a typo into "no filter at all".
        val alien = QueryNode.Facet("ext", Op.NE, "pdf")
        assertFalse(FacetEvaluator.matches(subject(starred = true), alien, registry, ctx))
    }

    // ---- a host that breaks FacetField's no-throw contract degrades, it does not crash ----

    @Test fun `a throwing isBacked is read as backed rather than propagating`() {
        // `size`'s isBacked is `value.toInt()`, which throws on `10mb`. The guard reads that as
        // "backed" and lets the host's own `matches` give the real answer — which is where the
        // answer belonged, and is the same default QueryParser uses so the two cannot disagree.
        val facet = QueryNode.Facet("size", Op.EQ, "10mb")
        assertTrue(FacetEvaluator.matchesFacet(Conversation(), facet, HostileVocabulary.registry, ctx))
    }

    @Test fun `a throwing valueKind skips range decomposition instead of propagating`() {
        // `kind`'s valueKind explodes, so the evaluator cannot know the field is ordered. The
        // documented degrade is "no opinion": `1..5` reaches the host verbatim rather than being
        // split into GTE 1 AND LTE 5, which is exactly what this field's `matches` reports.
        val facet = QueryNode.Facet("kind", Op.EQ, "1..5")
        assertTrue(FacetEvaluator.matchesFacet(Conversation(), facet, HostileVocabulary.registry, ctx))
    }

    @Test fun `both guards together still produce an answer for every operator`() {
        for (op in Op.entries) {
            val facet = QueryNode.Facet("hostile", op, "not-a-number")
            assertTrue(
                "expected the host's own matches() to decide for $op",
                FacetEvaluator.matchesFacet(Conversation(starred = true), facet, HostileVocabulary.registry, ctx),
            )
            assertFalse(
                FacetEvaluator.matchesFacet(Conversation(starred = false), facet, HostileVocabulary.registry, ctx),
            )
        }
    }

    // ---- project / model ----

    @Test fun `project matches case-insensitively`() {
        assertTrue(matches("project:Aarso", subject(projectId = "aarso")))
        assertFalse(matches("project:Aarso", subject(projectId = "Hyle")))
    }

    @Test fun `in is an alias for project`() {
        assertTrue(matches("in:Hyle", subject(projectId = "Hyle")))
    }

    @Test fun `model matches a substring of a namespaced model id`() {
        val s = subject(modelIds = listOf("cloud:anthropic/claude-opus", "local:qwen-7b"))
        assertTrue(matches("model:claude", s))
        assertTrue(matches("model:qwen", s))
        assertFalse(matches("model:gemini", s))
    }

    // ---- dates ----

    @Test fun `after includes today, before excludes it`() {
        val today = subject(updatedAtMillis = now + 3_600_000L)
        assertTrue(matches("after:today", today))
        assertFalse(matches("before:today", today))
    }

    @Test fun `before matches an older conversation`() {
        assertTrue(matches("before:today", subject(updatedAtMillis = now - day)))
    }

    @Test fun `after with a relative offset spans the whole window`() {
        assertTrue(matches("after:-7d", subject(updatedAtMillis = now - 3 * day)))
        assertFalse(matches("after:-7d", subject(updatedAtMillis = now - 30 * day)))
    }

    @Test fun `during matches only the named day`() {
        assertTrue(matches("during:yesterday", subject(updatedAtMillis = now - day)))
        assertFalse(matches("during:yesterday", subject(updatedAtMillis = now)))
    }

    @Test fun `an unparseable date matches nothing rather than throwing`() {
        assertFalse(matches("before:someday", subject()))
    }

    /**
     * **A defect fixed in the port, pinned here.** `during:a..b` returned an unconditional `false`
     * upstream: the whole `a..b` string went to a date resolver that could only fail. A DATE field
     * is now decomposed exactly as a NUMBER field is — `GTE low AND LTE high` — so a date range
     * works for free in any host that implements its comparison operators.
     */
    @Test fun `a date range is decomposed into two comparisons`() {
        val inside = subject(updatedAtMillis = now - 3 * day)
        val before = subject(updatedAtMillis = now - 30 * day)
        val after = subject(updatedAtMillis = now + 3 * day)
        assertTrue(matches("during:2026-07-25..2026-07-31", inside))
        assertFalse(matches("during:2026-07-25..2026-07-31", before))
        assertFalse(matches("during:2026-07-25..2026-07-31", after))
        // Inclusive on both ends: the whole of the closing day counts.
        assertTrue(matches("during:2026-07-27..2026-07-27", subject(updatedAtMillis = now - 3 * day)))
    }

    // ---- numeric comparisons ----

    @Test fun `turns with comparison operators`() {
        assertTrue(matches("turns:>5", subject(turnCount = 6)))
        assertFalse(matches("turns:>5", subject(turnCount = 5)))
        assertTrue(matches("turns:>=5", subject(turnCount = 5)))
        assertTrue(matches("turns:<5", subject(turnCount = 4)))
        assertTrue(matches("turns:<=5", subject(turnCount = 5)))
        assertTrue(matches("turns:5", subject(turnCount = 5)))
    }

    @Test fun `cost range is inclusive on both ends`() {
        assertTrue(matches("cost:10..50", subject(costMinor = 10)))
        assertTrue(matches("cost:10..50", subject(costMinor = 50)))
        assertFalse(matches("cost:10..50", subject(costMinor = 51)))
        assertFalse(matches("cost:10..50", subject(costMinor = 9)))
    }

    @Test fun `branch count compares like the other numerics`() {
        assertTrue(matches("branch:>0", subject(branchCount = 2)))
        assertFalse(matches("branch:>0", subject(branchCount = 0)))
    }

    @Test fun `a non-numeric value matches nothing rather than throwing`() {
        assertFalse(matches("turns:many", subject(turnCount = 9)))
    }

    @Test fun `a dotted range on a TEXT field is a literal value, not an interval`() {
        // `..` is far likelier to be part of a path than a range on a text field, so decomposition
        // is deliberately restricted to NUMBER and DATE. This value reaches the host verbatim.
        assertTrue(matches("project:\"..build\"", subject(projectId = "..build")))
    }

    // ---- boolean composition ----

    @Test fun `lexical leaves are neutral so text plus facet is just the facet here`() {
        assertTrue(matches("gradle is:starred", subject(starred = true)))
        assertFalse(matches("gradle is:starred", subject(starred = false)))
        assertTrue(matches("gradle", subject()))
    }

    @Test fun `negated facet excludes`() {
        assertTrue(matches("-is:archived", subject(archived = false)))
        assertFalse(matches("-is:archived", subject(archived = true)))
    }

    @Test fun `a negated term is neutral here, not a rejection of everything`() {
        // Regression: negation must only apply to subtrees this evaluator has an opinion about.
        // `-maven` is the lexical pass's job; treating it as `!true` here rejected every candidate.
        assertTrue(matches("-maven", subject()))
        assertTrue(matches("gradle -maven", subject()))
        assertTrue(matches("gradle -maven is:starred", subject(starred = true)))
        assertFalse(matches("gradle -maven is:starred", subject(starred = false)))
    }

    @Test fun `a negated phrase is likewise neutral`() {
        assertTrue(matches("-\"build cache\"", subject()))
    }

    @Test fun `AND of facets requires both`() {
        assertTrue(matches("is:starred has:code", subject(starred = true, hasCode = true)))
        assertFalse(matches("is:starred has:code", subject(starred = true, hasCode = false)))
    }

    @Test fun `OR of facets requires either`() {
        assertTrue(matches("(is:starred OR has:code)", subject(hasCode = true)))
        assertTrue(matches("(is:starred OR has:code)", subject(starred = true)))
        assertFalse(matches("(is:starred OR has:code)", subject()))
    }

    @Test fun `nested composition evaluates exactly`() {
        val q = "is:starred (has:code OR has:image) -is:archived"
        assertTrue(matches(q, subject(starred = true, hasCode = true)))
        assertTrue(matches(q, subject(starred = true, hasImage = true)))
        assertFalse(matches(q, subject(starred = true, hasCode = true, archived = true)))
        assertFalse(matches(q, subject(starred = false, hasCode = true)))
    }

    @Test fun `a null root matches everything`() {
        assertTrue(FacetEvaluator.matches(subject(), null as QueryNode?, registry, ctx))
    }

    @Test fun `the ParsedQuery overload agrees with the node overload`() {
        val parsed = parse("is:starred has:code")
        val s = subject(starred = true, hasCode = true)
        assertTrue(FacetEvaluator.matches(s, parsed, registry, ctx))
        assertFalse(FacetEvaluator.matches(subject(starred = true), parsed, registry, ctx))
    }

    // ---- the one documented lossy shape ----

    @Test fun `a facet OR'd with text is flagged as lossy`() {
        assertTrue(FacetEvaluator.hasLossyDisjunction(parse("(gradle OR is:starred)").root))
    }

    @Test fun `a facet AND'd with text is not lossy`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(parse("gradle is:starred").root))
    }

    @Test fun `an all-facet OR is not lossy`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(parse("(is:starred OR has:code)").root))
    }

    @Test fun `an all-text OR is not lossy`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(parse("(gradle OR maven)").root))
    }

    @Test fun `a regex OR'd with a facet is not lossy — a regex is never applied upstream`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(parse("(/dr\\w+/ OR is:starred)").root))
    }

    @Test fun `the documented lossy case evaluates to the facet-neutral answer`() {
        // Pinned, not accidental: text-side TRUE makes the whole OR true, so the facet does not
        // narrow. Too-few rows (the lexical side still constrains), never wrong ones.
        assertTrue(matches("(gradle OR is:starred)", subject(starred = false)))
    }

    @Test fun `containsFacet answers whether running the facet pass would change anything`() {
        assertTrue(FacetEvaluator.containsFacet(parse("gradle is:starred").root))
        assertFalse(FacetEvaluator.containsFacet(parse("gradle maven").root))
        assertFalse(FacetEvaluator.containsFacet(null))
    }
}
