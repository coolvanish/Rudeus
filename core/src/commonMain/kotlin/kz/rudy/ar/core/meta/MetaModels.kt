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

    /**
     * Spark vision: like [scoreStyle] but technique only (line weight, fill texture, shading, shapes), ignoring the
     * character's own colours. The gate asks it only when [scoreStyle] failed the attempt.
     */
    suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float

    /**
     * Spark: name / personality / ability / 5 lines in kk and ru. [image] (the cutout, PNG/JPEG) lets Spark vision see
     * the character; [description] is an optional hint (blank = none). Implementations may ignore either.
     */
    suspend fun characterSheet(ref: String, description: String, image: ByteArray? = null): CharacterSheet

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

    /** The job a [MetaModels.segment] call belongs to: [SAM_CUTOUT] when every prompt is a cutout prompt, else [SAM_TABLE]. */
    fun segmentJob(prompts: List<String>): String =
        if (prompts.isNotEmpty() && prompts.all { it in CUTOUT_PROMPTS }) SAM_CUTOUT else SAM_TABLE

    val ALL = listOf(SAM_CUTOUT, SAM_TABLE, SAM_RIG, MUSE_STYLIZE, SPARK_SCORE, SPARK_SHEET, VOICE)
}
