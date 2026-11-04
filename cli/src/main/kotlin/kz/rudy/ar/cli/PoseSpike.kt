package kz.rudy.ar.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.Provider
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.pipeline.CharacterPipeline
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO

/*
 * pose-spike --bundle <dir> --ref <ref> --out <dir> --live [--budget-usd 0.10] [--refs <dir>] [--runs runs]
 *
 * Cut line 1 go/no-go: can Muse Image draw pose frames that keep the character's identity? Four Muse edits of the
 * bundled sprite through MetaApi.stylize(ref, sprite, refs, prompt) (HELLO x2, ROAR x2), each matted the way the
 * pipeline does (SpriteMatte via JvmRaster.matte, else one SAM cutout pass), saved as <out>/<ref>.<frame>.png; then
 * <out>/contact.png (sprite + the four frames side by side at 256 px height) and <out>/README.md with measured latency
 * and cost per frame and the three questions a human answers (left blank). Live-only. Stops before a call once
 * spent + worst case (Muse + SAM) > --budget-usd. Also writes runs/<runId>.json (stages stylize + matte per frame).
 */
private const val POSE_DEFAULT_BUDGET_USD = 0.10
private const val POSE_DEFAULT_REFS = "docs/api-samples/muse-stylize/refs"
private const val CONTACT_HEIGHT = 256
private const val CONTACT_GAP = 8

private const val POSE_BASE = "the very same character, identical colours, outline and proportions"

/** The matte needs Muse's usual plain white background (SpriteMatte floods it from the border). */
private const val POSE_SUFFIX = ". Keep the drawing style of the first image. Plain white background, nothing else in the picture."

private class PoseFrame(val name: String, val prompt: String)

private val POSE_FRAMES = listOf(
    PoseFrame("hello-1", "$POSE_BASE, waving one arm high"),
    PoseFrame("hello-2", "$POSE_BASE, both arms up, happy"),
    PoseFrame("roar-1", "$POSE_BASE, mouth wide open, roaring, leaning forward"),
    PoseFrame("roar-2", "$POSE_BASE, eyes squeezed, roaring"),
)

private class FrameResult(val frame: PoseFrame, val file: File?, val ms: Long, val usd: Double, val matte: String, val error: String?)

