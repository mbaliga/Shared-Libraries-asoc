package dev.aarso.diagnostics.core

/**
 * Crash-survivable checkpointing.
 *
 * The gap this closes: a session that ends in SIGKILL writes nothing. For ASOM that is not an edge
 * case — a model-serving process on a phone is the most likely thing in the portfolio to meet the
 * low-memory killer, and losing exactly the runs that failed leaves a history that looks healthier
 * than the software is. Same for a wallpaper killed under memory pressure.
 *
 * The design constraint is that checkpointing must be cheap enough to run during capture without
 * appearing in the numbers it records. So the journal does NOT persist observations — writing
 * thousands of frames per checkpoint would cost more than the thing being measured. It persists
 * *aggregates*: counts, percentiles, counters, facts and the latest trend values, which is a
 * bounded, small write on a fixed cadence.
 *
 * What that buys and what it costs is worth being explicit about, because a recovered report must
 * never be mistaken for a complete one: you get the shape of the run up to the last checkpoint, you
 * lose everything after it, and you lose the worst-observation detail entirely. The recovered report
 * is marked as such, its confidence is downgraded, and the `svc.survived` invariant reports WARN.
 *
 * Format is line-oriented text rather than anything structured on purpose: a partially-written final
 * line from a process dying mid-write must not make the whole file unparseable. Unparseable lines
 * are skipped, and the last complete checkpoint wins.
 */
object Journal {

    const val VERSION = "jrnl/1"

    private const val SEP = "\u0001"   // field separator — cannot occur in a log message
    private const val SUB = "\u0002"   // sub-field separator, inside one part
    private const val KV = "="

    // ---------------------------------------------------------------- writing

    fun header(session: SessionInfo, app: AppInfo, profileId: String): String =
        listOf("H", VERSION, session.id, profileId, app.applicationId, app.versionName,
            session.startedAtIso, session.trigger.name).joinToString(SEP)

    /**
     * One checkpoint. Everything needed to rebuild a coarse report, and nothing else.
     * Ordering within the line is stable so the parser never has to guess.
     */
    fun checkpoint(
        tSec: Double,
        series: List<SeriesReport>,
        counters: Map<String, Long>,
        facts: Map<String, String>,
        trends: List<TrendGroup>,
    ): String {
        val parts = mutableListOf("C", Stats.f(tSec, 3))
        for (s in series) {
            parts += "S" + KV + listOf(
                s.spec.id, s.spec.title, Stats.f(s.spec.budgetMs, 4), s.spec.budgetLabel,
                s.spec.overrunWord, s.whole.count.toString(),
                Stats.f(s.whole.p50, 4), Stats.f(s.whole.p90, 4), Stats.f(s.whole.p95, 4),
                Stats.f(s.whole.p99, 4), Stats.f(s.whole.max, 4), Stats.f(s.whole.mean, 4),
                s.whole.overrunCount.toString(), s.whole.severeCount.toString(),
            ).joinToString(SUB)
        }
        for ((k, v) in counters.toSortedMap()) parts += "N$KV$k" + SUB + v
        for ((k, v) in facts.toSortedMap()) parts += "F$KV$k" + SUB + v.replace(SEP, " ").replace(SUB, " ")
        for (g in trends) for (s in g.series) {
            parts += "T" + KV + listOf(g.id, g.title, g.unit, s.name,
                Stats.f(s.start, 3), Stats.f(s.peak, 3), Stats.f(s.end, 3),
                Stats.f(s.slopePerMin, 4), Stats.f(s.r2, 4)).joinToString(SUB)
        }
        return parts.joinToString(SEP)
    }

    /** Written on a clean stop. Its ABSENCE is what marks a journal as belonging to a dead process. */
    fun end(tSec: Double): String = listOf("E", Stats.f(tSec, 3)).joinToString(SEP)

    // ---------------------------------------------------------------- reading

    data class Recovered(
        val sessionId: String,
        val profileId: String,
        val applicationId: String,
        val versionName: String,
        val startedAtIso: String,
        val trigger: Trigger,
        val lastCheckpointSec: Double,
        val series: List<SeriesReport>,
        val counters: Map<String, Long>,
        val facts: Map<String, String>,
        val trends: List<TrendGroup>,
        val cleanEnd: Boolean,
        val checkpointCount: Int,
        val skippedLines: Int,
    )

