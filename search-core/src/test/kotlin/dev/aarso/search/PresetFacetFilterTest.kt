package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Android-IDE-core's `ConversationFacetFilterTest` — the truth table for a **second,
 * narrower subject shape** and the preset queries evaluated against it.
 *
 * ## What happened to `ConversationFacetFilter`
 *
 * It is not ported, and its absence is the point. Upstream, that app had two subject shapes — the
 * indexed `FacetSubject` and the tree-layer `Conversations.Summary` that the chats list already
 * rendered from — and one evaluator that could only speak to the first. So a *second* evaluator was
 * written for the second shape, with its own `when` over the same `Field` enum, its own narrower
 * backed/unbacked opinion, and its own copy of the boolean walk.
 *
 * Two evaluators over one grammar is two truth tables that must agree and cannot be made to. They
 * had already diverged: the main evaluator guarded negation (`Not` inverts only a subtree it has an
 * opinion about) and the narrow one did not (`is QueryNode.Not -> !matches(...)`, unguarded). See
 * [negation over a lexical subtree is neutral here, unlike the narrower upstream evaluator].
 *
 * A [FieldRegistry] over the second shape expresses the whole thing, with no second evaluator and
 * no second truth table to drift — which is what this file demonstrates. Every assertion below is
 * the original's, routed through the one [FacetEvaluator] with [summaryRegistry] instead of through
 * a bespoke function.
 */
class PresetFacetFilterTest {

    /**
     * The tree-layer summary. Deliberately *not* [Conversation]: it has no `archived`, no `hasCode`
     * and no counts, because that projection genuinely did not carry them — which is exactly why
     * `is:archived` must be unbacked here while being backed in [TestVocabulary].
     */
    private data class Summary(
        val id: String,
        val hasImage: Boolean = false,
        val starred: Boolean = false,
        val projectId: String? = null,
    )

    /**
     * The narrow vocabulary: `is:starred`, `is:orphan`, `has:image` and nothing else. Note
     * `starred` and `projectId` sit on the subject here even though upstream they were separate
     * parameters joined by the caller — the join is the host's business, and a [FacetField] reads
     * whatever the host hands it.
     */
    private val summaryRegistry: FieldRegistry<Summary> = FieldRegistry(
        listOf(
            TestFacet<Summary>(
                key = "is",
                valueKind = FacetValueKind.ENUM,
                backed = { it.lowercase() in setOf("starred", "orphan") },
                match = { s, _, v, _ ->
                    when (v.lowercase()) {
                        "starred" -> s.starred
                        "orphan" -> s.projectId == null
                        else -> false
                    }
                },
            ),
            TestFacet<Summary>(
                key = "has",
                valueKind = FacetValueKind.ENUM,
                backed = { it.lowercase() == "image" },
                match = { s, _, v, _ -> v.lowercase() == "image" && s.hasImage },
            ),
        ),
    )

    /** The chats room's five tabs, re-expressed as saved facet-query presets. */
    private enum class Preset(val query: String) {
        ALL(""),
        TEXT("-has:image"),
        IMAGE("has:image"),
        STARRED("is:starred"),
        PROJECTS("-is:orphan"),
    }

    private val ctx = TestVocabulary.CTX

    private fun summary(id: String, hasImage: Boolean = false) = Summary(id, hasImage)

    private fun parse(query: String) = QueryParser.parse(query, summaryRegistry, ctx, TestVocabulary.ZONE)

    private fun matches(
        node: QueryNode?,
        starred: Boolean = false,
        projectId: String? = null,
        hasImage: Boolean = false,
    ) = FacetEvaluator.matches(
        Summary("c1", hasImage = hasImage, starred = starred, projectId = projectId),
        node,
        summaryRegistry,
        ctx,
    )

    @Test fun `null node always matches`() {
        assertTrue(matches(null))
    }

    @Test fun `is starred matches only starred conversations`() {
        val node = parse("is:starred").root
        assertTrue(matches(node, starred = true))
        assertFalse(matches(node, starred = false))
    }

    @Test fun `is orphan matches only conversations with no project`() {
        val node = parse("is:orphan").root
        assertTrue(matches(node, projectId = null))
        assertFalse(matches(node, projectId = "Aarso"))
    }

    @Test fun `has image matches only image conversations`() {
        val node = parse("has:image").root
        assertTrue(matches(node, hasImage = true))
        assertFalse(matches(node, hasImage = false))
    }

    @Test fun `negated has image is the TEXT preset`() {
        val node = parse("-has:image").root
        assertTrue(matches(node, hasImage = false))
        assertFalse(matches(node, hasImage = true))
    }

