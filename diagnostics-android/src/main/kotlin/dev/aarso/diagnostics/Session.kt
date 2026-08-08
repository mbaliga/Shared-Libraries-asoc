package dev.aarso.diagnostics

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.aarso.diagnostics.collect.*
import dev.aarso.diagnostics.crash.CrashLink
import dev.aarso.diagnostics.core.*
import dev.aarso.diagnostics.export.JournalWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns one capture: holds the ring buffers, drives the sources, and assembles a [Report].
 *
 * Threading. Observations arrive on whatever thread produced them — the main thread for UI frames,
 * the audio thread for callbacks, a BLE callback thread for samples — so the sink is the one place
 * that has to be safe under concurrency, and it is kept to an array append under a per-series lock.
 * Aggregation happens once, in [buildReport], when the measurement is already over.
 */
internal class Session(
    private val app: Application,
    private val config: Config,
    private val label: String?,
    private val trigger: Trigger,
    private val registry: SourceRegistry,
) {
    val profile: Profile = config.profile
    val id: String = buildId(app.packageName, label, config.redactor)

    private val startedWallMs = System.currentTimeMillis()
    private val startedElapsedMs = SystemClock.elapsedRealtime()

    private val lock = Any()
    private val observations = HashMap<String, RingBuffer<Observation>>()
    private val specs = HashMap<String, SeriesSpec>()
    private val counters = HashMap<String, Long>()
    private val facts = LinkedHashMap<String, Fact>()
    private val logs = RingBuffer<LogEntry>(config.logRingCapacity)
    private val marks = mutableListOf<Mark>()
    private val spans = HashMap<String, MutableList<Double>>()
    private val attributes = LinkedHashMap<String, String>()
    private val memorySamples = mutableListOf<MemorySample>()
    private val thermalTransitions = mutableListOf<Pair<Double, ThermalStatus>>()
    private val screenStack = ArrayDeque<String>()

    private val thread = HandlerThread("diagnostics").apply { priority = Thread.MIN_PRIORITY }
    private lateinit var handler: Handler

    private val memoryCollector = MemoryCollector(app)
    private val thermalCollector = ThermalCollector(app)
    private val journal = if (config.journalEnabled) JournalWriter(app, id) else null

    @Volatile var isRunning = false
        private set

    // ------------------------------------------------------------------ sink

    private val sink = object : MetricSource.Sink {
        override fun observe(seriesId: String, o: Observation) {
            val ring = synchronized(lock) {
                observations.getOrPut(seriesId) { RingBuffer(config.observationCapacity) }
            }
            synchronized(ring) { ring.add(o) }
        }
        override fun count(name: String, delta: Long) {
            synchronized(lock) { counters[name] = (counters[name] ?: 0L) + delta }
        }
        override fun set(name: String, value: Long) {
            synchronized(lock) { counters[name] = value }
        }
        override fun fact(key: String, value: String, note: String?) {
            synchronized(lock) { facts[key] = Fact(key, value, note) }
        }
        override fun now(): Double = (SystemClock.elapsedRealtime() - startedElapsedMs) / 1000.0
    }

    // ------------------------------------------------------------------ control

    fun start() {
        if (isRunning) return
        isRunning = true
        thread.start()
        handler = Handler(thread.looper)

        for (source in registry.all()) {
            // Specs are captured at start so an unresolved budget is visible in the report rather
            // than silently filled in later from a value that was never true during the capture.
            for (spec in source.specs()) synchronized(lock) { specs[spec.id] = spec }
            for (f in source.facts()) sink.fact(f.key, f.value, f.note)
            source.start(sink)
        }
        // Any series the profile declares but no source supplied still appears, unresolved and
        // empty, so its absence is stated rather than inferred from a missing section.
        for (spec in profile.series) synchronized(lock) { specs.putIfAbsent(spec.id, spec) }

        thermalCollector.start { st -> synchronized(lock) { thermalTransitions += sink.now() to st } }
        journal?.begin(SessionInfo(id, label, ISO.format(Date(startedWallMs)), 0.0, trigger),
            DeviceContext.appInfo(app), profile.id)
        scheduleSampling()
        mark("session-start")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        mark("session-end")
        registry.all().forEach { runCatching { it.stop() } }
        thermalCollector.stop()
        handler.removeCallbacksAndMessages(null)
        journal?.end(sink.now())
        thread.quitSafely()
    }

    private fun scheduleSampling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                val s = memoryCollector.sample(sink.now())
                synchronized(lock) { memorySamples += s }
                handler.postDelayed(this, config.sampleIntervalMs)
            }
        }, config.sampleIntervalMs)

        val j = journal ?: return
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                // Aggregates only. Journalling raw observations would cost more than the thing
                // being measured; see Journal.kt for what that trade buys and what it loses.
                runCatching { j.checkpoint(sink.now(), buildSeries(), snapshotCounters(),
                    snapshotFacts(), buildTrends()) }
                handler.postDelayed(this, config.journalIntervalMs)
            }
        }, config.journalIntervalMs)
    }

    // ------------------------------------------------------------------ app-facing intake

    fun mark(name: String, auto: Boolean = false) {
        synchronized(lock) { marks += Mark(sink.now(), name, auto) }
    }

    fun beginSpan(name: String): SpanHandle {
        val t0 = System.nanoTime()
        return object : SpanHandle {
            override fun end() {
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                synchronized(lock) { spans.getOrPut(name) { mutableListOf() } += ms }
            }
        }
    }

    fun log(tag: String, message: String, level: Level) {
        synchronized(lock) {
            logs.add(LogEntry(TIME.format(Date()), level, tag, message, Thread.currentThread().name))
        }
    }

    fun counter(name: String, delta: Long) = sink.count(name, delta)
    fun fact(key: String, value: String, note: String? = null) = sink.fact(key, value, note)
    fun attribute(key: String, value: String) { synchronized(lock) { attributes[key] = value } }
    fun screenEntered(name: String) { screenStack.addLast(name); mark("screen:$name") }
    fun screenExited(name: String) { screenStack.remove(name) }
    fun currentScreen(): String? = screenStack.lastOrNull()
    fun sinkRef(): MetricSource.Sink = sink

    /** The RUNTIME-resolved spec for a series, if a source has supplied one. See [start]. */
    fun resolvedSpec(seriesId: String): SeriesSpec? = synchronized(lock) { specs[seriesId] }

    // ------------------------------------------------------------------ assembly

    private fun snapshotCounters(): Map<String, Long> = synchronized(lock) { counters.toMap() }
    private fun snapshotFacts(): Map<String, String> =
        synchronized(lock) { facts.values.associate { it.key to it.value } }

    private fun buildSeries(): List<SeriesReport> {
        val duration = sink.now()
        val throttleAt = synchronized(lock) {
            thermalTransitions.firstOrNull { it.second.ordinal >= ThermalStatus.MODERATE.ordinal }?.first
        }
        val windows = if (throttleAt == null) emptyList() else listOf(
            "Pre-throttle (0–${throttleAt.toInt()} s)" to { o: Observation -> o.tSec <= throttleAt },
            "Post-throttle (${throttleAt.toInt()} s–end)" to { o: Observation -> o.tSec > throttleAt },
        )
        val snapshot = synchronized(lock) { specs.toMap() to observations.toMap() }
        return snapshot.first.values.map { spec ->
            val ring = snapshot.second[spec.id]
            val obs = if (ring == null) emptyList() else synchronized(ring) { ring.snapshot() }
            Stats.seriesReport(obs, spec, duration, windows)
        }.sortedBy { it.spec.id }
    }

    private fun buildTrends(): List<TrendGroup> {
        val samples = synchronized(lock) { memorySamples.toList() }
        if (samples.isEmpty()) return emptyList()
        val t = samples.map { it.tSec }
        return listOf(TrendGroup(
            id = "memory", title = "Memory", unit = "MB",
            sampleCount = samples.size, sampleIntervalMs = config.sampleIntervalMs,
            series = listOf(
                Stats.trendSeries("PSS total", t, samples.map { it.pssTotalMb }),
                Stats.trendSeries("PSS dalvik", t, samples.map { it.pssDalvikMb }),
                Stats.trendSeries("PSS native", t, samples.map { it.pssNativeMb }),
                Stats.trendSeries("PSS graphics", t, samples.map { it.pssGraphicsMb }),
                Stats.trendSeries("PSS other", t, samples.map { it.pssOtherMb }),
                Stats.trendSeries("Java heap used", t, samples.map { it.javaHeapUsedMb }),
            ),
        ))
    }

    fun buildReport(): Report {
        val duration = sink.now()
        val device = DeviceContext.read(app)
        return Report(
            profileId = profile.id,
            profileTitle = profile.title,
            session = SessionInfo(id, label, ISO.format(Date(startedWallMs)), duration, trigger,
                synchronized(lock) { attributes.toMap() }),
            app = DeviceContext.appInfo(app),
            device = device,
            series = buildSeries(),
            trends = buildTrends(),
            facts = synchronized(lock) { facts.values.toList() },
            counters = synchronized(lock) { counters.map { CounterValue(it.key, it.value) } },
            power = thermalCollector.section(synchronized(lock) { thermalTransitions.toList() }, duration),
            startup = StartupTracker.section(),
            spans = synchronized(lock) { spans.map { (n, v) -> Stats.spanStat(n, v) } },
            marks = synchronized(lock) { marks.toList() },
            logs = synchronized(lock) { logs.snapshot() },
            logRingCapacity = config.logRingCapacity,
            crash = CrashLink.lastCrash(app),
            crashModulePresent = CrashLink.isPresent(),
            notMeasured = notMeasured(duration),
        )
    }

    /**
     * Absent must never read as zero. Everything the module could not collect is named with the
     * reason, including sources the profile expected but that were never registered — an empty
     * section from an unwired source otherwise looks exactly like a healthy quiet one.
     */
    private fun notMeasured(duration: Double): List<NotMeasured> = buildList {
        for (missing in registry.missingFor(profile.sources))
            add(NotMeasured("Source `$missing`",
                "declared by profile `${profile.id}` but never registered, so anything it would " +
                    "have measured is absent rather than zero"))

        val unresolved = synchronized(lock) { specs.values.filter { !it.resolved } }
        for (u in unresolved)
            add(NotMeasured("Budget for `${u.id}`",
                "no source supplied one; observations are reported unjudged rather than compared " +
                    "against a fabricated budget"))

        if (duration < profile.minSessionSecForDrain)
            add(NotMeasured("Battery drain rate",
                "session below the ${Stats.f(profile.minSessionSecForDrain, 0)} s minimum for " +
                    "profile `${profile.id}`"))

        add(NotMeasured("CPU utilisation per core", "no non-root API above API 26"))
        add(NotMeasured("Network", "module declares no INTERNET permission by design"))

        synchronized(lock) {
            for ((sid, ring) in observations) if (ring.dropped > 0)
                add(NotMeasured("${ring.dropped} early observation(s) of `$sid`",
                    "aged out of a ${config.observationCapacity}-entry ring; raise " +
                        "Config.observationCapacity for this profile's rate"))
            if (logs.dropped > 0) add(NotMeasured("${logs.dropped} older log line(s)",
                "aged out of a ${config.logRingCapacity}-entry ring"))
        }
    }

    private companion object {
        val ISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val ID = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        fun buildId(pkg: String, label: String?, redactor: Redactor): String =
            "diag_${pkg}_${ID.format(Date())}" +
                // Redact BEFORE the filename-safety sanitisation below: this id becomes both the
                // report body's session id and the exported .md filename (see ReportWriter.write),
                // so a secret-shaped label must never survive into it either way.
                (label?.let { "_${redactor.redact(it).replace(Regex("[^A-Za-z0-9._-]"), "-")}" } ?: "")
    }
}
