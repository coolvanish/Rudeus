package kz.rudy.ar.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.bundle.BundleEntry
import kz.rudy.ar.core.bundle.BundleIndex
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.io.FileFixtureSource
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.Fixture
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.FixtureMeta
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.Provider
import kz.rudy.ar.core.meta.RecordingModels
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.pipeline.CharacterPipeline
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * choreo --bundle <dir> [--ref <ref> | --all] [--fixtures docs/api-samples] [--live [--budget-usd 0.20]
 *        [--record <fixturesDir> [--force]]] [--out runs]
 *
 * For each selected bundled character (<bundle>/index.json): <ref>/sprite.png + <ref>/sheet.json ->
 * models.choreograph(ref, sheet, sprite fitted to CharacterPipeline.SHEET_IMAGE_MAX_SIDE, the image the pipeline's
 * choreography call sees) -> <ref>/choreo.json (Bundle.encodeEmotes = ChoreographyParser.toJson) and the index entry's
 * `choreo` path (BundleIndex.upsert, index rewritten after every ref).
 *
 * Offline (default): FixtureApi over --fixtures replays spark-choreo/<ref>.fixture.json; a missing fixture is reported
 * and the ref skipped. --live: MetaApi (KeySource, never printed), MetricsLog as cost meter, stops starting refs once
 * spent + worst case > --budget-usd. A live call that fails writes the defaults (source=default) only when the ref has
 * no choreo.json yet, as the pipeline ships defaults for a failed call. --record (live-only) wraps MetaApi in
 * RecordingModels (spark-choreo/<ref>.fixture.json) and writes spark-choreo/meta.json once at the end; it refuses to
 * overwrite a synthetic fixture, and a real one without --force, before the first call.
 *
 * One stage `choreo` per ref in runs/<runId>.json (no gate ran: gateScore 0, gatePassed false, retries 0).
 * Exit 0 when at least one ref succeeded and none was skipped for the budget.
 */
private const val CHOREO_DEFAULT_FIXTURES = "docs/api-samples"
private const val CHOREO_DEFAULT_BUDGET_USD = 0.20

/**
 * Per-call worst case for the budget guard: the reply's ceiling, 4096 output tokens x $4.25/M ~ $0.0174, plus 1-2k input
 * tokens (prompt + one <= 512 px image). 16 bundled refs x $0.02 still fit the $0.20 default.
 */
private const val CHOREO_WORST_CASE_USD = 0.02

private val metaJson = Json { prettyPrint = true; encodeDefaults = true }

