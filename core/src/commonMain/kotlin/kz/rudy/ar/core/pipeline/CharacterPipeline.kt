package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.SheetParser
import kz.rudy.ar.core.metrics.MetricsLog

/** Output of [CharacterPipeline.run]. `gatePassed=false` means the sprite shipped after exhausting retries. */
data class CharacterResult(
    val ref: String,
    /** Stylized sprite, PNG bytes; matted (transparent background) when the pipeline has a `spriteMatter`. */
    val sprite: ByteArray,
    val sheet: CharacterSheet,
    val gateScore: Float,
    val gatePassed: Boolean,
    /** Stylize+gate attempts made (1 = first try). */
    val attempts: Int,
    val cutoutMask: MaskResult?,
)

/**
 * photo -> cutout (SAM) -> stylize (Muse) -> gate (Spark score + deterministic half) -> retry stylize
 * up to [maxRetries] times while the gate fails. The character sheet (Spark vision on the cutout) starts right after
 * the cutout and runs concurrently with the stylize/gate loop; a failed sheet call ships [SheetParser.fallback]
 * instead of failing the character (cutout, stylize and gate failures still fail it). Every stage lands in [metrics]
 * with its duration, attempt number, success flag and cost: the measured cost when the models report to [metrics]
 * as their `CostMeter` (`MetaApi(config, client, costMeter = metrics)`), else the `metrics.costs` estimate. The sheet
 * stage is recorded after the loop, so the stage order in the JSON is deterministic.
 *
 * [rasterDecoder] (e.g. `JvmRaster::decode`) enables the deterministic gate half; without it the model
 * score alone decides. [cutoutComposer] turns (photo, mask) into the transparent PNG Muse receives; without
 * it the photo is sent as is (Muse still gets the mask via the prompt-less edit; wire `JvmRaster.cutout`
 * on the JVM and a Bitmap-based one on Android).
 *
 * [spriteMatter] (e.g. `JvmRaster::matte`) cuts the opaque white background Muse always returns; it runs after every
 * stylize attempt (stage `matte`) and the gate scores the matted sprite. When it returns null (degenerate matte) the
 * stylized image gets one SAM pass ([Jobs.CUTOUT_PROMPTS] + [cutoutComposer]); if that fails too the unmatted sprite
 * ships and the `matte` stage is recorded with `ok=false`.
 *
 * Only the calling coroutine touches [metrics]; the sheet coroutine reads nothing but `metrics.now()` (the clock must
 * be safe to read from any thread; the default system clock is). One [run] at a time per instance.
 */
