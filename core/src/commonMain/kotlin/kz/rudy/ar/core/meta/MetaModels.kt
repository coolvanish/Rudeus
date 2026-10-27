package kz.rudy.ar.core.meta

import kz.rudy.ar.core.domain.CharacterSheet

/**
 * The five model jobs Rudeus needs, provider-agnostic. Implementations: [MetaApi] (Ktor, real calls),
 * [FixtureApi] (replays docs/api-samples), [FakeModels] (scripted, tests).
 *
 * `ref` is a stable identifier of the input (e.g. "cat-01") used for logging and fixture lookup;
 * it never changes what the model sees.
 */
interface MetaModels {
    /** SAM: segment [image] (JPEG/PNG bytes) for the given text [prompts]. One mask per prompt; empty mask = absent. */
    suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult

    /** Muse Image edit: restyle the [cutout] (PNG with alpha) to match the style [refs]. Returns PNG bytes. */
    suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray

    /** Spark vision: how well [image] matches the style of [refs], in 0..1. */
    suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float

    /** Spark text: name / personality / ability / 5 lines in kk and ru for the character [description]. */
    suspend fun characterSheet(ref: String, description: String): CharacterSheet

    /** Voice Transcribe: [pcm16kMono] 16-bit little-endian PCM, 16 kHz mono, in language [lang] ("kk", "ru"). */
    suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String
}

/** Job names; they double as the fixture folder names under docs/api-samples. */
object Jobs {
    const val SAM_CUTOUT = "sam-cutout"
    const val SAM_TABLE = "sam-table"
    const val SAM_RIG = "sam-rig"
    const val MUSE_STYLIZE = "muse-stylize"
    const val SPARK_SCORE = "spark-score"
    const val SPARK_SHEET = "spark-sheet"
    const val VOICE = "voice"

    /** Prompts that distinguish a cutout call from a table scan. */
    val CUTOUT_PROMPTS = listOf("drawing", "toy")

    val ALL = listOf(SAM_CUTOUT, SAM_TABLE, SAM_RIG, MUSE_STYLIZE, SPARK_SCORE, SPARK_SHEET, VOICE)
}
