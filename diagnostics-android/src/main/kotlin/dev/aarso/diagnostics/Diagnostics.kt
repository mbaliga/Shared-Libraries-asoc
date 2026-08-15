package dev.aarso.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.Window
import dev.aarso.diagnostics.collect.*
import dev.aarso.diagnostics.core.*
import dev.aarso.diagnostics.export.ReportWriter
import dev.aarso.diagnostics.export.Sharing
import dev.aarso.diagnostics.trigger.DiagnosticsReceiver
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole public surface.
 *
 * Instrumenting must cost one line and read as documentation, or it does not get done — so the API
 * stays small even though it now spans seven app types. Most methods are no-ops unless the active
 * profile has a source that consumes them: calling [frame] in a `ui` profile does nothing, because
 * there the platform supplies frames and an app-supplied one would double-count.
 *
 * SAFETY: this artifact is debugImplementation only, `diagnostics-noop` mirrors it for release, and
 * [install] additionally refuses to arm in a non-debuggable process. A collector alive in a shipped
 * build is a privacy problem, not just dead weight.
 */
object Diagnostics {

    private const val TAG = "Diag"

    @Volatile private var app: Application? = null
    @Volatile private var config: Config = Config()
    @Volatile private var session: Session? = null
    private var registry = SourceRegistry()
    private val armed = AtomicBoolean(false)

    val isCapturing: Boolean get() = session?.isRunning == true
    val profile: Profile get() = config.profile

    // ------------------------------------------------------------------ lifecycle

