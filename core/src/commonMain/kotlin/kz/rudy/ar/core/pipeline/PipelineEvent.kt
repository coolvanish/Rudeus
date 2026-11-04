package kz.rudy.ar.core.pipeline

/**
 * Progress of one [CharacterPipeline.run], delivered to its `onEvent` callback on the calling coroutine, in order.
 * [stage] names are the `CharacterPipeline.STAGE_*` constants (`cutout`, `stylize`, `matte`, `gate`, `rescore`, `review`, `sheet`); free of
 * UI types so ProcessingScreen (T03) can map them to its own steps.
 *
 * A first-try run reads: Started(cutout) Finished(cutout) Started(sheet) Started(stylize) Finished(stylize)
 * [Started(matte) Finished(matte)] SpriteReady Started(gate) Started(rescore) GateScored Finished(gate) Finished(rescore)
 * Finished(sheet) Finished(result).
 * The sheet starts right after the cutout and runs alongside the loop; its Finished arrives after the last gate.
 * With a reviewer, a below-threshold attempt with attempts left adds Started(review) Finished(review) after Finished(rescore);
 * `ok` on Finished(review) is the answer (true = this sprite ships, false = the next attempt starts).
 */
sealed interface PipelineEvent {
    data class StageStarted(val stage: String, val attempt: Int) : PipelineEvent

    data class StageFinished(val stage: String, val attempt: Int, val ok: Boolean, val ms: Long) : PipelineEvent

    /** What Muse sent for [attempt], matted, before the gate looks at it: the screen can show it at any score. */
    data class SpriteReady(val attempt: Int, val sprite: ByteArray) : PipelineEvent

    /** The combined gate score of [attempt]; `passed=false` with attempts left means a retry follows. */
    data class GateScored(val attempt: Int, val score: Float, val passed: Boolean) : PipelineEvent

    /** The character is ready (possibly with `gatePassed=false`, `accepted=true` or a fallback sheet). */
    data class Finished(val result: CharacterResult) : PipelineEvent

    /** The character failed (cutout, stylize or gate error); [message] is the error's message. Not sent on cancellation. */
    data class Failed(val message: String) : PipelineEvent
}
