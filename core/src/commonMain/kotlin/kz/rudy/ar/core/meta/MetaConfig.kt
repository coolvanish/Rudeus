package kz.rudy.ar.core.meta

/**
 * Who serves the models.
 *
 * - [META]: the Meta Model API (https://api.meta.ai/v1, `Authorization: Bearer <key>`) serves all four models.
 * - [FIREWORKS]: chat-only fallback. Fireworks serves the open-weight Spark (`accounts/fireworks/models/muse-spark-open`,
 *   OpenAI-compatible, limited preview), so only [MetaModels.scoreStyle] and [MetaModels.characterSheet] work there.
 *   **SAM 3.1, Muse Image and Muse Voice Transcribe have no fallback provider** (docs/api-samples/README.md,
 *   "Fallback providers", 2026-10-28); [MetaApi] fails those jobs with [MetaError.Provider] instead of a 404.
 */
enum class Provider(val metricsName: String) { META("meta"), FIREWORKS("fireworks") }

/**
 * Connection settings for [MetaApi]. The key comes from `BuildConfig.META_API_KEY` (phone) or the
 * `META_API_KEY` env (CLI); it is never logged (see [MetaApi]'s Logging config).
 *
 * Hosts, paths and model ids verified against https://dev.meta.ai/docs on 2026-10-28 (Stream A, re-checked in
 * Stream B2); the request templates in docs/api-samples/<job>/request.json.tmpl carry the per-page sources.
 */
data class MetaConfig(
    val apiKey: String,
    val provider: Provider = Provider.META,
    val baseUrl: String = defaultBaseUrl(provider),
    val samModel: String = "sam-3.1",
    val museImageModel: String = "muse-image-1.0",
    /**
     * Spark for the score + sheet chat calls: the CONTRIBUTOR tier on Meta, never the standard model
     * (user decision 2026-10-28: contributor tier, training on our prompts is acceptable, cheaper).
     * On [Provider.FIREWORKS] defaults to [FIREWORKS_SPARK_MODEL].
     */
    val sparkModel: String = defaultSparkModel(provider),
    val voiceModel: String = "muse-voice-transcribe-1.0",
    /** Endpoint paths relative to [baseUrl] (which already ends in `/v1`). SAM rides the Responses API. */
    val segmentPath: String = "/responses",
    val stylizePath: String = "/images/edits",
    val chatPath: String = "/chat/completions",
    val transcribePath: String = "/asr/transcribe",
    /** SAM `metadata.mask_encoding`: "lossless" (API default, `~`) or "one_bit" (`!`, several times smaller). */
    val samMaskEncoding: String = "lossless",
    /** Muse Image `reasoning_strength`: "low" = single pass (faster), "high" = API default (self-refines); null = omit. */
    val museReasoningStrength: String? = "low",
    /** Voice `keywords` to bias recognition (e.g. the Kazakh command words); empty = omitted. */
    val voiceKeywords: List<String> = emptyList(),
    val connectTimeoutMs: Long = 10_000,
    val requestTimeoutMs: Long = 120_000,
) {
    init { require(apiKey.isNotBlank()) { "MetaConfig.apiKey is blank" } }

    fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /** False for the jobs a provider does not serve (everything but chat on Fireworks). */
    fun serves(job: String): Boolean = provider == Provider.META || job == Jobs.SPARK_SCORE || job == Jobs.SPARK_SHEET

    companion object {
        /** Spark contributor tier (user decision 2026-10-28: contributor tier, training on our prompts is acceptable, cheaper). */
        const val META_SPARK_MODEL = "muse-spark-1.3-contributor"

        /** Open-weight Spark on Fireworks (OpenAI-compatible chat only). */
        const val FIREWORKS_SPARK_MODEL = "accounts/fireworks/models/muse-spark-open"

        fun defaultBaseUrl(provider: Provider): String = when (provider) {
            Provider.META -> "https://api.meta.ai/v1"
            Provider.FIREWORKS -> "https://api.fireworks.ai/inference/v1"
        }

        fun defaultSparkModel(provider: Provider): String = when (provider) {
            Provider.META -> META_SPARK_MODEL
            Provider.FIREWORKS -> FIREWORKS_SPARK_MODEL
        }
    }
}
