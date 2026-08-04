package dev.aarso.search

/**
 * "Search within results": scopes **stack rather than replace**, so each level's candidate set is
 * the previous level's result set.
 *
 * A host renders this as a breadcrumb and pops one level on `Esc`. The reason it is a stack and
 * not a single query string with more words appended is that popping has to be lossless — a user
 * narrowing three times and then backing off once expects to land exactly where they were, not on
 * a re-parse of a string someone edited by hand.
 *
 * Capped at [MAX_DEPTH]. The cap is a UI constraint, not a technical one: past four levels the
 * breadcrumb stops fitting and nobody remembers what level two was.
 *
 * Pure and dependency-free on purpose — persisting a [SavedSearchDraft] is the host's concern,
 * and this type having no idea how that happens is what lets three apps with three different
 * stores share it.
 */
data class ScopeStack(val levels: List<ParsedQuery> = emptyList()) {

    val depth: Int get() = levels.size
    val isEmpty: Boolean get() = levels.isEmpty()
    val current: ParsedQuery? get() = levels.lastOrNull()
    val isFull: Boolean get() = levels.size >= MAX_DEPTH

    /** Pushes a new scope level. A no-op past [MAX_DEPTH] — never throws, and never silently
     *  drops an earlier level to make room either. A caller offering a "scope to this" gesture is
     *  expected to check [isFull] first and hide or disable the gesture at that point; dropping
     *  the *oldest* level to accept a new one would quietly change what the user is looking at. */
    fun push(query: ParsedQuery): ScopeStack = if (isFull) this else copy(levels = levels + query)

    /** Pops the most recent level. A no-op on an already-empty stack. */
    fun pop(): ScopeStack = if (levels.isEmpty()) this else copy(levels = levels.dropLast(1))

    /** Pops every level at once — back to the unscoped base state. */
    fun clear(): ScopeStack = ScopeStack()

    /** One label per level, in push order. Falls back to an ellipsis for a level whose raw text
     *  is blank (a pure-facet query built programmatically, say) rather than an empty crumb. */
    fun breadcrumb(): List<String> = levels.map { it.rawText.ifBlank { "…" } }

    /** Every level, base-first. "This level's candidate set is the previous level's result set"
     *  means an executor applies these in order, each over the prior's results — not
     *  independently, and not by OR-ing them. */
    fun asOrderedQueries(): List<ParsedQuery> = levels

    /**
     * Names the scope through [index] (inclusive), for "save this as a smart collection".
     *
     * A saved search is one standalone query, not a multi-level stack, so every level up to
     * [index] is flattened into a single combined query string. AND-ing their raw text together
     * reproduces exactly what stacking already means semantically — each level narrows the one
     * before it — so a fresh single-shot run of the combined string returns the same set.
     *
     * Returns `null` for an out-of-range [index] or a blank [name]. Never throws.
     */
    fun nameableSnapshot(index: Int, name: String): SavedSearchDraft? {
        if (index !in levels.indices || name.isBlank()) return null
        val combined = levels.subList(0, index + 1).joinToString(" ") { it.rawText }.trim()
        return SavedSearchDraft(name = name.trim(), query = combined)
    }

    companion object {
        const val MAX_DEPTH = 4
    }
}

/** What [ScopeStack.nameableSnapshot] produces — ready for whatever the host persists into. */
data class SavedSearchDraft(val name: String, val query: String)
