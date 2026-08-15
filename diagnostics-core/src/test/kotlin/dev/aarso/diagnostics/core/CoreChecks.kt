package dev.aarso.diagnostics.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-device checks for diagnostics-core. Bare `main` so it runs with plain kotlinc and no test
 * framework; in the repo these become JUnit cases one-for-one and run in CI, which stays
 * Android-SDK-free by construction.
 *
 * The point of the rework was that one aggregation path serves every app type, so the suite proves
 * exactly that: the same maths is exercised through all seven profiles, and each profile's
 * invariants are checked in all three outcomes — holds, violated, and NOT EVALUABLE.
 *
 * [CoreChecksSuite] below is that promised JUnit wiring — added when this module actually landed
 * in a repo with a real Gradle/JUnit Platform setup (it hadn't, before). `main()` itself is left
 * exactly as authored (still runnable standalone via plain kotlinc, still useful for a full-audit
 * console dump), and [check] is unchanged (log-and-count, not throw-on-failure) so `main()`'s
 * "run every check, report a full failure list at the end" behavior survives untouched. The suite
 * wraps each of the ten section functions in its own `@Test`, resetting the shared counters first
 * and asserting zero failures after — bucketed by section (ten JUnit tests), not exploded into
 * 179 individually-named JUnit cases: with 179 real `check()` call sites (some inside loops, so
 * more than 179 assertions actually execute — the true count is whatever `passed` reads after a
 * full run, not a static grep of this file), a genuine one-for-one split risked far more
 * transcription error for no verification benefit `assertTrue(failed == 0, ...)` doesn't already
 * give: a failing assertion still names the exact failed check by its own `name` argument, via
 * the shared `failures` list.
 */

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passed++; println("  [ok]   $name") }
    else {
        failed++; failures += name
        println("  [FAIL] $name${if (detail.isNotEmpty()) "  -- $detail" else ""}")
    }
}

private fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps

fun main() {
    statsChecks()
    seriesChecks()
    ringChecks()
    redactorChecks()
    clackMetricChecks()
    invariantChecks()
    profileChecks()
    verdictChecks()
    journalChecks()
    reporterChecks()
    crossProfileRenderChecks()

    println("\n" + "=".repeat(52))
    println("  $passed passed, $failed failed")
    if (failures.isNotEmpty()) failures.forEach { println("   - $it") }
    println("=".repeat(52) + "\n")
    if (failed > 0) kotlin.system.exitProcess(1)
}

// ==================================================================== stats

private fun statsChecks() {
    println("\n== Stats ==")
    val v = (1..100).map { it.toDouble() }
    check("p50 nearest-rank", near(Stats.percentile(v, 0.50), 50.0))
    check("p99 nearest-rank", near(Stats.percentile(v, 0.99), 99.0))
    check("percentile names an observed value", Stats.percentile(v, 0.99) in v)
    check("empty percentile is 0", near(Stats.percentile(emptyList(), 0.5), 0.0))
    check("unsorted input handled", near(Stats.percentile(listOf(9.0, 1.0, 5.0), 0.5), 5.0))

    val xs = (0..10).map { it.toDouble() }
    val fit = Stats.linearFit(xs, xs.map { 3.0 * it + 5.0 })
    check("perfect line slope", near(fit.slope, 3.0, 1e-9))
    check("perfect line R2 = 1", near(fit.r2, 1.0, 1e-9))
    check("flat series slope 0", near(Stats.linearFit(xs, xs.map { 42.0 }).slope, 0.0))
    val rnd = Random(7)
    check("noisy series has low R2",
        Stats.linearFit(xs, xs.map { 42.0 + rnd.nextDouble(-20.0, 20.0) }).r2 < 0.5)

    val t = (0..60).map { it.toDouble() }
    val s = Stats.trendSeries("x", t, t.map { 100.0 + it })
    check("trend slope converted to per-minute", near(s.slopePerMin, 60.0, 1e-6), "got ${s.slopePerMin}")
    check("trend peak captured", near(s.peak, 160.0))

    // Budget derivation — one place, so a wrong budget is a bug in one visible spot.
    check("vsync budget 120 Hz", near(Stats.vsyncBudget(120.0), 8.3333333, 1e-6))
    check("vsync budget 60 Hz differs", !near(Stats.vsyncBudget(60.0), Stats.vsyncBudget(120.0), 0.1))
    check("audio budget 256 frames @ 48 kHz", near(Stats.audioBudget(256, 48000), 5.3333333, 1e-6))
    check("audio budget 96 frames @ 48 kHz is smaller",
        Stats.audioBudget(96, 48000) < Stats.audioBudget(256, 48000))
    check("rate budget 30 fps camera", near(Stats.rateBudget(30.0), 33.3333333, 1e-6))
    check("rate budget 256 Hz EEG", near(Stats.rateBudget(256.0), 3.90625, 1e-9))
    check("zero rate yields zero budget, not infinity", near(Stats.rateBudget(0.0), 0.0))

    check("p95 withheld below 20 spans", Stats.spanStat("x", List(5) { 1.0 }).p95Ms == null)
    check("p95 present at 100 spans", Stats.spanStat("x", List(100) { it.toDouble() }).p95Ms != null)
}

// ==================================================================== series

