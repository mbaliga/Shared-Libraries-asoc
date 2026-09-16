package dev.aarso.diagnostics.core

/**
 * Declarative assertions that must hold for a run to be considered sound.
 *
 * This is the piece that catches what thresholds cannot. A percentile tells you something got
 * slower; an invariant tells you something is structurally wrong in a way no timing number would
 * ever surface:
 *
 *   - the pose-inference delegate silently fell back from GPU to CPU,
 *   - the wallpaper is still drawing frames after onVisibilityChanged(false),
 *   - the low-latency audio path was requested and denied,
 *   - the IME transmitted text while its own privacy copy says it never does.
 *
 * Every one of those is invisible to frame timing right up until it is a crisis, and every one is a
 * one-line assertion over a fact or a counter. They are also the failures most worth catching for
 * an owner who cannot easily verify on-device behaviour by hand.
 *
 * An invariant returns three outcomes, not two: holds, violated, or NOT EVALUABLE. The third is
 * load-bearing — an assertion whose evidence was never collected must report as unevaluated, never
 * quietly as passing. A green report that is green because nothing was measured is worse than no
 * report.
 */
class Invariant(
    val id: String,
    /** The claim, in the affirmative, as it will read in the report. */
    val statement: String,
    /** Why anyone should care. Printed alongside a violation so a reader can act without context. */
    val rationale: String,
    /** Grade applied when the assertion is violated. Not everything is a FAIL. */
    val severity: Grade = Grade.FAIL,
    val check: (Evidence) -> Outcome,
) {
    /** [holds] == null means the evidence was absent, i.e. not evaluable. */
    data class Outcome(val holds: Boolean?, val observed: String)

    fun evaluate(e: Evidence): InvariantResult {
        val o = runCatching { check(e) }
            .getOrElse { Outcome(null, "check threw: ${it::class.simpleName}") }
        val grade = when (o.holds) {
            true -> Grade.PASS
            false -> severity
            null -> Grade.NA
        }
        return InvariantResult(this, grade, o.observed)
    }
}

data class InvariantResult(val invariant: Invariant, val grade: Grade, val observed: String)

/**
 * Asserts over the RENDERED report text rather than over the collected data.
 *
 * The motivating case is real and not hypothetical: the EEG/Baseline work must never let raw signal
 * values reach a file that is designed to be shared. That is a property of the payload, not of any
 * measurement, so it can only be checked after rendering — and it must be checked, because a
 * privacy rule enforced by intention alone is not enforced.
 */
class PayloadRule(
    val id: String,
    val statement: String,
    val forbidden: Regex,
    val severity: Grade = Grade.FAIL,
) {
    fun evaluate(rendered: String): InvariantResult {
        val hits = forbidden.findAll(rendered).map { it.value }.take(3).toList()
        val inv = Invariant(id, statement, "Checked against the rendered report, not the raw data.",
            severity) { Invariant.Outcome(true, "") }
        return InvariantResult(
            inv,
            if (hits.isEmpty()) Grade.PASS else severity,
            if (hits.isEmpty()) "no matches" else "matched: ${hits.joinToString(", ")}",
        )
    }
}

/**
 * Everything an invariant is allowed to see. Deliberately a snapshot of collected data rather than
 * a live handle: a check must not be able to change what it is checking.
 */
class Evidence(
    val series: Map<String, SeriesReport>,
    val counters: Map<String, Long>,
    val facts: Map<String, String>,
    val trends: Map<String, TrendGroup>,
    val session: SessionInfo,
    val device: DeviceInfo,
) {
    companion object {
        fun of(r: Report) = Evidence(
            series = r.series.associateBy { it.spec.id },
            counters = r.counters.associate { it.name to it.value },
            facts = r.facts.associate { it.key to it.value },
            trends = r.trends.associateBy { it.id },
            session = r.session,
            device = r.device,
        )
    }
}

/**
 * Builders, so a profile reads as a list of claims rather than a list of lambdas.
 */
