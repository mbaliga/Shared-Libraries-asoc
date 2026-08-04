package dev.aarso.search

/**
 * Evaluates the **facet** half of a [QueryNode] tree against one host subject.
 *
 * ## What changed relative to Android-IDE-core, and why
 *
 * The original evaluated against a fixed ten-field `FacetSubject` struct (`starred`, `archived`,
 * `projectId`, `modelIds`, `turnCount`, …) with a `when` over the closed `Field` enum: `Field.IS`
 * read `subject.starred`, `Field.TURNS` compared `subject.turnCount`, and seven of the eighteen
 * fields were hard-coded to `false` because nothing in that app indexed them. Every branch of that
 * `when` is an app-specific fact, so the whole function was unusable by a second host.
 *
 * Here the subject is a type parameter and the per-field decision lives on [FacetField.matches],
 * which the host implements. What remains in this file is the part that is genuinely *not*
 * app-specific and that every host would otherwise have to rediscover: the boolean walk, the
 * negation guard, the neutral-lexical-leaf rule, the unbacked-value zero, and range decomposition.
 *
 * The original's companion `ConversationFacetFilter` — a second, narrower evaluator over the app's
 * conversation-tree summary — is **not** ported. It existed only because that app had two subject
 * shapes and one evaluator that could only speak to one of them. A [FieldRegistry] over the second
 * shape expresses it exactly, with no second evaluator and no second truth table to drift.
 *
 * ## The two rules that look like bugs and are not
 *
 * Both are load-bearing and both are preserved verbatim from the original; see [matches].
 *
 *  1. **A lexical leaf evaluates to `true`.** Matching free text is the scorer's job, not the
 *     filter's.
 *  2. **A [QueryNode.Not] over a purely-lexical subtree evaluates to `true`, not `false`.** Without
 *     that guard, rule 1 inverts and the query hides everything.
 *
 * ## Where the clock and the time zone went
 *
 * The original took `nowMillis` and a `ZoneId` and resolved `before:`/`after:`/`during:` itself.
 * Only the clock survives here, on [EvalContext], because it is the one piece of ambient state the
 * *parser* also needs and the two must agree. The zone does not appear at all: `today` is a
 * question about a place, the host is the only party that knows which place its documents are
 * stamped in, and a [FacetField] over a date is the natural place to close over that decision.
 * [RelativeDate] is public precisely so a host's date field can call it with its own zone.
 */
object FacetEvaluator {

    /**
     * Whether [subject] satisfies the facet constraints in [node], resolved through [registry].
     *
     * `null` — an empty or unparseable query — matches everything. That is an honest "no
     * constraint", and it is the distinction a host must never have to guess at: an empty query is
     * an unfiltered browse, not a filter that matches nothing.
     *
     * ### Lexical leaves are neutral
     *
     * [QueryNode.Term], [QueryNode.Phrase], [QueryNode.Regex] and [QueryNode.Semantic] all
     * contribute `true`. This function decides *filtering*, and free text is not a filter here — it
     * is ranked by a [Scorer], or already applied upstream by an FTS `MATCH` for the hosts that
     * have one. For an all-AND query (overwhelmingly the common shape) that composes to exactly the
     * right answer: `lexical AND facets`.
     *
     * **The one documented lossy case** is a facet OR'd with text — `gradle OR starred:true`. True
     * semantics are "matches gradle, or is starred"; a pipeline that intersects this function's
     * result with a separate lexical pass computes `lexical(gradle) AND (true OR starred)` =
     * `lexical(gradle)`, quietly dropping the starred-but-not-matching-gradle branch. It never
     * returns a *wrong* subject, only too few, and only for that mixed-disjunction shape. Detect it
     * with [hasLossyDisjunction] and say so out loud rather than under-returning silently — or use
     * [Matcher], which evaluates lexical leaves for real and does not have the problem at all.
     *
     * ### Negation is guarded
     *
     * `Not` inverts only a subtree this evaluator actually has an opinion about. Without the guard,
     * `-maven` (a negated *term*, whose negation belongs to the lexical pass) would invert the
     * neutral `true` into `false` and silently reject every subject. The original shipped that
     * guard on its main path; its narrower sibling evaluator did not, which is why any preset
     * containing a bare negated word — `-has:image` is safe, `-draft` is not — hid its entire list.
     */
    fun <S> matches(
        subject: S,
        node: QueryNode?,
        registry: FieldRegistry<S>,
        ctx: EvalContext,
    ): Boolean = when (node) {
        null -> true
        is QueryNode.And -> node.children.all { matches(subject, it, registry, ctx) }
        is QueryNode.Or -> node.children.any { matches(subject, it, registry, ctx) }
        is QueryNode.Not ->
            if (containsFacet(node.child)) !matches(subject, node.child, registry, ctx) else true
        is QueryNode.Facet -> matchesFacet(subject, node, registry, ctx)
        // Neutral by design — see the KDoc above, not a missing branch.
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Regex, is QueryNode.Semantic -> true
    }

    /** Convenience overload for the common `parsed.root` call. */
    fun <S> matches(
        subject: S,
        parsed: ParsedQuery,
        registry: FieldRegistry<S>,
        ctx: EvalContext,
    ): Boolean = matches(subject, parsed.root, registry, ctx)

