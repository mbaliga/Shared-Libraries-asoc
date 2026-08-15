package dev.aarso.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.Window
import dev.aarso.diagnostics.core.Level
import dev.aarso.diagnostics.core.Profile
import dev.aarso.diagnostics.core.Profiles
import dev.aarso.diagnostics.core.Redactor
import dev.aarso.diagnostics.core.Report
import dev.aarso.diagnostics.core.SeriesSpec
import dev.aarso.diagnostics.core.Trigger
import java.io.File

/**
 * Release-build stand-in. Mirrors the debug API exactly and does nothing.
 *
 * This exists so call sites — Diagnostics.frame(...), .audioCallback(...), .span("model-load") —
 * compile unchanged in release without a BuildConfig.DEBUG fence at every instrumentation point.
 * Fences get forgotten; a no-op artifact cannot be.
 *
 *     debugImplementation("dev.aarso:diagnostics-android:0.2.0")
 *     debugImplementation("dev.aarso:diagnostics-overlay:0.2.0")
 *     releaseImplementation("dev.aarso:diagnostics-noop:0.2.0")
 *
 * The CI guard in release-safety.gradle.kts fails the build if a release variant ever resolves the
 * real artifact. That is the check that actually matters — the rest is convention, and convention
 * is not what should stand between a collector and a shipped APK.
 *
 * KEEP THIS IN LOCKSTEP with the real facade. Signature drift here does not fail until someone's
 * release build breaks, which is the worst possible moment to discover it. `check-noop-parity.py`
 * in the repo root asserts the two match, and runs in CI.
 */
object Diagnostics {

    val isCapturing: Boolean get() = false
    val profile: Profile get() = Profiles.ui()

    @JvmStatic @JvmOverloads fun install(app: Application, config: Config = Config()) = Unit
    @JvmStatic fun addSource(source: Any) = Unit
    @JvmStatic fun <T> source(id: String): T? = null

    @JvmStatic
    @JvmOverloads
    fun startSession(label: String? = null, trigger: Trigger = Trigger.API): SessionHandle? = null

    @JvmStatic fun endSession(): Report? = null

    @JvmStatic fun mark(name: String) = Unit

    /** Inlined and empty: the lambda still runs, the timing simply is not recorded. */
    @JvmStatic inline fun <T> span(name: String, block: () -> T): T = block()

    @JvmStatic fun beginSpan(name: String): SpanHandle? = null
    @JvmStatic fun recordSpan(name: String, durationMs: Double) = Unit

    @JvmStatic
    @JvmOverloads
    fun log(tag: String, message: String, level: Level = Level.INFO) = Unit

    @JvmStatic @JvmOverloads fun counter(name: String, delta: Long = 1) = Unit
    @JvmStatic @JvmOverloads fun fact(key: String, value: String, note: String? = null) = Unit
    @JvmStatic fun attribute(key: String, value: String) = Unit
    @JvmStatic fun screenEntered(name: String) = Unit
    @JvmStatic fun screenExited(name: String) = Unit
    @JvmStatic fun resolvedSeriesSpec(seriesId: String): SeriesSpec? = null

    // per-profile intake
    @JvmStatic @JvmOverloads fun frame(durationMs: Double, scene: String? = null) = Unit
    @JvmStatic fun visibility(visible: Boolean) = Unit
    @JvmStatic fun attachImeWindow(window: Window?) = Unit
    @JvmStatic fun keyDown() = Unit
    @JvmStatic @JvmOverloads fun glyphVisible(key: String? = null) = Unit
    @JvmStatic fun audioCallback(durationMs: Double) = Unit
    @JvmStatic @JvmOverloads fun audioUnderrun(count: Long = 1) = Unit
    @JvmStatic fun cameraFrame() = Unit
    @JvmStatic @JvmOverloads fun cameraDropped(n: Long = 1) = Unit
    @JvmStatic fun inference(ms: Double) = Unit
    @JvmStatic fun pipelineEndToEnd(cameraMs: Double, inferenceMs: Double, dspMs: Double, renderMs: Double) = Unit
    @JvmStatic @JvmOverloads fun streamSample(channel: String? = null) = Unit
    @JvmStatic @JvmOverloads fun streamDropped(n: Long = 1) = Unit
    @JvmStatic fun streamReconnected() = Unit
    @JvmStatic @JvmOverloads fun request(endpoint: String, durationMs: Double, error: Boolean = false) = Unit
    @JvmStatic fun timeToFirstToken(model: String, ms: Double) = Unit

    // output
    @JvmStatic @JvmOverloads fun snapshot(label: String? = null): File? = null
    @JvmStatic fun export(report: Report): File? = null
    @JvmStatic fun share(context: Context, file: File) = Unit
    @JvmStatic fun listReports(): List<File> = emptyList()
    @JvmStatic fun deleteReport(file: File): Boolean = false
    @JvmStatic fun recoverAbandonedSessions(): List<File> = emptyList()

    // overlay
    @JvmStatic fun showOverlay(activity: Activity) = Unit
    @JvmStatic fun hideOverlay() = Unit
}

/** Mirrors the debug Config field-for-field so install() compiles in both variants. */
data class Config(
    val profile: Profile = Profiles.ui(),
    val sampleIntervalMs: Long = 500,
    val journalIntervalMs: Long = 10_000,
    val journalEnabled: Boolean = true,
    val logRingCapacity: Int = 512,
    val observationCapacity: Int = 20_000,
    val autoSession: Boolean = true,
    val retainReports: Int = 20,
    val overlayOnLaunch: Boolean = false,
    val registerAdbReceiver: Boolean = true,
    val redactor: Redactor = Redactor.default(),
)

@JvmInline value class SessionHandle(val id: String)

interface SpanHandle { fun end() }
