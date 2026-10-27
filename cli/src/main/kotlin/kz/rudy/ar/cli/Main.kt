package kz.rudy.ar.cli

import kotlinx.coroutines.runBlocking
import kz.rudy.ar.core.io.FileFixtureSource
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.FixtureApi
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
import kotlin.system.exitProcess

/*
 * rudeus CLI — runs the same :core pipeline as the phone, from the Mac.
 *
 *   character --fixtures <dir> --out <dir> [--ref <name>] [--all] [--live] [--replay-latency]
 *             [--drawings <dir>] [--provider meta|fireworks]
 *   metrics summarise <runsDir>
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val code = try {
        when (args[0]) {
            "character" -> character(parseFlags(args.drop(1)))
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
          metrics summarise <runsDir>
        """.trimIndent(),
    )
    return if (error == null) 0 else 2
}

private class Flags(val values: Map<String, String>, val switches: Set<String>) {
    fun get(name: String): String? = values[name]
    fun require(name: String): String = values[name] ?: throw CliError("--$name is required")
    fun has(name: String): Boolean = name in switches
}

private fun parseFlags(args: List<String>): Flags {
    val values = HashMap<String, String>()
    val switches = HashSet<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) throw CliError("unexpected argument: $a")
        val name = a.removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next != null && !next.startsWith("--")) { values[name] = next; i += 2 } else { switches += name; i++ }
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