    @JvmStatic
    @JvmOverloads
    fun install(app: Application, config: Config = Config()) {
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "install() refused: process is not debuggable. Release builds must " +
                "resolve diagnostics-noop, not this artifact.")
            return
        }
        if (!armed.compareAndSet(false, true)) return

        this.app = app
        this.config = config
        registry = SourceRegistry()
        wireDefaultSources(app, config.profile)

        StartupTracker.attach(app)
        if (config.autoSession) startSession(null, Trigger.AUTO)
        if (config.registerAdbReceiver) DiagnosticsReceiver.register(app)
        Log.i(TAG, "installed with profile `${config.profile.id}`; sources: " +
            registry.all().joinToString(", ") { it.id })
    }

    /**
     * Sources the module can wire on its own. Push-fed sources for audio, pipelines and streams are
     * NOT auto-wired: they need parameters only the app knows (the negotiated buffer size, the
     * capture rate, which delegate was actually created), and inventing those values would produce
     * exactly the confident-but-wrong output this module exists to prevent. Register them with
     * [addSource] instead.
     */
    private fun wireDefaultSources(app: Application, profile: Profile) {
        val hz = { DeviceContext.read(app).refreshHz }
        when (profile.id) {
            "ui", "vision-pipeline" -> registry.register(
                ActivityFrameSource(hz) { session?.currentScreen() }.also { it.bind(app) })
            "ime" -> {
                registry.register(ImeFrameSource(hz))
                registry.register(WebViewRafSource())
                registry.register(InputLatencySource())
            }
            "wallpaper" -> registry.register(
                ManualFrameSource(hz, app.applicationInfo.nativeLibraryDir))
            "service" -> registry.register(RequestSource())
        }
    }

    /** Register a push-fed source the app constructs itself. */
    @JvmStatic
    fun addSource(source: MetricSource) {
        registry.register(source)
        // One read of the volatile field, not two: `isCapturing` then a separate `session!!` is a
        // check-then-act race against a concurrent endSession() on another thread (push sources are
        // documented as constructed on whatever thread resolves their parameters, e.g. the audio
        // thread), which can NPE on the `!!` after the field goes null in between.
        session?.takeIf { it.isRunning }?.let { source.start(it.sinkRef()) }
    }

    @JvmStatic
    fun <T : MetricSource> source(id: String): T? {
        @Suppress("UNCHECKED_CAST")
        return registry.get(id) as? T
    }

    @JvmStatic
    @JvmOverloads
    fun startSession(label: String? = null, trigger: Trigger = Trigger.API): SessionHandle? {
        val a = app ?: return null
        session?.stop()
        val s = Session(a, config, label, trigger, registry).also { session = it }
        s.start()
        return SessionHandle(s.id)
    }

    @JvmStatic
    fun endSession(): Report? = session?.let { s ->
        s.stop()
        s.buildReport().also { session = null }
    }

    // ------------------------------------------------------------------ instrumentation

    @JvmStatic fun mark(name: String) { session?.mark(name) }

    @JvmStatic
    inline fun <T> span(name: String, block: () -> T): T {
        val h = beginSpan(name)
        try { return block() } finally { h?.end() }
    }

    @JvmStatic fun beginSpan(name: String): SpanHandle? = session?.beginSpan(name)

    /**
     * Record a span whose duration is already known -- the ingestion point for a span measured
     * outside this process's clock, e.g. one parsed from a [dev.aarso.diagnostics.interop.ClackMetricLine].
     * Same span table [beginSpan] populates.
     */
    @JvmStatic fun recordSpan(name: String, durationMs: Double) { session?.recordSpan(name, durationMs) }

    @JvmStatic
    @JvmOverloads
    fun log(tag: String, message: String, level: Level = Level.INFO) { session?.log(tag, message, level) }

    @JvmStatic
    @JvmOverloads
    fun counter(name: String, delta: Long = 1) { session?.counter(name, delta) }

    /** Discrete environment facts the invariants assert over. */
    @JvmStatic
    @JvmOverloads
    fun fact(key: String, value: String, note: String? = null) { session?.fact(key, value, note) }

    @JvmStatic fun attribute(key: String, value: String) { session?.attribute(key, value) }
    @JvmStatic fun screenEntered(name: String) { session?.screenEntered(name) }
    @JvmStatic fun screenExited(name: String) { session?.screenExited(name) }

    /**
     * The runtime-resolved spec for a series, if a session is running and a source has resolved
     * one -- e.g. the actual vsync budget for the display's current refresh rate, not the profile's
     * static declaration. Falls back to null so a caller (the overlay) can fall back to the static
     * [Profile] declaration itself.
     */
    @JvmStatic
    fun resolvedSeriesSpec(seriesId: String): SeriesSpec? = session?.resolvedSpec(seriesId)

    // ------------------------------------------------------------------ per-profile intake
    // Thin pass-throughs to whichever source consumes them. Each is a no-op when the active profile
    // has no such source, so an app can carry the calls unconditionally.

    /** Wallpaper / custom draw loop: the duration of one drawn frame. */
    @JvmStatic
    @JvmOverloads
    fun frame(durationMs: Double, scene: String? = null) {
        source<ManualFrameSource>("manual-frames")?.frame(durationMs, scene)
    }

    /** Wallpaper: arms the no-draw-while-hidden invariant. Call from onVisibilityChanged. */
    @JvmStatic
    fun visibility(visible: Boolean) {
        source<ManualFrameSource>("manual-frames")?.visibility(visible)
    }

    /** IME: hand over the service's own window, since there is no Activity to find it through. */
    @JvmStatic
    fun attachImeWindow(window: Window?) {
        source<ImeFrameSource>("ime-frames")?.attach(window)
    }

    @JvmStatic fun keyDown() { source<InputLatencySource>("input-latency")?.keyDown() }

    @JvmStatic
    @JvmOverloads
    fun glyphVisible(key: String? = null) {
        source<InputLatencySource>("input-latency")?.glyphVisible(key)
    }

    /** Audio: duration of one callback, called at the end of it and nothing else. */
    @JvmStatic
    fun audioCallback(durationMs: Double) {
        source<AudioCallbackSource>("audio-callback")?.callback(durationMs)
    }

    @JvmStatic
    @JvmOverloads
    fun audioUnderrun(count: Long = 1) {
        source<AudioCallbackSource>("audio-callback")?.underrun(count)
    }

    /** Vision pipeline. */
    @JvmStatic fun cameraFrame() { source<PipelineSource>("pipeline")?.cameraFrame() }
    @JvmStatic
    @JvmOverloads
    fun cameraDropped(n: Long = 1) { source<PipelineSource>("pipeline")?.cameraDropped(n) }
    @JvmStatic fun inference(ms: Double) { source<PipelineSource>("pipeline")?.inference(ms) }
    @JvmStatic
    fun pipelineEndToEnd(cameraMs: Double, inferenceMs: Double, dspMs: Double, renderMs: Double) {
        source<PipelineSource>("pipeline")?.endToEnd(cameraMs, inferenceMs, dspMs, renderMs)
    }

    /** Sensor stream. Timestamps only — there is deliberately no way to hand a sample VALUE in. */
    @JvmStatic
    @JvmOverloads
    fun streamSample(channel: String? = null) {
        source<StreamIntegritySource>("stream-integrity")?.sample(channel)
    }

    @JvmStatic
    @JvmOverloads
    fun streamDropped(n: Long = 1) { source<StreamIntegritySource>("stream-integrity")?.dropped(n) }

    @JvmStatic
    fun streamReconnected() { source<StreamIntegritySource>("stream-integrity")?.reconnected() }

    /** Service. */
    @JvmStatic
    @JvmOverloads
    fun request(endpoint: String, durationMs: Double, error: Boolean = false) {
        source<RequestSource>("request")?.request(endpoint, durationMs, error)
    }

    @JvmStatic
    fun timeToFirstToken(model: String, ms: Double) {
        source<RequestSource>("request")?.timeToFirstToken(model, ms)
    }

    // ------------------------------------------------------------------ output

    @JvmStatic
    @JvmOverloads
    fun snapshot(label: String? = null): File? {
        val s = session ?: return null
        val a = app ?: return null
        return ReportWriter(a, config).write(s.buildReport(), labelOverride = label)
    }

    @JvmStatic
    fun export(report: Report): File? = app?.let { ReportWriter(it, config).write(report) }

    /** Manual share only — FileProvider + chooser. There is no upload path in this module. */
    @JvmStatic
    fun share(context: Context, file: File) = Sharing.share(context, file)

    @JvmStatic
    fun listReports(): List<File> = app?.let { ReportWriter(it, config).list() } ?: emptyList()

    @JvmStatic fun deleteReport(file: File): Boolean = file.delete()

    /**
     * Rebuilds a report from a journal left behind by a process that died.
     *
     * Call early in install() for long-running profiles. Without it the runs that failed are exactly
     * the runs that leave no evidence, and a history of clean reports can simply mean the bad ones
     * vanished.
     */
    @JvmStatic
    fun recoverAbandonedSessions(): List<File> {
        val a = app ?: return emptyList()
        // Exclude the session that is (or was, until a moment ago) running in THIS process: its
        // journal is a live, in-progress file, not evidence of an abandoned run. Without this, the
        // documented call pattern -- invoke right after install() -- recovers and deletes the
        // just-started autoSession's own header-only journal, so a later real crash in the same run
        // leaves a headerless journal (no H line survives) that the next launch's recovery pass
        // can't resolve to a sessionId and silently discards.
        return dev.aarso.diagnostics.export.JournalWriter.recoverAll(a, config, excludeSessionId = session?.id)
    }

    // ------------------------------------------------------------------ overlay

    @JvmStatic fun showOverlay(activity: Activity) { overlay()?.show(activity) }
    @JvmStatic fun hideOverlay() { overlay()?.hide() }

    private fun overlay(): OverlayHost? = try {
        Class.forName("dev.aarso.diagnostics.overlay.OverlayController")
            .getDeclaredField("INSTANCE").get(null) as OverlayHost
    } catch (e: Throwable) {
        Log.d(TAG, "overlay module not present"); null
    }

    internal fun currentSession(): Session? = session
    internal fun currentConfig(): Config = config
}

/** Contract the overlay module implements, so neither module compiles against the other. */
interface OverlayHost {
    fun show(activity: Activity)
    fun hide()
}

@JvmInline value class SessionHandle(val id: String)

interface SpanHandle { fun end() }
