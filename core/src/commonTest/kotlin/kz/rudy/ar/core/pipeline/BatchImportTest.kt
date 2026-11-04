package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchImportTest {
    @Test
    fun itemsComeBackInOrderWhateverFinishesFirst() = runTest {
        // Later indices finish first (shorter delays); the list is still by index.
        val results = BatchImport.run(5, concurrency = 5) { i -> delay((5 - i) * 10L); "item-$i" }
        assertEquals(listOf("item-0", "item-1", "item-2", "item-3", "item-4"), results.map { it.getOrThrow() })
    }

    @Test
    fun neverMoreThanConcurrencyInFlight() = runTest {
        var inFlight = 0
        var peak = 0
        val results = BatchImport.run(BatchImport.MAX_PHOTOS, concurrency = BatchImport.CONCURRENCY) { i ->
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(10)
            inFlight--
            i
        }
        assertEquals(BatchImport.MAX_PHOTOS, results.size)
        assertEquals(BatchImport.CONCURRENCY, peak)
        assertEquals(0, inFlight)
    }

    @Test
    fun oneFailureLeavesTheOthersAlone() = runTest {
        val results = BatchImport.run(4, concurrency = 2) { i ->
            delay(5)
            if (i == 1) throw IllegalStateException("muse down") else i * 10
        }
        assertEquals(listOf(true, false, true, true), results.map { it.isSuccess })
        assertEquals("muse down", results[1].exceptionOrNull()?.message)
        assertEquals(listOf(0, 20, 30), listOf(results[0], results[2], results[3]).map { it.getOrThrow() })
    }

    @Test
    fun cancellingTheCallerCancelsEveryItem() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = 0
        var finished = 0
        val batch = async(start = CoroutineStart.UNDISPATCHED) {
            BatchImport.run(4, concurrency = 2) { i ->
                if (i == 0) started.complete(Unit)
                try {
                    delay(1_000)
                    finished++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled++
                    throw e
                }
            }
        }
        started.await()
        yield()
        batch.cancel()
        yield()
        assertTrue(batch.isCancelled)
        assertEquals(2, cancelled, "the two running items were cancelled") // the other two never started
        assertEquals(0, finished)
    }

    @Test
    fun takeKeepsTheFirstSeven() {
        val photos = List(9) { byteArrayOf(it.toByte()) }
        val taken = BatchImport.take(photos)
        assertEquals(BatchImport.MAX_PHOTOS, taken.size)
        assertEquals(photos.take(7), taken)
        assertEquals(3, BatchImport.take(photos.take(3)).size)
    }

    @Test
    fun zeroItemsIsAnEmptyList() = runTest {
        var ran = false
        val results = BatchImport.run(0) { ran = true }
        assertTrue(results.isEmpty())
        assertFalse(ran)
    }
}
