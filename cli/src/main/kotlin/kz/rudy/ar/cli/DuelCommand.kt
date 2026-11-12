package kz.rudy.ar.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.bundle.Bundle
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.domain.fighter
import kz.rudy.ar.core.duel.BotLevel
import kz.rudy.ar.core.duel.BotTuning
import kz.rudy.ar.core.duel.CellGroup
import kz.rudy.ar.core.duel.CellResult
import kz.rudy.ar.core.duel.CellTally
import kz.rudy.ar.core.duel.Contestant
import kz.rudy.ar.core.duel.Criterion
import kz.rudy.ar.core.duel.DuelArenas
import kz.rudy.ar.core.duel.DuelBot
import kz.rudy.ar.core.duel.DuelEvent
import kz.rudy.ar.core.duel.DuelMatch
import kz.rudy.ar.core.duel.DuelMatrix
import kz.rudy.ar.core.duel.DuelMatrix.one
import kz.rudy.ar.core.duel.DuelMatrix.pct
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelRules
import kz.rudy.ar.core.duel.DuelRunner
import kz.rudy.ar.core.duel.DuelSim
import kz.rudy.ar.core.duel.MatchEvent
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.core.duel.MatrixCell
import kz.rudy.ar.core.duel.RunnerState
import kz.rudy.ar.core.duel.SimResult
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/*
 * duel match  --a <fighter> --b <fighter> [--arena empty|cup|small|large] [--level-a easy|normal|hard] [--level-b ...]
 *             [--seed 1] [--step-ms 33] [--params file.json] [--rules file.json] [--tuning file.json] [--bundle docs/bundled]
 * duel series (the same) --n 200
 * duel matrix [--level normal,hard] [--arenas all|empty,cup,small,large] [--n 200] [--seed 1] [--groups ability,equal,stat]
 *             [--params file.json] [--rules file.json] [--tuning file.json] [--bundle docs/bundled] [--out build/duel]
 *             [--threads <cores>] [--check-n <n>] [--check-step-ms 16] [--step-ms 33] [--gate]
 *
 * T48: the duel's balance by measurement. Every match is a seeded bot-vs-bot match of :core (DuelSim through
 * DuelMatrix.play); nothing here calls a model or reads a key. <fighter> is an ability with its archetype stats ("fly"),
 * an ability with stats power,speed,guard,agility ("roll:7,6,5,2"), or "@<ref>": a bundled character
 * (<bundle>/<ref>/sheet.json: sheet.fighter, sheet.ability).
 *
 * matrix plays every cell (ability pairs with archetype stats; T57: ability pairs all at 5/5/5/5, the "equal" group; per
 * ability the specialists 8/4/4/4 and the illegal 8/8/8/8 against 5/5/5/5; the bundled round robin with --bundle) n
 * times on each arena at each level, spread over --threads, replays the first --check-n matches of every cell at
 * --check-step-ms (B9: they must be identical), prints the acceptance table of ai/tasks/T57 (B1..B9) and writes
 * <out>/matrix.md and <out>/matrix.json (default build/duel; never
 * runs/, which belongs to MetricsLog). The same arguments print the same table (the timing goes to stderr). Exit 0
 * whatever the verdicts; with --gate, 1 unless every criterion passes.
 * --params / --rules / --tuning are JSON files of DuelParams / DuelRules / BotTuning (missing fields = the defaults).
 */
private const val DUEL_DEFAULT_OUT = "build/duel"
private const val DUEL_DEFAULT_BUNDLE = "docs/bundled"
private const val CHUNK = 10L

private val duelJson = Json { prettyPrint = true; encodeDefaults = true }
private val duelInputJson = Json { ignoreUnknownKeys = false }

internal fun duel(args: List<String>): Int {
    val sub = args.firstOrNull() ?: return usage("duel needs one of: match, series, matrix")
    val flags = parseFlags(args.drop(1))
    return when (sub) {
        "match" -> duelMatch(flags)
        "series" -> duelSeries(flags)
        "matrix" -> duelMatrix(flags)
        else -> usage("unknown duel command: $sub (match, series, matrix; `tune` is cut, see T48)")
    }
}

