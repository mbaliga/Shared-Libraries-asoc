package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import kotlin.math.abs

/**
 * The number itself, with its label above and an optional delta below (viz.js `stat`). Direction is
 * an arrow shape and a sign, never a colour. Not interactive: its accessible name reads the whole
 * statement ("Crash-free sessions: 99.2%, up 0.4 pts vs last week").
 */
class StatView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data = Stat("")

    override val interactive: Boolean get() = false

    fun setData(s: Stat) { data = s; changed() }

    private fun labelSize() = rem() * 0.72f
    private fun valueSize() = rem() * (options.size ?: 2.6f)
    private fun valueText() = data.value?.let { fmt(it) } ?: (data.valueText ?: "")

    /** The delta as shown after its arrow: a plus for a rise, the unit appended. */
    internal fun deltaText(): String? = data.delta?.let { (if (it > 0) "+" else "") + fmt(it) + (data.deltaUnit ?: "") }

    internal fun direction(): String? = data.delta?.let { if (it > 0) "up" else if (it < 0) "down" else "flat" }

    override fun naturalHeight(w: Float, avail: Float?): Float =
        labelSize() * 1.35f + valueSize() * 1.1f + (if (data.delta != null) labelSize() * 1.35f else 0f)

    override fun table() = VizTable(null, listOf(listOf(data.label, data.value?.let { fmt(it) } ?: (data.valueText ?: ""))))

    override fun describe(): String = buildString {
        append(data.label).append(": ").append(valueText())
        deltaText()?.let { append(", ").append(direction()).append(' ').append(it.removePrefix("+").removePrefix("-")) }
        data.vs?.let { append(' ').append(it) }
    }

    override fun build(w: Float, h: Float) = Unit

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val ls = labelSize(); val vs = valueSize()
        var y = 0f
        fun base(top: Float, lh: Float, size: Float) = top + (lh - size) / 2 + size * 0.8f
        text(c, data.label, 0f, base(y, ls * 1.35f, ls), ls, alpha = 0.72f); y += ls * 1.35f
        text(c, valueText(), 0f, base(y, vs * 1.1f, vs), vs); y += vs * 1.1f
        val d = data.delta ?: return
        val bl = base(y, ls * 1.35f, ls)
        // The arrow is drawn, not typed, so it never depends on a font having the glyph.
        val s = ls * 0.7f
        val ps = paintScratch()
        ps.shader = null; ps.style = Paint.Style.FILL; ps.color = theme.ink; ps.alpha = 255
        val cy = bl - ls * 0.3f
        val arrow = Path()
        when {
            d > 0 -> { arrow.moveTo(s / 2, cy - s / 2); arrow.lineTo(s, cy + s / 2); arrow.lineTo(0f, cy + s / 2) }
            d < 0 -> { arrow.moveTo(0f, cy - s / 2); arrow.lineTo(s, cy - s / 2); arrow.lineTo(s / 2, cy + s / 2) }
            else -> { arrow.moveTo(0f, cy - s * 0.12f); arrow.lineTo(s * 0.55f, cy - s * 0.12f); arrow.lineTo(s * 0.55f, cy - s / 2); arrow.lineTo(s, cy); arrow.lineTo(s * 0.55f, cy + s / 2); arrow.lineTo(s * 0.55f, cy + s * 0.12f); arrow.lineTo(0f, cy + s * 0.12f) }
        }
        arrow.close()
        c.drawPath(arrow, ps)
        val x = s + ls * 0.4f
        val t = deltaText() ?: return
        text(c, t, x, bl, ls, weight = 500)
        data.vs?.let { text(c, it, x + textWidth(t, ls, 500) + ls * 0.4f, bl, ls, alpha = 0.7f) }
    }
}
