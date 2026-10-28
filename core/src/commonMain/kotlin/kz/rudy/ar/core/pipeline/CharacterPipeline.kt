package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.SheetParser
import kz.rudy.ar.core.metrics.CostMark
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.metrics.StageCost

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
 * instead of failing the character (cutout and stylize failures still fail it). Every stage lands in [metrics]
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
 * ships and the `matte` stage is recorded with `ok=false`. The deterministic gate half decodes the style refs matted
 * by the same matter (once per run), so the sprite and the refs are both measured over the character only.
 *
 * A failed stage (an exception, not a gate below threshold) is charged only what was metered, else 0. When the
 * character fails, the sheet stage is still recorded (its outcome if done, else cancelled with `ok=false`).
 *
 * Every attempt gets two gate looks at the same sprite, run in parallel (user decision 2026-10-28): stage `gate`
 * ([MetaModels.scoreStyle] + [StyleGate.deterministicScore]) and stage `rescore`, the colour-free one
 * ([MetaModels.scoreTechnique], the character's own colours ignored, + [StyleGate.techniqueScore], outline only); same
 * weights and threshold. The attempt scores the higher of the two and passes if either look clears the threshold; each
 * stage's `ok` is its own look's verdict. Spark gets the first [SPARK_MAX_REFS] style refs, the deterministic halves
 * all of them. Event order per attempt: Started(gate) Started(rescore) GateScored Finished(gate) Finished(rescore).
 *
 * A look that throws (its Spark score call, e.g. `finish_reason=length`, or its deterministic half) is a failed
 * look, not a dead character: its stage is recorded `ok=false` with what was metered, and the attempt scores the other
 * look alone. When both throw, the attempt counts as score 0 / not passed ([PipelineEvent.GateScored]
 * `(attempt, 0, false)`) and the loop retries as for a low score; after the last attempt the character ships with
 * `gatePassed=false`. A CancellationException from a look while this coroutine is still active (a timeout surfacing as
 * cancellation) is the same failed look; a real cancellation of the caller propagates.
 *
 * [onEvent] must not throw: a listener exception is not a model failure and is not swallowed; it fails the character
 * (after the stage it was reporting has been recorded once).
 *
 * [imageResizer] (e.g. `JvmRaster::downscale`: keeps aspect, never upscales) bounds what each model receives: Muse
 * gets the cutout and the style refs at <= [MUSE_INPUT_MAX_SIDE], the Spark score the sprite and refs at
 * <= [SPARK_IMAGE_MAX_SIDE], the sheet the cutout at <= [SHEET_IMAGE_MAX_SIDE]; the refs are scaled once per [run].
 * SAM always gets the full photo, and [CharacterResult.sprite] stays full size.
 *
 * [onEvent] receives [PipelineEvent]s (stage started/finished, gate scored, finished/failed) on the calling coroutine
 * only, in order; it drives the processing screen.
 *
 * Only the calling coroutine touches [metrics]; the sheet and the two score coroutines read nothing but `metrics.now()`
 * (the clock must be safe to read from any thread; the default system clock is). One [run] at a time per instance.
 *
 * Threading: [run] does CPU-heavy work on the calling dispatcher (decode, matte and downscale of images up to a few
 * megapixels, ~0.3 s per matte on a 1408x1536 Muse output), so call it off the main thread, e.g.
 * `withContext(Dispatchers.Default) { pipeline.run(...) }` (or `Dispatchers.IO`); deliver [onEvent] to the UI yourself.
 */
class CharacterPipeline(
    private val models: MetaModels,
    private val gate: StyleGate,
    private val metrics: MetricsLog,
    private val rasterDecoder: ((ByteArray) -> RasterImage?)? = null,
    private val maxRetries: Int = 2,
    private val cutoutComposer: ((image: ByteArray, mask: MaskResult) -> ByteArray)? = null,
    private val spriteMatter: ((ByteArray) -> ByteArray?)? = null,
    private val imageResizer: ((bytes: ByteArray, maxSide: Int) -> ByteArray)? = null,
    private val onEvent: ((PipelineEvent) -> Unit)? = null,
) {
    init { require(maxRetries >= 0) }

    suspend fun run(ref: String, image: ByteArray, styleRefs: List<ByteArray>, description: String): CharacterResult {
        metrics.startCharacter(ref)
        var attempt = 0
        val result = try {
            coroutineScope {
                // 1. cutout
                val mask = stage(STAGE_CUTOUT, 1, Jobs.SAM_CUTOUT, metrics.costs.cutoutUsd) {
                    models.segment(ref, image, Jobs.CUTOUT_PROMPTS).largest()?.takeIf { !it.isEmpty }
                }
                val cutout = if (mask != null && cutoutComposer != null) cutoutComposer.invoke(image, mask) else image

                // 4. sheet: starts now, runs concurrently with 2-3, recorded after the loop. Its job (spark-sheet) is
                // disjoint from the loop's, so its measured cost is simply everything recorded for the job since now.
                val sheetMark = metrics.costMark(Jobs.SPARK_SHEET)
                val sheetImage = fit(cutout, SHEET_IMAGE_MAX_SIDE)
                emit(PipelineEvent.StageStarted(STAGE_SHEET, 1))
                val sheetStart = metrics.now()
                val sheetCall = async { sheet(ref, description, sheetImage) }

                // 2-3. stylize + matte + gate, with retries. Inputs are downscaled once here, not per attempt.
                val museCutout = fit(cutout, MUSE_INPUT_MAX_SIDE)
                val museRefs = styleRefs.map { fit(it, MUSE_INPUT_MAX_SIDE) }
                val sparkRefs = styleRefs.map { fit(it, SPARK_IMAGE_MAX_SIDE) }
                // Spark sees the first SPARK_MAX_REFS refs (callers order them best first, e.g. by cull.json); the
                // deterministic half still compares against all of them.
                val modelRefs = sparkRefs.take(SPARK_MAX_REFS)
                // The deterministic half compares like with like: the sprite is matted, so the refs are matted too
                // (same matter, once per run; a ref whose matte fails is used as is).
                val refRasters: List<RasterImage?> = rasterDecoder?.let { d ->
                    sparkRefs.map { r -> d(spriteMatter?.invoke(r) ?: r) }
                } ?: emptyList()
                var sprite: ByteArray = ByteArray(0)
                var score = 0f
                var passed = false
                try {
                    while (true) {
                        attempt++
                        val stylized = stage(STAGE_STYLIZE, attempt, Jobs.MUSE_STYLIZE, metrics.costs.stylizeUsd) {
                            models.stylize(ref, museCutout, museRefs)
                        }
                        sprite = matte(ref, stylized, attempt)
                        // Both looks at once (user decision 2026-10-28): the style rubric and the colour-free one
                        // score the same sprite in parallel; the attempt keeps the higher score.
                        emit(PipelineEvent.StageStarted(STAGE_GATE, attempt))
                        emit(PipelineEvent.StageStarted(STAGE_RESCORE, attempt))
                        val t0 = metrics.now()
                        val styleMark = metrics.costMark(Jobs.SPARK_SCORE)
                        val techniqueMark = metrics.costMark(Jobs.SPARK_SCORE_TECHNIQUE)
                        // Only the scoring is guarded: a failed score call (e.g. Spark out of tokens) is a failed look,
                        // not a dead character. The records below stay outside, so a throwing listener can never
                        // record a stage twice. The calls only read the clock; metrics and events stay on this coroutine.
                        val scored = guarded { fit(sprite, SPARK_IMAGE_MAX_SIDE) }
                        val styleCall = async { scored?.let { s -> guarded { models.scoreStyle(ref, s, modelRefs) } } to metrics.now() }
                        val techniqueCall = async { scored?.let { s -> guarded { models.scoreTechnique(ref, s, modelRefs) } } to metrics.now() }
                        val (styleModel, styleEnd) = styleCall.await()
                        val (techniqueModel, techniqueEnd) = techniqueCall.await()
                        // Deterministic halves; a decode or half that throws fails its look (decoded once per attempt).
                        val raster by lazy(LazyThreadSafetyMode.NONE) { scored?.let { s -> rasterDecoder?.invoke(s) } }
                        val style = styleModel?.let { m ->
                            guarded { gate.combine(m, rasterDecoder?.let { gate.deterministicScore(raster, refRasters) }) }
                        }
                        val technique = techniqueModel?.let { m ->
                            guarded { gate.combine(m, rasterDecoder?.let { gate.techniqueScore(raster, refRasters) }) }
                        }
                        score = maxOf(style ?: 0f, technique ?: 0f)
                        val stylePassed = style != null && gate.passes(style)
                        val techniquePassed = technique != null && gate.passes(technique)
                        passed = stylePassed || techniquePassed
                        // A call that returned was billed; one that threw is charged only what was metered.
                        val styleCost = metrics.costSince(styleMark, if (styleModel != null) metrics.costs.scoreUsd else 0.0)
                        val techniqueCost = metrics.costSince(techniqueMark, if (techniqueModel != null) metrics.costs.scoreUsd else 0.0)
                        emit(PipelineEvent.GateScored(attempt, score, passed))
                        record(STAGE_GATE, styleEnd - t0, attempt, stylePassed, styleCost)
                        record(STAGE_RESCORE, techniqueEnd - t0, attempt, techniquePassed, techniqueCost)
                        if (passed || attempt > maxRetries) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The character fails, but the sheet was (or is being) paid for: record it before giving up.
                    // Finished -> its outcome; still running -> cancel it, charge what was metered (usually 0).
                    if (sheetCall.isCompleted) {
                        val sheet = sheetCall.await()
                        record(STAGE_SHEET, sheet.ms, 1, sheet.ok, sheetCost(sheetMark, sheet.ok))
                    } else {
                        sheetCall.cancel()
                        record(STAGE_SHEET, metrics.now() - sheetStart, 1, false, sheetCost(sheetMark, false))
                    }
                    throw e
                }

                val sheet = sheetCall.await()
                record(STAGE_SHEET, sheet.ms, 1, sheet.ok, sheetCost(sheetMark, sheet.ok))

                metrics.endCharacter(gateScore = score, gatePassed = passed, retries = attempt - 1)
                CharacterResult(ref, sprite, sheet.sheet, score, passed, attempt, mask)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            metrics.endCharacter(gateScore = 0f, gatePassed = false, retries = (attempt - 1).coerceAtLeast(0))
            val error = if (e is MetaError) e else MetaError.Provider("$ref: ${e::class.simpleName}: ${e.message}", e)
            emit(PipelineEvent.Failed(error.message ?: error::class.simpleName.orEmpty()))
            throw error
        }
        emit(PipelineEvent.Finished(result))
        return result
    }

    private fun emit(event: PipelineEvent) {
        onEvent?.invoke(event)
    }

    /** Records stage [name] in [metrics] and reports it as finished. */
    private fun record(name: String, ms: Long, attempt: Int, ok: Boolean, cost: StageCost) {
        metrics.stage(name, ms, attempt, ok, cost)
        emit(PipelineEvent.StageFinished(name, attempt, ok, ms))
    }

    /**
     * [block]'s value, or null when it throws: a failed score call is a failed look, not a dead character. A
     * CancellationException while this coroutine is still active (a timeout surfacing as cancellation) is a failed
     * look too; a real cancellation of the caller propagates.
     */
    private suspend inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        null
    } catch (e: Throwable) {
        null
    }

    /** [bytes] scaled to at most [maxSide] px on the long side by [imageResizer]; unchanged without one. */
    private fun fit(bytes: ByteArray, maxSide: Int): ByteArray = imageResizer?.invoke(bytes, maxSide) ?: bytes

    /** A sheet call that failed is charged only what was metered (0 when nothing was), never the estimate. */
    private fun sheetCost(mark: CostMark, ok: Boolean): StageCost =
        metrics.costSince(mark, if (ok) metrics.costs.sheetUsd else 0.0)

    private class SheetOutcome(val sheet: CharacterSheet, val ms: Long, val ok: Boolean)

    /**
     * The sheet call, in its own coroutine. Never throws except on a real cancellation of the caller: a failure yields
     * the fallback sheet, and so does a CancellationException while this coroutine is still active (a timeout surfacing
     * as cancellation, as in `segmentEach`).
     */
    private suspend fun sheet(ref: String, description: String, image: ByteArray): SheetOutcome {
        val t0 = metrics.now()
        return try {
            val sheet = SheetParser.normalise(models.characterSheet(ref, description, image))
            SheetOutcome(sheet, metrics.now() - t0, true)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            SheetOutcome(SheetParser.fallback(ref), metrics.now() - t0, false)
        } catch (e: Throwable) {
            SheetOutcome(SheetParser.fallback(ref), metrics.now() - t0, false)
        }
    }

    /** Stage `matte`: [spriteMatter], else one SAM pass on the stylized image, else the unmatted sprite. */
    private suspend fun matte(ref: String, stylized: ByteArray, attempt: Int): ByteArray {
        val matter = spriteMatter ?: return stylized
        emit(PipelineEvent.StageStarted(STAGE_MATTE, attempt))
        val t0 = metrics.now()
        matter(stylized)?.let {
            record(STAGE_MATTE, metrics.now() - t0, attempt, true, StageCost(0.0, measured = false))
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
        record(STAGE_MATTE, metrics.now() - t0, attempt, viaSam != null, metrics.costSince(samMark, samEstimate))
        return viaSam ?: stylized
    }

    /**
     * Runs [block] as stage [name]; its cost is what [job] recorded meanwhile, else [estimateUsd] when it succeeded and
     * 0 when it failed (an HTTP error that metered nothing is not billed).
     */
    private suspend inline fun <T> stage(name: String, attempt: Int, job: String, estimateUsd: Double, block: () -> T): T {
        emit(PipelineEvent.StageStarted(name, attempt))
        val t0 = metrics.now()
        val mark = metrics.costMark(job)
        val r = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            record(name, metrics.now() - t0, attempt, false, metrics.costSince(mark, 0.0))
            throw e
        }
        // Outside the try: a throwing listener must not record the stage a second time as failed.
        record(name, metrics.now() - t0, attempt, true, metrics.costSince(mark, estimateUsd))
        return r
    }

    companion object {
        const val STAGE_CUTOUT = "cutout"
        const val STAGE_STYLIZE = "stylize"
        const val STAGE_MATTE = "matte"
        const val STAGE_GATE = "gate"

        /** The gate's colour-free look, run alongside [STAGE_GATE] on every attempt. */
        const val STAGE_RESCORE = "rescore"
        const val STAGE_SHEET = "sheet"

        /** Long-side bound (px) of the cutout and style refs sent to Muse Image. */
        const val MUSE_INPUT_MAX_SIDE = 1024

        /** Long-side bound (px) of the sprite and style refs sent to the Spark score (sprite + [SPARK_MAX_REFS] refs). */
        const val SPARK_IMAGE_MAX_SIDE = 512

        /**
         * Style refs per Spark score call (user decision 2026-10-28: 3 instead of all 8, fewer image tokens per call,
         * for latency and cost). Muse still gets every ref.
         */
        const val SPARK_MAX_REFS = 3

        /** Long-side bound (px) of the cutout the sheet call looks at. */
        const val SHEET_IMAGE_MAX_SIDE = 512
    }
}
