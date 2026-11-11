package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.max

/**
 * One pill split into parts (viz.js `stack`). Touching parts are separated by a 2 dp gap in the
 * ground, never an outline; a part is labelled only where its label fits; a ninth part folds into
 * "Other".
 */
class StackView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data: List<Datum> = emptyList()
    private var segs: List<Seg> = emptyList()

    /** One laid-out part in dp, [x] .. [x]+[w] along a pill [thick] tall; [labelled] when its text fits beneath. */
    internal class Seg(val datum: Datum, val x: Float, val w: Float, val thick: Float, val figure: String, val pct: String, val labelled: Boolean, val fillIndex: Int)

    fun setData(d: List<Datum>) { data = d; changed() }

    internal fun segments(): List<Seg> { ensureBuilt(); return segs }

    private fun parts() = Viz.fold(data).filter { it.value > 0 }
    private fun thick() = options.thickness ?: (rem() * 1.75f)

    override fun naturalHeight(w: Float, avail: Float?): Float = thick() + rem() * 2.6f

    override fun table(): VizTable {
        val d = parts(); val total = d.sumOf { it.value }
        return VizTable(listOf("", options.unit ?: "Value", "Share"), d.map { listOf(it.label, fmt(it.value), "${Math.round(it.value / total * 100)}%") })
    }

    override fun build(w: Float, h: Float) {
        val d = parts(); val rem = rem(); val th = thick(); val gap = 2f
        val big = rem * 0.95f; val small = rem * 0.64f
        val total = d.sumOf { it.value }
        val room = w - gap * (d.size - 1)
        var x = 0f
        segs = d.mapIndexed { i, it ->
            val sw = (it.value / total).toFloat() * room
            val fig = fmt(it.value)
            val s = Seg(it, x, sw, th, fig, "${Math.round(it.value / total * 100)}%", Shapes.est(fig, big) <= sw - 4 && Shapes.est(it.label, small) <= sw - 4, i)
            x += sw + gap
            s
        }
        segs.forEachIndexed { i, s ->
            val m = Mark(i, s.datum.label, s.datum.value, "${s.figure} · ${s.pct}", s.datum)
            addNode(node(m, m.description, RectF(s.x, 0f, s.x + s.w, h), w, h, s.x + s.w / 2, 0f))
        }
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        if (segs.isEmpty()) return
        val rem = rem(); val th = thick(); val big = rem * 0.95f; val small = rem * 0.64f
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w, th), th / 2, th / 2, Path.Direction.CW) })
        segs.forEachIndexed { i, s ->
            val box = RectF(s.x, 0f, s.x + s.w, th)
            fill(c, Path().apply { addRoundRect(box, 3f, 3f, Path.Direction.CW) }, textureFor(s.datum, i), box, FadeDir.UP, dim(i))
        }
        c.restore()
        segs.forEach { s ->
            if (!s.labelled) return@forEach
            text(c, s.figure, s.x + 1, th + rem * 1.25f, big)
            text(c, s.datum.label, s.x + 1, th + rem * 1.25f + small + rem * 0.3f, small, alpha = 0.72f)
        }
    }
}
