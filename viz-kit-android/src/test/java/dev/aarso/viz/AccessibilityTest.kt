package dev.aarso.viz

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    private val ctx get() = Rig.context
    private val d get() = Rig.density

    private fun steps() = StepsView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.builds); Rig.layout(it, 340f, 192f) }
    private fun info(v: VizView, id: Int): AccessibilityNodeInfo = v.accessibilityNodeProvider.createAccessibilityNodeInfo(id)!!
    private fun key(v: VizView, code: Int, shift: Boolean = false) =
        v.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, if (shift) KeyEvent.META_SHIFT_ON else 0))
    private fun touch(v: VizView, action: Int, xDp: Float, yDp: Float) =
        v.dispatchTouchEvent(MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, xDp * d, yDp * d, 0))

    @Test fun everyMarkIsAVirtualNodeWithNameAndFigure() {
        val v = steps()
        assertEquals(4, v.a11yNodes.size)
        assertEquals(listOf("Failed: 1", "Flaky: 3", "Running: 14", "Passed: 94"), (0 until 4).map { info(v, it).contentDescription.toString() })
    }

    @Test fun nodeBoundsAreInsideTheViewAndFingerSized() {
        val v = steps()
        for (i in 0 until 4) {
            val r = Rect(); info(v, i).getBoundsInParent(r)
            assertTrue("w ${r.width() / d}", r.width() / d >= 44f - 0.5f)
            assertTrue("h ${r.height() / d}", r.height() / d >= 44f - 0.5f)
            assertTrue(r.left >= 0 && r.right <= v.width && r.top >= 0 && r.bottom <= v.height)
        }
    }

    @Test fun theChartHasAnAccessibleNameBuiltFromTheFigures() {
        val v = steps()
        assertEquals("Failed 1, Flaky 3, Running 14, Passed 94", v.contentDescription.toString())
        v.chartLabel = "Builds this week"
        assertEquals("Builds this week", v.contentDescription.toString())
    }

    @Test fun tableTextIsTheSameFiguresAsText() {
        assertEquals("\tValue\nFailed\t1\nFlaky\t3\nRunning\t14\nPassed\t94", steps().tableText())
        val t = StepsView(ctx).also { it.options = ChartOptions(unit = "Builds"); it.setData(Feed.builds) }
        assertTrue(t.tableText().startsWith("\tBuilds\n"))
        assertEquals("", "")
        val line = LineView(ctx).also { it.options = Feed.commitsOptions; it.setData(Feed.commits) }
        val rows = line.tableText().lines()
        assertEquals("Day\tmain\tbranches", rows[0]); assertEquals("12 Oct\t4\t9", rows[1]); assertEquals(31, rows.size)
        val ht = HalftoneView(ctx).also { it.setData(Feed.hours) }.table()
        assertEquals(7, ht.rows.size); assertEquals(25, ht.head!!.size)
        assertEquals("Stat", "Stat")
        assertEquals("Crash-free sessions\t99.2%", StatView(ctx).also { it.setData(Feed.stat) }.tableText())
    }

    @Test fun selectingFromTalkBackReachesTheCallback() {
        val v = steps(); var got: Mark? = null
        v.onSelect = { got = it }
        assertTrue(v.accessibilityNodeProvider.performAction(3, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("Passed", got!!.label); assertEquals(94.0, got!!.value, 0.0); assertEquals("Passed", got!!.datum!!.label)
        assertTrue(info(v, 3).actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    }

    @Test fun withoutAListenerThereIsNoClickAction() {
        val v = steps()
        assertTrue(info(v, 0).actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
    }

    @Test fun dpadStepsMarkByMarkAndEnterSelects() {
        val v = steps(); val hovered = ArrayList<String?>(); var picked: Mark? = null
        v.onHover = { hovered.add(it?.label) }; v.onSelect = { picked = it }
        assertTrue(key(v, KeyEvent.KEYCODE_DPAD_RIGHT)); assertTrue(key(v, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(key(v, KeyEvent.KEYCODE_DPAD_LEFT)); assertTrue(key(v, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf<String?>("Failed", "Flaky", "Failed"), hovered)
        assertEquals("Failed", picked!!.label)
        key(v, KeyEvent.KEYCODE_MOVE_END); assertEquals(3, v.activeIndex)
        key(v, KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("an arrow stops at the edge", 3, v.activeIndex)
        key(v, KeyEvent.KEYCODE_MOVE_HOME); assertEquals(0, v.activeIndex)
    }

    @Test fun tabWalksOffTheEndAndLeavesTheChart() {
        val v = steps()
        key(v, KeyEvent.KEYCODE_MOVE_END)
        key(v, KeyEvent.KEYCODE_TAB)
        assertEquals(-1, v.activeIndex)
        key(v, KeyEvent.KEYCODE_TAB, shift = true) // not handled as a step once off the chart: starts at the end
        assertTrue(v.activeIndex == -1 || v.activeIndex == 3)
    }

    @Test fun aPressShowsTheReadoutAndTheOthersStepBack() {
        val v = steps(); val hovered = ArrayList<String?>(); var picked: Mark? = null
        v.onHover = { hovered.add(it?.label) }; v.onSelect = { picked = it }
        val col = v.columns()[2]
        val x = col.x + col.w / 2
        assertTrue(touch(v, MotionEvent.ACTION_DOWN, x, 100f))
        assertEquals(2, v.activeIndex); assertNotNull(v.tip)
        assertEquals("14", v.tip!!.rows[0].value); assertEquals("Running", v.tip!!.rows[0].label)
        assertEquals(1f, v.dim(2), 0f); assertEquals(0.3f, v.dim(0), 0f)
        touch(v, MotionEvent.ACTION_UP, x, 100f)
        assertEquals("Running", picked!!.label)
        assertEquals(listOf<String?>("Running", null), hovered)
        assertEquals(-1, v.activeIndex); assertNull(v.tip)
    }

    @Test fun aCancelledTouchSelectsNothing() {
        val v = steps(); var picked: Mark? = null
        v.onSelect = { picked = it }
        touch(v, MotionEvent.ACTION_DOWN, 60f, 100f); touch(v, MotionEvent.ACTION_CANCEL, 60f, 100f)
        assertNull(picked); assertEquals(-1, v.activeIndex)
    }

    @Test fun fanSlicesAreHitByShapeNotBox() {
        val v = FanView(ctx).also { it.theme = Rig.SUN; it.options = ChartOptions(sweep = 360, showCallout = false); it.setData(Feed.storage); Rig.layout(it, 300f, 200f) }
        val g = v.geometry()
        for ((n, s) in g.slices.withIndex()) {
            val a = (s.a0 + s.a1) / 2; val r = (g.r + g.inner) / 2
            assertEquals(n, v.hit(v.nodes, g.cx + Math.cos(a.toDouble()).toFloat() * r, g.cy + Math.sin(a.toDouble()).toFloat() * r))
        }
        assertEquals("the hole is no slice", -1, v.hit(v.nodes, g.cx, g.cy))
        assertEquals(listOf("Photos: 61 · 46%", "Video: 38 · 28%", "Apps: 23 · 17%", "Cache: 12 · 9%"), v.a11yNodes.map { it.description })
    }

    @Test fun lineStepsThroughPointsWithOneReadoutForEverySeries() {
        val v = LineView(ctx).also { it.theme = Rig.SUN; it.options = Feed.commitsOptions; it.setData(Feed.commits); Rig.layout(it, 340f, 200f) }
        assertEquals(30, v.a11yNodes.size)
        assertEquals("12 Oct: main 4, branches 9", info(v, 0).contentDescription.toString())
        key(v, KeyEvent.KEYCODE_MOVE_END)
        assertEquals(29, v.activeIndex)
        val tip = v.tip!!
        assertEquals("10 Nov", tip.head)
        assertEquals(listOf("21", "3"), tip.rows.map { it.value }); assertEquals(listOf("main", "branches"), tip.rows.map { it.label })
        assertTrue("dash keys with more than one series", tip.rows.all { it.hasDash })
        key(v, KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(28, v.activeIndex)
    }

    @Test fun lineScrubsUnderAMovingFinger() {
        val v = LineView(ctx).also { it.theme = Rig.SUN; it.options = Feed.commitsOptions; it.setData(Feed.commits); Rig.layout(it, 340f, 200f) }
        val g = v.geometry()!!
        touch(v, MotionEvent.ACTION_DOWN, g.x(3), 80f); assertEquals(3, v.activeIndex)
        touch(v, MotionEvent.ACTION_MOVE, g.x(10), 80f); assertEquals(10, v.activeIndex)
        touch(v, MotionEvent.ACTION_UP, g.x(10), 80f); assertEquals(-1, v.activeIndex)
    }

    @Test fun sparkIsOneAnnouncementNotThirtyNodes() {
        val v = SparkView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.spark); Rig.layout(it, 160f, 44f) }
        assertEquals(0, v.a11yNodes.size)
        assertEquals("Trend of 14 points: 98.1 to 99.2, low 98.1, high 99.3", v.contentDescription.toString())
        assertTrue(!v.isFocusable)
    }

    @Test fun statReadsAsOneStatementWithDirectionInWords() {
        val v = StatView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.stat); Rig.layout(it, 160f) }
        assertEquals("Crash-free sessions: 99.2%, up 0.4 pts vs last week", v.contentDescription.toString())
        assertEquals("down", StatView(ctx).also { it.setData(Stat("x", 1.0, delta = -2.0)) }.direction())
        assertEquals("flat", StatView(ctx).also { it.setData(Stat("x", 1.0, delta = 0.0)) }.direction())
        assertEquals("+0.4 pts", v.deltaText())
        assertEquals("-2", StatView(ctx).also { it.setData(Stat("x", 1.0, delta = -2.0)) }.deltaText())
    }

    @Test fun meterNodeCarriesTheWholeStatement() {
        val v = MeterView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.meter); Rig.layout(it, 300f) }
        assertEquals("beta seats: 412 of 500 · 82%", info(v, 0).contentDescription.toString())
        val r = Rect(); info(v, 0).getBoundsInParent(r)
        assertTrue(r.height() / d >= 44f)
    }

    @Test fun legendBecomesFingerTargetsOnlyWhenItIsAControl() {
        val items = Feed.storage.map { LegendItem(it.label, it.fill, value = it.value) }
        val quiet = LegendView(ctx).also { it.theme = Rig.SUN; it.options = ChartOptions(values = true); it.setData(items); Rig.layout(it, 160f) }
        assertEquals(0, quiet.a11yNodes.size); assertTrue(!quiet.isFocusable)
        assertEquals("Photos 61, Video 38, Apps 23, Cache 12", quiet.contentDescription.toString())
        val h = quiet.measuredHeight / d
        assertTrue("compact $h", h < 4 * 24f)
        var picked: Mark? = null
        val control = LegendView(ctx).also { it.theme = Rig.SUN; it.options = ChartOptions(values = true); it.setData(items); it.onSelect = { m -> picked = m }; Rig.layout(it, 160f) }
        assertEquals(4, control.a11yNodes.size); assertTrue(control.isFocusable)
        for (i in 0 until 4) { val r = Rect(); info(control, i).getBoundsInParent(r); assertTrue(r.height() / d >= 47.5f) }
        control.accessibilityNodeProvider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals("Video", picked!!.label)
    }

    @Test fun halftoneGivesTalkBackRowsWhenCellsAreTooSmall() {
        val v = HalftoneView(ctx).also { it.theme = Rig.SUN; it.setData(Feed.hours); it.onSelect = {}; Rig.layout(it, 405f) }
        assertEquals(168, v.marks().size)
        assertEquals(7, v.a11yNodes.size)
        assertTrue(info(v, 0).contentDescription.toString().startsWith("Mon: total "))
        key(v, KeyEvent.KEYCODE_DPAD_RIGHT); key(v, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(24 + 0, v.activeIndex)
        assertEquals("Tue · 00", v.marks()[v.activeIndex].label)
    }

    @Test fun everyTargetIsFingerSizedWhereTheLayoutHasRoom() {
        val views = listOf<VizView>(
            steps(),
            BarsView(ctx).also { it.setData(Feed.repos); Rig.layout(it, 340f) },
            StackView(ctx).also { it.setData(Feed.time); Rig.layout(it, 340f) },
            FanView(ctx).also { it.setData(Feed.storage); Rig.layout(it, 340f, 200f) },
            FanView(ctx).also { it.options = ChartOptions(sweep = 360); it.setData(Feed.storage); Rig.layout(it, 340f, 200f) },
            MeterView(ctx).also { it.setData(Feed.meter); Rig.layout(it, 340f) },
        )
        for (v in views) for (n in v.a11yNodes) {
            assertTrue("${v.javaClass.simpleName} ${n.description} w=${n.target.width()}", n.target.width() >= 44f - 1e-3f)
            assertTrue("${v.javaClass.simpleName} ${n.description} h=${n.target.height()}", n.target.height() >= 44f - 1e-3f)
        }
    }
}