    @Test fun `negated is orphan is the PROJECTS preset`() {
        val node = parse("-is:orphan").root
        assertTrue(matches(node, projectId = "Aarso"))
        assertFalse(matches(node, projectId = null))
    }

    @Test fun `unbacked is value never matches`() {
        // `is:archived` is real in TestVocabulary and unbacked here: this projection does not carry
        // it. Same key, same grammar, a different honest answer per registry.
        val node = parse("is:archived").root
        assertFalse(matches(node))
    }

    // ---- every preset parses, and behaves as documented ----

    @Test fun `every preset query parses without diagnostics that would break evaluation`() {
        for (preset in Preset.entries) {
            val parsed = parse(preset.query)
            // ALL's blank query is the one exception with no root; everything else must parse to a
            // real node for the filter to do anything.
            if (preset != Preset.ALL) {
                assertTrue("expected a root for preset '${preset.name}' ('${preset.query}')", parsed.root != null)
            }
        }
    }

    @Test fun `ALL preset matches everything`() {
        val node = parse(Preset.ALL.query).root
        assertTrue(matches(node, starred = false, projectId = null, hasImage = false))
        assertTrue(matches(node, starred = true, projectId = "x", hasImage = true))
    }

    @Test fun `TEXT preset matches exactly conversations without images`() {
        val node = parse(Preset.TEXT.query).root
        assertTrue(matches(node, hasImage = false))
        assertFalse(matches(node, hasImage = true))
    }

    @Test fun `STARRED preset matches exactly starred conversations`() {
        val node = parse(Preset.STARRED.query).root
        assertTrue(matches(node, starred = true))
        assertFalse(matches(node, starred = false))
    }

    @Test fun `filtering a mixed list with TEXT preset behaves like the legacy hasImage filter`() {
        val list = listOf(summary("a", hasImage = false), summary("b", hasImage = true), summary("c", hasImage = false))
        val node = parse(Preset.TEXT.query).root
        val viaPreset = list.filter { FacetEvaluator.matches(it, node, summaryRegistry, ctx) }
        val legacy = list.filter { !it.hasImage }
        assertEquals(legacy.map { it.id }, viaPreset.map { it.id })
    }

    @Test fun `filtering with STARRED preset behaves like the legacy Bookmarks filter`() {
        val bookmarked = setOf("b")
        val list = listOf("a", "b", "c").map { Summary(it, starred = it in bookmarked) }
        val node = parse(Preset.STARRED.query).root
        val viaPreset = list.filter { FacetEvaluator.matches(it, node, summaryRegistry, ctx) }
        val legacy = list.filter { it.id in bookmarked }
        assertEquals(legacy.map { it.id }, viaPreset.map { it.id })
    }

    // ---- the divergence the merge removes ----

    /**
     * **A deliberate behaviour change, pinned.**
     *
     * The narrower upstream evaluator negated unconditionally: `is QueryNode.Not -> !matches(...)`.
     * Its lexical leaves returned `true` (no in-memory equivalent — "matching free text is the FTS
     * index's job"), so a `Not` over one of them inverted that neutral `true` into `false` and hid
     * **every** conversation. `-has:image` was safe; `-draft` emptied the list.
     *
     * The main evaluator already guarded this, and routing both shapes through it is what makes the
     * guard apply to both. So this test asserts the *opposite* of what the upstream narrow filter
     * did, on purpose: a bare negated word is neutral, and a negated facet still excludes.
     */
    @Test fun `negation over a lexical subtree is neutral here, unlike the narrower upstream evaluator`() {
        assertTrue(matches(parse("-draft").root))
        assertTrue(matches(parse("draft -maven").root))
        // The guard is scoped: a Not that *does* contain a facet still inverts.
        assertFalse(matches(parse("-has:image").root, hasImage = true))
        // And a mixed subtree inverts on the facet it contains, not on the neutral text.
        assertTrue(matches(parse("-has:image draft").root, hasImage = false))
        assertFalse(matches(parse("-has:image draft").root, hasImage = true))
    }

    /**
     * One registry per subject shape, one evaluator for both — the property that removes the second
     * truth table. The same query text means the same thing in each; only what the host can answer
     * about differs.
     */
    @Test fun `the same query text routes through either registry with no second evaluator`() {
        val starredSummary = Summary("s", starred = true)
        val starredConversation = Conversation(starred = true)

        val summaryNode = parse("is:starred").root
        val conversationNode = QueryParser.parse(
            "is:starred", TestVocabulary.registry, ctx, TestVocabulary.ZONE,
        ).root

        assertEquals(summaryNode, conversationNode)
        assertTrue(FacetEvaluator.matches(starredSummary, summaryNode, summaryRegistry, ctx))
        assertTrue(FacetEvaluator.matches(starredConversation, conversationNode, TestVocabulary.registry, ctx))
    }
}