    /**
     * Evaluates one facet leaf. Exposed because [Matcher] needs exactly this and nothing else of
     * the walk above: there must be one implementation of "what does `size:>10mb` mean", not two.
     *
     * Three things happen here that a host would otherwise each reimplement:
     *
     *  - **An unrecognized key is `false`,** never a silent pass. The parser degrades an unknown
     *    `foo:bar` to a literal term, so a `Facet` node with a key this registry does not know can
     *    only arrive from a tree built against a *different* registry, or built by hand. Matching
     *    everything in that case would turn a typo into "no filter at all".
     *  - **An unbacked value is `false`.** [FacetField.isBacked] already told the parser that
     *    nothing indexed can match, and the parser already emitted [Diagnostic.UnindexedFacet] so
     *    the UI can say *why* nothing matched. Checked against the value exactly as the parser
     *    checked it — untrimmed, operator already stripped — so the two answers cannot disagree.
     *  - **A range is decomposed,** see below.
     *
     * ### Ranges
     *
     * `Op.RANGE` does not exist in this module ([FacetField.matches] takes one `(op, value)` pair,
     * and a range is not a comparison), so `turns:10..50` arrives as [Op.EQ] carrying the `..`.
     * Rather than push `..` parsing onto every host, a range over a [FacetValueKind.NUMBER] or
     * [FacetValueKind.DATE] field is evaluated as `GTE low AND LTE high` — which is the original's
     * `actual in low..high` exactly, and comes free to any host that implements its comparison
     * operators.
     *
     * The decomposition is deliberately **not** applied to [FacetValueKind.TEXT],
     * [FacetValueKind.ENUM] or [FacetValueKind.BOOL] fields, where `..` is far likelier to be part
     * of the value (`path:../build`) than a range the user meant. Those values reach
     * [FacetField.matches] verbatim, which also matches how the original behaved: it only ever
     * acted on a range for its three numeric fields and passed the raw string through everywhere
     * else. The one place this goes beyond the original is dates — `during:2024-01-01..2024-02-01`
     * returned an unconditional `false` there, because it fed the whole `a..b` string to a date
     * resolver that could only fail. That was a defect, not a decision.
     */
    fun <S> matchesFacet(
        subject: S,
        facet: QueryNode.Facet,
        registry: FieldRegistry<S>,
        ctx: EvalContext,
    ): Boolean {
        val field = registry[facet.key] ?: return false
        if (!field.isBacked(facet.value)) return false

        val bounds = facet.rangeBounds
        if (bounds != null && field.valueKind.isOrdered()) {
            val (low, high) = bounds
            return field.matches(subject, Op.GTE, low.trim(), ctx) &&
                field.matches(subject, Op.LTE, high.trim(), ctx)
        }
        // Trimmed for the host, untrimmed for isBacked above — the original drew the line in the
        // same place, and moving it would make a value backed but unmatchable (or the reverse).
        return field.matches(subject, facet.op, facet.value.trim(), ctx)
    }

    /** The value kinds for which `a..b` denotes an interval rather than a literal value. */
    private fun FacetValueKind.isOrdered(): Boolean =
        this == FacetValueKind.NUMBER || this == FacetValueKind.DATE

    /**
     * Detects the one shape a split lexical/facet pipeline evaluates lossily: an `OR` with a facet
     * on one side and lexical text on the other (`gradle OR starred:true`). See [matches] — the
     * result is too-few subjects, never wrong ones.
     *
     * Exposed so a host can surface it ("showing only results matching *gradle*") instead of
     * silently under-returning. A host using [Matcher] never needs this: it evaluates both halves
     * in one walk, so the disjunction is exact.
     */
    fun hasLossyDisjunction(node: QueryNode?): Boolean = when (node) {
        null -> false
        is QueryNode.Or -> {
            val anyFacet = node.children.any(::containsFacet)
            val anyLexical = node.children.any(::containsLexical)
            (anyFacet && anyLexical) || node.children.any(::hasLossyDisjunction)
        }
        is QueryNode.And -> node.children.any(::hasLossyDisjunction)
        is QueryNode.Not -> hasLossyDisjunction(node.child)
        else -> false
    }

    /** Whether [node] constrains anything this evaluator has an opinion about. Public because it
     *  is also the honest answer to "would running the facet pass change anything". */
    fun containsFacet(node: QueryNode?): Boolean = when (node) {
        null -> false
        is QueryNode.Facet -> true
        is QueryNode.And -> node.children.any(::containsFacet)
        is QueryNode.Or -> node.children.any(::containsFacet)
        is QueryNode.Not -> containsFacet(node.child)
        else -> false
    }

    /**
     * Whether [node] contains free text.
     *
     * [QueryNode.Regex] is deliberately **not** counted, preserved from the original: this
     * predicate exists to answer "did an upstream text index already apply this", and a regex is
     * never served by an index — it is a scan over whatever the index and the facets already
     * narrowed. Counting it would report a disjunction as lossy in the one case where the lexical
     * side was not applied upstream at all.
     */
    fun containsLexical(node: QueryNode?): Boolean = when (node) {
        null -> false
        is QueryNode.Term, is QueryNode.Phrase, is QueryNode.Semantic -> true
        is QueryNode.And -> node.children.any(::containsLexical)
        is QueryNode.Or -> node.children.any(::containsLexical)
        is QueryNode.Not -> containsLexical(node.child)
        else -> false
    }
}
