package kz.rudy.ar.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.bundle.BundleEntry
import kz.rudy.ar.core.bundle.BundleIndex
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseFrames
import kz.rudy.ar.core.emote.PoseSpec
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
 *        [--record <fixturesDir> [--force]]] [--out runs] [--frames | --frames-only] [--refs <dir>]
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
 *
 * --frames (T33): after the choreography, models.drawPose draws PoseSpec.DRAWN (hello-1, hello-2, roar-1, roar-2) from
 * the sprite (<= 1024 px) with the Muse style refs (--refs, default docs/api-samples/muse-stylize/refs), one call at a
 * time; each is matted (JvmRaster.matte, no SAM fallback), bounded to PoseFrames.MAX_SIDE and written to
 * <bundle>/<ref>/frames/<emote>-<n>.png; the index entry's `frames` lists them and choreo.json is re-keyed for them
 * (EmoteSet.withFrames). Offline replays muse-pose/<ref>.fixture.json (the T30 spike for toy_dino). A ref whose
 * frames all failed keeps the frames and keys it had; a stage `frames` is recorded either way. --frames-only skips
 * the Spark call and keys the choreo.json the bundle already has (the defaults without one). Worst case for the
 * budget guard grows by PoseSpec.DRAWN.size x CostTable.poseUsd ($0.04).
 */
private const val CHOREO_DEFAULT_FIXTURES = "docs/api-samples"
private const val CHOREO_DEFAULT_BUDGET_USD = 0.20

/**
 * Per-call worst case for the budget guard: the reply's ceiling, 4096 output tokens x $4.25/M ~ $0.0174, plus 1-2k input
 * tokens (prompt + one <= 512 px image). 16 bundled refs x $0.02 still fit the $0.20 default.
 */
private const val CHOREO_WORST_CASE_USD = 0.02
private const val CHOREO_DEFAULT_REFS = "docs/api-samples/muse-stylize/refs"

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
    val framesOnly = flags.has("frames-only")
    val drawFrames = framesOnly || flags.has("frames")
    val styleRefs: List<ByteArray> = if (!drawFrames) emptyList() else {
        val refsDir = File(flags.get("refs") ?: CHOREO_DEFAULT_REFS)
        if (!refsDir.isDirectory) throw CliError("refs dir not found: $refsDir")
        refsDir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }.orEmpty()
            .sortedBy { it.name }.map { JvmRaster.downscale(it.readBytes(), CharacterPipeline.MUSE_INPUT_MAX_SIDE) }
            .also { if (it.isEmpty()) System.err.println("warning: no style refs in $refsDir; Muse sees the sprite only") }
    }

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
    val recordedJobs = listOfNotNull(Jobs.SPARK_CHOREO.takeUnless { framesOnly }, Jobs.MUSE_POSE.takeIf { drawFrames })
    if (recordDir != null) preflightChoreo(recordDir, selected.map { it.ref }, force, recordedJobs)

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

    val worstCaseUsd = (if (framesOnly) 0.0 else CHOREO_WORST_CASE_USD) + (if (drawFrames) PoseSpec.DRAWN.size * metrics.costs.poseUsd else 0.0)
    println(
        "run ${metrics.runId}: choreo ${selected.size} ref(s), provider=$providerName, bundle=${bundleDir.path}" +
            (if (drawFrames) ", frames=${PoseSpec.DRAWN.joinToString(",") { it.name }}" + (if (framesOnly) " (frames only)" else "") else "") +
            (budgetUsd?.let { ", budget=$${fmtUsd(it)} (worst case $${fmtUsd(worstCaseUsd)}/ref)" } ?: "") +
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
                if (budgetUsd != null && spentChoreoUsd(metrics) + worstCaseUsd > budgetUsd) { budgetSkipped += ref; continue }
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
                if (!live && !framesOnly && !File(fixturesDir, "${Jobs.SPARK_CHOREO}/$ref${FixtureApi.SUFFIX}").isFile) {
                    skipped += ref
                    println("  $ref: SKIPPED no fixture ${fixturesDir.path}/${Jobs.SPARK_CHOREO}/$ref${FixtureApi.SUFFIX}")
                    continue
                }
                val sprite = spriteFile.readBytes()
                val choreoFile = File(bundleDir, Bundle.choreoPath(ref))

                metrics.startCharacter(ref)
                var set: EmoteSet?
                var ms = 0L
                var usd = 0.0
                if (framesOnly) {
                    // No Spark call: the frames are keyed into the choreography the bundle already has.
                    set = choreoFile.takeIf { it.isFile }?.let { Bundle.decodeEmotes(it.readText()) } ?: EmoteSet.defaults()
                } else {
                    val image = JvmRaster.downscale(sprite, CharacterPipeline.SHEET_IMAGE_MAX_SIDE)
                    val mark = metrics.costMark(Jobs.SPARK_CHOREO)
                    val t0 = metrics.now()
                    val outcome: Result<EmoteSet> = try {
                        Result.success(models.choreograph(ref, sheet, image))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    ms = metrics.now() - t0
                    val ok = outcome.isSuccess
                    val cost = metrics.costSince(mark, if (ok) metrics.costs.choreoUsd else 0.0)
                    usd = cost.usd
                    metrics.stage(CharacterPipeline.STAGE_CHOREO, ms, 1, ok, cost)
                    set = outcome.getOrElse { e ->
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
                    }
                    if (ok) {
                        succeeded++
                        recordedMs += ms
                        recordedUsd += cost.usd
                    }
                }
                if (set == null) {
                    metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = 0)
                    continue
                }

                // T33: the drawn frames, then the choreography keyed for the ones that came back.
                var framePaths = entry.frames
                var framesNote = ""
                if (drawFrames) {
                    val drawn = drawPoseFrames(models, metrics, ref, sprite, styleRefs)
                    ms += drawn.ms
                    usd += drawn.usd
                    drawn.errors.forEach { println("  $ref: frame $it") }
                    if (drawn.frames.isEmpty) {
                        if (framesOnly) failed++
                        framesNote = " frames=none" + (if (framePaths != null) " (kept the old ones)" else "")
                    } else {
                        val dir = File(bundleDir, ref).resolve("frames")
                        dir.deleteRecursively()
                        dir.mkdirs()
                        for ((name, bytes) in drawn.frames.files()) File(dir, name).writeBytes(bytes)
                        framePaths = Bundle.framePaths(ref, drawn.frames)
                        set = set.withFrames(drawn.frames.counts())
                        if (framesOnly) succeeded++
                        framesNote = " frames=" + drawn.frames.counts().entries.joinToString(",") { "${it.key.name.lowercase()}:${it.value}" } +
                            if (drawn.frames.count < PoseSpec.DRAWN.size) " (partial)" else ""
                    }
                }
                metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = 0)

                choreoFile.parentFile?.mkdirs()
                choreoFile.writeText(Bundle.encodeEmotes(set))
                index = index.upsert(entry.copy(choreo = Bundle.choreoPath(ref), frames = framePaths))
                indexFile.writeText(Bundle.encodeIndex(index))
                val durations = Emote.entries.joinToString(",") { "${it.name.lowercase()}:${set[it].durationMs}" }
                println("  $ref source=${set.source} latency_ms=$ms cost_usd=${fmtUsd(usd)} durations=$durations$framesNote")
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

