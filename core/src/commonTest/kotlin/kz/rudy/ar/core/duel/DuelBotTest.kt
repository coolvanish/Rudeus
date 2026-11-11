package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stat lines the bot tests play with: the all-rounder and the four archetypes. */
private val STAT_LINES = listOf(FighterStats.BALANCED) + Ability.entries.map { FighterStats.defaultFor(it) }

/** A far corner of [arena]'s boundary box, 5 cm in from both edges (free on every arena of [DuelArenas.ALL]). */
private fun farCorner(arena: Level): Vec2 {
    val pts = arena.boundary!!.points
    return Vec2(pts.maxOf { it.u } - 0.05f, pts.maxOf { it.v } - 0.05f)
}

class DuelBotTest {

    @Test
    fun aBotAloneNeverLeavesTheTable() {
        // Each level against a dummy that never moves, in a far corner: 60 s of match time x 20 seeds on the three arenas,
        // first to 1000 so the rounds keep coming. The bot pushes the dummy off again and again, and never falls itself.
        val rules = DuelRules(pointsToWin = 1000, maxRounds = 1000)
        val margin = TUNING_DEFAULT.edgeMarginRadii * R
        for ((name, arena) in DuelArenas.ALL) {
            val marks = DuelArenas.marks(arena).first to farCorner(arena)
            for (level in BotLevel.entries) {
                var pushedOff = 0
                for (seed in 0L until 20L) {
                    val bot = Contestant("bot", STAT_LINES[(seed % STAT_LINES.size).toInt()], Ability.entries[(seed % 4).toInt()])
                    val dummy = Contestant("dummy", FighterStats.BALANCED, Ability.JUMP)
                    val start = RunnerState(DuelMatch.start(bot, dummy, arena, marks, rules), botA = DuelBot.mind(level, seed))
                    val t = runRunner(start, arena, maxMs = 60_000L, rules = rules)
                    val rings = t.events.filterIsInstance<MatchEvent.Fight>().map { it.event }.filterIsInstance<DuelEvent.RingOut>()
                    assertTrue(rings.none { it.id == "bot" }, "$name, $level, seed $seed: the bot left the table at ${rings.firstOrNull { it.id == "bot" }?.atMs} ms")
                    pushedOff += rings.size
                    for (d in t.decisions) assertSafe(d, arena, margin, "$name, $level, seed $seed")
                }
                println("aBotAloneNeverLeavesTheTable: $name $level pushed the dummy off $pushedOff times in 20 x 60 s")
                assertTrue(pushedOff > 0, "$name $level never pushed the dummy off")
            }
        }
    }

    @Test
    fun botsNeverEmitNonFiniteOrOutsideTargets() {
        // 216 bot-vs-bot matches (every arena, every level pair, every ability on each side, varied stats; 20 s rounds so
        // the rounds and their resets come often): every decision is checked.
        val rules = DuelRules(roundMs = 20_000L)
        val margin = TUNING_DEFAULT.edgeMarginRadii * R
        val arenas = DuelArenas.ALL.entries.toList()
        var decisions = 0
        val kinds = HashMap<String, Int>()
        for (i in 0 until 216) {
            val (name, arena) = arenas[i % 3]
            val la = BotLevel.entries[(i / 3) % 3]
            val lb = BotLevel.entries[(i / 9) % 3]
            val a = Contestant("a", STAT_LINES[i % STAT_LINES.size], Ability.entries[(i / 27) % 4])
            val b = Contestant("b", STAT_LINES[(i / 5) % STAT_LINES.size], Ability.entries[(i / 54 + i) % 4])
            val t = runRunner(botMatch(a, b, arena, la, lb, i.toLong(), rules), arena, rules = rules)
            assertEquals(MatchPhase.RESULT, t.last.match.phase)
            for (d in t.decisions) {
                assertSafe(d, arena, margin, "match $i ($name, $la v $lb)")
                val level = if (d.id == "a") la else lb
                if (level == BotLevel.EASY) assertTrue(d.input != DuelInput.Special, "EASY used its special: match $i at ${d.atMs} ms")
                kinds[d.input::class.simpleName ?: "?"] = (kinds[d.input::class.simpleName ?: "?"] ?: 0) + 1
            }
            decisions += t.decisions.size
        }
        println("botsNeverEmitNonFiniteOrOutsideTargets: $decisions decisions checked: $kinds")
        assertTrue(kinds.keys.containsAll(listOf("MoveTo", "Dash", "Special")), "$kinds")
    }

