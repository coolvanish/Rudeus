package kz.rudy.ar.core.meta

/**
 * Published Meta Model API prices (https://dev.meta.ai/docs, read 2026-10-28; the same figures are in
 * docs/api-samples/README.md, "Endpoints") and the per-call USD that [MetaApi] reports to its [CostMeter].
 *
 * - SAM 3.1: $0.0025 per request (one concept per request; `usage.images_processed` multiplies it when present).
 * - Muse Image edit: $0.01 per generated image (the reply's token `usage` is informative only).
 * - Muse Spark 1.3: priced at the STANDARD-tier rates, $1.25 per million input tokens and $4.25 per million output
 *   tokens (reasoning tokens are output tokens). We call `muse-spark-1.3-contributor`, which is cheaper but its price
 *   is not published, so every Spark figure here is an upper bound.
 * - Voice Transcribe: $0.18 per audio hour.
 */
object Pricing {
    const val SAM_USD_PER_REQUEST = 0.0025
    const val MUSE_IMAGE_USD_PER_IMAGE = 0.01
    const val SPARK_USD_PER_M_INPUT_TOKENS = 1.25
    const val SPARK_USD_PER_M_OUTPUT_TOKENS = 4.25
    const val VOICE_USD_PER_AUDIO_HOUR = 0.18

    /** One SAM request. */
    fun samUsd(usage: Usage): Double = SAM_USD_PER_REQUEST * (usage.imagesProcessed?.takeIf { it > 0 } ?: 1L)

    /** One Muse edit that returned [images] images. */
    fun museUsd(images: Int): Double = MUSE_IMAGE_USD_PER_IMAGE * images.coerceAtLeast(0)

    /** One Spark chat call; null when the reply carried no token counts at all (then the caller estimates). */
    fun sparkUsd(usage: Usage): Double? {
        if (usage.inputTokens == null && usage.outputTokens == null) return null
        return ((usage.inputTokens ?: 0L) * SPARK_USD_PER_M_INPUT_TOKENS + (usage.outputTokens ?: 0L) * SPARK_USD_PER_M_OUTPUT_TOKENS) / 1_000_000.0
    }

    /** One transcription of [audioMs] of audio; null when the duration is unknown. */
    fun voiceUsd(audioMs: Long?): Double? = audioMs?.takeIf { it >= 0 }?.let { it * VOICE_USD_PER_AUDIO_HOUR / 3_600_000.0 }
}
