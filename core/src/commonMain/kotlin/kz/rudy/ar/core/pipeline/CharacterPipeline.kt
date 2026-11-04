package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseFrames
import kz.rudy.ar.core.emote.PoseSpec
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.SheetParser
import kz.rudy.ar.core.metrics.CostMark
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.metrics.StageCost
import kotlin.concurrent.Volatile

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
    /**
     * The five emote choreographies ([MetaModels.choreograph]); the defaults when that call failed. With [frames] they
     * are keyed for the frames that came back ([EmoteSet.withFrames]).
     */
    val emotes: EmoteSet = EmoteSet.defaults(),
    /** T33: the drawn pose frames (stage `frames`); empty when the pipeline does not draw them or every call failed. */
    val frames: PoseFrames = PoseFrames.EMPTY,
    /** The gate said no (`gatePassed=false`), but a person looked at this sprite and took it ([CharacterPipeline] `review`). */
    val accepted: Boolean = false,
)

/**
 * Style refs that several [CharacterPipeline.run]s share: the first run to get this far scales and mattes them and the
 * others reuse that, so a character starts without redoing it. Share one only between pipelines built with the same
 * `imageResizer`, `spriteMatter` and `rasterDecoder`. A run that fails while preparing leaves nothing behind.
 */
class StyleRefs(val originals: List<ByteArray>) {
    @Volatile internal var prepared: PreparedRefs? = null
}

internal class PreparedRefs(val muse: List<ByteArray>, val spark: List<ByteArray>, val matted: List<ByteArray>)