    @Test
    fun sidesAreFair() {
        // NORMAL vs NORMAL, identical contestants (only their seeds differ), 400 matches swapping marks every other one.
        val a = Contestant("a", FighterStats.BALANCED, Ability.JUMP)
        val b = Contestant("b", FighterStats.BALANCED, Ability.JUMP)
        val s = DuelSim.series(a, b, DuelArenas.EMPTY, BotLevel.NORMAL, BotLevel.NORMAL, 400, 5_000L)
        println("sidesAreFair: $s")
        assertEquals(0, s.unfinished)
        assertTrue(s.shareA <= 0.6f && s.shareB <= 0.6f, "a ${s.shareA}, b ${s.shareB}")
    }

    @Test
    fun theBotAnswersNoneOutsideTheFight() {
        val arena = DuelArenas.EMPTY
        val start = botMatch(Contestant("a", FighterStats.BALANCED, Ability.JUMP), Contestant("b", FighterStats.BALANCED, Ability.ROLL), arena, BotLevel.HARD, BotLevel.HARD, 1L)
        val t = runRunner(start, arena)
        // Every decision lies inside a fight: from a RoundStarted to the Point that ended it.
        val starts = t.events.filterIsInstance<MatchEvent.RoundStarted>().map { it.atMs }
        val ends = t.events.filterIsInstance<MatchEvent.Point>().map { it.atMs }
        assertEquals(starts.size, ends.size)
        val fights = starts.zip(ends)
        assertTrue(t.decisions.size > 100)
        for (d in t.decisions) assertTrue(fights.any { (s, e) -> d.atMs >= s && d.atMs < e }, "a decision at ${d.atMs} ms, outside the fights $fights")
        val countdown = start.match
        val mind = start.botA!!
        assertEquals(mind to DuelInput.None, DuelBot.decide(mind, "a", countdown, arena))
        assertEquals(mind to DuelInput.None, DuelBot.decide(mind, "nobody", countdown, arena))
    }

    private fun assertSafe(d: BotDecision, arena: Level, margin: Float, what: String) {
        val boundary = arena.boundary!!
        when (val input = d.input) {
            is DuelInput.MoveTo -> {
                val p = input.target
                assertTrue(p.u.isFinite() && p.v.isFinite(), "$what: non-finite target $p at ${d.atMs} ms")
                assertTrue(boundary.contains(p) && DuelArena.insideDistance(boundary, p) >= margin - 1e-4f, "$what: target $p is ${DuelArena.insideDistance(boundary, p)} m inside at ${d.atMs} ms")
                for (o in arena.objects) {
                    if (o.kind == ObjectKind.OBSTACLE || o.kind == ObjectKind.CLIFF) {
                        assertTrue(!o.footprint.contains(p), "$what: target $p in ${o.id} at ${d.atMs} ms")
                    }
                }
            }
            is DuelInput.Dash -> {
                val dir = input.dir
                assertTrue(dir != null && dir.u.isFinite() && dir.v.isFinite() && kotlin.math.abs(dir.length - 1f) < 1e-3f, "$what: dash $dir at ${d.atMs} ms")
            }
            DuelInput.Special, DuelInput.Jump -> Unit
            DuelInput.None -> error("$what: a None was reported as a decision")
        }
    }
}

private val TUNING_DEFAULT = BotTuning()
