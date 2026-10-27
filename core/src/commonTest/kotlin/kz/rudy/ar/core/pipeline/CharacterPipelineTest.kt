package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.io.argbOf
import kz.rudy.ar.core.meta.FakeModels
import kz.rudy.ar.core.meta.MetaError
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
        val models = FakeModels(scores = listOf(0.85f))
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
        assertEquals(listOf("segment:cat-01", "stylize:cat-01", "scoreStyle:cat-01", "characterSheet:cat-01"), models.calls)

        val run = log.toRun()
        val c = run.characters.single()
        assertEquals(listOf("cutout", "stylize", "gate", "sheet"), c.stages.map { it.name })
        assertTrue(c.stages.all { it.ok })
        assertEquals(0, c.retries)
        assertTrue(c.gatePassed)
        assertEquals(0.029, c.totalCostUsd, 1e-9) // CostTable: 0.005 + 0.01 + 0.009 + 0.005
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
        assertEquals(listOf("cutout", "stylize", "matte", "gate", "stylize", "matte", "gate", "sheet"), c.stages.map { it.name })
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
        assertEquals(listOf("cutout", "stylize", "gate", "stylize", "gate", "sheet"), c.stages.map { it.name })
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
        val models = FakeModels(scores = listOf(0.85f), sheetError = MetaError.Http(500, "boom"))
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
        assertEquals(listOf("m:r1", "r2", "m:PNG", "m:PNG"), decoded, "the gate decodes matted refs and matted sprites")
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
                "+gate1", "score1", "-gate1", "-sheet1", "done",
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
                "+stylize1", "-stylize1", "+gate1", "score1!", "-gate1!",
                "+stylize2", "-stylize2", "+gate2", "score2", "-gate2",
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

    /** 16x16: red body with a black 1px outline, transparent corners. */
    private fun sample(): RasterImage {
        val r = ArrayRaster(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            val edge = x == 2 || x == 13 || y == 2 || y == 13
            val inside = x in 2..13 && y in 2..13
            r.set(x, y, when {
                !inside -> 0
                edge -> argbOf(255, 10, 10, 10)
                else -> argbOf(255, 220, 40, 40)
            })
        }
        return r
    }
}
