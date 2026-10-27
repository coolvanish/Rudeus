package kz.rudy.ar.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsLogTest {
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
        // 3 first-try characters at 0.028 and 2 one-retry characters at 0.050 -> 0.0368 average.
        assertEquals(0.0368, run.summary.costPerCharacterUsd, 1e-9)
        assertEquals(listOf(30_000L, 10_000L, 50_000L, 20_000L, 40_000L), run.characters.map { it.totalMs })

        log.recordScan(5, 4800, listOf(1.2f, 0.8f))
        assertEquals(1, log.toRun().scans.size)

        val table = MetricsLog.summarise(listOf(run))
        assertTrue(table.contains("| 2026-10-28T14-03-22Z | cli | fixture | mac | 5 | 60.0% | 30000 | 50000 | $0.0368 |"), table)
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
