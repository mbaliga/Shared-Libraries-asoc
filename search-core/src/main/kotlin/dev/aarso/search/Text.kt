package dev.aarso.search

import java.util.Locale

/**
 * `java.text.Normalizer` is shadowed by this file's own [Normalizer] object, so it gets an
 * unambiguous local name. File-private, so nothing outside this file sees it.
 */
private typealias JdkNormalizer = java.text.Normalizer

/** Nested types are not reachable through a typealias, so [java.text.Normalizer.Form] needs its
 *  own alias for the same shadowing reason. */
private typealias JdkNormalizerForm = java.text.Normalizer.Form

/**
 * Unicode-aware text folding and match location — the bottom of the matching stack.
 *
 * Everything in this module that compares two pieces of text does it through [normalize], and
 * everything that reports *where* a match landed does it through [findMatches]. Keeping both in
 * one object is what makes "the index and the query agree" checkable by reading one file.
 *
 * ### Script-awareness (the binding requirement)
 * Matching is Unicode-aware, not naive byte matching. [normalize] folds case and Unicode form so
 * that the same word written with different code points (composed vs decomposed, full-width vs
 * half-width) matches, while **non-ASCII is never stripped** — Devanagari, Japanese, and
 * Arabic-script text survive normalization intact and match against queries in the same script.
 * There is no transliteration and no language guessing: a query in a script matches text in that
 * script.
 *
 * ### What changed relative to Android-IDE-core
 * [findMatches] used to have a documented escape hatch: when NFKC changed a string's length it
 * abandoned original coordinates and returned ranges over the *normalized* text instead. That is
 * in-bounds but wrong, and it went wrong on exactly the inputs that need highlighting most —
 * ligatures, full-width forms, half-width katakana, circled digits, Indic composition. This file
 * replaces the escape hatch with a real offset map (see [findMatches]), so the guarantee
 * [Highlight] documents is now actually true.
 *
 * ### Case folding is [Locale.ROOT], and is not a parameter
 * Not a default a caller may override — there is no caller-facing locale here at all, and that is
 * the point. "The index and the query agree" is a property of the *pair*, and the two sides are
 * folded by different code at different times: [Segmenter] folds documents at index time,
 * [tokenizeQuery] folds the search box at query time. Any knob that either side can set
 * independently is a knob that can make them disagree, and the disagreement is silent — a `tr`
 * host would index `INDEX` as `index` and tokenize the same query as `ındex`, and simply never
 * match, for one locale's users only. Removing the parameter is what makes the agreement
 * structural instead of a thing every call site has to remember. (The locale [Segmenter.segment]
 * takes is unrelated and stays: it picks BreakIterator break *rules*.)
 */
object Normalizer {

    /**
     * Unicode-aware normalization for matching. Steps, in order:
     *  1. **NFKC normalize** — canonical + compatibility folding, so composed/decomposed forms
     *     and full-width/half-width variants unify.
     *  2. **Lowercase** with [Locale.ROOT] — locale-independent case folding with no Turkish-i
     *     surprises, and a no-op for caseless scripts. Not overridable; see the object's KDoc.
     *  3. **Collapse whitespace** — runs of ASCII whitespace become a single space, then the
     *     result is trimmed.
     *
     * The order matters and is load-bearing: NFKC before lowercasing is what lets a full-width
     * `Ａ` fold to `a` in one step, and collapsing last is what lets NFKC-introduced spacing
     * (e.g. `U+00A0`-adjacent compatibility forms) be swept up.
     *
     * NFKC can change a string's length (`ﬁ` → `fi`, `①` → `1`), which is exactly why
     * [findMatches] cannot assume normalized indices are original indices.
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        return collapseAndTrim(fold(s), null, null)
    }

    /**
     * Tokenize a raw query into match terms: [normalize] it, split on the single spaces that
     * normalization leaves behind, and drop blanks. A blank or empty query yields an empty list,
     * which every caller treats as "no query" rather than "matches nothing".
     */
    fun tokenizeQuery(q: String): List<String> =
        normalize(q).split(' ').filter { it.isNotBlank() }

