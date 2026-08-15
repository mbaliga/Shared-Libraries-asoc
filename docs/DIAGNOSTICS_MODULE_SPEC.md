# `:diagnostics` — on-device evidence module

**Repo:** `shared-libraries-asoc` (alongside `:crash-recovery`, and like it *not part of Hyle* — zero Compose/Material in anything an app is required to take). Landed here directly rather than being relocated from Hyle-Design-System, for the same D-L reason `:crash-recovery` moved.
**Artifact:** `dev.aarso:diagnostics-*` `0.3.0` · consumed via composite-build substitution, not a Maven registry (see the root README)
**Schema:** `diag/2`
**Status:** core verified off-device (248 checks, JVM, in CI); Android layer compiles and assembles (`assembleDebug`/`assembleRelease`) against a real Android SDK in CI, including the merged-manifest no-permission check — still never run on a device

---

## 1. What changed, and why

The first cut of this module measured one thing: frames, from `Window.addOnFrameMetricsAvailableListener`, attached through `ActivityLifecycleCallbacks`. That silently assumed every app is Activity-hosted and rendering a view hierarchy. Two of the seven app types in the constellation satisfy that assumption.

The others fail in three distinct ways, and the failures are worth naming because they determine the architecture.

**No Activity, but a window exists.** An IME is an `InputMethodService`; the lifecycle callback never fires. The window is reachable — `getWindow().getWindow()` — it simply has to be handed over. Clackpad would have produced a clean, empty, entirely misleading report.

**No window at all.** A `WallpaperService.Engine` draws to a `SurfaceHolder`. `FrameMetrics` returns nothing, ever. Animalcules — the app held up as the animation benchmark for the whole constellation — was unmeasurable. So was ASOM, which has no UI whatsoever.

**The bottleneck isn't the UI thread.** Bocal lives on audio callback timing. Crocodyl can render at 120 fps while dropping two of every three camera frames. The EEG work needs sample-loss and jitter, where latency is nearly irrelevant.

The correction was not to write six more measurement systems. It was to notice that **an audio callback overrunning its buffer period, a camera frame missing its capture cadence, a BLE sample arriving late, and a UI frame missing vsync are the same statistical object** — a timed observation judged against a budget. The aggregation, percentiles, overrun rate, verdicts and report layout are written once and every source reuses them. What is genuinely per-app-type is only three things: where the budget comes from, what an overrun is *called*, and which invariants must hold.

---

## 2. Architecture

```
diagnostics-core/        pure JVM · zero android.* (enforced) · all maths, verdicts, reporting
  Series.kt              Observation, SeriesSpec, SeriesKind, aggregates, trends, facts, counters
  Stats.kt               percentiles, regression, budget derivation — source-agnostic
  Invariants.kt          declarative assertions + payload rules + Evidence
  Profiles.kt            the seven app-type profiles
  Verdict.kt             thresholds, confidence, caveats
  Journal.kt             crash-survivable checkpointing
  MarkdownReporter.kt    the one file that leaves the device

diagnostics-android/     MetricSource plugins, session, export, adb trigger · no Compose
diagnostics-overlay/     profile-aware floating bubble · plain Views, not Compose
diagnostics-noop/        identical API, every call a no-op · release builds
```

### 2.1 The measurement vocabulary

```kotlin
data class Observation(
    val tSec: Double,
    val valueMs: Double,
    val bucket: String? = null,              // screen / endpoint / stage / scene / channel
    val phases: Map<String, Double> = emptyMap(),
    val first: Boolean = false,
)
```

`phases` generalises the FrameMetrics breakdown. For a UI frame the keys are draw/layout/sync; for a vision pipeline they are camera/inference/dsp/render. Same table, same dominance analysis, different vocabulary — so the rule that localises a UI stutter to the draw phase equally localises a pipeline stall to inference, without a line of new code.

### 2.2 DURATION versus INTERVAL

This distinction is not cosmetic and it was found by looking at rendered output rather than by reasoning ahead of time.

A **DURATION** series is work that must fit inside a budget: a frame, an audio callback, an inference pass. Exceeding the budget by any amount is the failure, so the overrun threshold is the budget itself.

An **INTERVAL** series is the spacing between arrivals: camera frames at 30 fps, EEG samples at 256 Hz. These jitter around the nominal period by construction, so on a perfectly healthy stream roughly half of them exceed it. Judging an interval against its own nominal period produces a permanent ~50 % "late" rate — not merely a useless metric but an actively harmful one, because a report that always fails is a report nobody reads. Interval series therefore carry a tolerance multiple (1.5× for camera, 2× for BLE), and the report prints the rule rather than leaving it implicit.

