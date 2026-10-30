package kz.rudy.ar.core.meta

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfflineGuardTest {
    private val img = byteArrayOf(1, 2, 3)

    @Test
    fun networkErrorMarksOfflineAndRethrows() = runTest {
        val err = MetaError.Network("x")
        val guard = OfflineGuard(FakeModels(segmentError = err))
        val thrown = assertFailsWith<MetaError.Network> { guard.segment("t", img, listOf("cup")) }
        assertSame(err, thrown)
        assertTrue(guard.offline.value)
        assertEquals(1, guard.transitions)
    }

    @Test
    fun successMarksOnlineAgain() = runTest {
        val fake = FakeModels(segmentError = MetaError.Network("x"))
        val guard = OfflineGuard(fake)
        assertFailsWith<MetaError.Network> { guard.segment("t", img, listOf("cup")) }
        assertFailsWith<MetaError.Network> { guard.segment("t", img, listOf("cup")) }
        assertEquals(1, guard.transitions, "a repeated failure is not a change")
        fake.segmentError = null
        assertEquals(SegmentResult(fake.masks), guard.segment("t", img, listOf("cup")))
        assertFalse(guard.offline.value)
        assertEquals(2, guard.transitions)
        guard.reset()
        assertEquals(2, guard.transitions, "reset while online is not a change")
    }

    @Test
    fun httpErrorDoesNotChangeTheFlag() = runTest {
        val err = MetaError.Http(500, "boom")
        val guard = OfflineGuard(FakeModels(segmentError = err))
        assertSame(err, assertFailsWith<MetaError.Http> { guard.segment("t", img, listOf("cup")) })
        assertFalse(guard.offline.value)
        assertEquals(0, guard.transitions)
    }

    @Test
    fun allSixCallsAreForwarded() = runTest {
        val fake = FakeModels(scores = listOf(0.4f), techniqueScores = listOf(0.6f), transcript = "ok")
        val guard = OfflineGuard(fake)
        assertEquals(SegmentResult(fake.masks), guard.segment("a", img, listOf("drawing")))
        assertSame(fake.sprite, guard.stylize("b", img, listOf(img)))
        assertEquals(0.4f, guard.scoreStyle("c", img, listOf(img)))
        assertEquals(0.6f, guard.scoreTechnique("d", img, listOf(img)))
        assertEquals(fake.sheet, guard.characterSheet("e", "hint", img))
        assertEquals("ok", guard.transcribe("f", img, "kk"))
        assertEquals(
            listOf("segment:a", "stylize:b", "scoreStyle:c", "scoreTechnique:d", "characterSheet:e", "transcribe:f"),
            fake.calls,
        )
        assertFalse(guard.offline.value)
        assertEquals(0, guard.transitions)
    }

    @Test
    fun cancellationLeavesTheFlagAlone() = runTest {
        val fake = FakeModels(segmentError = MetaError.Network("x"), sheetError = CancellationException())
        val guard = OfflineGuard(fake)
        assertFailsWith<MetaError.Network> { guard.segment("t", img, listOf("cup")) }
        assertFailsWith<CancellationException> { guard.characterSheet("s", "") }
        assertTrue(guard.offline.value, "offline stays offline")
        assertEquals(1, guard.transitions)
        guard.reset()
        assertFalse(guard.offline.value)
        assertFailsWith<CancellationException> { guard.characterSheet("s", "") }
        assertFalse(guard.offline.value, "online stays online")
        assertEquals(2, guard.transitions)
    }
}
