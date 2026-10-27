package kz.rudy.ar.core.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.meta.CostMeter
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
    /** "cutout" | "stylize" | "gate" | "sheet" (and "table" | "voice" when those jobs are measured). */
    val name: String,
    val ms: Long,
    val costUsd: Double,
    val attempt: Int,
    val ok: Boolean,
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
 * Per-call cost estimates in USD used for `costUsd` until the Day 1 probe records real prices
 * (then update here and in docs/api-samples/<job>/meta.json together). Defaults are deliberately
 * pessimistic image-model prices: a first-try character costs 0.004 + 0.02 + 0.002 + 0.002 = $0.028,
 * a two-retry character $0.072 (the Day 2 gate is <= $0.05 average).
 */
data class CostTable(
    val cutoutUsd: Double = 0.004,
    val stylizeUsd: Double = 0.020,
    val scoreUsd: Double = 0.002,
    val sheetUsd: Double = 0.002,
    val tableUsd: Double = 0.004,
    val voiceUsd: Double = 0.003,
)

/**
 * Collects one run. Usage: `startCharacter(input)` -> `stage(...)`* -> `endCharacter(...)`, any number of
 * times, plus `recordScan(...)`; then `toRun()` / `toJson()`. The summary is always computed, never written by hand.
 */
class MetricsLog(
    val source: String,
    val provider: String,
    val device: String,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val costs: CostTable = CostTable(),
) : CostMeter {
    val runId: String = runIdFrom(clock())

    /** CONTRACT STUB (Day 2 freeze): measured call costs are dropped until T07 accumulates them per stage. */
    override fun record(job: String, ref: String, usd: Double) {}

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

    fun stage(name: String, ms: Long, attempt: Int = 1, ok: Boolean = true, costUsd: Double = 0.0) {
        check(currentInput != null) { "stage('$name') outside startCharacter/endCharacter" }
        currentStages += StageMetrics(name, ms, round4(costUsd), attempt, ok)
    }

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
