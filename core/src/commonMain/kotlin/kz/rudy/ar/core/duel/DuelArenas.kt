package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The arenas the simulator ([DuelSim]) and the CLI tune the duel on: plane coordinates, metres, the spawn at the
 * origin, every one mirror-symmetric across `u = 0` so that swapping the marks swaps nothing else.
 */
object DuelArenas {
    /** How far apart [marks] puts the two fighters, centre to centre: a 10 cm gap, just out of one dash's reach (13.5 cm). */
    const val SEPARATION_M = 0.16f

    /** An empty 60 x 40 cm table. */
    val EMPTY: Level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f))

    /**
     * [EMPTY] with a cup (OBSTACLE, 9 cm tall, 8 cm across) and a book (PLATFORM, 3 cm, 12 x 8 cm), one on each side of
     * the line between the marks: the cup stops knockback (wall slams) and has to be walked round, the book is high
     * ground (a hit from it is stronger).
     */
    val CUP_AND_BOOK: Level = EMPTY.copy(
        objects = listOf(
            LevelObject("cup", "cup", ObjectKind.OBSTACLE, Polygon.circle(Vec2(0f, 0.11f), 0.04f), 0.09f),
            LevelObject("book", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2(0f, -0.11f), 0.12f, 0.08f), 0.03f),
        ),
    )

    /** A small 30 x 30 cm table: every edge is close. */
    val SMALL: Level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f))

    /** By name, in this order: "empty", "cup-and-book", "small". */
    val ALL: Map<String, Level> = linkedMapOf("empty" to EMPTY, "cup-and-book" to CUP_AND_BOOK, "small" to SMALL)

    /**
     * A 120 x 60 cm table, the size of a real one (T48): from the marks the nearest edge is 30 cm away, against 7 cm on
     * [SMALL]. A knockback that matters here is a one-hit ring-out there; the balance has to hold on both.
     */
    val LARGE: Level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 1.2f, 0.6f))

    /** The arenas the balance matrix measures ([DuelMatrix]), by name: [ALL] in its order, then "large" ([LARGE]). */
    val MATRIX: Map<String, Level> = linkedMapOf("empty" to EMPTY, "cup-and-book" to CUP_AND_BOOK, "small" to SMALL, "large" to LARGE)

    /**
     * The marks a match on [arena] starts on: [DuelArena.marks] for fighters of [radiusM], [separationM] apart.
     * Throws when the arena has no room for two fighters (none of [ALL]).
     */
    fun marks(
        arena: Level,
        radiusM: Float = SurfaceProfile.TABLE.radiusM,
        separationM: Float = SEPARATION_M,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): Pair<Vec2, Vec2> = requireNotNull(DuelArena.marks(arena, radiusM, separationM, physics)) {
        "the arena has no room for two fighters of radius $radiusM m"
    }
}

/** Which table of the balance matrix ([DuelMatrix]) a cell belongs to. */
@Serializable
enum class CellGroup {
    /** Ability against ability, each with its archetype stats ([defaultFor]). */
    ABILITY,

    /** One ability on both sides: a specialist (one stat at 8, the rest at 4) or the illegal 8/8/8/8, against 5/5/5/5. */
    STAT,

    /** Bundled characters, round robin. */
    BUNDLE,
}

/**
 * One cell of the balance matrix: [a] against [b] on the arena named [arena] (a key of [DuelArenas.MATRIX]), both played
 * by bots of [level]. The contestants' ids are [DuelMatrix.A] and [DuelMatrix.B]; [labelA] / [labelB] are what the
 * tables print.
 */
@Serializable
data class MatrixCell(
    val group: CellGroup,
    val arena: String,
    val level: BotLevel,
    val a: Contestant,
    val b: Contestant,
    val labelA: String,
    val labelB: String,
)

/**
 * The sums over some matches of one cell ([SimResult]s of contestants [DuelMatrix.A] and [DuelMatrix.B]).
 *
 * @property matchMs total match time (countdowns and pauses included).
 * @property timeouts rounds ended by the round clock, [ringOuts] by a ring-out (replays included in both).
 * @property hits Hit and Clash events, a clash once (as [SimResult.hits]).
 */