private fun seriesChecks() {
    println("\n== Series aggregation (source-agnostic) ==")

    // The same 10%-overrun population, judged through four different domains. If the abstraction
    // holds, the overrun rate is identical and only the vocabulary and budget change.
    fun pop(budget: Double) = List(100) { Observation(it * 0.01, if (it < 90) budget * 0.7 else budget * 2.4) }

    val ui = SeriesSpec("frames", "Frames", 8.33, "8.33 ms", "jank", severeMs = 700.0)
    val audio = SeriesSpec("audio.callback", "Audio callback", 5.33, "5.33 ms", "underrun", severeMs = null)
    val cam = SeriesSpec("camera.arrival", "Camera", 33.33, "33.33 ms", "late frame", severeMs = null)
    val eeg = SeriesSpec("stream.interval", "Sample interval", 3.90, "3.90 ms", "late sample", severeMs = null)

    val rates = listOf(ui, audio, cam, eeg).map { Stats.seriesStats(pop(it.budgetMs), it).overrunRatePct }
    check("identical overrun rate across four domains", rates.all { near(it, 10.0, 1e-9) },
        "got $rates")
    check("severe only counted where the domain defines it",
        Stats.seriesStats(pop(8.33) + Observation(9.9, 812.0), ui).severeCount == 1 &&
            Stats.seriesStats(pop(5.33) + Observation(9.9, 812.0), audio).severeCount == 0,
        "audio has no 'frozen callback' concept")

    check("unresolved spec reports zero overruns rather than inventing a budget",
        Stats.seriesStats(pop(8.33), SeriesSpec("x", "X")).overrunCount == 0)
    check("unresolved spec is flagged", !SeriesSpec("x", "X").resolved)
    check("resolve() marks it resolved", SeriesSpec("x", "X").resolve(8.33, "8.33 ms").resolved)

    // Phase means must exclude severe observations, or one outlier rewrites the attribution.
    val normal = List(9) { Observation(it.toDouble(), 40.0,
        phases = mapOf("layout" to 2.0, "draw" to 20.0, "commandIssue" to 16.0)) }
    val frozen = Observation(9.0, 812.0,
        phases = mapOf("layout" to 604.0, "draw" to 88.0, "commandIssue" to 94.0))
    val rep = Stats.seriesReport(normal + frozen, ui, 10.0)
    val top = rep.phaseMeans.maxByOrNull { it.value }!!.key
    check("phase means exclude the severe outlier", top == "draw",
        "got $top with means ${rep.phaseMeans}")
    check("severe observation still appears in worst list",
        rep.worst.any { it.valueMs > 700.0 })

    // Bucketing
    val bucketed = List(200) { Observation(it * 0.01, if (it < 100) 6.0 else 30.0,
        bucket = if (it < 100) "A" else "B") }
    val br = Stats.seriesReport(bucketed, ui, 2.0)
    check("buckets split", br.buckets.size == 2)
    check("bucket B carries the overruns",
        br.buckets.first { it.bucket == "B" }.stats.overrunRatePct > 99.0)
    check("buckets sorted by count", br.buckets.map { it.stats.count } ==
        br.buckets.map { it.stats.count }.sortedDescending())

    // Windows (pre/post throttle)
    val wr = Stats.seriesReport(bucketed, ui, 2.0,
        windows = listOf("Pre" to { o: Observation -> o.tSec <= 1.0 },
            "Post" to { o: Observation -> o.tSec > 1.0 }))
    check("windows computed", wr.windows.size == 2 && wr.windows[0].stats.count == 101,
        "got ${wr.windows.map { it.stats.count }}")

    check("rate is observations per second", near(Stats.seriesReport(pop(8.33), ui, 2.0).rate, 50.0))

    // INTERVAL series must not report ~50 % late on a healthy stream. Judging arrival cadence
    // against its own nominal period is the trap: jitter is symmetric, so half of every healthy
    // stream sits above nominal and the metric permanently screams.
    val rnd2 = Random(3)
    val nominal = Stats.rateBudget(30.0)
    val healthy = List(900) { Observation(it / 30.0, nominal + rnd2.nextDouble(-3.0, 3.0)) }

    val naive = SeriesSpec("camera.arrival", "Camera", nominal, "33.33 ms", "late frame")
    check("naive DURATION treatment of an interval misfires (this is the bug that was fixed)",
        Stats.seriesStats(healthy, naive).overrunRatePct > 40.0,
        "got ${Stats.f(Stats.seriesStats(healthy, naive).overrunRatePct)} %")

    val interval = Profiles.visionPipeline().spec("camera.arrival")!!.resolve(nominal, "33.33 ms")
    check("INTERVAL kind is set on camera.arrival", interval.kind == SeriesKind.INTERVAL)
    check("healthy jittering stream reports ~0 % late",
        Stats.seriesStats(healthy, interval).overrunRatePct < 1.0,
        "got ${Stats.f(Stats.seriesStats(healthy, interval).overrunRatePct)} %")

    // ...but a real gap must still be caught.
    val withGaps = healthy.toMutableList()
    repeat(45) { withGaps[it * 20] = withGaps[it * 20].copy(valueMs = nominal * 2.2) }
    check("real gaps are still caught",
        Stats.seriesStats(withGaps, interval).overrunRatePct in 4.0..6.0,
        "got ${Stats.f(Stats.seriesStats(withGaps, interval).overrunRatePct)} %")
    check("overrun rule is printed, not implicit",
        interval.overrunRule.contains("nominal interval") && interval.overrunRule.contains("gap"))
    check("DURATION series keep budget as the threshold",
        near(ui.overrunAtMs, ui.budgetMs))
    check("EEG stream interval also uses INTERVAL kind",
        Profiles.stream().spec("stream.interval")!!.kind == SeriesKind.INTERVAL)
    check("every INTERVAL series has a tolerance above 1.0",
        Profiles.all().flatMap { it.series }.filter { it.kind == SeriesKind.INTERVAL }
            .all { it.overrunMultiple > 1.0 })
    check("every DURATION series has a tolerance of exactly 1.0",
        Profiles.all().flatMap { it.series }.filter { it.kind == SeriesKind.DURATION }
            .all { near(it.overrunMultiple, 1.0) })
}

// ==================================================================== ring

private fun ringChecks() {
    println("\n== RingBuffer ==")
    val r = RingBuffer<Int>(4)
    (1..4).forEach { r.add(it) }
    check("fills to capacity", r.snapshot() == listOf(1, 2, 3, 4))
    r.add(5); r.add(6)
    check("wraps oldest-first", r.snapshot() == listOf(3, 4, 5, 6), "got ${r.snapshot()}")
    check("dropped counted", r.dropped == 2L)
    r.clear()
    check("clear empties", r.snapshot().isEmpty())
}

// ==================================================================== redactor

private fun redactorChecks() {
    println("\n== Redactor ==")
    val red = Redactor.default()
    check("bearer token removed",
        !red.redact("Authorization: Bearer abcdef1234567890xyz").contains("abcdef1234567890"))
    check("google key removed",
        red.redact("key=AIzaSyA1234567890abcdefghijklmnopqrstuv").contains("<redacted:"))
    check("email removed", red.redact("mail me at a@b.com").contains("<redacted:email>"))
    check("assignment removed", red.redact("password = hunter2").contains("<redacted:assignment>"))
    check("placeholder is typed", red.redact("a@b.com").contains("email"))
    check("ordinary line untouched",
        red.redact("atlas upload id=93 size=2048x2048") == "atlas upload id=93 size=2048x2048")
    check("version string not eaten by ipv4 rule", red.redact("version 0.4.2 (42)") == "version 0.4.2 (42)")

    check("user-path /data/user/N/... redacted",
        red.redact("/data/user/0/com.example.app/files/secret.db").contains("<redacted:user-path>"))
    check("user-path /storage/emulated/N/... redacted",
        red.redact("/storage/emulated/0/Download/notes.txt").contains("<redacted:user-path>"))
    check("user-path /data/data/<pkg>/... redacted -- the app-private-storage alias Room/SQLite errors use",
        red.redact("unable to open database file: /data/data/com.example.app/databases/x.db").contains("<redacted:user-path>"))
    check("user-path redaction does not leak the package name",
        !red.redact("/data/data/com.example.app/files/secret.db").contains("com.example.app"))
}

