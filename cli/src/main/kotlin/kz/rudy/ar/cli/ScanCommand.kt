package kz.rudy.ar.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.io.FileFixtureSource
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.Fixture
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.Pricing
import kz.rudy.ar.core.meta.Provider
import kz.rudy.ar.core.meta.SegmentResult
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.pipeline.ScanInput
import kz.rudy.ar.core.pipeline.ScanMasks
import kz.rudy.ar.core.pipeline.SceneBuilder
import kz.rudy.ar.core.pipeline.SceneParams
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.atan
import kotlin.math.tan

/*
 * `scan` (T16): the phone's Day 4 scan (androidApp/.../scan/ScanCapture.kt, runTableScan) from the Mac on a saved
 * table photo, to time it. JPEG <= 1280 px q90 -> MetaModels.segment -> ScanMasks.of -> ScanInput.fromAnchor ->
 * SceneBuilder.build. The phone's real camera pose is replaced by a synthetic one: anchor at the origin (horizontal
 * plane), camera --camera-height-m above it looking at it --pitch-deg down, Intrinsics.fromFov(--hfov-deg). The
 * object positions are therefore illustrative; the timings and the detections are real.
 */

/** Same lists as androidApp's `ScanPrompts` (the CLI cannot depend on :androidApp); keep them in step. */
private val TABLE_PROMPTS = listOf("cup", "book", "phone", "bottle", "hand", "object")
private val FLOOR_EXTRA_PROMPTS = listOf("box", "shoe", "bag", "ball")

/** The phone's `SCAN_MAX_SIDE` / `SCAN_JPEG_QUALITY` (ScanCapture.kt). */
private const val SCAN_MAX_SIDE = 1280
private const val SCAN_JPEG_QUALITY = 0.9f

private const val DEFAULT_SCAN_BUDGET_USD = 0.30
private const val DEFAULT_FIXTURES = "docs/api-samples"
private const val RECORD_NOTE_PREFIX = "recorded by cli scan --live on"