object Invariants {

    fun counterIsZero(id: String, counter: String, statement: String, rationale: String,
                      severity: Grade = Grade.FAIL) = Invariant(id, statement, rationale, severity) { e ->
        val v = e.counters[counter]
        if (v == null) Invariant.Outcome(null, "counter `$counter` never reported")
        else Invariant.Outcome(v == 0L, "$counter = $v")
    }

    fun counterAtMost(id: String, counter: String, max: Long, statement: String, rationale: String,
                      severity: Grade = Grade.FAIL) = Invariant(id, statement, rationale, severity) { e ->
        val v = e.counters[counter]
        if (v == null) Invariant.Outcome(null, "counter `$counter` never reported")
        else Invariant.Outcome(v <= max, "$counter = $v (max $max)")
    }

    fun factEquals(id: String, key: String, expected: String, statement: String, rationale: String,
                   severity: Grade = Grade.FAIL) = Invariant(id, statement, rationale, severity) { e ->
        val v = e.facts[key]
        if (v == null) Invariant.Outcome(null, "fact `$key` never reported")
        else Invariant.Outcome(v == expected, "$key = $v (expected $expected)")
    }

    fun factIsOneOf(id: String, key: String, allowed: Set<String>, statement: String,
                    rationale: String, severity: Grade = Grade.FAIL) =
        Invariant(id, statement, rationale, severity) { e ->
            val v = e.facts[key]
            if (v == null) Invariant.Outcome(null, "fact `$key` never reported")
            else Invariant.Outcome(v in allowed, "$key = $v (allowed: ${allowed.sorted().joinToString("/")})")
        }

    /** Rate ratio between two counters, e.g. dropped vs delivered. */
    fun ratioUnder(id: String, numerator: String, denominator: String, maxPct: Double,
                   statement: String, rationale: String, severity: Grade = Grade.FAIL) =
        Invariant(id, statement, rationale, severity) { e ->
            val n = e.counters[numerator]; val d = e.counters[denominator]
            if (n == null || d == null)
                Invariant.Outcome(null, "needs `$numerator` and `$denominator`")
            else if (d == 0L) Invariant.Outcome(null, "$denominator = 0, ratio undefined")
            else {
                val pct = n * 100.0 / d
                Invariant.Outcome(pct <= maxPct, "${Stats.f(pct)} % (max ${Stats.f(maxPct, 1)} %)")
            }
        }

    /** A series must actually have been collected — catches a source that never attached. */
    fun seriesPresent(id: String, seriesId: String, minCount: Int, statement: String,
                      rationale: String, severity: Grade = Grade.FAIL) =
        Invariant(id, statement, rationale, severity) { e ->
            val s = e.series[seriesId]
            when {
                s == null -> Invariant.Outcome(false, "series `$seriesId` absent — source never attached")
                !s.spec.resolved -> Invariant.Outcome(null, "series `$seriesId` present but budget unresolved")
                else -> Invariant.Outcome(s.whole.count >= minCount,
                    "${Stats.grp(s.whole.count)} observations (min $minCount)")
            }
        }

    /**
     * Conditional assertion: only meaningful when some other fact holds. Used for the Clackpad case
     * — text must not leave the device unless the user explicitly enabled a BYO endpoint, so the
     * assertion is vacuous (and correctly reported as such) when the feature is off.
     */
    fun whenFact(id: String, key: String, value: String, then: Invariant,
                 statement: String, rationale: String) =
        Invariant(id, statement, rationale, then.severity) { e ->
            val v = e.facts[key]
            when {
                v == null -> Invariant.Outcome(null, "fact `$key` never reported")
                v != value -> Invariant.Outcome(true, "not applicable ($key = $v)")
                else -> then.check(e)
            }
        }

    fun custom(id: String, statement: String, rationale: String, severity: Grade = Grade.FAIL,
               check: (Evidence) -> Invariant.Outcome) = Invariant(id, statement, rationale, severity, check)
}
