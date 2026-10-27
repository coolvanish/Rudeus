package kz.rudy.ar.core.meta

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The per-prompt loop of [MetaApi.segment], driven by a fake call instead of HTTP (no MockEngine dependency). */
class SegmentEachTest {
    private fun mask(label: String) = MaskResult.fromBits(label, 2, 1, booleanArrayOf(true, false))

    @Test
    fun oneFailedPromptLeavesAnEmptyMaskAndTheScanSurvives() = runTest {
        val result = segmentEach(listOf("cup", "book", "phone"), maxConcurrency = 3) { p ->
            delay(if (p == "cup") 30 else 10) // finish out of order
            if (p == "book") throw MetaError.Http(429, "rate limited") else mask(p)
        }
        assertEquals(listOf("cup", "book", "phone"), result.masks.map { it.label }, "prompt order kept")
        assertEquals(listOf("book"), result.failedPrompts)
        assertTrue(result.masks[1].isEmpty)
        assertEquals(2 to 1, result.masks[1].width to result.masks[1].height, "failed prompt gets the frame size")
        assertEquals(1, result.masks[0].area)
    }

    @Test
    fun everyPromptFailingRethrowsTheFirstError() = runTest {
        val first = MetaError.Network("timeout")
        val e = assertFailsWith<MetaError.Network> {
            segmentEach(listOf("cup", "book"), 3) { p -> delay(if (p == "cup") 20 else 1); throw if (p == "cup") first else MetaError.Parse("x") }
        }
        assertSame(first, e)
        assertEquals(SegmentResult(emptyList()), segmentEach(emptyList(), 3) { error("never called") })
    }

    @Test
    fun atMostThreeRequestsAreInFlight() = runTest {
        var inFlight = 0
        var peak = 0
        val prompts = (1..8).map { "p$it" }
        val result = segmentEach(prompts, MetaApi.MAX_SAM_CONCURRENCY) { p ->
            inFlight++; peak = maxOf(peak, inFlight)
            delay(100)
            inFlight--
            mask(p)
        }
        assertEquals(3, peak)
        assertEquals(prompts, result.masks.map { it.label })
        assertTrue(result.failedPrompts.isEmpty())
    }
}