The first synthetic vision-pipeline run reported 60 % late frames on a healthy fixture. That is how the bug surfaced.

### 2.3 Budgets are derived, never assumed

```kotlin
Stats.vsyncBudget(refreshHz)                  // display's ACTUAL rate, not the mode maximum
Stats.audioBudget(bufferFrames, sampleRateHz) // the buffer being filled
Stats.rateBudget(hz)                          // capture or sample cadence
```

A source that cannot determine its budget returns the spec **unresolved** rather than guessing. An unresolved series is reported without judgement and raises a caveat. Assuming 16.67 ms is exactly the class of quiet wrongness this module exists to prevent, so the code refuses to do it and says so in the file.

### 2.4 Invariants

The piece that catches what thresholds cannot. A percentile tells you something got slower; an invariant tells you something is structurally wrong in a way no timing number would ever surface.

```kotlin
Invariants.factIsOneOf(
    "vp.accelerated-delegate", "inference.delegate", setOf("gpu", "nnapi"),
    statement = "Pose inference ran on an accelerated delegate.",
    rationale = "MediaPipe falls back to CPU silently when the GPU delegate cannot be created. " +
        "It costs several times the budget and NOTHING in the UI timings shows it, because the " +
        "UI thread was never the bottleneck.",
)
```

Three outcomes, not two: holds, violated, or **not evaluable**. The third is load-bearing. An assertion whose evidence was never collected must report as unevaluated, never quietly as passing — a green report that is green because nothing was measured is worse than no report at all. The renderer states this explicitly and the check suite asserts that no unmeasured invariant can ever render as PASS.

A **payload rule** asserts over the rendered text rather than the collected data. The motivating case is real: the EEG work must never let raw signal values reach a file designed to be shared. That is a property of the payload, not of any measurement, so it can only be checked after rendering — and it must be checked, because a privacy rule enforced by intention alone is not enforced. `StreamIntegritySource` also exposes no method that accepts a sample value, so the leak is structurally difficult *and* asserted against.

---

## 3. The seven profiles

Thresholds below are a starting position from platform documentation and ordinary practice, **not measurements of your apps**. Treat the first real run on the RedMagic as calibration. The invariants are not negotiable in the same way: a violated invariant is a defect regardless of what the timings say.

