package dev.aarso.viz

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.ColorInt
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The eight textures, in the fixed order identity is assigned by. Names are the contract's `fill`
 * strings (`solid`, `hatch`, `dots`, `fade`, `cross`, `lines`, `checks`, `stipple`).
 */
enum class Texture(val key: String) {
    SOLID("solid"), HATCH("hatch"), DOTS("dots"), FADE("fade"),
    CROSS("cross"), LINES("lines"), CHECKS("checks"), STIPPLE("stipple");

    companion object {
        /** The texture named [key]; an unknown name is `null` here and reads as solid when painted. */
        fun of(key: String?): Texture? = values().firstOrNull { it.key == key }
    }
}

/** Which way a fade climbs to full ink: `UP` is faint at the foot, `RIGHT` faint at the left edge. */
enum class FadeDir { UP, RIGHT }

/**
 * Builds the shader fills for all eight textures at a texture [scale] and screen [density]. A port of
 * `ASOC.viz.fills` / `canvasFills`: the same pitches, dot radii and bar widths, in dp. Tiles are
 * rasterised once at [density] so they stay crisp, and are set on a Paint with [apply]. Drawing is
 * done in a canvas already scaled by density, so a tile always covers its dp size.
 *
 * Deterministic: nothing here reads a clock or a global random; the one irregular texture (stipple)
 * is jittered by a [seed]ed generator, so the same inputs give the same pixels on every run.
 */
class TexturePainter(
    @ColorInt val ink: Int,
    val scale: Float = 1f,
    val density: Float = 1f,
    val seed: Long = STIPPLE_SEED,
) {
    private val tiles = HashMap<Texture, Shader>()
    private val k = scale

    /** Edge of one tile in dp for [t] (0 for solid and fade, which are not tiled). */
    fun tileSize(t: Texture): Float = when (t) {
        Texture.HATCH, Texture.CROSS -> 7f * k * ROOT2 // perpendicular pitch 7*k at 45 degrees
        Texture.DOTS -> 9f * k
        Texture.LINES -> 6f * k
        Texture.CHECKS -> 8f * k
        Texture.STIPPLE -> 4.5f * k * STIPPLE_CELLS
        else -> 0f
    }

    /** The tile bitmap for [t], or null for solid and fade. Pixel-identical for identical arguments. */
    fun tile(t: Texture): Bitmap? {
        val s = tileSize(t)
        if (s <= 0f) return null
        val px = max(2, (s * density).roundToInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val q = px / s
        c.scale(q, q)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL }
        when (t) {
            Texture.HATCH -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 7f * k * 0.46f
                diagonals(c, p, s, up = true, down = false)
            }
            Texture.CROSS -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 7f * k * 0.18f
                diagonals(c, p, s, up = true, down = true)
            }
            Texture.DOTS -> {
                c.drawCircle(s * 0.25f, s * 0.25f, 1.5f * k, p)
                c.drawCircle(s * 0.75f, s * 0.75f, 1.5f * k, p)
            }
            Texture.LINES -> c.drawRect(0f, 0f, s, s * 0.3f, p)
            Texture.CHECKS -> {
                c.drawRect(0f, 0f, s / 2, s / 2, p)
                c.drawRect(s / 2, s / 2, s, s, p)
            }
            Texture.STIPPLE -> {
                val rnd = Random(seed)
                val cell = 4.5f * k
                val jitter = 0.6f * k
                for (j in 0 until STIPPLE_CELLS) for (i in 0 until STIPPLE_CELLS) {
                    val dx = (rnd.nextFloat() * 2 - 1) * jitter
                    val dy = (rnd.nextFloat() * 2 - 1) * jitter
                    c.drawCircle((i + 0.5f) * cell + dx, (j + 0.5f) * cell + dy, 0.85f * k, p)
                }
            }
            else -> Unit
        }
        return bmp
    }

    /** Segments through the tile corners at 45 degrees, plus the two corner pieces so the tiling has no seam. */
    private fun diagonals(c: Canvas, p: Paint, s: Float, up: Boolean, down: Boolean) {
        if (up) {
            c.drawLine(0f, s, s, 0f, p); c.drawLine(-s / 2, s / 2, s / 2, -s / 2, p); c.drawLine(s / 2, s * 1.5f, s * 1.5f, s / 2, p)
        }
        if (down) {
            c.drawLine(0f, 0f, s, s, p); c.drawLine(-s / 2, s / 2, s / 2, s * 1.5f, p); c.drawLine(s / 2, -s / 2, s * 1.5f, s / 2, p)
        }
    }

    private fun shader(t: Texture): Shader = tiles.getOrPut(t) {
        val bmp = tile(t)!!
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            val m = Matrix(); val f = tileSize(t) / bmp.width; m.setScale(f, f); it.setLocalMatrix(m)
        }
    }

    /**
     * Point [paint] at texture [t]. [bounds] is the mark's box in dp, which a fade needs (it climbs
     * from faint at one edge of the box to full ink at the other); other textures are anchored at the
     * canvas origin, as the SVG patterns are. Resets shader and colour first.
     */
    fun apply(paint: Paint, t: Texture?, bounds: RectF? = null, dir: FadeDir = FadeDir.UP) {
        paint.shader = null
        paint.color = ink
        when (val kind = t ?: Texture.SOLID) {
            Texture.SOLID -> Unit
            Texture.FADE -> {
                val b = bounds ?: RectF(0f, 0f, 1f, 1f)
                paint.shader = if (dir == FadeDir.RIGHT) LinearGradient(b.left, 0f, b.right, 0f, FADE_COLORS(ink), FADE_AT, Shader.TileMode.CLAMP)
                else LinearGradient(0f, b.bottom, 0f, b.top, FADE_COLORS(ink), FADE_AT, Shader.TileMode.CLAMP)
            }
            else -> paint.shader = shader(kind)
        }
    }

    /** A fade that climbs from the centre outwards (the fan's slices); [r0] is the radius fraction it starts at. */
    fun applyRadial(paint: Paint, cx: Float, cy: Float, r: Float, r0: Float = 0.05f) {
        paint.color = ink
        val a = r0.coerceIn(0f, 0.98f)
        paint.shader = RadialGradient(cx, cy, r, FADE_COLORS(ink), floatArrayOf(a, max(0.55f, a + 0.01f), 1f), Shader.TileMode.CLAMP)
    }

    companion object {
        const val STIPPLE_SEED = 7L
        private const val STIPPLE_CELLS = 4
        private val ROOT2 = sqrt(2.0).toFloat()
        private val FADE_AT = floatArrayOf(0f, 0.55f, 1f)

        /** The fade's three stops: 7%, 50% and 100% of the ink. */
        private fun FADE_COLORS(ink: Int) = intArrayOf(withAlpha(ink, 0.07f), withAlpha(ink, 0.5f), withAlpha(ink, 1f))

        /** [color] with its alpha replaced by [a] (0..1). */
        fun withAlpha(@ColorInt color: Int, a: Float): Int =
            Color.argb((a.coerceIn(0f, 1f) * 255f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
