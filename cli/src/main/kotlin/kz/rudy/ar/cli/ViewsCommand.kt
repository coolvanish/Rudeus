package kz.rudy.ar.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.emote.SpriteViews
import kz.rudy.ar.core.io.FileFixtureSource
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.Fixture
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.FixtureMeta
import kz.rudy.ar.core.meta.FixtureWriter
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.Provider
import kz.rudy.ar.core.meta.RecordingModels
import kz.rudy.ar.core.meta.SheetParser
import kz.rudy.ar.core.meta.ViewPrompts
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.pipeline.CharacterPipeline
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO

/*
 * views --ref <ref> [--bundle docs/bundled] [--fixtures docs/api-samples] [--refs <dir>] [--out build/views] [--runs <dir>]
 *       [--live [--budget-usd 0.05] [--record [<fixturesDir>] [--force]]]
 *
 * T41, the G3 spike: can Muse draw a character from the other sides and keep it? Takes one character, from the bundle
 * (<bundle>/index.json lists <ref>: its sprite.png + sheet.json) or else from the fixtures (muse-stylize/<ref> output,
 * matted, + spark-sheet/<ref>), asks models.drawView for SpriteViews.missing(sheet.baseView ?: SIDE), one call at a
 * time, with the sprite at <= 1024 px and the Muse style refs (--refs, default <fixtures>/muse-stylize/refs). Each
 * view is matted as the pipeline's `views` stage does (JvmRaster.matte, no SAM fallback), bounded to
 * SpriteViews.MAX_SIDE and written to <out>/<ref>/view-<view>.png (a view whose matte fails is kept unmatted as
 * view-<view>.raw.png for the human, and does not count). Then <out>/<ref>/contact.png (the base sprite and every view
 * side by side at 256 px height, labelled) and <out>/<ref>/README.md (prompt, latency, cost, matte per view and the
 * questions a human answers, left blank). One stage `views` in <runs>/<runId>.json (default: the out dir).
 *
 * Offline (default): FixtureApi replays muse-view/<ref>.fixture.json (sample-01 is hand-made). --live: MetaApi (key
 * from KeySource, never printed), stops before a call once spent + CostTable.viewUsd > --budget-usd. --record
 * (live-only) wraps it in RecordingModels: muse-view/<ref>.view-<view>.png (<= Record.OUTPUT_MAX_SIDE) + fixture, and
 * muse-view/meta.json with the measured figures; it refuses a synthetic fixture, and a real one without --force,
 * before the first call. Exit 0 only when every missing view came back and matted.
 */
private const val VIEWS_DEFAULT_FIXTURES = "docs/api-samples"
private const val VIEWS_DEFAULT_BUNDLE = "docs/bundled"
private const val VIEWS_DEFAULT_OUT = "build/views"
private const val VIEWS_DEFAULT_BUDGET_USD = 0.05
private const val VIEWS_CONTACT_HEIGHT = 256
private const val VIEWS_CONTACT_GAP = 8
private const val VIEWS_LABEL_HEIGHT = 22

private val viewsMetaJson = Json { prettyPrint = true; encodeDefaults = true }

/** One character to draw views of: its sprite (matted), its sheet, where they came from. */
private class ViewSubject(val sprite: ByteArray, val sheet: CharacterSheet?, val from: String)

private class ViewResult(val view: SpriteView, val file: File?, val raw: File?, val ms: Long, val usd: Double, val matte: String, val error: String?)

