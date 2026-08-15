package dev.aarso.diagnostics.core

/**
 * The judgement layer. Pure: profile in, measurements in, verdicts out — exhaustively testable with
 * no device attached.
 *
 * Every verdict carries a glyph AND a word AND the threshold that produced it. Never a glyph alone,
 * never a colour alone (WCAG 1.4.1, applied across the constellation), and never a number without
 * the rule that judged it — a reader with no memory of the run has to tell good from bad from the
 * report alone.
 */
enum class Grade(val glyph: String, val word: String) {
    PASS("✓", "PASS"),
    WARN("!", "WARN"),
    FAIL("✕", "FAIL"),
    NA("·", "N/A");

    val chip: String get() = "$glyph $word"
}

data class VerdictLine(
    val metric: String,
    val value: String,
    val threshold: String,
    val grade: Grade,
)

enum class Confidence { HIGH, MEDIUM, LOW }

data class Caveat(val text: String, val downgrades: Boolean)

data class Judgement(
    val lines: List<VerdictLine>,
    val invariants: List<InvariantResult>,
    val confidence: Confidence,
    val caveats: List<Caveat>,
) {
    val overall: Grade
        get() {
            val all = lines.map { it.grade } + invariants.map { it.grade }
            return when {
                all.any { it == Grade.FAIL } -> Grade.FAIL
                all.any { it == Grade.WARN } -> Grade.WARN
                all.any { it == Grade.PASS } -> Grade.PASS
                else -> Grade.NA
            }
        }

    fun count(g: Grade) = lines.count { it.grade == g } + invariants.count { it.grade == g }

    /** Violated invariants outrank threshold misses in a summary — they are structural, not gradual. */
    val violations: List<InvariantResult>
        get() = invariants.filter { it.grade == Grade.FAIL || it.grade == Grade.WARN }

    val unevaluated: List<InvariantResult>
        get() = invariants.filter { it.grade == Grade.NA }
}

object Judge {

    private fun gradeLow(v: Double, good: Double, warn: Double): Grade =
        if (v <= good) Grade.PASS else if (v <= warn) Grade.WARN else Grade.FAIL

