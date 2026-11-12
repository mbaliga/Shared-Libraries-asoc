package dev.aarso.viz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Test scaffolding: measure / lay out / draw a view the way a parent would, at phone density. */
internal object Rig {
    val context: Context get() = RuntimeEnvironment.getApplication()
    val density: Float get() = context.resources.displayMetrics.density

    val SUN = VizTone.SUN.theme()
    val NIGHT = VizTone.NIGHT.theme()
    val BONE = VizTone.BONE.theme()

    /** Measure at [widthDp] (exact) and, if given, [heightDp] (exact); then lay out at origin. */
    fun layout(v: View, widthDp: Float, heightDp: Float? = null): View {
        val d = density
        val ws = MeasureSpec.makeMeasureSpec((widthDp * d).toInt(), MeasureSpec.EXACTLY)
        val hs = if (heightDp != null) MeasureSpec.makeMeasureSpec((heightDp * d).toInt(), MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        v.measure(ws, hs)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    /** Draw [v] on a bitmap filled with [ground]. */
    fun draw(v: View, ground: Int): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ground)
        v.draw(c)
        return bmp
    }

    fun png(b: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels differing from [ground] in [b]. */
    fun inked(b: Bitmap, ground: Int): Int {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { it != ground }
    }
}
