package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * T38: several photos become characters in one go ("Add toys"), without playing them. The policy lives here, tested
 * and free of UI: at most [MAX_PHOTOS] per batch, [CONCURRENCY] pipelines in flight (seven 2048 px photos and
 * their Muse answers at once would be a memory and rate-limit gamble on the phone), and [MAX_ATTEMPTS] stylize attempts
 * per photo with nobody asked in between (`CharacterPipeline` with `maxRetries = MAX_ATTEMPTS - 1`, no reviewer): a
 * batch is for filling the shelf, not for perfecting one drawing.
 */
object BatchImport {
    const val MAX_PHOTOS = 7
    const val MAX_ATTEMPTS = 2
    const val CONCURRENCY = 3

    /** The photos a batch takes: the first [MAX_PHOTOS]; the picker already caps the choice, this is the belt. */
    fun take(photos: List<ByteArray>): List<ByteArray> = photos.take(MAX_PHOTOS)

    /**
     * Runs [one] for every index in `0 until count` with at most [concurrency] running at a time and returns one
     * [Result] per index, in index order, once all of them ended. A failed item is a failure in its slot and the others
     * carry on; cancelling the caller cancels every item still running (and the ones not started).
     */
    suspend fun <T> run(count: Int, concurrency: Int = CONCURRENCY, one: suspend (index: Int) -> T): List<Result<T>> {
        require(count >= 0) { "count must be >= 0, was $count" }
        require(concurrency >= 1) { "concurrency must be >= 1, was $concurrency" }
        if (count == 0) return emptyList()
        return coroutineScope {
            val permits = Semaphore(concurrency)
            List(count) { index ->
                async {
                    permits.withPermit {
                        try {
                            Result.success(one(index))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
