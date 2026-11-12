package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A matrix where magnitude is the area of a dot (viz.js `halftone`). Sets its own height. Cells are
 * touchable one by one; TalkBack is given a node per cell where a cell is at least 44 dp across, and
 * otherwise a node per row (peak and total), because a swipe through 168 two-centimetre nodes is not
 * a reading. [tableText] always has every figure.
 */
class HalftoneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data = Matrix(emptyList())
    private var geo: Geo? = null
    private var rowNodes: List<Node> = emptyList()

    /** The grid in dp: cell (r, c) is centred at ([labW] + (c+.5)*[cell], [top] + (r+.5)*[cell]). */
    internal class Geo(val labW: Float, val nc: Int, val cell: Float, val top: Float, val small: Float, val max: Double)

    fun setData(m: Matrix) { data = m; changed() }

    internal fun geometry(): Geo? { ensureBuilt(); return geo }

    private fun geoFor(w: Float): Geo {
        val small = rem() * 0.64f
        var labW = data.rows?.maxOfOrNull { Shapes.est(it, small) } ?: 0f
        if (labW > 0f) labW += rem() * 0.6f
        val nc = max(1, data.values.firstOrNull()?.size ?: 0)
        val cell = min((w - labW) / nc, options.cell ?: (rem() * 2.2f))
        val top = if (data.cols != null) rem() * 1.2f else 0f
        val mx = data.values.maxOfOrNull { r -> r.maxOfOrNull { max(0.0, it) } ?: 0.0 } ?: 0.0
        return Geo(labW, nc, cell, top, small, mx)
    }

    override fun naturalHeight(w: Float, avail: Float?): Float { val g = geoFor(w); return g.top + data.values.size * g.cell }

    override fun table(): VizTable {
        val head = listOf("") + (data.cols ?: (data.values.firstOrNull() ?: emptyList()).indices.map { (it + 1).toString() })
        return VizTable(head, data.values.mapIndexed { i, r -> listOf(data.rows?.getOrNull(i) ?: (i + 1).toString()) + r.map { fmt(it) } })
    }

    override val a11yNodes: List<Node> get() {
        ensureBuilt()
        val g = geo ?: return nodes
        return if (g.cell >= Viz.MIN_TARGET_DP) nodes else rowNodes
    }

    override fun neighbour(i: Int, keyCode: Int): Int {
        val nc = geo?.nc ?: 1
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> i + 1
            KeyEvent.KEYCODE_DPAD_LEFT -> i - 1
            KeyEvent.KEYCODE_DPAD_DOWN -> i + nc
            else -> i - nc
        }
    }

    override fun build(w: Float, h: Float) {
        val g = geoFor(w); geo = g
        val rowsOut = ArrayList<Node>()
        data.values.forEachIndexed { r, row ->
            val rn = data.rows?.getOrNull(r) ?: (r + 1).toString()
            row.forEachIndexed { c, v ->
                val cn = data.cols?.getOrNull(c) ?: (c + 1).toString()
                val m = Mark(r * g.nc + c, "$rn · $cn", v, fmt(v))
                val cx = g.labW + (c + 0.5f) * g.cell; val cy = g.top + (r + 0.5f) * g.cell
                val box = RectF(cx - g.cell / 2, cy - g.cell / 2, cx + g.cell / 2, cy + g.cell / 2)
                addNode(node(m, m.description, box, w, h, cx, cy - g.cell / 2))
            }
            val peak = row.indices.maxByOrNull { row[it] } ?: 0
            val rb = RectF(0f, g.top + r * g.cell, w, g.top + (r + 1) * g.cell)
            val desc = "$rn: total ${fmt(row.sum())}, peak ${fmt(row.getOrElse(peak) { 0.0 })} at ${data.cols?.getOrNull(peak) ?: (peak + 1).toString()}"
            rowsOut.add(node(null, desc, rb, w, h, w / 2, rb.top, minTarget = Viz.TARGET_DP))
        }
        rowNodes = rowsOut
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val g = geo ?: return
        val ps = paintScratch()
        data.cols?.let { cols ->
            val longest = cols.maxByOrNull { it.length } ?: ""
            val every = max(1, ceil((Shapes.est(longest, g.small) + 4) / g.cell).toInt())
            cols.forEachIndexed { j, name -> if (j % every == 0) text(c, name, g.labW + (j + 0.5f) * g.cell, g.small, g.small, Paint.Align.CENTER, alpha = 0.6f) }
        }
        data.values.forEachIndexed { i, row ->
            val y = g.top + (i + 0.5f) * g.cell
            data.rows?.getOrNull(i)?.let { text(c, it, 0f, y + g.small * 0.36f, g.small, alpha = 0.7f) }
            row.forEachIndexed { j, v0 ->
                val v = max(0.0, v0)
                val x = g.labW + (j + 0.5f) * g.cell
                val r = if (v > 0) max(0.9f, sqrt(v / (if (g.max == 0.0) 1.0 else g.max)).toFloat() * g.cell * 0.46f) else 0.7f
                ps.shader = null; ps.style = Paint.Style.FILL; ps.color = theme.ink
                ps.alpha = ((if (v > 0) 1f else 0.25f) * dim(i * g.nc + j) * 255).toInt()
                c.drawCircle(x, y, r, ps)
            }
        }
    }
}