// ==================================================================== ClackMetric interop

private fun clackMetricChecks() {
    println("\n== ClackMetric line parser (Clackpad interop) ==")

    fun span(name: String, ms: Double, attrs: Map<String, String> = emptyMap()) =
        ClackMetricLine.Span(name, ms, attrs)

    check("bare name=value parses",
        ClackMetricLine.parseMessage("touch2char=37.4ms") == span("touch2char", 37.4))
    check("ms suffix is optional",
        ClackMetricLine.parseMessage("cold_start=612") == span("cold_start", 612.0))
    check("integer duration parses as a whole double",
        ClackMetricLine.parseMessage("float_build=88ms") == span("float_build", 88.0))
    check("further key=value pairs collected as attrs, not folded into the span",
        ClackMetricLine.parseMessage("recovery_capture=15ms build=debug api=34") ==
            span("recovery_capture", 15.0, mapOf("build" to "debug", "api" to "34")))
    check("no name=value token yields null, not a fabricated span",
        ClackMetricLine.parseMessage("keyboard resumed") == null)
    check("negative duration still parses (a clock-skew artifact should surface, not vanish)",
        ClackMetricLine.parseMessage("cold_start=-3.0ms")?.durationMs == -3.0)

    check("brief-format logcat line parses",
        ClackMetricLine.parseLogcatLine("D/ClackMetric( 8123): touch2char=37.4ms") == span("touch2char", 37.4))
    check("brief-format with a wider tag column still parses",
        ClackMetricLine.parseLogcatLine("D/ClackMetric(12345): cold_start=612ms") == span("cold_start", 612.0))
    check("threadtime-format logcat line parses",
        ClackMetricLine.parseLogcatLine(
            "08-15 09:41:03.118  8123  8123 D ClackMetric: touch2char=37.4ms") == span("touch2char", 37.4))
    check("bare 'TAG: message' parses",
        ClackMetricLine.parseLogcatLine("ClackMetric: float_build=88ms") == span("float_build", 88.0))
    check("a foreign tag is silently not a ClackMetric span, not a parse error",
        ClackMetricLine.parseLogcatLine("D/OtherTag( 8123): touch2char=37.4ms") == null)
    check("a foreign tag in threadtime format is also silently excluded",
        ClackMetricLine.parseLogcatLine(
            "08-15 09:41:03.118  8123  8123 D SomethingElse: touch2char=37.4ms") == null)
    check("gibberish is not a span",
        ClackMetricLine.parseLogcatLine("not a logcat line at all") == null)

    check("format() round-trips through parseMessage()",
        ClackMetricLine.parseMessage(ClackMetricLine.format(span("touch2char", 37.4))) == span("touch2char", 37.4))
    check("format() round-trips with attrs preserved",
        ClackMetricLine.parseMessage(ClackMetricLine.format(span("cold_start", 612.0, mapOf("api" to "34")))) ==
            span("cold_start", 612.0, mapOf("api" to "34")))
    check("format() renders a whole-number duration without a trailing .0",
        ClackMetricLine.format(span("float_build", 88.0)) == "float_build=88ms")
}

// ==================================================================== invariants

