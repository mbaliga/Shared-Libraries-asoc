package dev.aarso.search

/**
 * [QueryNode] → text, in three shapes that must not be confused with one another:
 *
 *  - [QueryCompiler.renderCanonical] — round-trippable query-box text. Reparsing it yields an
 *    equivalent tree. This is what chips and saved searches are made of.
 *  - [QueryCompiler.lexicalText] — just the words, for a scorer or a find-in-page.
 *  - [QueryCompiler.compileFts] — SQLite FTS5 `MATCH` syntax, for hosts backed by an FTS index.
 *
 * ## Why compileFts is here at all, and why it is no longer eager
 *
 * This module has no storage engine and knows nothing about SQLite. But one of its hosts drives
 * an FTS5 table, and the translation from this AST to FTS5's grammar is genuinely tricky (see
 * [compileFts]'s notes on `NOT`), so leaving each host to rediscover it would be worse than
 * carrying it.
 *
 * What changed is *when* it runs. The original computed the FTS expression eagerly inside
 * `parse` and stored it on `ParsedQuery`. Its one consumer ignored that field and recompiled with
 * `prefixBareTerms = true`, so every keystroke walked the tree twice and discarded the first
 * result. It is now an explicit call, and hosts that do not use FTS never pay for it.
 */
object QueryCompiler {

    /**
     * Compiles the lexical parts of [node] into an FTS5 `MATCH` expression, or `null` when there
     * is nothing lexical to search — a pure facet or semantic query. `null` is an honest "no FTS
     * constraint", not a failure; a caller must run its facet filter over everything rather than
     * treat it as an empty result.
     *
     * Three node kinds contribute nothing here, each for its own reason:
     *
     *  - [QueryNode.Facet] is a structured predicate, not text. It is evaluated against
     *    [FacetField.matches]; feeding `starred:true` to FTS would rank documents for containing
     *    the words "starred" and "true".
     *  - [QueryNode.Regex] has no FTS5 equivalent. Regex runs as a scan over the candidate set
     *    that the FTS match and the facet filter already narrowed.
     *  - A **top-level** [QueryNode.Not]. FTS5's `NOT` is binary (`a NOT b`) with no standalone
     *    negation form, so a bare `-maven` cannot be expressed. Inside an [QueryNode.And] it
     *    works and is emitted (see below); alone, it is dropped rather than emitting syntax that
     *    would make SQLite throw. The negation is not lost — evaluate [QueryNode.Not] against the
     *    result set.
     *
     * [QueryNode.Semantic] degrades to a plain prefix term, so `?deadlock` still finds documents
     * containing "deadlock" in a host with no semantic backend. The parser surfaces it separately
     * as [ParsedQuery.semanticText], so the host can *also* say "semantic search unavailable"
     * without the term silently vanishing from the query.
     *
     * @param prefixBareTerms when true, a term the user did not explicitly suffix with `*` still
     *   compiles as a prefix match. This is what an as-you-type box needs: mid-word, `gradl` must
     *   already find `gradle`, or results only appear once a whole token is typed. Off by default
     *   so the output stays a faithful rendering of exactly what was typed — the retrieval layer
     *   opts in; a caller inspecting a parse does not.
     */
    fun compileFts(node: QueryNode?, prefixBareTerms: Boolean = false): String? =
        node?.let { renderFts(it, prefixBareTerms) }?.takeIf { it.isNotBlank() }

    private fun renderFts(node: QueryNode, prefixBare: Boolean): String? = when (node) {
        is QueryNode.Term -> quotedPrefix(node.text, node.prefix || prefixBare)
        is QueryNode.Phrase -> quoted(node.text)
        is QueryNode.Semantic -> quotedPrefix(node.text, prefix = true)
        is QueryNode.Regex -> null
        is QueryNode.Facet -> null
        is QueryNode.Or -> {
            val parts = node.children.mapNotNull { renderFts(it, prefixBare) }
            if (parts.isEmpty()) null else "(" + parts.joinToString(" OR ") + ")"
        }
        is QueryNode.And -> {
            val positive = node.children.filterNot { it is QueryNode.Not }.mapNotNull { renderFts(it, prefixBare) }
            val negative = node.children.filterIsInstance<QueryNode.Not>().mapNotNull { renderFts(it.child, prefixBare) }
            when {
                positive.isEmpty() -> null // nothing lexical to anchor a NOT against — see KDoc
                negative.isEmpty() -> positive.joinToString(" ")
                else -> positive.joinToString(" ") + " NOT (" + negative.joinToString(" OR ") + ")"
            }
        }
        is QueryNode.Not -> null
    }

