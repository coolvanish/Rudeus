package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.io.argbOf
import kz.rudy.ar.core.meta.FakeModels
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.SheetParser
import kz.rudy.ar.core.metrics.MetricsLog
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterPipelineTest {
    private fun metrics(): MetricsLog {
        var t = 1_000_000L
        return MetricsLog(source = "cli", provider = "fixture", device = "test", clock = { t += 10; t })
    }

    private val image = byteArrayOf(1, 2, 3)
    private val refs = listOf(byteArrayOf(4), byteArrayOf(5))

    @Test
    fun pipelineHappyPathSingleAttempt() = runTest {
        val models = FakeModels(scores = listOf(0.85f), techniqueScores = listOf(0.8f))
        val log = metrics()
        val pipeline = CharacterPipeline(models, StyleGate(), log)

        val result = pipeline.run("cat-01", image, refs, "a cat")

        assertEquals(1, result.attempts)
        assertTrue(result.gatePassed)
        assertEquals(0.85f, result.gateScore, 1e-4f)
        assertEquals(1, models.stylizeCalls)
        assertEquals(1, models.scoreCalls)
        assertNotNull(result.cutoutMask)
        assertEquals(4, result.cutoutMask.area)
        assertEquals(
            listOf("segment:cat-01", "stylize:cat-01", "characterSheet:cat-01", "scoreStyle:cat-01", "scoreTechnique:cat-01"),
            models.calls,
            "the sheet starts before the gate; both gate looks run on every attempt",
        )

        val run = log.toRun()
        val c = run.characters.single()
        assertEquals(listOf("cutout", "stylize", "gate", "rescore", "sheet"), c.stages.map { it.name })
        assertTrue(c.stages.all { it.ok })
        assertEquals(0, c.retries)
        assertTrue(c.gatePassed)
        assertEquals(0.038, c.totalCostUsd, 1e-9) // CostTable: 0.005 + 0.01 + 2 * 0.009 (both looks) + 0.005
        assertEquals(1, run.summary.n)
        assertEquals(1f, run.summary.gatePassRateFirstTry)
    }

    @Test
    fun pipelineRetriesTwiceThenShipsWithGateFailed() = runTest {
        val models = FakeModels(scores = listOf(0.5f, 0.6f, 0.65f))
        val log = metrics()
        val pipeline = CharacterPipeline(models, StyleGate(threshold = 0.7f), log, maxRetries = 2)

        val result = pipeline.run("dog-02", image, refs, "a dog")

        assertEquals(3, result.attempts)
        assertFalse(result.gatePassed)
        assertEquals(0.65f, result.gateScore, 1e-4f)
        assertEquals(3, models.stylizeCalls)
        assertEquals(3, models.scoreCalls)

        val c = log.toRun().characters.single()
        assertEquals(2, c.retries)
        assertFalse(c.gatePassed)
        val gates = c.stages.filter { it.name == "gate" }
        assertEquals(listOf(1, 2, 3), gates.map { it.attempt })
        assertTrue(gates.none { it.ok })
        assertEquals(3, c.stages.count { it.name == "stylize" })
        assertEquals("sheet", c.stages.last().name)
    }

    @Test
    fun pipelineScoresMattedSprite() = runTest {
        val models = FakeModels(scores = listOf(0.5f, 0.9f))
        val log = metrics()
        val matted = byteArrayOf(7, 7, 7)
        val pipeline = CharacterPipeline(models, StyleGate(), log, spriteMatter = { matted })

        val result = pipeline.run("cat-01", image, refs, "a cat")

        assertEquals(2, result.attempts)
        assertContentEquals(matted, result.sprite)
        assertEquals(2, models.scoredImages.size)
        assertTrue(models.scoredImages.all { it.contentEquals(matted) }, "the gate sees the matted sprite on every attempt")
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout", "stylize", "matte", "gate", "rescore", "stylize", "matte", "gate", "rescore", "sheet"), c.stages.map { it.name })
        val mattes = c.stages.filter { it.name == "matte" }
        assertEquals(listOf(1, 2), mattes.map { it.attempt })
        assertTrue(mattes.all { it.ok && it.costUsd == 0.0 }, "deterministic matte is free")
    }

    @Test
    fun matteFailureFallsBackToSam() = runTest {
        val models = FakeModels(scores = listOf(0.9f))
        val log = metrics()
        val samCut = byteArrayOf(5, 5)
        val pipeline = CharacterPipeline(
            models, StyleGate(), log,
            cutoutComposer = { img, _ -> if (img.contentEquals(models.sprite)) samCut else img },
            spriteMatter = { null },
        )

        val result = pipeline.run("cat-01", image, refs, "a cat")

        assertEquals(2, models.calls.count { it.startsWith("segment:") }, "one cutout + one SAM pass on the stylized sprite")
        assertContentEquals(samCut, result.sprite)
        assertContentEquals(samCut, models.scoredImages.single())
        val matte = log.toRun().characters.single().stages.single { it.name == "matte" }
        assertTrue(matte.ok)
        assertEquals(log.costs.cutoutUsd, matte.costUsd, 1e-9)

        // SAM finds nothing on the sprite either (empty masks; the composer is never reached): the unmatted sprite
        // ships, matte ok=false, and the SAM pass that did run is still charged.
        val empty = FakeModels(scores = listOf(0.9f), masks = emptyList())
        val log2 = metrics()
        val shipped = CharacterPipeline(
            empty, StyleGate(), log2,
            cutoutComposer = { _, _ -> error("no mask, no composition") },
            spriteMatter = { null },
        ).run("cat-01", image, refs, "a cat")
        assertContentEquals(empty.sprite, shipped.sprite)
        assertEquals(2, empty.calls.count { it.startsWith("segment:") })
        val emptyMatte = log2.toRun().characters.single().stages.single { it.name == "matte" }
        assertFalse(emptyMatte.ok)
        assertEquals(log2.costs.cutoutUsd, emptyMatte.costUsd, 1e-9)

        // No cutoutComposer at all: SAM cannot cut the sprite, the unmatted sprite ships, matte ok=false.
        val noComposer = FakeModels(scores = listOf(0.9f))
        val log3 = metrics()
        val unmatted = CharacterPipeline(noComposer, StyleGate(), log3, spriteMatter = { null }).run("cat-01", image, refs, "a cat")
        assertContentEquals(noComposer.sprite, unmatted.sprite)
        assertFalse(log3.toRun().characters.single().stages.single { it.name == "matte" }.ok)
    }

    @Test
    fun sheetRunsConcurrentlyAndIsRecordedLast() = runTest {
        val models = FakeModels(
            scores = listOf(0.5f, 0.9f),
            delays = mapOf("segment" to 1_000L, "stylize" to 5_000L, "scoreStyle" to 2_000L, "characterSheet" to 6_000L),
        )
        val log = MetricsLog(source = "cli", provider = "fixture", device = "test", clock = { testScheduler.currentTime })

        val result = CharacterPipeline(models, StyleGate(), log).run("cat-01", image, refs, "a cat")

        assertEquals(2, result.attempts)
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout", "stylize", "gate", "rescore", "stylize", "gate", "rescore", "sheet"), c.stages.map { it.name })
        val sheet = c.stages.last()
        assertEquals(6_000L, sheet.ms)
        assertTrue(sheet.ok)
        // Sequential would be 1 + 2 * (5 + 2) + 6 = 21 s; with the sheet alongside the loop it is 1 + 14 = 15 s.
        assertEquals(21_000L, c.stages.sumOf { it.ms })
        assertEquals(15_000L, c.totalMs)
        assertTrue(c.totalMs < c.stages.sumOf { it.ms })
    }

    @Test
    fun sheetFailureShipsFallbackSheet() = runTest {
        val models = FakeModels(scores = listOf(0.85f), techniqueScores = listOf(0.85f), sheetError = MetaError.Http(500, "boom"))
        val log = metrics()

        val result = CharacterPipeline(models, StyleGate(), log).run("cat-01", image, refs, "a cat")

        assertEquals(SheetParser.fallback("cat-01"), result.sheet)
        assertEquals("Cat", result.sheet.name)
        assertEquals(Ability.JUMP, result.sheet.ability)
        assertEquals(SheetParser.FALLBACK_LINES.getValue("kk"), result.sheet.lines.getValue("kk"))
        assertEquals(SheetParser.FALLBACK_LINES.getValue("ru"), result.sheet.lines.getValue("ru"))
        val c = log.toRun().characters.single()
        assertEquals("sheet", c.stages.last().name)
        assertFalse(c.stages.last().ok)
        assertTrue(c.stages.dropLast(1).all { it.ok })
        assertTrue(c.gatePassed)
        assertEquals(0.85f, c.gateScore, 1e-4f)
        assertEquals(0, c.retries)
        assertEquals("Dos", SheetParser.fallback("01").name)
        assertEquals("Robot", SheetParser.fallback("robot-toy-02").name)
        assertEquals(0.0, c.stages.last().costUsd, "a failed call that metered nothing is not charged the estimate")
    }

    @Test
    fun strayCancellationInTheSheetIsASheetFailure() = runTest {
        // A timeout surfacing as CancellationException while the pipeline is alive: fallback sheet, character ships.
        val models = FakeModels(scores = listOf(0.85f), sheetError = kotlinx.coroutines.CancellationException("timeout"))
        val log = metrics()
        val result = CharacterPipeline(models, StyleGate(), log).run("cat-01", image, refs, "a cat")
        assertEquals(SheetParser.fallback("cat-01"), result.sheet)
        assertFalse(log.toRun().characters.single().stages.last().ok)

        // A real cancellation of the caller still propagates.
        val slow = FakeModels(delays = mapOf("stylize" to 5_000L, "characterSheet" to 5_000L))
        val job = launch { CharacterPipeline(slow, StyleGate(), metrics()).run("cat-01", image, refs, "a cat") }
        delay(1_000L)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun failedCharacterStillRecordsTheSheet() = runTest {
        // The sheet finished (and was paid for) before stylize failed: recorded with its outcome and estimate.
        val done = FakeModels(
            stylizeError = MetaError.Http(500, "muse down"),
            delays = mapOf("characterSheet" to 1_000L, "stylize" to 3_000L),
        )
        val log = MetricsLog(source = "cli", provider = "fixture", device = "test", clock = { testScheduler.currentTime })
        val events = ArrayList<PipelineEvent>()
        assertFailsWith<MetaError.Http> {
            CharacterPipeline(done, StyleGate(), log, onEvent = { events += it }).run("cat-01", image, refs, "")
        }
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout", "stylize", "sheet"), c.stages.map { it.name })
        val stylize = c.stages[1]
        assertFalse(stylize.ok)
        assertEquals(0.0, stylize.costUsd, "failed stylize, nothing metered: not charged")
        val sheet = c.stages.last()
        assertTrue(sheet.ok)
        assertEquals(1_000L, sheet.ms)
        assertEquals(log.costs.sheetUsd, sheet.costUsd, 1e-9)
        assertEquals(log.costs.cutoutUsd + log.costs.sheetUsd, c.totalCostUsd, 1e-9)
        assertTrue(PipelineEvent.StageFinished("sheet", 1, true, 1_000L) in events)
        assertTrue(events.last() is PipelineEvent.Failed)

        // The sheet was still running: cancelled, recorded ok=false, charged nothing.
        val running = FakeModels(
            stylizeError = MetaError.Http(500, "muse down"),
            delays = mapOf("characterSheet" to 10_000L, "stylize" to 3_000L),
        )
        val log2 = MetricsLog(source = "cli", provider = "fixture", device = "test", clock = { testScheduler.currentTime })
        val events2 = ArrayList<PipelineEvent>()
        assertFailsWith<MetaError.Http> {
            CharacterPipeline(running, StyleGate(), log2, onEvent = { events2 += it }).run("cat-01", image, refs, "")
        }
        val sheet2 = log2.toRun().characters.single().stages.last()
        assertEquals("sheet", sheet2.name)
        assertFalse(sheet2.ok)
        assertEquals(3_000L, sheet2.ms)
        assertEquals(0.0, sheet2.costUsd)
        assertTrue(PipelineEvent.StageFinished("sheet", 1, false, 3_000L) in events2)
    }

    /**
     * [FakeModels] whose score call meters [scoreUsd] to [meter] (like MetaApi: the reply's usage is billed even when
     * its content is empty), then throws on the attempts in [failOn] (1-based), as Spark did with
     * `finish_reason=length`; other attempts return the inner score.
     */
    private class FlakyScore(
        private val inner: FakeModels,
        private val meter: MetricsLog,
        private val failOn: Set<Int>,
        private val scoreUsd: Double = 0.002,
        private val error: Throwable = MetaError.Parse("empty content, finish_reason=length"),
    ) : MetaModels by inner {
        var scoreCalls = 0
        override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
            scoreCalls++
            meter.record(Jobs.SPARK_SCORE, ref, scoreUsd)
            if (scoreCalls in failOn) throw error
            return inner.scoreStyle(ref, image, refs)
        }
    }

    @Test
    fun scoreErrorCountsAsFailedAttempt() = runTest {
        val log = metrics()
        val models = FlakyScore(FakeModels(scores = listOf(0.85f)), log, failOn = setOf(1))
        val events = ArrayList<PipelineEvent>()

        val result = CharacterPipeline(models, StyleGate(), log, onEvent = { events += it }).run("toy-car-02", image, refs, "")

        assertEquals(2, result.attempts)
        assertTrue(result.gatePassed)
        assertEquals(0.85f, result.gateScore, 1e-4f)
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout", "stylize", "gate", "rescore", "stylize", "gate", "rescore", "sheet"), c.stages.map { it.name })
        val gates = c.stages.filter { it.name == "gate" }
        assertEquals(listOf(false, true), gates.map { it.ok })
        assertEquals(listOf(0.002, 0.002), gates.map { it.costUsd }, "each gate is charged what was metered, never the estimate")
        assertTrue(gates.all { it.measured })
        assertEquals(1, c.retries)
        assertTrue(c.gatePassed)
        assertEquals(
            listOf(PipelineEvent.GateScored(1, 0f, false), PipelineEvent.GateScored(2, 0.85f, true)),
            events.filterIsInstance<PipelineEvent.GateScored>(),
        )
        for ((i, g) in gates.withIndex()) {
            val scored = events.indexOf(PipelineEvent.GateScored(i + 1, if (g.ok) 0.85f else 0f, g.ok))
            val finished = events.indexOf(PipelineEvent.StageFinished("gate", i + 1, g.ok, g.ms))
            assertTrue(scored in 0 until finished, "attempt ${i + 1}: GateScored before StageFinished(gate)")
        }
        assertTrue(events.last() is PipelineEvent.Finished)

        // Nothing metered on the failed call: the gate stage is charged 0, not the estimate.
        val log2 = metrics()
        CharacterPipeline(FlakyScore(FakeModels(scores = listOf(0.85f)), log2, failOn = setOf(1), scoreUsd = 0.0), StyleGate(), log2)
            .run("toy-car-02", image, refs, "")
        assertEquals(0.0, log2.toRun().characters.single().stages.first { it.name == "gate" }.costUsd)
    }

    @Test
    fun scoreErrorOnEveryAttemptShipsUnpassed() = runTest {
        val log = metrics()
        val models = FlakyScore(FakeModels(), log, failOn = setOf(1, 2, 3))

        val result = CharacterPipeline(models, StyleGate(), log, maxRetries = 2).run("toy-car-02", image, refs, "")

        assertEquals(3, result.attempts)
        assertFalse(result.gatePassed)
        assertEquals(0f, result.gateScore)
        assertEquals(3, models.scoreCalls)
        val c = log.toRun().characters.single()
        val gates = c.stages.filter { it.name == "gate" }
        assertEquals(listOf(1, 2, 3), gates.map { it.attempt })
        assertTrue(gates.none { it.ok })
        assertEquals(3, c.stages.count { it.name == "stylize" })
        assertEquals("sheet", c.stages.last().name)
        assertTrue(c.stages.last().ok)
        assertFalse(c.gatePassed)
        assertEquals(2, c.retries)

        // The deterministic half throwing is the same failed attempt.
        val log2 = metrics()
        val det = CharacterPipeline(
            FakeModels(scores = listOf(0.9f)), StyleGate(), log2,
            rasterDecoder = { b -> if (b in refs) null else error("corrupt PNG") }, // refs decode, the sprite throws
        ).run("cat-01", image, refs, "")
        assertEquals(3, det.attempts)
        assertFalse(det.gatePassed)
        assertEquals(3, log2.toRun().characters.single().stages.count { it.name == "gate" && !it.ok })
    }

    @Test
    fun throwingListenerOnAPassingGateRecordsOneGateStage() = runTest {
        // The listener throws on the gate's StageFinished, after metrics.stage was written: no second gate stage, no
        // second GateScored, no retry. A throwing listener is not a model failure: the character fails.
        val log = metrics()
        val models = FakeModels(scores = listOf(0.9f))
        val events = ArrayList<PipelineEvent>()
        assertFailsWith<MetaError> {
            CharacterPipeline(models, StyleGate(), log, onEvent = { e ->
                events += e
                if (e is PipelineEvent.StageFinished && e.stage == "gate") error("listener bug")
            }).run("cat-01", image, refs, "")
        }
        val gates = log.toRun().characters.single().stages.filter { it.name == "gate" }
        assertEquals(1, gates.size, "exactly one gate stage for attempt 1")
        assertTrue(gates.single().ok)
        assertEquals(listOf(PipelineEvent.GateScored(1, 0.9f, true)), events.filterIsInstance<PipelineEvent.GateScored>())
        assertEquals(1, models.stylizeCalls, "a passing attempt is not retried")
        assertTrue(events.last() is PipelineEvent.Failed)
    }

    @Test
    fun strayCancellationInTheScoreIsAFailedAttempt() = runTest {
        // A timeout surfacing as CancellationException while the pipeline is alive: a failed attempt, then a retry.
        val log = metrics()
        val stray = FlakyScore(
            FakeModels(scores = listOf(0.85f)), log, failOn = setOf(1),
            error = kotlinx.coroutines.CancellationException("timeout"),
        )
        val result = CharacterPipeline(stray, StyleGate(), log).run("cat-01", image, refs, "")
        assertEquals(2, result.attempts)
        assertTrue(result.gatePassed)
        assertEquals(listOf(false, true), log.toRun().characters.single().stages.filter { it.name == "gate" }.map { it.ok })

        // A real cancellation of the caller during the score call still propagates: no GateScored, no retry.
        val slow = FakeModels(delays = mapOf("scoreStyle" to 5_000L))
        val events = ArrayList<PipelineEvent>()
        val job = launch { CharacterPipeline(slow, StyleGate(), metrics(), onEvent = { events += it }).run("cat-01", image, refs, "") }
        delay(1_000L)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, slow.scoreCalls)
        assertEquals(1, slow.stylizeCalls)
        assertTrue(events.none { it is PipelineEvent.GateScored || it is PipelineEvent.Finished })
    }

    @Test
    fun deterministicHalfUsesMattedRefs() = runTest {
        val matted = ArrayList<String>()
        val decoded = ArrayList<String>()
        val matter = { b: ByteArray ->
            val s = b.decodeToString()
            matted += s
            if (s == "r2") null else "m:$s".encodeToByteArray() // r2's matte fails: used as is
        }
        val pipeline = CharacterPipeline(
            FakeModels(scores = listOf(0.5f, 0.9f), sprite = "PNG".encodeToByteArray()), StyleGate(), metrics(),
            rasterDecoder = { b -> decoded += b.decodeToString(); null },
            spriteMatter = matter,
        )
        pipeline.run("cat-01", image, listOf("r1".encodeToByteArray(), "r2".encodeToByteArray()), "")

        assertEquals(listOf("r1", "r2", "PNG", "PNG"), matted, "refs matted once per run, the sprite per attempt")
        assertEquals(listOf("m:r1", "r2", "m:PNG", "m:PNG"), decoded, "matted refs once, the matted sprite once per attempt (both looks share it)")
    }

    @Test
    fun cutoutFailureStillFailsTheCharacter() = runTest {
        val models = FakeModels(segmentError = MetaError.Http(503, "down"))
        val log = metrics()
        assertFailsWith<MetaError.Http> { CharacterPipeline(models, StyleGate(), log).run("cat-01", image, refs, "a cat") }
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout"), c.stages.map { it.name })
        assertEquals(0.0, c.stages.single().costUsd, "failed cutout, nothing metered: not charged")
        assertFalse(c.gatePassed)
        assertEquals(0, models.calls.count { it.startsWith("characterSheet:") }, "the sheet never starts without a cutout")
    }

    @Test
    fun pipelineDownscalesModelInputsOncePerRun() = runTest {
        val models = FakeModels(scores = listOf(0.5f, 0.9f), sprite = "PNG".encodeToByteArray())
        val resized = ArrayList<String>()
        val resizer = { b: ByteArray, max: Int -> "${b.decodeToString()}@$max".also { resized += it }.encodeToByteArray() }
        val pipeline = CharacterPipeline(models, StyleGate(), metrics(), imageResizer = resizer)

        val result = pipeline.run("cat-01", "photo".encodeToByteArray(), listOf("r1".encodeToByteArray(), "r2".encodeToByteArray()), "")

        assertEquals(2, result.attempts)
        assertEquals(listOf("photo@1024", "photo@1024"), models.stylizeInputs.map { it.decodeToString() })
        assertEquals(listOf("photo@512"), models.sheetImages.map { it!!.decodeToString() })
        assertEquals(listOf("PNG@512", "PNG@512"), models.scoredImages.map { it.decodeToString() })
        assertTrue(models.scoredRefs.all { refs -> refs.map { it.decodeToString() } == listOf("r1@512", "r2@512") })
        assertEquals(listOf("r1@1024", "r1@512"), resized.filter { it.startsWith("r1") }, "refs scaled once per run, not per attempt")
        assertEquals("PNG", result.sprite.decodeToString(), "the shipped sprite stays full size")
    }

    @Test
    fun eventsFollowStageOrder() = runTest {
        fun label(e: PipelineEvent): String = when (e) {
            is PipelineEvent.StageStarted -> "+${e.stage}${e.attempt}"
            is PipelineEvent.StageFinished -> "-${e.stage}${e.attempt}${if (e.ok) "" else "!"}"
            is PipelineEvent.GateScored -> "score${e.attempt}${if (e.passed) "" else "!"}"
            is PipelineEvent.Finished -> "done"
            is PipelineEvent.Failed -> "failed"
        }

        // First try, with a matter.
        val first = ArrayList<PipelineEvent>()
        val ok = CharacterPipeline(FakeModels(scores = listOf(0.9f)), StyleGate(), metrics(), spriteMatter = { it }, onEvent = { first += it })
            .run("cat-01", image, refs, "")
        assertEquals(
            listOf(
                "+cutout1", "-cutout1", "+sheet1", "+stylize1", "-stylize1", "+matte1", "-matte1",
                "+gate1", "+rescore1", "score1", "-gate1", "-rescore1!", "-sheet1", "done",
            ),
            first.map(::label),
        )
        assertEquals(ok, (first.last() as PipelineEvent.Finished).result)
        assertEquals(PipelineEvent.GateScored(1, 0.9f, true), first.single { it is PipelineEvent.GateScored })

        // One retry, no matter (no matte events).
        val retry = ArrayList<PipelineEvent>()
        CharacterPipeline(FakeModels(scores = listOf(0.5f, 0.8f)), StyleGate(), metrics(), onEvent = { retry += it })
            .run("cat-01", image, refs, "")
        assertEquals(
            listOf(
                "+cutout1", "-cutout1", "+sheet1",
                "+stylize1", "-stylize1", "+gate1", "+rescore1", "score1!", "-gate1!", "-rescore1!",
                "+stylize2", "-stylize2", "+gate2", "+rescore2", "score2", "-gate2", "-rescore2!",
                "-sheet1", "done",
            ),
            retry.map(::label),
        )

        // A failed cutout ends with Failed, after the stage's own failure.
        val failed = ArrayList<PipelineEvent>()
        assertFailsWith<MetaError> {
            CharacterPipeline(FakeModels(segmentError = MetaError.Http(503, "down")), StyleGate(), metrics(), onEvent = { failed += it })
                .run("cat-01", image, refs, "")
        }
        assertEquals(listOf("+cutout1", "-cutout1!", "failed"), failed.map(::label))
    }

    @Test
    fun gateScoreExactlyThresholdPasses() {
        val gate = StyleGate(threshold = 0.7f)
        assertTrue(gate.passes(0.7f))
        assertTrue(gate.passes(gate.combine(0.7f, null)))
        assertTrue(gate.passes(gate.combine(0.7f, 0.7f)))
        assertFalse(gate.passes(0.699f))
        assertFalse(gate.passes(gate.combine(0.7f, 0.69f)))
        // 0.6 * 0.5 + 0.4 * 1.0 = 0.7
        assertTrue(gate.passes(gate.combine(0.5f, 1.0f)))
    }

    @Test
    fun colourFreeLookPassesTheAttempt() = runTest {
        // A red-and-black ladybug: the style look fails it on palette, the colour-free look passes the same sprite.
        val models = FakeModels(scores = listOf(0.3f), techniqueScores = listOf(0.9f))
        val log = metrics()
        val events = ArrayList<PipelineEvent>()

        val result = CharacterPipeline(models, StyleGate(), log, onEvent = { events += it }).run("ladybug-01", image, refs, "")

        assertTrue(result.gatePassed)
        assertEquals(1, result.attempts, "passed first try: no second stylize")
        assertEquals(0.9f, result.gateScore)
        assertEquals(1, models.stylizeCalls)
        assertEquals(1, models.techniqueCalls)
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout", "stylize", "gate", "rescore", "sheet"), c.stages.map { it.name })
        assertEquals(listOf(false, true), c.stages.filter { it.name == "gate" || it.name == "rescore" }.map { it.ok })
        assertTrue(c.gatePassed)
        assertEquals(0, c.retries, "counts as a first-try pass")
        assertEquals(listOf(PipelineEvent.GateScored(1, 0.9f, true)), events.filterIsInstance<PipelineEvent.GateScored>())
    }

    @Test
    fun bothLooksRunInParallelEveryAttempt() = runTest {
        // Attempt 1: style 0.4, colour-free 0.5 -> fails with the higher (0.5). Attempt 2: style 0.85 passes; the
        // colour-free look (0.5 again) ran anyway, alongside it.
        val models = FakeModels(
            scores = listOf(0.4f, 0.85f), techniqueScores = listOf(0.5f),
            delays = mapOf("stylize" to 5_000L, "scoreStyle" to 2_000L, "scoreTechnique" to 3_000L),
        )
        val log = MetricsLog(source = "cli", provider = "fixture", device = "test", clock = { testScheduler.currentTime })
        val events = ArrayList<PipelineEvent>()

        val result = CharacterPipeline(models, StyleGate(), log, onEvent = { events += it }).run("cat-01", image, refs, "")

        assertTrue(result.gatePassed)
        assertEquals(2, result.attempts)
        assertEquals(2, models.techniqueCalls)
        assertEquals(
            listOf(PipelineEvent.GateScored(1, 0.5f, false), PipelineEvent.GateScored(2, 0.85f, true)),
            events.filterIsInstance<PipelineEvent.GateScored>(),
        )
        val c = log.toRun().characters.single()
        assertEquals(listOf("cutout", "stylize", "gate", "rescore", "stylize", "gate", "rescore", "sheet"), c.stages.map { it.name })
        assertEquals(listOf(2_000L, 3_000L, 2_000L, 3_000L), c.stages.filter { it.name == "gate" || it.name == "rescore" }.map { it.ms })
        // Each attempt waits for the slower look (3 s), not the sum (5 s): 2 x (5 + 3) = 16 s.
        assertEquals(16_000L, c.totalMs)
    }

    @Test
    fun sparkSeesTheFirstThreeRefsTheDeterministicHalfAll() = runTest {
        val models = FakeModels(scores = listOf(0.9f))
        val seen = ArrayList<Int>()
        val five = (1..5).map { "r$it".encodeToByteArray() }
        CharacterPipeline(models, StyleGate(), metrics(), rasterDecoder = { b -> seen += b.size; null })
            .run("cat-01", image, five, "")
        assertEquals(listOf("r1", "r2", "r3"), models.scoredRefs.single().map { it.decodeToString() })
        assertEquals(CharacterPipeline.SPARK_MAX_REFS, models.scoredRefs.single().size)
        assertEquals(6, seen.size, "the deterministic half decodes all 5 refs and the sprite once")
    }

    @Test
    fun bothLooksThrowingFailTheAttempt() = runTest {
        val inner = FakeModels(scores = listOf(0.9f))
        var calls = 0
        val models = object : MetaModels by inner {
            override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
                if (++calls == 1) throw MetaError.Parse("empty content, finish_reason=length") else inner.scoreStyle(ref, image, refs)
            override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
                // A timeout surfacing as cancellation while the pipeline is alive: a failed look, not a dead character.
                if (calls == 1) throw kotlinx.coroutines.CancellationException("timeout") else 0f
        }
        val log = metrics()
        val events = ArrayList<PipelineEvent>()

        val result = CharacterPipeline(models, StyleGate(), log, onEvent = { events += it }).run("cat-01", image, refs, "")

        assertTrue(result.gatePassed)
        assertEquals(2, result.attempts)
        assertEquals(PipelineEvent.GateScored(1, 0f, false), events.filterIsInstance<PipelineEvent.GateScored>().first())
        val looks = log.toRun().characters.single().stages.filter { it.attempt == 1 && (it.name == "gate" || it.name == "rescore") }
        assertEquals(listOf(false, false), looks.map { it.ok })
        assertEquals(listOf(0.0, 0.0), looks.map { it.costUsd }, "nothing metered, nothing charged")
    }

    @Test
    fun rescoreErrorKeepsTheStyleScore() = runTest {
        val inner = FakeModels(scores = listOf(0.6f))
        val models = object : MetaModels by inner {
            override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
                throw MetaError.Parse("empty content, finish_reason=length")
        }
        val log = metrics()

        val result = CharacterPipeline(models, StyleGate(), log, maxRetries = 0).run("cat-01", image, refs, "")

        assertFalse(result.gatePassed)
        assertEquals(0.6f, result.gateScore, "the style look's score stands")
        val c = log.toRun().characters.single()
        val rescore = c.stages.single { it.name == "rescore" }
        assertFalse(rescore.ok)
        assertEquals(0.0, rescore.costUsd, "a failed rescore that metered nothing is not charged")
        assertEquals("sheet", c.stages.last().name)
    }

    @Test
    fun techniqueScoreIgnoresPalette() {
        val gate = StyleGate()
        val ref = sample(body = argbOf(255, 240, 220, 180)) // pastel beige, black outline
        val ladybug = sample() // red, the same outline
        val det = assertNotNull(gate.deterministicScore(ladybug, listOf(ref)))
        assertTrue(det < 0.9f, "the style half sees the palette difference, got $det")
        assertEquals(1f, assertNotNull(gate.techniqueScore(ladybug, listOf(ref))), 1e-5f)

        // Thicker ink scores lower; the same null cases as the deterministic half.
        val inky = ArrayRaster.solid(16, 16, argbOf(255, 10, 10, 10))
        assertTrue(gate.techniqueScore(inky, listOf(ref))!! < 0.5f)
        assertEquals(null, gate.techniqueScore(null, listOf(ref)))
        assertEquals(null, gate.techniqueScore(ladybug, emptyList()))
        assertEquals(null, gate.techniqueScore(ArrayRaster(16, 16), listOf(ref)), "fully transparent sprite")
    }

    @Test
    fun sheetUnknownAbilityDefaultsToJumpAndPadsLines() = runTest {
        val raw = """
            Sure! Here is the sheet:
            ```json
            {"name": "Бөрі", "personality": "Fast and loyal.", "ability": "teleport",
             "lines": {"kk": ["Сәлем!", "Жүгірейік!"], "ru": ["Привет!", "Бежим!", "Я тут!"]}}
            ```
        """.trimIndent()
        val parsed = SheetParser.parse(raw)
        assertEquals(Ability.JUMP, parsed.ability)
        assertEquals("Бөрі", parsed.name)

        // Through the pipeline the sheet is normalised again: 5 lines for kk and ru, no matter what the model returned.
        val models = FakeModels(sheet = parsed.copy(lines = mapOf("kk" to listOf("Сәлем!"), "ru" to emptyList())))
        val result = CharacterPipeline(models, StyleGate(), metrics()).run("wolf", image, refs, "a wolf")
        assertEquals(Ability.JUMP, result.sheet.ability)
        assertEquals(5, result.sheet.lines.getValue("kk").size)
        assertEquals(5, result.sheet.lines.getValue("ru").size)
        assertEquals("Сәлем!", result.sheet.lines.getValue("kk").first())
        assertTrue(result.sheet.lines.values.flatten().all { it.isNotBlank() })
    }

    @Test
    fun styleGateIdenticalPalettesScoreOne() {
        val img = sample()
        val gate = StyleGate()
        val det = gate.deterministicScore(img, listOf(img, img))
        assertNotNull(det)
        assertEquals(1f, det, 1e-5f)
        assertEquals(1f, gate.combine(1f, det), 1e-5f)

        // A very different palette (and no outline) scores clearly lower.
        val other = ArrayRaster.solid(16, 16, argbOf(255, 20, 200, 240))
        val low = gate.deterministicScore(other, listOf(img))
        assertNotNull(low)
        assertTrue(low < 0.8f, "expected a low deterministic score, got $low")

        // No decoder / no refs -> null -> model score only.
        assertEquals(null, gate.deterministicScore(null, listOf(img)))
        assertEquals(null, gate.deterministicScore(img, emptyList()))
        assertEquals(0.42f, gate.combine(0.42f, null), 1e-5f)
    }

    /** 16x16: a [body]-coloured (default red) body with a black 1px outline, transparent corners. */
    private fun sample(body: Int = argbOf(255, 220, 40, 40)): RasterImage {
        val r = ArrayRaster(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            val edge = x == 2 || x == 13 || y == 2 || y == 13
            val inside = x in 2..13 && y in 2..13
            r.set(x, y, when {
                !inside -> 0
                edge -> argbOf(255, 10, 10, 10)
                else -> body
            })
        }
        return r
    }
}