internal fun views(flags: Flags): Int {
    val ref = flags.require("ref").also(::requireSafeRef)
    val fixturesDir = File(flags.get("fixtures") ?: VIEWS_DEFAULT_FIXTURES)
    val bundleDir = File(flags.get("bundle") ?: VIEWS_DEFAULT_BUNDLE)
    val outDir = File(flags.get("out") ?: VIEWS_DEFAULT_OUT)
    val runsDir = flags.get("runs")?.let(::File) ?: outDir
    val live = flags.has("live")
    val force = flags.has("force")
    val recordDir: File? = flags.get("record")?.let(::File) ?: if (flags.has("record")) fixturesDir else null
    if (recordDir != null && !live) throw CliError("--record is live-only (it records real replies); add --live or drop --record")
    val budgetUsd = flags.budget("budget-usd") ?: if (live) VIEWS_DEFAULT_BUDGET_USD else null
    if (!live && budgetUsd != null) System.err.println("warning: --budget-usd is unused offline (fixtures cost nothing)")
    if (!live && !fixturesDir.isDirectory) throw CliError("fixtures dir not found: $fixturesDir")

    val fixtureApi = FixtureApi(FileFixtureSource(File("/")), root = fixturesDir.absolutePath)
    val subject = viewSubject(ref, bundleDir, fixturesDir, fixtureApi)
    val base = subject.sheet?.baseView ?: SpriteView.SIDE
    val facesRight = subject.sheet?.facesRight ?: false
    val wanted = SpriteViews.missing(base)

    val refsDir = File(flags.get("refs") ?: File(fixturesDir, "${Jobs.MUSE_STYLIZE}/refs").path)
    if (!refsDir.isDirectory) throw CliError("refs dir not found: $refsDir")
    // Muse sees the refs as the pipeline sends them: <= MUSE_INPUT_MAX_SIDE, at most MetaApi.MAX_STYLE_REFS (MetaApi trims).
    val styleRefs = refsDir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }.orEmpty()
        .sortedBy { it.name }.map { JvmRaster.downscale(it.readBytes(), CharacterPipeline.MUSE_INPUT_MAX_SIDE) }
    if (styleRefs.isEmpty()) System.err.println("warning: no style refs in $refsDir; Muse sees the sprite only")
    val museSprite = JvmRaster.downscale(subject.sprite, CharacterPipeline.MUSE_INPUT_MAX_SIDE)

    // Everything that can refuse runs before the first API call.
    if (recordDir != null) preflightViews(recordDir, ref, force)

    val metrics = MetricsLog(source = "cli", provider = if (live) Provider.META.metricsName else "fixture", device = "mac")
    var config: MetaConfig? = null
    var api: MetaApi? = null
    val models: MetaModels = if (live) {
        val cfg = MetaConfig(apiKey = KeySource.read(), provider = Provider.META).also { config = it }
        val meta = MetaApi(cfg, costMeter = metrics).also { api = it }
        if (recordDir != null) RecordingModels(meta, viewsWriter(recordDir), clock = System::currentTimeMillis) else meta
    } else {
        fixtureApi
    }
    val c = metrics.costs
    val dir = File(outDir, ref).apply { mkdirs() }
    println(
        "run ${metrics.runId}: views $ref (${subject.from}), base=${base.name.lowercase()}${if (facesRight) " looking right" else ""}, " +
            "drawing ${wanted.joinToString(",") { it.name.lowercase() }}, provider=${metrics.provider}" +
            (budgetUsd?.let { ", budget=$${fmtUsd(it)} (worst case $${fmtUsd(c.viewUsd)}/view)" } ?: "") +
            (recordDir?.let { ", record=${it.path}" } ?: "") + ", out=${dir.path}",
    )

    val results = ArrayList<ViewResult>()
    try {
        runBlocking {
            if (recordDir != null && force) clearViews(recordDir, ref)
            metrics.startCharacter("$ref.views")
            val t0 = metrics.now()
            val mark = metrics.costMark(Jobs.MUSE_VIEW)
            var returned = 0
            for (view in wanted) {
                val spent = metrics.costSince(mark, returned * c.viewUsd).usd
                if (budgetUsd != null && spent + c.viewUsd > budgetUsd) {
                    results += ViewResult(view, null, null, 0, 0.0, "-", "skipped: budget")
                    println("  ${view.name.lowercase()}: SKIPPED budget (spent $${fmtUsd(spent)} of $${fmtUsd(budgetUsd)})")
                    continue
                }
                val v0 = metrics.now()
                val viewMark = metrics.costMark(Jobs.MUSE_VIEW)
                val png = try {
                    models.drawView(ref, museSprite, styleRefs, view).also { returned++ }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val ms = metrics.now() - v0
                    // The committed README gets the class and HTTP status only, never a reply body (that goes to stdout).
                    val brief = e::class.simpleName.orEmpty() + ((e as? MetaError.Http)?.let { " HTTP ${it.code}" } ?: "")
                    results += ViewResult(view, null, null, ms, metrics.costSince(viewMark, 0.0).usd, "-", brief)
                    println("  ${view.name.lowercase()}: FAILED ${e::class.simpleName}: ${e.message}")
                    continue
                }
                val ms = metrics.now() - v0
                val usd = metrics.costSince(viewMark, c.viewUsd).usd
                val matted = JvmRaster.matte(png)
                if (matted == null) {
                    val raw = File(dir, SpriteViews.fileName(view).replace(".png", ".raw.png"))
                    raw.writeBytes(JvmRaster.downscale(png, SpriteViews.MAX_SIDE))
                    results += ViewResult(view, null, raw, ms, usd, "degenerate", "matte degenerate, view dropped")
                    println("  ${view.name.lowercase()}: latency_ms=$ms cost_usd=${fmtUsd(usd)} matte=degenerate -> ${raw.path} (not a view)")
                } else {
                    val file = File(dir, SpriteViews.fileName(view))
                    file.writeBytes(JvmRaster.downscale(matted, SpriteViews.MAX_SIDE))
                    results += ViewResult(view, file, null, ms, usd, "sprite-matte", null)
                    println("  ${view.name.lowercase()}: latency_ms=$ms cost_usd=${fmtUsd(usd)} matte=sprite-matte -> ${file.path}")
                }
            }
            val drawn = results.count { it.file != null }
            metrics.stage(CharacterPipeline.STAGE_VIEWS, metrics.now() - t0, 1, drawn > 0, metrics.costSince(mark, returned * c.viewUsd))
            metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = 0)
        }
    } finally {
        api?.close()
        println("wrote ${FileMetricsSink(runsDir).write("${metrics.runId}.json", metrics.toJson())}")
        val cfg = config
        val measured = results.filter { it.file != null || it.raw != null }
        if (recordDir != null && cfg != null && measured.isNotEmpty()) println("wrote ${writeViewsMeta(recordDir, cfg, measured)}")
    }

    val contact = File(dir, "contact.png")
    writeViewsContactSheet(contact, subject.sprite, base, facesRight, results)
    println("wrote ${contact.path}")
    val readme = File(dir, "README.md")
    readme.writeText(viewsReadme(ref, subject, base, facesRight, metrics.runId, live, results))
    println("wrote ${readme.path}")
    val made = results.count { it.file != null }
    println("views: $made/${wanted.size} drawn, total cost $${fmtUsd(results.sumOf { it.usd })}")
    return if (made == wanted.size) 0 else 1
}

