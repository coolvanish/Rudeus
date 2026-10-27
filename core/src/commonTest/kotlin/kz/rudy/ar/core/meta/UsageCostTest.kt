package kz.rudy.ar.core.meta

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Lenient `usage` parsing on every reply shape and its conversion to USD through [Pricing]. */
class UsageCostTest {
    private val repo = DiskFixtureSource.repoRoot()
    private fun sample(path: String) = repo.readText("docs/api-samples/$path")!!

    @Test
    fun usageParsesNumbersAndIgnoresRedacted() {
        // Chat (Spark): prompt/completion tokens at the standard-tier rates.
        val chat = MetaApi.json.decodeFromString(
            ChatResponse.serializer(),
            """{"choices":[{"message":{"content":"0.8"}}],"usage":{"prompt_tokens":6000,"completion_tokens":300,"total_tokens":6300}}""",
        )
        val chatUsage = Usage.of(chat.usage)
        assertEquals(Usage(inputTokens = 6000, outputTokens = 300), chatUsage)
        assertEquals(6000 * 1.25e-6 + 300 * 4.25e-6, Pricing.sparkUsd(chatUsage)!!, 1e-12)

        // The saved real chat replies are redacted: "<redacted>" is absent, the decode does not throw, no USD.
        val redacted = MetaApi.json.decodeFromString(ChatResponse.serializer(), sample("spark-sheet/drawing-01.description.response.json"))
        assertEquals(Usage(), Usage.of(redacted.usage))
        assertNull(Pricing.sparkUsd(Usage.of(redacted.usage)))
        assertEquals("0.00", MetaApi.json.decodeFromString(ChatResponse.serializer(), sample("spark-score/drawing-01.out-1.response.json")).contentText())

        // Image (Muse): the real reply carries numeric token counts; the price is per generated image.
        val image = MetaApi.json.decodeFromString(StylizeResponse.serializer(), sample("muse-stylize/drawing-01.input.response.json"))
        assertEquals(Usage(inputTokens = 4602, outputTokens = 1100), Usage.of(image.usage))
        assertEquals(0.01, Pricing.museUsd(image.data.size), 1e-12)

        // SAM (Responses): tokens redacted, images_processed = 1 -> one request.
        val sam = MetaApi.json.decodeFromString(ResponsesResponse.serializer(), sample("sam-cutout/drawing-01.input.drawing.response.json"))
        val samUsage = Usage.of(sam.usage)
        assertEquals(Usage(imagesProcessed = 1), samUsage)
        assertEquals(0.0025, Pricing.samUsd(samUsage), 1e-12)
        assertEquals(0.0025, Pricing.samUsd(Usage()), 1e-12, "no usage at all: still one request")
        assertEquals(0.005, Pricing.samUsd(Usage(imagesProcessed = 2)), 1e-12)

        // Malformed blocks never throw: a string, an array, negatives, NaN-ish strings, numeric strings.
        assertEquals(Usage(), Usage.of(JsonPrimitive("<redacted>")))
        assertEquals(Usage(), Usage.of(null))
        val odd = MetaApi.json.decodeFromString(
            ChatResponse.serializer(),
            """{"usage":{"prompt_tokens":-5,"completion_tokens":"42","images_processed":[1]}}""",
        )
        assertEquals(Usage(outputTokens = 42), Usage.of(odd.usage))
        assertEquals(42 * 4.25e-6, Pricing.sparkUsd(Usage.of(odd.usage))!!, 1e-12)

        // Voice: $0.18 per audio hour.
        assertEquals(0.18, Pricing.voiceUsd(3_600_000)!!, 1e-12)
        assertNull(Pricing.voiceUsd(null))
    }
}
