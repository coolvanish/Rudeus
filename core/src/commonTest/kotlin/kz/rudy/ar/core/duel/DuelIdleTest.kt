package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bot's side and the human's side of [sideMatch]. */
private const val BOT = "bot"
private const val HUMAN = "human"

/** I4's limit: a NORMAL or HARD bot never goes this long without closing in on its opponent or attacking it. */
private const val STAND_OFF_LIMIT_MS = 10_000L

/**
 * The bot with T60's fix on ([BotTuning.wholeDetour]). It is off by default: on, `normalIsBetweenEasyAndHard` fails its
 * floor in the cup-and-book ROLL cell (the brief's Result), and turning it on is a human's decision.
 */
private val FIXED = BotTuning(wholeDetour = true)

/**
 * One match of a bot ([BOT]) against a side the test plays ([HUMAN]), and what came of it.
 *
 * @property botRingOuts rounds the human lost by a ring-out; [humanRingOuts] the bot did; [timeouts] rounds to the clock.
 * @property attacks dashes and specials the bot started.
 * @property standOffMs the longest stretch of FIGHT in which the bot neither closed the distance by a radius nor
 *   attacked, the human on the table ([StandOff]).
 */
internal class SideResult(
    val winner: String?,
    val scoreBot: Int,
    val scoreHuman: Int,
    val botRingOuts: Int,
    val humanRingOuts: Int,
    val timeouts: Int,
    val attacks: Int,
    val standOffMs: Long,
    val matchMs: Long,
) {
    /** Won 2:0, both points by ring-outs of the human. */
    val cleanWin: Boolean get() = winner == BOT && scoreHuman == 0 && timeouts == 0 && humanRingOuts == 0

    /** Lost 0:2 with both points to the clock. */
    val lostOnTheClock: Boolean get() = winner == HUMAN && scoreBot == 0 && timeouts >= 2

    override fun toString() = "$scoreBot:$scoreHuman ${winner ?: "draw"} ring-outs $botRingOuts / $humanRingOuts clock $timeouts attacks $attacks stand-off ${standOffMs} ms in ${matchMs / 1000} s"
}

/**
 * Tracks I4 for one fighter ([me]) on every state of a match: the longest stretch of FIGHT in which it neither attacked
 * (a dash or a special) nor came one radius closer to the other than the farthest the other has been from it since the
 * stretch began (so walking back at a fighter that a hit knocked away is closing in). A stretch restarts at every
 * attack, every such closing in, every round, and while either is out (falling: not reachable on foot). The bot of the
 * T60 bug went to and fro 1 to 2 cm from the other, its distance changing by less than a radius: 59 s.
 */
internal class StandOff(private val me: String) {
    var longestMs = 0L
        private set
    private var startMs = -1L
    private var refM = 0f

    fun see(state: DuelMatchState, events: List<MatchEvent>, arena: Level) {
        val now = state.duel.timeMs
        val self = state.duel[me]
        val other = if (state.duel.a.id == me) state.duel.b else state.duel.a
        if (state.phase != MatchPhase.FIGHT || self == null || DuelPhysics.isOut(other, arena) || DuelPhysics.isOut(self, arena)) {
            startMs = -1L
            return
        }
        val d = self.body.state.pos.distanceTo(other.body.state.pos)
        val attacked = events.any { e ->
            e is MatchEvent.Fight && e.event.let { (it is DuelEvent.DashStarted && it.id == me) || (it is DuelEvent.SpecialStarted && it.id == me) }
        }
        if (startMs < 0L || attacked || d <= refM - self.body.state.radiusM) {
            startMs = now
            refM = d
            return
        }
        refM = maxOf(refM, d)
        longestMs = maxOf(longestMs, now - startMs)
    }
}

/**
 * A match on [arena] between a [level] bot of [botAbility] and a side of [humanAbility] that [human] plays (no input by
 * default: a fighter that stands still), both 5/5/5/5; the bot on the first mark when [botFirst]. 33 ms frames; the bot
 * with T60's fix on unless [tuning] says otherwise.
 */
internal fun sideMatch(
    arena: Level,
    level: BotLevel,
    botAbility: Ability,
    humanAbility: Ability,
    botFirst: Boolean,
    seed: Long,
    tuning: BotTuning = FIXED,
    human: (DuelMatchState) -> Map<String, DuelInput> = { emptyMap() },
): SideResult {
    val bot = Contestant(BOT, FighterStats.BALANCED, botAbility)
    val other = Contestant(HUMAN, FighterStats.BALANCED, humanAbility)
    val marks = DuelArenas.marks(arena)
    val start = if (botFirst) {
        RunnerState(DuelMatch.start(bot, other, arena, marks), botA = DuelBot.mind(level, seed))
    } else {
        RunnerState(DuelMatch.start(other, bot, arena, marks), botB = DuelBot.mind(level, seed))
    }
    var s = start
    var botRingOuts = 0
    var humanRingOuts = 0
    var timeouts = 0
    var attacks = 0
    val standOff = StandOff(BOT)
    while (s.match.phase != MatchPhase.RESULT && s.match.duel.timeMs < 600_000L) {
        val out = DuelRunner.step(s, arena, 33L, human(s.match), tuning = tuning)
        for (e in out.events) {
            when (e) {
                is MatchEvent.Point -> if (e.reason == PointReason.TIMEOUT) timeouts++ else if (e.winner == BOT) botRingOuts++ else if (e.winner == HUMAN) humanRingOuts++
                is MatchEvent.Fight -> when (val f = e.event) {
                    is DuelEvent.DashStarted -> if (f.id == BOT) attacks++
                    is DuelEvent.SpecialStarted -> if (f.id == BOT) attacks++
                    else -> Unit
                }
                else -> Unit
            }
        }
        s = out.state
        standOff.see(s.match, out.events, arena)
    }
    val m = s.match
    return SideResult(m.winner, m.scoreOf(BOT), m.scoreOf(HUMAN), botRingOuts, humanRingOuts, timeouts, attacks, standOff.longestMs, m.duel.timeMs)
}