/** The bundled character [ref] when the bundle lists it, else the fixture character (its stylize output, matted). */
private fun viewSubject(ref: String, bundleDir: File, fixturesDir: File, fixtures: FixtureApi): ViewSubject {
    val indexFile = File(bundleDir, Bundle.INDEX)
    val entry = indexFile.takeIf { it.isFile }?.let { f ->
        try { Bundle.decodeIndex(f.readText()) } catch (e: Exception) { throw CliError("${f.path}: not a bundle index (${e.message})") }
    }?.characters?.firstOrNull { it.ref == ref }
    if (entry != null) {
        val spriteFile = File(bundleDir, entry.sprite)
        val sheetFile = File(bundleDir, entry.sheet)
        if (!spriteFile.isFile) throw CliError("sprite not found: ${spriteFile.path}")
        val sheet = sheetFile.takeIf { it.isFile }?.let { f ->
            try { Bundle.decodeSheet(f.readText()) } catch (e: Exception) { throw CliError("${f.path}: not a sheet (${e.message})") }
        }
        return ViewSubject(spriteFile.readBytes(), sheet, "bundle ${bundleDir.path}")
    }
    // A fixture character: the stylize output the pipeline would ship (first attempt), matted as the pipeline does.
    val stylized = try {
        runBlocking { fixtures.stylize(ref, ByteArray(0), emptyList()) }
    } catch (e: MetaError) {
        throw CliError("$ref is not in ${indexFile.path} and has no ${Jobs.MUSE_STYLIZE} fixture in ${fixturesDir.path} (${e.message})")
    }
    val sheet = try {
        runBlocking { SheetParser.normalise(fixtures.characterSheet(ref, "")) }
    } catch (e: MetaError) {
        System.err.println("warning: no ${Jobs.SPARK_SHEET} fixture for $ref; treating the sprite as a left-looking SIDE view")
        null
    }
    return ViewSubject(JvmRaster.matte(stylized) ?: stylized, sheet, "fixtures ${fixturesDir.path}")
}

