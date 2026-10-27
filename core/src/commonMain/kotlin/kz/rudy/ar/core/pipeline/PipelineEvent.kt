package kz.rudy.ar.core.pipeline

/**
 * Progress of one [CharacterPipeline.run], delivered to its `onEvent` callback on the calling coroutine, in order.
 * [stage] names are the `CharacterPipeline.STAGE_*` constants (`cutout`, `stylize`, `matte`, `gate`, `sheet`); free of
 * UI types so ProcessingScreen (T03) can map them to its own steps.
 *
 * A first-try run reads: Started(cutout) Finished(cutout) Started(sheet) Started(stylize) Finished(stylize)
 * [Started(matte) Finished(matte)] Started(gate) GateScored Finished(gate) Finished(sheet) Finished(result).
 * The sheet starts right after the cutout and runs alongside the loop; its Finished arrives after the last gate.
 */
sealed interface PipelineEvent {
    data class StageStarted(val stage: String, val attempt: Int) : PipelineEvent

    data class StageFinished(val stage: String, val attempt: Int, val ok: Boolean, val ms: Long) : PipelineEvent

    /** The combined gate score of [attempt]; `passed=false` with attempts left means a retry follows. */
    data class GateScored(val attempt: Int, val score: Float, val passed: Boolean) : PipelineEvent

    /** The character is ready (possibly with `gatePassed=false` or a fallback sheet). */
    data class Finished(val result: CharacterResult) : PipelineEvent

    /** The character failed (cutout, stylize or gate error); [message] is the error's message. Not sent on cancellation. */
    data class Failed(val message: String) : PipelineEvent
}
