package kz.rudy.ar.core.meta

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.io.FixtureSource
import kz.rudy.ar.core.io.joinPath
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update

/**
 * Normalised fixture file: `<root>/<job>/<ref>.fixture.json`. One schema for every job; only the
 * job-specific field is filled in:
 *
 * | job          | field     | meaning                                                            |
 * |--------------|-----------|--------------------------------------------------------------------|
 * | sam-cutout   | `masks`   | [MaskResult]s (RLE or bbox); the pipeline takes the largest         |
 * | sam-table    | `masks`   | one per prompt; an absent object is a mask with no rle/bbox         |
 * | muse-stylize | `outputs` | sibling PNG file names, one per attempt (last one repeats)          |
 * | spark-score  | `scores`  | one score per call, in order (last one repeats)                     |
 * | spark-score  | `techniqueScores` | [scoreTechnique] calls, in order (last one repeats); absent in
 * |              |           | fixtures recorded before 2026-10-28, which then throw on that call   |
 * | spark-sheet  | `sheet`   | the model's JSON as returned, parsed by [SheetParser]               |
 * | voice        | `text`    | the transcript                                                      |
 *
 * `synthetic: true` marks hand-made samples (not a real API reply). `latencyMs` overrides the folder's
 * `meta.json` latency for replay.
 */
@Serializable
data class Fixture(
    val schema: Int = SCHEMA,
    val job: String,
    val ref: String = "",
    val synthetic: Boolean = false,
    val note: String = "",
    val masks: List<MaskResult>? = null,
    val outputs: List<String>? = null,
    val scores: List<Float>? = null,
    /** [MetaModels.scoreTechnique] replies, in call order (spark-score only). */
    val techniqueScores: List<Float>? = null,
    val sheet: JsonObject? = null,
    val text: String? = null,
    val latencyMs: Long? = null,
) {
    companion object { const val SCHEMA = 1 }
}

/** `<root>/<job>/meta.json`: how the folder's samples were produced. Latency drives `--replay-latency`. */
@Serializable
data class FixtureMeta(
    val job: String = "",
    val endpoint: String = "",
    val model: String = "",
    val requestShape: String = "",
    val latencyMs: Long = 0,
    val costUsd: Double = 0.0,
    val probedAt: String = "",
    val synthetic: Boolean = true,
    val notes: String = "",
)

/**
 * [MetaModels] that replays saved fixtures. Never touches the network, never flaky.
 * Repeated calls for the same `ref` walk through per-attempt lists (`outputs`, `scores`).
 */
@OptIn(ExperimentalAtomicApi::class)
class FixtureApi(
    private val source: FixtureSource,
    private val root: String = "docs/api-samples",
    private val replayLatency: Boolean = false,
) : MetaModels {
    // Immutable maps behind atomic references: the pipeline's sheet coroutine may call in from another thread.
    private val calls = AtomicReference(emptyMap<String, Int>())
    private val metaCache = AtomicReference(emptyMap<String, FixtureMeta?>())

    fun fixturePath(job: String, ref: String): String = joinPath(root, job, "$ref.fixture.json")

    /** Refs that have a fixture for [job] (file names without `.fixture.json`). */
    fun listRefs(job: String): List<String> =
        source.list(joinPath(root, job)).filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }

    fun meta(job: String): FixtureMeta? {
        val cache = metaCache.load()
        if (job in cache) return cache[job]
        val meta = source.readText(joinPath(root, job, "meta.json"))?.let { text ->
            try { json.decodeFromString(FixtureMeta.serializer(), text) } catch (e: Exception) { throw MetaError.Parse("$job/meta.json: ${e.message}", e) }
        }
        metaCache.update { it + (job to meta) }
        return meta
    }

    fun load(job: String, ref: String): Fixture {
        val path = fixturePath(job, ref)
        val text = source.readText(path) ?: throw MetaError.FixtureMissing(path)
        val fx = try { json.decodeFromString(Fixture.serializer(), text) } catch (e: Exception) { throw MetaError.Parse("$path: ${e.message}", e) }
        if (fx.schema != Fixture.SCHEMA) throw MetaError.Parse("$path: schema ${fx.schema}, expected ${Fixture.SCHEMA}")
        return fx
    }

    /** Resets the per-ref attempt counters (e.g. between pipeline runs on the same instance). */
    fun reset() = calls.store(emptyMap())

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult {
        val job = Jobs.segmentJob(prompts)
        val fx = load(job, ref)
        replay(job, fx)
        return SegmentResult(fx.masks ?: throw MetaError.Parse("${fixturePath(job, ref)}: no masks"))
    }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray {
        val fx = load(Jobs.MUSE_STYLIZE, ref)
        val outputs = fx.outputs.orEmpty()
        if (outputs.isEmpty()) throw MetaError.Parse("${fixturePath(Jobs.MUSE_STYLIZE, ref)}: no outputs")
        val name = outputs[nextCall(Jobs.MUSE_STYLIZE, ref).coerceAtMost(outputs.lastIndex)]
        val path = joinPath(root, Jobs.MUSE_STYLIZE, name)
        replay(Jobs.MUSE_STYLIZE, fx)
        return source.readBytes(path) ?: throw MetaError.FixtureMissing(path)
    }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        val fx = load(Jobs.SPARK_SCORE, ref)
        val scores = fx.scores.orEmpty()
        if (scores.isEmpty()) throw MetaError.Parse("${fixturePath(Jobs.SPARK_SCORE, ref)}: no scores")
        replay(Jobs.SPARK_SCORE, fx)
        return scores[nextCall(Jobs.SPARK_SCORE, ref).coerceAtMost(scores.lastIndex)].coerceIn(0f, 1f)
    }

    override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        val fx = load(Jobs.SPARK_SCORE, ref)
        val scores = fx.techniqueScores.orEmpty()
        if (scores.isEmpty()) throw MetaError.Parse("${fixturePath(Jobs.SPARK_SCORE, ref)}: no techniqueScores")
        replay(Jobs.SPARK_SCORE, fx)
        return scores[nextCall(TECHNIQUE_CALLS, ref).coerceAtMost(scores.lastIndex)].coerceIn(0f, 1f)
    }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet {
        val fx = load(Jobs.SPARK_SHEET, ref)
        replay(Jobs.SPARK_SHEET, fx)
        return SheetParser.fromJson(fx.sheet ?: throw MetaError.Parse("${fixturePath(Jobs.SPARK_SHEET, ref)}: no sheet"))
    }

    override suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String {
        val fx = load(Jobs.VOICE, ref)
        replay(Jobs.VOICE, fx)
        return fx.text ?: throw MetaError.Parse("${fixturePath(Jobs.VOICE, ref)}: no text")
    }

    private fun nextCall(job: String, ref: String): Int {
        val key = "$job/$ref"
        return calls.fetchAndUpdate { it + (key to (it[key] ?: 0) + 1) }[key] ?: 0
    }

    private suspend fun replay(job: String, fx: Fixture) {
        if (!replayLatency) return
        val ms = fx.latencyMs ?: meta(job)?.latencyMs ?: 0L
        if (ms > 0) delay(ms)
    }

    companion object {
        const val SUFFIX = ".fixture.json"

        /** Call-counter key for [scoreTechnique], apart from [scoreStyle]'s: the two lists advance independently. */
        private const val TECHNIQUE_CALLS = "${Jobs.SPARK_SCORE}#technique"
        val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    }
}
