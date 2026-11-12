package dev.aarso.viz

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Number formatting. [compact] is viz.js's default `fmt`: grouped under 10,000, `12.3K` / `1.2M` above. */
object VizFormat {
    private val UNITS = listOf(1e3 to "K", 1e6 to "M", 1e9 to "B", 1e12 to "T")

    /**
     * Under 10,000: grouped, at most two decimals below 10 and one from 10 up (so 94 stays 94 and 3.14159
     * is 3.14). From 10,000: K / M / B / T with at most one decimal and no trailing zero. English suffixes
     * and the [locale]'s own separators.
     */
    @JvmOverloads
    fun compact(n: Double, locale: Locale = Locale.getDefault()): String {
        if (n.isNaN()) return "NaN"
        if (n.isInfinite()) return if (n > 0) "Infinity" else "-Infinity"
        val a = abs(n)
        if (a >= 1e4) {
            var i = UNITS.indexOfLast { a >= it.first }
            var scaled = round1(n / UNITS[i].first)
            if (abs(scaled) >= 1000 && i < UNITS.lastIndex) { i++; scaled = round1(n / UNITS[i].first) }
            val df = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = RoundingMode.HALF_UP }
            return df.format(scaled) + UNITS[i].second
        }
        val nf = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = if (a < 10) 2 else 1
            minimumFractionDigits = 0
            roundingMode = RoundingMode.HALF_UP
        }
        return nf.format(n)
    }

    private fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0
}