/** The numbers a run plays with: defaults, or the JSON files given. */
private class Setup(val params: DuelParams, val rules: DuelRules, val tuning: BotTuning, val sources: String)

private fun setup(flags: Flags): Setup {
    fun <T> load(name: String, read: (String) -> T, default: T): Pair<T, String?> {
        val path = flags.get(name) ?: return default to null
        val f = File(path)
        if (!f.isFile) throw CliError("--$name: file not found: $path")
        val value = try {
            read(f.readText())
        } catch (e: IllegalArgumentException) {
            throw CliError("--$name $path: ${e.message}")
        }
        return value to "$name ${f.path}"
    }
    val (params, p) = load("params", { duelInputJson.decodeFromString(DuelParams.serializer(), it) }, DuelParams.DEFAULT)
    val (rules, r) = load("rules", { duelInputJson.decodeFromString(DuelRules.serializer(), it) }, DuelRules())
    val (tuning, t) = load("tuning", { duelInputJson.decodeFromString(BotTuning.serializer(), it) }, BotTuning())
    try {
        tuning.requireFits(params)
    } catch (e: IllegalArgumentException) {
        throw CliError(e.message ?: "tuning does not fit the params")
    }
    val sources = listOfNotNull(p, r, t).joinToString(", ").ifEmpty { "the defaults" }
    return Setup(params, rules, tuning, sources)
}

private fun arenaByName(name: String): Pair<String, Level> {
    val key = if (name == "cup") "cup-and-book" else name
    val level = DuelArenas.MATRIX[key] ?: throw CliError("unknown arena '$name' (${DuelArenas.MATRIX.keys.joinToString()}, or cup)")
    return key to level
}

private fun levelByName(name: String): BotLevel =
    BotLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: throw CliError("unknown bot level '$name' (easy, normal, hard)")

private fun Flags.long(name: String): Long? = get(name)?.let { it.toLongOrNull() ?: throw CliError("--$name must be an integer") }

/** `fly`, `roll:7,6,5,2` or `@ref` (a bundled character), as contestant [id]. */
private fun parseFighter(text: String, id: String, bundleDir: File): Pair<String, Contestant> {
    if (text.startsWith("@")) {
        val ref = text.drop(1).also(::requireSafeRef)
        val f = File(bundleDir, Bundle.sheetPath(ref))
        if (!f.isFile) throw CliError("$text: no ${f.path}")
        val sheet = try {
            Bundle.decodeSheet(f.readText())
        } catch (e: Exception) {
            throw CliError("${f.path}: not a sheet (${e.message})")
        }
        return ref to Contestant(id, sheet.fighter, sheet.ability)
    }
    val name = text.substringBefore(':')
    val ability = Ability.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw CliError("unknown fighter '$text': an ability (jump, fly, roll, hide), ability:p,s,g,a or @ref")
    if (':' !in text) return text to Contestant(id, FighterStats.defaultFor(ability), ability)
    val nums = text.substringAfter(':').split(',').map { it.trim().toIntOrNull() ?: throw CliError("'$text': stats must be four integers") }
    if (nums.size != 4) throw CliError("'$text': stats are power,speed,guard,agility")
    val stats = FighterStats(nums[0], nums[1], nums[2], nums[3])
    if (!stats.isBalanced) System.err.println("warning: $text is not a legal fighter (each 1..10, sum ${FighterStats.BUDGET}); played as given")
    return text to Contestant(id, stats, ability)
}

// ---------------------------------------------------------------- match, series

