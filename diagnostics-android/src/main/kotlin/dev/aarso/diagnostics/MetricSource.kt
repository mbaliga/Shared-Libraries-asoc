package dev.aarso.diagnostics

import dev.aarso.diagnostics.core.Fact
import dev.aarso.diagnostics.core.Observation
import dev.aarso.diagnostics.core.SeriesSpec

/**
 * A plugin that contributes measurements.
 *
 * This interface is the whole rework in one type. The original module hard-wired one measurement
 * path — Window.addOnFrameMetricsAvailableListener via ActivityLifecycleCallbacks — which quietly
 * assumed every app is Activity-hosted and rendering a view hierarchy. Two of the seven app types
 * in the portfolio satisfy that. A wallpaper has no Window at all; an IME has one but no Activity;
 * a daemon has neither; and for audio, camera pipelines and BLE streams the frame is not the unit
 * of work in the first place.
 *
 * So the platform coupling lives here, per source, and everything above it — aggregation,
 * percentiles, verdicts, invariants, reporting — is source-agnostic and already unit-tested
 * off-device.
 *
 * Two kinds of source exist and the distinction matters for where the cost lands:
 *
 *  - PULL sources own their own callback (frame metrics, thermal listeners). They call [emit]
 *    themselves and the app never sees them.
 *  - PUSH sources are fed by the app, because only the app knows when the thing happened — a
 *    wallpaper's draw loop, an audio callback, an inference pass. These expose a facade method
 *    (Diagnostics.frame, .stage, .request) and must stay allocation-free on the caller's thread.
 *
 * A source must not do work proportional to the data it collects while collecting it. Aggregation
 * happens once, at report time, when the measurement is already over and the cost cannot
 * contaminate it.
 */
interface MetricSource {

    /** Stable id, matching what a Profile declares in [dev.aarso.diagnostics.core.Profile.sources]. */
    val id: String

    /**
     * Series this source can populate, with budgets RESOLVED where the source knows them.
     *
     * A source that cannot determine its budget returns the spec unresolved rather than guessing.
     * An unresolved series is reported without judgement and raises a caveat — which is the correct
     * outcome, because a fabricated budget produces confident nonsense and this module exists to
     * stop exactly that.
     */
    fun specs(): List<SeriesSpec> = emptyList()

    /** Discrete environment facts for the invariants to assert over. */
    fun facts(): List<Fact> = emptyList()

    fun start(sink: Sink) {}
    fun stop() {}

    /** Where a source puts what it measures. Implemented by the Session; sources never buffer. */
    interface Sink {
        fun observe(seriesId: String, o: Observation)
        fun count(name: String, delta: Long = 1)
        /**
         * Set a counter to an absolute value rather than incrementing it. Needed for gauge-like
         * quantities such as a high-water mark: adding 1 each time a new maximum is seen would
         * report the number of times the maximum moved, not the maximum.
         */
        fun set(name: String, value: Long)
        fun fact(key: String, value: String, note: String? = null)
        /** Session-relative seconds, so every source shares one clock. */
        fun now(): Double
    }
}

/**
 * Sources a profile expects but that were never registered.
 *
 * Surfaced deliberately rather than ignored: a profile declaring `manual-frames` on an app that
 * never calls Diagnostics.frame() produces an empty report that looks calm. The seriesPresent
 * invariants turn that silence into a visible FAIL, and this list explains why.
 */
class SourceRegistry {

    private val lock = Any()
    private val sources = LinkedHashMap<String, MetricSource>()

    // addSource() can register a push source from an arbitrary app thread (e.g. once an audio
    // engine resolves its negotiated buffer size) while Session.start()/stop() iterate all() on
    // whatever thread started/ended the session -- an unsynchronized LinkedHashMap turns that into
    // a fail-fast-iterator/corruption hazard, so every access goes through the same lock.
    fun register(source: MetricSource) { synchronized(lock) { sources[source.id] = source } }
    fun get(id: String): MetricSource? = synchronized(lock) { sources[id] }
    fun all(): List<MetricSource> = synchronized(lock) { sources.values.toList() }

    fun missingFor(expected: List<String>): List<String> =
        synchronized(lock) { expected.filterNot { sources.containsKey(it) } }
}
