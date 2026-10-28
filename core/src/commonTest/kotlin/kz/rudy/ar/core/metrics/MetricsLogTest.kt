package kz.rudy.ar.core.metrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.meta.DiskFixtureSource
import kz.rudy.ar.core.meta.FakeModels
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.SegmentResult
import kz.rudy.ar.core.pipeline.CharacterPipeline
import kz.rudy.ar.core.pipeline.StyleGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsLogTest {
    /** [FakeModels] that reports a measured cost to [meter] like MetaApi does: per call, by job (the sheet reports none). */
    private class MeteredModels(private val inner: FakeModels, private val meter: MetricsLog) : MetaModels by inner {
        override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult =
            inner.segment(ref, image, prompts).also { prompts.forEach { _ -> meter.record(Jobs.segmentJob(prompts), ref, 0.0025) } }
        override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray =
            inner.stylize(ref, cutout, refs).also { meter.record(Jobs.MUSE_STYLIZE, ref, 0.01) }
        override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
            inner.scoreStyle(ref, image, refs).also { meter.record(Jobs.SPARK_SCORE, ref, 0.0031) }
        override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
            inner.scoreTechnique(ref, image, refs).also { meter.record(Jobs.SPARK_SCORE_TECHNIQUE, ref, 0.0029) }
        override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet =
            inner.characterSheet(ref, description, image)
    }

    @Test
    fun measuredCostOverridesEstimate() = runTest {
        val log = MetricsLog("cli", "meta", "mac", clock = { 0L })
        val mark = log.costMark(Jobs.MUSE_STYLIZE)
        assertEquals(StageCost(0.02, measured = false), log.costSince(mark, 0.02), "nothing recorded: the estimate")
        log.record(Jobs.MUSE_STYLIZE, "cat-01", 0.01)
        log.record(Jobs.SPARK_SCORE, "cat-01", 0.5) // another job: not part of this stage
        assertEquals(StageCost(0.01, measured = true), log.costSince(mark, 0.02))
        val mark2 = log.costMark(Jobs.MUSE_STYLIZE)
        log.record(Jobs.MUSE_STYLIZE, "cat-01", 0.0) // a measured zero is still a measurement
        assertEquals(StageCost(0.0, measured = true), log.costSince(mark2, 0.02))
        assertEquals(0.01, log.measuredUsd(Jobs.MUSE_STYLIZE), 1e-12)

        // Through the pipeline: cutout, stylize and gate are measured, the sheet (no usage) falls back to the table.
        val models = FakeModels(scores = listOf(0.5f, 0.9f))
        val run = MetricsLog("cli", "meta", "mac", clock = { 0L })
        CharacterPipeline(MeteredModels(models, run), StyleGate(), run).run("cat-01", byteArrayOf(1), emptyList(), "")
        val stages = run.toRun().characters.single().stages
        // The two looks run at once; each stage gets its own call's cost (the colour-free one meters under its own job).
        assertEquals(listOf("cutout", "stylize", "gate", "rescore", "stylize", "gate", "rescore", "sheet"), stages.map { it.name })
        assertEquals(listOf(0.005, 0.01, 0.0031, 0.0029, 0.01, 0.0031, 0.0029, run.costs.sheetUsd), stages.map { it.costUsd })
        assertEquals(listOf(true, true, true, true, true, true, true, false), stages.map { it.measured })
        assertEquals(0.0370 + run.costs.sheetUsd, run.toRun().characters.single().totalCostUsd, 1e-9)
    }

    @Test
    fun costMeterIsThreadSafe() = runTest {
        val log = MetricsLog("cli", "meta", "mac", clock = { 0L })
        withContext(Dispatchers.Default) {
            repeat(8) { worker ->
                launch {
                    repeat(1_000) {
                        log.record(if (worker % 2 == 0) Jobs.SAM_CUTOUT else Jobs.SPARK_SHEET, "r", 0.0025)
                        log.record(Jobs.MUSE_STYLIZE, "r", 0.01)
                    }
                }
            }
        }
        assertEquals(10.0, log.measuredUsd(Jobs.SAM_CUTOUT))
        assertEquals(10.0, log.measuredUsd(Jobs.SPARK_SHEET))
        assertEquals(80.0, log.measuredUsd(Jobs.MUSE_STYLIZE))
        assertEquals(8_000, log.costMark(Jobs.MUSE_STYLIZE).calls)
    }

    @Test
    fun runsWithoutTheMeasuredFieldStillParse() {
        val old = """{"schema":1,"runId":"r","source":"cli","provider":"fixture","device":"mac",
            "characters":[{"input":"a","stages":[{"name":"cutout","ms":1,"costUsd":0.004,"attempt":1,"ok":true}],
            "gateScore":0.8,"gatePassed":true,"retries":0,"totalMs":1,"totalCostUsd":0.004}],"scans":[],
            "summary":{"n":1,"gatePassRateFirstTry":1.0,"p50Ms":1,"p90Ms":1,"costPerCharacterUsd":0.004}}"""
        assertFalse(MetricsLog.fromJson(old).characters.single().stages.single().measured)
        // Every committed run in runs/ still parses.
        val repo = DiskFixtureSource.repoRoot()
        val runs = repo.list("runs").filter { it.endsWith(".json") }
        for (name in runs) assertEquals(1, MetricsLog.fromJson(repo.readText("runs/$name")!!).schema, name)
    }
    private fun log(totals: List<Long>, passFirst: List<Boolean>): MetricsLog {
        var t = 1_793_196_202_000L // 2026-10-28T14:03:22Z
        val log = MetricsLog("cli", "fixture", "mac", clock = { t })
        for ((i, total) in totals.withIndex()) {
            log.startCharacter("drawings/sample-${i + 1}.png")
            log.stage("cutout", 100, 1, true, log.costs.cutoutUsd)
            if (passFirst[i]) {
                log.stage("stylize", 200, 1, true, log.costs.stylizeUsd)
                log.stage("gate", 50, 1, true, log.costs.scoreUsd)
            } else {
                log.stage("stylize", 200, 1, true, log.costs.stylizeUsd)
                log.stage("gate", 50, 1, false, log.costs.scoreUsd)
                log.stage("stylize", 200, 2, true, log.costs.stylizeUsd)
                log.stage("gate", 50, 2, true, log.costs.scoreUsd)
            }
            log.stage("sheet", 30, 1, true, log.costs.sheetUsd)
            t += total
            log.endCharacter(gateScore = if (passFirst[i]) 0.85f else 0.72f, gatePassed = true, retries = if (passFirst[i]) 0 else 1)
        }
        return log
    }

    @Test
    fun metricsSummaryComputesP50AndPassRate() {
        val log = log(
            totals = listOf(30_000, 10_000, 50_000, 20_000, 40_000),
            passFirst = listOf(true, true, false, true, false),
        )
        val run = log.toRun()
        assertEquals("2026-10-28T14-03-22Z", run.runId)
        assertEquals(5, run.summary.n)
        assertEquals(0.6f, run.summary.gatePassRateFirstTry, 1e-6f)
        assertEquals(30_000L, run.summary.p50Ms)
        assertEquals(50_000L, run.summary.p90Ms)
        // 3 first-try characters at 0.029 and 2 one-retry characters at 0.048 -> 0.0366 average (CostTable defaults).
        assertEquals(0.0366, run.summary.costPerCharacterUsd, 1e-9)
        assertEquals(listOf(30_000L, 10_000L, 50_000L, 20_000L, 40_000L), run.characters.map { it.totalMs })

        log.recordScan(5, 4800, listOf(1.2f, 0.8f))
        assertEquals(1, log.toRun().scans.size)

        val table = MetricsLog.summarise(listOf(run))
        assertTrue(table.contains("| 2026-10-28T14-03-22Z | cli | fixture | mac | 5 | 60.0% | 30000 | 50000 | $0.0366 |"), table)
        assertEquals(Summary(0, 0f, 0, 0, 0.0), MetricsLog.summaryOf(emptyList()))
    }

    @Test
    fun metricsRunJsonRoundTrip() {
        val log = log(totals = listOf(31_200, 28_000), passFirst = listOf(true, false))
        log.recordScan(5, 4800, listOf(1.2f, 0.8f, 2.1f, 1.5f, 0.9f))
        val run = log.toRun()
        val json = log.toJson()

        assertTrue(json.contains("\"schema\": 1"), json)
        assertTrue(json.contains("\"runId\": \"2026-10-28T14-03-22Z\""), json)
        assertTrue(json.contains("\"gatePassRateFirstTry\""), json)
        assertTrue(json.contains("\"withinCm\""), json)
        // Field order of the documented schema.
        val order = listOf("\"schema\"", "\"runId\"", "\"source\"", "\"provider\"", "\"device\"", "\"characters\"", "\"scans\"", "\"summary\"")
        val positions = order.map { json.indexOf(it) }
        assertEquals(positions.sorted(), positions, "fields must follow the schema order")

        val back = MetricsLog.fromJson(json)
        assertEquals(run, back)
        assertEquals(run.summary, MetricsLog.summaryOf(back.characters))
    }
}