/**
 * I3's target: every 500 ms of match time it walks to a new point one radius from its own mark (in a direction drawn
 * from [seed]).
 */
internal fun shuffler(seed: Long): (DuelMatchState) -> Map<String, DuelInput> {
    val rnd = Random(seed)
    var last = -1L
    return { s ->
        val slot = s.duel.timeMs / 500L
        if (s.phase == MatchPhase.FIGHT && slot != last) {
            last = slot
            val mark = if (s.duel.a.id == HUMAN) s.startA else s.startB
            val r = s.duel[HUMAN]!!.body.state.radiusM
            val angle = rnd.nextDouble() * 2.0 * kotlin.math.PI
            mapOf(HUMAN to DuelInput.MoveTo(mark + Vec2(cos(angle).toFloat(), sin(angle).toFloat()) * r))
        } else {
            emptyMap()
        }
    }
}

/** I1's sample: every bot ability against the same ability and against JUMP. */
private val PAIRS: List<Pair<Ability, Ability>> = Ability.entries.flatMap { x -> listOf(x to x, x to Ability.JUMP).distinct() }

/** The tuning arenas, by name. */
private val ARENAS = DuelArenas.MATRIX

/**
 * T60: a bot must beat a fighter that stands still (and one that only shuffles round its mark). Every check but the
 * switch's own runs with the fix on ([FIXED]).
 */
class DuelIdleTest {

    /** I1's matches for [level]: arena name -> results, every pair x both marks x seeds 1..3. */
    private fun idleSample(level: BotLevel): Map<String, List<Pair<String, SideResult>>> = ARENAS.mapValues { (_, arena) ->
        val out = ArrayList<Pair<String, SideResult>>()
        for ((x, y) in PAIRS) for (first in listOf(true, false)) for (seed in 1L..3L) {
            out += "$x v idle $y, ${if (first) "first" else "second"} mark, seed $seed" to sideMatch(arena, level, x, y, first, seed)
        }
        out
    }

    private fun table(level: BotLevel, sample: Map<String, List<Pair<String, SideResult>>>): String = sample.entries.joinToString("; ") { (name, rs) ->
        val r = rs.map { it.second }
        "$name $level: bot won ${r.count { it.winner == BOT }} of ${r.size} (${r.count { it.cleanWin }} 2:0 by ring-outs), " +
            "lost ${r.count { it.winner == HUMAN }} (${r.count { it.lostOnTheClock }} 0:2 on the clock), " +
            "mean ${r.sumOf { it.matchMs } / r.size / 100 / 10.0} s, longest stand-off ${r.maxOf { it.standOffMs }} ms"
    }

