package dev.aarso.viz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One tappable, focusable, announced mark. [bounds] is what it looks like; [target] is what a finger / TalkBack gets. */
internal class Node(
    val mark: Mark?,
    val description: String,
    val bounds: RectF,
    val target: RectF,
    /** Where a tooltip anchors for keyboard / accessibility focus (dp). */
    val anchorX: Float,
    val anchorY: Float,
    /** Exact hit test for shapes whose box is a poor guide (fan slices); null means [target]. */
    val precise: ((Float, Float) -> Boolean)? = null,
    /** Outline for the focus ring where a rectangle would be wrong. */
    val outline: Path? = null,
)

/** The tooltip: the value leads, the name follows; [head] is a line above (the line chart's x). */
internal class Tip(val rows: List<TipRow>, val head: String?, val x: Float, val y: Float)
internal class TipRow(val value: String, val label: String?, val dash: FloatArray? = null, val hasDash: Boolean = false)

/**
 * Base of every chart: a width-driven `View` that measures itself, draws in dp (the canvas is scaled
 * by density, so geometry here is viz.js's geometry), answers a finger / mouse / DPAD with a tooltip
 * and `onHover` / `onSelect`, and presents every mark as a virtual accessibility node.
 *
 * Subclasses supply data setters, [naturalHeight], [build] (geometry + nodes), [draw] and [table].
 */