@Serializable
data class CellTally(
    val matches: Int = 0,
    val winsA: Int = 0,
    val winsB: Int = 0,
    val draws: Int = 0,
    val unfinished: Int = 0,
    val matchMs: Long = 0L,
    val rounds: Int = 0,
    val ringOuts: Int = 0,
    val timeouts: Int = 0,
    val replays: Int = 0,
    val hits: Int = 0,
    val wallSlams: Int = 0,
    val specials: Int = 0,
) {
    operator fun plus(o: CellTally) = CellTally(
        matches + o.matches, winsA + o.winsA, winsB + o.winsB, draws + o.draws, unfinished + o.unfinished,
        matchMs + o.matchMs, rounds + o.rounds, ringOuts + o.ringOuts, timeouts + o.timeouts, replays + o.replays,
        hits + o.hits, wallSlams + o.wallSlams, specials + o.specials,
    )

    /** The other side's view: a and b swapped. */
    fun swapped(): CellTally = copy(winsA = winsB, winsB = winsA)

    /** Share of the matches [DuelMatrix.A] won (draws and unfinished count as not won); 0 without matches. */
    val shareA: Float get() = if (matches == 0) 0f else winsA.toFloat() / matches

    /** Share of the matches [DuelMatrix.B] won. */
    val shareB: Float get() = if (matches == 0) 0f else winsB.toFloat() / matches

    /** Rounds ended by the clock / rounds ended at all; 0 when none ended. */
    val clockShare: Float get() = if (ringOuts + timeouts == 0) 0f else timeouts.toFloat() / (ringOuts + timeouts)

    /** Hits per ring-out ([SeriesResult.hitsPerRingOut]'s definition); [Float.POSITIVE_INFINITY] without a ring-out. */
    val hitsPerRingOut: Float get() = if (ringOuts == 0) Float.POSITIVE_INFINITY else hits.toFloat() / ringOuts

    /** Mean match length in seconds of match time; 0 without matches. */
    val meanMatchS: Float get() = if (matches == 0) 0f else matchMs.toFloat() / matches / 1000f

    companion object {
        /** One match: contestant [DuelMatrix.A] against [DuelMatrix.B]. */
        fun of(r: SimResult): CellTally = CellTally(
            matches = 1,
            winsA = if (r.finished && r.winner == DuelMatrix.A) 1 else 0,
            winsB = if (r.finished && r.winner == DuelMatrix.B) 1 else 0,
            draws = if (r.finished && r.winner == null) 1 else 0,
            unfinished = if (r.finished) 0 else 1,
            matchMs = r.matchMs,
            rounds = r.rounds,
            ringOuts = r.ringOuts,
            timeouts = r.timeouts,
            replays = r.replays,
            hits = r.hits,
            wallSlams = r.wallSlams,
            specials = r.specials,
        )
    }
}

/** A measured cell. */
@Serializable
data class CellResult(val cell: MatrixCell, val tally: CellTally)

/**
 * The thresholds of T48's acceptance table (ai/tasks/T48-cli-duel-sim-balance.md); the defaults are the brief's. A
 * test may widen them to check a small sample.
 */
@Serializable
data class Acceptance(
    /** A1: each ability's win share against the field (the other three), per arena and level. */
    val fieldMin: Float = 0.35f,
    val fieldMax: Float = 0.65f,
    /** A2: each side's win share in every ability-against-another-ability cell. */
    val cellMin: Float = 0.25f,
    val cellMax: Float = 0.75f,
    /** A3: the share of rounds ended by the clock, per ability pair (same-ability pairs included). */
    val clockMax: Float = 0.25f,
    /** A3 on [largeArena]. */
    val clockMaxLarge: Float = 0.40f,
    val largeArena: String = "large",
    /** A4: hits per ring-out over an arena's ability cells, per level. */
    val hitsMin: Float = 2f,
    val hitsMax: Float = 8f,
    /** A5: mean match length over an arena's ability cells, per level, seconds of match time. */
    val matchMinS: Float = 30f,
    val matchMaxS: Float = 120f,
    /** A6: a specialist's win share against 5/5/5/5 with the same ability. */
    val specialistMin: Float = 0.40f,
    val specialistMax: Float = 0.65f,
    /** A7: 8/8/8/8's win share against 5/5/5/5 with the same ability. */
    val boostedMin: Float = 0.65f,
)