**`ui`** — Activity-hosted apps (Fonebrew, Nooz, Crocodyl's shell). The original case. Asserts frames were actually collected, because an empty series usually means the collector never attached rather than that the app rendered nothing.

**`ime`** — Clackpad. Frame source attaches to the service's own window. Adds key-down→glyph-visible latency (32 ms house target), since frame rate is not a keyboard's felt quality, and a WebView rAF series because Android-level timing says *when* the WebView composited, never *why* it was slow. Asserts the window was attached, and — conditionally — that no network requests occur while the BYO-AI endpoint is disabled, which is the assertion that keeps the privacy copy and the code in agreement.

**`wallpaper`** — Animalcules. Frames pushed from the engine's own draw loop. Asserts nothing draws while hidden (the classic battery bug, invisible in every timing metric), no GC inside the draw loop, no native libraries in the APK (the deliberate no-NDK decision, worth asserting so a transitive dependency cannot undo it), and that the session ran long enough to estimate drain.

**`audio`** — Bocal, the haptics workbench. Budget is the buffer period. No severe threshold: there is no "frozen callback", an overrun is already the failure. Asserts the low-latency path was *granted* rather than merely requested, zero underruns, and that the stream runs at the device's native rate and buffer size — a mismatch inserts a resampler and an extra buffer for nothing.

**`vision-pipeline`** — Crocodyl. Camera inter-arrival (INTERVAL), pose inference, and end-to-end with stage phases. Asserts an accelerated delegate, a camera drop rate under 5 %, and that analysis keeps up with capture.

**`stream`** — the EEG work. Measures signal integrity, not performance. Asserts zero dropped samples, a held BLE link, clean CRC, bounded clock drift — plus the two payload rules.

**`service`** — ASOM. Request latency, TTFT, model load against stated SLAs. Asserts no OOM kill, an error rate under 1 %, bounded queue depth, and that the session ended normally rather than being recovered from a journal.

---

## 4. Crash-survivable journalling

A session that ends in SIGKILL writes nothing. For ASOM that is not an edge case — a model-serving process on a phone is the most likely thing in the portfolio to meet the low-memory killer — and losing exactly the runs that failed leaves a history that looks healthier than the software is.

The journal writes **aggregates**, not observations: counts, percentiles, counters, facts and the latest trend values, on a fixed cadence. Persisting per-observation data would cost more than the thing being measured.

What that buys and what it costs, stated plainly because a recovered report must never be mistaken for a complete one: you get the shape of the run up to the last checkpoint; you lose everything after it; you lose worst-observation detail, phase breakdowns, bucket splits and the log ring entirely. All of that is listed in the report's *Not measured* section. The report is marked `recovered`, its confidence is downgraded, and the survival invariant reports WARN.

The format is line-oriented text so that a partially-written final line from a dying process does not make the file unparseable. Unparseable lines are counted and skipped; the last complete checkpoint wins. The check suite exercises exactly that case.

---

## 5. Integration

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this, Config(profile = Profiles.wallpaper()))
        Diagnostics.recoverAbandonedSessions()   // worth it for long-running profiles
    }
}
```

Push-fed sources are **not** auto-wired, because they need parameters only the app knows — the negotiated buffer size, the capture rate, which delegate was actually created. Inventing those would produce precisely the confident-but-wrong output this module exists to stop.

```kotlin
Diagnostics.addSource(AudioCallbackSource(
    sampleRateHz = stream.sampleRate,
    bufferFrames = stream.framesPerBurst,
    nativeSampleRateHz = deviceNativeRate,
    nativeBufferFrames = deviceNativeBurst,
    lowLatencyGranted = stream.performanceMode == PerformanceMode.LOW_LATENCY,
))
```

Per-profile intake is a no-op when the active profile has no source consuming it, so an app can carry the calls unconditionally:

```kotlin
Diagnostics.frame(drawMs, scene = "pond")       // wallpaper
Diagnostics.visibility(visible)                 // wallpaper — arms the hidden-draw invariant
Diagnostics.attachImeWindow(window?.window)     // ime
Diagnostics.audioCallback(callbackMs)           // audio
Diagnostics.cameraFrame(); Diagnostics.inference(ms)   // vision
Diagnostics.streamSample(channel)               // stream — timestamps only, never values
Diagnostics.request("/v1/chat", ms)             // service
```

---

## 6. Triggers

Automatic rolling capture is the default. The floating overlay is profile-aware — headline number, budget line and phase rows all come from the active profile, because a panel that says "fps" over an instrument app is worse than no panel — and it **refuses** to show for `ime` and `wallpaper`, where a floating window would cover or steal input from the surface under measurement. It points at the ADB trigger instead, which is the one that matters most for your workflow:

```bash
adb shell am broadcast -a dev.aarso.diagnostics.START --es label "pond-high"
adb shell am broadcast -a dev.aarso.diagnostics.STOP
adb logcat -d -s Diag | grep REPORT_PATH        # pull straight into the repo
adb shell am broadcast -a dev.aarso.diagnostics.RECOVER
```

---

## 7. Safety

Four independent guards, listed in order of how much they are worth:

1. **`release-safety.gradle.kts`** fails the build if a release variant resolves a collector. This is the one that matters — the others assume someone wired the dependency correctly, which is the mistake being guarded against.
2. **`check-noop-parity.py`** asserts the no-op mirrors the real facade, function for function and parameter for parameter. Signature drift does not fail when introduced; it fails later, in someone's release build.
3. **No `INTERNET` permission** in the module manifest, ever. The absence is verifiable in the merged manifest — a sovereignty claim that can be checked beats one that must be trusted. `scripts/check-no-internet-permission.py` asserts exactly this against the actual AGP-merged manifest for both build types (not the source manifest this repo authors — a dependency's own manifest could in principle contribute a permission that never appears there) and runs in CI.
4. **`install()` refuses** in a non-debuggable process, and the ADB receiver re-checks `FLAG_DEBUGGABLE` at runtime.

Redaction replaces anything shaped like a key, token, JWT, email or user path with a *typed* placeholder, because a reader needs to know a value was present and withheld rather than absent. It runs on every free-text field that reaches the rendered report — session label, facts, bucket names, log tag/message, crash throwable/frames, **and** custom-span and mark names (an app can call `Diagnostics.mark(someRuntimeString)`; nothing in the type system stops that string from being user-typed content on a keyboard host, so it gets the same treatment as a bucket or a label).

Storage is app-private throughout: exports go to `getExternalFilesDir(null)/diagnostics/` (package-scoped, no runtime permission on any supported API, not the public/shared external directory), the crash-survivable journal goes to `filesDir/diagnostics-journal/`, and the `FileProvider` path config (`diagnostics_file_paths.xml`) scopes sharing to the `diagnostics/` export directory only — the journal directory is deliberately not exposed, since it is working state, not a deliverable.

---

## 8. Clackpad interop: ingesting `ClackMetric`

Clackpad is the intended first consumer, and it already has its own metrics convention: debug builds log spans as `ClackMetric` logcat lines, gated on `BuildConfig.CLACKPAD_DEBUG_METRICS`, with span names like `touch2char` / `cold_start` / `float_build` / `recovery_capture`, and Clackpad already has `scripts/parse_metrics.sh` reading them. The integration constraint the owner set is explicit: this suite ingests those *existing* spans; Clackpad's logging does not change for it.

That constraint is implemented as two pieces, split the way everything else in this module is split — the text-parsing half is source-agnostic and lives in `diagnostics-core`, the Android-facing half lives in `diagnostics-android`:

- **`ClackMetricLine`** (`diagnostics-core`) — a pure parser/formatter for the `ClackMetric` line convention, unit-tested off-device like the rest of the maths (`clackMetricChecks()` in `CoreChecks.kt`). It reads `name=value[ms]` as the span, tolerates further `key=value` attributes on the line, and is permissive about the surrounding `adb logcat` framing (brief, threadtime, or a bare already-stripped message) so it survives whichever `-v` format a host captured with.
- **`ClackMetricAdapter`** (`diagnostics-android`, package `interop`) — feeds a parsed span into the SAME span table `Diagnostics.span { }` / `beginSpan` already populate, via a new `Diagnostics.recordSpan(name, durationMs)` entry point (a span whose duration is already known, as opposed to one measured in-process against this clock). It is deliberately not a `MetricSource` that owns a callback — there is no portable, permission-free "a logcat line just arrived" event to subscribe to — so the host decides how lines reach it: shelling out to `logcat -s ClackMetric` (an app may read its own process's log without any permission), a `LogcatReceiver`, or a captured-log replay in a test.

**Caveat, stated plainly:** this repo does not have Clackpad attached, so the exact wire format above is *inferred* from the stated convention (tag, span-name shapes, the existence of a parser script already reading them), not read from Clackpad's own source. `ClackMetricLine.parseMessage` is the single seam to adjust if Clackpad's actual line shape differs; neither the logcat-framing parser nor the Android-side adapter would need to change.

**Privacy, by construction, not by promise:** only the span name and duration cross into the report — both go through the same redactor every mark/span/bucket does before rendering. Any further `key=value` attribute the parser notices on a `ClackMetric` line is available on `ClackMetricLine.Span.attrs` but is deliberately **not** forwarded by the adapter. This is the ingestion seam for a privacy-first keyboard's own debug log lines; a future attribute Clackpad logs alongside a span must not gain a path into a file this module exists to make shareable just because the parser happened to notice it sitting there.

```kotlin
// Clackpad, debug build, wherever it reads its own ClackMetric lines today:
ClackMetricAdapter.ingest("D/ClackMetric( 8123): touch2char=37.4ms")   // -> recorded as a span
```

---

## 9. What is verified and what is not

Verified off-device, 248 checks, all in CI (`:diagnostics-core:test`): percentile and regression maths; the source-agnostic claim (identical overrun rate for the same population through four different domains); interval tolerance, including that a naive treatment misfires and that real gaps are still caught; phase means excluding severe outliers; ring wraparound; redaction — including span/mark-name redaction and the ClackMetric-adapter's attribute drop; every invariant builder in all three outcomes; every profile's structure; verdict boundaries; the no-false-leak rule; confidence and caveats; journal round-trip including a truncated final line; renderer determinism; every profile rendering coherently with its own vocabulary and no leakage between them; and the `ClackMetricLine` parser/formatter round-trip across brief/threadtime/bare logcat framing.

Also verified, in CI, against a real Android SDK: `diagnostics-android`, `diagnostics-overlay` and `diagnostics-noop` all compile and assemble (`assembleDebug`/`assembleRelease`) as real AARs; `diagnostics-noop`'s API surface is asserted to mirror `diagnostics-android`'s exactly (`check-noop-parity.py`); and the merged manifest of both `diagnostics-android` build types is asserted to declare zero permissions (`check-no-internet-permission.py`).

Not verified: the Android layer has never run on a device or in an emulator, so no MetricSource has ever observed a real callback, no overlay has ever been drawn, and no ADB trigger has ever been fired against a running app. It compiles and assembles against a real SDK now — that gate did not exist before this reconciliation — but "assembles" and "runs correctly" are different claims, and per the environment-honesty convention only the first one is made here.