abstract class VizView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** The ink, ground and type the chart is drawn with. */
    var theme: VizTheme = VizTheme()
        set(v) { field = v; painters.clear(); faces.clear(); changed(true) }

    /** The options besides data (sweep, x labels, ...). Views also expose the common ones as properties. */
    var options: ChartOptions = ChartOptions()
        set(v) { field = v; changed(true) }

    /** The accessible name of the chart; null builds one from the figures. */
    var chartLabel: String? = null
        set(v) { field = v; refreshDescription() }

    /** A mark was activated (tap, Enter, TalkBack double-tap). */
    var onSelect: ((Mark) -> Unit)? = null
        set(v) { field = v; changed(false) }

    /** A mark is hovered, pressed or focused; null when none is. */
    var onHover: ((Mark?) -> Unit)? = null

    internal val density: Float get() = resources.displayMetrics.density
    private val a11y = VizAccessibility(this)

    private var built = false
    private var bw = -1f
    private var bh = -1f
    internal var nodes: List<Node> = emptyList()
        private set
    internal var activeIndex = -1
        private set
    internal var tip: Tip? = null
        private set
    private var keyIndex = -1
    private val painters = HashMap<Float, TexturePainter>()
    private val faces = HashMap<Int, android.graphics.Typeface>()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true; fontFeatureSettings = "tnum" }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, a11y)
    }

    // ---- what a subclass supplies ----------------------------------------------------------------

    /** The chart's own height in dp for a content width of [w]; [avail] is the height the parent fixed, if it fixed one. */
    protected abstract fun naturalHeight(w: Float, avail: Float?): Float

    /** Lay the chart out for [w] x [h] dp: compute geometry and add its marks with [addNode]. */
    protected abstract fun build(w: Float, h: Float)

    /** Draw in dp; the canvas is already scaled and translated past the padding. */
    protected abstract fun drawChart(c: Canvas, w: Float, h: Float)

    /** The same figures as a table, from the data alone (no layout needed). */
    abstract fun table(): VizTable

    /** Whether the chart answers touch and keys. Sparks and stats do not. */
    protected open val interactive: Boolean get() = true

    /** Whether a drag across the chart scrubs a readout (the line chart) rather than pressing one mark. */
    protected open val scrubs: Boolean get() = false

    /** The nodes TalkBack is given; the marks themselves unless a chart's marks are too small to be useful one by one. */
    internal open val a11yNodes: List<Node> get() { ensureBuilt(); return nodes }

    /** The tooltip for node [i] at ([x], [y]) dp. */
    internal open fun tipFor(i: Int, x: Float, y: Float): Tip? {
        val m = nodes[i].mark ?: return null
        return Tip(listOf(TipRow(m.text, m.label)), null, x, y)
    }

    /** The key that moves from node [i]; the default steps through the marks in data order. */
    protected open fun neighbour(i: Int, keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> i + 1
        else -> i - 1
    }

    // ---- public API ------------------------------------------------------------------------------

    /** The same figures as text (tab-separated), for a table view beside the chart. */
    fun tableText(): String = table().text()

    /** Marks in layout order, for hosts and tests. */
    fun marks(): List<Mark> { ensureBuilt(); return nodes.mapNotNull { it.mark } }

    /** Step to mark [i] as the keyboard would, showing its readout. */
    fun focusMark(i: Int) { ensureBuilt(); if (i in nodes.indices) { keyIndex = i; setActive(i, nodes[i].anchorX, nodes[i].anchorY) } }

    // ---- plumbing --------------------------------------------------------------------------------

    /** Subclasses call this when their data changed. */
    protected fun changed(relayout: Boolean = true) {
        built = false
        activeIndex = -1; tip = null; keyIndex = -1
        isFocusable = interactive
        refreshDescription()
        if (relayout) requestLayout()
        invalidate()
        a11y.invalidateRoot()
    }

    internal fun addNode(n: Node) { (nodeList).add(n) }
    private val nodeList = ArrayList<Node>()

    protected fun rem(): Float = theme.rem
    protected fun fmt(v: Double): String = theme.format(v)

    internal fun ensureBuilt() {
        val w = contentW(); val h = contentH()
        if (built && w == bw && h == bh) return
        bw = w; bh = h
        nodeList.clear()
        activeIndex = -1; tip = null
        build(w, h)
        nodes = ArrayList(nodeList)
        built = true
        a11y.invalidateRoot()
    }

    private fun contentW() = max(40f, (width - paddingLeft - paddingRight) / density)
    private fun contentH() = max(1f, (height - paddingTop - paddingBottom) / density)

    private fun refreshDescription() {
        contentDescription = chartLabel ?: describe()
    }

    /** The chart's accessible name: the first dozen rows of the table, as viz.js builds it. */
    protected open fun describe(): String = table().rows.take(12).joinToString(", ") { it.joinToString(" ") }

    internal fun hit(list: List<Node>, x: Float, y: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in list.indices) {
            val n = list[i]
            val inside = n.precise?.invoke(x, y) ?: n.target.contains(x, y)
            if (!inside) continue
            val dd = hypot(x - n.target.centerX(), y - n.target.centerY())
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    internal fun selectNode(i: Int): Boolean {
        val m = a11yNodes.getOrNull(i)?.mark ?: return false
        val cb = onSelect ?: return false
        cb(m)
        return true
    }

    internal fun keyboardFocusChanged(id: Int, has: Boolean) {
        val n = a11yNodes.getOrNull(id) ?: return
        val idx = nodes.indexOfFirst { it === n }.let { if (it >= 0) it else nodes.indexOfFirst { x -> x.mark != null && x.mark == n.mark } }
        if (has && idx >= 0) { keyIndex = idx; setActive(idx, nodes[idx].anchorX, nodes[idx].anchorY) }
        else if (!has) { keyIndex = -1; setActive(-1, 0f, 0f) }
    }

    private fun setActive(i: Int, x: Float, y: Float) {
        val moved = i != activeIndex
        activeIndex = i
        tip = if (i >= 0 && i < nodes.size) tipFor(i, x, y) else null
        if (moved) onHover?.invoke(nodes.getOrNull(i)?.mark)
        invalidate()
    }

    // ---- measuring -------------------------------------------------------------------------------

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val d = density
        val padH = paddingLeft + paddingRight
        val padV = paddingTop + paddingBottom
        val wMode = MeasureSpec.getMode(widthSpec)
        val wPx = if (wMode == MeasureSpec.UNSPECIFIED) ceil(320f * d).toInt() + padH else MeasureSpec.getSize(widthSpec)
        val wDp = max(40f, (wPx - padH) / d)
        val avail = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) (MeasureSpec.getSize(heightSpec) - padV) / d else null
        val hDp = options.height ?: naturalHeight(wDp, avail)
        setMeasuredDimension(wPx, resolveSize(ceil(hDp * d).toInt() + padV, heightSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        built = false
    }

    // ---- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        ensureBuilt()
        val w = contentW(); val h = contentH()
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.scale(density, density)
        drawChart(canvas, w, h)
        drawFocus(canvas)
        tip?.let { drawTip(canvas, it, w, h) }
        canvas.restore()
    }

    private fun drawFocus(c: Canvas) {
        if (!hasFocus() || keyIndex !in nodes.indices) return
        val n = nodes[keyIndex]
        sp.shader = null; sp.style = Paint.Style.STROKE; sp.strokeWidth = 2f; sp.color = theme.ink; sp.alpha = 255
        val o = n.outline
        if (o != null) c.drawPath(o, sp) else {
            val b = RectF(n.bounds); b.inset(-3f, -3f)
            c.drawRoundRect(b, 6f, 6f, sp)
        }
    }

    private fun drawTip(c: Canvas, t: Tip, w: Float, h: Float) {
        val f = theme.rem * 0.72f
        val lh = f * 1.35f
        val padX = f * 0.8f; val padY = f * 0.4f
        val keyW = 14f
        var inner = 0f
        val widths = t.rows.map { r ->
            val vw = textWidth(r.value, f, 500); val lw = r.label?.let { textWidth(it, f, theme.weight) } ?: 0f
            (if (r.hasDash) keyW + f * 0.5f else 0f) + vw + (if (r.label != null) f * 0.5f + lw else 0f)
        }
        widths.forEach { inner = max(inner, it) }
        t.head?.let { inner = max(inner, textWidth(it, f, theme.weight)) }
        val tw = inner + padX * 2
        val th = (t.rows.size + (if (t.head != null) 1 else 0)) * lh + padY * 2
        var top = t.y - th - 10
        if (top < 0 && t.y + 18 + th <= h) top = t.y + 18
        top = max(0f, min(top, h - th))
        val left = max(0f, min(w - tw, t.x - tw / 2))
        sp.style = Paint.Style.FILL; sp.shader = null; sp.color = theme.ink; sp.alpha = 255
        c.drawRoundRect(RectF(left, top, left + tw, top + th), f, f, sp)
        var y = top + padY
        t.head?.let { text(c, it, left + padX, y + lh * 0.5f + f * 0.35f, f, color = theme.ground, alpha = 0.6f); y += lh }
        t.rows.forEachIndexed { i, r ->
            var x = left + padX
            val base = y + lh * 0.5f + f * 0.35f
            if (r.hasDash) {
                sp.style = Paint.Style.STROKE; sp.strokeWidth = 2f; sp.color = theme.ground; sp.strokeCap = Paint.Cap.ROUND
                sp.pathEffect = r.dash?.let { android.graphics.DashPathEffect(it, 0f) }
                c.drawLine(x, y + lh / 2, x + keyW, y + lh / 2, sp)
                sp.pathEffect = null; sp.strokeCap = Paint.Cap.BUTT
                x += keyW + f * 0.5f
            }
            text(c, r.value, x, base, f, weight = 500, color = theme.ground)
            r.label?.let { text(c, it, x + textWidth(r.value, f, 500) + f * 0.5f, base, f, color = theme.ground, alpha = 0.7f) }
            y += lh
        }
    }

    // ---- helpers for subclasses ------------------------------------------------------------------

    /** The texture painter at the theme's scale times [mul] (the legend's keys are drawn at 0.62). */
    internal fun texturePainter(mul: Float = 1f): TexturePainter {
        val d = density
        painters.keys.filter { painters[it]?.density != d }.forEach { painters.remove(it) }
        return painters.getOrPut(mul) { TexturePainter(theme.ink, theme.textureScale * mul, d) }
    }

    private fun face(w: Int) = faces.getOrPut(w) { theme.typeface(w) }

    internal fun textWidth(s: String, size: Float, weight: Int = theme.weight): Float {
        tp.typeface = face(weight); tp.textSize = size
        return tp.measureText(s)
    }

    /** Text at a baseline point; [size] in dp, [alpha] 0..1. */
    internal fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float,
        align: Paint.Align = Paint.Align.LEFT, weight: Int = theme.weight, alpha: Float = 1f, color: Int = theme.ink,
    ) {
        tp.typeface = face(weight); tp.textSize = size; tp.textAlign = align; tp.color = color
        tp.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawText(s, x, y, tp)
    }

    /** Fill [path] with texture [t] (a fade climbs across [box]); [alpha] 0..1. */
    internal fun fill(c: Canvas, path: Path, t: Texture?, box: RectF, dir: FadeDir = FadeDir.UP, alpha: Float = 1f) {
        sp.style = Paint.Style.FILL
        texturePainter().apply(sp, t, box, dir)
        sp.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawPath(path, sp)
        sp.shader = null
    }

    /** Alpha for node [i] while another is active: the others step back to 30%. */
    internal fun dim(i: Int): Float = if (activeIndex < 0 || activeIndex == i) 1f else 0.3f

    /** The texture for mark [i]: its own, else the chart's one, else by order. */
    internal fun textureFor(d: Datum?, i: Int): Texture {
        val o = options
        return d?.fill ?: (if (o.fill != null && !o.fillEach) o.fill else null) ?: Viz.ORDER[i % Viz.ORDER.size]
    }

    /** A node whose target is [bounds] grown to a finger (where the view has room). */
    internal fun node(
        mark: Mark?, desc: String, bounds: RectF, w: Float, h: Float, ax: Float, ay: Float,
        precise: ((Float, Float) -> Boolean)? = null, outline: Path? = null, minTarget: Float = Viz.TARGET_DP,
    ): Node = Node(mark, desc, bounds, Shapes.target(bounds, minTarget, RectF(0f, 0f, w, h)), ax, ay, precise, outline)

    internal fun paintScratch(): Paint = sp

    // ---- touch, mouse, keys ----------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive || !isEnabled) return super.onTouchEvent(e)
        ensureBuilt()
        val d = density
        val x = (e.x - paddingLeft) / d; val y = (e.y - paddingTop) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = hit(nodes, x, y)
                if (i < 0) return false
                downX = e.x; downY = e.y; dragging = false
                keyIndex = -1
                setActive(i, x, y)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (scrubs && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                val i = hit(nodes, x, y)
                if (i >= 0) setActive(i, x, y) else if (!scrubs) setActive(-1, x, y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val i = activeIndex
                isPressed = false
                if (i >= 0) { nodes.getOrNull(i)?.mark?.let { m -> onSelect?.invoke(m) }; performClick() }
                setActive(-1, 0f, 0f)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; setActive(-1, 0f, 0f); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (a11y.dispatchHoverEvent(event)) return true
        if (!interactive || event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return super.dispatchHoverEvent(event)
        ensureBuilt()
        val x = (event.x - paddingLeft) / density; val y = (event.y - paddingTop) / density
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> setActive(hit(nodes, x, y), x, y)
            MotionEvent.ACTION_HOVER_EXIT -> setActive(-1, 0f, 0f)
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!interactive) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        ensureBuilt()
        if (nodes.isEmpty()) return super.dispatchKeyEvent(event)
        val k = event.keyCode
        when (k) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> {
                val cur = if (keyIndex in nodes.indices) keyIndex else -1
                val next = when (k) {
                    KeyEvent.KEYCODE_MOVE_HOME -> 0
                    KeyEvent.KEYCODE_MOVE_END -> nodes.size - 1
                    KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) cur - 1 else cur + 1
                    else -> if (cur < 0) 0 else neighbour(cur, k)
                }
                if (next !in nodes.indices) {
                    // Tab walks off either end and leaves the chart; an arrow just stops at the edge.
                    if (k == KeyEvent.KEYCODE_TAB) { keyIndex = -1; setActive(-1, 0f, 0f); return super.dispatchKeyEvent(event) }
                    return true
                }
                keyIndex = next
                setActive(next, nodes[next].anchorX, nodes[next].anchorY)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                val i = if (keyIndex in nodes.indices) keyIndex else return super.dispatchKeyEvent(event)
                nodes[i].mark?.let { m -> onSelect?.invoke(m) }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!interactive) return
        ensureBuilt()
        if (gainFocus && nodes.isNotEmpty()) {
            val i = if (direction == FOCUS_BACKWARD || direction == FOCUS_UP || direction == FOCUS_LEFT) nodes.size - 1 else 0
            keyIndex = i; setActive(i, nodes[i].anchorX, nodes[i].anchorY)
        } else { keyIndex = -1; setActive(-1, 0f, 0f) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeIndex = -1; tip = null; keyIndex = -1
    }
}
