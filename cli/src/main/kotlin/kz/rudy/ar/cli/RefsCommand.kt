package kz.rudy.ar.cli

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.pipeline.RefCandidate
import kz.rudy.ar.core.pipeline.RefCuller
import java.io.File
import java.time.LocalDate
import java.util.Base64

/*
 * Style-reference candidates (Day 2: "8 culled style refs").
 *
 *   refs generate --prompt <text | @file> --n <1..10> --out <dir> [--size WxH] [--budget-usd <x>]
 *       POST /v1/images/generations on Muse Image (verified 2026-10-28, https://dev.meta.ai/docs/image-generation):
 *       body {model, prompt, n, size?, response_format:"b64_json", output_format:"png"} — unknown top-level fields
 *       are a 400, so no `background`. $0.01 per image. Writes gen-<nn>.png (numbering continues after existing
 *       files) and appends the prompt to prompt.txt.
 *   refs cull --from <dir> [--keep 8] --out <dir> [--force]
 *       RefCuller over every decodable png/jpg/jpeg, copies the kept files, writes <out>/cull.json.
 */
internal fun refs(args: List<String>): Int = when (args.firstOrNull()) {
    "generate" -> generate(parseFlags(args.drop(1)))
    "cull" -> cull(parseFlags(args.drop(1)))
    else -> usage("refs takes: generate ... | cull ...")
}

// ---------------------------------------------------------------- generate

private const val GENERATIONS_PATH = "/images/generations"
private const val USD_PER_IMAGE = 0.01
private const val MAX_IMAGES = 10
private const val MAX_RETRIES_503 = 2
private const val BACKOFF_MS = 2_000L
private const val DEFAULT_GENERATE_BUDGET_USD = 1.00

@Serializable
private data class GenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int,
    val size: String? = null,
    @SerialName("response_format") val responseFormat: String = "b64_json",
    @SerialName("output_format") val outputFormat: String = "png",
)

@Serializable
private data class GenerationResponse(val data: List<GeneratedImage> = emptyList(), val usage: JsonObject? = null)

@Serializable
private data class GeneratedImage(@SerialName("b64_json") val b64Json: String? = null)

private val refsJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }

private fun generate(flags: Flags): Int {
    val promptArg = flags.require("prompt")
    val prompt = if (promptArg.startsWith("@")) {
        val f = File(promptArg.removePrefix("@"))
        if (!f.isFile) throw CliError("prompt file not found: $f")
        f.readText().trim()
    } else promptArg.trim()
    if (prompt.isEmpty()) throw CliError("--prompt is empty")
    val n = flags.int("n") ?: throw CliError("--n is required")
    if (n !in 1..MAX_IMAGES) throw CliError("--n must be 1..$MAX_IMAGES")
    val size = flags.get("size")?.also { if (!Regex("\\d+x\\d+").matches(it)) throw CliError("--size must be WxH, e.g. 1024x1024") }
    val outDir = File(flags.require("out"))
    val budget = flags.double("budget-usd") ?: DEFAULT_GENERATE_BUDGET_USD
    val cost = n * USD_PER_IMAGE
    if (cost > budget + 1e-9) throw CliError("$n image(s) cost $${fmtUsd(cost)} > --budget-usd $${fmtUsd(budget)}")

    val config = MetaConfig(apiKey = KeySource.read())
    val body = refsJson.encodeToString(GenerationRequest.serializer(), GenerationRequest(config.museImageModel, prompt, n, size))
    val client = MetaApi.defaultClient(config)
    println("refs generate: n=$n size=${size ?: "default"} model=${config.museImageModel} cost=$${fmtUsd(cost)} -> ${outDir.path}")

    val t0 = System.currentTimeMillis()
    val text = try {
        runBlocking { postWithRetry(client, config, body) }
    } finally {
        client.close()
    }
    val ms = System.currentTimeMillis() - t0

    val parsed = try { refsJson.decodeFromString(GenerationResponse.serializer(), text) } catch (e: Exception) {
        throw CliError("cannot decode the generations reply: ${e.message}")
    }
    outDir.mkdirs()
    var next = nextGenIndex(outDir)
    val written = ArrayList<String>()
    for ((i, img) in parsed.data.withIndex()) {
        val bytes = img.b64Json?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (bytes == null || bytes.size < PNG_SIGNATURE.size || !bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            System.err.println("warning: data[$i] is not a base64 PNG; skipped")
            continue
        }
        val f = File(outDir, "gen-%02d.png".format(next++))
        f.writeBytes(bytes)
        written += f.name
    }
    if (written.isNotEmpty()) {
        File(outDir, "prompt.txt").appendText(
            "# ${written.first()}..${written.last()} size=${size ?: "default"} model=${config.museImageModel} on ${LocalDate.now()}\n$prompt\n\n",
        )
    }
    println("wrote ${written.size}/$n image(s) in $ms ms: ${written.joinToString()}")
    parsed.usage?.let { println("usage: $it") }
    return if (written.size == n) 0 else 1
}

