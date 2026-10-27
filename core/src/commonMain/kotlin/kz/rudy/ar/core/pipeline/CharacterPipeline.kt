package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.CancellationException
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
    /** Stylized sprite, PNG bytes. */
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
 * up to [maxRetries] times while the gate fails -> character sheet (Spark). Every stage lands in [metrics]
 * with its duration, attempt number, success flag and an estimated cost from `metrics.costs`.
 *
 * [rasterDecoder] (e.g. `JvmRaster::decode`) enables the deterministic gate half; without it the model
 * score alone decides. [cutoutComposer] turns (photo, mask) into the transparent PNG Muse receives; without
 * it the photo is sent as is (Muse still gets the mask via the prompt-less edit; wire `JvmRaster.cutout`
 * on the JVM and a Bitmap-based one on Android).
 */
class CharacterPipeline(
    private val models: MetaModels,
    private val gate: StyleGate,
    private val metrics: MetricsLog,
    private val rasterDecoder: ((ByteArray) -> RasterImage?)? = null,
    private val maxRetries: Int = 2,
    private val cutoutComposer: ((image: ByteArray, mask: MaskResult) -> ByteArray)? = null,
) {
    init { require(maxRetries >= 0) }

    suspend fun run(ref: String, image: ByteArray, styleRefs: List<ByteArray>, description: String): CharacterResult {
        metrics.startCharacter(ref)
        var attempt = 0
        try {
            // 1. cutout
            val mask = stage(STAGE_CUTOUT, 1, metrics.costs.cutoutUsd) {
                val result = models.segment(ref, image, Jobs.CUTOUT_PROMPTS)
                result.largest()?.takeIf { !it.isEmpty }
            }
            val cutout = if (mask != null && cutoutComposer != null) cutoutComposer.invoke(image, mask) else image

            // 2-3. stylize + gate, with retries
            val refRasters: List<RasterImage?> = rasterDecoder?.let { d -> styleRefs.map { d(it) } } ?: emptyList()
            var sprite: ByteArray = ByteArray(0)
            var score = 0f
            var passed = false
            while (true) {
                attempt++
                sprite = stage(STAGE_STYLIZE, attempt, metrics.costs.stylizeUsd) { models.stylize(ref, cutout, styleRefs) }
                val t0 = metrics.now()
                try {
                    val modelScore = models.scoreStyle(ref, sprite, styleRefs)
                    val deterministic = rasterDecoder?.let { d -> gate.deterministicScore(d(sprite), refRasters) }
                    score = gate.combine(modelScore, deterministic)
                    passed = gate.passes(score)
                    metrics.stage(STAGE_GATE, metrics.now() - t0, attempt, passed, metrics.costs.scoreUsd)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    metrics.stage(STAGE_GATE, metrics.now() - t0, attempt, false, metrics.costs.scoreUsd)
                    throw e
                }
                if (passed || attempt > maxRetries) break
            }

            // 4. sheet
            val sheet = stage(STAGE_SHEET, 1, metrics.costs.sheetUsd) {
                SheetParser.normalise(models.characterSheet(ref, description))
            }

            metrics.endCharacter(gateScore = score, gatePassed = passed, retries = attempt - 1)
            return CharacterResult(ref, sprite, sheet, score, passed, attempt, mask)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = (attempt - 1).coerceAtLeast(0))
            throw if (e is MetaError) e else MetaError.Provider("$ref: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private suspend inline fun <T> stage(name: String, attempt: Int, costUsd: Double, block: () -> T): T {
        val t0 = metrics.now()
        try {
            val r = block()
            metrics.stage(name, metrics.now() - t0, attempt, true, costUsd)
            return r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            metrics.stage(name, metrics.now() - t0, attempt, false, costUsd)
            throw e
        }
    }

    companion object {
        const val STAGE_CUTOUT = "cutout"
        const val STAGE_STYLIZE = "stylize"
        const val STAGE_GATE = "gate"
        const val STAGE_SHEET = "sheet"
    }
}
