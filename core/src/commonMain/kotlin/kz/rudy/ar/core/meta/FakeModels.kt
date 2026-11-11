package kz.rudy.ar.core.meta

import kotlinx.coroutines.delay
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseSpec

/**
 * Scripted [MetaModels] for tests: [scores] are returned in order (last one repeats), [techniqueScores] likewise
 * for [scoreTechnique] (empty = 0), the sheet and
 * masks are fixed, every call is appended to [calls] as `"job:ref"`. The images each call received are kept
 * ([stylizeInputs], [scoredImages], [scoredRefs], [sheetImages]); [delays] (method name -> ms, e.g. `"stylize"`)
 * makes a call suspend first, for concurrency tests under virtual time.
 */
class FakeModels(
    var scores: List<Float> = listOf(0.85f),
    var techniqueScores: List<Float> = emptyList(),
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
    /** What [choreograph] returns. */
    var emotes: EmoteSet = EmoteSet.defaults(),
    /** When set, [choreograph] throws it (after its delay); any Throwable, like [sheetError]. */
    var choreoError: Throwable? = null,
    /** What [drawPose] returns per [PoseSpec.name] (`hello-1`); a name not here throws [MetaError.Provider]. */
    var frames: Map<String, ByteArray> = emptyMap(),
    /** When set, every [drawPose] throws it (after its delay). */
    var poseError: Throwable? = null,
    /** What [drawView] returns per view (T41); a view not here throws [MetaError.Provider]. */
    var views: Map<SpriteView, ByteArray> = emptyMap(),
    /** When set, every [drawView] throws it (after its delay). */
    var viewError: Throwable? = null,
    var delays: Map<String, Long> = emptyMap(),
) : MetaModels {
    val calls = ArrayList<String>()
    val stylizeInputs = ArrayList<ByteArray>()
    val scoredImages = ArrayList<ByteArray>()
    val scoredRefs = ArrayList<List<ByteArray>>()
    val sheetImages = ArrayList<ByteArray?>()
    /** The sheet and image each [choreograph] call received. */
    val choreoSheets = ArrayList<CharacterSheet>()
    val choreoImages = ArrayList<ByteArray?>()
    /** The sprite each [drawPose] call received, by [PoseSpec.name]. */
    val poseInputs = LinkedHashMap<String, ByteArray>()
    /** The sprite each [drawView] call received, by view. */
    val viewInputs = LinkedHashMap<SpriteView, ByteArray>()
    private var scoreIndex = 0
    private var techniqueIndex = 0

    val stylizeCalls: Int get() = calls.count { it.startsWith("stylize:") }
    var stylizeFallbackCalls = 0
        private set
    val scoreCalls: Int get() = calls.count { it.startsWith("scoreStyle:") }
    val techniqueCalls: Int get() = calls.count { it.startsWith("scoreTechnique:") }

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

    override suspend fun stylizeFallback(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray {
        stylizeFallbackCalls++
        return stylize(ref, cutout, refs)
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

    override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        calls += "scoreTechnique:$ref"
        pause("scoreTechnique")
        if (techniqueScores.isEmpty()) return 0f
        return techniqueScores[techniqueIndex++.coerceAtMost(techniqueScores.lastIndex)]
    }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet {
        calls += "characterSheet:$ref"
        sheetImages += image
        pause("characterSheet")
        sheetError?.let { throw it }
        return sheet
    }

    override suspend fun choreograph(ref: String, sheet: CharacterSheet, image: ByteArray?): EmoteSet {
        calls += "choreograph:$ref"
        choreoSheets += sheet
        choreoImages += image
        pause("choreograph")
        choreoError?.let { throw it }
        return emotes
    }

    override suspend fun drawPose(ref: String, sprite: ByteArray, refs: List<ByteArray>, spec: PoseSpec): ByteArray {
        calls += "drawPose:$ref:${spec.name}"
        poseInputs[spec.name] = sprite
        pause("drawPose")
        poseError?.let { throw it }
        return frames[spec.name] ?: throw MetaError.Provider("no fake frame ${spec.name}")
    }

    override suspend fun drawView(ref: String, sprite: ByteArray, refs: List<ByteArray>, view: SpriteView): ByteArray {
        calls += "drawView:$ref:${view.name.lowercase()}"
        viewInputs[view] = sprite
        pause("drawView")
        viewError?.let { throw it }
        return views[view] ?: throw MetaError.Provider("no fake view $view")
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
                "en" to listOf("Hi!", "Let's jump!", "I'm brave!", "Where to?", "Well done!"),
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