/** POSTs [body] to the generations endpoint; a 503 is retried with doubling backoff at most [MAX_RETRIES_503] times. */
private suspend fun postWithRetry(client: HttpClient, config: MetaConfig, body: String): String {
    for (attempt in 0..MAX_RETRIES_503) {
        val resp: HttpResponse = try {
            client.post(config.url(GENERATIONS_PATH)) {
                bearerAuth(config.apiKey)
                accept(ContentType.Application.Json)
                setBody(TextContent(body, ContentType.Application.Json))
            }
        } catch (e: Exception) {
            throw CliError("network: ${e::class.simpleName}: ${e.message}")
        }
        val reply = runCatching { resp.bodyAsText() }.getOrDefault("")
        if (resp.status.isSuccess()) return reply
        if (resp.status.value != 503 || attempt == MAX_RETRIES_503) throw CliError("HTTP ${resp.status.value}: ${reply.take(300)}")
        val wait = BACKOFF_MS shl attempt
        System.err.println("HTTP 503, retry ${attempt + 1}/$MAX_RETRIES_503 in $wait ms")
        delay(wait)
    }
    error("unreachable")
}

/** 1 + the highest existing `gen-<nn>.png` in [dir], so repeated runs add candidates instead of overwriting. */
private fun nextGenIndex(dir: File): Int =
    (dir.list().orEmpty().mapNotNull { Regex("gen-(\\d+)\\.png").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1

// ---------------------------------------------------------------- cull

/** Tests rely on the synthetic refs here; `refs cull` never writes into it without --force. */
private const val PROTECTED_REFS_DIR = "docs/api-samples/muse-stylize/refs"

@Serializable
private data class CullReport(
    val schema: Int = 1,
    val from: String,
    val keep: Int,
    val kept: List<String>,
    val dropped: List<CullDrop>,
)

@Serializable
private data class CullDrop(val name: String, val reason: String)

private fun cull(flags: Flags): Int {
    val from = File(flags.require("from"))
    if (!from.isDirectory) throw CliError("--from dir not found: $from")
    val outDir = File(flags.require("out"))
    val keep = flags.int("keep") ?: 8
    if (keep <= 0) throw CliError("--keep must be positive")
    if (outDir.canonicalPath.replace('\\', '/').endsWith("/$PROTECTED_REFS_DIR") && !flags.has("force")) {
        throw CliError("$outDir holds the synthetic refs the tests rely on; pass --force to write there")
    }

    val files = from.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
    val dropped = ArrayList<CullDrop>()
    val candidates = ArrayList<RefCandidate>()
    for (f in files) {
        val ext = f.extension.lowercase()
        if (ext == "webp") { dropped += CullDrop(f.name, "webp: ImageIO cannot decode it (refs generate asks for png)"); continue }
        if (ext !in IMAGE_EXTENSIONS) continue
        val raster = JvmRaster.decode(f.readBytes())
        if (raster == null) dropped += CullDrop(f.name, "undecodable by ImageIO") else candidates += RefCandidate(f.name, raster)
    }
    if (candidates.isEmpty()) throw CliError("no decodable png/jpg/jpeg in $from")

    val result = RefCuller.cull(candidates, keep)
    dropped += result.dropped.map { CullDrop(it.name, it.reason) }
    outDir.mkdirs()
    for (name in result.kept) {
        val src = File(from, name)
        val dst = File(outDir, name)
        if (dst.canonicalFile != src.canonicalFile) src.copyTo(dst, overwrite = true)
    }
    val report = CullReport(from = from.path, keep = keep, kept = result.kept, dropped = dropped)
    File(outDir, "cull.json").writeText(refsJson.encodeToString(CullReport.serializer(), report) + "\n")

    println("refs cull: ${candidates.size} candidate(s), kept ${result.kept.size} -> ${outDir.path}")
    result.kept.forEach { println("  keep $it") }
    dropped.forEach { println("  drop ${it.name}: ${it.reason}") }
    // `character --refs <out>` sends every image in the folder, so leftovers from an earlier cull would leak in.
    val stale = outDir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS && f.name !in result.kept }.orEmpty()
    if (stale.isNotEmpty()) System.err.println("warning: ${outDir.path} also holds ${stale.joinToString { it.name }} (not kept); remove them before using it as --refs")
    println("wrote ${File(outDir, "cull.json").path}")
    return 0
}
