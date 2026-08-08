package dev.aarso.diagnostics.overlay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.aarso.diagnostics.Diagnostics
import dev.aarso.diagnostics.OverlayHost
import dev.aarso.diagnostics.core.SeriesSpec
import kotlin.math.abs

/**
 * The floating debug bubble.
 *
 * PLAIN VIEWS, NOT COMPOSE — on purpose. It has to composite correctly over a GLSurfaceView and a
 * live-wallpaper preview, which is exactly where a stutter most needs chasing, and no app in the
 * constellation should inherit Compose in order to get reliability tooling. Same reasoning that
 * keeps :crash-recovery outside Hyle.
 *
 * PROFILE-AWARE. The overlay reads the active profile's PRIMARY series rather than assuming frames:
 * on Bocal it shows audio callbacks against the buffer period, on Crocodyl inference against the
 * capture period, on ASOM request latency against the SLA. The label, the unit and the budget line
 * all come from the profile, because a panel that says "fps" over an audio app is worse than no
 * panel at all.
 *
 * MEASUREMENT HONESTY. The overlay costs frames, and for three of the seven profiles it cannot be
 * used at all without changing what is being measured — an IME (it covers the keyboard and steals
 * input), a launcher, and a wallpaper (it forces a foreground window over the surface under test).
 * Those profiles are refused here rather than quietly degrading the measurement; use the ADB
 * trigger, which is why that trigger exists.
 *
 * `diagnostics-overlay-mockup.html` is the authoritative visual and interaction spec. If a value
 * here disagrees with the mockup, the mockup wins.
 */
object OverlayController : OverlayHost {

    /** Profiles where a floating window would corrupt the very thing being measured. */
    private val REFUSED = setOf("ime", "wallpaper")

    private var view: OverlayView? = null
    private var hostActivity: Activity? = null

    // OverlayController is a process-lifetime singleton holding a strong View(Activity) reference;
    // without this, an Activity finished/rotated while the overlay is showing (and never paired
    // with an explicit hideOverlay()) stays reachable through that static field for the rest of the
    // process's life. This watches specifically for the hosting Activity's own destruction and
    // releases the reference automatically, on top of the existing explicit hide() path.
    private val lifecycleWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(a: Activity) { if (a === hostActivity) hide() }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }

    override fun show(activity: Activity) {
        val profileId = Diagnostics.profile.id
        if (profileId in REFUSED) {
            android.util.Log.w("Diag",
                "overlay refused for profile `$profileId`: a floating window would cover or steal " +
                    "input from the surface under measurement. Use the ADB broadcast trigger " +
                    "(dev.aarso.diagnostics.START) instead.")
            return
        }
        if (view != null) hide()
        val root = activity.window.decorView as? ViewGroup ?: return
        val v = OverlayView(activity)
        root.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v
        hostActivity = activity
        activity.application.registerActivityLifecycleCallbacks(lifecycleWatcher)
    }

    override fun hide() {
        view?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        view = null
        hostActivity?.application?.unregisterActivityLifecycleCallbacks(lifecycleWatcher)
        hostActivity = null
    }

    /** Pushed by the session so the overlay never has to reach into the collectors. */
    fun push(valueMs: Float, gaugeMb: Int) { view?.push(valueMs, gaugeMb) }
}

internal class OverlayView(activity: Activity) : View(activity) {

    // Violet + cyan are the only hue axis, and state is never carried by colour alone — every
    // readout is paired with a word, per the WCAG 1.4.1 rule applied across the constellation.
    private val violet = Color.parseColor("#8E7BFF")
    private val cyan = Color.parseColor("#08FED5")
    private val ink = Color.parseColor("#E8E9F0")

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EB0E0F16") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(36, 232, 233, 240)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = sp(13f); typeface = android.graphics.Typeface.MONOSPACE
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The profile's first series decides what this panel is about. */
    private val declaredSpec: SeriesSpec? = Diagnostics.profile.series.firstOrNull()

    /**
     * Re-read on every draw rather than captured once: the static [declaredSpec] is often
     * unresolved (budgetMs = 0.0) at overlay-construction time, because the REAL budget (e.g. the
     * actual vsync period) is only computed once a source resolves it inside the running Session --
     * which happens after the overlay is already showing. Falls back to the static declaration
     * before a session/source has resolved anything.
     */
    private fun currentSpec(): SeriesSpec? =
        declaredSpec?.let { d -> Diagnostics.resolvedSeriesSpec(d.id) ?: d }

    private var expanded = false
    private var bubbleX = dp(14f)
    private var bubbleY = dp(96f)
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    private val recent = FloatArray(120)
    private var head = 0
    private var gaugeMb = 0
    private var lastValue = 0f