internal fun poseSpike(flags: Flags): Int {
    if (!flags.has("live")) throw CliError("pose-spike is live-only (four paid Muse edits); add --live")
    val bundleDir = File(flags.require("bundle"))
    val ref = flags.require("ref").also(::requireSafeRef)
    val outDir = File(flags.require("out"))
    val runsDir = File(flags.get("runs") ?: "runs")
    val budgetUsd = flags.budget("budget-usd") ?: POSE_DEFAULT_BUDGET_USD
    val refsDir = File(flags.get("refs") ?: POSE_DEFAULT_REFS)
    if (!refsDir.isDirectory) throw CliError("refs dir not found: $refsDir")

    val index = File(bundleDir, Bundle.INDEX).let { f ->
        if (!f.isFile) throw CliError("bundle index not found: ${f.path}")
        try { Bundle.decodeIndex(f.readText()) } catch (e: Exception) { throw CliError("${f.path}: not a bundle index (${e.message})") }
    }
    val entry = index.characters.firstOrNull { it.ref == ref } ?: throw CliError("$ref is not in ${bundleDir.path}/${Bundle.INDEX}")
    val spriteFile = File(bundleDir, entry.sprite)
    if (!spriteFile.isFile) throw CliError("sprite not found: ${spriteFile.path}")
    val sprite = spriteFile.readBytes()
    // Muse sees the refs as the pipeline sends them: <= MUSE_INPUT_MAX_SIDE, at most MetaApi.MAX_STYLE_REFS (MetaApi trims).
    val styleRefs = refsDir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }.orEmpty()
        .sortedBy { it.name }.map { JvmRaster.downscale(it.readBytes(), CharacterPipeline.MUSE_INPUT_MAX_SIDE) }
    if (styleRefs.isEmpty()) System.err.println("warning: no style refs in $refsDir; Muse sees the sprite only")
    val museSprite = JvmRaster.downscale(sprite, CharacterPipeline.MUSE_INPUT_MAX_SIDE)

    val metrics = MetricsLog(source = "cli", provider = Provider.META.metricsName, device = "mac")
    val api = MetaApi(MetaConfig(apiKey = KeySource.read(), provider = Provider.META), costMeter = metrics)
    val c = metrics.costs
    val worstCaseUsd = c.stylizeUsd + c.cutoutUsd
    outDir.mkdirs()
    println("run ${metrics.runId}: pose-spike $ref, ${POSE_FRAMES.size} frames, budget=$${fmtUsd(budgetUsd)} (worst case $${fmtUsd(worstCaseUsd)}/frame), out=${outDir.path}")

    val results = ArrayList<FrameResult>()
    var spent = 0.0
    try {
        runBlocking {
            metrics.startCharacter("$ref.pose")
            for ((i, frame) in POSE_FRAMES.withIndex()) {
                val attempt = i + 1
                if (spent + worstCaseUsd > budgetUsd) {
                    results += FrameResult(frame, null, 0, 0.0, "-", "skipped: budget")
                    println("  ${frame.name}: SKIPPED budget (spent $${fmtUsd(spent)} of $${fmtUsd(budgetUsd)})")
                    continue
                }
                val t0 = metrics.now()
                // Above the try: MetaApi meters a billed reply before decoding it, so a decode failure still costs.
                val museMark = metrics.costMark(Jobs.MUSE_STYLIZE)
                try {
                    val stylized = api.stylize("$ref.${frame.name}", museSprite, styleRefs, frame.prompt + POSE_SUFFIX)
                    val museMs = metrics.now() - t0
                    val museCost = metrics.costSince(museMark, c.stylizeUsd)
                    metrics.stage(CharacterPipeline.STAGE_STYLIZE, museMs, attempt, true, museCost)

                    val m0 = metrics.now()
                    val samMark = metrics.costMark(Jobs.SAM_CUTOUT)
                    var how = "sprite-matte"
                    var matted = JvmRaster.matte(stylized)
                    var samCost = 0.0
                    if (matted == null) {
                        how = "sam"
                        // A failed SAM pass keeps the paid Muse frame (saved unmatted), as the pipeline does.
                        val mask = try {
                            api.segment("$ref.${frame.name}", stylized, Jobs.CUTOUT_PROMPTS).largest()?.takeIf { !it.isEmpty }
                                .also { samCost = metrics.costSince(samMark, c.cutoutUsd).usd }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            samCost = metrics.costSince(samMark, 0.0).usd
                            null
                        }
                        matted = mask?.let { JvmRaster.cutout(stylized, it) }?.takeUnless { it.contentEquals(stylized) }
                        if (matted == null) how = "none"
                    }
                    metrics.stage(CharacterPipeline.STAGE_MATTE, metrics.now() - m0, attempt, how != "none", samCost, measured = samCost > 0)

                    val file = File(outDir, "$ref.${frame.name}.png")
                    // Muse's native 1.4-1.8k px frames are 2-3 MB; stored at Record.OUTPUT_MAX_SIDE like recorded outputs.
                    file.writeBytes(JvmRaster.downscale(matted ?: stylized, Record.OUTPUT_MAX_SIDE))
                    val usd = museCost.usd + samCost
                    spent += usd
                    val ms = metrics.now() - t0
                    results += FrameResult(frame, file, ms, usd, how, null)
                    println("  ${frame.name}: latency_ms=$ms cost_usd=${fmtUsd(usd)} matte=$how -> ${file.path}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val ms = metrics.now() - t0
                    val lost = metrics.costSince(museMark, 0.0)
                    spent += lost.usd
                    metrics.stage(CharacterPipeline.STAGE_STYLIZE, ms, attempt, false, lost)
                    // README.md is committed: the class and HTTP status only, never a reply body (that goes to stdout).
                    val brief = e::class.simpleName.orEmpty() + ((e as? MetaError.Http)?.let { " HTTP ${it.code}" } ?: "")
                    results += FrameResult(frame, null, ms, lost.usd, "-", brief)
                    println("  ${frame.name}: FAILED ${e::class.simpleName}: ${e.message}")
                }
            }
            metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = 0)
        }
    } finally {
        api.close()
        println("wrote ${FileMetricsSink(runsDir).write("${metrics.runId}.json", metrics.toJson())}")
    }

    val contact = File(outDir, "contact.png")
    writeContactSheet(contact, sprite, results)
    println("wrote ${contact.path}")
    val readme = File(outDir, "README.md")
    readme.writeText(poseReadme(ref, spriteFile, metrics.runId, results))
    println("wrote ${readme.path}")
    val made = results.count { it.file != null }
    println("pose-spike: $made/${POSE_FRAMES.size} frames, total cost $${fmtUsd(results.sumOf { it.usd })}")
    return if (made == POSE_FRAMES.size) 0 else 1
}

