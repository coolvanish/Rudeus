package kz.rudy.ar.cli

import kotlinx.coroutines.runBlocking
import kz.rudy.ar.core.io.FileFixtureSource
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.Fixture
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.FixtureConverter
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.meta.MetaError
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.meta.Provider
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.core.pipeline.CharacterPipeline
import kz.rudy.ar.core.pipeline.StyleGate
import java.io.File
import java.time.LocalDate
import java.util.Base64
import kotlin.system.exitProcess

/*
 * rudeus CLI — runs the same :core pipeline as the phone, from the Mac.
 *
 *   character --fixtures <dir> --out <dir> [--ref <name>] [--all] [--live] [--replay-latency]
 *             [--drawings <dir>] [--provider meta|fireworks]
 *   fixture --job <job> --response <ref.response.json> [--ref <ref>] [--out <dir>] [--latency-ms <n>] [--synthetic] [--force]
 *   fixture --job sam-cutout|sam-table|sam-rig (--response <ref.prompt.response.json> ... | --responses-dir <dir> --ref <ref>)
 *           [--prompts <prompts.json>] [--out <dir>] [--latency-ms <n>] [--synthetic] [--force]
 *   metrics summarise <runsDir>
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val code = try {
        when (args[0]) {
            "character" -> character(parseFlags(args.drop(1)))
            "fixture" -> fixture(parseFlags(args.drop(1)))
            "metrics" -> metrics(args.drop(1))
            "help", "--help", "-h" -> usage()
            else -> usage("unknown command: ${args[0]}")
        }
    } catch (e: CliError) {
        System.err.println("error: ${e.message}")
        2
    }
    exitProcess(code)
}

private class CliError(message: String) : Exception(message)

private fun usage(error: String? = null): Int {
    if (error != null) System.err.println("error: $error")
    System.err.println(
        """
        usage:
          character --fixtures <dir> --out <dir> [--ref <name> | --all] [--live] [--replay-latency]
                    [--drawings <dir>] [--provider meta|fireworks]
          fixture --job <job> --response <ref.response.json> [--ref <ref>] [--out <dir>] [--latency-ms <n>] [--synthetic] [--force]
          fixture --job sam-cutout|sam-table|sam-rig (--response <ref.prompt.response.json> ... | --responses-dir <dir> --ref <ref>)
                  [--prompts <prompts.json>] [--out <dir>] [--latency-ms <n>] [--synthetic] [--force]
          metrics summarise <runsDir>
        """.trimIndent(),
    )
    return if (error == null) 0 else 2
}

private class Flags(val values: Map<String, List<String>>, val switches: Set<String>) {
    fun get(name: String): String? = values[name]?.last()
    fun all(name: String): List<String> = values[name].orEmpty()
    fun require(name: String): String = get(name) ?: throw CliError("--$name is required")
    fun has(name: String): Boolean = name in switches
}

private fun parseFlags(args: List<String>): Flags {
    val values = HashMap<String, MutableList<String>>()
    val switches = HashSet<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) throw CliError("unexpected argument: $a")
        val name = a.removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next != null && !next.startsWith("--")) { values.getOrPut(name) { ArrayList() } += next; i += 2 } else { switches += name; i++ }
    }
    return Flags(values, switches)
}

// ---------------------------------------------------------------- character

private fun character(flags: Flags): Int {
    val fixturesDir = File(flags.require("fixtures"))
    val outDir = File(flags.require("out"))
    val live = flags.has("live")
    val replayLatency = flags.has("replay-latency")
    val provider = when (flags.get("provider")?.lowercase()) {
        null, "meta" -> Provider.META
        "fireworks" -> Provider.FIREWORKS
        else -> throw CliError("--provider must be meta or fireworks")
    }
    if (!fixturesDir.isDirectory) throw CliError("fixtures dir not found: $fixturesDir")

    val source = FileFixtureSource(File("."))
    val fixtureApi = FixtureApi(source, root = fixturesDir.path, replayLatency = replayLatency)

    val models: MetaModels
    val providerName: String
    if (live) {
        val key = System.getenv("META_API_KEY").orEmpty()
        if (key.isBlank()) throw CliError("--live needs META_API_KEY in the environment")
        models = MetaApi(MetaConfig(apiKey = key, provider = provider))
        providerName = provider.metricsName
    } else {
        models = fixtureApi
        providerName = "fixture"
    }

    // Inputs: --ref <name>, --all (every sam-cutout fixture), or --drawings <dir> (every image file, live runs).
    val inputs: List<Pair<String, File>> = when {
        flags.get("drawings") != null -> {
            val dir = File(flags.require("drawings"))
            dir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }.orEmpty()
                .sortedBy { it.name }.map { it.nameWithoutExtension to it }
        }
        flags.get("ref") != null -> {
            val ref = flags.require("ref")
            listOf(ref to inputImage(fixturesDir, ref))
        }
        flags.has("all") -> fixtureApi.listRefs(Jobs.SAM_CUTOUT).map { it to inputImage(fixturesDir, it) }
        else -> throw CliError("give --ref <name>, --all or --drawings <dir>")
    }
    if (inputs.isEmpty()) throw CliError("no inputs found")

    val styleRefs = styleRefs(fixturesDir)
    if (styleRefs.isEmpty()) System.err.println("warning: no style refs in ${File(fixturesDir, "$MUSE_DIR/refs")}; deterministic gate half is off")

    val metrics = MetricsLog(source = "cli", provider = providerName, device = "mac")
    val pipeline = CharacterPipeline(
        models = models,
        gate = StyleGate(),
        metrics = metrics,
        rasterDecoder = JvmRaster::decode,
        cutoutComposer = JvmRaster::cutout,
    )
    val spritesDir = File(outDir, "sprites").apply { mkdirs() }

    println("run ${metrics.runId}: ${inputs.size} input(s), provider=$providerName, replayLatency=$replayLatency")
    var failures = 0
    runBlocking {
        for ((ref, file) in inputs) {
            val image = file.takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
            val description = description(fixturesDir, ref)
            try {
                val result = pipeline.run(ref, image, styleRefs, description)
                val spriteFile = File(spritesDir, "$ref.png")
                spriteFile.writeBytes(result.sprite)
                println(
                    "  $ref: gate=${result.gateScore} ${if (result.gatePassed) "PASS" else "FAIL"} attempts=${result.attempts} " +
                        "ability=${result.sheet.ability} name=${result.sheet.name} -> ${spriteFile.path}",
                )
            } catch (e: MetaError) {
                failures++
                println("  $ref: FAILED ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    val path = FileMetricsSink(outDir).write("${metrics.runId}.json", metrics.toJson())
    val s = metrics.toRun().summary
    println("wrote $path")
    println("summary: n=${s.n} passFirstTry=${s.gatePassRateFirstTry} p50=${s.p50Ms}ms p90=${s.p90Ms}ms cost=$${s.costPerCharacterUsd}/character")
    return if (failures == 0) 0 else 1
}

private const val MUSE_DIR = "muse-stylize"
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

/** `<fixtures>/sam-cutout/<ref>.input.(png|jpg|jpeg)` or `<ref>.(png|jpg)`; a missing file yields empty bytes (fixtures don't read it). */
private fun inputImage(fixturesDir: File, ref: String): File {
    val dir = File(fixturesDir, Jobs.SAM_CUTOUT)
    val candidates = IMAGE_EXTENSIONS.flatMap { listOf("$ref.input.$it", "$ref.$it") }
    return candidates.map { File(dir, it) }.firstOrNull { it.isFile } ?: File(dir, "$ref.input.png")
}