private fun invariantChecks() {
    println("\n== Invariant engine (three outcomes, not two) ==")

    fun ev(counters: Map<String, Long> = emptyMap(), facts: Map<String, String> = emptyMap(),
           series: Map<String, SeriesReport> = emptyMap(), recovered: Boolean = false,
           durationSec: Double = 120.0) = Evidence(
        series, counters, facts, emptyMap(),
        SessionInfo("s", null, "iso", durationSec, Trigger.API, recovered = recovered),
        DEVICE)

    val zero = Invariants.counterIsZero("i", "c", "claim", "why")
    check("counterIsZero holds", zero.evaluate(ev(counters = mapOf("c" to 0L))).grade == Grade.PASS)
    check("counterIsZero violated", zero.evaluate(ev(counters = mapOf("c" to 3L))).grade == Grade.FAIL)
    check("counterIsZero NOT EVALUABLE when absent",
        zero.evaluate(ev()).grade == Grade.NA,
        "missing evidence must never read as a pass")
    check("unevaluated says why", zero.evaluate(ev()).observed.contains("never reported"))

    val sev = Invariants.counterIsZero("i", "c", "claim", "why", severity = Grade.WARN)
    check("severity respected", sev.evaluate(ev(counters = mapOf("c" to 1L))).grade == Grade.WARN)

    val oneOf = Invariants.factIsOneOf("i", "delegate", setOf("gpu", "nnapi"), "claim", "why")
    check("factIsOneOf holds", oneOf.evaluate(ev(facts = mapOf("delegate" to "gpu"))).grade == Grade.PASS)
    check("factIsOneOf catches silent CPU fallback",
        oneOf.evaluate(ev(facts = mapOf("delegate" to "cpu"))).grade == Grade.FAIL)
    check("factIsOneOf observed shows allowed set",
        oneOf.evaluate(ev(facts = mapOf("delegate" to "cpu"))).observed.contains("gpu/nnapi"))

    val ratio = Invariants.ratioUnder("i", "dropped", "delivered", 5.0, "claim", "why")
    check("ratio under limit",
        ratio.evaluate(ev(counters = mapOf("dropped" to 2L, "delivered" to 100L))).grade == Grade.PASS)
    check("ratio over limit",
        ratio.evaluate(ev(counters = mapOf("dropped" to 20L, "delivered" to 100L))).grade == Grade.FAIL)
    check("ratio undefined on zero denominator",
        ratio.evaluate(ev(counters = mapOf("dropped" to 0L, "delivered" to 0L))).grade == Grade.NA)

    // The conditional case: vacuous when the feature is off, and reported as such.
    val cond = Invariants.whenFact("i", "byo_ai_enabled", "false",
        then = Invariants.counterIsZero("_", "net.requests", "", ""), statement = "claim", rationale = "why")
    check("conditional vacuous when feature on",
        cond.evaluate(ev(facts = mapOf("byo_ai_enabled" to "true"),
            counters = mapOf("net.requests" to 9L))).grade == Grade.PASS)
    check("conditional says it was not applicable",
        cond.evaluate(ev(facts = mapOf("byo_ai_enabled" to "true"))).observed.contains("not applicable"))
    check("conditional fires when feature off and traffic seen",
        cond.evaluate(ev(facts = mapOf("byo_ai_enabled" to "false"),
            counters = mapOf("net.requests" to 9L))).grade == Grade.FAIL)
    check("conditional passes when feature off and silent",
        cond.evaluate(ev(facts = mapOf("byo_ai_enabled" to "false"),
            counters = mapOf("net.requests" to 0L))).grade == Grade.PASS)

    // seriesPresent distinguishes "absent" from "present but unresolved"
    val sp = Invariants.seriesPresent("i", "frames", 600, "claim", "why")
    check("seriesPresent fails when the source never attached",
        sp.evaluate(ev()).grade == Grade.FAIL)
    check("seriesPresent explains a never-attached source",
        sp.evaluate(ev()).observed.contains("never attached"))
    val unresolved = SeriesReport(SeriesSpec("frames", "F"), 10.0, SeriesStats.EMPTY)
    check("seriesPresent is N/A when budget unresolved",
        sp.evaluate(ev(series = mapOf("frames" to unresolved))).grade == Grade.NA)
    val plenty = SeriesReport(SeriesSpec("frames", "F", 8.33, "8.33 ms"), 10.0,
        SeriesStats.EMPTY.copy(count = 900))
    check("seriesPresent passes with enough observations",
        sp.evaluate(ev(series = mapOf("frames" to plenty))).grade == Grade.PASS)

    // A throwing check must degrade, not crash the export.
    val boom = Invariants.custom("i", "claim", "why") { error("kaboom") }
    check("throwing check degrades to N/A", boom.evaluate(ev()).grade == Grade.NA)
    check("throwing check records the throw", boom.evaluate(ev()).observed.contains("threw"))

    // Payload rules
    val rule = Profiles.stream().payloadRules.first { it.id == "st.no-raw-signal" }
    val clean = "p95 was 4.10 ms and drift 12 ppm across 3 channels"
    val dirty = "samples: 1.02, -3.44, 2.10, 0.98, -1.11, 4.02, 3.31, -0.07, 2.22, 1.19"
    check("payload rule passes clean text", rule.evaluate(clean).grade == Grade.PASS)
    check("payload rule catches a raw signal window", rule.evaluate(dirty).grade == Grade.FAIL)
    check("payload rule reports what it matched", rule.evaluate(dirty).observed.contains("matched"))
    val uv = Profiles.stream().payloadRules.first { it.id == "st.no-microvolt-arrays" }
    check("microvolt array rule catches tagged samples",
        uv.evaluate("12.4 µV, 9").grade == Grade.FAIL)
}

// ==================================================================== profiles

private fun profileChecks() {
    println("\n== Profiles ==")
    val all = Profiles.all()
    check("seven profiles ship", all.size == 7, "got ${all.size}")
    check("ids unique", all.map { it.id }.toSet().size == all.size)
    check("byId resolves every profile", all.all { Profiles.byId(it.id) != null })
    check("byId returns null for unknown", Profiles.byId("nope") == null)
    check("every profile declares at least one series", all.all { it.series.isNotEmpty() })
    check("every profile declares sources", all.all { it.sources.isNotEmpty() })
    check("series ids unique within a profile",
        all.all { p -> p.series.map { it.id }.toSet().size == p.series.size })
    check("invariant ids unique within a profile",
        all.all { p -> p.invariants.map { it.id }.toSet().size == p.invariants.size })
    check("every invariant states a rationale",
        all.all { p -> p.invariants.all { it.rationale.length > 20 } })
    check("every invariant statement is affirmative prose",
        all.all { p -> p.invariants.all { it.statement.isNotBlank() && it.statement.endsWith(".") } })

    // Domain vocabulary must actually differ — a dropped audio buffer called "jank" reads wrong.
    val words = all.flatMap { it.series }.map { it.overrunWord }.toSet()
    check("overrun vocabulary is domain-specific", words.size >= 5, "got $words")
    check("audio calls it an underrun",
        Profiles.audio().spec("audio.callback")!!.overrunWord == "underrun")
    check("audio has no severe threshold — an overrun IS the failure",
        Profiles.audio().spec("audio.callback")!!.severeMs == null)
    check("wallpaper severe threshold is stricter than UI",
        Profiles.wallpaper().spec("frames")!!.severeMs!! < Profiles.ui().spec("frames")!!.severeMs!!)

    // Profiles that measure something the platform cannot supply must say so via invariants.
    check("wallpaper asserts its frames were reported by the engine",
        Profiles.wallpaper().invariants.any { it.id == "wp.frames-collected" })
    check("ime asserts the collector attached to the IME window",
        Profiles.ime().invariants.any { it.id == "ime.window-attached" })
    check("service startup targets are effectively disabled",
        Profiles.service().startup.coldMsGood == Double.MAX_VALUE)
    check("stream startup targets are effectively disabled",
        Profiles.stream().startup.coldMsGood == Double.MAX_VALUE)
    check("long-running profiles demand longer sessions",
        Profiles.service().minSessionSec > Profiles.ui().minSessionSec &&
            Profiles.wallpaper().minSessionSec > Profiles.ui().minSessionSec)
    check("only the stream profile ships payload rules",
        all.filter { it.payloadRules.isNotEmpty() }.map { it.id } == listOf("stream"))
}

// ==================================================================== verdicts