/**
 * One row of the acceptance table: [pass] with the [measured] range, and every value outside the threshold in
 * [failures] ("arena LEVEL what value").
 */
@Serializable
data class Criterion(
    val id: String,
    val title: String,
    val threshold: String,
    val measured: String,
    val pass: Boolean,
    val failures: List<String> = emptyList(),
)

/**
 * The balance matrix of T48: which cells to play, how to play one (seeded bot-vs-bot matches, [DuelSim]), and the
 * arithmetic of the acceptance table on the results. Pure and deterministic; the CLI (`duel matrix`) spreads the
 * [play] calls over threads, a test runs a small sample in one.
 */
object DuelMatrix {
    /** The id of every cell's first contestant. */
    const val A = "a"

    /** The id of every cell's second contestant. */
    const val B = "b"

    /** A specialist: one stat at [SPECIALIST_HIGH], the other three at [SPECIALIST_LOW] (the budget, 20). */
    const val SPECIALIST_HIGH = 8
    const val SPECIALIST_LOW = 4

    const val BALANCED_LABEL = "5/5/5/5"
    const val BOOSTED_LABEL = "8/8/8/8"

    /** The illegal fighter of A7: more of everything than the budget allows. */
    val BOOSTED = FighterStats(8, 8, 8, 8)

    /** The specialist in [stat]. */
    fun specialist(stat: Stat): FighterStats = FighterStats.of { if (it == stat) SPECIALIST_HIGH else SPECIALIST_LOW }

    /** Every unordered ability pair, same-ability pairs included (10 per arena and level), archetype stats. */
    fun abilityCells(arenas: List<String>, levels: List<BotLevel>): List<MatrixCell> {
        val out = ArrayList<MatrixCell>()
        val abilities = Ability.entries
        for (arena in arenas) for (level in levels) for (i in abilities.indices) for (j in i until abilities.size) {
            val x = abilities[i]
            val y = abilities[j]
            out += MatrixCell(
                CellGroup.ABILITY, arena, level,
                Contestant(A, FighterStats.defaultFor(x), x), Contestant(B, FighterStats.defaultFor(y), y), x.name, y.name,
            )
        }
        return out
    }

    /**
     * For each ability: the four specialists ([specialist]) and [BOOSTED], each against 5/5/5/5 with the same ability
     * (20 per arena and level). Labels: the stat's name, or [BOOSTED_LABEL], against [BALANCED_LABEL].
     */
    fun statCells(arenas: List<String>, levels: List<BotLevel>): List<MatrixCell> {
        val out = ArrayList<MatrixCell>()
        for (arena in arenas) for (level in levels) for (x in Ability.entries) {
            for (stat in Stat.entries) {
                out += MatrixCell(CellGroup.STAT, arena, level, Contestant(A, specialist(stat), x), Contestant(B, FighterStats.BALANCED, x), stat.name, BALANCED_LABEL)
            }
            out += MatrixCell(CellGroup.STAT, arena, level, Contestant(A, BOOSTED, x), Contestant(B, FighterStats.BALANCED, x), BOOSTED_LABEL, BALANCED_LABEL)
        }
        return out
    }

    /** Every unordered pair of distinct [fighters] (label, stats and ability; the ids are replaced by [A] / [B]). */
    fun roundRobin(fighters: List<Pair<String, Contestant>>, arenas: List<String>, levels: List<BotLevel>): List<MatrixCell> {
        val out = ArrayList<MatrixCell>()
        for (arena in arenas) for (level in levels) for (i in fighters.indices) for (j in i + 1 until fighters.size) {
            val (la, ca) = fighters[i]
            val (lb, cb) = fighters[j]
            out += MatrixCell(CellGroup.BUNDLE, arena, level, ca.copy(id = A), cb.copy(id = B), la, lb)
        }
        return out
    }

