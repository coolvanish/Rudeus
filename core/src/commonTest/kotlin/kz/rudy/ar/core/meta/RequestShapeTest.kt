package kz.rudy.ar.core.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every request DTO, serialised exactly as [MetaApi] sends it, has the top-level keys of its
 * docs/api-samples/<job>/request.json.tmpl (the bodies verified against https://dev.meta.ai/docs on 2026-10-28).
 */
class RequestShapeTest {
    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        MetaApi.json.parseToJsonElement(MetaApi.json.encodeToString(serializer, value)).jsonObject

    private val png = "iVBORw0KGgo="

    @Test
    fun samRequestIsAResponsesCallWithOneConcept() {
        val body = encode(SegmentRequest.serializer(), SegmentRequest.of("sam-3.1", "cup", "image/jpeg", png))
        assertEquals(setOf("model", "input", "stream", "store", "metadata"), body.keys)
        assertFalse("prompts" in body || "image" in body || "output_format" in body)
        assertEquals(JsonPrimitive(false), body["stream"])
        assertEquals(JsonPrimitive(false), body["store"], "store:false or large inline images fail with 401 (Day 1 probe)")
        val message = body["input"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("type", "role", "content"), message.keys)
        assertEquals("message", message["type"]!!.jsonPrimitive.content)
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        val (text, image) = message["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(mapOf("type" to "input_text", "text" to "cup"), text.mapValues { it.value.jsonPrimitive.content })
        assertEquals(mapOf("type" to "input_image", "image_url" to "data:image/jpeg;base64,$png"), image.mapValues { it.value.jsonPrimitive.content })
        val metadata = body["metadata"]!!.jsonObject
        assertEquals(setOf("mask_encoding", "include_confidence"), metadata.keys)
        assertTrue(metadata.values.all { it is JsonPrimitive && it.isString }, "metadata values are strings")
        assertEquals("true", metadata["include_confidence"]!!.jsonPrimitive.content)
    }

    @Test
    fun museEditSendsImagesArrayNotReferenceImages() {
        val req = StylizeRequest(
            model = "muse-image-1.0", prompt = Prompts.STYLIZE,
            images = listOf(ImageInput(dataUrl("image/png", png)), ImageInput(dataUrl("image/png", png))),
            reasoningStrength = "low",
        )
        val body = encode(StylizeRequest.serializer(), req)
        assertEquals(setOf("model", "prompt", "images", "n", "response_format", "output_format", "reasoning_strength"), body.keys)
        assertFalse("reference_images" in body || "image" in body)
        assertEquals(setOf("image_url"), body["images"]!!.jsonArray[0].jsonObject.keys)
        assertEquals("b64_json", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("png", body["output_format"]!!.jsonPrimitive.content)
        assertFalse("reasoning_strength" in encode(StylizeRequest.serializer(), req.copy(reasoningStrength = null)))
    }

    @Test
    fun stylizePromptAsksForRefOutlineAndIdentity() {
        val p = Prompts.STYLIZE.lowercase()
        for (phrase in listOf("reference images", "bold, even outline", "line weight and colour", "flat crayon colour", "white background", "recognises")) {
            assertTrue(phrase in p, "stylize prompt mentions \"$phrase\": $p")
        }
        assertFalse('\n' in Prompts.STYLIZE, "one paragraph")

        val tmpl = DiskFixtureSource.repoRoot().readText("docs/api-samples/muse-stylize/request.json.tmpl")!!
            .lines().filterNot { it.startsWith("#") }.joinToString("\n")
        val tmplBody = MetaApi.json.parseToJsonElement(tmpl.replace("{{REF_IMAGE_ITEMS}}", "")).jsonObject
        assertEquals(Prompts.STYLIZE, tmplBody["prompt"]!!.jsonPrimitive.content, "the probe template sends the same prompt")
    }

    @Test
    fun sparkScoreUsesMaxCompletionTokensAndNoTemperature() {
        val req = ChatRequest(
            model = MetaConfig(apiKey = "test-key").sparkModel,
            messages = listOf(ChatMessage.parts("user", listOf(ChatContentPart.text(Prompts.STYLE_SCORE), ChatContentPart.imageDataUrl("image/png", png)))),
            maxCompletionTokens = Prompts.SCORE_MAX_COMPLETION_TOKENS,
            reasoningEffort = Prompts.SCORE_REASONING_EFFORT,
        )
        val body = encode(ChatRequest.serializer(), req)
        assertEquals(setOf("model", "messages", "reasoning_effort", "max_completion_tokens"), body.keys)
        assertFalse("max_tokens" in body || "temperature" in body)
        assertEquals("muse-spark-1.3-contributor", body["model"]!!.jsonPrimitive.content, "contributor tier, never standard")
        assertEquals(1024, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt(), "16, 64 and 512 ran out on reasoning")
        val tmpl = DiskFixtureSource.repoRoot().readText("docs/api-samples/spark-score/request.json.tmpl")!!
            .lines().filterNot { it.startsWith("#") }.joinToString("\n")
        val tmplBody = MetaApi.json.parseToJsonElement(tmpl.replace("{{REF_CHAT_PARTS}}", "")).jsonObject
        assertEquals(body["max_completion_tokens"], tmplBody["max_completion_tokens"], "template budget matches the code")
        assertEquals(Prompts.STYLE_SCORE, tmplBody["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url"), parts.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(setOf("type", "text"), parts[0].keys)
        assertEquals("data:image/png;base64,$png", parts[1]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun sparkSheetSendsTheTemplateJsonSchema() {
        val req = ChatRequest(
            model = MetaConfig(apiKey = "test-key").sparkModel,
            messages = listOf(ChatMessage.text("user", Prompts.characterSheet("a cat"))),
            maxCompletionTokens = Prompts.SHEET_MAX_COMPLETION_TOKENS,
            reasoningEffort = Prompts.SHEET_REASONING_EFFORT,
            responseFormat = Prompts.CHARACTER_SHEET_FORMAT,
        )
        val body = encode(ChatRequest.serializer(), req)
        assertEquals(setOf("model", "messages", "response_format", "reasoning_effort", "max_completion_tokens"), body.keys)
        assertEquals("muse-spark-1.3-contributor", body["model"]!!.jsonPrimitive.content, "contributor tier, never standard")
        assertEquals(2048, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt(), "600 ran out on the Day 1 probe")
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("role", "content"), message.keys)
        val content = message["content"] as JsonPrimitive
        assertTrue(content.isString, "sheet content is a plain string, as in the template")
        assertEquals(Prompts.characterSheet("a cat"), content.content)
        val format = body["response_format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val js = format["json_schema"]!!.jsonObject
        assertEquals(setOf("name", "schema", "strict"), js.keys)
        assertEquals("CharacterSheet", js["name"]!!.jsonPrimitive.content)
        val schema = js["schema"]!!.jsonObject
        assertEquals(listOf("name", "personality", "ability", "lines"), (schema["required"] as JsonArray).map { it.jsonPrimitive.content })
        val ability = schema["properties"]!!.jsonObject["ability"]!!.jsonObject
        assertEquals(listOf("jump", "fly", "roll", "hide"), ability["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
    }

    @Test
    fun sheetRequestCarriesImagePart() {
        val model = MetaConfig(apiKey = "test-key").sparkModel
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)

        val withImage = encode(ChatRequest.serializer(), ChatRequest.characterSheet(model, "", pngBytes))
        assertEquals(setOf("model", "messages", "response_format", "reasoning_effort", "max_completion_tokens"), withImage.keys)
        assertEquals("json_schema", withImage["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2048, withImage["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("low", withImage["reasoning_effort"]!!.jsonPrimitive.content)
        val parts = withImage["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url"), parts.map { it["type"]!!.jsonPrimitive.content })
        val text = parts[0]["text"]!!.jsonPrimitive.content
        assertEquals(Prompts.characterSheet("", withImage = true), text)
        assertFalse(": ." in text || "  " in text, "blank description leaves no artefacts: $text")
        assertTrue(parts[1]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))

        val plain = encode(ChatRequest.serializer(), ChatRequest.characterSheet(model, "a cat", null))
        val content = plain["messages"]!!.jsonArray.single().jsonObject["content"] as JsonPrimitive
        assertTrue(content.isString, "no image: plain-string content, as before")
        assertEquals(Prompts.characterSheet("a cat"), content.content)
        assertTrue(content.content.startsWith("A child drew a character: a cat. Reply"), content.content)
        assertTrue(Prompts.characterSheet("  ").startsWith("A child drew a character. Reply"))
        assertTrue(Prompts.characterSheet("a red fox.", withImage = true).startsWith("The image shows a character a child drew: a red fox. Reply"))

        // The probe template is the same body: same keys, same text part, one image_url part.
        val tmpl = DiskFixtureSource.repoRoot().readText("docs/api-samples/spark-sheet/request.json.tmpl")!!
            .lines().filterNot { it.startsWith("#") }.joinToString("\n")
        val tmplBody = MetaApi.json.parseToJsonElement(tmpl).jsonObject
        assertEquals(withImage.keys, tmplBody.keys)
        assertEquals(withImage["response_format"], tmplBody["response_format"])
        val tmplParts = tmplBody["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url"), tmplParts.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(text, tmplParts[0]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun voiceRequestPartIsTheDocumentedJson() {
        val body = encode(TranscribeRequest.serializer(), TranscribeRequest(model = "muse-voice-transcribe-1.0", languageBias = listOf("Kazakh")))
        assertEquals(setOf("model", "audioEncoding", "mode", "languageBias"), body.keys)
        assertEquals("WAV", body["audioEncoding"]!!.jsonPrimitive.content)
        assertEquals("PUSH_TO_TALK", body["mode"]!!.jsonPrimitive.content)
        assertFalse("audio" in body || "language" in body)
        val withKeywords = encode(TranscribeRequest.serializer(), TranscribeRequest(model = "m", keywords = listOf("секір")))
        assertEquals(setOf("model", "audioEncoding", "mode", "keywords"), withKeywords.keys)
        assertEquals("Kazakh", languageName("kk"))
        assertEquals(null, languageName("xx"))
    }

    @Test
    fun configDefaultsPointAtTheMetaModelApi() {
        val c = MetaConfig(apiKey = "test-key")
        assertEquals("https://api.meta.ai/v1/responses", c.url(c.segmentPath))
        assertEquals("https://api.meta.ai/v1/asr/transcribe", c.url(c.transcribePath))
        assertEquals(listOf("sam-3.1", "muse-image-1.0", "muse-spark-1.3-contributor", "muse-voice-transcribe-1.0"), listOf(c.samModel, c.museImageModel, c.sparkModel, c.voiceModel))
        assertEquals(MetaConfig.META_SPARK_MODEL, c.sparkModel)
        val fw = MetaConfig(apiKey = "test-key", provider = Provider.FIREWORKS)
        assertEquals(MetaConfig.FIREWORKS_SPARK_MODEL, fw.sparkModel)
        assertTrue(fw.serves(Jobs.SPARK_SCORE) && fw.serves(Jobs.SPARK_SHEET))
        assertFalse(fw.serves(Jobs.SAM_TABLE) || fw.serves(Jobs.MUSE_STYLIZE) || fw.serves(Jobs.VOICE))
        assertEquals("image/png", sniffImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("image/jpeg", sniffImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun nullChatContentFromARunOutBudgetIsAParseErrorNamingTheFinishReason() {
        val raw = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":null},"finish_reason":"length"}],
            "usage":{"prompt_tokens":120,"completion_tokens":600}}"""
        val resp = MetaApi.json.decodeFromString(ChatResponse.serializer(), raw) // decodes; no SerializationException
        val e = kotlin.test.assertFailsWith<MetaError.Parse> { resp.contentText() }
        assertEquals("empty content, finish_reason=length", e.message)
        val fx = kotlin.test.assertFailsWith<MetaError.Parse> {
            FixtureConverter.fromResponse(Jobs.SPARK_SHEET, "sample-01", raw, null, synthetic = false, note = "")
        }
        assertTrue("finish_reason=length" in fx.message!!, fx.message)
        assertEquals("0.8", MetaApi.json.decodeFromString(ChatResponse.serializer(), """{"choices":[{"message":{"content":"0.8"}}]}""").contentText())
    }
}