    /**
     * Find the character ranges in [text] where any of [queryTerms] occurs, case- and
     * form-insensitively. Matching happens over the normalized text; the returned ranges are
     * **always** in the coordinates of the original [text], never the normalized form.
     *
     * ### How original coordinates are recovered
     * Normalization is re-run as a tracked transform ([analyze]) that records, for every
     * character of the normalized string, which slice of the original produced it:
     *
     *  - **Folding** (NFKC + lowercase) is aligned by splitting the original at positions where
     *    no Unicode composition can straddle the seam — never inside a surrogate pair, never
     *    before a combining mark, never inside a Hangul jamo run, never before a half-width
     *    voiced sound mark — and matching each piece's folded form against the real folded
     *    string. When a seam turns out to be unsafe anyway, the piece is grown until it lines up
     *    again, so the map self-heals instead of drifting.
     *  - **Whitespace collapse and trim** are performed by hand rather than by a regex, so the
     *    index each surviving character came from is known by construction.
     *
     * A `ﬁ` that folds to two characters therefore maps *both* of them back onto the single
     * original character, and highlighting either one highlights the ligature. A collapsed run of
     * five spaces maps its one normalized space back onto all five.
     *
     * If alignment cannot be established at all — a script with context-sensitive casing rules
     * this alignment does not model, for instance — the map degrades to "the whole original
     * string", which widens a highlight but never points it at the wrong text and never goes out
     * of bounds. It does not fall back to normalized coordinates; that failure mode is gone.
     *
     * Ranges are merged when they overlap or abut, and returned sorted by start. [queryTerms] are
     * assumed already normalized, as produced by [tokenizeQuery].
     */
    fun findMatches(
        text: String,
        queryTerms: List<String>,
    ): List<IntRange> {
        if (text.isEmpty() || queryTerms.isEmpty()) return emptyList()
        val analyzed = analyze(text)
        val normalized = analyzed.text
        if (normalized.isEmpty()) return emptyList()

        val raw = ArrayList<IntRange>()
        for (term in queryTerms) {
            if (term.isEmpty()) continue
            var from = 0
            while (true) {
                val idx = normalized.indexOf(term, from)
                if (idx < 0) break
                raw.add(idx..(idx + term.length - 1))
                from = idx + 1 // allow overlapping occurrences
            }
        }
        if (raw.isEmpty()) return emptyList()

        // Merge in normalized coordinates first, exactly as the original did, so the number and
        // shape of spans is unchanged for the length-preserving case.
        raw.sortBy { it.first }
        val mergedNormalized = mergeSorted(raw)

        // Then project into original coordinates and merge *again*: the map is expanding (one
        // original character can back several normalized ones and vice versa), so two spans that
        // were disjoint in the normalized string can overlap or abut in the original.
        val projected = mergedNormalized.map { analyzed.toOriginalRange(it) }
        return mergeSorted(projected)
    }

    // ---------------------------------------------------------------------------------------
    // Folding
    // ---------------------------------------------------------------------------------------

    /** Steps 1 and 2 of [normalize]: NFKC then case fold. Split out because the offset map has to
     *  run these two together and the whitespace pass separately. [Locale.ROOT] is the single
     *  folding locale for the whole module — see the object's KDoc for why it is not a parameter. */
    private fun fold(s: String): String =
        JdkNormalizer.normalize(s, JdkNormalizerForm.NFKC).lowercase(Locale.ROOT)

