package dev.aarso.viz

import android.graphics.Color
import android.graphics.RectF
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI

/** What the kit promises about its marks, measured on the laid-out geometry and on rendered pixels. */
@RunWith(RobolectricTestRunner::class)
class GeometryTest {
    private val ctx get() = Rig.context
    private fun steps(w: Float = 300f, h: Float = 192f, d: List<Datum> = Feed.builds) = StepsView(ctx).also { it.theme = Rig.SUN; it.setData(d); Rig.layout(it, w, h) }

    @Test fun columnsStandOnOneBaselineAndRiseFromZero() {
        val v = steps()
        val c = v.columns()
        c.forEach { assertEquals(192f, it.y + it.h, 1e-3f) }
        assertEquals(0f, StepsView(ctx).also { it.setData(listOf(Datum("none", 0.0), Datum("one", 1.0))); Rig.layout(it, 300f, 192f) }.columns()[0].h, 0f)
    }

    @Test fun stepsAreOnALinearScale() {
        val c = steps().columns()
        // 3 and 14 are both above the 3 dp floor, so heights are exactly proportional.
        assertEquals(14.0 / 3.0, (c[2].h / c[1].h).toDouble(), 1e-3)
        assertEquals(94.0 / 14.0, (c[3].h / c[2].h).toDouble(), 1e-3)
    }

    @Test fun aValueOfOneBesideNinetyFourIsASliver() {
        val c = steps().columns()
        assertTrue("sliver ${c[0].h}", c[0].h < 5f)
        assertTrue(c[0].h >= 3f)
        assertTrue(c[3].h > 100f)
    }