    fun push(valueMs: Float, gaugeMb: Int) {
        recent[head] = valueMs
        head = (head + 1) % recent.size
        lastValue = valueMs
        this.gaugeMb = gaugeMb
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (expanded) drawPanel(canvas) else drawBubble(canvas)
    }

    /** Bubble bounds, shared between drawing and touch hit-testing so the two can never drift apart. */
    private fun bubbleRect(): RectF {
        val w = dp(112f); val h = dp(38f)
        return RectF(bubbleX, bubbleY, bubbleX + w, bubbleY + h)
    }

    /** Panel bounds, shared between drawing and touch hit-testing. */
    private fun panelRect(): RectF {
        val pad = dp(10f)
        return RectF(pad, dp(88f), width - pad, dp(88f) + dp(300f))
    }

    private fun activeRect(): RectF = if (expanded) panelRect() else bubbleRect()

    private fun drawBubble(c: Canvas) {
        val h = dp(38f)
        val r = bubbleRect()
        c.drawRoundRect(r, h / 2, h / 2, bg)
        c.drawRoundRect(r, h / 2, h / 2, stroke)
        bar.color = violet
        c.drawCircle(r.left + dp(14f), r.centerY(), dp(4f), bar)
        text.textSize = sp(13f)
        // The headline number is the series' own p-value, not a frame rate — an audio app showing
        // "fps" would be actively misleading.
        c.drawText(fmt(lastValue), r.left + dp(24f), r.centerY() + sp(2f), text)
        text.textSize = sp(8f)
        c.drawText("ms", r.left + dp(64f), r.centerY() - sp(2f), text)
        c.drawText("$gaugeMb MB", r.left + dp(64f), r.centerY() + sp(8f), text)
    }

    private fun drawPanel(c: Canvas) {
        val spec = currentSpec()
        val budgetMs = spec?.let { if (it.resolved) it.overrunAtMs else 0.0 } ?: 0.0
        val title = spec?.title ?: "Diagnostics"
        val overrunWord = spec?.overrunWord ?: "overrun"

        val pad = dp(10f)
        val r = panelRect()
        c.drawRoundRect(r, dp(14f), dp(14f), bg)
        c.drawRoundRect(r, dp(14f), dp(14f), stroke)

        text.textSize = sp(10f)
        c.drawText("$title · ${Diagnostics.profile.id}", r.left + pad, r.top + dp(20f), text)

        val sx = r.left + pad
        val sw = r.width() - pad * 2
        val sy = r.top + dp(34f)
        val sh = dp(64f)
        // Scale to the budget rather than a fixed cap, so the same panel reads correctly whether
        // the budget is 8.33 ms (vsync) or 2000 ms (an inference SLA).
        val cap = (if (budgetMs > 0) budgetMs * 6 else 50.0).toFloat()
        val bw = sw / recent.size
        for (i in recent.indices) {
            val v = minOf(recent[(head + i) % recent.size], cap)
            val bh = maxOf(dp(1f), (v / cap) * sh)
            bar.color = if (budgetMs > 0 && v > budgetMs) violet else cyan
            bar.alpha = if (budgetMs > 0 && v > budgetMs) 242 else 158
            c.drawRect(sx + i * bw, sy + sh - bh, sx + i * bw + bw - dp(0.5f), sy + sh, bar)
        }
        if (budgetMs > 0) {
            val by = sy + sh - (budgetMs.toFloat() / cap) * sh
            stroke.alpha = 70
            c.drawLine(sx, by, sx + sw, by, stroke)
            stroke.alpha = 36
            text.textSize = sp(8f)
            c.drawText("$overrunWord above ${fmt(budgetMs.toFloat())} ms", sx, by - dp(3f), text)
        } else {
            text.textSize = sp(8f)
            c.drawText("budget unresolved — shown unjudged", sx, sy + sh + dp(12f), text)
        }
    }

    private fun fmt(v: Float) = String.format("%.1f", v)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // This view is MATCH_PARENT over the whole decorView so it can be hit-tested first,
                // but only the drawn bubble/panel should ever consume a touch -- otherwise a tap on
                // the host app anywhere else on screen is silently swallowed by an invisible full-
                // screen catch-all. An untouched host below only sees the event at all if this
                // returns false here.
                if (!activeRect().contains(e.x, e.y)) return false
                downX = e.x - bubbleX; downY = e.y - bubbleY; dragged = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!expanded) {
                    if (abs(e.x - bubbleX - downX) > dp(6f) || abs(e.y - bubbleY - downY) > dp(6f))
                        dragged = true
                    bubbleX = (e.x - downX).coerceIn(dp(6f), width - dp(118f))
                    bubbleY = (e.y - downY).coerceIn(dp(6f), height - dp(44f))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) expanded = !expanded
                else bubbleX = if (bubbleX + dp(56f) < width / 2f) dp(6f) else width - dp(118f)
                invalidate(); return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
