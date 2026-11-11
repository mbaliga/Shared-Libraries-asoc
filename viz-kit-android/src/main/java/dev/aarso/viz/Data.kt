package dev.aarso.viz

/**
 * The data shapes of the kit. They are exactly viz.js's (see CONTRACT.md), so one JSON feed can drive
 * both; [VizJson] parses and writes them.
 */

/** One part: a column, a ranked row, a slice, a segment. [fill] pins its texture so a filter never repaints the survivors. */
data class Datum(
    val label: String,
    val value: Double,
    val fill: Texture? = null,
    /** Only on the folded "Other": the parts it swallowed. */
    val parts: List<Datum> = emptyList(),
)

/** One line on a [LineView]; a null value is a gap. */
data class Series(val label: String? = null, val values: List<Double?>, val fill: Texture? = null)

/** A matrix where magnitude is the area of a dot. [rows] and [cols] are optional axis names. */
data class Matrix(val values: List<List<Double>>, val rows: List<String>? = null, val cols: List<String>? = null)

/** A figure against its ceiling. */
data class Meter(val value: Double, val max: Double = 100.0, val label: String? = null)

/**
 * The number itself. [value] is a number (formatted by the theme) or, when the host has already
 * formatted it, [valueText]. Direction is an arrow, never a colour: [delta] above zero is up, zero is flat.
 */
data class Stat(
    val label: String,
    val value: Double? = null,
    val valueText: String? = null,
    val delta: Double? = null,
    val deltaUnit: String? = null,
    val vs: String? = null,
)

/** One key of a legend: a texture, or (beside a line chart) a dash. */
data class LegendItem(
    val label: String,
    val fill: Texture? = null,
    /** A dash pattern like `"7 5"`; `""` is a solid line; null takes the default for the item's position. */
    val dash: String? = null,
    val value: Double? = null,
) {
    /** [dash] as an array, or null for a solid line. */
    fun dashArray(): FloatArray? = parseDash(dash)

    companion object {
        fun parseDash(s: String?): FloatArray? {
            val parts = s?.trim()?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() }?.mapNotNull { it.toFloatOrNull() }.orEmpty()
            return if (parts.isEmpty()) null else parts.toFloatArray()
        }
    }
}

/**
 * What a mark reports when it is hovered, pressed, focused or selected. [text] is the figure as shown
 * (`"94"`, `"61 · 45%"`); [datum] is set for the charts whose marks are [Datum]s.
 */
data class Mark(
    val index: Int,
    val label: String,
    val value: Double,
    val text: String,
    val datum: Datum? = null,
) {
    /** What TalkBack says: the name, then the figure. */
    val description: String get() = "$label: $text"
}

/** The same figures as a table, for a table view beside the chart. */
data class VizTable(val head: List<String>?, val rows: List<List<String>>) {
    /** Tab-separated text: a header line if there is one, then a line per row. */
    fun text(): String = buildString {
        head?.let { append(it.joinToString("\t")).append('\n') }
        rows.forEachIndexed { i, r -> if (i > 0) append('\n'); append(r.joinToString("\t")) }
    }
}

/**
 * The options a chart takes besides its data: viz.js's, minus the web-only ones. They travel in a
 * feed too (`VizJson.options`). Views also expose each as a property.
 */
data class ChartOptions(
    /** Fan: 90 (corner quarter), 180 (half, default) or 360 (ring). */
    val sweep: Int = 180,
    /** Fan: hole radius as a fraction of the radius, 0..0.9; null is 0.6 for a ring and 0 otherwise. */
    val inner: Float? = null,
    /** Fan: callout text; null is the total. */
    val callout: String? = null,
    /** Fan: false hides the callout. */
    val showCallout: Boolean = true,
    /** Fan ring: a caption under the figure. */
    val caption: String? = null,
    /** Line: x labels (the first and last are drawn). */
    val x: List<String> = emptyList(),
    val xTitle: String? = null,
    /** Line: include zero on the axis (default true). */
    val zero: Boolean = true,
    val area: Boolean = true,
    val grid: Boolean = false,
    val smooth: Boolean = true,
    /** Column heading for the value in the table view. */
    val unit: String? = null,
    /** One texture for every mark (null: by order). */
    val fill: Texture? = null,
    /** Bars: every row takes its own texture by order. */
    val fillEach: Boolean = false,
    /** Meter: the pill alone. */
    val plain: Boolean = false,
    /** Halftone: cap on the cell size in dp. */
    val cell: Float? = null,
    /** Bars, stack, meter: thickness in dp. */
    val thickness: Float? = null,
    /** Accessible name of the chart; null is built from the data. */
    val label: String? = null,
    /** Height in dp, overriding the chart's own. */
    val height: Float? = null,
    /** Legend: items in a wrapping row instead of a column. */
    val row: Boolean = false,
    /** Legend: show each item's value. */
    val values: Boolean = false,
    /** Legend: dash keys beside a line chart. */
    val lines: Boolean = false,
    /** Legend: solid keys at the kit's four steps of ink, for stacked series. */
    val shades: Boolean = false,
    /** Stat: size of the figure in rem (default 2.6). */
    val size: Float? = null,
)
