package dev.aarso.search

/**
 * The query AST, the diagnostic vocabulary, and the parse result.
 *
 * ## What changed relative to Android-IDE-core, and why
 *
 * The engine this was generalised from nailed its grammar to a closed 18-value `Field` enum of
 * conversation attributes (`model:`, `turns:`, `cost:`, `room:`, `loop:`, `build:`, `branch:`, …).
 * The parser did `Field.fromKey(...)`, the diagnostics carried a `Field`, the chips carried a
 * `Field`, and the evaluator `when`-ed exhaustively over it. That enum is the single thing that
 * made an otherwise app-agnostic parser unusable by a second app: Fylz needs `ext:`/`size:`/
 * `type:`, Foto Xplorr needs `label:`/`person:`/`text:`, and neither wants `loop:`.
 *
 * So [QueryNode.Facet] now carries a plain [QueryNode.Facet.key] string, and the *vocabulary* is
 * supplied by the host as a [FieldRegistry]. The parser consults the registry for recognition,
 * value kind, backing and "did you mean" suggestions; it has no opinion about which keys exist.
 *
 * ## The hard rule this file exists to encode
 *
 * **Parsing never throws.** Every malformed input — an unterminated quote, an unknown field, a
 * date that isn't a date — degrades to a best-effort parse plus a [Diagnostic]. A search box that
 * throws on the third keystroke of every word is a search box people stop using, and *every*
 * keystroke in an as-you-type box is a half-typed query by definition. That rule is preserved
 * verbatim from the original, including each diagnostic case and the shape of what it carries.
 */

/**
 * The parsed query tree. Boolean structure ([And]/[Or]/[Not]) composes freely with every leaf
 * kind, which is why the evaluator can be a plain recursive walk.
 */
sealed interface QueryNode {

    data class And(val children: List<QueryNode>) : QueryNode

    data class Or(val children: List<QueryNode>) : QueryNode

    data class Not(val child: QueryNode) : QueryNode

    /** A bare word. [prefix] is set by a trailing `*` (`gradl*`) — see also
     *  [QueryCompiler.compileFts]'s `prefixBareTerms`, which forces it on for as-you-type. */
    data class Term(val text: String, val prefix: Boolean) : QueryNode

    /** A `"quoted phrase"`. Matches as a contiguous run, not as its words. */
    data class Phrase(val text: String) : QueryNode

    /** `/pattern/` or `/pattern/i`. The engine never compiles this at parse time — an invalid
     *  pattern must not cost the user their whole query, so compilation (and its failure) belongs
     *  to whoever evaluates it. */
    data class Regex(val pattern: String, val ignoreCase: Boolean) : QueryNode

    /** `?term` or `?"a longer thought"`. A request for semantic retrieval, which this module does
     *  not implement; it is surfaced separately as [ParsedQuery.semanticText] so a host can
     *  either serve it or report [Diagnostic.SemanticUnavailable] while still running the term
     *  lexically as a fallback. */
    data class Semantic(val text: String) : QueryNode

    /**
     * A `key:value` constraint, e.g. `ext:pdf`, `size:>10mb`, `modified:2024-01-01..2024-02-01`.
     *
     * [key] is the key **as typed**, not as the registry canonicalises it — it stays resolvable
     * through [FieldRegistry.get], which is case- and alias-insensitive. Keeping what was typed
     * is deliberate: chips round-trip back into the search box, and silently rewriting a user's
     * alias `modified:` into the canonical `mtime:` mid-edit moves their cursor out from under
     * them.
     *
     * [value] is the raw value text with the operator prefix already stripped (`>10mb` becomes
     * [Op.GT] plus `"10mb"`), but otherwise untyped. Typing it — parsing `10mb` into bytes,
     * `pdf` into an enum — is [FacetField.matches]'s job, because only the host knows the units.
     *
     * ### Where `Op.RANGE` went
     *
     * The original had a sixth operator, `RANGE`, for `cost:10..50`. [Op] in `Model.kt` is a
     * fixed contract and has no `RANGE` — and it is the *right* contract, because
     * [FacetField.matches] receives exactly one `(op, value)` pair and a range is not a
     * comparison. So a range is represented as [Op.EQ] with the `..` left in [value], and
     * [rangeBounds] is the accessor that recovers it. This keeps the wire shape lossless (the
     * canonical render is still `cost:10..50`, which reparses identically) without inventing an
     * operator the evaluation contract cannot express.
     */
    data class Facet(val key: String, val op: Op, val value: String) : QueryNode {

        /**
         * `10..50` → `("10", "50")`; `null` when this facet is not a range.
         *
         * Only [Op.EQ] can be a range: `>=10..50` is not a thing anyone means, and the parser
         * never produces it (operator stripping happens first, and a stripped value that still
         * contains `..` under a comparison operator is treated as an opaque value the host may
         * still understand).
         */
        val rangeBounds: Pair<String, String>?
            get() {
                if (op != Op.EQ) return null
                val i = value.indexOf(RANGE_SEPARATOR)
                if (i < 0) return null
                return value.substring(0, i) to value.substring(i + RANGE_SEPARATOR.length)
            }

        /** Convenience for `rangeBounds != null`, for readability at call sites that only test. */
        val isRange: Boolean get() = rangeBounds != null

        companion object {
            const val RANGE_SEPARATOR: String = ".."
        }
    }
}

