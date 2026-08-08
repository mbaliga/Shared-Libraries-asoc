package dev.aarso.diagnostics.collect

import android.os.SystemClock
import dev.aarso.diagnostics.MetricSource
import dev.aarso.diagnostics.core.*

/**
 * The push-fed sources: the app tells them when something happened, because only the app knows.
 *
 * Every method on the hot path here is two field reads, an arithmetic op and one call into the
 * sink. No allocation, no logging, no synchronisation beyond what the sink does. If instrumentation
 * shows up in the numbers it is measuring, the module has failed at its only job — and for the
 * audio callback in particular, doing real work here would itself cause the underrun it is meant
 * to detect.
 */

// ==================================================================== wallpaper / custom draw loop

/**
 * For anything that draws outside a view hierarchy: a WallpaperService.Engine, a GLSurfaceView, a
 * custom Canvas loop, the Tauri desktop port's Android sibling.
 *
 * The platform gives you nothing here. A WallpaperService.Engine draws to a SurfaceHolder and has
 * no Window, so FrameMetrics never fires — which is why Animalcules, the app held up as the
 * animation benchmark for the whole constellation, was unmeasurable by the original design.
 *
 * Usage in the engine:
 *
 *     override fun onVisibilityChanged(visible: Boolean) {
 *         Diagnostics.visibility(visible)          // arms the no-draw-while-hidden invariant
 *     }
 *
 *     private fun drawFrame() {
 *         val t0 = System.nanoTime()
 *         val canvas = holder.lockCanvas()
 *         try { render(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
 *         Diagnostics.frame((System.nanoTime() - t0) / 1_000_000.0, scene = currentScene)
 *     }
 */
class ManualFrameSource(
    private val refreshHzProvider: () -> Double,
    /** ApplicationInfo.nativeLibraryDir. Null means the host app declined to supply it. */
    private val nativeLibDir: String? = null,
) : MetricSource {

    override val id = "manual-frames"

    @Volatile private var sink: MetricSource.Sink? = null

    @Volatile private var visible: Boolean = true
    @Volatile private var gcAtDrawStart: Int = 0

    override fun specs(): List<SeriesSpec> {
        val hz = refreshHzProvider()
        val base = Profiles.wallpaper().spec("frames")!!
        // Budget is the display's ACTUAL refresh rate. A wallpaper on a 120 Hz panel dropped to
        // 60 Hz by a battery saver has a different budget, and comparing across the two silently
        // invalidates the conclusion.
        return if (hz > 0) listOf(base.resolve(Stats.vsyncBudget(hz), "${Stats.f(hz, 1)} Hz vsync"))
        else listOf(base)
    }

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        sink.fact("native.libs", detectNativeLibs())
        sink.count("frames.while_hidden", 0)   // register at zero so the invariant is evaluable
        sink.count("gc.during_draw", 0)
    }

    override fun stop() { sink = null }

    /** Called by the engine's draw loop. Must stay trivial. */
    fun frame(durationMs: Double, scene: String? = null) {
        val s = sink ?: return
        s.observe("frames", Observation(s.now(), durationMs, bucket = scene))
        // The invariant that actually matters for a wallpaper: a draw loop that never stopped is
        // invisible in every timing metric and shows up only as battery the user blames elsewhere.
        if (!visible) s.count("frames.while_hidden")
    }

    fun visibility(isVisible: Boolean) {
        visible = isVisible
        sink?.fact("wallpaper.visible", isVisible.toString())
    }

    /** Optional: bracket the draw call to detect allocation-driven GC inside the loop. */
    fun beginDraw() { gcAtDrawStart = gcCount() }
    fun endDraw() {
        if (gcCount() > gcAtDrawStart) sink?.count("gc.during_draw")
    }

    private fun gcCount(): Int =
        android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toIntOrNull() ?: 0

    /**
     * The deliberate no-NDK decision is what stops a repeat of the original app dying on
     * 64-bit-only devices, so it is worth asserting rather than remembering — including when a
     * transitive dependency quietly reintroduces a .so.
     *
     * Returns "unknown" when the directory was not supplied, and the invariant then reports as
     * NOT EVALUABLE rather than passing. Guessing "none" here would be the exact failure this
     * module exists to prevent.
     */
    private fun detectNativeLibs(): String {
        val dir = nativeLibDir ?: return "unknown"
        return runCatching {
            val f = java.io.File(dir)
            val sos = f.listFiles { file -> file.name.endsWith(".so") }
            when {
                !f.exists() -> "none"
                sos == null || sos.isEmpty() -> "none"
                else -> sos.joinToString(",") { it.name }
            }
        }.getOrDefault("unknown")
    }
}

