package dev.aarso.search

/**
 * The document model, the field vocabulary contract, and the scoring contract.
 *
 * These are the types every other file in this module — and every host application — codes
 * against, so they are defined in one place and kept small on purpose.
 *
 * ## What changed relative to Android-IDE-core, and why
 *
 * The engine this was generalised from modelled a document as a fixed pair of text tiers
 * (`title` and `snippet + " " + body`) and its facet vocabulary as a closed 18-value `Field`
 * enum of conversation attributes (`model:`, `turns:`, `room:`, …). Both are correct for one
 * app and unusable for three:
 *
 *  - Fylz needs `ext:`, `size:` and `type:` over filesystem entries.
 *  - Foto Xplorr needs `label:`, `person:` and `text:` over recognition output.
 *  - Android-IDE-core keeps exactly the vocabulary it has today.
 *
 * So the field set becomes data supplied by the host ([FieldRegistry]) rather than an enum
 * baked into the parser, and the text tiers become a named map rather than two slots.
 */

/** A stable identity for an indexed thing. Opaque to this module; meaningful to the host. */
@JvmInline
value class DocId(val value: String) : Comparable<DocId> {
    override fun compareTo(other: DocId): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

/** The name of a text or attribute field, e.g. `title`, `content`, `ext`. */
@JvmInline
value class FieldName(val value: String) {
    override fun toString(): String = value
}

/**
 * A projected, searchable document.
 *
 * [text] is what gets **matched and ranked**. [display] is what a host wants to show alongside
 * a hit but must never match — the distinction the original model lacked, which is why its
 * `snippet` was confusingly both.
 *
 * Projecting a document is the expensive half of indexing (it tokenizes), which is why
 * [DocumentAdapter] separates it from the cheap [DocumentAdapter.revision] check.
 */
data class SearchDoc(
    val id: DocId,
    /** Host clock, epoch millis. Drives the recency term in [CoverageRecencyScorer]. */
    val timestampMillis: Long,
    /** Matched and ranked. Field weights are supplied to the scorer, not stored here. */
    val text: Map<FieldName, String>,
    /** Shown with a hit, never matched. */
    val display: Map<FieldName, String> = emptyMap(),
    /** Structured values for facet filtering (`ext:pdf`, `starred:true`). */
    val attributes: Map<FieldName, FieldValue> = emptyMap(),
)

/** A structured attribute value. Closed on purpose — every facet reduces to one of these. */
sealed interface FieldValue {
    @JvmInline value class Text(val value: String) : FieldValue
    @JvmInline value class Number(val value: Double) : FieldValue
    @JvmInline value class Bool(val value: Boolean) : FieldValue
    /** Epoch millis. Distinct from [Number] so date operators can be type-checked. */
    @JvmInline value class Timestamp(val millis: Long) : FieldValue
    /** Multi-valued, e.g. every label on a photo. */
    data class Many(val values: List<FieldValue>) : FieldValue
}

/**
 * A highlight range.
 *
 * Ranges are **always** in the coordinates of the original, un-normalized text. The engine this
 * came from silently returned normalized coordinates whenever NFKC changed a string's length,
 * which quietly misplaced highlights on exactly the scripts most likely to need them. Making
 * the guarantee part of the type is the fix.
 */
data class Highlight(val field: FieldName, val range: IntRange)

/** Which comparison a facet term asked for: `size:>10mb` is [Op.GT] with value `10mb`. */
enum class Op { EQ, NE, LT, LTE, GT, GTE }

/** What kind of value a field accepts. Lets the parser reject `turns:yesterday` at parse time. */
enum class FacetValueKind { ENUM, TEXT, NUMBER, DATE, BOOL }

/**
 * Ambient context for evaluating a query: the clock, and the locale used for normalization.
 *
 * Passed explicitly rather than read from a global so that relative dates (`modified:today`)
 * and ranking are deterministic and testable — no test in this module reads a real clock.
 */
data class EvalContext(
    val nowMillis: Long,
    val locale: java.util.Locale = java.util.Locale.ROOT,
)

/**
 * One facet field, supplied by the host.
 *
 * Contravariant in [S] so a registry built for a supertype can evaluate subtypes. Parse-time
 * knowledge ([isBacked], [valueKind]) and eval-time knowledge ([matches]) live on the same
 * object deliberately: splitting them across two types, as an earlier design did, gives two
 * sources of truth for one key set that must agree and silently diverge when they don't.
 *
 * [matches] takes the subject directly rather than an attribute-bag lookup, so the natural
 * implementation is a field read (`subject.isFavorite`) with no allocation. Foto Xplorr
 * evaluates facets during Compose composition over a large library; boxing every attribute per
 * predicate per keystroke would be the wrong trade there.
 */
interface FacetField<in S> {
    /** The key as typed: the `ext` in `ext:pdf`. */
    val key: String

    val valueKind: FacetValueKind

    /** Alternative spellings accepted for [key]. */
    val aliases: Set<String> get() = emptySet()

    /**
     * Whether any indexed document could match this value — checked *before* evaluation so the
     * UI can say "no results because nothing has that value" rather than showing an empty list
     * indistinguishable from a typo. Answering `false` is an honest zero, not an error.
     */
    fun isBacked(value: String): Boolean

    fun matches(subject: S, op: Op, value: String, ctx: EvalContext): Boolean
}

/**
 * The host's facet vocabulary. Replaces the closed `Field` enum.
 *
 * An app may hold several registries over different subject types — Android-IDE-core needs one
 * over its conversation summaries and another over its message tree — and route both through
 * the same evaluator.
 */
class FieldRegistry<S>(fields: List<FacetField<S>>) {

    private val byKey: Map<String, FacetField<S>> = buildMap {
        for (f in fields) {
            put(f.key.lowercase(), f)
            for (a in f.aliases) put(a.lowercase(), f)
        }
    }

    /** Canonical keys only, in declaration order — aliases are resolvable but not advertised. */
    val keys: List<String> = fields.map { it.key }

    operator fun get(key: String): FacetField<S>? = byKey[key.lowercase()]

    fun recognizes(key: String): Boolean = byKey.containsKey(key.lowercase())

    /**
     * Nearest known key within a small edit distance, for "did you mean" diagnostics.
     * Returns null rather than a bad guess when nothing is close.
     */
    fun suggest(typed: String): String? {
        val t = typed.lowercase()
        if (t.isEmpty()) return null
        // Threshold scales with length so short keys don't match everything.
        val maxDistance = when {
            t.length <= 3 -> 1
            t.length <= 6 -> 2
            else -> 3
        }
        return keys
            .map { it to editDistance(t, it.lowercase()) }
            .filter { (_, d) -> d in 1..maxDistance }
            .minByOrNull { (key, d) -> d * 100 + key.length }
            ?.first
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, sub)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }
}