    @Test fun barsStartAtZeroOnALinearScale() {
        val v = BarsView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.repos); Rig.layout(it, 300f) }
        val r = v.rowsLaidOut()
        assertEquals(300f, r[0].bar, 1e-3f)
        assertEquals(131.0 / 412.0 * 300, r[1].bar.toDouble(), 1e-2)
        assertEquals(88.0 / 131.0, (r[2].bar / r[1].bar).toDouble(), 1e-3)
        val b = RectF(); Shapes.bar(0f, r[2].bar, r[2].thick).computeBounds(b, true)
        assertEquals(0f, b.left, 0f)
        assertEquals(r[2].bar, b.right, 1e-2f)
    }

    @Test fun aNegativeOrZeroValueDrawsNoBar() {
        val v = BarsView(ctx).also { it.setData(listOf(Datum("a", 5.0), Datum("b", 0.0), Datum("c", -3.0))); Rig.layout(it, 300f) }
        assertEquals(listOf(true, false, false), v.rowsLaidOut().map { it.bar > 0 })
    }

    @Test fun barRowsAreTouchHighAndSelfSized() {
        val v = BarsView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.repos); Rig.layout(it, 300f) }
        assertTrue(v.rowsLaidOut()[1].y - v.rowsLaidOut()[0].y >= 44f)
        assertEquals((5 * 44f - 8f) * Rig.density, v.measuredHeight.toFloat(), 1f)
    }

    @Test fun theNinthPartFoldsIntoOther() {
        assertEquals(8, Viz.fold(Feed.time).size)
        val other = Viz.fold(Feed.time).last()
        assertEquals("Other", other.label); assertEquals(1.5, other.value, 1e-9)
        assertEquals(listOf("Heron", "Islet"), other.parts.map { it.label })
        assertEquals(Feed.time.take(7), Viz.fold(Feed.time).take(7))
        // Eight stay eight; there is no ninth texture.
        assertEquals(Feed.time.take(8), Viz.fold(Feed.time.take(8)))
        assertEquals(8, Viz.ORDER.size)
        assertEquals(8, Viz.fold(List(20) { Datum("p$it", 1.0) }).size)
    }

    @Test fun stackSplitsOnePillWithTwoDpGaps() {
        val v = StackView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.time); Rig.layout(it, 300f) }
        val s = v.segments()
        assertEquals(8, s.size); assertEquals("Other", s.last().datum.label)
        for (i in 0 until s.size - 1) assertEquals(2f, s[i + 1].x - (s[i].x + s[i].w), 1e-3f)
        assertEquals(300f, s.last().x + s.last().w, 1e-2f)
        assertEquals(18.5 / 11.0, (s[0].w / s[1].w).toDouble(), 1e-3)
        assertEquals("36%", s[0].pct) // 18.5 of 51, the last two parts folded into one 1.5
    }

    @Test fun aSegmentIsLabelledOnlyWhereItsLabelFits() {
        val s = StackView(ctx).also { it.setData(Feed.time); Rig.layout(it, 300f) }.segments()
        assertTrue(s[0].labelled); assertTrue(!s.last().labelled)
    }

    @Test fun theGapBetweenPartsIsTheGroundNotAnOutline() {
        val v = StackView(ctx).also { it.theme = Rig.SUN; it.setData(listOf(Datum("a", 1.0), Datum("b", 1.0))); Rig.layout(it, 300f) }
        val bmp = Rig.draw(v, Rig.SUN.ground)
        val d = Rig.density
        val seg = v.segments()
        val gapX = ((seg[0].x + seg[0].w + 1f) * d).toInt()
        val y = (v.segments()[0].thick / 2 * d).toInt()
        assertEquals("gap pixel", Rig.SUN.ground, bmp.getPixel(gapX, y))
        assertTrue("part pixel is ink", Color.red(bmp.getPixel((seg[0].x * d + 40).toInt(), y)) < 0x40)
    }

    @Test fun fanPartsFillTheSweepAndAreSeparatedByGroundStroke() {
        val v = FanView(ctx).also { it.theme = Rig.SUN; it.options = ChartOptions(sweep = 180, showCallout = false); it.setData(Feed.storage); Rig.layout(it, 300f, 192f) }
        val g = v.geometry()
        assertEquals(PI.toFloat(), g.slices.last().a1 - g.slices.first().a0, 1e-3f)
        assertEquals(PI.toFloat(), g.slices.first().a0, 1e-6f)
        assertEquals(134.0, g.total, 1e-9)
        for (i in 0 until g.slices.size - 1) assertEquals(g.slices[i].a1, g.slices[i + 1].a0, 1e-6f)
        val bmp = Rig.draw(v, Rig.SUN.ground)
        val a = g.slices[1].a1; val r = g.r * 0.8f; val d = Rig.density
        val px = bmp.getPixel(((g.cx + Math.cos(a.toDouble()) * r) * d).toInt(), ((g.cy + Math.sin(a.toDouble()) * r) * d).toInt())
        assertEquals("the seam between slices is the ground", Rig.SUN.ground, px)
    }

    @Test fun fanHonoursSweepAndInner() {
        for ((sweep, span) in listOf(90 to PI / 2, 180 to PI, 360 to 2 * PI)) {
            val v = FanView(ctx).also { it.options = ChartOptions(sweep = sweep, inner = if (sweep == 360) null else 0.5f); it.setData(Feed.storage); Rig.layout(it, 300f, 192f) }
            val g = v.geometry()
            assertEquals("sweep $sweep", span.toFloat(), g.span, 1e-4f)
            assertEquals(if (sweep == 360) 0.6f * g.r else 0.5f * g.r, g.inner, 1e-3f)
        }
        val ring = FanView(ctx).also { it.options = ChartOptions(sweep = 360); it.setData(Feed.storage); Rig.layout(it, 300f, 192f) }
        assertNull(ring.geometry().pill)
        assertEquals("134", ring.geometry().callout)
    }

    @Test fun lineKeepsOneAxisAndFourSeries() {
        val five = List(5) { Series("s$it", listOf(1.0, 2.0 + it, 3.0)) }
        val v = LineView(ctx).also { it.setData(five); Rig.layout(it, 300f, 192f) }
        val g = v.geometry()!!
        assertEquals(4, g.series.size)
        assertEquals(4, Viz.DASHES.size); assertNull(Viz.DASHES[0])
        assertEquals(4, Viz.DASHES.map { it?.toList() }.toSet().size)
    }

    @Test fun lineAxisIsLinearFromZeroToANiceCeiling() {
        val v = LineView(ctx).also { it.options = Feed.commitsOptions; it.setData(Feed.commits); Rig.layout(it, 300f, 192f) }
        val g = v.geometry()!!
        assertEquals(0.0, g.lo, 0.0); assertEquals(25.0, g.hi, 0.0)
        assertEquals(g.mt, g.y(25.0), 1e-3f)
        assertEquals(g.mt + g.ph, g.y(0.0), 1e-3f)
        assertEquals(g.y(12.5), (g.y(0.0) + g.y(25.0)) / 2, 1e-3f)
        assertEquals(30, v.marks().size)
        assertEquals("12 Oct", v.marks().first().label)
    }

    @Test fun halftoneCellsCapAtTwoPointTwoRem() {
        val v = HalftoneView(ctx).also { it.setData(Feed.hours); Rig.layout(it, 405f) }
        val g = v.geometry()!!
        assertEquals(24, g.nc)
        assertTrue(g.cell <= 16 * 2.2f + 1e-3f)
        assertEquals(168, v.marks().size)
        val roomy = HalftoneView(ctx).also { it.setData(Matrix(listOf(listOf(1.0, 2.0)))); Rig.layout(it, 405f) }
        assertEquals(16 * 2.2f, roomy.geometry()!!.cell, 1e-3f)
    }

    @Test fun meterShareIsClampedAndGapped() {
        val m = MeterView(ctx).also { it.setData(Meter(412.0, 500.0, "beta seats")); Rig.layout(it, 300f) }
        assertEquals(0.824f, m.share, 1e-4f)
        assertEquals(1f, MeterView(ctx).also { it.setData(Meter(900.0, 500.0)) }.share, 0f)
        assertEquals(0f, MeterView(ctx).also { it.setData(Meter(-1.0, 500.0)) }.share, 0f)
    }

    @Test fun smoothCurveNeverOvershoots() {
        val pts = listOf(android.graphics.PointF(0f, 100f), android.graphics.PointF(50f, 10f), android.graphics.PointF(100f, 10f), android.graphics.PointF(150f, 90f))
        val b = RectF(); Shapes.curve(pts).computeBounds(b, false)
        assertTrue("top ${b.top}", b.top >= 10f - 1e-3f)
        assertTrue("bottom ${b.bottom}", b.bottom <= 100f + 1e-3f)
        assertNull(null as Path?)
    }

    @Test fun targetsGrowToAFingerAndStayInside() {
        val bounds = RectF(0f, 0f, 300f, 200f)
        val t = Shapes.target(RectF(2f, 10f, 10f, 20f), 48f, bounds)
        assertEquals(48f, t.width(), 1e-3f); assertEquals(48f, t.height(), 1e-3f)
        assertTrue(t.left >= 0f && t.top >= 0f)
        assertEquals(RectF(0f, 0f, 300f, 200f), Shapes.target(RectF(0f, 0f, 300f, 200f), 48f, bounds))
        val tiny = Shapes.target(RectF(0f, 0f, 5f, 5f), 48f, RectF(0f, 0f, 30f, 30f))
        assertEquals(30f, tiny.width(), 0f)
    }
}