internal fun scan(flags: Flags): Int {
    val image = File(flags.require("image"))
    if (!image.isFile) throw CliError("image not found: $image")
    val live = flags.has("live")
    val floor = flags.has("floor")
    val profile = if (floor) SurfaceProfile.FLOOR else SurfaceProfile.TABLE
    val basePrompts = flags.get("prompts")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: TABLE_PROMPTS
    if (basePrompts.isEmpty()) throw CliError("--prompts is empty")
    val prompts = if (floor) basePrompts + FLOOR_EXTRA_PROMPTS.filter { it !in basePrompts } else basePrompts

    val concurrency = flags.int("concurrency") ?: MetaApi.MAX_SAM_CONCURRENCY
    if (concurrency <= 0) throw CliError("--concurrency must be positive")
    val cameraHeightM = (flags.double("camera-height-m") ?: 0.5).toFloat()
    val pitchDeg = flags.double("pitch-deg") ?: 45.0
    val hfovDeg = flags.double("hfov-deg") ?: 60.0
    if (!(cameraHeightM > 0f)) throw CliError("--camera-height-m must be > 0")
    if (!(pitchDeg > 0.0 && pitchDeg <= 90.0)) throw CliError("--pitch-deg must be in (0, 90]")
    if (!(hfovDeg > 0.0 && hfovDeg < 180.0)) throw CliError("--hfov-deg must be in (0, 180)")
    val repeat = flags.int("repeat") ?: 1
    if (repeat < 1) throw CliError("--repeat must be >= 1")
    val outDir = File(flags.get("out") ?: "runs")

    val provider = when (flags.get("provider")?.lowercase()) {
        null, "meta" -> Provider.META
        "fireworks" -> Provider.FIREWORKS
        else -> throw CliError("--provider must be meta or fireworks")
    }
    val recordDir = flags.get("record")?.let(::File)
    if (recordDir != null && !live) throw CliError("--record is live-only (it records real replies); add --live or drop --record")
    val budgetUsd = flags.budget("budget-usd") ?: if (live) DEFAULT_SCAN_BUDGET_USD else null
    if (!live && budgetUsd != null) System.err.println("warning: --budget-usd is unused offline (fixtures cost nothing)")
    if (!live && flags.get("concurrency") != null) System.err.println("warning: --concurrency is unused offline (fixtures answer at once)")

    val ref = flags.get("ref") ?: if (live) image.nameWithoutExtension.removeSuffix(".input") else throw CliError("offline needs --ref <fixture ref>")
    requireSafeRef(ref)
    if (recordDir != null) preflightScanRecord(recordDir, ref, flags.has("force"))

    // Same JPEG the phone sends: upright, <= 1280 px on the long side, q90.
    val src = if (ImageConvert.needed(image)) ImageConvert.convert(image) else image
    val prepStart = System.nanoTime()
    val (jpeg, jpegW, jpegH) = scanJpeg(src.readBytes())
    val jpegMs = msSince(prepStart)

    val providerName = if (live) provider.metricsName else "fixture"
    val metrics = MetricsLog(source = "cli", provider = providerName, device = "mac")
    val models: MetaModels = if (live) {
        MetaApi(MetaConfig(apiKey = KeySource.read(), provider = provider, samConcurrency = concurrency), costMeter = metrics)
    } else {
        val fixturesDir = File(flags.get("fixtures") ?: DEFAULT_FIXTURES)
        if (!fixturesDir.isDirectory) throw CliError("fixtures dir not found: $fixturesDir")
        FixtureApi(FileFixtureSource(File("/")), root = fixturesDir.absolutePath)
    }
    val worstUsd = prompts.size * Pricing.SAM_USD_PER_REQUEST

    println(
        "scan run ${metrics.runId}: ref=$ref provider=$providerName prompts=${prompts.joinToString(",")} " +
            "concurrency=${if (live) concurrency.toString() else "-"} profile=${profile.surface} jpeg=${jpegW}x$jpegH " +
            "bytes=${jpeg.size} jpeg_ms=$jpegMs" + (budgetUsd?.let { " budget=$${fmtUsd(it)} (worst $${fmtUsd(worstUsd)}/scan)" } ?: "") +
            (recordDir?.let { " record=${it.path}" } ?: ""),
    )

    val camera = syntheticCamera(cameraHeightM, Math.toRadians(pitchDeg).toFloat())
    val hfovRad = Math.toRadians(hfovDeg).toFloat()
    val segmentMs = ArrayList<Long>()
    val scanMs = ArrayList<Long>()
    var failures = 0
    var skipped = 0
    var recorded = false
    try {
        runBlocking {
            for (i in 1..repeat) {
                if (budgetUsd != null && metrics.measuredUsd(Jobs.segmentJob(prompts)) + worstUsd > budgetUsd) { skipped = repeat - i + 1; break }
                try {
                    val t0 = System.nanoTime()
                    val result = models.segment(ref, jpeg, prompts)
                    val segMs = msSince(t0)
                    val masks = ScanMasks.of(result)
                    // As on the phone: masks come back in SAM's grid (= the JPEG's); intrinsics follow that grid.
                    val grid = masks.firstOrNull()?.mask
                    val w = grid?.width ?: jpegW
                    val h = grid?.height ?: jpegH
                    // --hfov-deg spans the frame's LONG side (the phone sends landscape sensor frames, where that is the
                    // width); the same rule as Day4ScenarioTest, so both print the same geometry for a portrait photo.
                    val fovRad = if (w >= h) hfovRad else 2f * atan(tan(hfovRad / 2f) * w / h)
                    val intrinsics = Intrinsics.fromFov(fovRad, w, h)
                    val input = ScanInput.fromAnchor(Pose3.IDENTITY, camera, intrinsics, masks, planePolygonWorld = null)
                    val level = SceneBuilder(SceneParams()).build(input, previous = null, profile = profile)
                    val totalMs = msSince(t0)
                    metrics.recordScan(level.objects.size, totalMs, withinCm = emptyList())
                    segmentMs += segMs
                    scanMs += totalMs
                    println(
                        "[$i/$repeat] segment_ms=$segMs scan_ms=$totalMs objects=${level.objects.size} masks=${masks.size} " +
                            "failed=${result.failedPrompts.joinToString(",").ifEmpty { "-" }}",
                    )
                    if (i == 1) printScan(result, level)
                    if (recordDir != null && !recorded) {
                        // A scan with a failed prompt (429, timeout) is not a fixture: replayed, it would read as "nothing found".
                        if (result.failedPrompts.isEmpty()) {
                            writeScanFixture(recordDir, ref, jpeg, jpegW, jpegH, result, segMs, concurrency)
                            recorded = true
                        } else {
                            println("not recorded: failed prompts ${result.failedPrompts.joinToString(",")}; the next clean repeat is")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    println("[$i/$repeat] FAILED ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    } finally {
        println("wrote ${FileMetricsSink(outDir).write("${metrics.runId}.json", metrics.toJson())}")
    }
    if (segmentMs.size > 1) println("median segment_ms=${median(segmentMs)} scan_ms=${median(scanMs)} over ${segmentMs.size} runs")
    if (live) println("spent $${fmtUsd(metrics.measuredUsd(Jobs.segmentJob(prompts)))}" + (budgetUsd?.let { " of $${fmtUsd(it)}" } ?: ""))
    if (skipped > 0) println("budget: skipped $skipped repeat(s); each scan may cost $${fmtUsd(worstUsd)}")
    return if (failures == 0 && skipped == 0) 0 else 1
}

/** Camera [heightM] above the origin on the +Z side, looking at the origin [pitchRad] below the horizon. */
private fun syntheticCamera(heightM: Float, pitchRad: Float): Pose3 =
    Pose3.lookAt(Vec3(0f, heightM, heightM / tan(pitchRad)), Vec3.ZERO)

private fun printScan(result: SegmentResult, level: Level) {
    val failed = result.failedPrompts.toSet()
    for (m in result.masks) {
        val parts = m.instances.ifEmpty { if (m.isEmpty) emptyList() else listOf(m) }
        val best = parts.mapNotNull { it.score }.maxOrNull()
        val what = when {
            m.label in failed -> "FAILED"
            parts.isEmpty() -> "absent"
            else -> "instances=${parts.size} best=${best?.let { "%.2f".format(Locale.ROOT, it) } ?: "-"}"
        }
        println("  prompt ${m.label}: $what")
    }
    for (o in level.objects) {
        val c = o.centroid
        println(
            "  object %s %s %s c=(%.1f, %.1f) cm h=%.1f cm n=%d".format(
                Locale.ROOT, o.id, o.label, o.kind, c.u * 100, c.v * 100, o.heightM * 100, o.footprint.size,
            ),
        )
    }
}

/** Upright, at most [SCAN_MAX_SIDE] px on the long side (progressive bilinear halving, as JvmRaster), JPEG q90. */
private fun scanJpeg(bytes: ByteArray): Triple<ByteArray, Int, Int> {
    val src = ImageIO.read(ByteArrayInputStream(JvmRaster.upright(bytes))) ?: throw CliError("cannot decode the image")
    val longSide = maxOf(src.width, src.height)
    val scale = minOf(1.0, SCAN_MAX_SIDE.toDouble() / longSide)
    val tw = maxOf(1, Math.round(src.width * scale).toInt())
    val th = maxOf(1, Math.round(src.height * scale).toInt())
    var img = src
    var w = src.width
    var h = src.height
    do {
        w = maxOf(tw, w / 2); h = maxOf(th, h / 2)
        val next = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = next.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        img = next
    } while (w != tw || h != th)
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val out = ByteArrayOutputStream()
    try {
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = SCAN_JPEG_QUALITY
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
    } finally {
        writer.dispose()
    }
    return Triple(out.toByteArray(), tw, th)
}

private fun scanFixtureFile(dir: File, ref: String) = File(dir, "${Jobs.SAM_TABLE}/$ref${FixtureApi.SUFFIX}")
private fun scanInputFile(dir: File, ref: String) = File(dir, "${Jobs.SAM_TABLE}/$ref.input.jpg")

/** Before any call: a synthetic fixture is never replaced; a real one, or an input copy, only with --force. */
/** Fixtures the core tests load; `--record` never touches them, with or without --force. */
private val PROTECTED_SCAN_REFS = setOf("table-01", "sample-01", "sample-02")

private fun preflightScanRecord(dir: File, ref: String, force: Boolean) {
    if (ref in PROTECTED_SCAN_REFS) throw CliError("--record must not touch $ref (core tests load it); pick another --ref")
    val fx = scanFixtureFile(dir, ref)
    if (fx.isFile) {
        val synthetic = runCatching { FixtureApi.json.decodeFromString(Fixture.serializer(), fx.readText()).synthetic }.getOrDefault(false)
        if (synthetic) throw CliError("--record would overwrite synthetic fixture ${fx.path}, which tests rely on; pick another --ref")
    }
    val existing = listOf(fx, scanInputFile(dir, ref)).filter { it.isFile }
    if (existing.isNotEmpty() && !force) {
        throw CliError("--record would overwrite ${existing.joinToString { it.path }}; pick another --ref or pass --force")
    }
}

/**
 * `sam-table/<ref>.fixture.json` (masks with their instances, latency = this segment call) plus the JPEG SAM saw as
 * `<ref>.input.jpg`, so `scan --fixtures <dir> --ref <ref> --image <that jpg>` replays it. Only masks are stored:
 * no headers, no request body, nothing to redact beyond what [FixtureApi] keeps.
 */
private fun writeScanFixture(
    dir: File, ref: String, jpeg: ByteArray, w: Int, h: Int, result: SegmentResult, segmentMs: Long, concurrency: Int,
) {
    val failed = if (result.failedPrompts.isEmpty()) "" else "; FAILED (empty masks): ${result.failedPrompts.joinToString()}"
    val fx = Fixture(
        job = Jobs.SAM_TABLE,
        ref = ref,
        synthetic = false,
        note = "$RECORD_NOTE_PREFIX ${LocalDate.now()}; ${result.masks.size} prompts at concurrency $concurrency; " +
            "input $ref.input.jpg ${w}x$h q90$failed",
        masks = result.masks,
        latencyMs = segmentMs,
    )
    val target = scanFixtureFile(dir, ref)
    target.parentFile.mkdirs()
    target.writeText(FixtureApi.json.encodeToString(Fixture.serializer(), fx) + "\n")
    scanInputFile(dir, ref).writeBytes(jpeg)
    println("recorded ${target.path} + ${scanInputFile(dir, ref).path}")
}

private fun msSince(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

private fun median(xs: List<Long>): Long {
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
