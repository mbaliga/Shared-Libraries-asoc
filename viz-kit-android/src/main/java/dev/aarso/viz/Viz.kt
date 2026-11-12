package dev.aarso.viz

/**
 * Constants of the kit. Mirrors `ASOC.viz` (web/viz-kit/viz.js 0.2.0): the same fixed texture order,
 * the same four dashes for line series, the same steps of one ink for stacked series.
 */
object Viz {
    /** This library's version. Keep in step with `vizVersion` in build.gradle.kts (VersionTest pins both). */
    const val VERSION = "0.1.0"

    /** The viz.js version this was ported from; the data contract is identical to it. */
    const val WEB_VERSION = "0.2.0"

    /** The textures in their fixed order. A mark takes its texture from its position, or from its own fill. */
    val ORDER: List<Texture> = Texture.values().toList()

    /** Dash patterns for up to four line series on one axis; null is a solid line. Same as `ASOC.viz.dashes`. */
    val DASHES: List<FloatArray?> = listOf(null, floatArrayOf(7f, 5f), floatArrayOf(1.5f, 5f), floatArrayOf(11f, 4f, 2f, 4f))

    /** Steps of the one ink, for series that stack in order. Same as `ASOC.viz.shades`. */
    val SHADES: List<Float> = listOf(0.92f, 0.55f, 0.3f, 0.16f)

    /** Smallest touch target (dp) a tappable mark is given where the layout has the room. */
    const val MIN_TARGET_DP = 44f

    /** Target size (dp) tappable marks are grown to where the layout allows. */
    const val TARGET_DP = 48f

    /**
     * More than eight parts: the first seven keep their textures, the rest become one "Other".
     * There is no ninth texture. Applied by fan, stack and legend, as in viz.js.
     */
    fun fold(d: List<Datum>): List<Datum> {
        if (d.size <= ORDER.size) return d
        val head = d.subList(0, ORDER.size - 1)
        val rest = d.subList(ORDER.size - 1, d.size)
        return head + Datum("Other", rest.sumOf { it.value }, parts = rest)
    }
}
