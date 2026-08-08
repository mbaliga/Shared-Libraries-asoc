package dev.aarso.diagnostics.collect

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import dev.aarso.diagnostics.MetricSource
import dev.aarso.diagnostics.core.*

/**
 * Frame timing from the platform's own `FrameMetrics`, which supplies the phase breakdown — input,
 * animation, layout/measure, draw, sync, command issue, swap. That breakdown is the reason to use
 * this rather than a Choreographer delta: it turns "it stutters" into "your layout pass is the
 * problem" without a profiler.
 *
 * Two sources here rather than one, because the ATTACH POINT is what differs between an Activity
 * app and an IME, and getting that wrong is what silently produced empty reports for Clackpad.
 */

// ==================================================================== activity-hosted

class ActivityFrameSource(
    private val refreshHzProvider: () -> Double,
    private val screenProvider: () -> String?,
) : MetricSource, Application.ActivityLifecycleCallbacks {

    override val id = "activity-frames"

    private var sink: MetricSource.Sink? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val attached = mutableSetOf<Window>()
    private var app: Application? = null
    private var seenFirstDraw = false

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    fun bind(app: Application) { this.app = app }

    override fun specs(): List<SeriesSpec> {
        val base = Profiles.ui().spec("frames")!!
        val hz = refreshHzProvider()
        return listOf(if (hz > 0) base.resolve(Stats.vsyncBudget(hz), "${Stats.f(hz, 1)} Hz vsync") else base)
    }

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        if (!supported) return
        thread = HandlerThread("diag-frames").apply { start() }
        handler = Handler(thread!!.looper)
        app?.registerActivityLifecycleCallbacks(this)
    }

    override fun stop() {
        app?.unregisterActivityLifecycleCallbacks(this)
        thread?.quitSafely(); thread = null; handler = null
        attached.clear(); sink = null
    }

    internal fun listen(window: Window) {
        if (!supported || !attached.add(window)) return
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            val s = sink ?: return@addOnFrameMetricsAvailableListener
            val first = !seenFirstDraw && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
            if (first) seenFirstDraw = true
            s.observe("frames", Observation(
                tSec = s.now(),
                valueMs = m.ms(FrameMetrics.TOTAL_DURATION),
                bucket = screenProvider(),
                phases = mapOf(
                    "input" to m.ms(FrameMetrics.INPUT_HANDLING_DURATION),
                    "animation" to m.ms(FrameMetrics.ANIMATION_DURATION),
                    "layout" to m.ms(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    "draw" to m.ms(FrameMetrics.DRAW_DURATION),
                    "sync" to m.ms(FrameMetrics.SYNC_DURATION),
                    "commandIssue" to m.ms(FrameMetrics.COMMAND_ISSUE_DURATION),
                    "swap" to m.ms(FrameMetrics.SWAP_BUFFERS_DURATION),
                ),
                first = first,
            ))
        }, handler)
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) { listen(a.window) }
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) { attached.remove(a.window) }
}

// ==================================================================== IME

/**
 * The same platform API, a completely different attach point.
 *
 * An InputMethodService has no Activity, so ActivityLifecycleCallbacks never fires and an
 * Activity-based collector attaches to nothing and then reports a clean, empty, entirely misleading
 * run. The window does exist — `InputMethodService.getWindow()` returns a Dialog whose `getWindow()`
 * is a real Window — it simply has to be handed over explicitly.
 *
 * Wire it in the service:
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         Diagnostics.install(application, Config(profile = Profiles.ime()))
 *     }
 *     override fun onCreateInputView(): View {
 *         val v = super.onCreateInputView()
 *         Diagnostics.attachImeWindow(window?.window)   // sets ime.window_attached
 *         return v
 *     }
 */
class ImeFrameSource(
    private val refreshHzProvider: () -> Double,
) : MetricSource {

    override val id = "ime-frames"

    private var sink: MetricSource.Sink? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var attachedWindow: Window? = null

    override fun specs(): List<SeriesSpec> {
        val base = Profiles.ime().spec("frames")!!
        val hz = refreshHzProvider()
        return listOf(if (hz > 0) base.resolve(Stats.vsyncBudget(hz), "${Stats.f(hz, 1)} Hz vsync") else base)
    }

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        // Declared false up front so the invariant is EVALUABLE from the start. If the app never
        // calls attachImeWindow the report says so loudly, instead of showing an empty frame table
        // that reads like a quiet keyboard.
        sink.fact("ime.window_attached", "false")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        thread = HandlerThread("diag-ime-frames").apply { start() }
        handler = Handler(thread!!.looper)
    }

    override fun stop() {
        thread?.quitSafely(); thread = null; handler = null
        attachedWindow = null; sink = null
    }

    fun attach(window: Window?) {
        val s = sink ?: return
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (attachedWindow === window) return
        attachedWindow = window
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            val sk = sink ?: return@addOnFrameMetricsAvailableListener
            sk.observe("frames", Observation(
                tSec = sk.now(),
                valueMs = m.ms(FrameMetrics.TOTAL_DURATION),
                bucket = "ime",
                phases = mapOf(
                    "input" to m.ms(FrameMetrics.INPUT_HANDLING_DURATION),
                    "animation" to m.ms(FrameMetrics.ANIMATION_DURATION),
                    "layout" to m.ms(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    "draw" to m.ms(FrameMetrics.DRAW_DURATION),
                    "sync" to m.ms(FrameMetrics.SYNC_DURATION),
                    "commandIssue" to m.ms(FrameMetrics.COMMAND_ISSUE_DURATION),
                    "swap" to m.ms(FrameMetrics.SWAP_BUFFERS_DURATION),
                ),
            ))
        }, handler)
        s.fact("ime.window_attached", "true")
    }
}

// ==================================================================== WebView

/**
 * For Clackpad's WebView-backed keyboard.
 *
 * Android-level frame timing tells you WHEN the WebView composited; it cannot tell you WHY it was
 * slow, because everything expensive happened inside the page. This source receives rAF timings
 * pushed back over the JS bridge so the two halves can be compared in one report — a native frame
 * that is fine while the JS frame is not localises the problem immediately.
 */
class WebViewRafSource : MetricSource {

    override val id = "webview-raf"
    private var sink: MetricSource.Sink? = null

    override fun specs(): List<SeriesSpec> = listOfNotNull(Profiles.ime().spec("webview.raf"))

    override fun start(sink: MetricSource.Sink) { this.sink = sink }
    override fun stop() { sink = null }

    /** Called from the JavascriptInterface with a rAF delta measured inside the page. */
    fun rafFrame(durationMs: Double, view: String? = null) {
        val s = sink ?: return
        s.observe("webview.raf", Observation(s.now(), durationMs, bucket = view))
    }

    /** Resolve the budget once the page reports the display rate it is actually seeing. */
    fun resolveBudget(refreshHz: Double, onResolved: (SeriesSpec) -> Unit) {
        val base = Profiles.ime().spec("webview.raf")!!
        if (refreshHz > 0) onResolved(base.resolve(Stats.vsyncBudget(refreshHz),
            "${Stats.f(refreshHz, 1)} Hz (as seen by the page)"))
    }
}

private fun FrameMetrics.ms(metric: Int): Double = getMetric(metric) / 1_000_000.0
