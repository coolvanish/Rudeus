package kz.rudy.ar.core.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire DTOs for [MetaApi], one request + response pair per job, plus a `parse*` function that
 * turns the response into the normalised types in Types.kt.
 *
 * Every shape below is an informed guess from the public API docs; nothing is verified yet.
 * The Day 1 probe (`scripts/probe-meta.sh <job> <input>`) saves the real request/response under
 * docs/api-samples/<job>/ and the DTO + parser must be reconciled with it in the same change, with a test.
 */

// ---------------------------------------------------------------- SAM 3.1 (segment)

// TODO(probe): confirm against docs/api-samples/sam-cutout/meta.json and docs/api-samples/sam-table/meta.json
@Serializable
data class SegmentRequest(
    val model: String,
    /** Base64 JPEG/PNG. */
    @SerialName("image") val imageB64: String,
    /** Text prompts; one mask per prompt is expected back. */
    val prompts: List<String>,
    @SerialName("output_format") val outputFormat: String = "rle",
)

@Serializable
data class SegmentMaskDto(
    val label: String? = null,
    val prompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** COCO uncompressed RLE (alternating off/on runs). */
    val rle: List<Int>? = null,
    /** `[x, y, w, h]` in pixels. */
    val bbox: List<Int>? = null,
    val score: Float? = null,
)

@Serializable
data class SegmentResponse(
    val masks: List<SegmentMaskDto> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
)

// TODO(probe): confirm against docs/api-samples/sam-cutout/meta.json
fun parseSegment(resp: SegmentResponse): SegmentResult {
    val masks = resp.masks.map { m ->
        val w = m.width ?: resp.width ?: throw MetaError.Parse("segment mask has no width")
        val h = m.height ?: resp.height ?: throw MetaError.Parse("segment mask has no height")
        val bbox = m.bbox?.let { if (it.size == 4) BBox(it[0], it[1], it[2], it[3]) else throw MetaError.Parse("bbox needs 4 ints") }
        if (m.rle != null && m.rle.sum() != w * h) throw MetaError.Parse("rle sums to ${m.rle.sum()}, expected ${w * h}")
        MaskResult(label = m.label ?: m.prompt ?: "object", width = w, height = h, rle = m.rle, bbox = bbox, score = m.score)
    }
    return SegmentResult(masks)
}

// ---------------------------------------------------------------- Muse Image edit (stylize)

// TODO(probe): confirm against docs/api-samples/muse-stylize/meta.json
@Serializable
data class StylizeRequest(
    val model: String,
    /** Base64 PNG of the cutout (alpha = background). */
    @SerialName("image") val imageB64: String,
    /** Up to 9 base64 style references. */
    @SerialName("reference_images") val referenceImagesB64: List<String>,
    val prompt: String,
    @SerialName("response_format") val responseFormat: String = "b64_json",
)

@Serializable
data class ImageDataDto(@SerialName("b64_json") val b64Json: String? = null, val url: String? = null)

@Serializable
data class StylizeResponse(val data: List<ImageDataDto> = emptyList())

// TODO(probe): confirm against docs/api-samples/muse-stylize/meta.json
fun parseStylize(resp: StylizeResponse): String =
    resp.data.firstOrNull()?.b64Json ?: throw MetaError.Parse("stylize reply has no data[0].b64_json (url-only replies are not supported yet)")

// ---------------------------------------------------------------- Spark 1.3 (chat: score + sheet)

@Serializable
data class ChatImageUrl(val url: String)

/** One content part of a multimodal chat message (`type` = "text" | "image_url"). */
@Serializable
data class ChatContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ChatImageUrl? = null,
) {
    companion object {
        fun text(t: String) = ChatContentPart("text", text = t)
        fun imageDataUrl(mime: String, b64: String) = ChatContentPart("image_url", imageUrl = ChatImageUrl("data:$mime;base64,$b64"))
    }
}

@Serializable
data class ChatMessage(val role: String, val content: List<ChatContentPart>)

// TODO(probe): confirm against docs/api-samples/spark-score/meta.json and docs/api-samples/spark-sheet/meta.json
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0f,
    @SerialName("max_tokens") val maxTokens: Int = 600,
)

@Serializable
data class ChatChoiceMessage(val role: String? = null, val content: String? = null)

@Serializable
data class ChatChoice(val index: Int = 0, val message: ChatChoiceMessage? = null, @SerialName("finish_reason") val finishReason: String? = null)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

fun ChatResponse.contentText(): String =
    choices.firstOrNull()?.message?.content ?: throw MetaError.Parse("chat reply has no choices[0].message.content")

// TODO(probe): confirm against docs/api-samples/spark-score/meta.json
/** The score prompt asks for a bare number in 0..1; this also accepts `{"score": 0.8}` or "82%". */
fun parseScore(text: String): Float {
    val t = text.trim()
    val fromJson = Regex("\"score\"\\s*:\\s*([0-9]*\\.?[0-9]+)").find(t)?.groupValues?.get(1)
    val number = (fromJson ?: Regex("[0-9]*\\.?[0-9]+").find(t)?.value)?.toFloatOrNull()
        ?: throw MetaError.Parse("no score in: ${t.take(80)}")
    val scaled = if (number > 1f) number / 100f else number
    return scaled.coerceIn(0f, 1f)
}

// TODO(probe): confirm against docs/api-samples/spark-sheet/meta.json
fun parseSheet(text: String) = SheetParser.parse(text)

// ---------------------------------------------------------------- Voice Transcribe

// TODO(probe): confirm against docs/api-samples/voice/meta.json
@Serializable
data class TranscribeRequest(
    val model: String,
    /** Base64 16 kHz mono 16-bit PCM (raw, no WAV header). */
    @SerialName("audio") val audioB64: String,
    val language: String,
    @SerialName("sample_rate") val sampleRate: Int = 16_000,
    val encoding: String = "pcm_s16le",
)

@Serializable
data class TranscribeResponse(val text: String? = null, val transcript: String? = null)

// TODO(probe): confirm against docs/api-samples/voice/meta.json
fun parseTranscript(resp: TranscribeResponse): String =
    resp.text ?: resp.transcript ?: throw MetaError.Parse("transcribe reply has neither text nor transcript")

/** Prompts sent to Spark. Kept here so fixtures and live calls use identical text. */
object Prompts {
    const val STYLE_SCORE = "You are a strict art director. Rate from 0.00 to 1.00 how closely the FIRST image matches the " +
        "drawing style (line weight, palette, shading) of the reference images that follow. Reply with the number only."

    fun characterSheet(description: String): String =
        "A child drew a character: $description. Reply with JSON only: {\"name\": short name, \"personality\": one sentence, " +
            "\"ability\": one of \"jump\",\"fly\",\"roll\",\"hide\", \"lines\": {\"kk\": [5 short lines in Kazakh the character says], " +
            "\"ru\": [the same 5 lines in Russian]}}. Lines are for a child, max 6 words each."

    const val STYLIZE = "Redraw this character in the exact style of the reference images: same line weight, palette and shading. " +
        "Keep the pose and silhouette, transparent background."
}
