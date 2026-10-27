package kz.rudy.ar.core.meta

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.CharacterSheet
import kotlin.io.encoding.Base64

/**
 * Real [MetaModels] over Ktor. One POST per job; the key travels only in the `Authorization` header,
 * which the Logging plugin never prints (level INFO = method + url + status, no headers, no bodies).
 *
 * Endpoint paths, model ids and wire shapes live in [MetaConfig] / MetaDtos.kt and are placeholders
 * until the Day 1 probe is saved — see the `TODO(probe)` markers. Failures surface as [MetaError].
 */
class MetaApi(
    private val config: MetaConfig,
    private val client: HttpClient = defaultClient(config),
) : MetaModels {

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult {
        // TODO(probe): confirm against docs/api-samples/sam-cutout/meta.json (cutout) and sam-table/meta.json (table)
        val req = SegmentRequest(model = config.samModel, imageB64 = Base64.encode(image), prompts = prompts)
        val resp: SegmentResponse = call(ref, "segment", config.segmentPath, req)
        return parseOrThrow { parseSegment(resp) }
    }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray {
        // TODO(probe): confirm against docs/api-samples/muse-stylize/meta.json
        val req = StylizeRequest(
            model = config.museImageModel,
            imageB64 = Base64.encode(cutout),
            referenceImagesB64 = refs.take(MAX_STYLE_REFS).map { Base64.encode(it) },
            prompt = Prompts.STYLIZE,
        )
        val resp: StylizeResponse = call(ref, "stylize", config.stylizePath, req)
        return parseOrThrow { Base64.decode(parseStylize(resp)) }
    }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float {
        // TODO(probe): confirm against docs/api-samples/spark-score/meta.json
        val parts = ArrayList<ChatContentPart>()
        parts += ChatContentPart.text(Prompts.STYLE_SCORE)
        parts += ChatContentPart.imageDataUrl("image/png", Base64.encode(image))
        refs.take(MAX_STYLE_REFS).forEach { parts += ChatContentPart.imageDataUrl("image/png", Base64.encode(it)) }
        val req = ChatRequest(model = config.sparkModel, messages = listOf(ChatMessage("user", parts)), maxTokens = 16)
        val resp: ChatResponse = call(ref, "scoreStyle", config.chatPath, req)
        return parseOrThrow { parseScore(resp.contentText()) }
    }

    override suspend fun characterSheet(ref: String, description: String): CharacterSheet {
        // TODO(probe): confirm against docs/api-samples/spark-sheet/meta.json
        val req = ChatRequest(
            model = config.sparkModel,
            messages = listOf(ChatMessage("user", listOf(ChatContentPart.text(Prompts.characterSheet(description))))),
            temperature = 0.7f,
        )
        val resp: ChatResponse = call(ref, "characterSheet", config.chatPath, req)
        return parseOrThrow { parseSheet(resp.contentText()) }
    }

    override suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String {
        // TODO(probe): confirm against docs/api-samples/voice/meta.json
        val req = TranscribeRequest(model = config.voiceModel, audioB64 = Base64.encode(pcm16kMono), language = lang)
        val resp: TranscribeResponse = call(ref, "transcribe", config.transcribePath, req)
        return parseOrThrow { parseTranscript(resp) }
    }

    /** POSTs [body] as JSON and decodes the reply as [R]; maps transport/HTTP/decoding failures to [MetaError]. */
    private suspend inline fun <reified B : Any, reified R : Any> call(ref: String, job: String, path: String, body: B): R {
        val response: HttpResponse = try {
            client.post(config.url(path)) {
                bearerAuth(config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MetaError) {
            throw e
        } catch (e: Throwable) {
            throw MetaError.Network("$job($ref) -> ${config.provider}: ${e::class.simpleName}: ${e.message}", e)
        }
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            throw MetaError.Http(response.status.value, text)
        }
        return try {
            response.body<R>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MetaError.Parse("$job($ref): cannot decode reply: ${e.message}", e)
        }
    }

    private inline fun <T> parseOrThrow(block: () -> T): T = try {
        block()
    } catch (e: MetaError) {
        throw e
    } catch (e: SerializationException) {
        throw MetaError.Parse(e.message ?: "bad reply", e)
    } catch (e: IllegalArgumentException) {
        throw MetaError.Parse(e.message ?: "bad reply", e)
    }

    companion object {
        /** Muse Image edit takes at most 9 style references (ai/build-plan.md). */
        const val MAX_STYLE_REFS = 9

        val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        /**
         * Engine comes from the platform source set (CIO on jvm, OkHttp on android) via Ktor's engine discovery.
         * Logging stays at INFO: request line + status only, never headers (the key) or bodies (base64 images).
         */
        fun defaultClient(config: MetaConfig): HttpClient = HttpClient {
            install(ContentNegotiation) { json(json) }
            install(Logging) {
                level = LogLevel.INFO
                sanitizeHeader { true }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = config.connectTimeoutMs
                requestTimeoutMillis = config.requestTimeoutMs
                socketTimeoutMillis = config.requestTimeoutMs
            }
            expectSuccess = false
        }
    }
}