private fun styleRefs(fixturesDir: File): List<ByteArray> =
    File(fixturesDir, "$MUSE_DIR/refs").listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
        .orEmpty().sortedBy { it.name }.map { it.readBytes() }

private fun description(fixturesDir: File, ref: String): String =
    File(fixturesDir, "${Jobs.SPARK_SHEET}/$ref.description.txt").takeIf { it.isFile }?.readText()?.trim()
        ?: "a child's drawing of a character"

// ---------------------------------------------------------------- fixture

/** Converts a raw probe reply (`<ref>.response.json`) into `<out>/<ref>.fixture.json` via [FixtureConverter]. */
private fun fixture(flags: Flags): Int {
    val job = flags.require("job")
    if (job !in FixtureConverter.JOBS) throw CliError("unknown job '$job' (expected one of ${FixtureConverter.JOBS.joinToString()})")
    if (job in FixtureConverter.SAM_JOBS) return samFixture(job, flags)
    if (flags.all("response").size > 1) throw CliError("--response is repeatable only for the SAM jobs")
    val response = File(flags.require("response"))
    if (!response.isFile) throw CliError("response file not found: $response")
    val ref = flags.get("ref") ?: response.name.removeSuffix(RESPONSE_SUFFIX).takeIf { it != response.name }
        ?: throw CliError("cannot derive ref from ${response.name} (expected <ref>$RESPONSE_SUFFIX); pass --ref")
    if (ref.isBlank() || '/' in ref || ".." in ref) throw CliError("bad ref '$ref': must be non-blank, without '/' or '..'")
    val outDir = flags.get("out")?.let(::File) ?: response.absoluteFile.parentFile
    if (outDir.absoluteFile.name != job) System.err.println("warning: out dir '${outDir.absoluteFile.name}' is not the job folder '$job'")
    val latencyMs = flags.get("latency-ms")?.let { it.toLongOrNull() ?: throw CliError("--latency-ms must be an integer") }
    val note = "converted from ${response.name} by cli fixture on ${LocalDate.now()}"

    val target = File(outDir, "$ref${FixtureApi.SUFFIX}")
    refuseSyntheticOverwrite(target, flags)

    val raw = response.readText()
    val (fx, pngBytes) = try {
        val fx = FixtureConverter.fromResponse(job, ref, raw, latencyMs, synthetic = flags.has("synthetic"), note = note)
        fx to if (job == Jobs.MUSE_STYLIZE) FixtureConverter.inlineStylizeBase64(raw)?.let(::decodePng) else null
    } catch (e: MetaError) {
        System.err.println("error: ${e.message}")
        return 1
    }

    writeFixture(outDir, target, fx)

    if (job == Jobs.MUSE_STYLIZE) {
        val png = File(outDir, FixtureConverter.outputName(ref))
        when {
            pngBytes != null && png.exists() -> println("kept existing ${png.path}")
            pngBytes != null -> { png.writeBytes(pngBytes); println("wrote ${png.path} (decoded inline payload)") }
            !png.isFile -> System.err.println("warning: ${png.path} is missing; the probe must save the stylized PNG there")
        }
    }
    return 0
}

