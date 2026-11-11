package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.max

/**
 * The keys, in the same textures (viz.js `legend`). A column by default, or a wrapping row
 * (`options.row`); `options.lines` draws dash keys beside a [LineView]; `options.shades` draws solid
 * keys at the four steps of one ink; `options.values` shows each item's figure. When [onSelect] is
 * set the items become 48 dp targets (a filter control); otherwise they stay as compact as the web
 * legend and the whole is read as one list.
 */
class LegendView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var items: List<LegendItem> = emptyList()
    private var slots: List<Slot> = emptyList()
    private var totalH = 0f

    /** One placed key in dp: swatch at ([x], [y]), the row [h] tall. */
    internal class Slot(val item: LegendItem, val index: Int, val x: Float, val y: Float, val w: Float, val h: Float, val labelW: Float, val valueW: Float)

    override val interactive: Boolean get() = onSelect != null

    fun setData(d: List<LegendItem>) { items = d; changed() }

    internal fun placed(): List<Slot> { ensureBuilt(); return slots }

    private fun folded(): List<LegendItem> {
        if (items.size <= Viz.ORDER.size) return items
        val head = items.subList(0, Viz.ORDER.size - 1)
        val rest = items.subList(Viz.ORDER.size - 1, items.size)
        val any = rest.any { it.value != null }
        return head + LegendItem("Other", value = if (any) rest.sumOf { it.value ?: 0.0 } else null)
    }

    private fun fs() = rem() * 0.72f
    private fun sw() = if (options.lines) 18f else 14f

    private fun place(w: Float): Pair<List<Slot>, Float> {
        val f = fs(); val sw = sw(); val gapIn = f * 0.55f
        val natural = max(sw, f * 1.45f)
        val rowH = if (onSelect != null) max(natural, Viz.TARGET_DP) else natural
        val out = ArrayList<Slot>()
        var x = 0f; var y = 0f
        folded().forEachIndexed { i, it ->
            val lw = textWidth(it.label, f)
            val vw = if (it.value != null && options.values) textWidth(fmt(it.value), f, 500) else 0f
            if (options.row) {
                val iw = sw + gapIn + lw + (if (vw > 0) gapIn + vw else 0f)
                if (x > 0 && x + iw > w) { x = 0f; y += rowH + f * 0.4f }
                out.add(Slot(it, i, x, y, iw, rowH, lw, vw))
                x += iw + f * 1.1f
            } else {
                out.add(Slot(it, i, 0f, y, w, rowH, lw, vw))
                y += rowH + (if (onSelect != null) 0f else f * 0.55f)
            }
        }
        val total = if (out.isEmpty()) 0f else if (options.row) y + rowH else y - (if (onSelect != null) 0f else f * 0.55f)
        return out to total
    }

    override fun naturalHeight(w: Float, avail: Float?): Float = place(w).second

    override fun table() = VizTable(null, folded().map { listOf(it.label) + (it.value?.let { v -> listOf(fmt(v)) } ?: emptyList()) })

    override fun describe(): String = chartLabel ?: folded().joinToString(", ") { it.label + (if (it.value != null && options.values) " " + fmt(it.value) else "") }

    override fun build(w: Float, h: Float) {
        val (s, t) = place(w); slots = s; totalH = t
        if (onSelect == null) return
        s.forEach { sl ->
            val m = Mark(sl.index, sl.item.label, sl.item.value ?: 0.0, sl.item.value?.let { fmt(it) } ?: "", null)
            val box = RectF(sl.x, sl.y, sl.x + sl.w, sl.y + sl.h)
            addNode(node(m, if (sl.item.value != null) m.description else sl.item.label, box, w, h, sl.x + sl.w / 2, sl.y))
        }
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val f = fs(); val sw = sw(); val gapIn = f * 0.55f
        val ps = paintScratch()
        slots.forEach { sl ->
            val a = dim(sl.index)
            val cy = sl.y + sl.h / 2
            val top = cy - sw / 2
            if (options.lines) {
                val dash = if (sl.item.dash == null) Viz.DASHES.getOrNull(sl.index) else sl.item.dashArray()
                ps.shader = null; ps.style = Paint.Style.STROKE; ps.strokeWidth = 2f; ps.strokeCap = Paint.Cap.ROUND; ps.color = theme.ink; ps.alpha = (a * 255).toInt()
                ps.pathEffect = dash?.let { DashPathEffect(it, 0f) }
                c.drawLine(sl.x + 1, cy, sl.x + sw - 1, cy, ps)
                ps.pathEffect = null; ps.strokeCap = Paint.Cap.BUTT
            } else {
                val box = RectF(sl.x + 0.5f, top + 0.5f, sl.x + sw - 0.5f, top + sw - 0.5f)
                val tex = if (options.shades) Texture.SOLID else sl.item.fill ?: Viz.ORDER[sl.index % Viz.ORDER.size]
                val alpha = if (options.shades) Viz.SHADES[sl.index % Viz.SHADES.size] else 1f
                val path = Path().apply { addRoundRect(box, sw / 2, sw / 2, Path.Direction.CW) }
                ps.style = Paint.Style.FILL
                texturePainter(if (sw < 20) 0.62f else 1f).apply(ps, tex, box, FadeDir.UP)
                ps.alpha = (alpha * a * 255).toInt()
                c.drawPath(path, ps)
                ps.shader = null
                ps.style = Paint.Style.STROKE; ps.strokeWidth = 1f; ps.color = theme.ink
                ps.alpha = ((if (tex == Texture.SOLID && !options.shades) 0f else 0.35f) * a * 255).toInt()
                c.drawPath(path, ps)
            }
            val tx = sl.x + sw + gapIn
            text(c, if (options.row) sl.item.label else Shapes.clip(sl.item.label, f, w - tx - sl.valueW - gapIn), tx, cy + f * 0.35f, f, alpha = 0.85f * a)
            if (sl.valueW > 0) {
                val v = fmt(sl.item.value!!)
                if (options.row) text(c, v, tx + sl.labelW + gapIn, cy + f * 0.35f, f, weight = 500, alpha = a)
                else text(c, v, w, cy + f * 0.35f, f, Paint.Align.RIGHT, 500, a)
            }
        }
    }
}