    @Test
    fun aBotBeatsAFighterThatStandsStill() {
        // I1: a fighter that sends no input, against NORMAL and HARD bots: the four tuning arenas, every bot ability
        // against the same ability and against JUMP, both marks, 3 seeds (168 matches per level). The bot wins every one
        // 2:0, both points by ring-outs. With the fix off (the T57 bot) both levels lose all 42 on cup-and-book 0:2 on the
        // clock without a dash: the bot goes to and fro between two detour waypoints beside the idle fighter (the brief's
        // Result).
        for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) {
            val sample = idleSample(level)
            println("aBotBeatsAFighterThatStandsStill: ${table(level, sample)}")
            for ((name, rs) in sample) for ((what, r) in rs) assertTrue(r.cleanWin, "$name $level $what: $r")
        }
    }

    @Test
    fun easyStillBeatsAnIdleFighter() {
        // I2: the same sample against EASY: at least 2 of 3 seeds per cell, and no match lost 0:2 on the clock.
        val sample = idleSample(BotLevel.EASY)
        println("easyStillBeatsAnIdleFighter: ${table(BotLevel.EASY, sample)}")
        for ((name, rs) in sample) {
            for ((what, r) in rs) assertTrue(!r.lostOnTheClock, "$name EASY $what: $r")
            for (cell in rs.chunked(3)) {
                assertTrue(cell.count { it.second.winner == BOT } >= 2, "$name EASY ${cell.first().first}: ${cell.map { it.second }}")
            }
        }
    }

    @Test
    fun aBotBeatsATargetThatShuffles() {
        // I3: a target that walks to a new point one radius from its mark every 500 ms (seeded): 9 matches per arena and
        // level (bot and target abilities and marks varied), NORMAL and HARD win at least 8.
        val lines = ArrayList<String>()
        for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) for ((name, arena) in ARENAS) {
            val rs = (0 until 9).map { k ->
                val x = Ability.entries[k % 4]
                val y = Ability.entries[(k + 1 + k / 4) % 4]
                "$x v shuffling $y seed $k" to sideMatch(arena, level, x, y, k % 2 == 0, 100L + k, human = shuffler(700L + k))
            }
            val won = rs.count { it.second.winner == BOT }
            lines += "$name $level $won of 9 (${rs.count { it.second.cleanWin }} 2:0 by ring-outs; longest stand-off ${rs.maxOf { it.second.standOffMs }} ms)"
            assertTrue(won >= 8, "$name $level: the bot won $won of 9: ${rs.filter { it.second.winner != BOT }}")
        }
        println("aBotBeatsATargetThatShuffles: ${lines.joinToString("; ")}")
    }

    @Test
    fun theFixSwitchedOffIsTheOldBot() {
        // `BotTuning.wholeDetour` off is the T57 bot, decision for decision: the 24 golden match setups
        // ([goldenMatch]: four arenas, every ability, four level pairs) with today's rules and tuning, the switch off, give
        // the very results, events, decisions and final states recorded on 920ed48 (before T60). PRE_T57_TUNING has it
        // off too, so `theNewRulesSwitchedOffAreThePreviousPhysics` still replays 207acc6 through the new code.
        // The old bot named by its switches (T61: impatience is a second behaviour on by default, so "the default with
        // wholeDetour off" is no longer the pre-T60 bot).
        val off = BotTuning(wholeDetour = false).let { it.copy(normal = it.normal.copy(impatientAfterMs = 0L), hard = it.hard.copy(impatientAfterMs = 0L)) }
        for (i in 0 until GOLDEN_MATCHES) {
            val (r, fp) = goldenMatch(i, DuelParams.DEFAULT, off)
            val (summary, expected) = PRE_T60_PRINTS[i]
            assertEquals(summary, "${r.winner}/${r.scoreA}/${r.scoreB}/${r.rounds}/${r.matchMs}/${r.ringOuts}/${r.timeouts}/${r.hits}/${r.wallSlams}/${r.specials}", "match $i")
            assertEquals(expected, fp, "match $i: the same events, decisions and final state")
        }
        assertFalse(PRE_T57_TUNING.wholeDetour)
        // And the switch is the fix: off, a NORMAL bot loses to a fighter standing on its mark of cup-and-book without a
        // single dash; on, it wins 2:0 by ring-outs.
        val lost = sideMatch(DuelArenas.CUP_AND_BOOK, BotLevel.NORMAL, Ability.JUMP, Ability.JUMP, true, 1L, tuning = off)
        assertTrue(lost.lostOnTheClock && lost.attacks == 0, "$lost")
        val won = sideMatch(DuelArenas.CUP_AND_BOOK, BotLevel.NORMAL, Ability.JUMP, Ability.JUMP, true, 1L, tuning = FIXED)
        assertTrue(won.cleanWin, "$won")
    }

    @Test
    fun noBotStandsOffForARound() {
        // I4: the longest stretch of FIGHT in which a NORMAL or HARD bot neither comes a radius closer nor attacks, the
        // other on the table, stays under 10 s: against a fighter that stands still (I1's sample) and between bots (the
        // matrix's equal-stats cells on the four arenas, 4 seeds each: 320 matches, both sides measured).
        var idleMax = 0L
        var idleWhere = ""
        for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) for ((name, rs) in idleSample(level)) for ((what, r) in rs) {
            if (r.standOffMs > idleMax) {
                idleMax = r.standOffMs
                idleWhere = "$name $level $what"
            }
        }
        var botMax = 0L
        var botWhere = ""
        val cells = DuelMatrix.equalCells(ARENAS.keys.toList(), listOf(BotLevel.NORMAL, BotLevel.HARD))
        for (c in cells) for (seed in 1L..4L) {
            val arena = ARENAS.getValue(c.arena)
            val marks = DuelArenas.marks(arena)
            val m = if (seed % 2L == 1L) marks else marks.second to marks.first
            var s = RunnerState(DuelMatch.start(c.a, c.b, arena, m), DuelBot.mind(c.level, 2L * seed), DuelBot.mind(c.level, 2L * seed + 1L))
            val a = StandOff(c.a.id)
            val b = StandOff(c.b.id)
            while (s.match.phase != MatchPhase.RESULT) {
                val out = DuelRunner.step(s, arena, 33L, tuning = FIXED)
                s = out.state
                a.see(s.match, out.events, arena)
                b.see(s.match, out.events, arena)
            }
            for ((side, so) in listOf(c.labelA to a, c.labelB to b)) {
                if (so.longestMs > botMax) {
                    botMax = so.longestMs
                    botWhere = "${c.arena} ${c.level} ${c.labelA} v ${c.labelB} seed $seed ($side)"
                }
            }
        }
        println("noBotStandsOffForARound: against a fighter that stands still $idleMax ms ($idleWhere); between bots $botMax ms ($botWhere)")
        assertTrue(idleMax < STAND_OFF_LIMIT_MS, "against a fighter that stands still: $idleMax ms, $idleWhere")
        assertTrue(botMax < STAND_OFF_LIMIT_MS, "between bots: $botMax ms, $botWhere")
    }
}

