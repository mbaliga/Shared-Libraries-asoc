package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Parts of a whole as a corner quarter (`sweep` 90), a half (180, the default) or a ring (360), with
 * an optional `inner` hole and a callout (viz.js `fan`). Slices are separated by a 2 dp gap in the
 * ground; a ninth part folds into "Other".
 */
class FanView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data: List<Datum> = emptyList()
    private var geo: Geo? = null

    /** One slice in radians from [a0] to [a1] (clockwise, y down); [share] is its fraction of the whole. */
    internal class Slice(val datum: Datum, val a0: Float, val a1: Float, val share: Float, val index: Int)

    /** The fan's geometry in dp. */
    internal class Geo(
        val cx: Float, val cy: Float, val r: Float, val inner: Float, val start: Float, val span: Float,
        val total: Double, val slices: List<Slice>, val callout: String?, val pill: RectF?,
    )

    fun setData(d: List<Datum>) { data = d; changed() }

    internal fun geometry(): Geo { ensureBuilt(); return geo!! }

    private fun sweep() = if (options.sweep >= 360) 360 else if (options.sweep <= 90) 90 else 180

    override fun naturalHeight(w: Float, avail: Float?): Float =
        if (avail != null && avail > 60) avail else (if (sweep() >= 360) min(w, rem() * 14) else rem() * 12)

    override fun table(): VizTable {
        val d = Viz.fold(data); val total = d.sumOf { max(0.0, it.value) }
        return VizTable(listOf("", options.unit ?: "Value", "Share"), d.map { listOf(it.label, fmt(max(0.0, it.value)), "${Math.round(if (total > 0) max(0.0, it.value) / total * 100 else 0.0)}%") })
    }

    override fun build(w: Float, h: Float) {
        val d = Viz.fold(data); val rem = rem(); val sw = sweep()
        val full = sw >= 360; val quarter = sw <= 90
        val total = d.sumOf { max(0.0, it.value) }
        val ph = rem * 1.9f
        val call: String? = if (!options.showCallout) null else (options.callout ?: fmt(total))
        val cx: Float; val cy: Float; var r: Float; val a0: Float
        if (full) { cx = w / 2; cy = h / 2; r = min(w, h) / 2 - 1; a0 = (-PI / 2).toFloat() }
        else if (quarter) {
            cx = w; cy = h
            r = min(w - (if (call != null) Shapes.est(call, rem) / 2 + rem else 0f), h - (if (call != null) ph / 2 else 0f))
            r = min(r, min(w, h) * 1.25f); a0 = PI.toFloat()
        } else { cx = w / 2; cy = h; r = min(w / 2, h - (if (call != null) ph / 2 else 0f)); a0 = PI.toFloat() }
        r = max(0f, r)
        val inner = (options.inner ?: (if (full) 0.6f else 0f)).coerceIn(0f, 0.9f) * r
        val span = ((if (full) 360 else if (quarter) 90 else 180) * PI / 180).toFloat()
        var a = a0
        val slices = ArrayList<Slice>()
        d.forEachIndexed { i, it ->
            val v = max(0.0, it.value)
            val share = if (total > 0) (v / total).toFloat() else 0f
            val a1 = a + share * span
            if (v > 0) slices.add(Slice(it, a, a1, share, i))
            a = a1
        }
        var pill: RectF? = null
        if (call != null && !full) {
            val am = if (quarter) a0 + span * 0.42f else a0 + span / 2
            val pw = Shapes.est(call, rem * 0.95f) + rem * 1.5f
            var px = cx + cos(am) * r; var py = cy + sin(am) * r
            px = max(pw / 2, min(w - pw / 2, px)); py = max(ph / 2, py)
            pill = RectF(px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2)
        }
        geo = Geo(cx, cy, r, inner, a0, span, total, slices, call, pill)

        slices.forEachIndexed { n, s ->
            val m = Mark(n, s.datum.label, s.datum.value, "${fmt(s.datum.value)} · ${Math.round(s.share * 100.0)}%", s.datum)
            val path = slicePath(geo!!, s)
            val b = RectF(); path.computeBounds(b, true)
            val mid = (s.a0 + s.a1) / 2
            addNode(node(m, m.description, b, w, h, cx + cos(mid) * r, cy + sin(mid) * r,
                precise = { x, y -> inSlice(geo!!, s, x, y) }, outline = path))
        }
    }

    private fun slicePath(g: Geo, s: Slice): Path {
        val one = s.share > 0.9995f
        val e = if (one) s.a1 - 0.0001f else s.a1
        val deg = Math.toDegrees((e - s.a0).toDouble()).toFloat()
        val st = Math.toDegrees(s.a0.toDouble()).toFloat()
        val p = Path()
        p.moveTo(g.cx + cos(s.a0) * g.r, g.cy + sin(s.a0) * g.r)
        p.arcTo(RectF(g.cx - g.r, g.cy - g.r, g.cx + g.r, g.cy + g.r), st, deg)
        if (g.inner > 0) {
            p.lineTo(g.cx + cos(e) * g.inner, g.cy + sin(e) * g.inner)
            p.arcTo(RectF(g.cx - g.inner, g.cy - g.inner, g.cx + g.inner, g.cy + g.inner), st + deg, -deg)
        } else p.lineTo(g.cx, g.cy)
        p.close()
        return p
    }

    private fun inSlice(g: Geo, s: Slice, x: Float, y: Float): Boolean {
        val d = hypot(x - g.cx, y - g.cy)
        if (d > g.r || d < g.inner) return false
        var a = atan2(y - g.cy, x - g.cx)
        val twoPi = (2 * PI).toFloat()
        while (a < s.a0) a += twoPi
        while (a >= s.a0 + twoPi) a -= twoPi
        return a <= s.a1
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val g = geo ?: return
        val rem = rem()
        val ps = paintScratch()
        g.slices.forEachIndexed { n, s ->
            val path = slicePath(g, s)
            val tex = s.datum.fill ?: (if (options.fill != null && !options.fillEach) options.fill else null) ?: Viz.ORDER[s.index % Viz.ORDER.size]
            val a = dim(n)
            ps.style = Paint.Style.FILL
            if (tex == Texture.FADE) texturePainter().applyRadial(ps, g.cx, g.cy, g.r, max(0.05f, g.inner / g.r))
            else texturePainter().apply(ps, tex)
            ps.alpha = (a * 255).toInt()
            c.drawPath(path, ps)
            ps.shader = null
            if (s.share <= 0.9995f) {
                ps.style = Paint.Style.STROKE; ps.strokeWidth = 2f; ps.color = theme.ground; ps.strokeJoin = Paint.Join.ROUND
                c.drawPath(path, ps)
                ps.style = Paint.Style.FILL
            }
        }
        val call = g.callout ?: return
        if (g.pill == null) {
            val size = min(rem * 1.7f, g.inner * 0.62f)
            text(c, call, g.cx, g.cy + (if (options.caption != null) 0f else rem * 0.5f), size, Paint.Align.CENTER)
            options.caption?.let { text(c, Shapes.clip(it, rem * 0.64f, g.inner * 1.6f), g.cx, g.cy + rem * 1.05f, rem * 0.64f, Paint.Align.CENTER, alpha = 0.7f) }
            return
        }
        val p = g.pill
        ps.shader = null; ps.style = Paint.Style.FILL; ps.color = theme.accent ?: theme.ink
        c.drawRoundRect(p, p.height() / 2, p.height() / 2, ps)
        text(c, call, p.centerX(), p.centerY() + rem * 0.33f, rem * 0.95f, Paint.Align.CENTER, 400, color = if (theme.accent == null) theme.ground else theme.ink)
    }
}
