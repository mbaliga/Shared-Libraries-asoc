package dev.aarso.diagnostics.core

/**
 * App-type profiles.
 *
 * A profile is the answer to "what does healthy look like for THIS kind of app". It bundles the
 * series to collect, where each budget comes from, what an overrun is called, the thresholds, and
 * the invariants that must hold. The aggregation and reporting machinery underneath is identical
 * for all of them.
 *
 * The honest framing: these thresholds are a starting position drawn from platform documentation
 * and ordinary practice, not measurements of your apps. The first real run on the RedMagic should
 * be treated as calibration, and the numbers moved to fit what your apps actually do. What is NOT
 * negotiable is the invariants — those are structural claims, and a violated invariant is a bug
 * regardless of what the timings say.
 */

data class StartupTargets(
    val coldMsGood: Double = 800.0,
    val coldMsWarn: Double = 1500.0,
    val warmMsGood: Double = 400.0,
    val warmMsWarn: Double = 800.0,
    val hotMsGood: Double = 200.0,
    val hotMsWarn: Double = 400.0,
) {
    companion object {
        /** Google's published bad-behaviour thresholds, so a report stays comparable to the console. */
        fun playVitals() = StartupTargets(5000.0, 5000.0, 2000.0, 2000.0, 1500.0, 1500.0)
        /** Background services and daemons have no user-visible start-up in this sense. */
        fun none() = StartupTargets(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
            Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    }
}

data class Profile(
    val id: String,
    val title: String,
    /** Source ids the Android layer is expected to wire for this profile. Documentation with teeth: a declared-but-absent source trips a seriesPresent invariant. */
    val sources: List<String>,
    val series: List<SeriesSpec>,
    val invariants: List<Invariant> = emptyList(),
    val payloadRules: List<PayloadRule> = emptyList(),
    val startup: StartupTargets = StartupTargets(),
    val minSessionSec: Double = 20.0,
    /** Sessions below this are still written, but confidence is downgraded and drain is not estimated. */
    val minSessionSecForDrain: Double = 300.0,
    val notes: List<String> = emptyList(),
) {
    fun spec(id: String): SeriesSpec? = series.firstOrNull { it.id == id }
}

object Profiles {

    fun byId(id: String): Profile? = all().firstOrNull { it.id == id }

    fun all(): List<Profile> = listOf(ui(), ime(), wallpaper(), audio(), visionPipeline(), stream(), service())

    // ---------------------------------------------------------------- shared pieces

    private val UI_PHASES = listOf("input", "animation", "layout", "draw", "sync", "commandIssue", "swap")

    /** The platform frame series. Budget is unresolved until the display's actual refresh rate is read. */
    private fun frameSpec(
        title: String = "Frames",
        severeMs: Double? = 700.0,
        bucketLabel: String? = "Screen",
        worst: Int = 10,
    ) = SeriesSpec(
        id = "frames",
        title = title,
        overrunWord = "jank",
        severeMs = severeMs,
        severeWord = "frozen",
        bucketLabel = bucketLabel,
        phaseOrder = UI_PHASES,
        worstCount = worst,
    )

    // ================================================================ ui

    /**
     * Ordinary Activity-hosted, view-hierarchy apps. Fonebrew, Nooz, Crocodyl's UI shell,
     * Sphere Launcher's settings. This is the only case the first cut of the module covered.
     */
    fun ui() = Profile(
        id = "ui",
        title = "UI (Activity-hosted)",
        sources = listOf("activity-frames", "memory", "thermal", "startup"),
        series = listOf(frameSpec()),
        invariants = listOf(
            Invariants.seriesPresent(
                "ui.frames-collected", "frames", minCount = 600,
                statement = "Frame metrics were actually collected.",
                rationale = "An empty frame series usually means the collector never attached to a " +
                    "window, not that the app rendered nothing. Silence must not read as health.",
            ),
        ),
        notes = listOf(
            "Covers the UI thread only. If the app's real work is on a background pipeline, use a " +
                "profile that measures that pipeline — a smooth UI over a starved worker looks perfect here.",
        ),
    )

    // ================================================================ ime

    /**
     * Clackpad. An IME is NOT Activity-hosted — ActivityLifecycleCallbacks never fires — so the
     * frame source must attach to InputMethodService.getWindow().getWindow() instead. That single
     * difference silently produced an empty report in the first design.
     *
     * The metric that actually matters for a keyboard is not frame rate: it is key-down to
     * glyph-visible latency. And because Clackpad's UI is a WebView, Android-level frame timing
     * says WHEN the WebView composited, never WHY it was slow — hence the separate JS-side series.
     */
    fun ime() = Profile(
        id = "ime",
        title = "IME (input method)",
        sources = listOf("ime-frames", "webview-raf", "input-latency", "memory", "thermal"),
        series = listOf(
            frameSpec(title = "IME window frames", bucketLabel = "Layer"),
            SeriesSpec(
                id = "input.latency",
                title = "Key-down → glyph visible",
                budgetMs = 32.0,
                budgetLabel = "32 ms (house target for perceived immediacy)",
                overrunWord = "slow key",
                severeMs = 150.0,
                severeWord = "stalled key",
                targets = SeriesTargets(
                    overrunRatePctGood = 1.0, overrunRatePctWarn = 5.0,
                    p99BudgetMultipleGood = 2.0, p99BudgetMultipleWarn = 3.0,
                ),
            ),
            SeriesSpec(
                id = "webview.raf",
                title = "WebView rAF frame",
                overrunWord = "jank",
                bucketLabel = "View",
            ),
        ),
        invariants = listOf(
            Invariants.factEquals(
                "ime.window-attached", "ime.window_attached", "true",
                statement = "The frame collector attached to the IME window.",
                rationale = "An IME has no Activity. If the collector is wired through " +
                    "ActivityLifecycleCallbacks it attaches to nothing and reports a clean empty run.",
            ),
            Invariants.whenFact(
                "ime.no-egress-when-byo-off", "byo_ai_enabled", "false",
                then = Invariants.counterIsZero("_", "net.requests", "", ""),
                statement = "No network requests occur while the bring-your-own AI endpoint is disabled.",
                rationale = "The privacy copy states that no text is transmitted. The BYO path is the " +
                    "one carve-out, so with it off the count must be exactly zero — this is the " +
                    "assertion that keeps the claim and the code in agreement.",
            ),
            Invariants.seriesPresent(
                "ime.latency-collected", "input.latency", minCount = 50,
                statement = "Key-to-glyph latency was measured.",
                rationale = "Frame rate is not the keyboard's felt quality; latency is. A report " +
                    "without it is not evidence about typing.",
            ),
        ),
        startup = StartupTargets(coldMsGood = 300.0, coldMsWarn = 600.0,
            warmMsGood = 150.0, warmMsWarn = 300.0, hotMsGood = 80.0, hotMsWarn = 160.0),
        minSessionSec = 15.0,
        notes = listOf(
            "The floating overlay is unusable here — it covers the surface being measured and steals " +
                "input. Use the ADB broadcast trigger for IME work.",
            "IME processes are short-lived and recreated often, so sessions are fragmentary by nature; " +
                "compare distributions across several runs rather than trusting one.",
        ),
    )

    // ================================================================ wallpaper

    /**
     * Animalcules. A WallpaperService.Engine draws to a SurfaceHolder — there is no Window and no
     * view hierarchy, so FrameMetrics returns NOTHING. The app named as the animation bar for the
     * whole constellation was the one the original design could not measure at all.
     *
     * Frames come from the engine's own draw loop instead, pushed in via Diagnostics.frame().
     */
    fun wallpaper() = Profile(
        id = "wallpaper",
        title = "Live wallpaper",
        sources = listOf("manual-frames", "memory", "thermal", "visibility"),
        series = listOf(
            frameSpec(title = "Draw-loop frames", severeMs = 500.0, bucketLabel = "Scene"),
        ),
        invariants = listOf(
            Invariants.counterIsZero(
                "wp.no-draw-while-hidden", "frames.while_hidden",
                statement = "The engine draws nothing while the wallpaper is not visible.",
                rationale = "The classic wallpaper bug: a draw loop that keeps running behind the " +
                    "app switcher or a full-screen app. It is invisible in every UI metric and shows " +
                    "up only as battery the user blames on something else.",
            ),
            Invariants.counterIsZero(
                "wp.no-alloc-in-draw", "gc.during_draw",
                statement = "No garbage collection was triggered inside the draw loop.",
                rationale = "Allocation per frame is the usual cause of periodic hitching in a " +
                    "Canvas engine, and it is cheap to assert against.",
                severity = Grade.WARN,
            ),
            Invariants.factEquals(
                "wp.no-native-libs", "native.libs", "none",
                statement = "The APK ships no native libraries.",
                rationale = "The deliberate no-NDK/no-ABI-splits decision is what stops a repeat of " +
                    "the original app dying on 64-bit-only devices. Worth asserting so it cannot " +
                    "regress by way of a transitive dependency.",
                severity = Grade.WARN,
            ),
            Invariants.custom(
                "wp.long-enough-for-drain",
                statement = "The session was long enough to estimate battery drain.",
                rationale = "A wallpaper runs for hours; its real cost is drain, not p99. A two-minute " +
                    "capture cannot speak to that.",
                severity = Grade.WARN,
            ) { e ->
                Invariant.Outcome(e.session.durationSec >= 300.0,
                    "${Stats.f(e.session.durationSec, 0)} s (min 300 s)")
            },
            Invariants.seriesPresent(
                "wp.frames-collected", "frames", minCount = 1200,
                statement = "Draw-loop frames were reported by the engine.",
                rationale = "A wallpaper has no Window, so nothing arrives unless the engine calls " +
                    "Diagnostics.frame() itself. An empty series here means uninstrumented, not idle.",
            ),
        ),
        startup = StartupTargets(coldMsGood = 600.0, coldMsWarn = 1200.0,
            warmMsGood = 300.0, warmMsWarn = 600.0, hotMsGood = 150.0, hotMsWarn = 300.0),
        minSessionSec = 60.0,
        notes = listOf(
            "Run long. A wallpaper's failure modes are drain, thermal creep and slow memory growth — " +
                "all of which need tens of minutes, not two.",
            "The same HTML/Canvas spec drives the Android engine and the desktop port, so the same " +
                "series ids can be emitted from both and compared directly.",
        ),
    )

    // ================================================================ audio

    /**
     * Bocal, and the haptics/audio workbench. Audio has no frames at all: the unit is the callback,
     * and the budget is the wall-clock duration of the buffer it is filling. Overrun the budget and
     * you get an audible glitch — the audio equivalent of a dropped frame, and statistically the
     * same object, which is why it reuses the entire aggregation path unchanged.
     */
    fun audio() = Profile(
        id = "audio",
        title = "Audio / instrument companion",
        sources = listOf("audio-callback", "memory", "thermal"),
        series = listOf(
            SeriesSpec(
                id = "audio.callback",
                title = "Audio callback",
                overrunWord = "underrun",
                severeMs = null,   // there is no "frozen callback"; an overrun is already the failure
                targets = SeriesTargets(
                    overrunRatePctGood = 0.0, overrunRatePctWarn = 0.1,
                    p99BudgetMultipleGood = 0.5, p99BudgetMultipleWarn = 0.8,
                ),
            ),
            SeriesSpec(
                id = "pitch.detect",
                title = "Pitch detection",
                overrunWord = "over-budget",
            ),
        ),
        invariants = listOf(
            Invariants.factEquals(
                "audio.low-latency-granted", "audio.path", "low-latency",
                statement = "The low-latency audio path was granted.",
                rationale = "Requesting it is not getting it. A denied request silently costs tens of " +
                    "milliseconds of round-trip latency, which is the difference between an " +
                    "instrument that feels playable and one that does not.",
            ),
            Invariants.counterIsZero(
                "audio.no-underruns", "audio.underrun",
                statement = "No buffer underruns occurred.",
                rationale = "Every underrun is an audible click. There is no acceptable non-zero rate.",
            ),
            Invariants.custom(
                "audio.native-rate",
                statement = "The stream runs at the device's native sample rate and buffer size.",
                rationale = "Any mismatch inserts a resampler and an extra buffer, adding latency and " +
                    "CPU for nothing. The device tells you its preferred values; using anything else " +
                    "should be a deliberate choice, not an accident.",
            ) { e ->
                val sr = e.facts["audio.sample_rate"]; val nsr = e.facts["audio.native_sample_rate"]
                val bf = e.facts["audio.buffer_frames"]; val nbf = e.facts["audio.native_buffer_frames"]
                if (sr == null || nsr == null || bf == null || nbf == null)
                    Invariant.Outcome(null, "needs audio.sample_rate/native_sample_rate/buffer_frames/native_buffer_frames")
                else Invariant.Outcome(sr == nsr && bf == nbf,
                    "rate $sr vs native $nsr · buffer $bf vs native $nbf")
            },
            Invariants.seriesPresent(
                "audio.callbacks-collected", "audio.callback", minCount = 500,
                statement = "Audio callbacks were timed.",
                rationale = "Without this series the report says nothing about the only thing that " +
                    "matters for an instrument app.",
            ),
        ),
        startup = StartupTargets(),
        minSessionSec = 30.0,
        notes = listOf(
            "Round-trip latency (mic in → speaker out) is not measured here; it needs a loopback " +
                "measurement with external hardware. Callback timing is the on-device proxy.",
            "Haptics share this profile: a haptic callback missing its deadline is the same object " +
                "as an audio one, and audio/haptic sync shows up as drift between the two series.",
        ),
    )

    // ================================================================ vision pipeline

    /**
     * Crocodyl. The pipeline is camera → pose inference → DSP → render, and UI frame rate is
     * actively misleading here: the app can render at 120 fps while dropping two of every three
     * camera frames at the ImageAnalysis stage. The measured object is the pipeline, not the screen.
     *
     * The single most valuable line in this profile is the delegate invariant. A silent fallback
     * from GPU to CPU costs several times the inference budget and is invisible to every timing
     * metric that watches the UI thread.
     */
    fun visionPipeline() = Profile(
        id = "vision-pipeline",
        title = "Camera / inference pipeline",
        sources = listOf("pipeline", "activity-frames", "memory", "thermal"),
        series = listOf(
            SeriesSpec(
                id = "camera.arrival",
                title = "Camera frame inter-arrival",
                overrunWord = "late frame",
                // INTERVAL, not DURATION. Capture cadence jitters around the nominal period, so
                // judging it against the period itself would report ~50 % late on a healthy stream.
                // A gap of 1.5x nominal means a frame's worth of time passed with nothing arriving.
                kind = SeriesKind.INTERVAL,
                overrunMultiple = 1.5,
                bucketLabel = "Resolution",
                targets = SeriesTargets(overrunRatePctGood = 2.0, overrunRatePctWarn = 8.0),
            ),
            SeriesSpec(
                id = "pose.inference",
                title = "Pose inference",
                overrunWord = "over-budget",
                bucketLabel = "Delegate",
                targets = SeriesTargets(overrunRatePctGood = 2.0, overrunRatePctWarn = 10.0),
            ),
            SeriesSpec(
                id = "pipeline.e2e",
                title = "Capture → analysed",
                overrunWord = "over-budget",
                severeMs = 1000.0,
                severeWord = "stalled",
                phaseOrder = listOf("camera", "inference", "dsp", "render"),
            ),
            frameSpec(title = "UI frames"),
        ),
        invariants = listOf(
            Invariants.factIsOneOf(
                "vp.accelerated-delegate", "inference.delegate", setOf("gpu", "nnapi"),
                statement = "Pose inference ran on an accelerated delegate.",
                rationale = "MediaPipe falls back to CPU silently when the GPU delegate cannot be " +
                    "created. It costs several times the budget and NOTHING in the UI timings shows " +
                    "it, because the UI thread was never the bottleneck.",
            ),
            Invariants.ratioUnder(
                "vp.camera-drop-rate", "camera.dropped", "camera.delivered", maxPct = 5.0,
                statement = "Fewer than 5 % of camera frames were dropped before analysis.",
                rationale = "ImageAnalysis applies backpressure by discarding frames. High drop rates " +
                    "mean the form analysis is running on a fraction of the movement it appears to see.",
            ),
            Invariants.custom(
                "vp.keeps-up",
                statement = "Analysis keeps up with capture.",
                rationale = "If p95 inference exceeds the capture period the pipeline can never drain, " +
                    "and every additional second of recording increases the backlog.",
            ) { e ->
                val inf = e.series["pose.inference"]
                val cam = e.series["camera.arrival"]
                if (inf == null || cam == null || !cam.spec.resolved)
                    Invariant.Outcome(null, "needs pose.inference and a resolved camera.arrival budget")
                else Invariant.Outcome(inf.whole.p95 <= cam.spec.budgetMs,
                    "inference p95 ${Stats.f(inf.whole.p95)} ms vs capture period ${Stats.f(cam.spec.budgetMs)} ms")
            },
            Invariants.seriesPresent(
                "vp.inference-collected", "pose.inference", minCount = 100,
                statement = "Inference timings were collected.",
                rationale = "Without them the report describes a camera preview, not a form analyser.",
            ),
        ),
        minSessionSec = 30.0,
        notes = listOf(
            "Thermal matters more here than anywhere else in the portfolio — sustained camera plus " +
                "inference is the hottest thing a phone does, and a long recording session will " +
                "throttle. Read the pre/post-throttle split, not the whole-session aggregate.",
        ),
    )

    // ================================================================ stream

    /**
     * The EEG work (Baseline, MW75, the MindWave project) and any BLE sensor stream.
     *
     * The distinction that matters: this profile measures SIGNAL INTEGRITY, not performance. A 2 %
     * dropped-sample rate does not make anything feel slow — it quietly poisons the band-power
     * estimates that everything downstream depends on. Integrity failures invalidate the science
     * rather than the UX, which makes them worth more than any latency number here.
     */
    fun stream() = Profile(
        id = "stream",
        title = "Sensor / BLE stream",
        sources = listOf("stream-integrity", "memory", "thermal"),
        series = listOf(
            SeriesSpec(
                id = "stream.interval",
                title = "Inter-sample interval",
                overrunWord = "late sample",
                // INTERVAL. BLE delivers in bursts and the phone timestamps on receipt, so
                // individual intervals scatter widely around nominal while the long-run rate stays
                // exact. Only a gap materially wider than nominal indicates a real loss.
                kind = SeriesKind.INTERVAL,
                overrunMultiple = 2.0,
                bucketLabel = "Channel",
                targets = SeriesTargets(
                    overrunRatePctGood = 1.0, overrunRatePctWarn = 5.0,
                    p99BudgetMultipleGood = 3.0, p99BudgetMultipleWarn = 6.0,
                ),
            ),
            SeriesSpec(
                id = "dsp.stage",
                title = "DSP stage",
                overrunWord = "over-budget",
                bucketLabel = "Stage",
            ),
        ),
        invariants = listOf(
            Invariants.counterIsZero(
                "st.no-dropped-samples", "stream.dropped",
                statement = "No samples were dropped.",
                rationale = "Dropped samples do not feel like anything. They shift band-power " +
                    "estimates, and a within-subject baseline built on a lossy stream is a baseline " +
                    "for the packet loss, not the person.",
            ),
            Invariants.counterIsZero(
                "st.no-reconnects", "stream.reconnects",
                statement = "The BLE link held for the whole session.",
                rationale = "Every reconnect leaves a gap and a clock discontinuity in the middle of " +
                    "a window that will be analysed as continuous.",
                severity = Grade.WARN,
            ),
            Invariants.ratioUnder(
                "st.crc-clean", "stream.crc_errors", "stream.samples", maxPct = 0.1,
                statement = "Fewer than 0.1 % of packets failed integrity checks.",
                rationale = "Corrupt packets that are accepted rather than discarded are worse than " +
                    "dropped ones — they enter the analysis as plausible values.",
            ),
            Invariants.custom(
                "st.clock-drift",
                statement = "Device-to-phone clock drift stayed under 200 ppm.",
                rationale = "Drift accumulates across a long recording and silently misaligns the " +
                    "stream against anything time-locked to it.",
                severity = Grade.WARN,
            ) { e ->
                val ppm = e.facts["stream.drift_ppm"]?.toDoubleOrNull()
                if (ppm == null) Invariant.Outcome(null, "fact `stream.drift_ppm` never reported")
                else Invariant.Outcome(kotlin.math.abs(ppm) <= 200.0, "${Stats.f(ppm, 0)} ppm (max 200)")
            },
        ),
        payloadRules = listOf(
            PayloadRule(
                id = "st.no-raw-signal",
                statement = "No raw signal values appear in the report.",
                // Any run of numeric values long enough to be a signal window rather than a statistic.
                forbidden = Regex("""(-?\d+\.\d+\s*,\s*){8,}-?\d+\.\d+"""),
            ),
            PayloadRule(
                id = "st.no-microvolt-arrays",
                statement = "No microvolt-tagged sample arrays appear in the report.",
                forbidden = Regex("""(?i)\b\d+(\.\d+)?\s*(µV|uV|microvolts)\s*[,;]\s*\d"""),
            ),
        ),
        startup = StartupTargets.none(),
        minSessionSec = 60.0,
        notes = listOf(
            "These reports are designed to be shared, and the underlying data is personal-state data. " +
                "The payload rules above are enforcement, not guidance: integrity statistics may " +
                "leave the device, signal values may not.",
            "Latency is nearly irrelevant here. Read the integrity invariants first and the " +
                "percentiles second.",
        ),
    )

    // ================================================================ service

    /**
     * ASOM, and any headless daemon. No UI, so nothing in the original design applied at all.
     *
     * The characteristic failure is not slowness — it is being killed. A model-serving process on a
     * phone is the most likely thing in the portfolio to meet the low-memory killer, and a session
     * that dies to SIGKILL writes nothing. That is what the journal in Journal.kt exists for, and
     * the survival invariant below is how a recovered report announces itself.
     */
    fun service() = Profile(
        id = "service",
        title = "Headless service / inference daemon",
        sources = listOf("request", "memory", "thermal", "journal"),
        series = listOf(
            SeriesSpec(
                id = "request.latency",
                title = "Request latency",
                budgetMs = 2000.0,
                budgetLabel = "2 000 ms (house SLA — yours to set per model)",
                overrunWord = "slow request",
                severeMs = 30_000.0,
                severeWord = "stalled",
                bucketLabel = "Endpoint",
            ),
            SeriesSpec(
                id = "ttft",
                title = "Time to first token",
                budgetMs = 1500.0,
                budgetLabel = "1 500 ms (house SLA)",
                overrunWord = "slow start",
                bucketLabel = "Model",
                targets = SeriesTargets(overrunRatePctGood = 10.0, overrunRatePctWarn = 25.0),
            ),
            SeriesSpec(
                id = "model.load",
                title = "Model load",
                budgetMs = 10_000.0,
                budgetLabel = "10 000 ms (house SLA)",
                overrunWord = "slow load",
                bucketLabel = "Model",
            ),
        ),
        invariants = listOf(
            Invariants.counterIsZero(
                "svc.no-oom", "oom",
                statement = "The process was never killed for memory.",
                rationale = "A model-serving process is the most likely thing on the device to meet " +
                    "the low-memory killer, and a killed process writes no report at all — which is " +
                    "why a clean-looking history can simply mean the bad runs vanished.",
            ),
            Invariants.custom(
                "svc.survived",
                statement = "The session ended normally rather than being recovered from a journal.",
                rationale = "A recovered report is still evidence, but it is evidence about a process " +
                    "that died. It must never be read as a completed run.",
                severity = Grade.WARN,
            ) { e -> Invariant.Outcome(!e.session.recovered,
                if (e.session.recovered) "recovered from journal" else "ended normally") },
            Invariants.ratioUnder(
                "svc.error-rate", "errors", "requests", maxPct = 1.0,
                statement = "Fewer than 1 % of requests returned an error.",
                rationale = "Routing failures and model errors are indistinguishable from the client " +
                    "side; the daemon is the only place that can count them honestly.",
            ),
            Invariants.counterAtMost(
                "svc.queue-depth", "queue.max_depth", 4,
                statement = "The request queue never exceeded a depth of 4.",
                rationale = "A growing queue means the daemon is accepting work it cannot serve, and " +
                    "latency percentiles stop describing the model and start describing the backlog.",
                severity = Grade.WARN,
            ),
        ),
        startup = StartupTargets.none(),
        minSessionSec = 120.0,
        minSessionSecForDrain = 600.0,
        notes = listOf(
            "Memory is the headline metric here, not latency — this is the one app where PSS " +
                "genuinely approaches the device ceiling.",
            "Sustained inference is the canonical thermal case. Expect the throttle split to matter " +
                "in every long run.",
        ),
    )
}