/**
 * What the T57 bot (920ed48, before T60) played in [goldenMatch] with `DuelParams.DEFAULT` and `BotTuning()`: the
 * result's numbers and the fingerprint ([DuelIdleTest.theFixSwitchedOffIsTheOldBot]).
 */
private val PRE_T60_PRINTS: List<Pair<String, Long>> = listOf(
    "b/1/2/3/40808/3/0/15/0/5" to 2143776594108503168L,
    "a/2/1/3/48968/3/0/18/8/6" to -105897823244764273L,
    "b/1/2/3/33040/3/0/10/0/4" to 4249649305524587591L,
    "a/2/1/3/101080/3/0/38/0/10" to -7338515750620106694L,
    "b/0/2/2/27616/2/0/11/0/2" to 623553659888911554L,
    "b/1/2/3/52744/3/0/25/3/6" to -6632976743386546430L,
    "a/2/1/3/29936/3/0/9/0/3" to -8264088736710522419L,
    "b/0/2/2/36240/2/0/15/0/4" to -8090752200506766762L,
    "a/2/1/3/37952/3/0/12/0/5" to -8388630919781355443L,
    "b/0/2/2/39808/2/0/15/4/2" to -2433896733872083054L,
    "b/0/2/2/20984/2/0/6/0/1" to 578363453411104607L,
    "a/2/0/2/37216/2/0/11/0/4" to 4364679026271495672L,
    "b/0/2/2/31704/2/0/14/0/1" to 4188612832840687403L,
    "b/0/2/2/41864/2/0/17/3/0" to 3016208883214277882L,
    "a/2/1/3/30640/3/0/8/0/3" to -6547085993226500058L,
    "b/0/2/2/63632/2/0/21/0/7" to -3985096976095182306L,
    "b/1/2/3/36456/3/0/13/0/3" to 1468722206277205627L,
    "b/0/2/2/43896/2/0/16/4/3" to 7319890126080570943L,
    "a/2/0/2/25776/2/0/8/0/3" to -6127879400989571310L,
    "b/1/2/3/57960/3/0/20/0/6" to -2929018388386138007L,
    "b/0/2/2/21776/2/0/7/0/2" to 1011057463722000075L,
    "b/0/2/2/27376/2/0/10/0/2" to -3962757788132248015L,
    "a/2/0/2/12928/2/0/3/0/2" to 8229038344571701012L,
    "b/1/2/3/49696/3/0/18/0/7" to -503426696238601224L,
)

// ---------------------------------------------------------------------------------------------------------------------
// T61: bot impatience. Random tables (R2), the stand-off spots of T60's Result (R3), and the rule's tests.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * R2's table [seed]: [DuelArenas.EMPTY]'s 60 x 40 cm with 1..3 objects, each a cup (radius 3..5 cm, 9 cm tall: an
 * OBSTACLE) or a book (8..14 x 6..10 cm, 3 cm: a PLATFORM), wholly on the table and apart from the others (bounding
 * circles), placed uniformly at random; a draw where [DuelArena.marks] finds no two marks is drawn again. Seeded: the
 * same table on every run.
 */
internal fun randomTable(seed: Long): Level {
    val rnd = Random(seed)
    fun between(lo: Float, hi: Float) = lo + (hi - lo) * rnd.nextFloat()
    while (true) {
        val wanted = 1 + rnd.nextInt(3)
        val objects = ArrayList<LevelObject>()
        val circles = ArrayList<Pair<Vec2, Float>>()
        var tries = 0
        while (objects.size < wanted && tries < 50) {
            tries++
            val id = "o${objects.size + 1}"
            val isCup = rnd.nextBoolean()
            val w = if (isCup) 0f else between(0.08f, 0.14f)
            val d = if (isCup) 0f else between(0.06f, 0.10f)
            val r = if (isCup) between(0.03f, 0.05f) else sqrt(w * w + d * d) / 2f
            val hu = if (isCup) r else w / 2f
            val hv = if (isCup) r else d / 2f
            val c = Vec2(between(-0.3f + hu, 0.3f - hu), between(-0.2f + hv, 0.2f - hv))
            if (circles.any { (o, or) -> o.distanceTo(c) < or + r }) continue
            objects += if (isCup) cup(id, c, r) else book(id, c, w, d, 0.03f)
            circles += c to r
        }
        val level = DuelArenas.EMPTY.copy(objects = objects)
        if (DuelArena.marks(level, R, DuelArenas.SEPARATION_M) != null) return level
    }
}

/** R2: the 60 random tables, seeds 1..60. */
internal val RANDOM_TABLES: List<Level> by lazy { (1L..60L).map { randomTable(it) } }

/** [x] metres with three decimals, the same text on every platform: -0.0814f -> "-0.081". */
private fun m3(x: Float): String {
    val k = (x.toDouble() * 1000.0).roundToLong()
    val a = abs(k)
    return "${if (k < 0L) "-" else ""}${a / 1000L}.${(a % 1000L).toString().padStart(3, '0')}"
}