    fun judge(r: Report, profile: Profile): Judgement {
        val lines = mutableListOf<VerdictLine>()
        val caveats = mutableListOf<Caveat>()

        // ---------------------------------------------------------------- series
        for (s in r.series) {
            val spec = s.spec
            val t = spec.targets

            if (!spec.resolved) {
                lines += VerdictLine(
                    "${spec.title} — ${spec.overrunWord} rate", "not measured",
                    "budget unresolved — source never supplied one", Grade.NA)
                caveats += Caveat(
                    "**`${spec.id}` has no budget.** The source never resolved one, so its " +
                        "observations are reported without judgement. A fabricated budget would be " +
                        "worse than none.",
                    downgrades = true)
                continue
            }
            if (s.whole.count == 0) {
                lines += VerdictLine("${spec.title} — ${spec.overrunWord} rate", "no observations",
                    "—", Grade.NA)
                continue
            }

            lines += VerdictLine(
                "${spec.title} — ${spec.overrunWord} rate",
                "${Stats.f(s.whole.overrunRatePct)} %",
                "≤ ${Stats.f(t.overrunRatePctGood, 1)} % of observations ${spec.overrunRule}",
                gradeLow(s.whole.overrunRatePct, t.overrunRatePctGood, t.overrunRatePctWarn),
            )

            val mult = s.whole.p99 / spec.budgetMs
            lines += VerdictLine(
                "${spec.title} — p99",
                "${Stats.f(s.whole.p99, 1)} ms",
                "≤ ${Stats.f(spec.budgetMs * t.p99BudgetMultipleGood, 1)} ms " +
                    "(${Stats.f(t.p99BudgetMultipleGood, 1)} × ${spec.budgetLabel})",
                gradeLow(mult, t.p99BudgetMultipleGood, t.p99BudgetMultipleWarn),
            )

            if (spec.severeMs != null) {
                lines += VerdictLine(
                    "${spec.title} — ${spec.severeWord} (> ${Stats.f(spec.severeMs, 0)} ms)",
                    "${s.whole.severeCount}",
                    "${t.severeCountGood}",
                    gradeLow(s.whole.severeCount.toDouble(), t.severeCountGood.toDouble(),
                        t.severeCountWarn.toDouble()),
                )
            }
        }

        // ---------------------------------------------------------------- trends
        for (g in r.trends) {
            val head = g.series.firstOrNull() ?: continue
            // A slope only becomes a finding when the fit supports it. Steep-but-noisy is reported
            // as inconclusive rather than raised as an alarm — false leak reports are expensive.
            val grade = if (head.r2 < g.minR2) Grade.NA
                else gradeLow(head.slopePerMin, g.slopeGoodPerMin, g.slopeWarnPerMin)
            lines += VerdictLine(
                "${g.title} — ${head.name} growth",
                "${Stats.f(head.slopePerMin)} ${g.unit}/min (R² ${Stats.f(head.r2)})",
                if (grade == Grade.NA) "R² < ${Stats.f(g.minR2)} — trend inconclusive"
                else "≤ ${Stats.f(g.slopeGoodPerMin, 1)} ${g.unit}/min",
                grade,
            )
        }

        // ---------------------------------------------------------------- start-up
        val st = r.startup
        if (st != null && profile.startup.coldMsGood < Double.MAX_VALUE) {
            fun line(label: String, v: Double?, good: Double, warn: Double) {
                if (v == null) lines += VerdictLine(label, "not measured", "—", Grade.NA)
                else lines += VerdictLine(label, "${Stats.grp(v.toLong())} ms",
                    "≤ ${Stats.grp(good.toLong())} ms", gradeLow(v, good, warn))
            }
            line("Cold start → first frame", st.coldToFirstFrameMs,
                profile.startup.coldMsGood, profile.startup.coldMsWarn)
            line("Warm start", st.warmMs, profile.startup.warmMsGood, profile.startup.warmMsWarn)
        }

        // ---------------------------------------------------------------- power
        val p = r.power
        if (p != null) {
            val drain = p.drainPctPerHour(r.session.durationSec, profile.minSessionSecForDrain)
            lines += if (drain == null)
                VerdictLine("Battery drain", "not measured",
                    "needs ≥ ${Stats.f(profile.minSessionSecForDrain, 0)} s on battery", Grade.NA)
            else VerdictLine("Battery drain", "${Stats.f(drain, 1)} %/h", "informational", Grade.PASS)
        }

        // ---------------------------------------------------------------- invariants
        val evidence = Evidence.of(r)
        val invariants = profile.invariants.map { it.evaluate(evidence) }

        // ---------------------------------------------------------------- caveats
        if (p != null && p.throttledSec > 0) {
            val pctOf = if (r.session.durationSec > 0)
                p.throttledSec * 100.0 / r.session.durationSec else 0.0
            caveats += Caveat(
                "**Thermal pressure during capture.** Device reached `THERMAL_STATUS_${p.worst}`; " +
                    "${Stats.f(p.throttledSec, 0)} s of the ${Stats.f(r.session.durationSec, 0)} s " +
                    "capture (${Stats.f(pctOf, 0)} %) at MODERATE or worse. Timings from that window " +
                    "describe a throttled device, not your code.",
                downgrades = true)
        }
        if (p != null && p.charging) {
            caveats += Caveat(
                "**Charging during capture.** Clock behaviour differs while charging on many devices; " +
                    "do not compare this run against one on battery.",
                downgrades = true)
        }
        if (r.device.refreshHz < r.device.panelMaxHz - 0.5) {
            caveats += Caveat(
                "**Display below panel maximum** — ran at ${Stats.f(r.device.refreshHz, 1)} Hz of " +
                    "${Stats.f(r.device.panelMaxHz, 1)} Hz. Any frame budget here is derived from the " +
                    "actual rate; comparisons against a run at a different refresh rate are invalid.",
                downgrades = true)
        }
        if (r.session.durationSec < profile.minSessionSec) {
            caveats += Caveat(
                "**Short session for this profile** — ${Stats.f(r.session.durationSec, 1)} s against a " +
                    "${Stats.f(profile.minSessionSec, 0)} s minimum for `${profile.id}`. " +
                    "This profile's failure modes need longer runs to appear.",
                downgrades = true)
        }
        if (r.session.recovered) {
            caveats += Caveat(
                "**Recovered from a journal.** The process did not exit normally, so this report was " +
                    "rebuilt from periodic checkpoints. Counts are truncated at the last checkpoint " +
                    "and everything after it is lost.",
                downgrades = true)
        }
        val unevaluated = invariants.count { it.grade == Grade.NA }
        if (unevaluated > 0) {
            caveats += Caveat(
                "**$unevaluated invariant${if (unevaluated == 1) "" else "s"} could not be evaluated** " +
                    "because the evidence was never collected. Unevaluated is not the same as passing; " +
                    "see the invariants table for which.",
                downgrades = true)
        }
        caveats += Caveat(
            "**Single run.** No variance information. Treat a FAIL as \"worth investigating\", not as " +
                "a measured regression — a p99 can move substantially between runs.",
            downgrades = false)

        val downgrades = caveats.count { it.downgrades }
        val confidence = when {
            downgrades == 0 -> Confidence.HIGH
            downgrades <= 2 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Judgement(lines, invariants, confidence, caveats)
    }
}
