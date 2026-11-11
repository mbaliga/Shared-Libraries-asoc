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
 * A figure against its ceiling; the remainder is the same ink, hatched and faint (viz.js `meter`).
 * `options.plain` draws the pill alone. The bar and the track are separated by a 2 dp gap in the
 * ground.
 */
class MeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VizView(context, attrs) {
    private var data = Meter(0.0)

    fun setData(m: Meter) { data = m; changed() }

    /** The filled share, 0..1. */
    val share: Float get() = (data.value / (if (data.max == 0.0) 100.0 else data.max)).toFloat().coerceIn(0f, 1f)

    private fun thick() = options.thickness ?: (rem() * 0.75f)
    private fun maxV() = if (data.max == 0.0) 100.0 else data.max

    override fun naturalHeight(w: Float, avail: Float?): Float = rem() * (if (options.plain) 0.75f else 3.3f)

    override fun table() = VizTable(listOf("", "Value", "Of"), listOf(listOf(data.label ?: "Value", fmt(data.value), fmt(maxV()))))

    override fun describe(): String = "${data.label ?: "Value"}: ${fmt(data.value)} of ${fmt(maxV())}, ${Math.round(share * 100.0)}%"

    override fun build(w: Float, h: Float) {
        val th = thick(); val y = h - th
        val m = Mark(0, data.label ?: "Value", data.value, "${fmt(data.value)} of ${fmt(maxV())} · ${Math.round(share * 100.0)}%")
        addNode(node(m, m.description, RectF(0f, y - 6, w, y + th + 6), w, h, w * share / 2, y - 6))
    }

    override fun drawChart(c: Canvas, w: Float, h: Float) {
        val rem = rem(); val th = thick(); val y = h - th; val big = rem * 1.5f
        val fw = share * w; val gap = if (share > 0f && share < 1f) 2f else 0f
        if (!options.plain) {
            val v = fmt(data.value)
            text(c, v, 0f, big * 0.82f, big)
            text(c, "of ${fmt(maxV())}${data.label?.let { " $it" } ?: ""}", textWidth(v, big) + rem * 0.4f, big * 0.82f, rem * 0.64f, alpha = 0.7f)
        }
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(0f, y, w, y + th), th / 2, th / 2, Path.Direction.CW) })
        val track = RectF(fw + gap, y, w, y + th)
        if (track.width() > 0) fill(c, Path().apply { addRect(track, Path.Direction.CW) }, Texture.HATCH, track, FadeDir.UP, 0.4f * dim(0))
        if (fw > 0) {
            val bar = RectF(0f, y, fw, y + th)
            fill(c, Path().apply { addRoundRect(bar, 2f, 2f, Path.Direction.CW) }, options.fill ?: Texture.SOLID, bar, FadeDir.RIGHT, 1f)
        }
        c.restore()
    }
}