/**
 * photo -> cutout (SAM) -> stylize (Muse) -> gate (Spark score + deterministic half) -> retry stylize
 * up to [maxRetries] times while the gate fails. Attempt 2 uses the backup prompt ([MetaModels.stylizeFallback]), the
 * others the main one. A stylize network error (the Muse timeout included) uses up its attempt like a low score; the
 * character fails only when no attempt produced a sprite. A failed gate ships the best-scoring sprite. The character sheet (Spark vision on the cutout) starts right after
 * the cutout and runs concurrently with the stylize/gate loop; a failed sheet call ships [SheetParser.fallback]
 * instead of failing the character (cutout and stylize failures still fail it). Every stage lands in [metrics]
 * with its duration, attempt number, success flag and cost: the measured cost when the models report to [metrics]
 * as their `CostMeter` (`MetaApi(config, client, costMeter = metrics)`), else the `metrics.costs` estimate. The sheet
 * stage is recorded after the loop, so the stage order in the JSON is deterministic.
 *
 * Right after the sheet resolves (its fallback included), a second background coroutine asks [MetaModels.choreograph]
 * for the five emotes with that sheet and the sheet's image; any failure (or a CancellationException while the caller
 * is still active) ships [EmoteSet.defaults]. Stage `choreo` (attempt 1) is recorded right after `sheet`, with its own
 * ms and cost (measured since a [Jobs.SPARK_CHOREO] mark, else `costs.choreoUsd` when ok); since the call runs in the
 * background, its StageStarted and StageFinished are both reported then, back to back, after Finished(sheet). When the
 * character fails, a sheet that came back is recorded with its own outcome and ms, and a choreography still in flight
 * is cancelled and not recorded (one that came back is recorded after the sheet).
 *
 * With [poseFrames] (T33), once the stylize loop has its sprite, [MetaModels.drawPose] draws [PoseSpec.DRAWN] (HELLO
 * x2, ROAR x2) from the matted sprite at <= [MUSE_INPUT_MAX_SIDE] with the Muse style refs, at most [frameConcurrency]
 * calls in flight, each result matted by [spriteMatter] (a degenerate matte drops that frame; no SAM fallback) and
 * bounded to [PoseFrames.MAX_SIDE]. Stage `frames` (attempt 1) is recorded after `choreo` with the wall time of the
 * whole batch and the cost metered under [Jobs.MUSE_POSE] (else `costs.poseUsd` per frame Muse returned); `ok` means
 * every frame came back and matted. Frames are kept per emote from index 0 while contiguous, the result's emotes are
 * keyed for them ([EmoteSet.withFrames]), and nothing fails the character: a call that throws is a missing frame.
 * A batch still in flight when the character fails or is cancelled is cancelled and not recorded.
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
 * by the same matter (once per [StyleRefs]), so the sprite and the refs are both measured over the character only.
 *
 * A failed stage (an exception, not a gate below threshold) is charged only what was metered, else 0. When the
 * character fails, the sheet stage is still recorded (its outcome if done, else cancelled with `ok=false`). When [run]
 * is cancelled, the character lands as aborted ([MetricsLog.abortCharacter]) with the stages recorded so far plus the
 * sheet if it had already come back, and no further event is emitted. A CancellationException while the caller is
 * still active (a timeout surfacing as one) is not a cancel: the stage and the character fail as for any error.
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
 * [review] puts a person in the loop (null = nobody, the CLI: the loop retries by itself). Above the threshold it is
 * never asked. After a below-threshold attempt with attempts left it gets (attempt, score) while the sprite is on screen
 * ([PipelineEvent.SpriteReady]): true ships that sprite ([CharacterResult.accepted]; `gatePassed` stays false, the gate's
 * own verdict), false starts the next attempt. The last attempt ends the loop without asking, and the host offers its
 * own answer on the finished result. With a reviewer the latest sprite ships, not the best-scoring one: the earlier ones
 * were turned down. Stage `review` (cost 0, `ok` = the answer) records the wait, which stays out of `totalMs` because a
 * person is not pipeline time. A cancel during the wait aborts the character as anywhere else.
 *
 * [onEvent] must not throw: a listener exception is not a model failure and is not swallowed; it fails the character
 * (after the stage it was reporting has been recorded once).
 *
 * [imageResizer] (e.g. `JvmRaster::downscale`: keeps aspect, never upscales) bounds what each model receives: Muse
 * gets the cutout and the style refs at <= [MUSE_INPUT_MAX_SIDE], the Spark score the sprite and refs at
 * <= [SPARK_IMAGE_MAX_SIDE], the sheet the cutout at <= [SHEET_IMAGE_MAX_SIDE]; the refs are scaled once per [StyleRefs].
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
    /** T33: draw the pose frames (stage `frames`) after the stylize loop; off = no call, no stage, no frames. */
    private val poseFrames: Boolean = false,
    /** Muse edits in flight at once for the frames (the stylize loop never runs two; 2 keeps the batch at ~2 call latencies). */
    private val frameConcurrency: Int = DEFAULT_FRAME_CONCURRENCY,
    private val review: (suspend (attempt: Int, score: Float) -> Boolean)? = null,
) {
    init {
        require(maxRetries >= 0)
        require(frameConcurrency >= 1)
    }

    suspend fun run(ref: String, image: ByteArray, styleRefs: List<ByteArray>, description: String): CharacterResult =
        run(ref, image, StyleRefs(styleRefs), description)

    /** As above, reusing the work a previous run did on [styleRefs]. */
    suspend fun run(ref: String, image: ByteArray, styleRefs: StyleRefs, description: String): CharacterResult {
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
                val choreoMark = metrics.costMark(Jobs.SPARK_CHOREO)
                val sheetImage = fit(cutout, SHEET_IMAGE_MAX_SIDE)
                emit(PipelineEvent.StageStarted(STAGE_SHEET, 1))
                val sheetStart = metrics.now()
                val sheetCall = async { sheet(ref, description, sheetImage) }
                // Its own Deferred, so a failing character can keep a sheet that came back and drop only this call.
                val choreoCall = async { choreo(ref, sheetCall.await().sheet, sheetImage) }

                // 2-3. stylize + matte + gate, with retries. Inputs are downscaled once here, not per attempt.
                val museCutout = fit(cutout, MUSE_INPUT_MAX_SIDE)
                val prepared = prepare(styleRefs)
                val museRefs = prepared.muse
                // Spark sees the first SPARK_MAX_REFS refs (callers order them best first, e.g. by cull.json); the
                // deterministic half still compares against all of them.
                val modelRefs = prepared.spark.take(SPARK_MAX_REFS)
                val refRasters: List<RasterImage?> = rasterDecoder?.let { d -> prepared.matted.map(d) } ?: emptyList()
                var best: ByteArray? = null
                var bestScore = 0f
                var passed = false
                var accepted = false
                try {
                    while (true) {
                        attempt++
                        val stylized = try {
                            stage(STAGE_STYLIZE, attempt, Jobs.MUSE_STYLIZE, metrics.costs.stylizeUsd) {
                                if (attempt == FALLBACK_ATTEMPT) models.stylizeFallback(ref, museCutout, museRefs)
                                else models.stylize(ref, museCutout, museRefs)
                            }
                        } catch (e: MetaError.Network) {
                            if (attempt <= maxRetries) continue
                            if (best == null) throw e
                            break
                        }
                        val sprite = matte(ref, stylized, attempt)
                        emit(PipelineEvent.SpriteReady(attempt, sprite))
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
                        val score = maxOf(style ?: 0f, technique ?: 0f)
                        val stylePassed = style != null && gate.passes(style)
                        val techniquePassed = technique != null && gate.passes(technique)
                        passed = stylePassed || techniquePassed
                        if (review != null || best == null || score >= bestScore) {
                            best = sprite
                            bestScore = score
                        }
                        // A call that returned was billed; one that threw is charged only what was metered.
                        val styleCost = metrics.costSince(styleMark, if (styleModel != null) metrics.costs.scoreUsd else 0.0)
                        val techniqueCost = metrics.costSince(techniqueMark, if (techniqueModel != null) metrics.costs.scoreUsd else 0.0)
                        emit(PipelineEvent.GateScored(attempt, score, passed))
                        record(STAGE_GATE, styleEnd - t0, attempt, stylePassed, styleCost)
                        record(STAGE_RESCORE, techniqueEnd - t0, attempt, techniquePassed, techniqueCost)
                        if (passed || attempt > maxRetries) break
                        if (askReview(attempt, score)) {
                            accepted = true
                            break
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) {
                        // Really cancelled (the player left): a sheet that already came back was billed, so it stays
                        // with the aborted character; recorded silently, a cancelled run reports nothing more.
                        if (sheetCall.isCompleted && !sheetCall.isCancelled) {
                            val sheet = sheetCall.await()
                            metrics.stage(STAGE_SHEET, sheet.ms, 1, sheet.ok, sheetCost(sheetMark, sheet.ok))
                        }
                        if (choreoCall.isCompleted && !choreoCall.isCancelled) {
                            val choreo = choreoCall.await()
                            metrics.stage(STAGE_CHOREO, choreo.ms, 1, choreo.ok, choreoCost(choreoMark, choreo.ok))
                        }
                        throw e
                    }
                    // The character fails (a stray CancellationException, i.e. a timeout surfacing as one, included),
                    // but the sheet was (or is being) paid for: record it before giving up.
                    // Finished -> its outcome; still running -> cancel it, charge what was metered (usually 0).
                    // The choreography: finished -> recorded after the sheet; still running -> cancelled, not recorded
                    // (an in-flight call has metered nothing yet, as for any stage in flight at a cancel).
                    if (sheetCall.isCompleted) {
                        val sheet = sheetCall.await()
                        record(STAGE_SHEET, sheet.ms, 1, sheet.ok, sheetCost(sheetMark, sheet.ok))
                    } else {
                        choreoCall.cancel()
                        sheetCall.cancel()
                        record(STAGE_SHEET, metrics.now() - sheetStart, 1, false, sheetCost(sheetMark, false))
                    }
                    if (choreoCall.isCompleted && !choreoCall.isCancelled) recordChoreo(choreoCall.await(), choreoMark)
                    else choreoCall.cancel()
                    throw e
                }

                // 5. frames (T33): from the sprite that ships, while the sheet and the choreography are awaited. A
                // failure below (a throwing listener) leaves this scope and cancels a batch in flight: not recorded.
                val sprite = checkNotNull(best)
                val framesMark = metrics.costMark(Jobs.MUSE_POSE)
                val framesCall = if (poseFrames) async { frames(ref, sprite, museRefs) } else null

                val sheet = sheetCall.await()
                record(STAGE_SHEET, sheet.ms, 1, sheet.ok, sheetCost(sheetMark, sheet.ok))
                val choreo = choreoCall.await()
                recordChoreo(choreo, choreoMark)
                val frames = framesCall?.await()
                if (frames != null) recordFrames(frames, framesMark)

                metrics.endCharacter(gateScore = bestScore, gatePassed = passed, retries = attempt - 1)
                val emotes = if (frames == null) choreo.emotes else choreo.emotes.withFrames(frames.frames.counts())
                CharacterResult(ref, sprite, sheet.sheet, bestScore, passed, attempt, mask, emotes, frames?.frames ?: PoseFrames.EMPTY, accepted)
            }
        } catch (e: Throwable) {
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                // Cancelled (the player left, the screen closed): keep the stages already paid for, out of the summary.
                metrics.abortCharacter(attempts = attempt)
                throw e
            }
            // Any other error, including a CancellationException while this coroutine is still active (a timeout
            // surfacing as one): a failed character that counts in the summary, and Failed so the UI leaves RUNNING.
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

    /** Stage `review`: [review]'s answer about the sprite on screen; false (the next attempt) without a reviewer. */
    private suspend fun askReview(attempt: Int, score: Float): Boolean {
        val ask = review ?: return false
        emit(PipelineEvent.StageStarted(STAGE_REVIEW, attempt))
        val t0 = metrics.now()
        val accepted = ask(attempt, score)
        val waited = metrics.now() - t0
        metrics.excludeFromTotal(waited)
        record(STAGE_REVIEW, waited, attempt, accepted, StageCost(0.0, measured = false))
        return accepted
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

    /**
     * The refs as the models and the gate want them, worked out on the first run of a [StyleRefs]. The deterministic
     * half compares like with like: the sprite is matted, so the refs are matted too (same matter; a ref whose matte
     * fails is used as is). Without a decoder nobody reads them, so nothing is matted.
     */
    private fun prepare(refs: StyleRefs): PreparedRefs = refs.prepared ?: run {
        val muse = refs.originals.map { fit(it, MUSE_INPUT_MAX_SIDE) }
        val spark = refs.originals.map { fit(it, SPARK_IMAGE_MAX_SIDE) }
        val matted = if (rasterDecoder == null) emptyList() else spark.map { spriteMatter?.invoke(it) ?: it }
        PreparedRefs(muse, spark, matted).also { refs.prepared = it }
    }

    /** A sheet call that failed is charged only what was metered (0 when nothing was), never the estimate. */
    private fun sheetCost(mark: CostMark, ok: Boolean): StageCost =
        metrics.costSince(mark, if (ok) metrics.costs.sheetUsd else 0.0)

    /** A choreography call that failed is charged only what was metered, like the sheet. */
    private fun choreoCost(mark: CostMark, ok: Boolean): StageCost =
        metrics.costSince(mark, if (ok) metrics.costs.choreoUsd else 0.0)

    /** Records stage `choreo` (its StageStarted is reported here too, see the class doc). */
    private fun recordChoreo(outcome: ChoreoOutcome, mark: CostMark) {
        emit(PipelineEvent.StageStarted(STAGE_CHOREO, 1))
        record(STAGE_CHOREO, outcome.ms, 1, outcome.ok, choreoCost(mark, outcome.ok))
    }

    private class ChoreoOutcome(val emotes: EmoteSet, val ms: Long, val ok: Boolean)

    private class SheetOutcome(val sheet: CharacterSheet, val ms: Long, val ok: Boolean)

    /** [returned] = frames Muse answered (billed), whether or not they matted; [frames] = the ones that ship. */
    private class FramesOutcome(val frames: PoseFrames, val returned: Int, val ms: Long, val ok: Boolean)

    /** Records stage `frames`: the estimate counts every frame Muse returned, as each is one billed edit. */
    private fun recordFrames(outcome: FramesOutcome, mark: CostMark) {
        emit(PipelineEvent.StageStarted(STAGE_FRAMES, 1))
        record(STAGE_FRAMES, outcome.ms, 1, outcome.ok, metrics.costSince(mark, outcome.returned * metrics.costs.poseUsd))
    }

    /**
     * The frame batch, in its own coroutine: every [PoseSpec.DRAWN] frame, at most [frameConcurrency] at once. Never
     * throws except on a real cancellation of the caller; a frame whose call or matte fails is simply missing, and an
     * emote's frames are kept from index 0 while contiguous (a HELLO with only frame 1 ships none).
     */
    private suspend fun frames(ref: String, sprite: ByteArray, refs: List<ByteArray>): FramesOutcome = coroutineScope {
        val t0 = metrics.now()
        val input = fit(sprite, MUSE_INPUT_MAX_SIDE)
        val permits = Semaphore(frameConcurrency)
        val drawn: List<Pair<PoseSpec, Drawn>> = PoseSpec.DRAWN.map { spec ->
            async { spec to permits.withPermit { drawFrame(ref, input, refs, spec) } }
        }.awaitAll()
        val byEmote = PoseFrames.DRAWN.associateWith { e ->
            drawn.filter { it.first.emote == e }.sortedBy { it.first.index }.map { it.second.frame }.takeWhile { it != null }.filterNotNull()
        }
        val frames = PoseFrames.of(byEmote)
        FramesOutcome(frames, drawn.count { it.second.returned }, metrics.now() - t0, frames.count == PoseSpec.DRAWN.size)
    }

    private class Drawn(val frame: ByteArray?, val returned: Boolean)

    /** One frame: the Muse edit, then the matte ([spriteMatter], the raw edit without one) bounded to [PoseFrames.MAX_SIDE]. */
    private suspend fun drawFrame(ref: String, input: ByteArray, refs: List<ByteArray>, spec: PoseSpec): Drawn {
        val png = try {
            models.drawPose(ref, input, refs, spec)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return Drawn(null, returned = false)
        } catch (e: Throwable) {
            return Drawn(null, returned = false)
        }
        val matted = try {
            if (spriteMatter == null) png else spriteMatter.invoke(png)
        } catch (e: Throwable) {
            null
        }
        return Drawn(matted?.let { fit(it, PoseFrames.MAX_SIDE) }, returned = true)
    }

    /**
     * The sheet call, in its own coroutine. Never throws except on a real cancellation of the caller: a failure yields
     * the fallback sheet, and so does a CancellationException while this coroutine is still active (a timeout surfacing
     * as cancellation, as in `segmentEach`). [choreo] follows the same rule with the default emotes.
     */
    private suspend fun sheet(ref: String, description: String, image: ByteArray): SheetOutcome {
        val t0 = metrics.now()
        val (sheet, ok) = try {
            SheetParser.normalise(models.characterSheet(ref, description, image)) to true
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            SheetParser.fallback(ref) to false
        } catch (e: Throwable) {
            SheetParser.fallback(ref) to false
        }
        return SheetOutcome(sheet, metrics.now() - t0, ok)
    }

    private suspend fun choreo(ref: String, sheet: CharacterSheet, image: ByteArray): ChoreoOutcome {
        val t0 = metrics.now()
        return try {
            ChoreoOutcome(models.choreograph(ref, sheet, image), metrics.now() - t0, true)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            ChoreoOutcome(EmoteSet.defaults(), metrics.now() - t0, false)
        } catch (e: Throwable) {
            ChoreoOutcome(EmoteSet.defaults(), metrics.now() - t0, false)
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
            if (!currentCoroutineContext().isActive) throw e
            null // a timeout surfacing as a cancellation: the SAM pass failed, ship the unmatted sprite
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
        } catch (e: Throwable) {
            // A real cancellation records nothing; any other error (a stray CancellationException included) fails it.
            if (e !is CancellationException || currentCoroutineContext().isActive) {
                record(name, metrics.now() - t0, attempt, false, metrics.costSince(mark, 0.0))
            }
            throw e
        }
        // Outside the try: a throwing listener must not record the stage a second time as failed.
        record(name, metrics.now() - t0, attempt, true, metrics.costSince(mark, estimateUsd))
        return r
    }

    companion object {
        /** The attempt that uses [MetaModels.stylizeFallback]. */
        const val FALLBACK_ATTEMPT = 2

        const val STAGE_CUTOUT = "cutout"
        const val STAGE_STYLIZE = "stylize"
        const val STAGE_MATTE = "matte"
        const val STAGE_GATE = "gate"

        /** The gate's colour-free look, run alongside [STAGE_GATE] on every attempt. */
        const val STAGE_RESCORE = "rescore"

        /** A person's look at a below-threshold sprite; `ms` is the wait, `ok` the answer. */
        const val STAGE_REVIEW = "review"
        const val STAGE_SHEET = "sheet"

        /** The five emote choreographies, asked right after the sheet in its coroutine; recorded right after it. */
        const val STAGE_CHOREO = "choreo"

        /** T33: the drawn pose frames, one batch after the stylize loop; recorded after `choreo`, only with `poseFrames`. */
        const val STAGE_FRAMES = "frames"

        /** Two Muse edits in flight for the frames: four frames in about two call latencies, without hammering Muse. */
        const val DEFAULT_FRAME_CONCURRENCY = 2

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
