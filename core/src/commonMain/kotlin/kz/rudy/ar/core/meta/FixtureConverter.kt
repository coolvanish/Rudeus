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
 *
 * SAM 3.1 takes one concept per request, so the probe saves one reply per prompt (`<ref>.<prompt>.response.json`);
 * the SAM jobs go through [fromSamResponses], which builds ONE fixture with one mask per prompt, in prompt order.
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
     * @throws IllegalArgumentException when [job] is not one of [JOBS], or is a SAM job (see [fromSamResponses]).
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
        require(job !in SAM_JOBS) { "$job takes one reply per prompt: use fromSamResponses" }
        val base = Fixture(job = job, ref = ref, synthetic = synthetic, note = note, latencyMs = latencyMs)
        return try {
            when (job) {
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

    /**
     * Builds one SAM fixture from the per-prompt replies [promptToRawJson] (`prompt` to raw Responses JSON), in order:
     * one mask per prompt labelled with the prompt; an empty `output_text` is an absent object (no rle/bbox, with the
     * frame size of its siblings). Masks are decoded strictly: a payload that does not decode fails the conversion.
     *
     * @throws IllegalArgumentException when [job] is not a SAM job or [promptToRawJson] is empty.
     * @throws MetaError.Parse naming [job], the prompt and the first 200 chars of the offending reply.
     */
    fun fromSamResponses(
        job: String,
        ref: String,
        promptToRawJson: List<Pair<String, String>>,
        latencyMs: Long?,
        synthetic: Boolean,
        note: String,
    ): Fixture {
        require(job in SAM_JOBS) { "fromSamResponses takes a SAM job (${SAM_JOBS.joinToString()}), not '$job'" }
        require(promptToRawJson.isNotEmpty()) { "$job: no replies to convert" }
        val masks = promptToRawJson.map { (prompt, raw) ->
            try {
                parseSamResponse(prompt, decode(ResponsesResponse.serializer(), raw), strict = true)
            } catch (e: MetaError) {
                throw parseError(job, raw, "prompt '$prompt': ${e.message}", e)
            } catch (e: SerializationException) {
                throw parseError(job, raw, "prompt '$prompt': ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw parseError(job, raw, "prompt '$prompt': ${e.message}", e)
            }
        }
        return Fixture(job = job, ref = ref, synthetic = synthetic, note = note, latencyMs = latencyMs, masks = withFrameSize(masks))
    }

    /**
     * Splits a per-prompt reply file name `<name>.<promptToken>.response.json` (as scripts/probe-meta.sh writes it)
     * into `(ref, promptToken)`, or null when it does not have that shape. The token never contains a dot (see
     * [samPromptToken]), so the LAST dot ends the name; a trailing `.input` (`sample-01.input.png` was the input) is
     * dropped so the ref matches `<ref>.fixture.json`.
     */
    fun splitSamResponseName(fileName: String): Pair<String, String>? {
        if (!fileName.endsWith(RESPONSE_SUFFIX)) return null
        val stem = fileName.removeSuffix(RESPONSE_SUFFIX)
        val dot = stem.lastIndexOf('.')
        if (dot <= 0 || dot == stem.lastIndex) return null
        return stem.substring(0, dot).removeSuffix(".input") to stem.substring(dot + 1)
    }

    /** The prompt as it appears in a reply file name: runs of anything but `A-Za-z0-9_-` become `_` (probe-meta.sh). */
    fun samPromptToken(prompt: String): String = prompt.replace(promptTokenRun, "_")

    private val promptTokenRun = Regex("[^A-Za-z0-9_-]+")

    const val RESPONSE_SUFFIX = ".response.json"

    /** The jobs whose replies are SAM token text, one reply per prompt. */
    val SAM_JOBS: List<String> = listOf(Jobs.SAM_CUTOUT, Jobs.SAM_TABLE, Jobs.SAM_RIG)

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