/** A scored hit. [highlights] are in original coordinates — see [Highlight]. */
data class Scored(
    val doc: SearchDoc,
    val score: Double,
    val matchedFields: Set<FieldName>,
    val highlights: List<Highlight> = emptyList(),
    val explanation: Explanation? = null,
)

/** Why a document scored what it did. Populated only when explicitly requested. */
data class Explanation(
    val termCoverage: Double,
    val matchedTermCount: Int,
    val distinctQueryTermCount: Int,
    val appliedFieldWeight: Double,
    val recencyFactor: Double,
    val total: Double,
)

/**
 * How a document is ranked against a set of query terms. Null means "not a hit".
 *
 * A plain interface rather than a `fun interface` because [explain] carries a default, and a
 * SAM-conversion target cannot have one.
 */
interface Scorer {
    fun score(doc: SearchDoc, terms: List<String>, ctx: EvalContext, explain: Boolean = false): Scored?
}

/**
 * Turns host items into indexable documents.
 *
 * The split between [revision] and [project] is the single most important contract in this
 * module. In the engine this was generalised from, keeping the index fresh re-projected the
 * *entire corpus* on every debounced change, which meant word-segmenting every message of every
 * conversation roughly every 1.5 seconds during a generating turn, then discarding almost all of
 * it. Any contract that only offers "give me all the documents" makes that permanent.
 *
 * So: [revision] must be answerable from metadata the host already has — an mtime, a size, a
 * row version — and must never tokenize or read file contents. The indexer compares it against
 * the stored revision and calls the expensive [project] only on a miss.
 */
interface DocumentAdapter<T> {
    fun id(item: T): DocId

    /** Cheap. MUST NOT tokenize or read contents. mtime+size, or a row version, is the intent. */
    fun revision(item: T): Long

    /** Expensive. Called only for items whose stored revision differs. */
    fun project(item: T): SearchDoc
}

/**
 * A corpus change.
 *
 * [Snapshot] carries a `Sequence`, not a `List`, so a cold start over a large corpus streams
 * instead of materialising. Fylz bounds its walk at 500k records; holding that as a list is not
 * a performance note, it is an out-of-memory crash.
 */
sealed interface CorpusUpdate<out T> {
    data class Snapshot<T>(val items: Sequence<T>) : CorpusUpdate<T>
    data class Delta<T>(val upserted: List<T>, val removed: List<DocId>) : CorpusUpdate<T>
}
