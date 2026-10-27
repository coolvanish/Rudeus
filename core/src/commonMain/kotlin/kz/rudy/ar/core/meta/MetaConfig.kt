package kz.rudy.ar.core.meta

/** Who serves the models. A Baseten/Meta slug 404 is handled by swapping to FIREWORKS; nothing else changes. */
enum class Provider(val metricsName: String) { META("meta"), FIREWORKS("fireworks") }

/**
 * Connection settings for [MetaApi]. The key comes from `BuildConfig.META_API_KEY` (phone) or the
 * `META_API_KEY` env (CLI); it is never logged (see [MetaApi]'s Logging config).
 *
 * TODO(probe): base URLs, endpoint paths and model ids are placeholders until the Day 1 curl probes
 * are saved; confirm each against docs/api-samples/<job>/meta.json.
 */
data class MetaConfig(
    val apiKey: String,
    val provider: Provider = Provider.META,
    val baseUrl: String = defaultBaseUrl(provider),
    val samModel: String = "sam-3.1",
    val museImageModel: String = "muse-image-edit",
    val sparkModel: String = "spark-1.3",
    val voiceModel: String = "voice-transcribe",
    /** Endpoint paths relative to [baseUrl]. */
    val segmentPath: String = "/segment",
    val stylizePath: String = "/images/edits",
    val chatPath: String = "/chat/completions",
    val transcribePath: String = "/audio/transcriptions",
    val connectTimeoutMs: Long = 10_000,
    val requestTimeoutMs: Long = 120_000,
) {
    init { require(apiKey.isNotBlank()) { "MetaConfig.apiKey is blank" } }

    fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        fun defaultBaseUrl(provider: Provider): String = when (provider) {
            Provider.META -> "https://api.llama.com/v1" // TODO(probe): confirm host after Day 1 probe
            Provider.FIREWORKS -> "https://api.fireworks.ai/inference/v1" // TODO(probe): confirm fallback host
        }
    }
}