private fun verdictChecks() {
    println("\n== Judge ==")
    val p = Profiles.ui()

    fun withJank(pct: Double): Judgement {
        val n = (pct * 10).toInt()
        val spec = p.spec("frames")!!.resolve(8.33, "8.33 ms (120 Hz)")
        val obs = List(1000) { Observation(it * 0.008, if (it < n) 20.0 else 6.0) }
        return Judge.judge(baseReport(p).copy(
            series = listOf(Stats.seriesReport(obs, spec, 8.0))), p)
    }
    fun jankGrade(pct: Double) = withJank(pct).lines
        .first { it.metric.contains("jank rate") }.grade

    check("jank 4 % passes", jankGrade(4.0) == Grade.PASS)
    check("jank exactly 5 % passes (boundary inclusive)", jankGrade(5.0) == Grade.PASS)
    check("jank 9 % warns", jankGrade(9.0) == Grade.WARN)
    check("jank 20 % fails", jankGrade(20.0) == Grade.FAIL)
    check("every verdict line carries a threshold",
        withJank(9.0).lines.all { it.threshold.isNotBlank() })
    check("every grade has a word, not only a glyph",
        Grade.values().all { it.word.isNotBlank() && it.chip.length > 1 })

    // An unresolved budget must not be judged.
    val unresolved = Judge.judge(baseReport(p).copy(series = listOf(
        SeriesReport(SeriesSpec("frames", "Frames"), 10.0, SeriesStats.EMPTY.copy(count = 500)))), p)
    check("unresolved budget yields N/A, not a verdict",
        unresolved.lines.first().grade == Grade.NA)
    check("unresolved budget raises a caveat",
        unresolved.caveats.any { it.text.contains("no budget") })

    // Trend gating
    fun slopeGrade(slope: Double, r2: Double): Grade {
        val g = TrendGroup("memory", "Memory", "MB", 200, 500,
            listOf(TrendSeries("PSS total", 200.0, 400.0, 400.0, slope, r2)))
        return Judge.judge(baseReport(p).copy(trends = listOf(g)), p)
            .lines.first { it.metric.contains("growth") }.grade
    }
    check("steep slope, tight fit fails", slopeGrade(3.0, 0.95) == Grade.FAIL)
    check("mild slope, tight fit warns", slopeGrade(1.2, 0.95) == Grade.WARN)
    check("flat slope passes", slopeGrade(0.1, 0.95) == Grade.PASS)
    check("steep slope, poor fit is N/A not FAIL", slopeGrade(3.0, 0.30) == Grade.NA,
        "a noisy trend must not be reported as a leak")

    // Confidence
    val clean = baseReport(p).copy(power = PowerSection(
        listOf(ThermalWindow(0.0, ThermalStatus.NONE, 120.0)), 78, 74, false, false))
    check("clean run is HIGH confidence", Judge.judge(clean, p).confidence == Confidence.HIGH)
    val hot = clean.copy(power = clean.power!!.copy(windows = listOf(
        ThermalWindow(0.0, ThermalStatus.NONE, 90.0),
        ThermalWindow(90.0, ThermalStatus.MODERATE, 30.0))))
    check("thermal MODERATE downgrades to MEDIUM", Judge.judge(hot, p).confidence == Confidence.MEDIUM)
    check("throttled seconds counted from MODERATE up", near(hot.power!!.throttledSec, 30.0))
    val bad = hot.copy(power = hot.power!!.copy(charging = true),
        device = DEVICE.copy(refreshHz = 60.0),
        session = hot.session.copy(durationSec = 5.0))
    check("several conditions drop to LOW", Judge.judge(bad, p).confidence == Confidence.LOW)
    check("recovered session raises its own caveat",
        Judge.judge(clean.copy(session = clean.session.copy(recovered = true)), p)
            .caveats.any { it.text.contains("Recovered from a journal") })

    // Invariants participate in the overall grade — a structural break outranks clean timings.
    val wp = Profiles.wallpaper()
    val drawingWhileHidden = baseReport(wp).copy(
        counters = listOf(CounterValue("frames.while_hidden", 4200)))
    val jw = Judge.judge(drawingWhileHidden, wp)
    check("violated invariant drives overall FAIL", jw.overall == Grade.FAIL)
    check("violation is surfaced in the violations list",
        jw.violations.any { it.invariant.id == "wp.no-draw-while-hidden" })
    check("unevaluated invariants are listed separately", jw.unevaluated.isNotEmpty())
    check("unevaluated invariants raise a caveat",
        jw.caveats.any { it.text.contains("could not be evaluated") })

    // Battery drain gating by profile
    val longRun = baseReport(wp).copy(
        session = baseReport(wp).session.copy(durationSec = 1800.0),
        power = PowerSection(emptyList(), 80, 68, false, false))
    check("drain estimated on a long discharging run",
        Judge.judge(longRun, wp).lines.first { it.metric == "Battery drain" }.grade == Grade.PASS)
    check("drain not estimated on a short run",
        Judge.judge(clean, wp).lines.first { it.metric == "Battery drain" }.grade == Grade.NA)
    check("drain not estimated while charging",
        longRun.power!!.copy(charging = true).drainPctPerHour(1800.0) == null)
    check("drain maths", near(longRun.power!!.drainPctPerHour(1800.0)!!, 24.0, 1e-9),
        "12 % over 30 min = 24 %/h")
}

// ==================================================================== journal

private fun journalChecks() {
    println("\n== Journal (crash-survivable) ==")
    val p = Profiles.service()
    val spec = p.spec("request.latency")!!
    val obs = List(500) { Observation(it * 0.2, 400.0 + it) }
    val series = listOf(Stats.seriesReport(obs, spec, 100.0))
    val counters = mapOf("requests" to 500L, "errors" to 2L, "oom" to 0L)
    val facts = mapOf("model.id" to "qwen3-8b", "runtime" to "llama.cpp")
    val trends = listOf(TrendGroup("memory", "Memory", "MB", 200, 500,
        listOf(Stats.trendSeries("PSS total", listOf(0.0, 60.0), listOf(2000.0, 2100.0)))))

    val lines = listOf(
        Journal.header(SessionInfo("sess-1", "load", "2026-08-07 10:00:00 IST", 0.0, Trigger.AUTO),
            AppInfo("com.asystemofcells.asom", "0.3.0", 30, "debug", true), p.id),
        Journal.checkpoint(30.0, series, counters, facts, trends),
        Journal.checkpoint(100.0, series, counters, facts, trends),
    )

    val rec = Journal.parse(lines)!!
    check("header parsed", rec.sessionId == "sess-1" && rec.profileId == "service")
    check("last checkpoint wins", near(rec.lastCheckpointSec, 100.0))
    check("checkpoint count", rec.checkpointCount == 2)
    check("series survived", rec.series.size == 1)
    check("percentiles survived exactly",
        near(rec.series[0].whole.p99, series[0].whole.p99, 1e-4),
        "got ${rec.series[0].whole.p99} vs ${series[0].whole.p99}")
    check("overrun rate recomputed consistently",
        near(rec.series[0].whole.overrunRatePct, series[0].whole.overrunRatePct, 1e-6))
    check("counters survived", rec.counters["requests"] == 500L && rec.counters["oom"] == 0L)
    check("facts survived", rec.facts["model.id"] == "qwen3-8b")
    check("trends survived", rec.trends.size == 1 && rec.trends[0].series.size == 1)
    check("missing END marks an unclean exit", !rec.cleanEnd)

    val clean = Journal.parse(lines + Journal.end(120.0))!!
    check("END marks a clean exit", clean.cleanEnd)

    // The realistic failure: process killed mid-write, leaving a truncated final line.
    val truncated = lines.dropLast(1) + Journal.checkpoint(150.0, series, counters, facts, trends)
        .substring(0, 40)
    val rt = Journal.parse(truncated)!!
    check("truncated final line skipped, not fatal", rt.checkpointCount == 1)
    check("skipped lines counted", rt.skippedLines == 1)
    check("last good checkpoint used", near(rt.lastCheckpointSec, 30.0))

    check("garbage line does not kill the parse",
        Journal.parse(lines + "!!!not a journal line!!!")!!.skippedLines == 1)
    check("no header yields null", Journal.parse(listOf(Journal.end(1.0))) == null)

    val report = Journal.toReport(rt, p, DEVICE)
    check("recovered report is marked recovered", report.session.recovered)
    check("recovered trigger set", report.session.trigger == Trigger.RECOVERED)
    check("recovered report declares what was lost",
        report.notMeasured.any { it.what.contains("after t+") } &&
            report.notMeasured.any { it.what.contains("Worst-observation") } &&
            report.notMeasured.any { it.what.contains("Log ring") })
    check("recovered report declares the unparseable line",
        report.notMeasured.any { it.what.contains("unparseable") })
    val jr = Judge.judge(report, p)
    check("survival invariant warns on a recovered run",
        jr.invariants.first { it.invariant.id == "svc.survived" }.grade == Grade.WARN)
    check("recovered run cannot be HIGH confidence", jr.confidence != Confidence.HIGH)
}