    /**
     * Rebuilds from whatever survived. A truncated final line is expected, not exceptional — it is
     * the normal shape of a file whose writer was killed — so malformed lines are counted and
     * skipped rather than allowed to fail the parse.
     */
    fun parse(lines: List<String>): Recovered? {
        var sessionId = ""; var profileId = ""; var appId = ""; var version = ""
        var started = ""; var trigger = Trigger.RECOVERED
        var cleanEnd = false
        var checkpoints = 0
        var skipped = 0
        var lastT = 0.0

        var series = listOf<SeriesReport>()
        var counters = mapOf<String, Long>()
        var facts = mapOf<String, String>()
        var trends = listOf<TrendGroup>()

        for (raw in lines) {
            val line = raw.trimEnd('\n', '\r')
            if (line.isBlank()) continue
            val f = line.split(SEP)
            when (f.getOrNull(0)) {
                "H" -> {
                    if (f.size < 8 || f[1] != VERSION) { skipped++; continue }
                    sessionId = f[2]; profileId = f[3]; appId = f[4]; version = f[5]
                    started = f[6]
                    trigger = runCatching { Trigger.valueOf(f[7]) }.getOrDefault(Trigger.RECOVERED)
                }
                "C" -> {
                    val parsed = runCatching { parseCheckpoint(f) }.getOrNull()
                    if (parsed == null) { skipped++; continue }
                    lastT = parsed.first
                    series = parsed.second
                    counters = parsed.third.first
                    facts = parsed.third.second
                    trends = parsed.third.third
                    checkpoints++
                }
                "E" -> cleanEnd = true
                else -> skipped++
            }
        }
        if (sessionId.isEmpty()) return null
        return Recovered(sessionId, profileId, appId, version, started, trigger, lastT,
            series, counters, facts, trends, cleanEnd, checkpoints, skipped)
    }

    private fun parseCheckpoint(f: List<String>):
        Triple<Double, List<SeriesReport>, Triple<Map<String, Long>, Map<String, String>, List<TrendGroup>>> {
        val t = f[1].toDouble()
        val series = mutableListOf<SeriesReport>()
        val counters = mutableMapOf<String, Long>()
        val facts = mutableMapOf<String, String>()
        val trendRows = mutableListOf<Triple<String, String, Pair<String, TrendSeries>>>()

        for (i in 2 until f.size) {
            val part = f[i]
            val tag = part.substringBefore(KV)
            val body = part.substringAfter(KV, "")
            val c = body.split(SUB)
            when (tag) {
                "S" -> {
                    if (c.size < 14) throw IllegalArgumentException("short S")
                    val spec = SeriesSpec(
                        id = c[0], title = c[1], budgetMs = c[2].toDouble(), budgetLabel = c[3],
                        overrunWord = c[4],
                    )
                    val count = c[5].toInt()
                    val stats = SeriesStats(
                        count = count, p50 = c[6].toDouble(), p90 = c[7].toDouble(),
                        p95 = c[8].toDouble(), p99 = c[9].toDouble(), max = c[10].toDouble(),
                        mean = c[11].toDouble(), overrunCount = c[12].toInt(),
                        overrunRatePct = if (count == 0) 0.0 else c[12].toInt() * 100.0 / count,
                        severeCount = c[13].toInt(),
                    )
                    // worst/buckets/phases are deliberately not journalled — see the file comment.
                    series += SeriesReport(spec, t, stats)
                }
                "N" -> if (c.size >= 2) counters[c[0]] = c[1].toLong() else throw IllegalArgumentException("short N")
                "F" -> if (c.size >= 2) facts[c[0]] = c[1] else throw IllegalArgumentException("short F")
                "T" -> {
                    if (c.size < 9) throw IllegalArgumentException("short T")
                    trendRows += Triple(c[0], c[1], c[2] to TrendSeries(
                        name = c[3], start = c[4].toDouble(), peak = c[5].toDouble(),
                        end = c[6].toDouble(), slopePerMin = c[7].toDouble(), r2 = c[8].toDouble()))
                }
                else -> throw IllegalArgumentException("unknown tag $tag")
            }
        }

        val trends = trendRows.groupBy { it.first }.map { (id, rows) ->
            TrendGroup(
                id = id, title = rows.first().second, unit = rows.first().third.first,
                sampleCount = 0, sampleIntervalMs = 0,
                series = rows.map { it.third.second },
                notes = listOf("Recovered from a journal checkpoint — sample count not preserved."),
            )
        }
        return Triple(t, series, Triple(counters, facts, trends))
    }

    /**
     * Turns a recovery into a Report. Everything that could not survive is declared in
     * [Report.notMeasured] rather than left to look absent-and-fine.
     */
    fun toReport(rec: Recovered, profile: Profile, device: DeviceInfo): Report = Report(
        profileId = profile.id,
        profileTitle = profile.title,
        session = SessionInfo(
            id = rec.sessionId, label = "recovered", startedAtIso = rec.startedAtIso,
            durationSec = rec.lastCheckpointSec, trigger = Trigger.RECOVERED, recovered = true),
        app = AppInfo(rec.applicationId, rec.versionName, 0, "debug", true),
        device = device,
        series = rec.series,
        trends = rec.trends,
        facts = rec.facts.map { Fact(it.key, it.value) },
        counters = rec.counters.map { CounterValue(it.key, it.value) },
        notMeasured = listOf(
            NotMeasured("Everything after t+${Stats.f(rec.lastCheckpointSec, 1)} s",
                "the process died before the next checkpoint"),
            NotMeasured("Worst-observation detail and phase breakdowns",
                "not journalled — persisting per-observation data would cost more than the measurement"),
            NotMeasured("Per-bucket splits", "not journalled, same reason"),
            NotMeasured("Log ring", "held in memory only; lost with the process"),
        ) + if (rec.skippedLines > 0) listOf(
            NotMeasured("${rec.skippedLines} unparseable journal line(s)",
                "expected when a process is killed mid-write; the last complete checkpoint was used")
        ) else emptyList(),
    )
}
