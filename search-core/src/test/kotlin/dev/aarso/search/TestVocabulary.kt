package dev.aarso.search

import java.time.ZoneId

/**
 * The test-local stand-in for a host application's facet vocabulary.
 *
 * ## Why this file exists
 *
 * The suites in this directory are ported from Android-IDE-core, where the facet vocabulary was a
 * closed 18-value `Field` enum compiled into the parser: a test wrote `Field.MODEL` and the parser
 * knew what that meant. This module deliberately has no such enum — the vocabulary is data the host
 * supplies as a [FieldRegistry] — so the ported tests need *a* vocabulary to be written against,
 * and the honest choice is to rebuild the original app's one here, in test code, exactly as a real
 * host would.
 *
 * That is not a workaround; it is the port's central claim under test. If the same 18 keys, the
 * same backed/unbacked split, and the same per-field semantics can be expressed as an ordinary
 * `FieldRegistry` over an ordinary data class — with no changes to the parser or the evaluator —
 * then generalising the enum away cost the original host nothing. Every assertion the original made
 * about `Field.IS`, `Field.TURNS` or `Field.DURING` is re-made here against the string keys `"is"`,
 * `"turns"` and `"during"` resolved through [registry].
 *
 * ## Faithfulness notes
 *
 *  - `in` is an **alias** of `project` rather than a separate field. The original had two enum
 *    constants (`IN`, `PROJECT`) whose evaluator branches were literally the same line; [FacetField]
 *    has [FacetField.aliases] for precisely this, so the duplication is gone. `in:Hyle` still parses
 *    to a facet whose [QueryNode.Facet.key] is the typed `"in"`, which is what chips round-trip.
 *  - The backed/unbacked split is copied verbatim from the original `isBacked`: `tool`, `file`,
 *    `build`, `loop`, `room`, `tag` and `lang` are never backed (that app indexed no such data), and
 *    `is`/`has` are split per value.
 *  - The [ZoneId] is closed over by the date fields rather than threaded through [EvalContext].
 *    That is the module's design: "today" is a question about a place, and the host is the only
 *    party that knows which place its documents are stamped in.
 */
data class Conversation(
    val starred: Boolean = false,
    val archived: Boolean = false,
    val projectId: String? = null,
    val modelIds: List<String> = emptyList(),
    val turnCount: Long = 0,
    val branchCount: Long = 0,
    val hasImage: Boolean = false,
    val hasCode: Boolean = false,
    val costMinor: Long = 0,
    val updatedAtMillis: Long = 0L,
)

/**
 * A [FacetField] assembled from lambdas, so a whole vocabulary reads as a list of declarations
 * instead of a file of one-method classes. Test-only: a real host is better served by named types
 * it can document, but a fixture's value is being readable end-to-end in one screen.
 */
internal class TestFacet<S>(
    override val key: String,
    override val valueKind: FacetValueKind,
    override val aliases: Set<String> = emptySet(),
    private val backed: (String) -> Boolean = { true },
    private val match: (S, Op, String, EvalContext) -> Boolean = { _, _, _, _ -> false },
) : FacetField<S> {
    override fun isBacked(value: String): Boolean = backed(value)
    override fun matches(subject: S, op: Op, value: String, ctx: EvalContext): Boolean =
        match(subject, op, value, ctx)
}

/** The 18-key conversation vocabulary, plus the fixed clock the ported facet tests resolve against. */
object TestVocabulary {

    val ZONE: ZoneId = ZoneId.of("UTC")

    /** 2026-07-30T00:00:00Z — the original `FacetEvaluatorTest`'s fixed "now", to the millisecond. */
    const val NOW_MILLIS: Long = 1_785_369_600_000L

    const val DAY_MILLIS: Long = 86_400_000L

    val CTX: EvalContext = EvalContext(nowMillis = NOW_MILLIS)

    private val BACKED_IS_VALUES = setOf("starred", "archived", "orphan")
    private val BACKED_HAS_VALUES = setOf("image", "code")