    /**
     * The matches of [cell] with seeds [from] until [until] on [arena] (the level named by `cell.arena`), as
     * [DuelSim.series] plays them: both sides at `cell.level`, `a` on the first mark for an even seed and on the second
     * for an odd one (so any split of a seed range gives the same matches). [stepMs] is the frame length.
     */
    fun play(
        cell: MatrixCell,
        arena: Level,
        from: Long,
        until: Long,
        stepMs: Long = 33L,
        maxMatchMs: Long = 600_000L,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
    ): List<SimResult> {
        val marks = DuelArenas.marks(arena, physics = physics)
        val swapped = marks.second to marks.first
        val out = ArrayList<SimResult>((until - from).coerceAtLeast(0L).toInt())
        for (seed in from until until) {
            val m = if (seed.mod(2L) == 0L) marks else swapped
            out += DuelSim.match(cell.a, cell.b, arena, cell.level, cell.level, seed, stepMs, maxMatchMs, rules, params, physics, tuning, m).result
        }
        return out
    }

    /** The sums of [results]. */
    fun tally(results: Iterable<SimResult>): CellTally = results.fold(CellTally()) { t, r -> t + CellTally.of(r) }

    /**
     * Ability [x] against the field on [arena] at [level]: its cells against each other ability (archetype stats), from
     * [x]'s side (wins = [x]'s wins, in [CellTally.winsA]). Null when there is no such cell.
     */
    fun againstField(results: List<CellResult>, arena: String, level: BotLevel, x: Ability): CellTally? {
        var sum: CellTally? = null
        for (r in abilityOf(results, arena, level)) {
            val c = r.cell
            val mine = when {
                c.a.ability == c.b.ability -> null
                c.a.ability == x -> r.tally
                c.b.ability == x -> r.tally.swapped()
                else -> null
            } ?: continue
            sum = (sum ?: CellTally()) + mine
        }
        return sum
    }

    /** All ability cells of [arena] at [level], summed (A4, A5). Null without any. */
    fun arenaTotal(results: List<CellResult>, arena: String, level: BotLevel): CellTally? =
        abilityOf(results, arena, level).map { it.tally }.reduceOrNull { x, y -> x + y }

    private fun abilityOf(results: List<CellResult>, arena: String, level: BotLevel) =
        results.filter { it.cell.group == CellGroup.ABILITY && it.cell.arena == arena && it.cell.level == level }