// ==================================================================== audio

/**
 * For Bocal and the haptics/audio workbench.
 *
 * The budget is the wall-clock duration of the buffer being filled: exceed it and the ring runs dry
 * and the user hears a click. That makes it the same statistical object as a dropped frame, which
 * is why it reuses the entire aggregation path with only the vocabulary changed.
 *
 * Facts here carry more weight than timings. Requesting the low-latency path is not the same as
 * getting it, and a denied request silently adds tens of milliseconds — the difference between an
 * instrument that feels playable and one that does not.
 */
class AudioCallbackSource(
    private val sampleRateHz: Int,
    private val bufferFrames: Int,
    private val nativeSampleRateHz: Int,
    private val nativeBufferFrames: Int,
    private val lowLatencyGranted: Boolean,
    private val api: String = "aaudio",
) : MetricSource {

    override val id = "audio-callback"
    @Volatile private var sink: MetricSource.Sink? = null

    override fun specs(): List<SeriesSpec> {
        val p = Profiles.audio()
        val budget = Stats.audioBudget(bufferFrames, sampleRateHz)
        val label = "$bufferFrames frames @ ${sampleRateHz} Hz"
        return listOfNotNull(
            p.spec("audio.callback")?.let {
                if (budget > 0) it.resolve(budget, label) else it
            },
            p.spec("pitch.detect")?.let {
                if (budget > 0) it.resolve(budget, label) else it
            },
        )
    }

    override fun facts(): List<Fact> = listOf(
        Fact("audio.path", if (lowLatencyGranted) "low-latency" else "normal",
            if (lowLatencyGranted) null else "requested but not granted"),
        Fact("audio.api", api),
        Fact("audio.sample_rate", sampleRateHz.toString()),
        Fact("audio.buffer_frames", bufferFrames.toString()),
        Fact("audio.native_sample_rate", nativeSampleRateHz.toString()),
        Fact("audio.native_buffer_frames", nativeBufferFrames.toString()),
    )

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        sink.count("audio.underrun", 0)
        sink.count("audio.callbacks", 0)
    }

    override fun stop() { sink = null }

    /**
     * Call at the END of the audio callback with its measured duration.
     * Do NOT call anything else from the audio thread.
     */
    fun callback(durationMs: Double) {
        val s = sink ?: return
        s.observe("audio.callback", Observation(s.now(), durationMs))
        s.count("audio.callbacks")
    }

    /** Reported by the audio engine when the stream signals an underrun/xrun. */
    fun underrun(count: Long = 1) { sink?.count("audio.underrun", count) }

    fun pitchDetect(durationMs: Double) {
        val s = sink ?: return
        s.observe("pitch.detect", Observation(s.now(), durationMs))
    }
}

// ==================================================================== camera / inference pipeline

/**
 * For Crocodyl.
 *
 * The key point is that UI frame rate is actively misleading for this app type: it can render at
 * 120 fps while dropping two of every three camera frames at the ImageAnalysis stage. The measured
 * object is the pipeline.
 *
 * And the single highest-value line in the whole rework is the delegate fact. MediaPipe falls back
 * to CPU silently when the GPU delegate cannot be created; it costs several times the budget, and
 * nothing in the UI timings shows it because the UI thread was never the bottleneck.
 */
