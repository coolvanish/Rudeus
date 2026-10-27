package kz.rudy.ar.core.meta

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Turns a raw, redacted API reply saved by `scripts/probe-meta.sh` (`<job>/<ref>.response.json`) into the
 * normalised [Fixture] that [FixtureApi] replays. It decodes with the same DTOs and `parse*` functions as
 * [MetaApi] (MetaDtos.kt), so the converter and the app agree on one wire shape: when a real probe deviates,
 * fixing the DTO fixes both.
 *
 * Pure: no file IO. Image payloads are never decoded into pixels here; for `muse-stylize` the fixture names the
 * sibling `<ref>.out-1.png` that the probe (or the CLI, for an unredacted payload) must save next to it.
 */
object FixtureConverter {
    /** Jobs the converter understands; anything else is a caller error. */
    val JOBS: List<String> = Jobs.ALL

    /**
     * Decoder for raw replies: exactly [MetaApi.json], the live client's decoder, so a reply that fails on the
     * phone also fails here (unknown keys tolerated; nulls in non-null fields and malformed JSON are not).
     */
    val rawJson: Json get() = MetaApi.json

    private val redactedBase64 = Regex("""^<base64 \d+ (bytes|chars)>$""")

    /**
     * Builds the fixture for one raw reply.
     *
     * @throws IllegalArgumentException when [job] is not one of [JOBS].
     * @throws MetaError.Parse when the reply does not fit the DTOs; the message names [job] and the first 200 chars of [rawJson].
     */
    fun fromResponse(
        job: String,
        ref: String,
        rawJson: String,
        latencyMs: Long?,
        synthetic: Boolean,
        note: String,
    ): Fixture {
        require(job in JOBS) { "unknown job '$job' (expected one of ${JOBS.joinToString()})" }
        val base = Fixture(job = job, ref = ref, synthetic = synthetic, note = note, latencyMs = latencyMs)
        return try {
            when (job) {
                Jobs.SAM_CUTOUT, Jobs.SAM_TABLE, Jobs.SAM_RIG ->
                    base.copy(masks = parseSegment(decode(SegmentResponse.serializer(), rawJson)).masks)
                Jobs.MUSE_STYLIZE -> {
                    val payload = parseStylize(decode(StylizeResponse.serializer(), rawJson))
                    val extra = if (isRedacted(payload)) "; payload redacted, sibling ${outputName(ref)} must be saved by scripts/probe-meta.sh" else ""
                    base.copy(outputs = listOf(outputName(ref)), note = note + extra)
                }
                Jobs.SPARK_SCORE -> base.copy(scores = listOf(parseScore(decode(ChatResponse.serializer(), rawJson).contentText())))
                Jobs.SPARK_SHEET -> {
                    val text = decode(ChatResponse.serializer(), rawJson).contentText()
                    parseSheet(text) // same validation MetaApi applies; the fixture keeps the raw object
                    base.copy(sheet = SheetParser.extractObject(text))
                }
                Jobs.VOICE -> base.copy(text = parseTranscript(decode(TranscribeResponse.serializer(), rawJson)))
                else -> error("unreachable: $job")
            }
        } catch (e: MetaError) {
            throw parseError(job, rawJson, e.message, e)
        } catch (e: SerializationException) {
            throw parseError(job, rawJson, e.message, e)
        } catch (e: IllegalArgumentException) {
            throw parseError(job, rawJson, e.message, e)
        }
    }

    /** File name of the single stylize output a converted `muse-stylize` fixture points at. */
    fun outputName(ref: String): String = "$ref.out-1.png"

    /** True when probe-meta.sh replaced the base64 payload with a `<base64 N bytes>` placeholder. */
    fun isRedacted(payload: String): Boolean = redactedBase64.matches(payload.trim())

    /**
     * The stylize reply's inline base64 image, or null when it is redacted (the probe saved the PNG itself).
     * Lets the CLI write the sibling PNG for small unredacted replies; decoding stays with the caller.
     */
    fun inlineStylizeBase64(rawJson: String): String? = try {
        parseStylize(decode(StylizeResponse.serializer(), rawJson)).takeUnless { isRedacted(it) }
    } catch (e: MetaError) {
        throw parseError(Jobs.MUSE_STYLIZE, rawJson, e.message, e)
    } catch (e: SerializationException) {
        throw parseError(Jobs.MUSE_STYLIZE, rawJson, e.message, e)
    }

    private fun <T> decode(strategy: DeserializationStrategy<T>, raw: String): T = rawJson.decodeFromString(strategy, raw)

    private fun parseError(job: String, raw: String, detail: String?, cause: Throwable): MetaError.Parse =
        MetaError.Parse("$job: cannot convert reply: ${detail ?: cause::class.simpleName}; body starts: ${raw.take(200)}", cause)
}
