package kz.rudy.ar.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateCheckTest {
    private fun run(n: Int, rate: Float, p50: Long, cost: Double, provider: String = "meta") = MetricsRun(
        runId = "2026-10-28T12-00-00Z", source = "cli", provider = provider, device = "mac",
        characters = emptyList(), scans = emptyList(),
        summary = Summary(n = n, gatePassRateFirstTry = rate, p50Ms = p50, p90Ms = p50, costPerCharacterUsd = cost),
    )

    private fun GateReport.row(name: String) = rows.first { it.name == name }.verdict

    @Test
    fun gateCheckPassFailUntested() {
        // Exactly at every boundary that passes: 7/10, 39 999 ms, $0.05.
        val pass = GateCheck.check(run(10, 0.7f, 39_999, 0.05))
        assertEquals(Verdict.PASS, pass.row("gate pass 1st try"))
        assertEquals(Verdict.PASS, pass.row("p50"))
        assertEquals(Verdict.PASS, pass.row("cost per character"))
        assertEquals(Verdict.PASS, pass.verdict)
        assertNull(pass.refusal)

        // One step over each boundary fails.
        val fail = GateCheck.check(run(10, 0.6f, 40_000, 0.0501))
        assertEquals(Verdict.FAIL, fail.row("gate pass 1st try"))
        assertEquals(Verdict.FAIL, fail.row("p50"))
        assertEquals(Verdict.FAIL, fail.row("cost per character"))
        assertEquals(Verdict.FAIL, fail.verdict)
        assertEquals(Verdict.FAIL, GateCheck.check(run(10, 0.7f, 40_000, 0.05)).verdict, "p50 alone fails the gate")

        // n < 10: the pass rate is untested (observed rate still shown), the other rows still judge.
        val small = GateCheck.check(run(9, 1.0f, 20_000, 0.03))
        assertEquals(Verdict.UNTESTED, small.row("gate pass 1st try"))
        assertEquals(Verdict.PASS, small.row("p50"))
        assertEquals(Verdict.UNTESTED, small.verdict)
        val text = small.format()
        assertTrue("UNTESTED (n=9)" in text, text)
        assertTrue("100.0%" in text, text)
        assertEquals(Verdict.FAIL, GateCheck.check(run(9, 1.0f, 45_000, 0.03)).verdict, "a failing row beats untested")
        assertEquals(Verdict.UNTESTED, GateCheck.check(run(0, 0f, 0, 0.0)).verdict)

        // A fixture run is never a measurement, however good its numbers.
        val fixture = GateCheck.check(run(10, 1.0f, 100, 0.0, provider = "fixture"))
        assertEquals(Verdict.REFUSED, fixture.verdict)
        assertTrue(fixture.rows.all { it.verdict == Verdict.REFUSED })
        assertNotNull(fixture.refusal)
        assertTrue("PASS" !in fixture.format(), fixture.format())
    }
}
