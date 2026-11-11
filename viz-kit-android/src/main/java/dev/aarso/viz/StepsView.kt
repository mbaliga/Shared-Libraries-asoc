package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.max
import kotlin.math.min

/**
 * Columns from one baseline, the figure and its name standing on each (viz.js `steps`). Bars start
 * at zero on a linear scale, so a value of 1 beside 94 is a sliver, and says so. Takes the height the
 * parent gives it, or 12 rem.
 */
class StepsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data: List<Datum> = emptyList()
    private var cols: List<Col> = emptyList()

    /** One laid-out column, in dp; kept for geometry tests. */
    internal class Col(val datum: Datum, val x: Float, val y: Float, val w: Float, val h: Float, val figureY: Float, val nameY: Float, val r: Float)

    fun setData(d: List<Datum>) { data = d; changed() }

    internal fun columns(): List<Col> { ensureBuilt(); return cols }

    override fun naturalHeight(w: Float, avail: Float?): Float = if (avail != null && avail > 60) avail else rem() * 12

    override fun table() = VizTable(listOf("", options.unit ?: "Value"), data.map { listOf(it.label, fmt(it.value)) })

    override fun build(w: Float, h: Float) {
        val rem = rem(); val n = max(1, data.size)
        val gap = max(6f, w * 0.03f)
        val bw = (w - gap * (n - 1)) / n
        val big = min(rem * 1.5f, bw * 0.5f); val small = rem * 0.64f
        val head = big * 0.8f + small + rem * 0.85f
        val plot = h - head
        val mx = data.maxOfOrNull { max(0.0, it.value) } ?: 0.0
        cols = data.mapIndexed { i, it ->
            val v = max(0.0, it.value)
            val bh = if (v > 0) max(3f, (v / (if (mx == 0.0) 1.0 else mx)).toFloat() * plot) else 0f
            val x = i * (bw + gap); val y = h - bh
            val r = min(min(options.thickness ?: rem * 0.9f, bw / 2), bh)
            Col(it, x, y, bw, bh, y - small - rem * 0.62f, y - rem * 0.4f, r)
        }
        data.forEachIndexed { i, it ->
            val c = cols[i]
            val m = Mark(i, it.label, it.value, fmt(it.value), it)
            val box = RectF(c.x - gap / 2, 0f, c.x + c.w + gap / 2, h)
            addNode(node(m, m.description, box, w, h, c.x + c.w / 2, c.figureY - big, minTarget = Viz.MIN_TARGET_DP))
        }
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val rem = rem(); val small = rem * 0.64f
        val n = max(1, data.size); val gap = max(6f, w * 0.03f); val bw = (w - gap * (n - 1)) / n
        val big = min(rem * 1.5f, bw * 0.5f)
        cols.forEachIndexed { i, col ->
            val a = dim(i)
            if (col.h > 0) fill(c, Shapes.column(col.x, col.y, col.w, h, col.r), textureFor(col.datum, i), RectF(col.x, col.y, col.x + col.w, h), FadeDir.UP, a)
            text(c, fmt(col.datum.value), col.x + 1, col.figureY, big, alpha = a)
            text(c, Shapes.clip(col.datum.label, small, col.w + gap * 0.6f), col.x + 1, col.nameY, small, alpha = 0.72f * a)
        }
    }
}