private fun duelMatch(flags: Flags): Int {
    val s = setup(flags)
    val bundleDir = File(flags.get("bundle") ?: DUEL_DEFAULT_BUNDLE)
    val (la, a) = parseFighter(flags.require("a"), DuelMatrix.A, bundleDir)
    val (lb, b) = parseFighter(flags.require("b"), DuelMatrix.B, bundleDir)
    val (arenaName, arena) = arenaByName(flags.get("arena") ?: "empty")
    val levelA = levelByName(flags.get("level-a") ?: "normal")
    val levelB = levelByName(flags.get("level-b") ?: "normal")
    val seed = flags.long("seed") ?: 1L
    val stepMs = flags.long("step-ms") ?: 33L
    if (stepMs <= 0L) throw CliError("--step-ms must be > 0")
    println("duel match: a = $la ${a.ability} ${statsText(a.stats)} ($levelA), b = $lb ${b.ability} ${statsText(b.stats)} ($levelB), $arenaName, seed $seed, $stepMs ms frames, ${s.sources}")
    val marks = DuelArenas.marks(arena)
    var state = RunnerState(DuelMatch.start(a, b, arena, marks, s.rules, s.params), DuelBot.mind(levelA, 2L * seed), DuelBot.mind(levelB, 2L * seed + 1L))
    var hits = 0
    val maxMs = 600_000L
    while (state.match.phase != MatchPhase.RESULT && state.match.duel.timeMs < maxMs) {
        val out = DuelRunner.step(state, arena, minOf(stepMs, maxMs - state.match.duel.timeMs), rules = s.rules, params = s.params, tuning = s.tuning)
        for (e in out.events) {
            when (e) {
                is MatchEvent.Fight -> if (e.event is DuelEvent.Hit || e.event is DuelEvent.Clash) hits++
                is MatchEvent.Point -> {
                    println("  round ${state.match.round}: ${e.reason} at ${one(e.atMs / 1000f)} s, point to ${e.winner ?: "nobody (replay)"}, $hits hits")
                    hits = 0
                }
                is MatchEvent.MatchOver -> println("  match over at ${one(e.atMs / 1000f)} s: ${e.winner ?: "draw"}")
                else -> Unit
            }
        }
        state = out.state
    }
    // The same match as DuelSim plays it (a seed is a match).
    val r = DuelSim.run(a, b, arena, levelA, levelB, seed, stepMs, maxMs, s.rules, s.params, tuning = s.tuning)
    println("result: $r")
    return 0
}

private fun duelSeries(flags: Flags): Int {
    val s = setup(flags)
    val bundleDir = File(flags.get("bundle") ?: DUEL_DEFAULT_BUNDLE)
    val (la, a) = parseFighter(flags.require("a"), DuelMatrix.A, bundleDir)
    val (lb, b) = parseFighter(flags.require("b"), DuelMatrix.B, bundleDir)
    val (arenaName, arena) = arenaByName(flags.get("arena") ?: "empty")
    val levelA = levelByName(flags.get("level-a") ?: "normal")
    val levelB = levelByName(flags.get("level-b") ?: "normal")
    val seed = flags.long("seed") ?: 1L
    val n = flags.int("n") ?: 200
    val stepMs = flags.long("step-ms") ?: 33L
    if (n <= 0 || stepMs <= 0L) throw CliError("--n and --step-ms must be > 0")
    val started = System.nanoTime()
    val r = DuelSim.series(a, b, arena, levelA, levelB, n, seed, stepMs, rules = s.rules, params = s.params, tuning = s.tuning)
    val sec = (System.nanoTime() - started) / 1e9
    println("duel series: a = $la ${a.ability} ${statsText(a.stats)} ($levelA) v b = $lb ${b.ability} ${statsText(b.stats)} ($levelB), $arenaName, $n matches from seed $seed, ${s.sources}")
    println(
        "  a ${pct(r.shareA)}, b ${pct(r.shareB)}, draws ${r.draws}, unfinished ${r.unfinished}; clock ${pct(r.timeoutShare)} of ${r.ringOuts + r.timeouts} rounds; " +
            "mean ${one(r.meanMatchMs / 1000f)} s; hits per ring-out ${one(r.hitsPerRingOut)}; wall slams ${r.wallSlams}; specials ${r.specials}",
    )
    System.err.println("  ${"%.1f".format(sec)} s, ${(n / sec).toLong()} matches/s")
    return 0
}

