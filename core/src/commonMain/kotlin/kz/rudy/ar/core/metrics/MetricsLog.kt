package kz.rudy.ar.core.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.meta.CostMeter
import kz.rudy.ar.core.meta.Pricing
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.math.ceil
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * Schema of runs/<runId>.json — see .claude/skills/metrics/SKILL.md. Field order matters for
 * readable diffs, so the data classes list fields in the documented order.
 */

@Serializable
data class StageMetrics(
    /** "cutout" | "stylize" | "matte" | "gate" | "rescore" | "sheet" (and "table" | "voice" when those jobs are measured). */
    val name: String,
    val ms: Long,
    val costUsd: Double,
    val attempt: Int,
    val ok: Boolean,
    /**
     * true = [costUsd] is the sum of what the provider's replies reported for this stage (via [MetricsLog.record]);
     * false = the [CostTable] estimate, or a stage without a model call (deterministic matte = 0). Runs written before
     * the field existed parse as false.
     */
    val measured: Boolean = false,
)

@Serializable
data class CharacterMetrics(
    val input: String,
    val stages: List<StageMetrics>,
    val gateScore: Float,
    val gatePassed: Boolean,
    val retries: Int,
    val totalMs: Long,
    val totalCostUsd: Double,
)

@Serializable
data class ScanMetrics(val objects: Int, val ms: Long, val withinCm: List<Float>)

@Serializable
data class Summary(
    val n: Int,
    val gatePassRateFirstTry: Float,
    val p50Ms: Long,
    val p90Ms: Long,
    val costPerCharacterUsd: Double,
)

@Serializable
data class MetricsRun(
    val schema: Int = SCHEMA,
    val runId: String,
    /** "cli" | "app" */
    val source: String,
    /** "meta" | "fixture" | "fireworks" */
    val provider: String,
    /** "Pixel 8" | "mac" */
    val device: String,
    val characters: List<CharacterMetrics>,
    val scans: List<ScanMetrics>,
    val summary: Summary,
) {
    companion object { const val SCHEMA = 1 }
}

/**
 * Per-stage cost estimates in USD, used for `costUsd` when no measured cost was recorded for the stage (offline
 * fixture runs, a reply without `usage`). Calibrated to the published prices in [Pricing]:
 *  - cutout = 2 SAM requests ("drawing", "toy") x $0.0025 = $0.005;
 *  - stylize = 1 Muse image = $0.01;
 *  - score: ~9 images at <= 512 px (sprite + 8 refs) at an assumed ~640 tokens each + ~140 prompt tokens = ~5,900
 *    input tokens x $1.25/M = $0.0074, plus ~300 output (reasoning) tokens x $4.25/M = $0.0013 -> ~$0.009;
 *  - sheet: 1 image (~640) + ~200 prompt tokens = ~840 input x $1.25/M = $0.0011, plus ~900 output tokens (reasoning +
 *    the kk/ru JSON) x $4.25/M = $0.0038 -> ~$0.005;
 *  - table = 5 SAM requests = $0.0125; voice = a ~5 s clip at $0.18/audio hour = $0.00025 -> $0.0003.
 * Spark figures use the standard-tier rates, an upper bound for the contributor tier (see [Pricing]). A first-try
 * character costs 0.005 + 0.01 + 2 * 0.009 + 0.005 = $0.038 (both gate looks run on every attempt), a two-retry
 * character 0.005 + 3 * (0.01 + 2 * 0.009) + 0.005 = $0.094 (the Day 2 gate is <= $0.05 average). The score estimate
 * still assumes 9 images per call; Spark now gets the sprite + 3 refs, so measured score costs come in lower. Keep docs/api-samples/<job>/meta.json `costUsd` in step.
 */
data class CostTable(
    val cutoutUsd: Double = 0.005,
    val stylizeUsd: Double = 0.010,
    val scoreUsd: Double = 0.009,
    val sheetUsd: Double = 0.005,
    val tableUsd: Double = 0.0125,
    val voiceUsd: Double = 0.0003,
)

/** Snapshot of the measured costs recorded for one job ([MetricsLog.costMark]); compare with [MetricsLog.costSince]. */
data class CostMark(val job: String, val calls: Int, val nanoUsd: Long)

/** Cost of one stage: measured from the replies, or the [CostTable] estimate. */
data class StageCost(val usd: Double, val measured: Boolean) {
    companion object {
        fun estimate(usd: Double) = StageCost(usd, measured = false)
    }
}

/**
 * Collects one run. Usage: `startCharacter(input)` -> `stage(...)`* -> `endCharacter(...)`, any number of
 * times, plus `recordScan(...)`; then `toRun()` / `toJson()`. The summary is always computed, never written by hand.
 *
 * As a [CostMeter] (pass it to `MetaApi`), it accumulates the measured cost of every model call per job, from any
 * thread: an immutable map of per-job tallies in nano-USD behind one atomic reference, updated by compare-and-set,
 * so concurrent records sum exactly and no lock or extra dependency is needed. A stage takes its measured cost as
 * [costSince] a [costMark] of its job; the character pipeline's two coroutines use disjoint jobs. Everything else
 * (`stage`, `startCharacter`, ...) is single-threaded: call it from one coroutine.
 */