    /**
     * Kotlin's `Regex("\\s+")` is Java's `\s`, which — without `UNICODE_CHARACTER_CLASS` — is the
     * six ASCII whitespace characters and nothing else. The original collapsed with exactly that
     * regex, so this predicate reproduces exactly that set. It is deliberately *narrower* than
     * [Char.isWhitespace], which is what the subsequent trim uses; widening it here would be a
     * silent change to what normalizes equal.
     */
    private fun isCollapsibleSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /**
     * Step 3 of [normalize], hand-rolled so offsets are recoverable: replace every run of
     * [isCollapsibleSpace] with a single space, then trim [Char.isWhitespace] from both ends —
     * byte-for-byte what `.replace(Regex("\\s+"), " ").trim()` does.
     *
     * When [srcStart] / [srcEnd] are non-null they are filled with, for each character of the
     * returned string, the first and last index in [folded] that produced it. They must be at
     * least `folded.length` long. Passing null skips the bookkeeping entirely, which is what
     * [normalize] does — it is on the hot path for every field of every document.
     */
    private fun collapseAndTrim(folded: String, srcStart: IntArray?, srcEnd: IntArray?): String {
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val at = out.length
            if (isCollapsibleSpace(folded[i])) {
                var j = i
                while (j < folded.length && isCollapsibleSpace(folded[j])) j++
                srcStart?.set(at, i)
                srcEnd?.set(at, j - 1)
                out.append(' ')
                i = j
            } else {
                srcStart?.set(at, i)
                srcEnd?.set(at, i)
                out.append(folded[i])
                i++
            }
        }
        // Trim by index rather than by String.trim() so the caller can slice the maps the same way.
        var lo = 0
        var hi = out.length
        while (lo < hi && out[lo].isWhitespace()) lo++
        while (hi > lo && out[hi - 1].isWhitespace()) hi--
        if (srcStart != null && srcEnd != null && lo > 0) {
            // Shift the surviving entries down so index k of the result is entry k of the map.
            for (k in lo until hi) {
                srcStart[k - lo] = srcStart[k]
                srcEnd[k - lo] = srcEnd[k]
            }
        }
        return out.substring(lo, hi)
    }

    // ---------------------------------------------------------------------------------------
    // The offset map
    // ---------------------------------------------------------------------------------------

    /**
     * A normalized string plus, for each of its characters, the slice of the original text that
     * produced it. [startInOriginal] and [endInOriginal] are parallel to [text] and both
     * inclusive.
     */
    private class Analyzed(
        val text: String,
        val startInOriginal: IntArray,
        val endInOriginal: IntArray,
        val originalLength: Int,
    ) {
        /** Project a range over [text] onto the original coordinates it came from. */
        fun toOriginalRange(r: IntRange): IntRange {
            val lastIndex = originalLength - 1
            val a = startInOriginal[r.first.coerceIn(0, text.length - 1)].coerceIn(0, lastIndex)
            val b = endInOriginal[r.last.coerceIn(0, text.length - 1)].coerceIn(0, lastIndex)
            return if (a <= b) a..b else a..a
        }
    }

    /** How many adjacent pieces may be glued together while hunting for an alignment before the
     *  folding stage gives up on that seam. Compositions never span more than a handful of code
     *  points, so this bound is generous; it exists only to keep a pathological input linear. */
    private const val MAX_MERGE = 8

    /** [normalize], but retaining the map back to original coordinates. */
    private fun analyze(text: String): Analyzed {
        val folded = fold(text)
        val foldStart: IntArray
        val foldEnd: IntArray

        // Fast path, and the overwhelmingly common one. `isNormalized` means NFKC is the identity
        // here, and lowercasing can only ever expand a code point (U+0130 is the one real case) —
        // never contract it. So an unchanged total length proves every code point mapped 1:1 and
        // the indices already line up. This is the same assumption the original made, but here it
        // is *established* rather than assumed, and there is a real map behind it when it fails.
        if (folded.length == text.length && JdkNormalizer.isNormalized(text, JdkNormalizerForm.NFKC)) {
            foldStart = IntArray(folded.length) { it }
            foldEnd = IntArray(folded.length) { it }
        } else {
            val built = buildFoldMap(text, folded)
            if (built == null) {
                // Alignment failed. Attribute every normalized character to the whole original
                // string: a wide highlight, but an honest one in the right coordinate space.
                foldStart = IntArray(folded.length) { 0 }
                foldEnd = IntArray(folded.length) { text.length - 1 }
            } else {
                foldStart = built.first
                foldEnd = built.second
            }
        }

        val viaStart = IntArray(folded.length)
        val viaEnd = IntArray(folded.length)
        val normalized = collapseAndTrim(folded, viaStart, viaEnd)

        // Compose the two stages: normalized[k] came from folded[viaStart[k]..viaEnd[k]], which
        // came from original[foldStart[viaStart[k]]..foldEnd[viaEnd[k]]].
        val start = IntArray(normalized.length)
        val end = IntArray(normalized.length)
        for (k in 0 until normalized.length) {
            start[k] = foldStart[viaStart[k]]
            end[k] = foldEnd[viaEnd[k]]
        }
        return Analyzed(normalized, start, end, text.length)
    }

    /**
     * Align [text] against its folded form [folded], returning per-folded-character start and end
     * indices into [text], or null if no alignment could be established.
     *
     * The original is cut at seams that no Unicode composition can straddle
     * ([isSafeSplitBefore]); each piece is folded on its own and matched against [folded] at the
     * current write position. A seam that turns out to be unsafe after all shows up immediately
     * as a mismatch, and the piece is grown one seam at a time until it lines up again — so the
     * seam rule only has to be *mostly* right, not provably complete.
     *
     * The final fallback inside the loop accepts a piece on length alone. That covers
     * context-sensitive case folding, where the folded characters legitimately differ from what
     * the piece folds to in isolation (Greek final sigma: `Σ` folds to `σ` alone but to `ς` at
     * the end of a word) while the character *count* is identical. Taking the length and reading
     * the actual characters from [folded] keeps matching correct and the map correct.
     */
    private fun buildFoldMap(text: String, folded: String): Pair<IntArray, IntArray>? {
        val seams = ArrayList<Int>()
        seams.add(0)
        for (i in 1 until text.length) if (isSafeSplitBefore(text, i)) seams.add(i)
        seams.add(text.length)

        val start = IntArray(folded.length)
        val end = IntArray(folded.length)
        var out = 0
        var s = 0
        while (s < seams.size - 1) {
            val from = seams[s]
            var pieces = 0
            var length = -1
            var span = 1
            while (span <= MAX_MERGE && s + span < seams.size) {
                val f = fold(text.substring(from, seams[s + span]))
                if (folded.regionMatches(out, f, 0, f.length)) {
                    length = f.length
                    pieces = span
                    break
                }
                span++
            }
            if (length < 0) {
                val f = fold(text.substring(from, seams[s + 1]))
                if (out + f.length > folded.length) return null
                length = f.length
                pieces = 1
            }
            val to = seams[s + pieces]
            for (k in out until out + length) {
                start[k] = from
                end[k] = to - 1
            }
            out += length
            s += pieces
        }
        return if (out == folded.length) start to end else null
    }

    /**
     * Whether [text] may be cut immediately before index [i] without a Unicode composition
     * straddling the cut. Conservative on purpose — a false "no" only costs a slightly coarser
     * map, while a false "yes" is caught and repaired by the growth loop in [buildFoldMap].
     */
    private fun isSafeSplitBefore(text: String, i: Int): Boolean {
        val c = text[i]
        // Never split a surrogate pair; the halves are not characters.
        if (Character.isLowSurrogate(c)) return false
        val cp = if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
            Character.toCodePoint(c, text[i + 1])
        } else {
            c.code
        }
        // A combining mark composes onto whatever precedes it (`क` + nukta, `e` + acute).
        when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> return false
        }
        // The half-width voiced / semi-voiced sound marks are modifier *symbols*, not marks, so
        // the category test above misses them — but NFKC folds `ｶ` + `ﾞ` into a single `ガ`.
        if (cp == 0xFF9E || cp == 0xFF9F) return false
        // Hangul is the one place where two starters compose (L + V + T -> a syllable), so a jamo
        // run has to stay whole.
        if (isHangul(cp) || isHangul(text[i - 1].code)) return false
        return true
    }

    private fun isHangul(cp: Int): Boolean =
        cp in 0x1100..0x11FF || // Jamo
            cp in 0xA960..0xA97F || // Jamo Extended-A
            cp in 0xD7B0..0xD7FF || // Jamo Extended-B
            cp in 0xAC00..0xD7A3 // Precomposed syllables

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    /** Merge overlapping or abutting ranges in an already start-sorted list. */
    private fun mergeSorted(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = if (isSortedByStart(ranges)) ranges else ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            cur = if (r.first <= cur.last + 1) {
                cur.first..maxOf(cur.last, r.last)
            } else {
                merged.add(cur)
                r
            }
        }
        merged.add(cur)
        return merged
    }

    private fun isSortedByStart(ranges: List<IntRange>): Boolean {
        for (i in 1 until ranges.size) if (ranges[i].first < ranges[i - 1].first) return false
        return true
    }
}