/** Like Record.writer: recorded Muse outputs are stored at Record.OUTPUT_MAX_SIDE; fixtures as they are. */
private fun viewsWriter(dir: File): FixtureWriter = FixtureWriter { path, bytes ->
    val f = File(dir, path)
    f.parentFile?.mkdirs()
    val isOutput = path.startsWith("${Jobs.MUSE_VIEW}/") && path.endsWith(".png")
    f.writeBytes(if (isOutput) JvmRaster.downscale(bytes, Record.OUTPUT_MAX_SIDE) else bytes)
}

private fun viewFixture(dir: File, ref: String) = File(dir, "${Jobs.MUSE_VIEW}/$ref${FixtureApi.SUFFIX}")

private fun viewOutputs(dir: File, ref: String): List<File> = SpriteView.entries.map { File(dir, "${Jobs.MUSE_VIEW}/$ref.${SpriteViews.fileName(it)}") }

/** A synthetic muse-view fixture is never overwritten (tests replay it); a real recording needs --force. */
private fun preflightViews(dir: File, ref: String, force: Boolean) {
    val f = viewFixture(dir, ref)
    if (f.isFile) {
        val synthetic = runCatching { FixtureApi.json.decodeFromString(Fixture.serializer(), f.readText()).synthetic }.getOrDefault(false)
        if (synthetic) throw CliError("--record would overwrite the synthetic fixture ${f.path}, which tests rely on; record another ref")
    }
    val existing = (listOf(f) + viewOutputs(dir, ref)).filter { it.isFile }
    if (existing.isNotEmpty() && !force) {
        throw CliError("--record would overwrite real recording(s); pass --force to replace them:\n  " + existing.joinToString("\n  ") { it.path })
    }
}

/** With --force, right before the paid calls: the old recording goes, so the new one never mixes with it. */
private fun clearViews(dir: File, ref: String) {
    (listOf(viewFixture(dir, ref)) + viewOutputs(dir, ref)).filter { it.isFile }.forEach { it.delete() }
}

/** muse-view/meta.json of a live recording: median latency and mean cost of the calls that returned, synthetic=false. */
private fun writeViewsMeta(dir: File, config: MetaConfig, results: List<ViewResult>): String {
    val f = File(dir, "${Jobs.MUSE_VIEW}/meta.json")
    val old = f.takeIf { it.isFile }?.let { runCatching { FixtureApi.json.decodeFromString(FixtureMeta.serializer(), it.readText()) }.getOrNull() }
    val sorted = results.map { it.ms }.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    val meanUsd = results.sumOf { it.usd } / results.size
    val meta = FixtureMeta(
        job = Jobs.MUSE_VIEW,
        endpoint = "POST " + URI(config.url(config.stylizePath)).path,
        model = config.museImageModel,
        requestShape = old?.requestShape.orEmpty(),
        latencyMs = median,
        costUsd = "%.5f".format(java.util.Locale.ROOT, meanUsd).toDouble(),
        probedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        synthetic = false,
        notes = "Recorded by cli views --live --record on ${LocalDate.now()}: ${results.size} view(s), latency min ${sorted.first()} / " +
            "median $median / max ${sorted.last()} ms, cost mean $${fmtUsd(meanUsd)} per view (measured where reported, else the " +
            "CostTable estimate). The human verdict for G3 lives in the README.md next to each contact.png. sample-01.fixture.json " +
            "stays the hand-made synthetic sample (its own synthetic flag).",
    )
    f.parentFile?.mkdirs()
    f.writeText(viewsMetaJson.encodeToString(FixtureMeta.serializer(), meta) + "\n")
    return f.path
}

/**
 * The base sprite, then every view in [results] order, each scaled to [VIEWS_CONTACT_HEIGHT] px high on white with a
 * label underneath; a view that failed is a grey box, one whose matte failed shows the unmatted picture.
 */
