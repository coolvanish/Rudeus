package kz.rudy.ar.core.meta

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.emote.ChoreographyParser
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.PoseSpec
import kz.rudy.ar.core.emote.SpriteViews
import kz.rudy.ar.core.io.joinPath
import kotlin.time.Instant

/** Where [RecordingModels] puts fixture files. [path] is '/'-separated and relative to the fixtures root. */
fun interface FixtureWriter {
    fun write(path: String, bytes: ByteArray)
}

/**
 * [MetaModels] decorator that forwards every call to [inner] and records the replies as schema-1 fixtures that
 * [FixtureApi] replays unchanged (`synthetic: false`, `note: "recorded by cli character --live on <date>"`).
 * Per ref:
 *
 * | call          | file(s) written                                                         |
 * |---------------|-------------------------------------------------------------------------|
 * | segment       | `sam-cutout/<ref>.fixture.json` `masks` — FIRST cutout call per ref only  |
 * | stylize       | `muse-stylize/<ref>.out-<n>.png` (raw bytes) + fixture `outputs`         |
 * | scoreStyle    | `spark-score/<ref>.fixture.json` `scores`, in call order                 |
 * | scoreTechnique| the same file's `techniqueScores`, in call order                          |
 * | characterSheet| `spark-sheet/<ref>.fixture.json` `sheet` in the model JSON shape         |
 * | choreograph   | `spark-choreo/<ref>.fixture.json` `text`: the set in the reply shape      |
 * | drawPose      | `muse-pose/<ref>.<emote>-<n>.png` (raw bytes) + fixture `outputs`, one per frame (T33) |
 * | drawView      | `muse-view/<ref>.view-<view>.png` (raw bytes) + fixture `outputs`, one per view (T41)  |
 *
 * The choreo `text` is [ChoreographyParser.toJson] of the sanitised set (the raw reply never leaves [MetaApi]); it
 * replays to the same choreographies with source `spark`; a set whose five emotes all fell back (source `default`)
 * is recorded as `{}` and replays as the defaults with source `default`.
 *
 * Only the first segment call per ref is recorded, and only when its prompts are [Jobs.CUTOUT_PROMPTS]: later
 * segment calls for the same ref (the matte fallback on the stylized sprite) and table scans pass through
 * unrecorded, so replay serves the photo's masks. [transcribe] is not recorded either. Cutout recordings drop
 * [MaskResult.instances] (cutout uses the merged mask only; keeping them would write each RLE twice).
 *
 * Files are (re)written after every call, so a character that fails half-way leaves the fixtures of the calls
 * that succeeded. `latencyMs` is the wall time of the first call of that job for the ref, measured with [clock]
 * (epoch ms; also gives the note's UTC date). Refusing to overwrite existing fixtures is the caller's job.
 * One character at a time per instance; not thread-safe.
 */