class PipelineSource(
    private val captureFps: Double,
    private val delegate: String,
    private val model: String,
    private val resolution: String,
) : MetricSource {

    override val id = "pipeline"
    @Volatile private var sink: MetricSource.Sink? = null
    private var lastArrivalNs: Long = 0

    override fun specs(): List<SeriesSpec> {
        val p = Profiles.visionPipeline()
        val period = Stats.rateBudget(captureFps)
        if (period <= 0) return p.series
        return listOf(
            p.spec("camera.arrival")!!.resolve(period, "${Stats.f(captureFps, 0)} fps capture"),
            p.spec("pose.inference")!!.resolve(period, "one capture period"),
            p.spec("pipeline.e2e")!!.resolve(period * 2, "two capture periods"),
        )
    }

    override fun facts(): List<Fact> = listOf(
        Fact("inference.delegate", delegate),
        Fact("pose.model", model),
        Fact("camera.resolution", resolution),
        Fact("camera.target_fps", Stats.f(captureFps, 0)),
    )

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        sink.count("camera.delivered", 0)
        sink.count("camera.dropped", 0)
    }

    override fun stop() { sink = null; lastArrivalNs = 0 }

    /** Call from the ImageAnalysis analyzer as each frame arrives. */
    fun cameraFrame() {
        val s = sink ?: return
        val now = System.nanoTime()
        if (lastArrivalNs != 0L) {
            s.observe("camera.arrival",
                Observation(s.now(), (now - lastArrivalNs) / 1_000_000.0, bucket = resolution))
        }
        lastArrivalNs = now
        s.count("camera.delivered")
    }

    /** Call when ImageAnalysis backpressure discards a frame. */
    fun cameraDropped(n: Long = 1) { sink?.count("camera.dropped", n) }

    fun inference(durationMs: Double) {
        val s = sink ?: return
        s.observe("pose.inference", Observation(s.now(), durationMs, bucket = delegate))
    }

    /** The whole hop, with its stage breakdown — the same phase machinery UI frames use. */
    fun endToEnd(cameraMs: Double, inferenceMs: Double, dspMs: Double, renderMs: Double) {
        val s = sink ?: return
        s.observe("pipeline.e2e", Observation(
            s.now(), cameraMs + inferenceMs + dspMs + renderMs,
            phases = mapOf("camera" to cameraMs, "inference" to inferenceMs,
                "dsp" to dspMs, "render" to renderMs)))
    }
}

// ==================================================================== BLE / sensor stream

/**
 * For the EEG work and any BLE sensor.
 *
 * This source measures SIGNAL INTEGRITY, not performance. A 2 % dropped-sample rate does not make
 * anything feel slow; it shifts the band-power estimates that everything downstream is built on, so
 * a within-subject baseline computed over a lossy stream is a baseline for the packet loss rather
 * than for the person.
 *
 * It deliberately exposes NO method that accepts a sample value. The only things it can record are
 * counts and timings, which makes it structurally impossible for signal data to reach a report —
 * and the stream profile's payload rules assert that independently, on the rendered text.
 */
class StreamIntegritySource(
    private val nominalRateHz: Double,
    private val deviceName: String,
    private val channels: Int,
) : MetricSource {

    override val id = "stream-integrity"
    @Volatile private var sink: MetricSource.Sink? = null
    private var lastSampleNs: Long = 0

    override fun specs(): List<SeriesSpec> {
        val p = Profiles.stream()
        val period = Stats.rateBudget(nominalRateHz)
        return listOfNotNull(
            p.spec("stream.interval")?.let {
                if (period > 0) it.resolve(period, "${Stats.f(nominalRateHz, 0)} Hz nominal") else it
            },
            p.spec("dsp.stage"),
        )
    }

    override fun facts(): List<Fact> = listOf(
        Fact("stream.device", deviceName),
        Fact("stream.sample_rate_hz", Stats.f(nominalRateHz, 0)),
        Fact("stream.channels", channels.toString()),
    )

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        sink.count("stream.samples", 0)
        sink.count("stream.dropped", 0)
        sink.count("stream.reconnects", 0)
        sink.count("stream.crc_errors", 0)
    }

    override fun stop() { sink = null; lastSampleNs = 0 }

    /** Timestamp only — never the value. */
    fun sample(channel: String? = null) {
        val s = sink ?: return
        val now = System.nanoTime()
        if (lastSampleNs != 0L) s.observe("stream.interval",
            Observation(s.now(), (now - lastSampleNs) / 1_000_000.0, bucket = channel))
        lastSampleNs = now
        s.count("stream.samples")
    }

    fun dropped(n: Long = 1) { sink?.count("stream.dropped", n) }
    fun reconnected() { sink?.count("stream.reconnects"); lastSampleNs = 0 }
    fun crcError(n: Long = 1) { sink?.count("stream.crc_errors", n) }
    fun driftPpm(ppm: Double) { sink?.fact("stream.drift_ppm", Stats.f(ppm, 1)) }

    fun dspStage(name: String, durationMs: Double) {
        val s = sink ?: return
        s.observe("dsp.stage", Observation(s.now(), durationMs, bucket = name))
    }
}