private fun refuseSyntheticOverwrite(target: File, flags: Flags) {
    if (target.isFile && !flags.has("force")) {
        val existingSynthetic = runCatching { FixtureApi.json.decodeFromString(Fixture.serializer(), target.readText()).synthetic }.getOrDefault(false)
        if (existingSynthetic) throw CliError("${target.path} is a synthetic fixture; pass --force to replace it")
    }
}

private fun writeFixture(outDir: File, target: File, fx: Fixture) {
    outDir.mkdirs()
    val replacing = target.isFile
    target.writeText(FixtureApi.json.encodeToString(Fixture.serializer(), fx) + "\n")
    println("${if (replacing) "replaced" else "wrote"} ${target.path}")
}

/**
 * SAM 3.1 takes one concept per request, so a probe leaves one `<ref>.<prompt>.response.json` per prompt.
 * Collects them (repeated `--response`, in the given order, or every `<ref>[.input].*.response.json` in
 * `--responses-dir`, ordered by prompts.json) and writes ONE `<ref>.fixture.json` with one mask per prompt.
 */
private fun samFixture(job: String, flags: Flags): Int {
    val dirFlag = flags.get("responses-dir")
    val given = flags.all("response")
    if ((dirFlag == null) == given.isEmpty()) throw CliError("$job: give --response <file> (repeatable) or --responses-dir <dir> --ref <ref>, not both")

    val files: List<File>
    val dir: File
    if (dirFlag != null) {
        dir = File(dirFlag)
        if (!dir.isDirectory) throw CliError("responses dir not found: $dir")
        val wanted = flags.require("ref")
        files = dir.listFiles { f -> f.isFile && FixtureConverter.splitSamResponseName(f.name)?.first == wanted }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) throw CliError("no $wanted.<prompt>${FixtureConverter.RESPONSE_SUFFIX} (or $wanted.input.<prompt>...) in $dir")
    } else {
        files = given.map(::File)
        files.firstOrNull { !it.isFile }?.let { throw CliError("response file not found: $it") }
        dir = files.first().absoluteFile.parentFile
    }

    val split = files.map { f -> f to (FixtureConverter.splitSamResponseName(f.name) ?: throw CliError("${f.name}: expected <ref>.<prompt>${FixtureConverter.RESPONSE_SUFFIX}")) }
    val refs = split.map { it.second.first }.distinct()
    val ref = flags.get("ref") ?: refs.singleOrNull() ?: throw CliError("replies name several refs (${refs.joinToString()}); pass --ref")
    if (ref.isBlank() || '/' in ref || ".." in ref) throw CliError("bad ref '$ref': must be non-blank, without '/' or '..'")

    // Real prompt text from prompts.json (file names carry a sanitised token); prompts.json also fixes the order.
    val promptsFile = flags.get("prompts")?.let(::File) ?: File(dir, "prompts.json")
    val prompts: List<String> = if (promptsFile.isFile) {
        runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(promptsFile.readText()) }
            .getOrElse { throw CliError("${promptsFile.path}: must be a JSON array of strings") }
    } else emptyList()
    val byToken = prompts.associateBy { FixtureConverter.samPromptToken(it) }
    var entries = split.map { (f, rp) -> Triple(f, byToken[rp.second] ?: rp.second, prompts.indexOfFirst { FixtureConverter.samPromptToken(it) == rp.second }) }
    if (dirFlag != null) entries = entries.sortedWith(compareBy({ if (it.third < 0) Int.MAX_VALUE else it.third }, { it.first.name }))
    val missing = prompts.filter { p -> entries.none { it.second == p } }
    if (missing.isNotEmpty()) System.err.println("warning: no reply for prompt(s) ${missing.joinToString()}; the fixture has no mask for them")

    val outDir = flags.get("out")?.let(::File) ?: dir
    if (outDir.absoluteFile.name != job) System.err.println("warning: out dir '${outDir.absoluteFile.name}' is not the job folder '$job'")
    val latencyMs = flags.get("latency-ms")?.let { it.toLongOrNull() ?: throw CliError("--latency-ms must be an integer") }
    val note = "converted from ${entries.joinToString { it.first.name }} by cli fixture on ${LocalDate.now()}"
    val target = File(outDir, "$ref${FixtureApi.SUFFIX}")
    refuseSyntheticOverwrite(target, flags)

    val fx = try {
        FixtureConverter.fromSamResponses(job, ref, entries.map { it.second to it.first.readText() }, latencyMs, synthetic = flags.has("synthetic"), note = note)
    } catch (e: MetaError) {
        System.err.println("error: ${e.message}")
        return 1
    }
    writeFixture(outDir, target, fx)
    for (m in fx.masks.orEmpty()) println("  ${m.label}: ${if (m.isEmpty) "absent" else "area=${m.area} bbox=${m.bbox}"} ${m.width}x${m.height}")
    return 0
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