    /**
     * The acceptance table A1..A8 on [results] with thresholds [t]. [stepsAgree]: whether every replayed match came out
     * the same at another frame length (A8; [stepsNote] says how many), null = not checked. A9 (the tests) is not the
     * matrix's to measure.
     */
    fun acceptance(results: List<CellResult>, stepsAgree: Boolean?, stepsNote: String = "", t: Acceptance = Acceptance()): List<Criterion> {
        val keys = results.filter { it.cell.group == CellGroup.ABILITY }.map { it.cell.arena to it.cell.level }.distinct()
        val out = ArrayList<Criterion>()

        // A1: each ability against the field, per arena and level.
        val field = ArrayList<Float>()
        val badField = ArrayList<String>()
        for ((arena, level) in keys) for (x in Ability.entries) {
            val s = againstField(results, arena, level, x)?.shareA ?: continue
            field += s
            if (s < t.fieldMin || s > t.fieldMax) badField += "$arena $level $x ${pct(s)}"
        }
        out += Criterion("A1", "each ability against the field", "${pct(t.fieldMin)}..${pct(t.fieldMax)}", range(field, ::pct), badField.isEmpty() && field.isNotEmpty(), badField)

        // A2: each side of every cell of two different abilities.
        val cells = ArrayList<Float>()
        val badCells = ArrayList<String>()
        for (r in results) {
            val c = r.cell
            if (c.group != CellGroup.ABILITY || c.a.ability == c.b.ability) continue
            for ((me, other, s) in listOf(Triple(c.labelA, c.labelB, r.tally.shareA), Triple(c.labelB, c.labelA, r.tally.shareB))) {
                cells += s
                if (s < t.cellMin || s > t.cellMax) badCells += "${c.arena} ${c.level} $me v $other ${pct(s)}"
            }
        }
        out += Criterion("A2", "every matchup, each side", "${pct(t.cellMin)}..${pct(t.cellMax)}", range(cells, ::pct), badCells.isEmpty() && cells.isNotEmpty(), badCells)

        // A3: the clock, per ability pair (same-ability pairs too).
        val clock = ArrayList<Float>()
        val badClock = ArrayList<String>()
        for (r in results) {
            val c = r.cell
            if (c.group != CellGroup.ABILITY) continue
            val s = r.tally.clockShare
            clock += s
            val max = if (c.arena == t.largeArena) t.clockMaxLarge else t.clockMax
            if (s > max) badClock += "${c.arena} ${c.level} ${c.labelA} v ${c.labelB} ${pct(s)}"
        }
        out += Criterion(
            "A3", "rounds ended by the clock, per ability pair", "<= ${pct(t.clockMax)} (${t.largeArena}: <= ${pct(t.clockMaxLarge)})",
            range(clock, ::pct), badClock.isEmpty() && clock.isNotEmpty(), badClock,
        )

        // A4, A5: over each arena's ability cells, per level.
        val hits = ArrayList<Float>()
        val length = ArrayList<Float>()
        val badHits = ArrayList<String>()
        val badLength = ArrayList<String>()
        for ((arena, level) in keys) {
            val sum = arenaTotal(results, arena, level) ?: continue
            hits += sum.hitsPerRingOut
            length += sum.meanMatchS
            if (!(sum.hitsPerRingOut >= t.hitsMin && sum.hitsPerRingOut <= t.hitsMax)) badHits += "$arena $level ${one(sum.hitsPerRingOut)}"
            if (sum.meanMatchS < t.matchMinS || sum.meanMatchS > t.matchMaxS) badLength += "$arena $level ${one(sum.meanMatchS)} s"
        }
        out += Criterion("A4", "hits per ring-out", "${one(t.hitsMin)}..${one(t.hitsMax)}", range(hits, ::one), badHits.isEmpty() && hits.isNotEmpty(), badHits)
        out += Criterion("A5", "mean match length", "${one(t.matchMinS)}..${one(t.matchMaxS)} s", range(length) { "${one(it)} s" }, badLength.isEmpty() && length.isNotEmpty(), badLength)

        // A6, A7: the stat cells.
        val spec = ArrayList<Float>()
        val boost = ArrayList<Float>()
        val badSpec = ArrayList<String>()
        val badBoost = ArrayList<String>()
        for (r in results) {
            val c = r.cell
            if (c.group != CellGroup.STAT) continue
            val s = r.tally.shareA
            val what = "${c.arena} ${c.level} ${c.a.ability} ${c.labelA} ${pct(s)}"
            if (c.a.stats == BOOSTED) {
                boost += s
                if (s < t.boostedMin) badBoost += what
            } else {
                spec += s
                if (s < t.specialistMin || s > t.specialistMax) badSpec += what
            }
        }
        out += Criterion("A6", "a specialist (8/4/4/4) against 5/5/5/5", "${pct(t.specialistMin)}..${pct(t.specialistMax)}", range(spec, ::pct), badSpec.isEmpty() && spec.isNotEmpty(), badSpec)
        out += Criterion("A7", "8/8/8/8 against 5/5/5/5", ">= ${pct(t.boostedMin)}", range(boost, ::pct), badBoost.isEmpty() && boost.isNotEmpty(), badBoost)

        // A8: the frame length.
        out += Criterion(
            "A8", "the same matches at another frame length", "identical",
            when (stepsAgree) {
                null -> "not checked"
                true -> "identical$stepsNote"
                false -> "DIFFERENT$stepsNote"
            },
            stepsAgree == true,
        )
        return out
    }

    /** [v] as a percentage with one decimal: 0.4567f -> "45.7 %". */
    fun pct(v: Float): String = "${one(v * 100f)} %"

    /** [v] with one decimal ("inf" for an infinite value, "nan" for NaN); the same text on every platform. */
    fun one(v: Float): String {
        if (v.isNaN()) return "nan"
        if (v.isInfinite()) return if (v > 0f) "inf" else "-inf"
        val tenths = (v.toDouble() * 10.0).roundToLong()
        val sign = if (tenths < 0L) "-" else ""
        val a = abs(tenths)
        return "$sign${a / 10L}.${a % 10L}"
    }

    private fun range(values: List<Float>, show: (Float) -> String): String =
        if (values.isEmpty()) "no cells" else "${show(values.min())}..${show(values.max())}"
}
