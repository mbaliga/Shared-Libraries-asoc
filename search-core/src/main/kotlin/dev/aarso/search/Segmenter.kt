package dev.aarso.search

import java.util.Locale

/**
 * **Word-boundary segmentation** — turning a run of text into the tokens an index stores.
 *
 * ### The problem this exists to fix
 * A naive Unicode "word" definition — SQLite FTS5's `unicode61` default categories, or a
 * `split(Regex("\\W+"))` — is wrong in two opposite directions at once. It treats an entire
 * unbroken Han / Khmer / Lao / Myanmar run as **one token**, so `東京駅で会った` is unsearchable by
 * anything shorter than the whole string; and it treats Devanagari matras, the virama, and Arabic
 * harakat as **separators**, shattering words mid-syllable (`कि` → `क` + break). [segment] fixes
 * both by doing the word breaking here, in Kotlin, before any text reaches a storage engine, so
 * that engine only ever sees pre-segmented, space-joined tokens.
 *
 * ### One seam, and why it is a seam
 * Boundary detection sits behind the [BoundarySource] fun-interface. That is not abstraction for
 * its own sake — it is the line between what this pure-JVM module can do and what it cannot:
 *
 *  - [JavaTextBoundarySource] is `java.text.BreakIterator` from the JDK. It runs identically on
 *    a plain JVM and on Android, and it handles Devanagari clusters, Arabic harakat, ZWNJ/ZWJ,
 *    Thai, and mixed-script text correctly. It is [defaultSource], so a host that does nothing
 *    gets a working segmenter.
 *  - It does **not** carry dictionary data for lone-script Han or Thai runs. A Chinese sentence
 *    with no punctuation stays a single token on it — precisely the failure this file exists to
 *    fix, still open in that one case.
 *
 * **An Android host should inject an ICU-backed [BoundarySource]** wrapping
 * `android.icu.text.BreakIterator.getWordInstance(locale)`, which is ICU4C on-device and does
 * have genuine dictionary data for Chinese, Japanese, Thai, Lao, Khmer and Myanmar. That
 * implementation cannot live here: this module is plain `kotlin("jvm")` with no Android SDK on
 * the compile classpath, by design (see the module's `build.gradle.kts`). The seam is how the
 * capability gets in without the dependency. `android.icu.text.BreakIterator` has the same
 * `first()`/`next()`/`DONE` shape as the JDK type but is not a subtype of it, so the host's
 * adapter is a short loop, not a cast.
 *
 * ### The offset contract (must not be broken)
 * Every [TokenSpan] carries `startOriginal`/`endOriginal` indexing the **original,
 * un-normalized** text, alongside the already-normalized token string. Normalization
 * ([Normalizer.normalize]) can change a token's *length* (`ﬁ` → `fi`, `①` → `1`, half-width
 * katakana → full-width) without changing where it sits in the original text. Offsets always
 * come from the boundary source over the original string, taken **before** normalization runs,
 * so the contract holds by construction rather than by arithmetic.
 */
object Segmenter {

    /**
     * One segmented word. [startOriginal] (inclusive) and [endOriginal] (exclusive) index the
     * *original* text passed to [segment]; [normalized] is that substring after
     * [Normalizer.normalize].
     */
    data class TokenSpan(
        val startOriginal: Int,
        val endOriginal: Int,
        val normalized: String,
    )

    /**
     * A source of Unicode word-boundary offsets into `text`, `BreakIterator`-style: sorted,
     * starting at `0`, ending at `text.length`, each consecutive pair delimiting one candidate
     * word-or-separator unit. [segment] decides which of those units are words.
     */
    fun interface BoundarySource {
        fun boundaries(text: String, locale: Locale): List<Int>
    }

    /**
     * `java.text.BreakIterator`: part of the standard JDK, identical behaviour on the JVM and on
     * Android, no dictionary data for lone-script Han/Thai runs. See the class KDoc.
     */
    object JavaTextBoundarySource : BoundarySource {
        override fun boundaries(text: String, locale: Locale): List<Int> {
            val iterator = java.text.BreakIterator.getWordInstance(locale)
            iterator.setText(text)
            val out = ArrayList<Int>()
            var b = iterator.first()
            out.add(b)
            while (b != java.text.BreakIterator.DONE) {
                val next = iterator.next()
                if (next == java.text.BreakIterator.DONE) break
                out.add(next)
                b = next
            }
            if (out.last() != text.length) out.add(text.length)
            return out
        }
    }

    /**
     * What [segment] uses when the caller does not say otherwise: [JavaTextBoundarySource].
     *
     * There is deliberately no runtime probe for a better implementation. The Android-side engine
     * this was ported from probed for `android.icu` and silently fell back when the SDK stub
     * threw, which meant the segmentation quality a given call got depended on which process it
     * happened to run in — untestable, and invisible when it regressed. Here the choice is the
     * host's, made once at wiring time and passed in.
     */
    val defaultSource: BoundarySource = JavaTextBoundarySource

    /**
     * Segment [text] into words, dropping whitespace- and punctuation-only runs. Empty for blank
     * input.
     *
     * @param locale selects the boundary **rules** only, and is the one locale this module still
     *   takes. Case folding is [Locale.ROOT] everywhere and is not a parameter anywhere — see the
     *   note inside, and [Normalizer]'s KDoc, on why those two must not be the same locale.
     */
    fun segment(
        text: String,
        locale: Locale = Locale.ROOT,
        boundarySource: BoundarySource = defaultSource,
    ): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()
        val bounds = boundarySource.boundaries(text, locale)
        val spans = ArrayList<TokenSpan>(bounds.size)
        for (i in 0 until bounds.size - 1) {
            val start = bounds[i]
            val end = bounds[i + 1]
            if (start >= end) continue
            val raw = text.substring(start, end)
            // Note `locale` is NOT passed on. The segmentation locale picks break rules; case
            // folding has to be locale-independent, because an index built by a `tr` host would
            // otherwise fold `I` to `ı` while a query tokenized anywhere else folds it to `i`, and
            // the two would never match. [Normalizer] takes no folding locale at all, so this is
            // now structural rather than a rule this call site has to remember.
            val normalized = Normalizer.normalize(raw)
            // Filter on the *normalized* form, not the raw one. Some word-like symbols (`①`, in
            // category No) are not letters or digits themselves but NFKC-fold to characters that
            // are (`1`) — checking post-normalization is what actually decides "is this a word",
            // and checking pre-normalization silently drops them.
            if (normalized.isNotEmpty() && normalized.any { it.isLetterOrDigit() }) {
                spans.add(TokenSpan(start, end, normalized))
            }
        }
        return spans
    }

    /**
     * [segment], then space-join the normalized tokens — the pre-segmented, space-joined form a
     * storage engine's text column actually receives.
     */
    fun tokenizeForIndex(
        text: String,
        locale: Locale = Locale.ROOT,
        boundarySource: BoundarySource = defaultSource,
    ): String = segment(text, locale, boundarySource).joinToString(" ") { it.normalized }
}