class RecordingModels(
    private val inner: MetaModels,
    private val writer: FixtureWriter,
    private val clock: () -> Long,
) : MetaModels {
    private val segmented = HashSet<String>()
    private val outputs = HashMap<String, MutableList<String>>()
    private val poseOutputs = HashMap<String, MutableList<String>>()
    private val viewOutputs = HashMap<String, MutableList<String>>()
    private val scores = HashMap<String, MutableList<Float>>()
    private val techniqueScores = HashMap<String, MutableList<Float>>()
    private val firstLatency = HashMap<String, Long>()

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult {
        val record = ref !in segmented && prompts.isNotEmpty() && prompts.all { it in Jobs.CUTOUT_PROMPTS }
        segmented += ref
        val (result, ms) = timed { inner.segment(ref, image, prompts) }
        if (record) write(Jobs.SAM_CUTOUT, ref, latency(Jobs.SAM_CUTOUT, ref, ms)) { fx -> fx.copy(masks = result.masks.map { it.copy(instances = emptyList()) }) }
        return result
    }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray =
        recordStylize(ref) { inner.stylize(ref, cutout, refs) }

    override suspend fun stylizeFallback(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray =
        recordStylize(ref) { inner.stylizeFallback(ref, cutout, refs) }

    private suspend fun recordStylize(ref: String, call: suspend () -> ByteArray): ByteArray {
        val (png, ms) = timed { call() }
        val names = outputs.getOrPut(ref) { ArrayList() }
        val name = "$ref.out-${names.size + 1}.png"
        writer.write(joinPath(Jobs.MUSE_STYLIZE, name), png)
        names += name
        write(Jobs.MUSE_STYLIZE, ref, latency(Jobs.MUSE_STYLIZE, ref, ms)) { it.copy(outputs = names.toList()) }
        return png
    }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        val (score, ms) = timed { inner.scoreStyle(ref, image, refs) }
        scores.getOrPut(ref) { ArrayList() } += score
        writeScores(ref, ms)
        return score
    }

    override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        val (score, ms) = timed { inner.scoreTechnique(ref, image, refs) }
        techniqueScores.getOrPut(ref) { ArrayList() } += score
        writeScores(ref, ms)
        return score
    }

    /** Both score lists share `spark-score/<ref>.fixture.json`, so every write carries both. */
    private fun writeScores(ref: String, ms: Long) =
        write(Jobs.SPARK_SCORE, ref, latency(Jobs.SPARK_SCORE, ref, ms)) {
            it.copy(scores = scores[ref]?.toList(), techniqueScores = techniqueScores[ref]?.toList())
        }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet {
        val (sheet, ms) = timed { inner.characterSheet(ref, description, image) }
        write(Jobs.SPARK_SHEET, ref, latency(Jobs.SPARK_SHEET, ref, ms)) { it.copy(sheet = sheetJson(sheet)) }
        return sheet
    }

    override suspend fun choreograph(ref: String, sheet: CharacterSheet, image: ByteArray?): EmoteSet {
        val (set, ms) = timed { inner.choreograph(ref, sheet, image) }
        // A reply that fell back entirely is recorded as an empty object, so it replays as source=default too.
        val text = if (set.source == EmoteSet.SOURCE_SPARK) ChoreographyParser.toJson(set) else "{}"
        write(Jobs.SPARK_CHOREO, ref, latency(Jobs.SPARK_CHOREO, ref, ms)) { it.copy(text = text) }
        return set
    }

    override suspend fun drawPose(ref: String, sprite: ByteArray, refs: List<ByteArray>, spec: PoseSpec): ByteArray =
        recordNamed(Jobs.MUSE_POSE, ref, "$ref.${spec.name}.png", poseOutputs) { inner.drawPose(ref, sprite, refs, spec) }

    override suspend fun drawView(ref: String, sprite: ByteArray, refs: List<ByteArray>, view: SpriteView): ByteArray =
        recordNamed(Jobs.MUSE_VIEW, ref, "$ref.${SpriteViews.fileName(view)}", viewOutputs) { inner.drawView(ref, sprite, refs, view) }

    /** One named Muse output of [job]: the PNG as [name] and the fixture's `outputs`, each name once. */
    private suspend fun recordNamed(job: String, ref: String, name: String, outputs: HashMap<String, MutableList<String>>, call: suspend () -> ByteArray): ByteArray {
        val (png, ms) = timed { call() }
        val names = outputs.getOrPut(ref) { ArrayList() }
        writer.write(joinPath(job, name), png)
        if (name !in names) names += name
        write(job, ref, latency(job, ref, ms)) { it.copy(outputs = names.toList()) }
        return png
    }

    override suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String =
        inner.transcribe(ref, pcm16kMono, lang)

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = clock()
        val r = block()
        return r to (clock() - t0)
    }

    /** Latency of the first call of [job] for [ref]; later calls keep it. */
    private fun latency(job: String, ref: String, ms: Long): Long = firstLatency.getOrPut("$job/$ref") { ms }

    private fun write(job: String, ref: String, latencyMs: Long, fill: (Fixture) -> Fixture) {
        val fx = fill(Fixture(job = job, ref = ref, synthetic = false, note = note(), latencyMs = latencyMs))
        val text = FixtureApi.json.encodeToString(Fixture.serializer(), fx) + "\n"
        writer.write(joinPath(job, "$ref${FixtureApi.SUFFIX}"), text.encodeToByteArray())
    }

    private fun note(): String = "$NOTE_PREFIX ${Instant.fromEpochMilliseconds(clock()).toString().take(10)}"

    companion object {
        const val NOTE_PREFIX = "recorded by cli character --live on"

        /** The model JSON shape [SheetParser.fromJson] reads: `name, names, personality, ability` (lowercase), `lines`. */
        fun sheetJson(sheet: CharacterSheet): JsonObject = JsonObject(
            mapOf(
                "name" to JsonPrimitive(sheet.name),
                "names" to JsonObject(sheet.names.mapValues { (_, n) -> JsonPrimitive(n) }),
                "personality" to JsonPrimitive(sheet.personality),
                "ability" to JsonPrimitive(sheet.ability.name.lowercase()),
                "lines" to JsonObject(sheet.lines.mapValues { (_, l) -> JsonArray(l.map { JsonPrimitive(it) }) }),
            ),
        )
    }
}
