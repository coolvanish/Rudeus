package kz.rudy.ar.core.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Wire DTOs for [MetaApi], one request + response pair per job, plus a `parse*` function that turns the
 * response into the normalised types in Types.kt. FixtureConverter decodes saved probe replies with the same
 * DTOs, so the app and the fixtures agree on one wire shape.
 *
 * Shapes verified against https://dev.meta.ai/docs on 2026-10-28 (the request templates in
 * docs/api-samples/<job>/request.json.tmpl are the same bodies, with their source pages). Nothing has been sent
 * with a key yet; the remaining guesses are marked `TODO(probe)`.
 */

// ---------------------------------------------------------------- SAM 3.1 (Responses API, one concept per request)

/** A Responses `input` content part: `input_text` {text} or `input_image` {image_url: URL or data URL}. */
@Serializable
data class ResponsesContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
) {
    companion object {
        fun text(t: String) = ResponsesContentPart("input_text", text = t)
        fun imageDataUrl(mime: String, b64: String) = ResponsesContentPart("input_image", imageUrl = dataUrl(mime, b64))
    }
}

@Serializable
data class ResponsesMessage(
    val type: String = "message",
    val role: String = "user",
    val content: List<ResponsesContentPart>,
)

/** Responses `metadata`: every value is a STRING (https://dev.meta.ai/docs/sam/segmenting, "Choose a mask encoding"). */
@Serializable
data class SamMetadata(
    @SerialName("mask_encoding") val maskEncoding: String = "lossless",
    @SerialName("include_confidence") val includeConfidence: String = "true",
)

/**
 * `POST /v1/responses` for `sam-3.1`: one short noun-phrase concept + one image. Comma lists return zero matches,
 * so [MetaApi.segment] sends one of these per prompt (https://dev.meta.ai/docs/sam/segmenting).
 */
@Serializable
data class SegmentRequest(
    val model: String,
    val input: List<ResponsesMessage>,
    val stream: Boolean = false,
    val metadata: SamMetadata = SamMetadata(),
) {
    companion object {
        fun of(model: String, prompt: String, imageMime: String, imageB64: String, maskEncoding: String = "lossless") = SegmentRequest(
            model = model,
            input = listOf(ResponsesMessage(content = listOf(ResponsesContentPart.text(prompt), ResponsesContentPart.imageDataUrl(imageMime, imageB64)))),
            metadata = SamMetadata(maskEncoding = maskEncoding),
        )
    }
}

@Serializable
data class ResponsesOutputContent(val type: String? = null, val text: String? = null, val refusal: String? = null)

@Serializable
data class ResponsesOutputItem(
    val type: String? = null,
    val role: String? = null,
    val content: List<ResponsesOutputContent>? = null,
)

@Serializable
data class ResponsesErrorDto(val code: String? = null, val message: String? = null)

/**
 * Non-streaming Responses reply (https://dev.meta.ai/docs/api-reference/responses/schemas, "Response"):
 * `output[]` items, a `message` item holding `output_text` parts, plus an optional top-level `output_text`.
 *
 * TODO(probe): the SAM pages only show the SSE stream (`response.output_text.delta`); that `stream:false` returns
 *  this documented Response object with the token text in it is the remaining guess. [outputText] reads either place.
 */
@Serializable
data class ResponsesResponse(
    val id: String? = null,
    val status: String? = null,
    val output: List<ResponsesOutputItem>? = null,
    @SerialName("output_text") val outputText: String? = null,
    val error: ResponsesErrorDto? = null,
)

/**
 * The reply's text lane: the top-level `output_text` when non-blank, else the concatenated `output_text` parts of
 * the `message` items. Empty string = zero matches (valid).
 *
 * A `failed`/`cancelled` status or a `refusal` content part is a [MetaError.Provider]; an `incomplete` status is a
 * [MetaError.Parse] ("truncated"), since a cut-off line cannot be trusted.
 */
fun ResponsesResponse.text(): String {
    if (status == "failed" || status == "cancelled") {
        throw MetaError.Provider("response $status: ${error?.code ?: ""} ${error?.message ?: ""}".trim())
    }
    if (status == "incomplete") throw MetaError.Parse("response incomplete: truncated output")
    val parts = output.orEmpty()
        .filter { it.type == null || it.type == "message" }
        .flatMap { it.content.orEmpty() }
    parts.firstOrNull { it.type == "refusal" }?.let { throw MetaError.Provider("response refused: ${it.refusal ?: it.text ?: ""}".trim()) }
    outputText?.takeIf { it.isNotBlank() }?.let { return it }
    return parts
        .filter { it.type == null || it.type == "output_text" }
        .joinToString("") { it.text.orEmpty() }
}

/**
 * One SAM reply -> one [MaskResult] labelled [prompt] (see [SamTokenParser] for placement and the merge rule).
 * Empty text = absent object = empty mask, not an error.
 */
