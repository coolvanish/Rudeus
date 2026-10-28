package kz.rudy.ar.ui.screens

import kz.rudy.ar.core.pipeline.CharacterPipeline
import kz.rudy.ar.core.pipeline.PipelineEvent

/**
 * Pure mapping of core [PipelineEvent]s onto the Processing screen's [ProcessingState]. Stage names follow
 * `CharacterPipeline.STAGE_*`: `matte` is part of the "painting" step, `rescore` of the "checking" step, and the sheet
 * runs in the background (its start never steals the active row; its finish ticks the row even on a fallback sheet).
 * A retry (`stylize` started with attempt > 1) reopens the painting and checking rows. The gate row completes on
 * [PipelineEvent.GateScored] when the attempt passed or was the last one; the per-look `StageFinished(gate)` flags
 * are not the verdict and are ignored.
 */
fun ProcessingState.reduce(event: PipelineEvent): ProcessingState = when (event) {
    is PipelineEvent.StageStarted -> {
        val stage = stageOf(event.stage)
        when {
            stage == null || event.stage == CharacterPipeline.STAGE_SHEET -> this
            event.stage == CharacterPipeline.STAGE_STYLIZE && event.attempt > 1 -> copy(
                status = ProcessingStatus.RUNNING,
                stage = stage,
                completed = completed - PipelineStage.STYLIZE - PipelineStage.GATE,
                attempt = event.attempt,
            )
            else -> copy(status = ProcessingStatus.RUNNING, stage = stage, attempt = maxOf(attempt, event.attempt))
        }
    }

    is PipelineEvent.StageFinished -> when (event.stage) {
        CharacterPipeline.STAGE_CUTOUT -> if (event.ok) copy(completed = completed + PipelineStage.CUTOUT) else this
        CharacterPipeline.STAGE_STYLIZE -> if (event.ok) copy(completed = completed + PipelineStage.STYLIZE) else this
        CharacterPipeline.STAGE_SHEET -> copy(completed = completed + PipelineStage.SHEET)
        else -> this
    }

    is PipelineEvent.GateScored -> {
        val settled = event.passed || event.attempt >= maxAttempts
        copy(
            gateScore = event.score,
            attempt = event.attempt,
            completed = if (settled) completed + PipelineStage.GATE else completed,
        )
    }

    is PipelineEvent.Finished -> copy(
        status = if (event.result.gatePassed) ProcessingStatus.DONE else ProcessingStatus.GATE_FAILED,
        stage = null,
        completed = PipelineStage.entries.toSet(),
        gateScore = event.result.gateScore,
        attempt = event.result.attempts,
        sheet = event.result.sheet,
    )

    is PipelineEvent.Failed -> copy(status = ProcessingStatus.ERROR)
}

/** Screen row for a core stage name; null for names the screen does not show (none today). */
internal fun stageOf(name: String): PipelineStage? = when (name) {
    CharacterPipeline.STAGE_CUTOUT -> PipelineStage.CUTOUT
    CharacterPipeline.STAGE_STYLIZE, CharacterPipeline.STAGE_MATTE -> PipelineStage.STYLIZE
    CharacterPipeline.STAGE_GATE, CharacterPipeline.STAGE_RESCORE -> PipelineStage.GATE
    CharacterPipeline.STAGE_SHEET -> PipelineStage.SHEET
    else -> null
}
