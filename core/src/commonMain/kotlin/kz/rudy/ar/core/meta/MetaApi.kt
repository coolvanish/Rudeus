package kz.rudy.ar.core.meta

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.CharacterSheet
import kotlin.io.encoding.Base64

/**
 * Real [MetaModels] over Ktor against the Meta Model API (https://dev.meta.ai/docs). The key travels only in the
 * `Authorization` header, which the Logging plugin never prints (level INFO = method + url + status, no headers,
 * no bodies). Wire shapes live in MetaDtos.kt; failures surface as [MetaError].
 *
 * - segment: `POST /responses` on `sam-3.1`, ONE request per prompt (sent concurrently), one [MaskResult] per
 *   prompt in prompt order; no match = empty mask.
 * - stylize: `POST /images/edits`, JSON `images[]` = cutout + <= 9 refs.
 * - scoreStyle / scoreTechnique / characterSheet: `POST /chat/completions` on `muse-spark-1.3-contributor` (the only jobs
 *   Fireworks can serve); scoreTechnique meters under [Jobs.SPARK_SCORE_TECHNIQUE]; the sheet message carries the
 *   character's picture when one is given.
 * - transcribe: multipart `POST /asr/transcribe` with a `request` JSON part and an `audio` WAV part.
 */
class MetaApi(
    private val config: MetaConfig,
    private val client: HttpClient = defaultClient(config),
    /**
     * Receives the measured cost of every successful call, once per HTTP request: SAM and Muse from the request /
     * image count, Spark from the reply's token `usage` (skipped when the reply has none), all priced by [Pricing].
     * SAM calls report [Jobs.segmentJob] (cutout vs table). Null = not measured.
     */
    private val costMeter: CostMeter? = null,
) : MetaModels {

    override suspend fun segment(ref: String, image: ByteArray, prompts: List<String>): SegmentResult {
        requireServed(Jobs.SAM_TABLE, "segment")
        val mime = sniffImageMime(image)
        val b64 = Base64.encode(image)
        val job = Jobs.segmentJob(prompts)
        return segmentEach(prompts, config.samConcurrency) { prompt ->
            val req = SegmentRequest.of(config.samModel, prompt, mime, b64, config.samMaskEncoding)
            val resp: ResponsesResponse = postJson(ref, "segment[$prompt]", config.segmentPath, req)
            meter(job, ref, Pricing.samUsd(Usage.of(resp.usage)))
            // Lenient on the phone: malformed lines are skipped, an undecodable mask falls back to its filled box.
            parseOrThrow { parseSamResponse(prompt, resp, strict = false) }
        }
    }

    override suspend fun stylize(ref: String, cutout: ByteArray, refs: List<ByteArray>): ByteArray {
        requireServed(Jobs.MUSE_STYLIZE, "stylize")
        val images = ArrayList<ImageInput>()
        images += ImageInput(dataUrl(sniffImageMime(cutout), Base64.encode(cutout)))
        refs.take(MAX_STYLE_REFS).forEach { images += ImageInput(dataUrl(sniffImageMime(it), Base64.encode(it))) }
        val req = StylizeRequest(
            model = config.museImageModel,
            prompt = Prompts.STYLIZE,
            images = images,
            reasoningStrength = config.museReasoningStrength,
        )
        val resp: StylizeResponse = postJson(ref, "stylize", config.stylizePath, req)
        meter(Jobs.MUSE_STYLIZE, ref, Pricing.museUsd(resp.data.size))
        return parseOrThrow { Base64.decode(parseStylize(resp)) }
    }

    override suspend fun scoreStyle(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
        score(ref, "scoreStyle", Jobs.SPARK_SCORE, Prompts.STYLE_SCORE, image, refs)

    override suspend fun scoreTechnique(ref: String, image: ByteArray, refs: List<ByteArray>): Float =
        score(ref, "scoreTechnique", Jobs.SPARK_SCORE_TECHNIQUE, Prompts.STYLE_SCORE_NO_COLOUR, image, refs)

    /** One Spark score call, metered under [job]: [prompt], then [image], then up to [MAX_STYLE_REFS] [refs]. */
    private suspend fun score(ref: String, call: String, job: String, prompt: String, image: ByteArray, refs: List<ByteArray>): Float {
        val parts = ArrayList<ChatContentPart>()
        parts += ChatContentPart.text(prompt)
        parts += ChatContentPart.imageDataUrl(sniffImageMime(image), Base64.encode(image))
        refs.take(MAX_STYLE_REFS).forEach { parts += ChatContentPart.imageDataUrl(sniffImageMime(it), Base64.encode(it)) }
        val req = ChatRequest(
            model = config.sparkModel,
            messages = listOf(ChatMessage.parts("user", parts)),
            maxCompletionTokens = Prompts.SCORE_MAX_COMPLETION_TOKENS,
            reasoningEffort = Prompts.SCORE_REASONING_EFFORT,
        )
        val resp: ChatResponse = postJson(ref, call, config.chatPath, req)
        Pricing.sparkUsd(Usage.of(resp.usage))?.let { meter(job, ref, it) }
        return parseOrThrow { parseScore(resp.contentText()) }
    }

    override suspend fun characterSheet(ref: String, description: String, image: ByteArray?): CharacterSheet {
        val req = ChatRequest.characterSheet(config.sparkModel, description, image)
        val resp: ChatResponse = postJson(ref, "characterSheet", config.chatPath, req)
        Pricing.sparkUsd(Usage.of(resp.usage))?.let { meter(Jobs.SPARK_SHEET, ref, it) }
        return parseOrThrow { parseSheet(resp.contentText()) }
    }

    override suspend fun transcribe(ref: String, pcm16kMono: ByteArray, lang: String): String {
        requireServed(Jobs.VOICE, "transcribe")
        val wav = if (WavHeader.isWav(pcm16kMono)) pcm16kMono.also { WavHeader.requireSupported(it) }
        else parseOrThrow { WavHeader.wrapPcm16Mono(pcm16kMono, SAMPLE_RATE) }
        val req = TranscribeRequest(
            model = config.voiceModel,
            languageBias = languageName(lang)?.let { listOf(it) },
            keywords = config.voiceKeywords.takeIf { it.isNotEmpty() },
        )
        val requestJson = json.encodeToString(TranscribeRequest.serializer(), req)
        val resp: TranscribeResponse = send(ref, "transcribe") {
            client.submitFormWithBinaryData(
                url = config.url(config.transcribePath),
                formData = formData {
                    append("request", requestJson, Headers.build { append(HttpHeaders.ContentType, "application/json") })
                    append("audio", wav, Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"$ref.wav\"")
                    })
                },
            ) {
                bearerAuth(config.apiKey)
                accept(ContentType.Application.Json)
            }
        }
        Pricing.voiceUsd(resp.audioDurationMs)?.let { meter(Jobs.VOICE, ref, it) }
        return parseOrThrow { parseTranscript(resp) }
    }

    /** One [CostMeter.record] per successful HTTP call; a misbehaving meter never fails the call. */
    private fun meter(job: String, ref: String, usd: Double) {
        val m = costMeter ?: return
        try { m.record(job, ref, usd) } catch (e: Exception) { /* cost is telemetry, never a failure */ }
    }

    private fun requireServed(job: String, call: String) {
        if (!config.serves(job)) throw MetaError.Provider("$call is not served by ${config.provider}: only Meta hosts SAM 3.1, Muse Image and Voice Transcribe")
    }

    /** POSTs [body] as JSON and decodes the reply as [R]. */
    private suspend inline fun <reified B : Any, reified R : Any> postJson(ref: String, job: String, path: String, body: B): R =
        send(ref, job) {
            client.post(config.url(path)) {
                bearerAuth(config.apiKey)
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body)
            }
        }

    /** Runs [request] and decodes the reply as [R]; maps transport/HTTP/decoding failures to [MetaError]. */
    private suspend inline fun <reified R : Any> send(ref: String, job: String, request: () -> HttpResponse): R {
        val response: HttpResponse = try {
            request()
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
        /** Default SAM requests in flight per scan ([MetaConfig.samConcurrency]; rate limits are undocumented). */
        const val MAX_SAM_CONCURRENCY = 3

        /** Muse Image edit takes 1..10 images: the cutout plus at most 9 style references. */
        const val MAX_STYLE_REFS = 9

        /** [MetaModels.transcribe] receives 16 kHz mono PCM; the endpoint accepts 16 or 24 kHz WAV. */
        const val SAMPLE_RATE = 16_000

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

/**
 * Runs [one] for every prompt with at most [maxConcurrency] in flight and returns one mask per prompt, in prompt
 * order. A prompt whose call fails (HTTP 429/5xx, timeout, parse error) gets an empty mask and is listed in
 * [SegmentResult.failedPrompts]; only when EVERY prompt failed is the first failure (in prompt order) rethrown.
 * Cancellation of the caller still propagates.
 */
suspend fun segmentEach(prompts: List<String>, maxConcurrency: Int, one: suspend (String) -> MaskResult): SegmentResult {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    val gate = Semaphore(maxConcurrency)
    val outcomes: List<Result<MaskResult>> = coroutineScope {
        prompts.map { prompt ->
            async {
                gate.withPermit {
                    try {
                        Result.success(one(prompt))
                    } catch (e: CancellationException) {
                        // A timeout surfacing as cancellation is a per-prompt failure; our own cancellation is not.
                        if (!currentCoroutineContext().isActive) throw e
                        Result.failure(e)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }
        }.awaitAll()
    }
    if (prompts.isNotEmpty() && outcomes.all { it.isFailure }) throw outcomes.first().exceptionOrNull()!!
    val masks = prompts.indices.map { i -> outcomes[i].getOrElse { MaskResult(label = prompts[i], width = 0, height = 0) } }
    val failed = prompts.filterIndexed { i, _ -> outcomes[i].isFailure }
    return SegmentResult(withFrameSize(masks), failed)
}