fun parseSamResponse(prompt: String, resp: ResponsesResponse, strict: Boolean = true): MaskResult =
    SamTokenParser.maskResult(prompt, resp.text(), strict)

/**
 * Gives the absent objects (no rle, no bbox, size unknown) the frame size of their siblings so every mask of one
 * call has the image's width x height; stays 0 x 0 when nothing at all was found.
 */
fun withFrameSize(masks: List<MaskResult>): List<MaskResult> {
    val sized = masks.firstOrNull { it.width > 0 && it.height > 0 } ?: return masks
    return masks.map { if (it.width == 0 && it.height == 0) it.copy(width = sized.width, height = sized.height) else it }
}

// ---------------------------------------------------------------- Muse Image edit (stylize)

@Serializable
data class ImageInput(@SerialName("image_url") val imageUrl: String)

/**
 * Meta-specific JSON body of `POST /v1/images/edits` (https://dev.meta.ai/docs/image-generation and
 * .../api-reference/images/schemas): `images` = 1..10 `{image_url}` items, the cutout first, then <= 9 style refs.
 * There is no `image` / `reference_images` field in the JSON form; unknown top-level fields are rejected.
 */
@Serializable
data class StylizeRequest(
    val model: String,
    val prompt: String,
    val images: List<ImageInput>,
    val n: Int = 1,
    @SerialName("response_format") val responseFormat: String = "b64_json",
    @SerialName("output_format") val outputFormat: String = "png",
    /** "low" (single pass) | "high" (default, self-refines); null = omitted. */
    @SerialName("reasoning_strength") val reasoningStrength: String? = null,
)

@Serializable
data class ImageDataDto(@SerialName("b64_json") val b64Json: String? = null, val url: String? = null)

@Serializable
data class ImageUsageDto(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
)

/** `{created, data:[{b64_json}], output_format, background: "opaque", usage}`. */
@Serializable
data class StylizeResponse(
    val created: Long? = null,
    val data: List<ImageDataDto> = emptyList(),
    @SerialName("output_format") val outputFormat: String? = null,
    /** Always "opaque" per the docs: the cutout's transparency is not preserved. */
    val background: String? = null,
    val usage: ImageUsageDto? = null,
)

fun parseStylize(resp: StylizeResponse): String =
    resp.data.firstOrNull()?.b64Json ?: throw MetaError.Parse("stylize reply has no data[0].b64_json (url-only replies are not supported yet)")

// ---------------------------------------------------------------- Muse Spark 1.3 (chat: score + sheet)

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
        fun imageDataUrl(mime: String, b64: String) = ChatContentPart("image_url", imageUrl = ChatImageUrl(dataUrl(mime, b64)))
    }
}

/**
 * A chat message; `content` is either a plain string or an array of [ChatContentPart]s (both are OpenAI-compatible).
 * Kept as a [JsonElement] so the sheet call can send the template's plain string and the score call its parts.
 */
@Serializable
data class ChatMessage(val role: String, val content: JsonElement) {
    companion object {
        fun text(role: String, text: String) = ChatMessage(role, JsonPrimitive(text))
        fun parts(role: String, parts: List<ChatContentPart>) =
            ChatMessage(role, MetaApi.json.encodeToJsonElement(ListSerializer(ChatContentPart.serializer()), parts))
    }
}

@Serializable
data class JsonSchemaFormat(val name: String, val schema: JsonObject, val strict: Boolean? = null)

/** `{"type":"json_schema","json_schema":{name, strict, schema}}` (https://dev.meta.ai/docs/features/structured-output). */
@Serializable
data class ResponseFormat(val type: String, @SerialName("json_schema") val jsonSchema: JsonSchemaFormat? = null)

/**
 * OpenAI-compatible `POST /v1/chat/completions` (https://dev.meta.ai/docs/features/chat-completion). Muse Spark is a
 * reasoning model: no `temperature` field at all (the default 1.0 is recommended), `max_completion_tokens` (counts
 * reasoning tokens too; `max_tokens` is a deprecated alias), `reasoning_effort` minimal..xhigh ("none" -> 400).
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
data class ChatChoiceMessage(val role: String? = null, val content: String? = null)

@Serializable
data class ChatChoice(val index: Int = 0, val message: ChatChoiceMessage? = null, @SerialName("finish_reason") val finishReason: String? = null)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

fun ChatResponse.contentText(): String =
    choices.firstOrNull()?.message?.content ?: throw MetaError.Parse("chat reply has no choices[0].message.content")

/** The score prompt asks for a bare number in 0..1; this also accepts `{"score": 0.8}` or "82%". */
fun parseScore(text: String): Float {
    val t = text.trim()
    val fromJson = Regex("\"score\"\\s*:\\s*([0-9]*\\.?[0-9]+)").find(t)?.groupValues?.get(1)
    val number = (fromJson ?: Regex("[0-9]*\\.?[0-9]+").find(t)?.value)?.toFloatOrNull()
        ?: throw MetaError.Parse("no score in: ${t.take(80)}")
    val scaled = if (number > 1f) number / 100f else number
    return scaled.coerceIn(0f, 1f)
}