class CharacterPipeline(
    private val models: MetaModels,
    private val gate: StyleGate,
    private val metrics: MetricsLog,
    private val rasterDecoder: ((ByteArray) -> RasterImage?)? = null,
    private val maxRetries: Int = 2,
    private val cutoutComposer: ((image: ByteArray, mask: MaskResult) -> ByteArray)? = null,
    private val spriteMatter: ((ByteArray) -> ByteArray?)? = null,
) {
    init { require(maxRetries >= 0) }

    suspend fun run(ref: String, image: ByteArray, styleRefs: List<ByteArray>, description: String): CharacterResult {
        metrics.startCharacter(ref)
        var attempt = 0
        try {
            return coroutineScope {
                // 1. cutout
                val mask = stage(STAGE_CUTOUT, 1, Jobs.SAM_CUTOUT, metrics.costs.cutoutUsd) {
                    val result = models.segment(ref, image, Jobs.CUTOUT_PROMPTS)
                    result.largest()?.takeIf { !it.isEmpty }
                }
                val cutout = if (mask != null && cutoutComposer != null) cutoutComposer.invoke(image, mask) else image

                // 4. sheet: starts now, runs concurrently with 2-3, recorded after the loop. Its job (spark-sheet) is
                // disjoint from the loop's, so its measured cost is simply everything recorded for the job since now.
                val sheetMark = metrics.costMark(Jobs.SPARK_SHEET)
                val sheetCall = async { sheet(ref, description, cutout) }

                // 2-3. stylize + matte + gate, with retries
                val refRasters: List<RasterImage?> = rasterDecoder?.let { d -> styleRefs.map { d(it) } } ?: emptyList()
                var sprite: ByteArray = ByteArray(0)
                var score = 0f
                var passed = false
                while (true) {
                    attempt++
                    val stylized = stage(STAGE_STYLIZE, attempt, Jobs.MUSE_STYLIZE, metrics.costs.stylizeUsd) {
                        models.stylize(ref, cutout, styleRefs)
                    }
                    sprite = matte(ref, stylized, attempt)
                    val t0 = metrics.now()
                    val gateMark = metrics.costMark(Jobs.SPARK_SCORE)
                    try {
                        val modelScore = models.scoreStyle(ref, sprite, styleRefs)
                        val deterministic = rasterDecoder?.let { d -> gate.deterministicScore(d(sprite), refRasters) }
                        score = gate.combine(modelScore, deterministic)
                        passed = gate.passes(score)
                        metrics.stage(STAGE_GATE, metrics.now() - t0, attempt, passed, metrics.costSince(gateMark, metrics.costs.scoreUsd))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        metrics.stage(STAGE_GATE, metrics.now() - t0, attempt, false, metrics.costSince(gateMark, metrics.costs.scoreUsd))
                        throw e
                    }
                    if (passed || attempt > maxRetries) break
                }

                val sheet = sheetCall.await()
                metrics.stage(STAGE_SHEET, sheet.ms, 1, sheet.ok, metrics.costSince(sheetMark, metrics.costs.sheetUsd))

                metrics.endCharacter(gateScore = score, gatePassed = passed, retries = attempt - 1)
                CharacterResult(ref, sprite, sheet.sheet, score, passed, attempt, mask)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = (attempt - 1).coerceAtLeast(0))
            throw if (e is MetaError) e else MetaError.Provider("$ref: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private class SheetOutcome(val sheet: CharacterSheet, val ms: Long, val ok: Boolean)

    /** The sheet call, in its own coroutine. Never throws except on cancellation: a failure yields the fallback sheet. */
    private suspend fun sheet(ref: String, description: String, image: ByteArray): SheetOutcome {
        val t0 = metrics.now()
        return try {
            val sheet = SheetParser.normalise(models.characterSheet(ref, description, image))
            SheetOutcome(sheet, metrics.now() - t0, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SheetOutcome(SheetParser.fallback(ref), metrics.now() - t0, false)
        }
    }

    /** Stage `matte`: [spriteMatter], else one SAM pass on the stylized image, else the unmatted sprite. */
    private suspend fun matte(ref: String, stylized: ByteArray, attempt: Int): ByteArray {
        val matter = spriteMatter ?: return stylized
        val t0 = metrics.now()
        matter(stylized)?.let {
            metrics.stage(STAGE_MATTE, metrics.now() - t0, attempt, true, 0.0)
            return it
        }
        // SAM fallback: sequential with the cutout (same job), so the mark isolates this pass.
        val samMark = metrics.costMark(Jobs.SAM_CUTOUT)
        var samEstimate = 0.0
        val viaSam = try {
            val mask = models.segment(ref, stylized, Jobs.CUTOUT_PROMPTS).largest()?.takeIf { !it.isEmpty }
            samEstimate = metrics.costs.cutoutUsd
            mask?.let { m -> cutoutComposer?.invoke(stylized, m) }?.takeUnless { it.contentEquals(stylized) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        metrics.stage(STAGE_MATTE, metrics.now() - t0, attempt, viaSam != null, metrics.costSince(samMark, samEstimate))
        return viaSam ?: stylized
    }

    /** Runs [block] as stage [name]; its cost is what [job] recorded meanwhile, else [estimateUsd]. */
    private suspend inline fun <T> stage(name: String, attempt: Int, job: String, estimateUsd: Double, block: () -> T): T {
        val t0 = metrics.now()
        val mark = metrics.costMark(job)
        try {
            val r = block()
            metrics.stage(name, metrics.now() - t0, attempt, true, metrics.costSince(mark, estimateUsd))
            return r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            metrics.stage(name, metrics.now() - t0, attempt, false, metrics.costSince(mark, estimateUsd))
            throw e
        }
    }

    companion object {
        const val STAGE_CUTOUT = "cutout"
        const val STAGE_STYLIZE = "stylize"
        const val STAGE_MATTE = "matte"
        const val STAGE_GATE = "gate"
        const val STAGE_SHEET = "sheet"
    }
}