/** Decodes the inline stylize payload; throws [MetaError.Parse] unless it is non-empty base64 of a PNG. */
private fun decodePng(b64: String): ByteArray {
    val bytes = try { Base64.getDecoder().decode(b64) } catch (e: IllegalArgumentException) {
        throw MetaError.Parse("${Jobs.MUSE_STYLIZE}: data[0].b64_json is not base64: ${e.message}", e)
    }
    if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
        throw MetaError.Parse("${Jobs.MUSE_STYLIZE}: data[0].b64_json is not a PNG (${bytes.size} bytes)")
    }
    return bytes
}

private const val RESPONSE_SUFFIX = FixtureConverter.RESPONSE_SUFFIX

// ---------------------------------------------------------------- metrics

private fun metrics(args: List<String>): Int {
    if (args.size < 2 || args[0] != "summarise") return usage("metrics takes: summarise <runsDir>")
    val dir = File(args[1])
    val files = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().sortedBy { it.name }
    if (files.isEmpty()) throw CliError("no runs/*.json in $dir")
    val runs = ArrayList<MetricsRun>()
    for (f in files) {
        try { runs += MetricsLog.fromJson(f.readText()) } catch (e: Exception) { System.err.println("skip ${f.name}: ${e.message}") }
    }
    print(MetricsLog.summarise(runs))
    return 0
}