private class DrawnFrames(val frames: PoseFrames, val ms: Long, val usd: Double, val errors: List<String>)

/**
 * T33: every PoseSpec.DRAWN frame of [sprite], one call at a time (the CLI has no reason to hurry Muse), matted and
 * bounded as the pipeline's `frames` stage does, which it also records in [metrics] (ok = all four came back and
 * matted; cost metered under muse-pose, else poseUsd per frame Muse returned). A call or matte that fails is a
 * missing frame, listed in [DrawnFrames.errors]; an emote's frames are kept from index 0 while contiguous.
 */
private suspend fun drawPoseFrames(models: MetaModels, metrics: MetricsLog, ref: String, sprite: ByteArray, refs: List<ByteArray>): DrawnFrames {
    val t0 = metrics.now()
    val mark = metrics.costMark(Jobs.MUSE_POSE)
    val input = JvmRaster.downscale(sprite, CharacterPipeline.MUSE_INPUT_MAX_SIDE)
    val byEmote = LinkedHashMap<Emote, MutableList<ByteArray?>>()
    val errors = ArrayList<String>()
    var returned = 0
    for (spec in PoseSpec.DRAWN) {
        val png: ByteArray? = try {
            models.drawPose(ref, input, refs, spec).also { returned++ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "${spec.name}: ${e::class.simpleName}: ${e.message}"
            null
        }
        val matted = png?.let { JvmRaster.matte(it) }
        if (png != null && matted == null) errors += "${spec.name}: matte degenerate, frame dropped"
        byEmote.getOrPut(spec.emote) { ArrayList() } += matted?.let { JvmRaster.downscale(it, PoseFrames.MAX_SIDE) }
    }
    val frames = PoseFrames.of(byEmote.mapValues { (_, list) -> list.takeWhile { it != null }.filterNotNull() })
    val ms = metrics.now() - t0
    val cost = metrics.costSince(mark, returned * metrics.costs.poseUsd)
    metrics.stage(CharacterPipeline.STAGE_FRAMES, ms, 1, frames.count == PoseSpec.DRAWN.size, cost)
    return DrawnFrames(frames, ms, cost.usd, errors)
}

/** A synthetic spark-choreo or muse-pose fixture is never overwritten (tests replay it); a real one needs --force. */
private fun preflightChoreo(dir: File, refs: List<String>, force: Boolean, jobs: List<String>) {
    val synthetic = ArrayList<String>()
    val existing = ArrayList<String>()
    for (ref in refs) for (job in jobs) {
        val f = File(dir, "$job/$ref${FixtureApi.SUFFIX}")
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