internal fun choreo(flags: Flags): Int {
    val bundleDir = File(flags.require("bundle"))
    val indexFile = File(bundleDir, Bundle.INDEX)
    if (!indexFile.isFile) throw CliError("bundle index not found: ${indexFile.path}")
    var index: BundleIndex = try { Bundle.decodeIndex(indexFile.readText()) } catch (e: Exception) {
        throw CliError("${indexFile.path}: not a bundle index (${e.message})")
    }
    val live = flags.has("live")
    val recordDir = flags.get("record")?.let(::File)
    if (recordDir != null && !live) throw CliError("--record is live-only (it records real replies); add --live or drop --record")
    val budgetUsd = flags.budget("budget-usd") ?: if (live) CHOREO_DEFAULT_BUDGET_USD else null
    if (!live && budgetUsd != null) System.err.println("warning: --budget-usd is unused offline (fixtures cost nothing)")
    val fixturesDir = File(flags.get("fixtures") ?: CHOREO_DEFAULT_FIXTURES)
    if (!live && !fixturesDir.isDirectory) throw CliError("fixtures dir not found: $fixturesDir")
    val outDir = File(flags.get("out") ?: "runs")
    val force = flags.has("force")

    val selected: List<BundleEntry> = when {
        flags.get("ref") != null -> {
            val ref = flags.require("ref")
            requireSafeRef(ref)
            listOf(index.characters.firstOrNull { it.ref == ref } ?: throw CliError("$ref is not in ${indexFile.path}"))
        }
        flags.has("all") -> index.characters
        else -> throw CliError("give --ref <ref> or --all")
    }
    if (selected.isEmpty()) throw CliError("${indexFile.path} lists no characters")
    selected.forEach { requireSafeRef(it.ref) }

    // Everything that can refuse runs before the first API call.
    if (recordDir != null) preflightChoreo(recordDir, selected.map { it.ref }, force)

    val provider = Provider.META
    val providerName = if (live) provider.metricsName else "fixture"
    val metrics = MetricsLog(source = "cli", provider = providerName, device = "mac")
    var config: MetaConfig? = null
    val models: MetaModels = if (live) {
        val cfg = MetaConfig(apiKey = KeySource.read(), provider = provider).also { config = it }
        val api = MetaApi(cfg, costMeter = metrics)
        if (recordDir != null) RecordingModels(api, Record.writer(recordDir), clock = System::currentTimeMillis) else api
    } else {
        FixtureApi(FileFixtureSource(File("/")), root = fixturesDir.absolutePath)
    }

    println(
        "run ${metrics.runId}: choreo ${selected.size} ref(s), provider=$providerName, bundle=${bundleDir.path}" +
            (budgetUsd?.let { ", budget=$${fmtUsd(it)} (worst case $${fmtUsd(CHOREO_WORST_CASE_USD)}/ref)" } ?: "") +
            (recordDir?.let { ", record=${it.path}" } ?: ""),
    )
    var succeeded = 0
    var failed = 0
    val skipped = ArrayList<String>()
    val budgetSkipped = ArrayList<String>()
    val recordedMs = ArrayList<Long>()
    val recordedUsd = ArrayList<Double>()
    try {
        runBlocking {
            for (entry in selected) {
                val ref = entry.ref
                if (budgetUsd != null && spentChoreoUsd(metrics) + CHOREO_WORST_CASE_USD > budgetUsd) { budgetSkipped += ref; continue }
                val spriteFile = File(bundleDir, entry.sprite)
                val sheetFile = File(bundleDir, entry.sheet)
                if (!spriteFile.isFile || !sheetFile.isFile) {
                    skipped += ref
                    println("  $ref: SKIPPED missing ${if (!spriteFile.isFile) spriteFile.path else sheetFile.path}")
                    continue
                }
                val sheet = try { Bundle.decodeSheet(sheetFile.readText()) } catch (e: Exception) {
                    skipped += ref
                    println("  $ref: SKIPPED ${sheetFile.path} is not a sheet (${e.message})")
                    continue
                }
                if (!live && !File(fixturesDir, "${Jobs.SPARK_CHOREO}/$ref${FixtureApi.SUFFIX}").isFile) {
                    skipped += ref
                    println("  $ref: SKIPPED no fixture ${fixturesDir.path}/${Jobs.SPARK_CHOREO}/$ref${FixtureApi.SUFFIX}")
                    continue
                }
                val image = JvmRaster.downscale(spriteFile.readBytes(), CharacterPipeline.SHEET_IMAGE_MAX_SIDE)

                metrics.startCharacter(ref)
                val mark = metrics.costMark(Jobs.SPARK_CHOREO)
                val t0 = metrics.now()
                val outcome: Result<EmoteSet> = try {
                    Result.success(models.choreograph(ref, sheet, image))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val ms = metrics.now() - t0
                val ok = outcome.isSuccess
                val cost = metrics.costSince(mark, if (ok) metrics.costs.choreoUsd else 0.0)
                metrics.stage(CharacterPipeline.STAGE_CHOREO, ms, 1, ok, cost)
                metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = 0)

                val choreoFile = File(bundleDir, Bundle.choreoPath(ref))
                val set = outcome.getOrElse { e ->
                    failed++
                    val msg = "${e::class.simpleName}: ${e.message}"
                    // Live only: the pipeline ships defaults for a failed call; never replace an existing file with them.
                    if (!live || choreoFile.isFile) {
                        println("  $ref: FAILED $msg (latency_ms=$ms)")
                        null
                    } else {
                        println("  $ref: FAILED $msg -> writing the defaults")
                        EmoteSet.defaults()
                    }
                } ?: continue
                if (ok) {
                    succeeded++
                    recordedMs += ms
                    recordedUsd += cost.usd
                }
                choreoFile.parentFile?.mkdirs()
                choreoFile.writeText(Bundle.encodeEmotes(set))
                index = index.upsert(entry.copy(choreo = Bundle.choreoPath(ref)))
                indexFile.writeText(Bundle.encodeIndex(index))
                val durations = Emote.entries.joinToString(",") { "${it.name.lowercase()}:${set[it].durationMs}" }
                println("  $ref source=${set.source} latency_ms=$ms cost_usd=${fmtUsd(cost.usd)} durations=$durations")
            }
        }
    } finally {
        println("wrote ${FileMetricsSink(outDir).write("${metrics.runId}.json", metrics.toJson())}")
        val cfg = config
        if (recordDir != null && cfg != null && recordedMs.isNotEmpty()) {
            println("wrote ${writeChoreoMeta(recordDir, cfg, recordedMs, recordedUsd)}")
        }
    }
    println("choreo: ok=$succeeded failed=$failed skipped=${skipped.size} -> ${indexFile.path}")
    if (budgetSkipped.isNotEmpty()) {
        println(
            "budget: spent $${fmtUsd(spentChoreoUsd(metrics))} of $${fmtUsd(budgetUsd ?: 0.0)}; worst case " +
                "$${fmtUsd(CHOREO_WORST_CASE_USD)}/ref; skipped ${budgetSkipped.size}: ${budgetSkipped.joinToString()}",
        )
    }
    return if (succeeded > 0 && budgetSkipped.isEmpty()) 0 else 1
}

