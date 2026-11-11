package dev.aarso.cellshell.core

/** One labelled stop on the edge strip. [itemIndex] is where it begins in the host's list. */
data class ScrubberStop(val label: String, val itemIndex: Int)

/** Which item a fraction of the way down the strip refers to; clamps, empty list answers 0. */
fun indexForFraction(fraction: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    if (fraction.isNaN()) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * itemCount).toInt().coerceIn(0, itemCount - 1)
}

/** The last stop that begins at or before [index]; null if none. [stops] ascending. */
fun stopForIndex(stops: List<ScrubberStop>, index: Int): ScrubberStop? =
    stops.lastOrNull { it.itemIndex <= index }

/** The y of an item's band centre on the strip, in px. */
fun itemCenterY(itemIndex: Int, itemCount: Int, heightPx: Float): Float {
    if (itemCount <= 0) return 0f
    return ((itemIndex + 0.5f) / itemCount).coerceIn(0f, 1f) * heightPx
}

/** The stops whose labels fit, greedily thinned so none lands within [minGapPx] of the last. */
fun thinnedLabels(
    stops: List<ScrubberStop>,
    itemCount: Int,
    heightPx: Float,
    minGapPx: Float,
): List<ScrubberStop> {
    if (stops.isEmpty() || itemCount <= 0 || heightPx <= 0f) return emptyList()
    val kept = ArrayList<ScrubberStop>(stops.size)
    var lastY = Float.NEGATIVE_INFINITY
    for (stop in stops) {
        val y = itemCenterY(stop.itemIndex, itemCount, heightPx)
        if (y - lastY < minGapPx) continue
        kept += stop
        lastY = y
    }
    return kept
}
