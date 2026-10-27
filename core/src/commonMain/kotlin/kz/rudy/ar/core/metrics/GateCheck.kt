package kz.rudy.ar.core.metrics

import kotlin.math.round

/** Outcome of one gate row, and of the gate as a whole. */
enum class Verdict { PASS, FAIL, UNTESTED, REFUSED }

data class GateRow(val name: String, val observed: String, val threshold: String, val verdict: Verdict, val detail: String = "")

data class GateReport(
    val runId: String,
    val provider: String,
    val n: Int,
    val rows: List<GateRow>,
    val verdict: Verdict,
    /** Why the run is not a measurement (fixture provider), else null. */
    val refusal: String? = null,
) {
    /** The table `metrics gate` prints. */
    fun format(): String {
        val sb = StringBuilder()
        sb.append("Day 2 gate — run $runId (provider=$provider, n=$n)\n")
        sb.append("| check | observed | threshold | verdict |\n")
        sb.append("|---|---:|---|---|\n")
        for (r in rows) {
            val v = if (r.detail.isEmpty()) r.verdict.name else "${r.verdict.name} (${r.detail})"
            sb.append("| ${r.name} | ${r.observed} | ${r.threshold} | $v |\n")
        }
        sb.append("gate: ${verdict.name}")
        if (refusal != null) sb.append(" — ").append(refusal)
        sb.append('\n')
        return sb.toString()
    }
}

/**
 * The Day 2 gate from ai/build-plan.md, evaluated on one `runs/<runId>.json`: ">= 7/10 pass gate first try;
 * p50 < 40 s; <= $0.05/character". Pure; the CLI only prints [GateReport.format]. Do not relax the thresholds.
 *
 * - Pass rate needs `n >= [MIN_N]`; below that it is UNTESTED (the observed rate is still shown).
 * - A run with no characters is UNTESTED on every row.
 * - A `provider: "fixture"` run replays saved replies: it is REFUSED as a measurement on every row.
 * - Overall: REFUSED, else FAIL if any row fails, else UNTESTED if any row is untested, else PASS.
 */
object GateCheck {
    const val MIN_N = 10
    const val MIN_FIRST_TRY_PASS_RATE = 0.7f
    /** p50 must be strictly below this. */
    const val MAX_P50_MS_EXCLUSIVE = 40_000L
    /** Average cost must be at most this. */
    const val MAX_COST_PER_CHARACTER_USD = 0.05
    const val FIXTURE_PROVIDER = "fixture"

    fun check(run: MetricsRun): GateReport {
        val s = run.summary
        val fixture = run.provider == FIXTURE_PROVIDER
        val empty = s.n == 0

        fun verdict(ok: Boolean): Verdict = when {
            fixture -> Verdict.REFUSED
            empty -> Verdict.UNTESTED
            ok -> Verdict.PASS
            else -> Verdict.FAIL
        }

        val rate = when {
            fixture -> Verdict.REFUSED
            s.n < MIN_N -> Verdict.UNTESTED
            else -> verdict(s.gatePassRateFirstTry >= MIN_FIRST_TRY_PASS_RATE)
        }
        val rows = listOf(
            GateRow(
                "gate pass 1st try", pct(s.gatePassRateFirstTry), ">= ${pct(MIN_FIRST_TRY_PASS_RATE)} over n >= $MIN_N", rate,
                if (rate == Verdict.UNTESTED) "n=${s.n}" else "",
            ),
            GateRow("p50", "${s.p50Ms} ms", "< $MAX_P50_MS_EXCLUSIVE ms", verdict(s.p50Ms < MAX_P50_MS_EXCLUSIVE)),
            GateRow(
                "cost per character", money(s.costPerCharacterUsd), "<= ${money(MAX_COST_PER_CHARACTER_USD)}",
                verdict(s.costPerCharacterUsd <= MAX_COST_PER_CHARACTER_USD),
            ),
        )
        val overall = when {
            fixture -> Verdict.REFUSED
            rows.any { it.verdict == Verdict.FAIL } -> Verdict.FAIL
            rows.any { it.verdict == Verdict.UNTESTED } -> Verdict.UNTESTED
            else -> Verdict.PASS
        }
        val refusal = if (fixture) "provider \"fixture\" replays saved replies; not a measurement" else null
        return GateReport(run.runId, run.provider, s.n, rows, overall, refusal)
    }

    private fun pct(f: Float): String = "${round(f * 1000f) / 10f}%"

    private fun money(d: Double): String {
        val units = round(d * 10_000).toLong()
        return "$${units / 10_000}.${(units % 10_000).toString().padStart(4, '0')}"
    }
}