private fun writeViewsContactSheet(target: File, sprite: ByteArray, base: SpriteView, facesRight: Boolean, results: List<ViewResult>) {
    fun load(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    val baseLabel = "base: ${base.name.lowercase()}" + if (base == SpriteView.SIDE) (if (facesRight) ", looks right" else ", looks left") else ""
    val tiles: List<Pair<BufferedImage?, String>> = listOf(load(sprite) to baseLabel) + results.map { r ->
        val img = (r.file ?: r.raw)?.let { load(it.readBytes()) }
        img to when {
            r.file != null -> r.view.name.lowercase()
            r.raw != null -> "${r.view.name.lowercase()} (matte failed)"
            else -> "${r.view.name.lowercase()}: ${r.error ?: "missing"}"
        }
    }
    val widths = tiles.map { (img, _) -> if (img == null || img.height == 0) VIEWS_CONTACT_HEIGHT else (img.width * VIEWS_CONTACT_HEIGHT.toDouble() / img.height).toInt().coerceAtLeast(1) }
    val total = widths.sum() + VIEWS_CONTACT_GAP * (tiles.size + 1)
    val out = BufferedImage(total, VIEWS_CONTACT_HEIGHT + VIEWS_LABEL_HEIGHT + 2 * VIEWS_CONTACT_GAP, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, out.width, out.height)
        var x = VIEWS_CONTACT_GAP
        for ((i, tile) in tiles.withIndex()) {
            val (img, label) = tile
            if (img == null) {
                g.color = Color.LIGHT_GRAY
                g.fillRect(x, VIEWS_CONTACT_GAP, widths[i], VIEWS_CONTACT_HEIGHT)
            } else {
                g.drawImage(img, x, VIEWS_CONTACT_GAP, widths[i], VIEWS_CONTACT_HEIGHT, null)
            }
            // A machine without fonts still gets the pictures; the labels are a convenience.
            runCatching {
                g.color = Color.DARK_GRAY
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
                g.drawString(label, x, VIEWS_CONTACT_GAP + VIEWS_CONTACT_HEIGHT + VIEWS_LABEL_HEIGHT - 6)
            }
            x += widths[i] + VIEWS_CONTACT_GAP
        }
    } finally {
        g.dispose()
    }
    if (!ImageIO.write(out, "png", target)) throw CliError("could not write ${target.path}")
}

private fun viewsReadme(ref: String, subject: ViewSubject, base: SpriteView, facesRight: Boolean, runId: String, live: Boolean, results: List<ViewResult>): String = buildString {
    appendLine("# muse-view — sprite views of `$ref` (T41, gate G3)")
    appendLine()
    appendLine(
        "Generated by `cli views${if (live) " --live" else ""}` on ${LocalDate.now()} (run `$runId`) from ${subject.from}; " +
            "base view ${base.name.lowercase()}${if (base == SpriteView.SIDE) if (facesRight) " looking right" else " looking left" else ""}.",
    )
    if (!live) appendLine("Offline: the pictures are fixture replays, not Muse's work; judge only a `--live` run.")
    appendLine("Each view: one Muse Image edit (`POST /v1/images/edits`, the sprite + the style refs) with the prompt below, then the")
    appendLine("pipeline's sprite matte (no SAM fallback). `contact.png`: the base sprite, then the views in this order, at $VIEWS_CONTACT_HEIGHT px height.")
    appendLine()
    appendLine("| view | file | prompt | latency ms | cost USD | matte |")
    appendLine("|---|---|---|---|---|---|")
    for (r in results) {
        val file = r.file?.name?.let { "`$it`" } ?: r.raw?.name?.let { "`$it` (${r.error})" } ?: "— (${r.error})"
        appendLine("| ${r.view.name.lowercase()} | $file | ${ViewPrompts.view(r.view)} | ${r.ms} | ${fmtUsd(r.usd)} | ${r.matte} |")
    }
    appendLine()
    appendLine("Total: ${results.sumOf { it.ms }} ms, $${fmtUsd(results.sumOf { it.usd })} (measured from the replies where reported, else the CostTable estimate).")
    appendLine()
    appendLine("## Human answers (fill in on a screen; G3 needs >= 4 of 5 characters with a usable BACK and FRONT)")
    appendLine()
    appendLine("| view | same character? | outline and colours kept? | reads as that side at 6 cm? |")
    appendLine("|---|---|---|---|")
    for (r in results) appendLine("| ${r.view.name.lowercase()} |  |  |  |")
    appendLine()
    appendLine("Verdict (usable / not usable, per view): ")
}