// ---------------------------------------------------------------- matrix

/** What `duel matrix` writes to matrix.json. */
@Serializable
private data class MatrixReport(
    val command: String,
    val n: Int,
    val seed: Long,
    val stepMs: Long,
    val checkStepMs: Long,
    val checkN: Int,
    val sources: String,
    val params: DuelParams,
    val rules: DuelRules,
    val tuning: BotTuning,
    val acceptance: List<Criterion>,
    val cells: List<CellResult>,
)

private fun duelMatrix(flags: Flags): Int {
    val s = setup(flags)
    val n = flags.int("n") ?: 200
    val seed = flags.long("seed") ?: 1L
    val stepMs = flags.long("step-ms") ?: 33L
    val checkStepMs = flags.long("check-step-ms") ?: 16L
    val checkN = (flags.int("check-n") ?: n).coerceIn(0, n)
    val threads = (flags.int("threads") ?: Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    if (n <= 0 || stepMs <= 0L || checkStepMs <= 0L) throw CliError("--n, --step-ms and --check-step-ms must be > 0")
    val levels = (flags.get("level") ?: "normal,hard").split(',').map { levelByName(it.trim()) }.distinct()
    val arenas = (flags.get("arenas") ?: "all").let { a ->
        if (a == "all") DuelArenas.MATRIX.keys.toList() else a.split(',').map { arenaByName(it.trim()).first }.distinct()
    }
    val bundleDir = flags.get("bundle")?.let(::File)
    val groups = (flags.get("groups") ?: "ability,equal,stat").split(',').map { it.trim().lowercase() }.toSet()
    val outDir = File(flags.get("out") ?: DUEL_DEFAULT_OUT)
    refuseRunsDir(outDir)

    val cells = ArrayList<MatrixCell>()
    if ("equal" in groups) cells += DuelMatrix.equalCells(arenas, levels)
    if ("ability" in groups) cells += DuelMatrix.abilityCells(arenas, levels)
    if ("stat" in groups) cells += DuelMatrix.statCells(arenas, levels)
    var bundled: List<Pair<String, Contestant>> = emptyList()
    if (bundleDir != null) {
        bundled = bundleFighters(bundleDir)
        // Characters with the same stats and ability are the same fighter: play each distinct one once.
        val distinct = bundled.distinctBy { it.second.stats to it.second.ability }.map { (_, c) -> fighterLabel(c) to c }
        cells += DuelMatrix.roundRobin(distinct, arenas, levels)
    }
    if (cells.isEmpty()) throw CliError("no cells: --groups ability,equal,stat and/or --bundle <dir>")

    val command = "duel matrix --n $n --seed $seed --level ${levels.joinToString(",") { it.name.lowercase() }} --arenas ${arenas.joinToString(",")} " +
        "--groups ${groups.sorted().joinToString(",")}" + (bundleDir?.let { " --bundle ${it.path}" } ?: "") +
        " --step-ms $stepMs --check-n $checkN --check-step-ms $checkStepMs"
    val total = cells.size.toLong() * n + cells.size.toLong() * checkN
    System.err.println("$command: ${cells.size} cells, ${cells.size.toLong() * n} matches + $checkN per cell at $checkStepMs ms, $threads threads, ${s.sources}")

    val started = System.nanoTime()
    val pool = Executors.newFixedThreadPool(threads)
    val done = AtomicInteger(0)
    val tick = maxOf(1L, total / 20L)
    fun submit(cell: MatrixCell, from: Long, until: Long, step: Long): Future<List<SimResult>> = pool.submit(
        Callable {
            val arena = DuelArenas.MATRIX.getValue(cell.arena)
            // first = the cell's first seed: the marks swap exactly as `duel series --seed $seed` swaps them.
            DuelMatrix.play(cell, arena, from, until, step, rules = s.rules, params = s.params, tuning = s.tuning, first = seed).also {
                val before = done.getAndAdd(it.size).toLong()
                val after = before + it.size
                if (after / tick != before / tick) {
                    System.err.println("  ${after * 100L / total} % (${"%.0f".format((System.nanoTime() - started) / 1e9)} s)")
                }
            }
        },
    )
    val main = cells.map { cell -> (seed until seed + n step CHUNK).map { from -> submit(cell, from, minOf(from + CHUNK, seed + n), stepMs) } }
    val check = cells.map { cell -> (seed until seed + checkN step CHUNK).map { from -> submit(cell, from, minOf(from + CHUNK, seed + checkN), checkStepMs) } }
    val results = ArrayList<CellResult>()
    var replayed = 0
    var differ = 0
    try {
        for ((i, cell) in cells.withIndex()) {
            val matches = main[i].flatMap { it.get() }
            val again = check[i].flatMap { it.get() }
            for ((k, r) in again.withIndex()) {
                replayed++
                if (r != matches[k]) {
                    differ++
                    if (differ <= 5) System.err.println("B9: ${cell.arena} ${cell.level} ${cell.labelA} v ${cell.labelB} seed ${seed + k}: $stepMs ms ${matches[k]} vs $checkStepMs ms $r")
                }
            }
            results += CellResult(cell, DuelMatrix.tally(matches))
        }
    } finally {
        pool.shutdownNow()
    }
    val sec = (System.nanoTime() - started) / 1e9

    val stepsAgree = if (replayed == 0) null else differ == 0
    val note = if (replayed == 0) "" else " ($replayed matches replayed at $checkStepMs ms, $differ different)"
    val acceptance = DuelMatrix.acceptance(results, stepsAgree, note)
    val md = matrixMarkdown(command, s, n, seed, stepMs, checkN, checkStepMs, levels, arenas, results, acceptance, bundled)
    print(md)
    outDir.mkdirs()
    File(outDir, "matrix.md").writeText(md)
    val report = MatrixReport(command, n, seed, stepMs, checkStepMs, checkN, s.sources, s.params, s.rules, s.tuning, acceptance, results)
    File(outDir, "matrix.json").writeText(duelJson.encodeToString(MatrixReport.serializer(), report) + "\n")
    System.err.println(
        "wrote ${File(outDir, "matrix.md").path} and matrix.json: ${"%.1f".format(sec)} s for $total matches on $threads threads " +
            "(${(total / sec).toLong()} matches/s)",
    )
    // A FAIL is a finding, not an error; --gate makes it the exit code (1).
    return if (!flags.has("gate") || acceptance.all { it.pass }) 0 else 1
}

/**
 * runs/ belongs to MetricsLog: refuse any --out inside it (its first path component, relative to the working directory,
 * the repo root under `:cli:run`, is `runs`: `runs`, `runs/duel`, `./runs/x`, or the same as an absolute path).
 */
private fun refuseRunsDir(outDir: File) {
    val cwd = File("").absoluteFile.toPath().normalize()
    val out = outDir.absoluteFile.toPath().normalize()
    val rel = if (out.startsWith(cwd)) cwd.relativize(out) else null
    if (rel != null && rel.nameCount > 0 && rel.getName(0).toString() == "runs") {
        throw CliError("--out $outDir: runs/ belongs to MetricsLog; write to build/duel")
    }
}

private fun bundleFighters(dir: File): List<Pair<String, Contestant>> {
    if (!dir.isDirectory) throw CliError("bundle dir not found: $dir")
    val refs = dir.listFiles { f -> f.isDirectory && File(f, "sheet.json").isFile }.orEmpty().map { it.name }.sorted()
    if (refs.isEmpty()) throw CliError("no <ref>/sheet.json in $dir")
    return refs.map { ref ->
        val sheet = try {
            Bundle.decodeSheet(File(dir, Bundle.sheetPath(ref)).readText())
        } catch (e: Exception) {
            throw CliError("$dir/$ref/sheet.json: not a sheet (${e.message})")
        }
        ref to Contestant(ref, sheet.fighter, sheet.ability)
    }
}

private fun statsText(s: FighterStats) = "${s.power}/${s.speed}/${s.guard}/${s.agility}"

private fun groupTitle(group: CellGroup) = if (group == CellGroup.EQUAL) "equal stats, 5/5/5/5" else "archetype stats"

private fun fighterLabel(c: Contestant) = "${c.ability} ${statsText(c.stats)}"

private fun matrixMarkdown(
    command: String,
    s: Setup,
    n: Int,
    seed: Long,
    stepMs: Long,
    checkN: Int,
    checkStepMs: Long,
    levels: List<BotLevel>,
    arenas: List<String>,
    results: List<CellResult>,
    acceptance: List<Criterion>,
    bundled: List<Pair<String, Contestant>>,
): String = buildString {
    appendLine("# Duel balance matrix")
    appendLine()
    appendLine("`$command`")
    appendLine()
    appendLine(
        "Numbers: ${s.sources}. $n matches per cell (seeds $seed..${seed + n - 1}, marks swapped every other match from the first, as duel series does), both sides " +
            "bots of the same level, $stepMs ms frames; B9 replays $checkN per cell at $checkStepMs ms. Clock = share of the " +
            "rounds ended by the round clock; hits = Hit + Clash events per ring-out (all rounds' hits); s = mean match length. " +
            "\"equal\": both sides 5/5/5/5 (B1); \"archetype\": each ability's stats for a character without stats (B2).",
    )
    appendLine()
    appendLine("## Acceptance")
    appendLine()
    appendLine("| # | Criterion | Threshold | Measured | Verdict |")
    appendLine("|---|---|---|---|---|")
    for (c in acceptance) appendLine("| ${c.id} | ${c.title} | ${c.threshold} | ${c.measured} | ${if (c.pass) "PASS" else "FAIL"} (${c.failures.size} out) |")
    appendLine("| B10 | every core/duel test green | - | not measured here: `scripts/verify.sh` (B9's other half, the rules switched off, is the test `theNewRulesSwitchedOffAreThePreviousPhysics`) | - |")
    appendLine()
    for (c in acceptance.filter { it.failures.isNotEmpty() }) {
        val shown = c.failures.take(16)
        appendLine("- ${c.id} out of ${c.threshold}: ${shown.joinToString("; ")}${if (c.failures.size > shown.size) "; and ${c.failures.size - shown.size} more" else ""}")
    }
    if (acceptance.any { it.failures.isNotEmpty() }) appendLine()

    for (group in DuelMatrix.PAIR_GROUPS) {
        if (results.none { it.cell.group == group }) continue
        appendLine("## Overview (${groupTitle(group)})")
        appendLine()
        appendLine("| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (arena in arenas) for (level in levels) {
            val sum = DuelMatrix.arenaTotal(results, arena, level, group) ?: continue
            val pairs = results.filter { it.cell.group == group && it.cell.arena == arena && it.cell.level == level }
            val worst = pairs.maxByOrNull { it.tally.clockShare }
            val most = pairs.maxByOrNull { it.tally.hitsPerRingOut }
            val shortest = pairs.minOf { it.tally.meanMatchS }
            val longest = pairs.maxOf { it.tally.meanMatchS }
            appendLine(
                "| $arena | $level | ${pct(sum.clockShare)} | ${worst?.let { "${it.cell.labelA} v ${it.cell.labelB} ${pct(it.tally.clockShare)}" } ?: "-"} | " +
                    "${one(sum.meanMatchS)} | ${one(shortest)} / ${one(longest)} | ${one(sum.hitsPerRingOut)} | " +
                    "${most?.let { "${it.cell.labelA} v ${it.cell.labelB} ${one(it.tally.hitsPerRingOut)}" } ?: "-"} | ${one(sum.ringOuts.toFloat() / sum.matches)} | " +
                    "${one(sum.wallSlams.toFloat() / sum.matches)} | ${one(sum.specials.toFloat() / sum.matches)} |",
            )
        }
        appendLine()
    }

    val abilities = Ability.entries
    for (group in DuelMatrix.PAIR_GROUPS) for (arena in arenas) for (level in levels) {
        val cells = results.filter { it.cell.group == group && it.cell.arena == arena && it.cell.level == level }
        if (cells.isEmpty()) continue
        fun cell(x: Ability, y: Ability): Pair<CellResult, Boolean>? {
            cells.firstOrNull { it.cell.a.ability == x && it.cell.b.ability == y }?.let { return it to false }
            return cells.firstOrNull { it.cell.a.ability == y && it.cell.b.ability == x }?.let { it to true }
        }
        appendLine("## Abilities (${groupTitle(group)}): $arena, $level v $level")
        appendLine()
        appendLine("Row's win share against the column, then clock / s / hits per ring-out of the pair.")
        appendLine()
        appendLine("| row v col | ${abilities.joinToString(" | ")} | v field |")
        appendLine("|---|${abilities.joinToString("") { "---|" }}---|")
        for (x in abilities) {
            val row = abilities.map { y ->
                val (r, flipped) = cell(x, y) ?: return@map "-"
                val t = if (flipped) r.tally.swapped() else r.tally
                "${pct(t.shareA)} / ${pct(t.clockShare)} / ${one(t.meanMatchS)} s / ${one(t.hitsPerRingOut)}"
            }
            val f = DuelMatrix.againstField(results, arena, level, x, group)
            val field = f?.let { "${pct(it.shareA)} / ${pct(it.clockShare)} / ${one(it.meanMatchS)} s / ${one(it.hitsPerRingOut)}" } ?: "-"
            val stats = if (group == CellGroup.EQUAL) FighterStats.BALANCED else FighterStats.defaultFor(x)
            appendLine("| **$x** ${statsText(stats)} | ${row.joinToString(" | ")} | $field |")
        }
        appendLine()
    }

    for (arena in arenas) for (level in levels) {
        val cells = results.filter { it.cell.group == CellGroup.STAT && it.cell.arena == arena && it.cell.level == level }
        if (cells.isEmpty()) continue
        appendLine("## Stats: $arena, $level v $level")
        appendLine()
        appendLine("The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.")
        appendLine()
        val cols = Stat.entries.map { it.name } + DuelMatrix.BOOSTED_LABEL
        appendLine("| ability | ${cols.joinToString(" | ") { if (it == DuelMatrix.BOOSTED_LABEL) it else "$it 8" }} |")
        appendLine("|---|${cols.joinToString("") { "---|" }}")
        for (x in abilities) {
            val row = cols.map { col ->
                cells.firstOrNull { it.cell.a.ability == x && it.cell.labelA == col }?.let { "${pct(it.tally.shareA)} / ${pct(it.tally.clockShare)}" } ?: "-"
            }
            appendLine("| $x | ${row.joinToString(" | ")} |")
        }
        appendLine()
    }

    if (bundled.isNotEmpty()) {
        for (arena in arenas) for (level in levels) {
            val cells = results.filter { it.cell.group == CellGroup.BUNDLE && it.cell.arena == arena && it.cell.level == level }
            if (cells.isEmpty()) continue
            appendLine("## Bundled round robin: $arena, $level v $level")
            appendLine()
            appendLine("${bundled.size} characters, ${bundled.map { fighterLabel(it.second) }.distinct().size} distinct fighters; each one's win share against the others.")
            appendLine()
            appendLine("| character | fighter | v the others | clock |")
            appendLine("|---|---|---|---|")
            val rows = bundled.map { (ref, c) ->
                val label = fighterLabel(c)
                var sum: CellTally? = null
                for (r in cells) {
                    val mine = when (label) {
                        r.cell.labelA -> r.tally
                        r.cell.labelB -> r.tally.swapped()
                        else -> null
                    } ?: continue
                    sum = (sum ?: CellTally()) + mine
                }
                Triple(ref, label, sum)
            }.sortedByDescending { it.third?.shareA ?: 0f }
            for ((ref, label, sum) in rows) appendLine("| $ref | $label | ${sum?.let { pct(it.shareA) } ?: "-"} | ${sum?.let { pct(it.clockShare) } ?: "-"} |")
            appendLine()
        }
    }
}