@OptIn(ExperimentalAtomicApi::class)
class MetricsLog(
    val source: String,
    val provider: String,
    val device: String,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val costs: CostTable = CostTable(),
) : CostMeter {
    val runId: String = runIdFrom(clock())

    private class Tally(val calls: Int, val nanoUsd: Long)

    private val tallies = AtomicReference(emptyMap<String, Tally>())

    /** Adds one measured call of [job] ([ref] is not used for accounting). Thread-safe; negative or NaN costs count as 0. */
    override fun record(job: String, ref: String, usd: Double) {
        val nano = if (usd.isFinite() && usd > 0) round(usd * NANO).toLong() else 0L
        tallies.update { m ->
            val t = m[job]
            m + (job to Tally((t?.calls ?: 0) + 1, (t?.nanoUsd ?: 0L) + nano))
        }
    }

    /** Total measured USD recorded for [job] so far. */
    fun measuredUsd(job: String): Double = (tallies.load()[job]?.nanoUsd ?: 0L) / NANO

    fun costMark(job: String): CostMark {
        val t = tallies.load()[job]
        return CostMark(job, t?.calls ?: 0, t?.nanoUsd ?: 0L)
    }

    /** The cost recorded for [mark]'s job since [mark] when at least one call was recorded since, else [estimateUsd]. */
    fun costSince(mark: CostMark, estimateUsd: Double): StageCost {
        val t = tallies.load()[mark.job]
        if (t == null || t.calls == mark.calls) return StageCost.estimate(estimateUsd)
        return StageCost((t.nanoUsd - mark.nanoUsd) / NANO, measured = true)
    }

    private val characters = ArrayList<CharacterMetrics>()
    private val scans = ArrayList<ScanMetrics>()
    private var currentInput: String? = null
    private var currentStart = 0L
    private val currentStages = ArrayList<StageMetrics>()

    fun now(): Long = clock()

    val characterCount: Int get() = characters.size

    fun startCharacter(input: String) {
        currentInput = input
        currentStart = now()
        currentStages.clear()
    }

    fun stage(name: String, ms: Long, attempt: Int = 1, ok: Boolean = true, costUsd: Double = 0.0, measured: Boolean = false) {
        check(currentInput != null) { "stage('$name') outside startCharacter/endCharacter" }
        currentStages += StageMetrics(name, ms, round4(costUsd), attempt, ok, measured)
    }

    fun stage(name: String, ms: Long, attempt: Int, ok: Boolean, cost: StageCost) =
        stage(name, ms, attempt, ok, cost.usd, cost.measured)

    fun endCharacter(gateScore: Float, gatePassed: Boolean, retries: Int): CharacterMetrics {
        val input = checkNotNull(currentInput) { "endCharacter without startCharacter" }
        val c = CharacterMetrics(
            input = input,
            stages = currentStages.toList(),
            gateScore = round3(gateScore),
            gatePassed = gatePassed,
            retries = retries,
            totalMs = now() - currentStart,
            totalCostUsd = round4(currentStages.sumOf { it.costUsd }),
        )
        characters += c
        currentInput = null
        currentStages.clear()
        return c
    }

    fun recordScan(objects: Int, ms: Long, withinCm: List<Float>) {
        scans += ScanMetrics(objects, ms, withinCm)
    }

    fun toRun(): MetricsRun = MetricsRun(
        runId = runId,
        source = source,
        provider = provider,
        device = device,
        characters = characters.toList(),
        scans = scans.toList(),
        summary = summaryOf(characters),
    )

    fun toJson(): String = json.encodeToString(MetricsRun.serializer(), toRun())

    companion object {
        val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        private const val NANO = 1_000_000_000.0

        /** "2026-10-28T14:03:22Z" -> "2026-10-28T14-03-22Z" (safe as a file name). */
        fun runIdFrom(epochMs: Long): String =
            Instant.fromEpochSeconds(epochMs / 1000).toString().replace(':', '-')

        fun fromJson(text: String): MetricsRun = json.decodeFromString(MetricsRun.serializer(), text)

        fun summaryOf(characters: List<CharacterMetrics>): Summary {
            val n = characters.size
            if (n == 0) return Summary(0, 0f, 0, 0, 0.0)
            val firstTry = characters.count { it.gatePassed && it.retries == 0 }
            val sorted = characters.map { it.totalMs }.sorted()
            return Summary(
                n = n,
                gatePassRateFirstTry = round3(firstTry.toFloat() / n),
                p50Ms = percentile(sorted, 0.5),
                p90Ms = percentile(sorted, 0.9),
                costPerCharacterUsd = round4(characters.sumOf { it.totalCostUsd } / n),
            )
        }

        /** Nearest-rank percentile over an ascending list. */
        fun percentile(sortedMs: List<Long>, p: Double): Long {
            if (sortedMs.isEmpty()) return 0
            val rank = ceil(p * sortedMs.size).toInt().coerceIn(1, sortedMs.size)
            return sortedMs[rank - 1]
        }

        /** Markdown table for the README; one row per run, numbers straight from the files. */
        fun summarise(runs: List<MetricsRun>): String {
            val sb = StringBuilder()
            sb.append("| run | source | provider | device | n | gate pass 1st try | p50 ms | p90 ms | $/character |\n")
            sb.append("|---|---|---|---|---:|---:|---:|---:|---:|\n")
            for (r in runs.sortedBy { it.runId }) {
                val s = r.summary
                sb.append("| ${r.runId} | ${r.source} | ${r.provider} | ${r.device} | ${s.n} | ")
                sb.append("${pct(s.gatePassRateFirstTry)} | ${s.p50Ms} | ${s.p90Ms} | ${money(s.costPerCharacterUsd)} |\n")
            }
            return sb.toString()
        }

        private fun pct(f: Float): String = "${round(f * 1000f) / 10f}%"
        private fun money(d: Double): String {
            val cents = round(d * 10_000).toLong()
            val whole = cents / 10_000
            val frac = (cents % 10_000).toString().padStart(4, '0')
            return "$$whole.$frac"
        }

        fun round3(f: Float): Float = round(f * 1000f) / 1000f
        fun round4(d: Double): Double = round(d * 10_000.0) / 10_000.0
    }
}