/** [level]'s objects in a line: "cup r 4.0 cm at (0.206, 0.111); book 12.0 x 8.0 cm at (...)". */
internal fun layout(level: Level): String = level.objects.joinToString("; ") { o ->
    val c = o.footprint.centroid()
    val at = "(${m3(c.u)}, ${m3(c.v)})"
    if (o.kind == ObjectKind.OBSTACLE) {
        "cup r ${DuelMatrix.one(o.footprint.points.maxOf { it.distanceTo(c) } * 100f)} cm at $at"
    } else {
        val us = o.footprint.points.map { it.u }
        val vs = o.footprint.points.map { it.v }
        "book ${DuelMatrix.one((us.max() - us.min()) * 100f)} x ${DuelMatrix.one((vs.max() - vs.min()) * 100f)} cm at $at"
    }
}

/** One R2 match: its table (index into [RANDOM_TABLES]), who played, and the result. */
internal class TableMatch(val table: Int, val what: String, val result: SideResult)

/**
 * R2 on [tables]: NORMAL and HARD bots of JUMP, ROLL and FLY against an idle JUMP, both marks, seed 1000 + the table's
 * index; [tuning] for the bots. 12 matches a table.
 */
internal fun randomTableMatches(tuning: BotTuning, tables: IntRange = RANDOM_TABLES.indices): List<TableMatch> {
    val out = ArrayList<TableMatch>()
    for (i in tables) for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) for (x in listOf(Ability.JUMP, Ability.ROLL, Ability.FLY)) {
        for (first in listOf(true, false)) {
            val r = sideMatch(RANDOM_TABLES[i], level, x, Ability.JUMP, first, 1000L + i, tuning = tuning)
            out += TableMatch(i, "$level $x ${if (first) "first" else "second"} mark", r)
        }
    }
    return out
}

/** R2's summary of [ms], then every table with a match the bot did not win, with its layout. */
internal fun randomTableReport(ms: List<TableMatch>): String {
    val lost = ms.filter { it.result.winner != BOT }
    val byTable = lost.groupBy { it.table }
    val head = "${ms.size} matches: not won ${lost.size} (lost 0:2 on the clock ${lost.count { it.result.lostOnTheClock }}, " +
        "draws ${lost.count { it.result.winner == null }}), won 2:0 by ring-outs ${ms.count { it.result.cleanWin }}, " +
        "stand-offs of 10 s or more ${ms.count { it.result.standOffMs >= 10_000L }}, tables with a match not won ${byTable.size}"
    val tables = byTable.entries.joinToString("\n") { (i, l) ->
        "  table ${i + 1} [${layout(RANDOM_TABLES[i])}]: " +
            l.joinToString(", ") { "${it.what} ${it.result.scoreBot}:${it.result.scoreHuman} attacks ${it.result.attacks}" }
    }
    return if (tables.isEmpty()) head else "$head\n$tables"
}

/** R3: T60's three stand-off spots on cup-and-book; the human walks to its spot every 480 ms of FIGHT and stays. */
internal val STAND_OFF_SPOTS: List<Pair<String, Vec2>> = listOf(
    "behind the cup" to Vec2(0f, 0.165f), "on the book" to Vec2(0f, -0.11f), "between the cup and the book" to Vec2(0f, 0f),
)

/** R3 for [tuning]: per spot and level, 8 matches (bot abilities and marks varied). */
internal fun spotMatches(tuning: BotTuning): List<Triple<String, BotLevel, List<SideResult>>> = STAND_OFF_SPOTS.flatMap { (what, p) ->
    listOf(BotLevel.NORMAL, BotLevel.HARD).map { level ->
        Triple(what, level, (0 until 8).map { k ->
            sideMatch(DuelArenas.CUP_AND_BOOK, level, Ability.entries[k % 4], Ability.entries[(k / 4 + k) % 4], k % 2 == 0, 40L + k, tuning = tuning) { s ->
                if (s.phase == MatchPhase.FIGHT && (s.duel.timeMs / 48L) % 10L == 0L) mapOf(HUMAN to DuelInput.MoveTo(p)) else emptyMap()
            }
        })
    }
}

internal fun spotReport(ms: List<Triple<String, BotLevel, List<SideResult>>>): String = ms.joinToString("; ") { (what, level, rs) ->
    "$what $level: won ${rs.count { it.winner == BOT }} of 8 (by ring-outs ${rs.count { it.cleanWin }}), longest stand-off ${rs.maxOf { it.standOffMs }} ms"
}

/** The default tuning with T61's impatience off (NORMAL and HARD at 0): the bot before T61. */
internal val PATIENT: BotTuning = BotTuning().let { it.copy(normal = it.normal.copy(impatientAfterMs = 0L), hard = it.hard.copy(impatientAfterMs = 0L)) }

/**
 * The default tuning with T61's impatience on at NORMAL and HARD, 16 s (the value measured; the default is 0 until a
 * human decides, the brief's Result): every T61 check but the off position runs with it.
 */
internal val IMPATIENT: BotTuning = BotTuning().let { it.copy(normal = it.normal.copy(impatientAfterMs = 16_000L), hard = it.hard.copy(impatientAfterMs = 16_000L)) }

/** T61: a NORMAL or HARD bot that has not attacked for a while attacks from where it stands. */
class DuelImpatienceTest {