    /** FTS5 escapes a `"` inside a quoted string by doubling it. */
    private fun quoted(text: String) = "\"" + text.replace("\"", "\"\"") + "\""
    private fun quotedPrefix(text: String, prefix: Boolean) = quoted(text) + if (prefix) "*" else ""

    /**
     * The plain words the user typed, with facet and regex syntax stripped — everything that is
     * genuinely *text to find*.
     *
     * Two callers need exactly this. Relevance re-scoring: handing `starred:true` to a lexical
     * [Scorer] would rank documents for containing the words "starred" and "true". And
     * continuity — opening a result from a search for `gradle starred:true` should look for
     * `gradle` inside the document, not for the facet.
     *
     * Negated terms are excluded: `-maven` is a thing to *avoid*, never a thing to highlight.
     */
    fun lexicalText(node: QueryNode?): String = buildList { collectLexical(node, this) }.joinToString(" ")

    /**
     * The same content as [lexicalText], but **normalized and split into match terms** — the exact
     * shape [Scorer.score] documents its `terms` parameter to be.
     *
     * The normalization is not optional decoration, which is why it happens here rather than being
     * left to the caller. [CoverageRecencyScorer] matches with `normalize(fieldText).contains(term)`,
     * so a term that has not been through [Normalizer.normalize] fails to match any document the
     * moment the user types a capital letter or a composed character — silently, as a zero score
     * rather than an error. Returning raw substrings from a function named "the terms a scorer
     * wants" was a footgun; there is now one way to get scorer-ready terms and it is correct.
     *
     * A [QueryNode.Phrase] therefore contributes its individual words, not one multi-word term.
     * That is deliberate and matches [Matcher]: a phrase is checked for contiguity by the *filter*,
     * while the scorer's job is coverage, and a three-word phrase legitimately counts for three.
     */
    fun lexicalTerms(node: QueryNode?): List<String> =
        Normalizer.tokenizeQuery(lexicalText(node))

    private fun collectLexical(node: QueryNode?, into: MutableList<String>) {
        when (node) {
            null -> Unit
            is QueryNode.Term -> into.add(node.text)
            is QueryNode.Phrase -> into.add(node.text)
            is QueryNode.Semantic -> into.add(node.text)
            is QueryNode.And -> node.children.forEach { collectLexical(it, into) }
            is QueryNode.Or -> node.children.forEach { collectLexical(it, into) }
            is QueryNode.Not -> Unit
            is QueryNode.Regex, is QueryNode.Facet -> Unit
        }
    }

    /**
     * Round-trippable canonical text for one node — what a chip's `text` is built from, and what
     * gets reparsed when a chip is edited or removed. Not FTS syntax; see [compileFts] for that.
     *
     * The round trip is a real contract, not a nicety: chip removal is implemented as "drop a chip
     * and reparse the rest", so any node whose render does not reparse to itself is a filter the
     * user cannot remove without corrupting their query. Two places earn their complexity here,
     * both fixing round trips the original lost:
     *
     *  - A facet's operator and value are quoted **together** (`key:">=a b"`), because the parser
     *    strips quotes before it detects operators. Rendering `key:>"a b"` instead would reparse
     *    as the value `>"a` followed by a stray word.
     *  - A negated **non-leaf** is re-parenthesised. `-(a b)`, because `-a b` reparses as "not a,
     *    and b" — a different query, and one that quietly returns more rows. And `-(-a)`, because
     *    `--a` does not tokenize as two negations at all: `-` is a word character mid-run, so the
     *    tokenizer reads one word `--a` and the parser strips one dash, yielding
     *    `Not(Term("-a"))` — a search for the literal text `-a`. An [QueryNode.Or] is the one
     *    non-leaf that already renders its own parentheses, so wrapping it again would add a layer
     *    of noise to a chip a user reads without buying anything.
     */
    fun renderCanonical(node: QueryNode): String = when (node) {
        is QueryNode.Term -> node.text + if (node.prefix) "*" else ""
        is QueryNode.Phrase -> "\"" + node.text + "\""
        is QueryNode.Regex -> "/" + node.pattern + "/" + if (node.ignoreCase) "i" else ""
        is QueryNode.Semantic -> "?" + if (needsQuoting(node.text)) "\"${node.text}\"" else node.text
        is QueryNode.Facet -> {
            val opText = when (node.op) {
                // A range is Op.EQ carrying `..` in the value, so it renders as bare `10..50`
                // and reparses to the same node. See QueryNode.Facet.rangeBounds.
                Op.EQ -> ""
                Op.NE -> "!="
                Op.GT -> ">"
                Op.GTE -> ">="
                Op.LT -> "<"
                Op.LTE -> "<="
            }
            val body = opText + node.value
            "${node.key}:" + if (needsQuoting(body)) "\"$body\"" else body
        }
        is QueryNode.Not -> "-" + node.child.let { child ->
            // Every leaf kind survives a bare `-` prefix, because the tokenizer has a rule for
            // each: `-word`/`-key:value` are read whole by scanWordOrFacet, and `-"`, `-/`, `-?`
            // each emit a standalone NOT token. The non-leaves do not, and need the parens: an And
            // because `-a b` binds the `-` to `a` alone, a Not because `--a` is one word. An Or
            // already renders parenthesised, so it is left as it is rather than double-wrapped.
            if (child is QueryNode.And || child is QueryNode.Not) {
                "(" + renderCanonical(child) + ")"
            } else {
                renderCanonical(child)
            }
        }
        is QueryNode.And -> node.children.joinToString(" ") { renderCanonical(it) }
        is QueryNode.Or -> "(" + node.children.joinToString(" OR ") { renderCanonical(it) } + ")"
    }

