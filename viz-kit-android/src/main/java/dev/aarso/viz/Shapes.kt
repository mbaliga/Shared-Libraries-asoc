package dev.aarso.viz

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Small geometry and text helpers shared by the charts. All lengths are dp, as in viz.js's px. */
internal object Shapes {
    /** A "nice" ceiling for an axis: 1, 2, 2.5, 5 or 10 times a power of ten. */
    fun nice(v: Double): Double {
        if (v <= 0) return 1.0
        val p = 10.0.pow(floor(ln(v) / ln(10.0)))
        val m = v / p
        return (if (m <= 1) 1.0 else if (m <= 2) 2.0 else if (m <= 2.5) 2.5 else if (m <= 5) 5.0 else 10.0) * p
    }

    /** Estimated width of [s] at [size]: the same `0.56 em` per character as viz.js, so layout decisions agree. */
    fun est(s: String, size: Float): Float = s.length * size * 0.56f

    /** [s] cut to fit [room], ending in an ellipsis. */
    fun clip(s: String, size: Float, room: Float): String {
        if (est(s, size) <= room) return s
        val n = max(1, floor(room / (size * 0.56f)).toInt() - 1)
        return s.take(n) + "…"
    }

    /** A column rising from [bottom] with its top corners rounded by [r]. */
    fun column(x: Float, y: Float, w: Float, bottom: Float, r: Float): Path = Path().apply {
        moveTo(x, bottom)
        lineTo(x, y + r)
        if (r > 0) arcTo(RectF(x, y, x + 2 * r, y + 2 * r), 180f, 90f)
        lineTo(x + w - r, y)
        if (r > 0) arcTo(RectF(x + w - 2 * r, y, x + w, y + 2 * r), 270f, 90f)
        lineTo(x + w, bottom)
        close()
    }

    /** A bar from x=0 with a fully rounded right end: radius [r] across, half the thickness down. */
    fun bar(top: Float, w: Float, th: Float): Path = Path().apply {
        val r = min(th / 2, w)
        moveTo(0f, top)
        lineTo(w - r, top)
        arcTo(RectF(w - 2 * r, top, w, top + th), -90f, 180f)
        lineTo(0f, top + th)
        close()
    }

    /**
     * A smooth curve through [p] that never overshoots a point (monotone cubic, the same one viz.js
     * draws); a straight polyline for fewer than three points.
     */
    fun curve(p: List<PointF>): Path {
        val n = p.size
        val out = Path()
        out.moveTo(p[0].x, p[0].y)
        if (n < 3) { for (i in 1 until n) out.lineTo(p[i].x, p[i].y); return out }
        val d = DoubleArray(n - 1) { i -> ((p[i + 1].y - p[i].y) / (if (p[i + 1].x - p[i].x != 0f) p[i + 1].x - p[i].x else 1f)).toDouble() }
        val m = DoubleArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val s = a * a + b * b
            if (s > 9) { val t = 3 / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        for (i in 0 until n - 1) {
            val h = (p[i + 1].x - p[i].x) / 3
            out.cubicTo(
                p[i].x + h, (p[i].y + m[i] * h).toFloat(),
                p[i + 1].x - h, (p[i + 1].y - m[i + 1] * h).toFloat(),
                p[i + 1].x, p[i + 1].y,
            )
        }
        return out
    }

    /**
     * [r] grown to at least [minSize] on each axis (about its centre), then slid back inside [bounds]
     * where [bounds] is big enough. This is how a mark smaller than a finger gets a finger-sized target.
     */
    fun target(r: RectF, minSize: Float, bounds: RectF): RectF {
        val o = RectF(r)
        fun axis(lo: Float, hi: Float, blo: Float, bhi: Float): Pair<Float, Float> {
            var a = lo; var b = hi
            if (b - a < minSize) { val c = (a + b) / 2; a = c - minSize / 2; b = c + minSize / 2 }
            val room = bhi - blo
            if (b - a >= room) return blo to bhi
            if (a < blo) { b += blo - a; a = blo }
            if (b > bhi) { a -= b - bhi; b = bhi }
            return a to b
        }
        val (l, rr) = axis(o.left, o.right, bounds.left, bounds.right)
        val (t, b) = axis(o.top, o.bottom, bounds.top, bounds.bottom)
        return RectF(l, t, rr, b)
    }
}
