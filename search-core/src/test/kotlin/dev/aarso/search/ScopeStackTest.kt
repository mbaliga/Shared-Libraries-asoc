package dev.aarso.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported unchanged from Android-IDE-core. "Search within results": scopes stack rather than
 * replace, so each level's candidate set is the previous level's result set, and popping is
 * lossless.
 */
class ScopeStackTest {

    private fun q(text: String) =
        QueryParser.parse(text, TestVocabulary.registry, TestVocabulary.CTX, TestVocabulary.ZONE)

    @Test fun `starts empty`() {
        val stack = ScopeStack()
        assertTrue(stack.isEmpty)
        assertEquals(0, stack.depth)
        assertNull(stack.current)
    }

    @Test fun `push adds a level and current reflects the top`() {
        val stack = ScopeStack().push(q("gradle"))
        assertEquals(1, stack.depth)
        assertEquals("gradle", stack.current?.rawText)
    }

    @Test fun `pop removes the most recent level`() {
        val stack = ScopeStack().push(q("gradle")).push(q("cache"))
        val popped = stack.pop()
        assertEquals(1, popped.depth)
        assertEquals("gradle", popped.current?.rawText)
    }

    @Test fun `pop on an empty stack is a no-op`() {
        assertEquals(ScopeStack(), ScopeStack().pop())
    }

    @Test fun `push is capped at MAX_DEPTH and never throws`() {
        var stack = ScopeStack()
        repeat(10) { stack = stack.push(q("level $it")) }
        assertEquals(ScopeStack.MAX_DEPTH, stack.depth)
        assertTrue(stack.isFull)
    }

    @Test fun `clear resets to empty regardless of depth`() {
        val stack = ScopeStack().push(q("a")).push(q("b")).push(q("c"))
        assertTrue(stack.clear().isEmpty)
    }

    @Test fun `breadcrumb has one label per level in push order`() {
        val stack = ScopeStack().push(q("gradle")).push(q("is:starred"))
        assertEquals(listOf("gradle", "is:starred"), stack.breadcrumb())
    }

    @Test fun `breadcrumb falls back to an ellipsis for a blank raw query`() {
        val stack = ScopeStack().push(q("   "))
        assertEquals(listOf("…"), stack.breadcrumb())
    }

    @Test fun `nameableSnapshot combines every level up to and including the index`() {
        val stack = ScopeStack().push(q("gradle")).push(q("is:starred")).push(q("cache"))
        val draft = stack.nameableSnapshot(1, "My collection")
        assertEquals("gradle is:starred", draft?.query)
        assertEquals("My collection", draft?.name)
    }

    @Test fun `nameableSnapshot on the last index includes everything`() {
        val stack = ScopeStack().push(q("gradle")).push(q("is:starred"))
        assertEquals("gradle is:starred", stack.nameableSnapshot(1, "x")?.query)
    }

    @Test fun `nameableSnapshot returns null for an out-of-range index`() {
        val stack = ScopeStack().push(q("gradle"))
        assertNull(stack.nameableSnapshot(-1, "x"))
        assertNull(stack.nameableSnapshot(5, "x"))
    }

    @Test fun `nameableSnapshot returns null for a blank name`() {
        val stack = ScopeStack().push(q("gradle"))
        assertNull(stack.nameableSnapshot(0, "   "))
    }

    @Test fun `nameableSnapshot trims the provided name`() {
        val stack = ScopeStack().push(q("gradle"))
        assertEquals("My collection", stack.nameableSnapshot(0, "  My collection  ")?.name)
    }

    @Test fun `asOrderedQueries reflects push order for a search-within-results executor`() {
        val stack = ScopeStack().push(q("gradle")).push(q("is:starred"))
        assertEquals(listOf("gradle", "is:starred"), stack.asOrderedQueries().map { it.rawText })
    }

    @Test fun `pushing past the cap never drops an earlier level`() {
        var stack = ScopeStack()
        repeat(ScopeStack.MAX_DEPTH) { stack = stack.push(q("level $it")) }
        val beforeOverflow = stack.breadcrumb()
        stack = stack.push(q("one too many"))
        assertEquals(beforeOverflow, stack.breadcrumb())
        assertFalse(stack.breadcrumb().contains("one too many"))
    }

    @Test fun `a flattened snapshot reparses to the same constraints it stacked`() {
        // The claim nameableSnapshot rests on: AND-ing the levels' raw text reproduces what
        // stacking already means, so a single-shot run of the combined string returns the same set.
        val stack = ScopeStack().push(q("gradle")).push(q("is:starred"))
        val combined = stack.nameableSnapshot(1, "x")!!.query
        assertEquals(
            stack.asOrderedQueries().flatMap { it.facets },
            q(combined).facets,
        )
    }
}
