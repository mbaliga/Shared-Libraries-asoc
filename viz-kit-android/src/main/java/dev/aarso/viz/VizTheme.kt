package dev.aarso.viz

import android.graphics.Typeface
import android.os.Build
import androidx.annotation.ColorInt

/** Named grounds, as in viz.js: sun, ember and night are the loud ones, bone the quiet one. */
enum class VizTone(@ColorInt val ink: Int, @ColorInt val ground: Int, @ColorInt val accent: Int?) {
    SUN(0xFF0C0C0C.toInt(), 0xFFF6EE2D.toInt(), null),
    EMBER(0xFF0C0C0C.toInt(), 0xFFFF8F4C.toInt(), 0xFFF6EE2D.toInt()),
    NIGHT(0xFFF2EFE9.toInt(), 0xFF0C0C0D.toInt(), 0xFFF6EE2D.toInt()),
    BONE(0xFF0C0C0C.toInt(), 0xFFEDEBE6.toInt(), null),
    RADIUM(0xFF07070A.toInt(), 0xFFCCEC75.toInt(), null),
    VIOLET(0xFF07070A.toInt(), 0xFF8E7BFF.toInt(), 0xFFCCEC75.toInt());

    /** This tone as a theme with the kit's defaults for everything else. */
    fun theme(): VizTheme = VizTheme(ink = ink, ground = ground, accent = accent)
}

/**
 * Everything a chart is drawn with. One [ink] on one [ground]; [ground] must be the solid colour
 * behind the chart, because gaps and rings are drawn in it. [accent] colours the fan's callout pill.
 * Defaults are viz.js's: ink #0c0c0c, ground #f6ee2d, rem 16, weight 300, compact numbers.
 *
 * @property rem the type and spacing unit, in dp (viz.js's `rem`, in CSS px).
 * @property font the typeface family; null is the system sans.
 * @property weight 100..900. Below API 28 it degrades to the light / regular / medium system families.
 * @property textureScale scales every texture's pitch (viz.js's `scale`).
 * @property format number formatter used for every figure (viz.js's `format`).
 */
data class VizTheme(
    @ColorInt val ink: Int = 0xFF0C0C0C.toInt(),
    @ColorInt val ground: Int = 0xFFF6EE2D.toInt(),
    @ColorInt val accent: Int? = null,
    val rem: Float = 16f,
    val font: Typeface? = null,
    val weight: Int = 300,
    val textureScale: Float = 1f,
    val format: (Double) -> String = VizFormat::compact,
) {
    /** The typeface at [w], honouring [font] where the platform supports variable weights. */
    fun typeface(w: Int = weight): Typeface {
        val base = font ?: Typeface.SANS_SERIF
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(base, w.coerceIn(1, 1000), false)
        return when {
            font != null -> Typeface.create(base, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
            w <= 350 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            w in 450..650 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            w > 650 -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    companion object {
        /** A theme from a named tone. */
        fun of(tone: VizTone): VizTheme = tone.theme()
    }
}
