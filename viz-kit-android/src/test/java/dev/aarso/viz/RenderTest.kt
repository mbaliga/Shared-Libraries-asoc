package dev.aarso.viz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Rasterises every chart on three grounds (sun, night, bone) at phone density (w405dp xxhdpi) and
 * writes PNGs. They are laid out as the web demo's cards are (22 dp side padding) so they can be
 * set beside web/viz-kit/demo.html's. `-Dviz.render.dir=<dir>` chooses where; otherwise build/viz-render.
 * The assertions are the floor: each render has ink, and the ground shows through.
 */
@RunWith(RobolectricTestRunner::class)
class RenderTest {
    private val ctx get() = Rig.context
    private val d get() = Rig.density
    private val outDir = File(System.getProperty("viz.render.dir") ?: "build/viz-render")

    private val grounds = listOf("sun" to VizTone.SUN, "night" to VizTone.NIGHT, "bone" to VizTone.BONE)

    private fun card(v: View, h: Float? = null): View {
        v.setPadding((22 * d).toInt(), (18 * d).toInt(), (22 * d).toInt(), (22 * d).toInt())
        return Rig.layout(v, 405f, h?.let { it + 40 })
    }

    private fun charts(t: VizTheme): List<Pair<String, VizView>> {
        fun <T : VizView> T.th(): T { theme = t; return this }
        val legendStorage = Feed.storage.map { LegendItem(it.label, it.fill) }
        return listOf(
            "steps" to StepsView(ctx).th().also { it.setData(Feed.builds) },
            "fan_quarter" to FanView(ctx).th().also { it.options = ChartOptions(sweep = 90, callout = "134 GB"); it.setData(Feed.storage) },
            "fan_half" to FanView(ctx).th().also { it.setData(Feed.storage) },
            "fan_ring" to FanView(ctx).th().also { it.options = ChartOptions(sweep = 360, caption = "GB used"); it.setData(Feed.storage) },
            "legend_storage" to LegendView(ctx).th().also { it.setData(legendStorage) },
            "stat" to StatView(ctx).th().also { it.setData(Feed.stat) },
            "spark" to SparkView(ctx).th().also { it.options = ChartOptions(height = 44f); it.setData(Feed.spark) },
            "meter" to MeterView(ctx).th().also { it.setData(Feed.meter) },
            "line" to LineView(ctx).th().also { it.options = Feed.commitsOptions; it.setData(Feed.commits) },
            "legend_lines" to LegendView(ctx).th().also { it.options = ChartOptions(lines = true, row = true); it.setData(Feed.commits.map { s -> LegendItem(s.label!!) }) },
            "bars" to BarsView(ctx).th().also { it.options = ChartOptions(unit = "MB"); it.setData(Feed.repos) },
            "halftone" to HalftoneView(ctx).th().also { it.setData(Feed.hours) },
            "stack" to StackView(ctx).th().also { it.options = ChartOptions(unit = "Hours"); it.setData(Feed.time) },
            "legend_time" to LegendView(ctx).th().also { it.options = ChartOptions(row = true); it.setData(Feed.time.map { LegendItem(it.label) }) },
        )
    }

    private val heights = mapOf("steps" to 200f, "fan_quarter" to 190f, "fan_half" to 190f, "fan_ring" to 190f, "line" to 200f)

    @Test fun everyChartOnThreeGrounds() {
        for ((gname, tone) in grounds) {
            val theme = tone.theme()
            for ((name, v) in charts(theme)) {
                card(v, heights[name])
                val bmp = Rig.draw(v, theme.ground)
                Rig.png(bmp, File(outDir, "${gname}_$name.png"))
                val inked = Rig.inked(bmp, theme.ground)
                assertTrue("$gname/$name has ink ($inked)", inked > 200)
                assertTrue("$gname/$name leaves ground", inked < bmp.width * bmp.height * 0.9)
                assertTrue("$gname/$name is phone width", bmp.width == (405 * d).toInt())
            }
        }
    }

    @Test fun pressedAndFocusedStatesRender() {
        val theme = VizTone.SUN.theme()
        val v = StepsView(ctx).also { it.theme = theme; it.setData(Feed.builds) }
        card(v, 200f)
        v.focusMark(2)
        val bmp = Rig.draw(v, theme.ground)
        Rig.png(bmp, File(outDir, "sun_steps_active.png"))
        val plain = Rig.draw(StepsView(ctx).also { it.theme = theme; it.setData(Feed.builds); card(it, 200f) }, theme.ground)
        assertTrue("an active mark changes the picture", bmp.sameAs(plain).not())
        val line = LineView(ctx).also { it.theme = theme; it.options = Feed.commitsOptions; it.setData(Feed.commits) }
        card(line, 200f)
        line.focusMark(18)
        Rig.png(Rig.draw(line, theme.ground), File(outDir, "sun_line_crosshair.png"))
    }

    @Test fun texturesInTheirFixedOrder() {
        for ((gname, tone) in grounds) {
            val t = tone.theme()
            val tile = 80 * d.toInt()
            val bmp = Bitmap.createBitmap(tile * 8 + 9 * 14, tile + 28, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(t.ground)
            val painter = TexturePainter(t.ink, 1.25f, d)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            Viz.ORDER.forEachIndexed { i, tex ->
                c.save(); c.translate(14f + i * (tile + 14f), 14f); c.scale(d, d)
                val box = android.graphics.RectF(0f, 0f, tile / d, tile / d)
                painter.apply(p, tex, box, FadeDir.UP)
                c.drawRoundRect(box, 16f, 16f, p)
                c.restore()
            }
            Rig.png(bmp, File(outDir, "${gname}_textures.png"))
            assertTrue(Rig.inked(bmp, t.ground) > 5000)
        }
    }
}