private fun spentChoreoUsd(metrics: MetricsLog): Double = metrics.toRun().characters.sumOf { it.totalCostUsd }

/** A synthetic spark-choreo fixture is never overwritten (tests replay it); a real one needs --force. */
private fun preflightChoreo(dir: File, refs: List<String>, force: Boolean) {
    val synthetic = ArrayList<String>()
    val existing = ArrayList<String>()
    for (ref in refs) {
        val f = File(dir, "${Jobs.SPARK_CHOREO}/$ref${FixtureApi.SUFFIX}")
        if (!f.isFile) continue
        val isSynthetic = runCatching { FixtureApi.json.decodeFromString(Fixture.serializer(), f.readText()).synthetic }.getOrDefault(false)
        if (isSynthetic) synthetic += f.path else existing += f.path
    }
    if (synthetic.isNotEmpty()) throw CliError("--record would overwrite synthetic fixture(s), which tests rely on:\n  " + synthetic.joinToString("\n  "))
    if (existing.isNotEmpty() && !force) {
        throw CliError("--record would overwrite real recording(s); pass --force to replace them:\n  " + existing.joinToString("\n  "))
    }
}

/** spark-choreo/meta.json of a live recording: median latency, mean measured cost per call, synthetic=false. */
private fun writeChoreoMeta(dir: File, config: MetaConfig, ms: List<Long>, usd: List<Double>): String {
    val f = File(dir, "${Jobs.SPARK_CHOREO}/meta.json")
    val old = f.takeIf { it.isFile }?.let { runCatching { FixtureApi.json.decodeFromString(FixtureMeta.serializer(), it.readText()) }.getOrNull() }
    val sorted = ms.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    val meanUsd = usd.sum() / usd.size
    val meta = FixtureMeta(
        job = Jobs.SPARK_CHOREO,
        endpoint = "POST " + URI(config.url(config.chatPath)).path,
        model = config.sparkModel,
        requestShape = old?.requestShape.orEmpty(),
        latencyMs = median,
        costUsd = "%.5f".format(java.util.Locale.ROOT, meanUsd).toDouble(),
        probedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        synthetic = false,
        notes = "Recorded by cli choreo --live --record on ${LocalDate.now()}: ${ms.size} call(s), latency min ${sorted.first()} / " +
            "median $median / max ${sorted.last()} ms, cost mean $${fmtUsd(meanUsd)} per call (measured from the replies' token " +
            "usage when reported). The fixtures' text is ChoreographyParser.toJson of the sanitised set (RecordingModels), " +
            "not the raw reply. sample-01.fixture.json stays the hand-written synthetic sample (its own synthetic flag).",
    )
    f.parentFile?.mkdirs()
    f.writeText(metaJson.encodeToString(FixtureMeta.serializer(), meta) + "\n")
    return f.path
}