/**
 * One chip in an editable chip row: the UI shape of "one thing I typed".
 *
 * [text] is the chip's canonical, round-trippable text. The contract the original pinned by test
 * and this port preserves: `chips.toQueryText()` reparses to a tree equivalent to the one the
 * chips were built from. That is what makes a chip removable — dropping a chip and reparsing the
 * rest is the entire implementation of "remove this filter".
 *
 * [key] is populated only for [ChipKind.FACET] chips, so a host can render `ext:pdf` with the
 * icon or colour it assigns to its own `ext` field without re-parsing the chip text.
 */
data class QueryChip(
    val text: String,
    val kind: ChipKind,
    val key: String? = null,
    val negated: Boolean = false,
)

enum class ChipKind { TERM, PHRASE, REGEX, SEMANTIC, FACET, GROUP }

/**
 * Something the parser noticed and chose not to throw about.
 *
 * A diagnostic is never fatal: the query that produced it still ran, minus (or degraded from) the
 * offending part. Hosts surface these inline — "unknown field `modl`, did you mean `model`?" next
 * to the search box — rather than as an error state.
 */
sealed interface Diagnostic {

    /** `foo:bar` where `foo` is not in the [FieldRegistry]. [suggestion] comes from
     *  [FieldRegistry.suggest] and is `null` when nothing known is close enough to guess.
     *  The term is not dropped: it degrades to a literal [QueryNode.Term] of `foo:bar`. */
    data class UnknownField(val typed: String, val suggestion: String?) : Diagnostic

    /** A recognized key whose *value* nothing indexed can match — [FacetField.isBacked] said no.
     *  An honest zero ("no results because nothing has that value"), never a silent no-op, and
     *  distinguishable in the UI from an empty result set that merely means "no hits". */
    data class UnindexedFacet(val key: String, val value: String) : Diagnostic

    /** A `"` with no closing `"`. [at] is the offset of the opening quote in the raw text.
     *  The run is still parsed, as though the quote closed at end-of-input. */
    data class UnterminatedQuote(val at: Int) : Diagnostic

    /** A `/` with no closing `/` before whitespace. [at] is the offset of the opening slash.
     *  The run degrades to a plain [QueryNode.Term], including the leading slash. */
    data class UnterminatedRegex(val at: Int) : Diagnostic

    /** A [FacetValueKind.DATE] field given something [RelativeDate] cannot resolve.
     *  The facet node is still produced — the host may know a date format this module doesn't. */
    data class InvalidDate(val key: String, val raw: String) : Diagnostic

    /**
     * A [FacetValueKind.NUMBER] field given something that does not start with a number.
     *
     * New in this port, and the reason `Model.kt` gives for [FacetField.valueKind] existing at
     * all: "lets the parser reject `turns:yesterday` at parse time". The check is deliberately
     * loose — it accepts `10mb` and `1.5s`, because unit suffixes are the host's business (see
     * [FacetField.matches]) and a parser that rejected `size:>10mb` would be worse than one that
     * checked nothing.
     */
    data class InvalidNumber(val key: String, val raw: String) : Diagnostic

    /** A `?semantic` term in a host with no semantic backend. Emitted by the host, not the
     *  parser — the parser cannot know what is wired up. Kept in the vocabulary so hosts do not
     *  each invent their own. */
    data class SemanticUnavailable(val text: String) : Diagnostic
}

/**
 * The full result of parsing one query string.
 *
 * ### What is deliberately *not* here
 *
 * The original carried an `ftsExpression: String?` computed eagerly inside `parse`. Its only
 * consumer ignored it and recompiled with different options (as-you-type wants bare terms
 * prefix-matched; a parse inspector wants a faithful rendering of what was typed), so every
 * keystroke paid for a tree walk whose output was thrown away. It is now an explicit call —
 * [QueryCompiler.compileFts] — for the hosts that back this engine with SQLite FTS5, and free for
 * everyone else.
 *
 * @property root `null` for an empty or entirely unparseable query. Not an error; an empty query
 *   is a legitimate state (an unfiltered browse), and the distinction between "no constraint" and
 *   "a constraint matching nothing" is one hosts must not have to guess at.
 * @property facets every [QueryNode.Facet] anywhere in [root], flattened regardless of AND/OR/NOT
 *   nesting, for callers that just want "which filters did the user type" without walking the
 *   tree. Note this loses the boolean structure on purpose — evaluate [root], not this.
 * @property semanticText the first [QueryNode.Semantic] found, if any.
 */
data class ParsedQuery(
    val rawText: String,
    val root: QueryNode?,
    val facets: List<QueryNode.Facet>,
    val semanticText: String?,
    val diagnostics: List<Diagnostic>,
    val chips: List<QueryChip>,
)

/** The inverse of parsing: chips back to one query string. Reparsing this reproduces a tree
 *  equivalent to the one the chips were built from — see [QueryChip]. */
fun List<QueryChip>.toQueryText(): String = joinToString(" ") { it.text }

/**
 * A registry with no facet fields at all, for a host whose search box is pure text.
 *
 * Exists so `QueryParser.parse("some words")` works without ceremony. With this registry every
 * `key:value` the user types degrades to [Diagnostic.UnknownField] and a literal term, which is
 * exactly right: if the host declares no fields, `foo:bar` *is* just text.
 */
val NO_FACET_FIELDS: FieldRegistry<Any?> = FieldRegistry(emptyList())
