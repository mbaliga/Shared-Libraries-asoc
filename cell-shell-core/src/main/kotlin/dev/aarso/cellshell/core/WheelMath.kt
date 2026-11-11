package dev.aarso.cellshell.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val WEIGHT_STEP = 25
private const val WEIGHT_NORMAL = 400
private const val WEIGHT_BOLD = 700

/** Integer alpha curve for a row [distance] rows from focus; floors at 0.22. */
fun railItemAlpha(distance: Int, isSelected: Boolean): Float {
    if (isSelected) return 1f
    return (0.62f - 0.10f * distance.coerceAtMost(5)).coerceAtLeast(0.22f)
}

/** [railItemAlpha] interpolated at a fractional distance. */
fun wheelAlpha(distance: Float): Float {
    val d = abs(distance)
    val low = floor(d).toInt()
    val fraction = d - low
    val nearer = railItemAlpha(low, isSelected = low == 0)
    val further = railItemAlpha(low + 1, isSelected = false)
    return nearer + (further - nearer) * fraction
}

/**
 * Numeric font weight (1..1000) for a row: bold on the focus line, normal a row out, quantised
 * to steps of 25. `:cell-shell` wraps this in `FontWeight(...)`.
 */
fun wheelFontWeightValue(distance: Float): Int {
    val focus = (1f - abs(distance)).coerceIn(0f, 1f)
    val raw = WEIGHT_NORMAL + (WEIGHT_BOLD - WEIGHT_NORMAL) * focus
    val stepped = (raw / WEIGHT_STEP).roundToInt() * WEIGHT_STEP
    return stepped.coerceIn(1, 1000)
}

/** Squash of the travelling bullet: 0 at either end, 1 at the midpoint (parabola). */
fun bulletSquash(current: Float, from: Float, to: Float): Float {
    val span = to - from
    if (abs(span) < 0.001f) return 0f
    val progress = ((current - from) / span).coerceIn(0f, 1f)
    return 4f * progress * (1f - progress)
}