fun parseSheet(text: String) = SheetParser.parse(text)

// ---------------------------------------------------------------- Muse Voice Transcribe

/**
 * The JSON `request` part of the multipart `POST /v1/asr/transcribe` (https://dev.meta.ai/docs/api-reference/voice/schemas).
 * The `audio` part is a RIFF WAV, mono 16-bit PCM at 16 or 24 kHz ([WavHeader.wrapPcm16Mono]).
 */
@Serializable
data class TranscribeRequest(
    val model: String,
    val audioEncoding: String = "WAV",
    val mode: String = "PUSH_TO_TALK",
    /** Language NAMES ("Kazakh"), a hint only; null = auto-detect. Kazakh is not among the 25 evaluated languages. */
    val languageBias: List<String>? = null,
    val keywords: List<String>? = null,
)

@Serializable
data class TranscribeTurn(
    val turnId: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val transcript: String? = null,
    val speaker: String? = null,
)

/** Buffered JSON reply: `{sessionId, transcript, audioDurationMs, turns}` (no `text` field). */
@Serializable
data class TranscribeResponse(
    val sessionId: String? = null,
    val transcript: String? = null,
    val audioDurationMs: Long? = null,
    val turns: List<TranscribeTurn> = emptyList(),
)

fun parseTranscript(resp: TranscribeResponse): String =
    resp.transcript ?: throw MetaError.Parse("transcribe reply has no transcript")

/** ISO code from [MetaModels.transcribe] -> the language name `languageBias` expects; null = let the model detect. */
fun languageName(code: String): String? = when (code.trim().lowercase()) {
    "kk", "kaz", "kazakh" -> "Kazakh"
    "ru", "rus", "russian" -> "Russian"
    "en", "eng", "english" -> "English"
    else -> null
}

// ---------------------------------------------------------------- shared helpers

fun dataUrl(mime: String, b64: String): String = "data:$mime;base64,$b64"

/** MIME type from the magic bytes (PNG, JPEG, WebP); JPEG when unknown (camera frames). */
fun sniffImageMime(bytes: ByteArray): String = when {
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
    bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
    else -> "image/jpeg"
}

/** Prompts sent to the models. Kept here so fixtures and live calls use identical text. */
object Prompts {
    const val STYLE_SCORE = "You are a strict art director. Rate from 0.00 to 1.00 how closely the FIRST image matches the " +
        "drawing style (line weight, palette, shading) of the reference images that follow. Reply with the number only."

    fun characterSheet(description: String): String =
        "A child drew a character: $description. Reply with JSON only: {\"name\": short name, \"personality\": one sentence, " +
            "\"ability\": one of \"jump\",\"fly\",\"roll\",\"hide\", \"lines\": {\"kk\": [5 short lines in Kazakh the character says], " +
            "\"ru\": [the same 5 lines in Russian]}}. Lines are for a child, max 6 words each."

    // TODO(design): Muse Image returns background "opaque" (docs: the field is always "opaque"), so the cutout's
    //  transparency is lost; the pipeline may need a second sam-cutout pass on the stylized output. Pipeline unchanged.
    const val STYLIZE = "The first image is a child's character cutout. Redraw this character in the exact style of the other " +
        "reference images: same line weight, palette and shading. Keep the pose and silhouette, plain white background, no text."

    /** JSON schema for the sheet, identical to docs/api-samples/spark-sheet/request.json.tmpl. */
    val CHARACTER_SHEET_SCHEMA: JsonObject = Json.parseToJsonElement(
        """
        {"type":"object","additionalProperties":false,
         "properties":{
           "name":{"type":"string"},
           "personality":{"type":"string"},
           "ability":{"type":"string","enum":["jump","fly","roll","hide"]},
           "lines":{"type":"object","additionalProperties":false,
                    "properties":{"kk":{"type":"array","items":{"type":"string"}},"ru":{"type":"array","items":{"type":"string"}}},
                    "required":["kk","ru"]}},
         "required":["name","personality","ability","lines"]}
        """.trimIndent(),
    ).jsonObject

    // TODO(probe): the template notes strict:true may reject the nested "lines" object; drop strict if the call is 400.
    val CHARACTER_SHEET_FORMAT = ResponseFormat(
        type = "json_schema",
        jsonSchema = JsonSchemaFormat(name = "CharacterSheet", schema = CHARACTER_SHEET_SCHEMA, strict = true),
    )

    /** Template value. TODO(probe): reasoning tokens count against it; raise to 64 if content comes back empty ("length"). */
    const val SCORE_MAX_COMPLETION_TOKENS = 16
    const val SHEET_MAX_COMPLETION_TOKENS = 600
    const val SCORE_REASONING_EFFORT = "low"
}