    val registry: FieldRegistry<Conversation> = FieldRegistry(fields(ZONE))

    private fun fields(zone: ZoneId): List<FacetField<Conversation>> = listOf(
        TestFacet<Conversation>(
            key = "is",
            valueKind = FacetValueKind.ENUM,
            backed = { it.lowercase() in BACKED_IS_VALUES },
            match = { s, _, v, _ ->
                when (v.lowercase()) {
                    "starred" -> s.starred
                    "archived" -> s.archived
                    // `isNullOrBlank`, not `== null`: a whitespace-only project id is an id nobody
                    // assigned. Carried over from the original evaluator unchanged.
                    "orphan" -> s.projectId.isNullOrBlank()
                    else -> false
                }
            },
        ),
        TestFacet<Conversation>(
            key = "has",
            valueKind = FacetValueKind.ENUM,
            backed = { it.lowercase() in BACKED_HAS_VALUES },
            match = { s, _, v, _ ->
                when (v.lowercase()) {
                    "image" -> s.hasImage
                    "code" -> s.hasCode
                    else -> false
                }
            },
        ),
        TestFacet<Conversation>(
            key = "project",
            valueKind = FacetValueKind.TEXT,
            aliases = setOf("in"),
            match = { s, _, v, _ -> s.projectId?.equals(v, ignoreCase = true) == true },
        ),
        TestFacet<Conversation>(
            key = "model",
            valueKind = FacetValueKind.TEXT,
            // Model ids are namespaced ("cloud:anthropic/claude-opus", "local:qwen-7b"), so a user
            // typing `model:claude` means "any model whose id mentions claude" — substring, not
            // equality. Preserved verbatim from the original evaluator.
            match = { s, _, v, _ -> s.modelIds.any { it.contains(v, ignoreCase = true) } },
        ),
        numeric("turns") { it.turnCount },
        numeric("branch") { it.branchCount },
        numeric("cost") { it.costMinor },
        // `before` and `after` deliberately ignore the operator: the *key* is the comparison. The
        // original did the same, and `before:>x` is not something anyone means.
        TestFacet<Conversation>(
            key = "before",
            valueKind = FacetValueKind.DATE,
            match = { s, _, v, ctx ->
                RelativeDate.resolvePoint(v, ctx, zone)?.let { s.updatedAtMillis < it } ?: false
            },
        ),
        TestFacet<Conversation>(
            key = "after",
            valueKind = FacetValueKind.DATE,
            match = { s, _, v, ctx ->
                RelativeDate.resolvePoint(v, ctx, zone)?.let { s.updatedAtMillis >= it } ?: false
            },
        ),
        // `during` is the one date field that honours the operator, because FacetEvaluator
        // decomposes a DATE range (`during:2024-01-01..2024-02-01`) into GTE low AND LTE high. The
        // original fed the whole `a..b` string to a resolver that could only fail, so date ranges
        // there returned an unconditional false; that was a defect, and this is what fixing it
        // costs a host: two extra branches.
        TestFacet<Conversation>(
            key = "during",
            valueKind = FacetValueKind.DATE,
            match = { s, op, v, ctx ->
                val range = RelativeDate.resolveRange(v, ctx, zone)
                when {
                    range == null -> false
                    op == Op.GTE -> s.updatedAtMillis >= range.startInclusiveMillis
                    op == Op.LTE -> s.updatedAtMillis < range.endExclusiveMillis
                    else -> s.updatedAtMillis in range
                }
            },
        ),
        // Grammar-complete but never backed: the original app indexed no tool calls, no file or
        // build linkage, no tags, no rooms, no language detection and no loop ids. Keeping the keys
        // recognized is what stops `tool:bash` degrading to "unknown field"; `isBacked` returning
        // false is what turns it into an honest zero with a diagnostic instead of a silent no-op.
        unbacked("tag"), unbacked("room"), unbacked("file"),
        unbacked("tool"), unbacked("build"), unbacked("lang"), unbacked("loop"),
    )