// ==================================================================== headless service

/**
 * For ASOM.
 *
 * No UI at all, so nothing in the original design applied. The characteristic failure is also not
 * slowness but death: a model-serving process is the most likely thing on the device to meet the
 * low-memory killer, and a killed process writes no report — which is why this profile pairs with
 * the journal, and why a clean-looking history can simply mean the bad runs vanished.
 */
class RequestSource : MetricSource {

    override val id = "request"
    @Volatile private var sink: MetricSource.Sink? = null
    private val queueLock = Any()
    private var queueDepth = 0
    private var maxQueueDepth = 0

    override fun specs(): List<SeriesSpec> = Profiles.service().series   // budgets are SLAs, already set

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        sink.count("requests", 0)
        sink.count("errors", 0)
        sink.count("oom", 0)
        sink.count("queue.max_depth", 0)
    }

    override fun stop() { sink = null }

    fun request(endpoint: String, durationMs: Double, error: Boolean = false) {
        val s = sink ?: return
        s.observe("request.latency", Observation(s.now(), durationMs, bucket = endpoint))
        s.count("requests")
        if (error) s.count("errors")
    }

    fun timeToFirstToken(model: String, ms: Double) {
        val s = sink ?: return
        s.observe("ttft", Observation(s.now(), ms, bucket = model))
    }

    fun modelLoad(model: String, ms: Double) {
        val s = sink ?: return
        s.observe("model.load", Observation(s.now(), ms, bucket = model))
    }

    // ASOM serves requests concurrently, so enqueue()/dequeue() can race across request-handling
    // threads; a plain queueDepth++/-- is a non-atomic read-modify-write that silently drops
    // increments under contention and corrupts the exact "bounded queue depth" invariant this
    // metric feeds.
    fun enqueue() {
        var depth = 0
        var newMax = false
        synchronized(queueLock) {
            queueDepth++
            depth = queueDepth
            if (depth > maxQueueDepth) { maxQueueDepth = depth; newMax = true }
        }
        // A high-water mark is a gauge, not a tally — incrementing here would report how many
        // times the maximum moved rather than what it reached.
        if (newMax) sink?.set("queue.max_depth", depth.toLong())
    }

    fun dequeue() { synchronized(queueLock) { if (queueDepth > 0) queueDepth-- } }

    fun modelFacts(modelId: String, quant: String, runtime: String) {
        sink?.fact("model.id", modelId)
        sink?.fact("model.quant", quant)
        sink?.fact("runtime", runtime)
    }
}

// ==================================================================== IME input latency

/**
 * For Clackpad.
 *
 * Frame rate is not a keyboard's felt quality; key-down to glyph-visible is. This source pairs the
 * two halves of that measurement, which arrive on different callbacks, and it tolerates the
 * fragmentary reality of an IME process being torn down mid-word.
 */
class InputLatencySource : MetricSource {

    override val id = "input-latency"
    @Volatile private var sink: MetricSource.Sink? = null
    private var keyDownNs: Long = 0

    override fun specs(): List<SeriesSpec> =
        listOfNotNull(Profiles.ime().spec("input.latency"))   // fixed house budget, already resolved

    override fun start(sink: MetricSource.Sink) {
        this.sink = sink
        sink.count("ime.keys", 0)
        sink.count("net.requests", 0)   // registered so the egress invariant is evaluable
    }

    override fun stop() { sink = null; keyDownNs = 0 }

    fun keyDown() { keyDownNs = System.nanoTime() }

    /** Call once the glyph has actually been committed and drawn. */
    fun glyphVisible(key: String? = null) {
        val s = sink ?: return
        if (keyDownNs == 0L) return          // unpaired; discard rather than invent a latency
        s.observe("input.latency",
            Observation(s.now(), (System.nanoTime() - keyDownNs) / 1_000_000.0, bucket = key))
        s.count("ime.keys")
        keyDownNs = 0
    }

    /** Called by the BYO-AI path. The invariant asserts this stays at zero while BYO is disabled. */
    fun networkRequest() { sink?.count("net.requests") }

    fun byoEnabled(enabled: Boolean) { sink?.fact("byo_ai_enabled", enabled.toString()) }
}
