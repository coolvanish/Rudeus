package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_CUTOUT
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_GATE
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_MATTE
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_RESCORE
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_REVIEW
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_SHEET
import kz.rudy.ar.core.pipeline.CharacterPipeline.Companion.STAGE_STYLIZE
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.core.pipeline.PipelineEvent.Failed
import kz.rudy.ar.core.pipeline.PipelineEvent.Finished
import kz.rudy.ar.core.pipeline.PipelineEvent.GateScored
import kz.rudy.ar.core.pipeline.PipelineEvent.SpriteReady
import kz.rudy.ar.core.pipeline.PipelineEvent.StageFinished
import kz.rudy.ar.core.pipeline.PipelineEvent.StageStarted
import kz.rudy.ar.ui.screens.PipelineStage
import kz.rudy.ar.ui.screens.ProcessingState
import kz.rudy.ar.ui.screens.ProcessingStatus
import kz.rudy.ar.ui.screens.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessingEventsTest {
    private val sheet = CharacterSheet("Dino", "brave", Ability.JUMP, mapOf("kk" to listOf("Sälem")))

    private fun result(score: Float, passed: Boolean, attempts: Int, accepted: Boolean = false, sprite: ByteArray = ByteArray(0)) =
        CharacterResult("ref", sprite, sheet, score, passed, attempts, null, accepted = accepted)

    private fun List<PipelineEvent>.reduce(start: ProcessingState = ProcessingState()) = fold(start) { s, e -> s.reduce(e) }

    /** The event order of a first-try run, as documented on PipelineEvent. */
    private fun firstTry(score: Float, passed: Boolean) = listOf(
        StageStarted(STAGE_CUTOUT, 1), StageFinished(STAGE_CUTOUT, 1, true, 900),
        StageStarted(STAGE_SHEET, 1),
        StageStarted(STAGE_STYLIZE, 1), StageFinished(STAGE_STYLIZE, 1, true, 20_000),
        StageStarted(STAGE_MATTE, 1), StageFinished(STAGE_MATTE, 1, true, 300),
        StageStarted(STAGE_GATE, 1), StageStarted(STAGE_RESCORE, 1),
        GateScored(1, score, passed),
        StageFinished(STAGE_GATE, 1, passed, 5_000), StageFinished(STAGE_RESCORE, 1, false, 5_000),
        StageFinished(STAGE_SHEET, 1, true, 8_000),
    )

    @Test
    fun firstTryPassWalksEveryRowToDone() {
        val events = firstTry(0.82f, passed = true)
        // Mid-run: after the cutout the sheet start must not steal the active row from stylize.
        val midway = events.take(4).reduce()
        assertEquals(PipelineStage.STYLIZE, midway.stage)
        assertEquals(setOf(PipelineStage.CUTOUT), midway.completed)
        assertEquals(ProcessingStatus.RUNNING, midway.status)
        // Matte keeps the painting row active; the gate row shows a score once scored.
        val afterGate = events.take(10).reduce()
        assertEquals(PipelineStage.GATE, afterGate.stage)
        assertEquals(0.82f, afterGate.gateScore)
        assertEquals(setOf(PipelineStage.CUTOUT, PipelineStage.STYLIZE, PipelineStage.GATE), afterGate.completed)

        val done = (events + Finished(result(0.82f, true, 1))).reduce()
        assertEquals(ProcessingStatus.DONE, done.status)
        assertNull(done.stage)
        assertEquals(PipelineStage.entries.toSet(), done.completed)
        assertEquals(1, done.attempt)
        assertEquals(sheet, done.sheet)
    }

    @Test
    fun retryReopensPaintingAndCheckingAndCountsAttempts() {
        val firstFail = firstTry(0.55f, passed = false).dropLast(1) // sheet still running
        val afterFirst = firstFail.reduce()
        // Not passed with attempts left: the gate row stays open and keeps the honest score.
        assertEquals(0.55f, afterFirst.gateScore)
        assertEquals(setOf(PipelineStage.CUTOUT, PipelineStage.STYLIZE), afterFirst.completed)

        val retry = afterFirst.reduce(StageStarted(STAGE_STYLIZE, 2))
        assertEquals(2, retry.attempt)
        assertEquals(PipelineStage.STYLIZE, retry.stage)
        assertEquals(setOf(PipelineStage.CUTOUT), retry.completed)
        assertEquals(ProcessingStatus.RUNNING, retry.status)
        assertNull(retry.gateScore, "no score until this attempt has one")
    }

    @Test
    fun lastAttemptBelowThresholdShipsAsGateFailed() {
        val state = ProcessingState()
            .reduce(StageStarted(STAGE_GATE, 3))
            .reduce(GateScored(3, 0.61f, false))
        assertEquals(setOf(PipelineStage.GATE), state.completed) // last attempt: the row settles even unpassed
        val shipped = state.reduce(Finished(result(0.61f, false, 3)))
        assertEquals(ProcessingStatus.GATE_FAILED, shipped.status)
        assertEquals(0.61f, shipped.gateScore)
        assertEquals(3, shipped.attempt)
        assertEquals(sheet, shipped.sheet)
    }

    @Test
    fun museSpriteShowsAtOnceAndARetryTakesItOffTheHero() {
        val sprite = byteArrayOf(1)
        val painted = ProcessingState().reduce(StageStarted(STAGE_STYLIZE, 1)).reduce(SpriteReady(1, sprite))
        assertSame(sprite, painted.sprite, "before the gate has scored it")
        assertEquals(ProcessingStatus.RUNNING, painted.status)

        val scored = painted.reduce(GateScored(1, 0.4f, false))
        assertSame(sprite, scored.sprite, "a low score keeps it on screen")
        assertNull(scored.reduce(StageStarted(STAGE_STYLIZE, 2)).sprite)
    }

    @Test
    fun belowGateAsksAndYesLeavesOnlyNamingToWaitFor() {
        val asking = (firstTry(0.55f, passed = false).dropLast(1) + StageStarted(STAGE_REVIEW, 1)).reduce()
        assertEquals(ProcessingStatus.REVIEW, asking.status)
        assertEquals(0.55f, asking.gateScore)
        assertFalse(PipelineStage.GATE in asking.completed)

        val yes = asking.reduce(StageFinished(STAGE_REVIEW, 1, ok = true, ms = 4_000))
        assertEquals(ProcessingStatus.RUNNING, yes.status)
        assertEquals(PipelineStage.SHEET, yes.stage)
        assertTrue(PipelineStage.GATE in yes.completed)

        val sheetAlreadyBack = asking.reduce(StageFinished(STAGE_SHEET, 1, true, 8_000))
        assertEquals(ProcessingStatus.REVIEW, sheetAlreadyBack.status, "the sheet coming back does not end the question")
        assertNull(sheetAlreadyBack.reduce(StageFinished(STAGE_REVIEW, 1, ok = true, ms = 4_000)).stage)
    }

    @Test
    fun acceptedResultIsDoneAndKeepsTheGatesOwnScore() {
        val sprite = byteArrayOf(2)
        val done = ProcessingState(status = ProcessingStatus.RUNNING)
            .reduce(Finished(result(0.55f, passed = false, attempts = 1, accepted = true, sprite = sprite)))
        assertEquals(ProcessingStatus.DONE, done.status)
        assertEquals(0.55f, done.gateScore)
        assertSame(sprite, done.sprite)
    }

    @Test
    fun noIsFollowedByTheNextAttempt() {
        val asking = (firstTry(0.55f, passed = false).dropLast(1) + StageStarted(STAGE_REVIEW, 1)).reduce()
        val next = asking.reduce(StageFinished(STAGE_REVIEW, 1, ok = false, ms = 4_000)).reduce(StageStarted(STAGE_STYLIZE, 2))
        assertEquals(ProcessingStatus.RUNNING, next.status)
        assertEquals(PipelineStage.STYLIZE, next.stage)
        assertEquals(2, next.attempt)
    }

    @Test
    fun failureKeepsTheActiveRowAndShowsError() {
        val failed = listOf(
            StageStarted(STAGE_CUTOUT, 1), StageFinished(STAGE_CUTOUT, 1, false, 40),
            Failed("HTTP 401: no key"),
        ).reduce()
        assertEquals(ProcessingStatus.ERROR, failed.status)
        assertEquals(PipelineStage.CUTOUT, failed.stage) // the cross lands on the stage that failed
        assertEquals(emptySet(), failed.completed)
    }

    @Test
    fun fallbackSheetStillTicksTheNamingRow() {
        val s = ProcessingState().reduce(StageFinished(STAGE_SHEET, 1, ok = false, ms = 1))
        assertEquals(setOf(PipelineStage.SHEET), s.completed)
    }
}