    /**
     * The run terminators a quote can actually rescue. Keep in step with `isWordChar` in
     * QueryParser.kt.
     *
     * That tokenizer breaks a bare run on four things: whitespace, `(`, `)`, and `"`. Only the
     * first three are listed here, and the omission of `"` is deliberate rather than an oversight:
     * the grammar has **no escape sequence**, so a `"` inside a value cannot be rescued by quoting
     * it either — `key:"say"hi"` re-tokenizes just as wrongly as `key:say"hi` does.
     *
     * This does not cost the round-trip contract anything, because **no parser-produced node can
     * contain a `"`**. `isWordChar` excludes it, so no bare word or bare facet value ever captures
     * one; and both quoted scanners consume up to the next `"` and stop, so no phrase or quoted
     * facet value ever captures one either. Every tree that came from [QueryParser.parse] — which
     * is every tree chips are built from — renders and reparses faithfully.
     *
     * A hand-built node carrying a `"` is therefore the only lossy case, and it is lossy no matter
     * what this function returns. Adding an escape sequence to the grammar is the real fix if a
     * host ever needs to synthesise such a node; quoting harder here would only look like one.
     */
    private fun needsQuoting(text: String): Boolean =
        text.any { it.isWhitespace() || it == '(' || it == ')' }
}

/**
 * Builds an editable chip row from a parsed tree.
 *
 * Each top-level AND child is one chip; a nested OR or NOT group collapses into a single removable
 * [ChipKind.GROUP] chip. That matches how a person thinks about "one thing I typed" rather than
 * the tree's internal shape — nobody wants three chips back from typing `(a OR b)` once.
 */
object ChipBuilder {

    fun build(root: QueryNode?): List<QueryChip> {
        if (root == null) return emptyList()
        val parts = if (root is QueryNode.And) root.children else listOf(root)
        return parts.map(::toChip)
    }

    private fun toChip(node: QueryNode): QueryChip = when (node) {
        is QueryNode.Term -> QueryChip(QueryCompiler.renderCanonical(node), ChipKind.TERM)
        is QueryNode.Phrase -> QueryChip(QueryCompiler.renderCanonical(node), ChipKind.PHRASE)
        is QueryNode.Regex -> QueryChip(QueryCompiler.renderCanonical(node), ChipKind.REGEX)
        is QueryNode.Semantic -> QueryChip(QueryCompiler.renderCanonical(node), ChipKind.SEMANTIC)
        is QueryNode.Facet -> QueryChip(QueryCompiler.renderCanonical(node), ChipKind.FACET, key = node.key)
        // The negation renders onto the child's chip rather than wrapping it, so `-ext:pdf` stays
        // one chip carrying its key — a host still colours it as an `ext` chip, struck through.
        is QueryNode.Not -> toChip(node.child).let {
            it.copy(text = QueryCompiler.renderCanonical(node), negated = true)
        }
        is QueryNode.And, is QueryNode.Or -> QueryChip(QueryCompiler.renderCanonical(node), ChipKind.GROUP)
    }
}