// ==================================================================== reporter

private fun reporterChecks() {
    println("\n== MarkdownReporter ==")
    val p = Profiles.visionPipeline()
    val r = visionReport()
    val md = MarkdownReporter(p).render(r)

    for (s in listOf("summary", "invariants", "caveats", "context", "trends", "lifecycle",
        "thermal", "counters", "logs", "crash", "notmeasured", "end"))
        check("section marker diag:section=$s", md.contains("<!-- diag:section=$s -->"))
    check("per-series markers use the series id",
        md.contains("<!-- diag:section=series.pose.inference -->"))
    check("schema marker leads the file", md.startsWith("<!-- diag:schema=diag/2 -->"))
    check("profile named in the title", md.contains("`vision-pipeline` profile"))
    check("deterministic: two renders are byte-identical", md == MarkdownReporter(p).render(r))

    check("invariants print above caveats",
        md.indexOf("diag:section=invariants") < md.indexOf("diag:section=caveats"))
    check("caveats print above the data",
        md.indexOf("diag:section=caveats") < md.indexOf("diag:section=context"))
    check("violated invariants surface in the summary",
        md.contains("Invariants violated — read these first"))
    check("silent CPU fallback is called out",
        md.contains("accelerated delegate") && md.contains("inference.delegate = cpu"))
    check("rationale printed for non-passing invariants",
        md.contains("falls back to CPU silently"))
    check("unevaluated is explicitly distinguished from passing",
        md.contains("Not evaluated is not the same as passing"))
    check("environment facts tabled", md.contains("### Environment facts"))
    check("counters tabled", md.contains("## Counters"))
    check("units on values", Regex("""\d+\.\d+ ms""").containsMatchIn(md))
    check("not-measured explains absence", md.contains("not because it was zero"))
    check("glyph and word both present", md.contains("✕ FAIL") && md.contains("✓ PASS"))
    check("derived findings are rule-attributed", md.contains("rule:"))
    check("secrets redacted in output", !md.contains("hunter2") && md.contains("<redacted:"))
    check("profile notes carried into the report",
        md.contains("hottest thing a phone does"))
    check("plausible size", md.length in 4_000..80_000, "got ${md.length}")

    // A session label is user-supplied (Diagnostics.startSession(label = ...)) and can carry
    // anything a caller typed, including something secret-shaped -- same threat as a fact or
    // attribute, so it must be redacted the same way.
    val leakyLabel = r.copy(session = r.session.copy(label = "token=hunter2secret"))
    val leakyLabelMd = MarkdownReporter(p).render(leakyLabel)
    check("session label is redacted, not printed raw",
        !leakyLabelMd.contains("hunter2secret") && leakyLabelMd.contains("<redacted:"))

    // Bucket is also caller-supplied per observation (Observation(bucket = ...)); the endpoint/
    // scene/channel a bucket names can itself be secret-shaped (e.g. a URL with a token in it).
    val leakyBucketSpec = p.spec("pose.inference")!!
        .resolve(Stats.rateBudget(30.0), "33.33 ms (one capture period)")
    val leakyBucketObs = List(50) { Observation(it / 30.0, 40.0,
        bucket = "https://api.example.com/x?token=hunter2secret") }
    val leakyBucketReport = r.copy(series = listOf(Stats.seriesReport(leakyBucketObs, leakyBucketSpec, 5.0)))
    val leakyBucketMd = MarkdownReporter(p).render(leakyBucketReport)
    check("bucket column is redacted in the 'By bucket' table and worst-observations table",
        !leakyBucketMd.contains("hunter2secret") && leakyBucketMd.contains("<redacted:"))

    // Diagnostics.mark(name) and beginSpan(name) are just as caller-supplied as a session label
    // or a bucket -- an app can call Diagnostics.mark(someRuntimeString) and there is nothing in
    // the type system stopping that string from being user-typed content on a keyboard host. The
    // Marks and Custom-spans sections were rendering `name` raw while every other free-text field
    // in the report went through the redactor: found by reading Redactor.kt's own claim ("runs
    // over every free-text string that reaches the file") against what MarkdownReporter actually
    // called it on.
    val leakyMarksReport = r.copy(
        marks = listOf(Mark(1.0, "token=hunter2secret")),
        spans = listOf(SpanStat("token=hunter3secret", 1, 5.0, null, 5.0)),
    )
    val leakyMarksMd = MarkdownReporter(p).render(leakyMarksReport)
    check("mark name is redacted in the Marks section",
        !leakyMarksMd.contains("hunter2secret") && leakyMarksMd.contains("<redacted:"))
    check("span name is redacted in the Custom spans table",
        !leakyMarksMd.contains("hunter3secret"))

    java.io.File("/tmp/samples").mkdirs()
    java.io.File("/tmp/samples/SAMPLE_REPORT_vision-pipeline.md").writeText(md)
    // Emit one rendered sample per profile. These are renderer OUTPUT, not hand-written prose —
    // which is the point: the format contract and the implementation cannot drift apart if the
    // documentation is generated by the thing it documents.
    for (p2 in Profiles.all()) {
        java.io.File("/tmp/samples/SAMPLE_REPORT_${p2.id}.md")
            .writeText(MarkdownReporter(p2).render(syntheticFor(p2)))
    }
}

