package kz.rudy.ar.core.meta

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kz.rudy.ar.core.domain.CharacterSheet

/**
 * Decorates a [MetaModels]: forwards every call unchanged and tracks connectivity from the outcomes.
 * - A call that throws [MetaError.Network] -> [offline] = true (the error is rethrown as is).
 * - A call that returns normally -> [offline] = false.
 * - Any other failure (Http, Parse, Provider, FixtureMissing, CancellationException) leaves the flag unchanged and
 *   rethrows as is.
 *
 * Nothing falls back and nothing retries (T22): offline = bundled characters + the level already scanned + a badge.
 * Thread-safe: calls arrive from IO and the pipeline's second coroutine; the flag flips by compareAndSet, so
 * [transitions] counts real changes only, and only the caller that made a change counts it.
 */
class OfflineGuard(private val delegate: MetaModels) : MetaModels {
    private val state = MutableStateFlow(false)
    private val changes = MutableStateFlow(0)

    /** True after a [MetaError.Network], false again after the next successful call. Starts false. */
    val offline: StateFlow<Boolean> = state.asStateFlow()

    /** How many times [offline] actually changed value (for logging / tests). */
    val transitions: Int get() = changes.value

    /** Back to online, e.g. the player tapped Retry. Counts as a transition only if the flag was set. */
    fun reset() = set(false)

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult =
        track { delegate.segment(ref, image, prompts) }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray =
        track { delegate.stylize(ref, cutout, refs) }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
        track { delegate.scoreStyle(ref, image, refs) }

    override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
        track { delegate.scoreTechnique(ref, image, refs) }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet =
        track { delegate.characterSheet(ref, description, image) }

    override suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String =
        track { delegate.transcribe(ref, pcm16kMono, lang) }

    private suspend inline fun <T> track(call: () -> T): T {
        val result = try {
            call()
        } catch (e: MetaError.Network) {
            set(true)
            throw e
        }
        set(false)
        return result
    }

    private fun set(value: Boolean) {
        if (state.compareAndSet(!value, value)) changes.update { it + 1 }
    }
}