/** The sprite and every frame scaled to [CONTACT_HEIGHT] px high, side by side on white; a missing frame is a grey box. */
private fun writeContactSheet(target: File, sprite: ByteArray, results: List<FrameResult>) {
    fun load(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    val tiles: List<BufferedImage?> = listOf(load(sprite)) + results.map { r -> r.file?.let { load(it.readBytes()) } }
    val widths = tiles.map { img -> if (img == null || img.height == 0) CONTACT_HEIGHT else (img.width * CONTACT_HEIGHT.toDouble() / img.height).toInt().coerceAtLeast(1) }
    val total = widths.sum() + CONTACT_GAP * (tiles.size + 1)
    val out = BufferedImage(total, CONTACT_HEIGHT + 2 * CONTACT_GAP, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color.WHITE
        g.fillRect(0, 0, out.width, out.height)
        var x = CONTACT_GAP
        for ((i, img) in tiles.withIndex()) {
            if (img == null) {
                g.color = Color.LIGHT_GRAY
                g.fillRect(x, CONTACT_GAP, widths[i], CONTACT_HEIGHT)
            } else {
                g.drawImage(img, x, CONTACT_GAP, widths[i], CONTACT_HEIGHT, null)
            }
            x += widths[i] + CONTACT_GAP
        }
    } finally {
        g.dispose()
    }
    if (!ImageIO.write(out, "png", target)) throw CliError("could not write ${target.path}")
}

private fun poseReadme(ref: String, sprite: File, runId: String, results: List<FrameResult>): String = buildString {
    appendLine("# muse-pose — pose-frame spike (T30, cut line 1)")
    appendLine()
    appendLine("Generated by `cli pose-spike --live` on ${LocalDate.now()} (run `$runId`), character `$ref` (`${sprite.path}`).")
    appendLine("Each frame: one Muse Image edit (`POST /v1/images/edits`, the sprite + the style refs) with the prompt below")
    appendLine("plus \"${POSE_SUFFIX.trim()}\", then the pipeline's sprite matte (SpriteMatte, SAM cutout as fallback).")
    appendLine("`contact.png`: the original sprite, then the frames in this order, at $CONTACT_HEIGHT px height.")
    appendLine()
    appendLine("| frame | file | prompt | latency ms | cost USD | matte |")
    appendLine("|---|---|---|---|---|---|")
    for (r in results) {
        val file = r.file?.name?.let { "`$it`" } ?: "— (${r.error})"
        appendLine("| ${r.frame.name} | $file | ${r.frame.prompt} | ${r.ms} | ${fmtUsd(r.usd)} | ${r.matte} |")
    }
    appendLine()
    appendLine("Total: ${results.sumOf { it.ms }} ms, $${fmtUsd(results.sumOf { it.usd })} (measured from the replies where reported, else the CostTable estimate).")
    appendLine()
    appendLine("## Human answers (fill in on a screen, then decide cut line 1)")
    appendLine()
    appendLine("| frame | same character? | outline and colours kept? | usable as a frame (pose readable at 6 cm)? |")
    appendLine("|---|---|---|---|")
    for (r in results) appendLine("| ${r.frame.name} |  |  |  |")
    appendLine()
    appendLine("Verdict (go / no-go for model-drawn pose frames): ")
}