// ==================================================================== cross-profile

/**
 * The real test of the rework: every profile must render a coherent report through the same
 * machinery, with its own vocabulary, and without any profile's assumptions leaking into another.
 */
private fun crossProfileRenderChecks() {
    println("\n== Every profile renders ==")
    for (p in Profiles.all()) {
        val r = syntheticFor(p)
        val rendered = runCatching { MarkdownReporter(p).render(r) }
        if (rendered.isFailure) {
            val e = rendered.exceptionOrNull()!!
            check("profile ${p.id} renders", false, "threw ${e::class.simpleName}: ${e.message}")
            continue
        }
        val md = rendered.getOrThrow()
        check("profile ${p.id} renders", md.length > 2000, "got ${md.length} chars")
        check("profile ${p.id} names itself", md.contains("`${p.id}` profile"))
        check("profile ${p.id} is deterministic", md == MarkdownReporter(p).render(r))
        check("profile ${p.id} uses its own overrun vocabulary",
            p.series.filter { it.overrunWord != "overrun" }
                .all { md.contains(it.overrunWord, ignoreCase = true) })
        check("profile ${p.id} prints an invariants table",
            md.contains("<!-- diag:section=invariants -->"))
        check("profile ${p.id} never claims an unmeasured invariant passed",
            !Regex("""\| *not measured *\| ✓ PASS *\|""").containsMatchIn(md))
    }

    // Vocabulary must not bleed: an audio report should never say "jank".
    val audioMd = MarkdownReporter(Profiles.audio()).render(syntheticFor(Profiles.audio()))
    check("audio report never says 'jank'", !audioMd.contains("jank", ignoreCase = true))
    check("audio report says 'underrun'", audioMd.contains("underrun"))
    val eegMd = MarkdownReporter(Profiles.stream()).render(syntheticFor(Profiles.stream()))
    check("stream report never says 'jank'", !eegMd.contains("jank", ignoreCase = true))
    check("stream report runs its payload rules",
        eegMd.contains("No raw signal values appear in the report"))
    check("stream payload rule passes on a clean report",
        eegMd.contains("| No raw signal values appear in the report. | no matches | ✓ PASS |"))

    // And the payload rule must actually fail a report that leaks signal.
    val leaky = syntheticFor(Profiles.stream()).let { r ->
        r.copy(logs = r.logs + LogEntry("10:00:00.000", Level.DEBUG, "EEG",
            "window: 1.02, -3.44, 2.10, 0.98, -1.11, 4.02, 3.31, -0.07, 2.22, 1.19"))
    }
    val leakyMd = MarkdownReporter(Profiles.stream()).render(leaky)
    check("payload rule fails a report that leaks raw signal",
        leakyMd.contains("| No raw signal values appear in the report. |") &&
            leakyMd.contains("matched:"),
        "the EEG privacy rule must be enforced, not merely stated")
    check("leaked signal escalates the overall grade", leakyMd.contains("**Overall: ✕ FAIL**"))
}

// ==================================================================== fixtures

private val DEVICE = DeviceInfo(
    manufacturer = "nubia", model = "RedMagic 11 Pro", codename = "NX789J", soc = "SM8850",
    androidRelease = "16", apiLevel = 36, abis = listOf("arm64-v8a"),
    totalRamMb = 24576, availRamMbAtStart = 17204,
    widthPx = 2480, heightPx = 1116, densityDpi = 480,
    refreshHz = 120.0, panelMaxHz = 120.0, rooted = false,
)

private fun baseReport(p: Profile) = Report(
    profileId = p.id, profileTitle = p.title,
    session = SessionInfo("diag_test", "fixture", "2026-08-07 14:23:11 IST", 120.0, Trigger.ADB_BROADCAST),
    app = AppInfo("com.asystemofcells.test", "0.1.0", 1, "debug", true, "a3f81c2", true),
    device = DEVICE,
)

private fun visionReport(): Report {
    val p = Profiles.visionPipeline()
    val rnd = Random(11)
    val capture = Stats.rateBudget(30.0)
    val camSpec = p.spec("camera.arrival")!!.resolve(capture, "33.33 ms (30 fps capture)")
    val infSpec = p.spec("pose.inference")!!.resolve(capture, "33.33 ms (one capture period)")
    val e2eSpec = p.spec("pipeline.e2e")!!.resolve(capture * 2, "66.67 ms (two capture periods)")

    val cam = List(900) { Observation(it / 30.0, capture + rnd.nextDouble(-2.0, 3.0), bucket = "1280x720") }
    // Inference on CPU: over budget most of the time, which is exactly the invisible regression.
    val inf = List(900) { Observation(it / 30.0, 38.0 + rnd.nextDouble(0.0, 22.0), bucket = "cpu") }
    val e2e = List(900) { i ->
        val c = 6.0 + rnd.nextDouble(0.0, 3.0); val f = 38.0 + rnd.nextDouble(0.0, 22.0)
        val d = 3.0 + rnd.nextDouble(0.0, 2.0); val rr = 4.0 + rnd.nextDouble(0.0, 3.0)
        Observation(i / 30.0, c + f + d + rr,
            phases = mapOf("camera" to c, "inference" to f, "dsp" to d, "render" to rr))
    }

    val t = (0..120).map { it * 0.5 }
    return baseReport(p).copy(
        session = baseReport(p).session.copy(attributes = mapOf("sport" to "archery", "mode" to "form")),
        series = listOf(
            Stats.seriesReport(cam, camSpec, 30.0),
            Stats.seriesReport(inf, infSpec, 30.0),
            Stats.seriesReport(e2e, e2eSpec, 30.0),
        ),
        trends = listOf(TrendGroup("memory", "Memory", "MB", t.size, 500, listOf(
            Stats.trendSeries("PSS total", t, t.map { 420.0 + 0.9 * it / 60.0 + rnd.nextDouble(-0.3, 0.3) }),
            Stats.trendSeries("PSS graphics", t, t.map { 180.0 + 0.6 * it / 60.0 + rnd.nextDouble(-0.2, 0.2) }),
        ))),
        facts = listOf(
            Fact("inference.delegate", "cpu", "GPU delegate creation failed at init"),
            Fact("camera.target_fps", "30"),
            Fact("pose.model", "blazepose-full"),
        ),
        counters = listOf(
            CounterValue("camera.delivered", 900),
            CounterValue("camera.dropped", 112),
            CounterValue("pose.skipped", 40),
        ),
        power = PowerSection(listOf(
            ThermalWindow(0.0, ThermalStatus.NONE, 18.0),
            ThermalWindow(18.0, ThermalStatus.MODERATE, 12.0)), 82, 79, false, false, 0.4, 0.9),
        startup = StartupSection(940.0, listOf(
            StartupPhase("Process start → Application.onCreate", 210.0, 210.0),
            StartupPhase("Application.onCreate", 300.0, 510.0),
            StartupPhase("→ first frame", 430.0, 940.0)), 280.0, 90.0),
        spans = listOf(Stats.spanStat("model-load", listOf(612.0))),
        marks = listOf(Mark(0.0, "session-start"), Mark(18.0, "thermal-moderate", auto = true)),
        logs = listOf(
            LogEntry("14:24:52.118", Level.WARN, "Pose", "GPU delegate unavailable, using CPU"),
            LogEntry("14:24:55.101", Level.INFO, "Cfg", "password = hunter2"),
        ),
        crashModulePresent = true,
        notMeasured = listOf(NotMeasured("Per-frame GPU timing", "needs EGL frame timestamps")),
    )
}

