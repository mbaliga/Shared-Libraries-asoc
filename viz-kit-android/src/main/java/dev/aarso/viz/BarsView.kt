package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.max

/**
 * Ranked rows: the name and the figure on the line above each bar (viz.js `bars`). Sets its own
 * height. Bars start at zero on one linear scale. A row is at least 44 dp tall so it is a touch
 * target (viz.js uses 2.5 rem, which is 40 at the default rem).
 */
class BarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data: List<Datum> = emptyList()
    private var rows: List<Row> = emptyList()

    /** One laid-out row in dp: the bar runs from x=0 to [bar] at [barTop], [thick] tall. */
    internal class Row(val datum: Datum, val y: Float, val bar: Float, val barTop: Float, val thick: Float, val value: String)

    fun setData(d: List<Datum>) { data = d; changed() }

    internal fun rowsLaidOut(): List<Row> { ensureBuilt(); return rows }

    private fun pitch() = max(rem() * 2.5f, Viz.MIN_TARGET_DP)

    override fun naturalHeight(w: Float, avail: Float?): Float = max(1, data.size) * pitch() - rem() * 0.5f

    override fun table() = VizTable(listOf("", options.unit ?: "Value"), data.map { listOf(it.label, fmt(max(0.0, it.value))) })

    override fun build(w: Float, h: Float) {
        val rem = rem(); val pitch = pitch(); val th = options.thickness ?: (rem * 0.75f); val small = rem * 0.72f
        val mx = data.maxOfOrNull { max(0.0, it.value) } ?: 0.0
        rows = data.mapIndexed { i, it ->
            val v = max(0.0, it.value)
            val y = i * pitch
            val bw = if (v > 0) max(3f, (v / (if (mx == 0.0) 1.0 else mx)).toFloat() * w) else 0f
            Row(it, y, bw, y + small + rem * 0.45f, th, fmt(v))
        }
        rows.forEachIndexed { i, r ->
            val m = Mark(i, r.datum.label, r.datum.value, r.value, r.datum)
            val box = RectF(0f, r.y, w, r.y + pitch)
            addNode(node(m, m.description, box, w, h, minOf(w, r.bar) / 2, r.y))
        }
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val rem = rem(); val small = rem * 0.72f
        rows.forEachIndexed { i, r ->
            val a = dim(i)
            text(c, Shapes.clip(r.datum.label, small, w - Shapes.est(r.value, small) - rem), 0f, r.y + small, small, alpha = 0.8f * a)
            text(c, r.value, w, r.y + small, small, Paint.Align.RIGHT, 500, a)
            if (r.bar > 0) {
                val tex = if (options.fillEach || r.datum.fill != null) textureFor(r.datum, i) else (options.fill ?: Texture.SOLID)
                fill(c, Shapes.bar(r.barTop, r.bar, r.thick), tex, RectF(0f, r.barTop, r.bar, r.barTop + r.thick), FadeDir.RIGHT, a)
            }
        }
    }
}