    /** A FIGHT 10 s into round 1 on [arena]: "me" (slot a, [me]) at [pa], an idle JUMP "foe" at [pb], both standing. */
    private fun fight(arena: Level, me: Ability, pa: Vec2, pb: Vec2): DuelMatchState {
        val s = DuelMatch.start(Contestant("me", FighterStats.BALANCED, me), Contestant("foe", FighterStats.BALANCED, Ability.JUMP), arena, DuelArenas.marks(arena))
        fun place(f: DuelFighter, p: Vec2) = f.copy(body = f.body.copy(state = f.body.state.copy(pos = p, h = kz.rudy.ar.core.physics.Physics.groundHeight(arena, p, 0f) ?: 0f)))
        return s.copy(phase = MatchPhase.FIGHT, phaseLeftMs = 50_000L, duel = s.duel.copy(a = place(s.duel.a, pa), b = place(s.duel.b, pb), timeMs = 10_000L))
    }

    /** A fresh mind of [level] that has gone [calmMs] without attacking, as of now (10 s into round 1). */
    private fun calm(level: BotLevel, calmMs: Long) = DuelBot.mind(level, 1L).copy(calmMs = calmMs, calmAtMs = 10_000L, calmRound = 1)

    @Test
    fun anImpatientBotAttacksFromWhereItStands() {
        val arena = DuelArenas.EMPTY
        val t = IMPATIENT
        for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) {
            val wait = t.skill(level).impatientAfterMs
            // Beside the opponent, in a dash's range, 90° off the push (toward the bottom edge): the plan walks round it;
            // after `impatientAfterMs` without an attack the bot dashes at it from where it stands.
            val beside = fight(arena, Ability.FLY, Vec2(0f, 0f), Vec2(0.07f, 0f))
            val patient = DuelBot.decide(calm(level, wait - 48L), "me", beside, arena, tuning = t).second
            assertTrue(patient !is DuelInput.Dash, "$level, patient: $patient")
            val urged = DuelBot.decide(calm(level, wait), "me", beside, arena, tuning = t)
            assertTrue(urged.second is DuelInput.Dash && (urged.second as DuelInput.Dash).dir!!.u > 0.9f, "$level, impatient: ${urged.second}")
            assertEquals(0L to 10_000L, urged.first.calmMs to urged.first.calmAtMs, "$level: the attack restarts its clock")
            // Out of range: it walks at the opponent, not to the spot behind it.
            val far = fight(arena, Ability.FLY, Vec2(-0.1f, 0f), Vec2(0.15f, 0.05f))
            val walk = DuelBot.decide(calm(level, wait), "me", far, arena, tuning = t).second
            assertEquals(DuelInput.MoveTo(Vec2(0.15f, 0.05f)), walk, "$level")
            assertTrue(DuelBot.decide(calm(level, 0L), "me", far, arena, tuning = t).second != walk, "$level: the plan goes behind it")
            // An opponent on the book, out of a dash's height reach: no dash that could only miss; the plan, unchanged.
            val onBook = fight(DuelArenas.CUP_AND_BOOK, Ability.FLY, Vec2(0f, -0.02f), Vec2(0f, -0.09f))
            assertEquals(0.03f, onBook.duel.b.body.state.h)
            assertEquals(
                DuelBot.decide(calm(level, 0L), "me", onBook, DuelArenas.CUP_AND_BOOK, tuning = t).second,
                DuelBot.decide(calm(level, wait), "me", onBook, DuelArenas.CUP_AND_BOOK, tuning = t).second, "$level",
            )
        }
        // EASY has no impatience (it dashes at any alignment anyway); its mind keeps no clock.
        assertEquals(0L, t.easy.impatientAfterMs)
        val easy = DuelBot.decide(DuelBot.mind(BotLevel.EASY, 1L), "me", fight(arena, Ability.FLY, Vec2(-0.1f, 0f), Vec2(0.15f, 0.05f)), arena, tuning = t).first
        assertEquals(Triple(0L, -1L, 0), Triple(easy.calmMs, easy.calmAtMs, easy.calmRound))
    }

    @Test
    fun impatienceRestartsWithEveryAttackAndEveryRound() {
        // NORMAL JUMP against a fighter standing on its mark of cup-and-book (T60's case: the plan never attacks). Its
        // clock counts FIGHT time between its own decisions: the first attack of every round comes once it has gone
        // `impatientAfterMs` without one (within a decision's wait), and every attack restarts it.
        val arena = DuelArenas.CUP_AND_BOOK
        val t = IMPATIENT
        val wait = t.normal.impatientAfterMs
        val think = t.normal.let { it.thinkMs + t.tickMs * it.thinkJitterTicks }
        val starts = ArrayList<Long>()
        fun run(lossAtMs: Long?): List<Triple<Long, Int, BotMind>> {
            val start = RunnerState(
                DuelMatch.start(Contestant("bot", FighterStats.BALANCED, Ability.JUMP), Contestant("idle", FighterStats.BALANCED, Ability.JUMP), arena, DuelArenas.marks(arena)),
                botA = DuelBot.mind(BotLevel.NORMAL, 1L),
            )
            var s = start
            val attacks = ArrayList<Triple<Long, Int, BotMind>>()
            var lost = false
            while (s.match.phase != MatchPhase.RESULT) {
                if (lossAtMs != null && !lost && s.match.duel.timeMs >= lossAtMs) {
                    lost = true
                    // 5 s of tracking loss in the middle of a stretch without an attack: no clock runs, the bot's neither.
                    repeat(150) { s = DuelRunner.step(s, arena, 33L, tracking = false, tuning = t).state }
                }
                val before = s
                val out = DuelRunner.step(s, arena, 33L, tuning = t)
                if (lossAtMs == null) for (e in out.events) if (e is MatchEvent.RoundStarted) starts += e.atMs
                for (d in out.decisions) {
                    if (d.input is DuelInput.Dash || d.input == DuelInput.Special) attacks += Triple(d.atMs, before.match.round, out.state.botA!!)
                }
                if (s.match.phase != MatchPhase.FIGHT && out.state.match.phase != MatchPhase.FIGHT) assertEquals(s.botA, out.state.botA, "no decision outside FIGHT")
                s = out.state
            }
            return attacks
        }
        val attacks = run(null)
        assertTrue(attacks.isNotEmpty() && starts.size >= 2)
        for (a in attacks) assertEquals(0L to a.first, a.third.calmMs to a.third.calmAtMs, "an attack at ${a.first} restarts the clock")
        for ((round, at) in starts.withIndex()) {
            val first = attacks.firstOrNull { it.second == round + 1 } ?: continue
            assertTrue(first.first - at in wait..wait + 2 * think, "round ${round + 1}: first attack ${first.first - at} ms in")
        }
        assertEquals(1, attacks.first().third.calmRound)
        assertTrue(attacks.any { it.second == 2 && it.third.calmRound == 2 }, "round 2 has its own clock")
        // A tracking loss 4 s into the first round changes nothing in match time.
        assertEquals(attacks.map { it.first to it.second }, run(starts.first() + 4_000L).map { it.first to it.second })
        println("impatienceRestartsWithEveryAttackAndEveryRound: rounds start at $starts; attacks at ${attacks.map { "${it.first} (round ${it.second})" }}")
    }

    @Test
    fun impatienceOffIsTodaysBot() {
        // `impatientAfterMs` 0 is the bot before T61, decision for decision: the 24 golden match setups at today's rules
        // with impatience off give the prints recorded on 920ed48 (the T57 bot; T60's switch is off by default), and its
        // minds keep no clock. PRE_T57_TUNING sets 0 explicitly, so the goldens replay 207acc6 through the new code.
        for (i in 0 until GOLDEN_MATCHES) {
            val (r, fp) = goldenMatch(i, DuelParams.DEFAULT, PATIENT)
            val (summary, expected) = PRE_T60_PRINTS[i]
            assertEquals(summary, "${r.winner}/${r.scoreA}/${r.scoreB}/${r.rounds}/${r.matchMs}/${r.ringOuts}/${r.timeouts}/${r.hits}/${r.wallSlams}/${r.specials}", "match $i")
            assertEquals(expected, fp, "match $i: the same events, decisions and final state")
        }
        for (level in BotLevel.entries) assertEquals(0L, PRE_T57_TUNING.skill(level).impatientAfterMs, "$level")
        val minds = (0L until 3L).map { seed ->
            var s = RunnerState(DuelMatch.start(Contestant("a", FighterStats.BALANCED, Ability.JUMP), Contestant("b", FighterStats.BALANCED, Ability.ROLL), DuelArenas.EMPTY, DuelArenas.marks(DuelArenas.EMPTY)), DuelBot.mind(BotLevel.NORMAL, seed), DuelBot.mind(BotLevel.HARD, seed + 7L))
            while (s.match.phase != MatchPhase.RESULT) s = DuelRunner.step(s, DuelArenas.EMPTY, 33L, tuning = PATIENT).state
            s.botA!! to s.botB!!
        }
        for ((a, b) in minds) for (m in listOf(a, b)) assertEquals(Triple(0L, -1L, 0), Triple(m.calmMs, m.calmAtMs, m.calmRound))
    }

    @Test
    fun anImpatientBotBeatsAFighterThatStandsStill() {
        // R1: T60's I1 sample (four tuning arenas, every bot ability against the same ability and against JUMP, both
        // marks, 3 seeds: 168 per level) with T60's switch off and impatience on (16 s): every match 2:0 by ring-outs.
        // Without impatience (today's default) both levels lose all 42 on cup-and-book 0:2 on the clock.
        for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) {
            val lines = ArrayList<String>()
            for ((name, arena) in ARENAS) {
                val rs = PAIRS.flatMap { (x, y) -> listOf(true, false).flatMap { first -> (1L..3L).map { seed -> "$x v idle $y first=$first seed $seed" to sideMatch(arena, level, x, y, first, seed, tuning = IMPATIENT) } } }
                lines += "$name ${rs.count { it.second.cleanWin }} of ${rs.size} 2:0 by ring-outs, mean ${rs.sumOf { it.second.matchMs } / rs.size / 100 / 10.0} s"
                for ((what, r) in rs) assertTrue(r.cleanWin, "$name $level $what: $r")
            }
            println("anImpatientBotBeatsAFighterThatStandsStill: $level ${lines.joinToString("; ")}")
        }
    }

    @Test
    fun aBotBeatsAnIdleFighterOnRandomTables() {
        // R2 on the first 15 of the 60 random tables (180 matches: NORMAL and HARD bots of JUMP, ROLL and FLY against an
        // idle JUMP, both marks), impatience on (16 s). Without it 28 of these 180 are lost (tables 3, 5, 9 and 13); with it
        // 3 (tables 5 and 9, the Result says why). R2's bar is 1 % (1 of 180), not met: this pins today's 3, so that a
        // change that makes it worse turns red. The full 720: `theRandomTablesAndTheStandOffSpots`.
        val ms = randomTableMatches(IMPATIENT, 0 until 15)
        val report = randomTableReport(ms)
        println("aBotBeatsAnIdleFighterOnRandomTables: $report")
        assertTrue(ms.count { it.result.winner != BOT } <= 3, report)
        assertEquals(28, randomTableMatches(PATIENT, 0 until 15).count { it.result.winner != BOT }, "the sample's stand-offs without impatience")
    }

    @Test
    fun impatienceDoesNotCarryTheBotOverAnEdge() {
        // A NORMAL and a HARD bot that grow impatient after 48 ms (so every one of its decisions is impatient) against a
        // fighter that stands still in a corner or by an edge, on the four tuning arenas and ten random tables, 30 s: it
        // never rings itself out and never sends a walk target off the table; the opponent goes off again and again.
        val rules = DuelRules(pointsToWin = 1000, maxRounds = 1000)
        val eager = BotTuning().let { it.copy(normal = it.normal.copy(impatientAfterMs = 48L), hard = it.hard.copy(impatientAfterMs = 48L)) }
        var outs = 0
        var played = 0
        val arenas = ARENAS.values.toList() + RANDOM_TABLES.take(10)
        for ((k, arena) in arenas.withIndex()) {
            val pts = arena.boundary!!.points
            val (u0, u1, v0, v1) = listOf(pts.minOf { it.u }, pts.maxOf { it.u }, pts.minOf { it.v }, pts.maxOf { it.v })
            val spots = listOf(Vec2(u1 - 0.05f, v1 - 0.05f), Vec2(u0 + 0.05f, v0 + 0.05f), Vec2(0f, v0 + 0.05f), Vec2(u1 - 0.05f, 0f))
            for ((j, spot) in spots.withIndex()) for (level in listOf(BotLevel.NORMAL, BotLevel.HARD)) {
                val marks = DuelArenas.marks(arena).first to spot
                if (DuelArena.startProblem(arena, marks, R) != null) continue
                val bot = Contestant("bot", FighterStats.BALANCED, Ability.entries[(k + j) % 4])
                val start = RunnerState(DuelMatch.start(bot, Contestant("idle", FighterStats.BALANCED, Ability.JUMP), arena, marks, rules), botA = DuelBot.mind(level, 10L * k + j))
                var s = start
                while (s.match.duel.timeMs < 30_000L) {
                    val out = DuelRunner.step(s, arena, 33L, rules = rules, tuning = eager)
                    for (e in out.events) if (e is MatchEvent.Fight && e.event is DuelEvent.RingOut) {
                        val id = (e.event as DuelEvent.RingOut).id
                        assertTrue(id != "bot", "arena $k, spot $j, $level: the impatient bot left the table at ${e.event.atMs} ms")
                        outs++
                    }
                    for (d in out.decisions) {
                        val input = d.input
                        if (input is DuelInput.MoveTo) assertTrue(DuelArena.insideDistance(arena.boundary!!, input.target) >= 2f * R - 1e-4f, "arena $k, spot $j, $level: target ${input.target}")
                    }
                    s = out.state
                }
                played++
            }
        }
        println("impatienceDoesNotCarryTheBotOverAnEdge: $played matches of 30 s, the still fighter pushed off $outs times, the bot never")
        assertTrue(played >= 80 && outs > played, "$played matches, $outs ring-outs")
    }

    @Test
    fun theRandomTablesAndTheStandOffSpots() {
        // R2's generator: 60 seeded tables, each with 1..3 objects and two marks; the same table every time.
        assertEquals(60, RANDOM_TABLES.size)
        for ((i, t) in RANDOM_TABLES.withIndex()) {
            assertTrue(t.objects.size in 1..3, "table ${i + 1}: ${t.objects.size} objects")
            assertEquals(null, DuelArena.startProblem(t, DuelArenas.marks(t), R), "table ${i + 1}")
        }
        assertEquals(RANDOM_TABLES[6], randomTable(7L))
        // The measurement (printed): R2's 720 matches and R3's spots, impatience off (before T61) and on (the defaults).
        println("theRandomTablesAndTheStandOffSpots: R2 before T61: ${randomTableReport(randomTableMatches(PATIENT))}")
        println("theRandomTablesAndTheStandOffSpots: R2 with impatience (16 s): ${randomTableReport(randomTableMatches(IMPATIENT))}")
        val before = spotMatches(PATIENT)
        val after = spotMatches(IMPATIENT)
        println("theRandomTablesAndTheStandOffSpots: R3 before T61: ${spotReport(before)}")
        println("theRandomTablesAndTheStandOffSpots: R3 with impatience (16 s): ${spotReport(after)}")
        // R3: no spot gets worse (as many matches won, as many by ring-outs).
        for ((b, a) in before.zip(after)) {
            assertTrue(a.third.count { it.winner == BOT } >= b.third.count { it.winner == BOT }, "${a.first} ${a.second}: won fewer")
            assertTrue(a.third.count { it.cleanWin } >= b.third.count { it.cleanWin }, "${a.first} ${a.second}: fewer by ring-outs")
        }
    }
}
