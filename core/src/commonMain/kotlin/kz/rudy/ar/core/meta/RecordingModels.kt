package kz.rudy.ar.core.meta

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kz.rudy.ar.core.domain.CharacterSheet
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
 * | characterSheet| `spark-sheet/<ref>.fixture.json` `sheet` in the model JSON shape         |
 *
 * Only the first segment call per ref is recorded, and only when its prompts are [Jobs.CUTOUT_PROMPTS]: later
 * segment calls for the same ref (the matte fallback on the stylized sprite) and table scans pass through
 * unrecorded, so replay serves the photo's masks. [transcribe] is not recorded either.
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
    private val scores = HashMap<String, MutableList<Float>>()
    private val firstLatency = HashMap<String, Long>()

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult {
        val record = ref !in segmented && prompts.isNotEmpty() && prompts.all { it in Jobs.CUTOUT_PROMPTS }
        segmented += ref
        val (result, ms) = timed { inner.segment(ref, image, prompts) }
        if (record) write(Jobs.SAM_CUTOUT, ref, latency(Jobs.SAM_CUTOUT, ref, ms)) { it.copy(masks = result.masks) }
        return result
    }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray {
        val (png, ms) = timed { inner.stylize(ref, cutout, refs) }
        val names = outputs.getOrPut(ref) { ArrayList() }
        val name = "$ref.out-${names.size + 1}.png"
        writer.write(joinPath(Jobs.MUSE_STYLIZE, name), png)
        names += name
        write(Jobs.MUSE_STYLIZE, ref, latency(Jobs.MUSE_STYLIZE, ref, ms)) { it.copy(outputs = names.toList()) }
        return png
    }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        val (score, ms) = timed { inner.scoreStyle(ref, image, refs) }
        val list = scores.getOrPut(ref) { ArrayList() }
        list += score
        write(Jobs.SPARK_SCORE, ref, latency(Jobs.SPARK_SCORE, ref, ms)) { it.copy(scores = list.toList()) }
        return score
    }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet {
        val (sheet, ms) = timed { inner.characterSheet(ref, description, image) }
        write(Jobs.SPARK_SHEET, ref, latency(Jobs.SPARK_SHEET, ref, ms)) { it.copy(sheet = sheetJson(sheet)) }
        return sheet
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

        /** The model JSON shape [SheetParser.fromJson] reads: `name, personality, ability` (lowercase), `lines`. */
        fun sheetJson(sheet: CharacterSheet): JsonObject = JsonObject(
            mapOf(
                "name" to JsonPrimitive(sheet.name),
                "personality" to JsonPrimitive(sheet.personality),
                "ability" to JsonPrimitive(sheet.ability.name.lowercase()),
                "lines" to JsonObject(sheet.lines.mapValues { (_, l) -> JsonArray(l.map { JsonPrimitive(it) }) }),
            ),
        )
    }
}