    private fun numeric(key: String, read: (Conversation) -> Long) = TestFacet<Conversation>(
        key = key,
        valueKind = FacetValueKind.NUMBER,
        match = { s, op, v, _ -> compareNumeric(read(s), op, v) },
    )

    private fun unbacked(key: String) = TestFacet<Conversation>(
        key = key,
        valueKind = FacetValueKind.TEXT,
        backed = { false },
    )

    /** An unparseable number matches nothing (an honest zero) rather than throwing — the same hard
     *  rule the parser follows, and the original `compareNumeric` verbatim plus [Op.NE], which the
     *  original operator set did not have. */
    private fun compareNumeric(actual: Long, op: Op, raw: String): Boolean =
        raw.trim().toLongOrNull()?.let { n ->
            when (op) {
                Op.EQ -> actual == n
                Op.NE -> actual != n
                Op.GT -> actual > n
                Op.GTE -> actual >= n
                Op.LT -> actual < n
                Op.LTE -> actual <= n
            }
        } ?: false
}

/**
 * A vocabulary whose host code **breaks [FacetField]'s no-throw contract**, for pinning what the
 * module does about it.
 *
 * ## Why this fixture is not contrived
 *
 * Upstream, the parser's field knowledge came from a closed enum it owned; `isBacked` and the value
 * kind were module code over a fixed key set and *could* not throw. Generalising the vocabulary
 * into a host-supplied [FieldRegistry] moved both onto the far side of an interface boundary, and
 * the parser calls them with the value **exactly as typed** — which in an as-you-type box means
 * every prefix of it. `value.toInt()` is the first thing anyone writes for a numeric `isBacked`, it
 * is total over the values the host has in mind, and it throws on `10m` on the way to `10mb`.
 *
 * So this is not a fixture built to defeat the parser; it is the obvious first implementation, and
 * the module's headline contract is that it still must not throw. Everything here is deliberately
 * the *plausible* mistake rather than an exotic one.
 */
object HostileVocabulary {

    /** `isBacked` written the obvious way. Total over `"10"`, partial over everything else. */
    private class NaiveNumberFacet : FacetField<Conversation> {
        override val key: String = "size"
        override val valueKind: FacetValueKind = FacetValueKind.NUMBER
        override fun isBacked(value: String): Boolean = value.toInt() >= 0
        override fun matches(subject: Conversation, op: Op, value: String, ctx: EvalContext) = true
    }

    /**
     * A host whose `valueKind` is computed rather than stored, and computes badly — the shape a
     * registry assembled from a config file or a lazily-loaded schema takes.
     *
     * `matches` answers true only for the **verbatim** `1..5`, so a test can tell "the evaluator
     * gave up on knowing the kind and passed the value through" (the documented degrade) apart from
     * "the evaluator decomposed the range anyway".
     */
    private class ExplodingKindFacet : FacetField<Conversation> {
        override val key: String = "kind"
        override val valueKind: FacetValueKind get() = error("valueKind is not available yet")
        override fun isBacked(value: String): Boolean = true
        override fun matches(subject: Conversation, op: Op, value: String, ctx: EvalContext) =
            value == "1..5"
    }

    /** Both parse-path members broken at once, so neither guard can be relied on by the other. */
    private class DoublyHostileFacet : FacetField<Conversation> {
        override val key: String = "hostile"
        override val valueKind: FacetValueKind get() = error("valueKind is not available yet")
        override fun isBacked(value: String): Boolean = value.toInt() >= 0
        override fun matches(subject: Conversation, op: Op, value: String, ctx: EvalContext) =
            subject.starred
    }

    val registry: FieldRegistry<Conversation> =
        FieldRegistry(listOf(NaiveNumberFacet(), ExplodingKindFacet(), DoublyHostileFacet()))
}
