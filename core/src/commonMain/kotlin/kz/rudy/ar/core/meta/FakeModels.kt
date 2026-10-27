package kz.rudy.ar.core.meta

import kotlinx.coroutines.delay
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet

/**
 * Scripted [MetaModels] for tests: [scores] are returned in order (last one repeats), the sheet and
 * masks are fixed, every call is appended to [calls] as `"job:ref"`. The images each call received are kept
 * ([stylizeInputs], [scoredImages], [scoredRefs], [sheetImages]); [delays] (method name -> ms, e.g. `"stylize"`)
 * makes a call suspend first, for concurrency tests under virtual time.
 */
class FakeModels(
    var scores: List<Float> = listOf(0.85f),
    var sheet: CharacterSheet = DEFAULT_SHEET,
    var masks: List<MaskResult> = listOf(DEFAULT_MASK),
    var sprite: ByteArray = byteArrayOf(0x50, 0x4E, 0x47),
    var transcript: String = "сәлем",
    /** When set, [segment] throws it (to exercise failure paths). */
    var segmentError: MetaError? = null,
    /** When set, [characterSheet] throws it (after its delay); any Throwable, e.g. a stray CancellationException. */
    var sheetError: Throwable? = null,
    /** When set, [stylize] throws it (after its delay). */
    var stylizeError: Throwable? = null,
    var delays: Map<String, Long> = emptyMap(),
) : MetaModels {
    val calls = ArrayList<String>()
    val stylizeInputs = ArrayList<ByteArray>()
    val scoredImages = ArrayList<ByteArray>()
    val scoredRefs = ArrayList<List<ByteArray>>()
    val sheetImages = ArrayList<ByteArray?>()
    private var scoreIndex = 0

    val stylizeCalls: Int get() = calls.count { it.startsWith("stylize:") }
    val scoreCalls: Int get() = calls.count { it.startsWith("scoreStyle:") }

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult {
        calls += "segment:$ref"
        pause("segment")
        segmentError?.let { throw it }
        return SegmentResult(masks)
    }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray {
        calls += "stylize:$ref"
        stylizeInputs += cutout
        pause("stylize")
        stylizeError?.let { throw it }
        return sprite
    }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        calls += "scoreStyle:$ref"
        scoredImages += image
        scoredRefs += refs
        pause("scoreStyle")
        if (scores.isEmpty()) return 0f
        val s = scores[scoreIndex.coerceAtMost(scores.lastIndex)]
        scoreIndex++
        return s
    }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet {
        calls += "characterSheet:$ref"
        sheetImages += image
        pause("characterSheet")
        sheetError?.let { throw it }
        return sheet
    }

    override suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String {
        calls += "transcribe:$ref"
        return transcript
    }

    private suspend fun pause(method: String) {
        delays[method]?.takeIf { it > 0 }?.let { delay(it) }
    }

    companion object {
        val DEFAULT_SHEET = CharacterSheet(
            name = "Мысық",
            personality = "Curious and brave.",
            ability = Ability.JUMP,
            lines = mapOf(
                "kk" to listOf("Сәлем!", "Секірейік!", "Мен батырмын!", "Қайда барамыз?", "Жарайсың!"),
                "ru" to listOf("Привет!", "Прыгаем!", "Я смелый!", "Куда идём?", "Молодец!"),
            ),
        )

        /** 4x4 image, centre 2x2 on. */
        val DEFAULT_MASK = MaskResult.fromBits(
            "drawing", 4, 4,
            booleanArrayOf(
                false, false, false, false,
                false, true, true, false,
                false, true, true, false,
                false, false, false, false,
            ),
            score = 0.9f,
        )
    }
}