/** A minimal but coherent run for any profile, so every one can be rendered and diffed. */
private fun syntheticFor(p: Profile): Report {
    val rnd = Random(p.id.hashCode())
    val resolved = p.series.map { spec ->
        val budget = when (spec.id) {
            "frames", "webview.raf" -> Stats.vsyncBudget(120.0)
            "audio.callback" -> Stats.audioBudget(96, 48000)
            "pitch.detect" -> Stats.audioBudget(96, 48000)
            "camera.arrival", "pipeline.e2e" -> Stats.rateBudget(30.0)
            "pose.inference" -> Stats.rateBudget(30.0)
            "stream.interval" -> Stats.rateBudget(256.0)
            "dsp.stage" -> 10.0
            else -> if (spec.budgetMs > 0) spec.budgetMs else 20.0
        }
        spec.resolve(budget, "${Stats.f(budget)} ms (synthetic)")
    }
    val series = resolved.map { spec ->
        val obs = List(800) { i ->
            Observation(i * 0.05, spec.budgetMs * (0.55 + rnd.nextDouble(0.0, 0.35)),
                bucket = if (spec.bucketLabel != null) "b${i % 2}" else null,
                phases = spec.phaseOrder.associateWith { spec.budgetMs / spec.phaseOrder.size })
        }
        Stats.seriesReport(obs, spec, 40.0)
    }
    // Facts and counters chosen so each profile's invariants mostly hold — a green baseline against
    // which a deliberately-broken fixture can be compared.
    val facts = listOf(
        Fact("ime.window_attached", "true"), Fact("byo_ai_enabled", "false"),
        Fact("native.libs", "none"), Fact("audio.path", "low-latency"),
        Fact("audio.sample_rate", "48000"), Fact("audio.native_sample_rate", "48000"),
        Fact("audio.buffer_frames", "96"), Fact("audio.native_buffer_frames", "96"),
        Fact("inference.delegate", "gpu"), Fact("stream.drift_ppm", "40"),
    )
    val counters = listOf(
        CounterValue("net.requests", 0), CounterValue("frames.while_hidden", 0),
        CounterValue("gc.during_draw", 0), CounterValue("audio.underrun", 0),
        CounterValue("camera.delivered", 800), CounterValue("camera.dropped", 8),
        CounterValue("stream.samples", 10240), CounterValue("stream.dropped", 0),
        CounterValue("stream.reconnects", 0), CounterValue("stream.crc_errors", 2),
        CounterValue("requests", 400), CounterValue("errors", 1),
        CounterValue("oom", 0), CounterValue("queue.max_depth", 2),
    )
    val t = (0..80).map { it * 0.5 }
    return baseReport(p).copy(
        session = baseReport(p).session.copy(durationSec = maxOf(p.minSessionSec * 2, 40.0)),
        series = series,
        trends = listOf(TrendGroup("memory", "Memory", "MB", t.size, 500, listOf(
            Stats.trendSeries("PSS total", t, t.map { 300.0 + 0.2 * it / 60.0 + rnd.nextDouble(-0.2, 0.2) })))),
        facts = facts,
        counters = counters,
        power = PowerSection(listOf(ThermalWindow(0.0, ThermalStatus.NONE, 40.0)), 80, 79, false, false),
        startup = StartupSection(700.0, listOf(StartupPhase("→ first frame", 700.0, 700.0)), 300.0, 120.0),
        logs = listOf(LogEntry("10:00:00.000", Level.INFO, "Test", "synthetic run for ${p.id}")),
        crashModulePresent = true,
    )
}

/** JUnit Platform wiring for the checks above — see the file-header KDoc for why this is bucketed by section rather than one-JUnit-case-per-`check()`-call. */
class CoreChecksSuite {

    @BeforeTest
    fun reset() {
        passed = 0
        failed = 0
        failures.clear()
    }

    private fun assertAllPassed(section: String) {
        assertTrue(failed == 0, "$section: $failed check(s) failed -- ${failures.joinToString(", ")}")
    }

    @Test fun stats() { statsChecks(); assertAllPassed("statsChecks") }
    @Test fun series() { seriesChecks(); assertAllPassed("seriesChecks") }
    @Test fun ring() { ringChecks(); assertAllPassed("ringChecks") }
    @Test fun redactor() { redactorChecks(); assertAllPassed("redactorChecks") }
    @Test fun clackMetric() { clackMetricChecks(); assertAllPassed("clackMetricChecks") }
    @Test fun invariants() { invariantChecks(); assertAllPassed("invariantChecks") }
    @Test fun profiles() { profileChecks(); assertAllPassed("profileChecks") }
    @Test fun verdicts() { verdictChecks(); assertAllPassed("verdictChecks") }
    @Test fun journal() { journalChecks(); assertAllPassed("journalChecks") }
    @Test fun reporter() { reporterChecks(); assertAllPassed("reporterChecks") }
    @Test fun crossProfileRender() { crossProfileRenderChecks(); assertAllPassed("crossProfileRenderChecks") }
}
