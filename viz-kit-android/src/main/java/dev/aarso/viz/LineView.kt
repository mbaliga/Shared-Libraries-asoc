package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.max
import kotlin.math.min

/**
 * Up to four series on ONE axis, told apart by dash; the first stands on a fade (viz.js `line`).
 * Touch or arrow keys step a crosshair through the points and one readout lists every series
 * there. A second measure is a second chart.
 */
open class LineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var series: List<Series> = emptyList()
    private var geo: Geo? = null

    /** The plot's geometry in dp: x of point i is [x], y of a value is [y]. */
    internal class Geo(
        val series: List<Series>, val n: Int, val lo: Double, val hi: Double,
        val ml: Float, val mt: Float, val pw: Float, val ph: Float, val ends: Boolean,
    ) {
        fun x(i: Int): Float = ml + (if (n > 1) i.toFloat() / (n - 1) else 0.5f) * pw
        fun y(v: Double): Float = mt + (1 - ((v - lo) / (hi - lo)).toFloat()) * ph
    }

    /** Spark mode: a line with nothing but its end. */
    protected open val bare: Boolean get() = false
    override val scrubs: Boolean get() = !bare
    override val interactive: Boolean get() = !bare

    /** One series, unlabelled. */
    fun setValues(values: List<Double?>) = setData(listOf(Series(values = values)))

    /** Up to four series (further ones are ignored, as in viz.js). */
    fun setData(s: List<Series>) { series = s.take(Viz.DASHES.size); changed() }

    internal fun geometry(): Geo? { ensureBuilt(); return geo }

    override fun naturalHeight(w: Float, avail: Float?): Float = if (avail != null && avail > 30) avail else rem() * (if (bare) 2.2f else 12f)

    private fun xs() = options.x

    override fun table(): VizTable {
        val n = series.maxOfOrNull { it.values.size } ?: 0
        val multi = series.size > 1
        val head = listOf(options.xTitle ?: "") + series.mapIndexed { i, s -> s.label ?: ((options.unit ?: "Value") + if (multi) " ${i + 1}" else "") }
        val rows = (0 until n).map { r -> listOf(xs().getOrNull(r) ?: (r + 1).toString()) + series.map { s -> s.values.getOrNull(r)?.let { fmt(it) } ?: "" } }
        return VizTable(head, rows)
    }

    override fun describe(): String {
        if (!bare) return super.describe()
        val v = series.firstOrNull()?.values?.filterNotNull().orEmpty()
        if (v.isEmpty()) return ""
        return "Trend of ${v.size} points: ${fmt(v.first())} to ${fmt(v.last())}, low ${fmt(v.min())}, high ${fmt(v.max())}"
    }

    override fun build(w: Float, h: Float) {
        geo = null
        val rem = rem(); val small = rem * 0.64f
        var n = 0; var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        series.forEach { s -> n = max(n, s.values.size); s.values.forEach { v -> if (v != null) { lo = min(lo, v); hi = max(hi, v) } } }
        if (n < 1 || !hi.isFinite()) return
        if (options.zero && !bare) { lo = min(0.0, lo); hi = Shapes.nice(hi) }
        if (hi == lo) hi = lo + 1
        val multi = series.size > 1
        val labW = series.maxOfOrNull { s -> s.label?.let { Shapes.est(it, small) } ?: 0f } ?: 0f
        var ends = multi && !bare && labW > 0
        val mr = if (bare) 6f else if (ends) labW + rem * 0.9f else 7f
        val mt = if (bare) 6f else rem * 1.3f
        val mb = if (bare) 6f else if (xs().isNotEmpty()) rem * 1.5f else 4f
        val ml = if (bare) 2f else 0f
        val g0 = Geo(series, n, lo, hi, ml, mt, w - ml - mr, h - mt - mb, ends)
        if (ends) {
            val ys = series.map { s -> g0.y(s.values.lastOrNull { it != null } ?: lo) }.sorted()
            for (q in 1 until ys.size) if (ys[q] - ys[q - 1] < small * 1.25f) ends = false
        }
        val g = Geo(series, n, lo, hi, ml, mt, g0.pw, g0.ph, ends)
        geo = g
        if (bare) return
        // One node per x: strips that partition the plot (the first and last reach the view's edges).
        for (i in 0 until n) {
            val half = (if (n > 1) g.pw / (n - 1) else g.pw) / 2
            val left = if (i == 0) 0f else g.x(i) - half
            val right = if (i == n - 1) w else g.x(i) + half
            val label = xs().getOrNull(i) ?: (i + 1).toString()
            val parts = series.mapIndexedNotNull { si, s -> s.values.getOrNull(i)?.let { v -> (s.label ?: if (multi) "Series ${si + 1}" else null).let { l -> if (l != null) "$l ${fmt(v)}" else fmt(v) } } }
            val first = series.firstNotNullOfOrNull { it.values.getOrNull(i) } ?: 0.0
            val m = Mark(i, label, first, parts.joinToString(", "))
            val top = series.mapNotNull { s -> s.values.getOrNull(i)?.let { g.y(it) } }.minOrNull() ?: g.y(lo)
            addNode(node(m, m.description, RectF(left, 0f, right, h), w, h, g.x(i), top, minTarget = 0f))
        }
    }

    override fun tipFor(i: Int, x: Float, y: Float): Tip? {
        val g = geo ?: return null
        val multi = series.size > 1
        val rows = ArrayList<TipRow>()
        var top = Float.MAX_VALUE
        series.forEachIndexed { si, s ->
            val v = s.values.getOrNull(i) ?: return@forEachIndexed
            top = min(top, g.y(v))
            rows.add(TipRow(fmt(v), s.label, Viz.DASHES.getOrNull(si), multi))
        }
        if (rows.isEmpty()) return null
        return Tip(rows, xs().getOrNull(i), g.x(i), top - 4)
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val g = geo ?: return
        val rem = rem(); val small = rem * 0.64f
        val ps = paintScratch()
        val base = g.y(g.lo)
        if (!bare) {
            ps.shader = null; ps.style = Paint.Style.STROKE; ps.strokeWidth = 1f; ps.color = theme.ink
            if (options.grid) {
                ps.alpha = (0.12f * 255).toInt()
                for (t in listOf(0.5, 1.0)) { val yy = g.y(g.lo + (g.hi - g.lo) * t); c.drawLine(g.ml, yy, g.ml + g.pw, yy, ps) }
            }
            ps.alpha = (0.28f * 255).toInt()
            c.drawLine(g.ml, base, g.ml + g.pw, base, ps)
            text(c, fmt(g.hi), g.ml, g.mt - rem * 0.45f, small, alpha = 0.6f)
            if (g.lo != 0.0) text(c, fmt(g.lo), g.ml, base - rem * 0.35f, small, alpha = 0.6f)
            val xs = xs()
            if (xs.isNotEmpty()) {
                text(c, xs[0], g.ml, h - 3, small, alpha = 0.6f)
                if (g.n > 1 && g.n - 1 < xs.size) text(c, xs[g.n - 1], g.ml + g.pw, h - 3, small, Paint.Align.RIGHT, alpha = 0.6f)
            }
        }
        series.forEachIndexed { si, s ->
            val pts = ArrayList<PointF>()
            s.values.forEachIndexed { i, v -> if (v != null) pts.add(PointF(g.x(i), g.y(v))) }
            if (pts.isEmpty()) return@forEachIndexed
            val path = if (!options.smooth) Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y) } else Shapes.curve(pts)
            val last = pts.last()
            if (si == 0 && options.area && !bare) {
                val area = Path(path); area.lineTo(last.x, base); area.lineTo(pts[0].x, base); area.close()
                val b = RectF(); area.computeBounds(b, true)
                fill(c, area, s.fill ?: Texture.FADE, b, FadeDir.UP, if (s.fill != null && s.fill != Texture.FADE) 0.5f else 0.42f)
            }
            ps.shader = null; ps.style = Paint.Style.STROKE; ps.strokeWidth = if (bare) 1.6f else 2f; ps.color = theme.ink; ps.alpha = 255
            ps.strokeJoin = Paint.Join.ROUND; ps.strokeCap = Paint.Cap.ROUND
            ps.pathEffect = Viz.DASHES.getOrNull(si)?.let { DashPathEffect(it, 0f) }
            c.drawPath(path, ps)
            ps.pathEffect = null
            val r = if (bare) 3f else 4f
            ps.style = Paint.Style.FILL; ps.color = theme.ink
            c.drawCircle(last.x, last.y, r, ps)
            ps.style = Paint.Style.STROKE; ps.strokeWidth = 2f; ps.color = theme.ground
            c.drawCircle(last.x, last.y, r, ps)
            if (g.ends && s.label != null) text(c, s.label, last.x + rem * 0.6f, last.y + small * 0.36f, small, alpha = 0.85f)
        }
        ps.strokeCap = Paint.Cap.BUTT
        // The crosshair: a rule at the active x, and a ring on each series there.
        val a = activeIndex
        if (!bare && a >= 0 && a < g.n) {
            val x = g.x(a)
            ps.style = Paint.Style.STROKE; ps.strokeWidth = 1f; ps.color = theme.ink; ps.alpha = (0.4f * 255).toInt()
            c.drawLine(x, g.mt, x, base, ps)
            series.forEach { s ->
                val v = s.values.getOrNull(a) ?: return@forEach
                ps.style = Paint.Style.FILL; ps.color = theme.ground; ps.alpha = 255
                c.drawCircle(x, g.y(v), 4.5f, ps)
                ps.style = Paint.Style.STROKE; ps.strokeWidth = 2f; ps.color = theme.ink
                c.drawCircle(x, g.y(v), 4.5f, ps)
            }
        }
    }
}

/** A line with nothing but its end (viz.js `spark`). Not interactive: its accessible name states the trend. */
class SparkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LineView(context, attrs) {
    override val bare: Boolean get() = true
}
