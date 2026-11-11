package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.physics.Physics
import kotlin.math.abs
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

    /**
     * [strong] against [weak] on [arena], equal stats and ability on both sides: [perAbility] matches with each ability
     * (its archetype stats), marks swapped every other match. Returns the share [strong] won, and prints the line;
     * [byAbility] gets each ability's share.
     */
    private fun share(
        strong: BotLevel,
        weak: BotLevel,
        arena: Level,
        name: String,
        perAbility: Int,
        byAbility: MutableMap<Ability, Float> = HashMap(),
    ): Float {
        var wins = 0
        var losses = 0
        var clock = 0
        var ended = 0
        val lines = ArrayList<String>()
        for (ab in Ability.entries) {
            val st = FighterStats.defaultFor(ab)
            val s = DuelSim.series(Contestant("s", st, ab), Contestant("w", st, ab), arena, strong, weak, perAbility, 31_000L + ab.ordinal * 1_000L)
            assertEquals(0, s.unfinished)
            wins += s.winsA
            losses += s.winsB
            clock += s.timeouts
            ended += s.ringOuts + s.timeouts
            lines += "$ab ${s.winsA}/${s.matches}"
            byAbility[ab] = s.shareA
        }
        val n = perAbility * Ability.entries.size
        println("strength $name $strong v $weak: ${wins * 100f / n} % won, ${losses * 100f / n} % lost, clock ${clock * 100 / maxOf(1, ended)} % ($lines)")
        return wins.toFloat() / n
    }

    @Test
    fun hardBeatsEasy() {
        for (name in listOf("empty", "cup-and-book")) {
            val won = share(BotLevel.HARD, BotLevel.EASY, DuelArenas.ALL.getValue(name), name, 50)
            assertTrue(won >= 0.70f, "$name: HARD won $won of 200 against EASY")
        }
    }

    @Test
    fun normalIsBetweenEasyAndHard() {
        for (name in listOf("empty", "cup-and-book")) {
            val arena = DuelArenas.ALL.getValue(name)
            val overEasy = share(BotLevel.NORMAL, BotLevel.EASY, arena, name, 50)
            assertTrue(overEasy >= 0.60f, "$name: NORMAL won $overEasy against EASY")
            val perAbility = HashMap<Ability, Float>()
            val hardOver = share(BotLevel.HARD, BotLevel.NORMAL, arena, name, 50, perAbility)
            assertTrue(hardOver >= 0.55f, "$name: HARD won $hardOver against NORMAL")
            // And with every ability on its own (T48 review: a HARD JUMP losing to a NORMAL one hid in the aggregate).
            // Measured 66..90 % per ability on these seeds; the floor keeps a 5-point margin under the lowest.
            for ((ab, s) in perAbility) assertTrue(s >= 0.60f, "$name: HARD $ab won only $s against NORMAL $ab ($perAbility)")
        }
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
        // The first decision: the first tick in FIGHT. The 3000 ms countdown is no multiple of 48: 3024. A countdown that
        // ends on a tick (2976 = 62 x 48): that tick, the call reaching it having already turned the phase to FIGHT. No
        // countdown: the start is COUNTDOWN with 0 ms left, the bot waits, so 48.
        assertEquals(3024L, t.decisions.first().atMs)
        for ((countdown, first) in listOf(2976L to 2976L, 0L to 48L)) {
            val rules = DuelRules(countdownMs = countdown)
            val c = botMatch(Contestant("a", FighterStats.BALANCED, Ability.JUMP), Contestant("b", FighterStats.BALANCED, Ability.ROLL), arena, BotLevel.HARD, BotLevel.HARD, 1L, rules)
            assertEquals(first, runRunner(c, arena, maxMs = countdown + 500L, rules = rules).decisions.first().atMs, "countdown $countdown")
        }
        val countdown = start.match
        val mind = start.botA!!
        assertEquals(mind to DuelInput.None, DuelBot.decide(mind, "a", countdown, arena))
        assertEquals(mind to DuelInput.None, DuelBot.decide(mind, "nobody", countdown, arena))
    }

    // -- the levels' traits, one decision each ---------------------------------------------------------------------

    /** A FIGHT 10 s into a match on [arena]: [me] (slot a) at [pa], the opponent at [pb], both standing; [edit] tunes them. */
    private fun fight(
        arena: Level,
        me: Ability,
        pa: Vec2,
        pb: Vec2,
        foe: Ability = Ability.JUMP,
        edit: (a: DuelFighter, b: DuelFighter) -> Pair<DuelFighter, DuelFighter> = { a, b -> a to b },
    ): DuelMatchState {
        val s = DuelMatch.start(Contestant("me", FighterStats.BALANCED, me), Contestant("foe", FighterStats.BALANCED, foe), arena, DuelArenas.marks(arena))
        fun place(f: DuelFighter, p: Vec2) = f.copy(body = f.body.copy(state = f.body.state.copy(pos = p, h = Physics.groundHeight(arena, p, 0f) ?: 0f)))
        val (a, b) = edit(place(s.duel.a, pa), place(s.duel.b, pb))
        return s.copy(phase = MatchPhase.FIGHT, phaseLeftMs = 50_000L, duel = s.duel.copy(a = a, b = b, timeMs = 10_000L))
    }

    private fun answer(level: BotLevel, state: DuelMatchState, arena: Level): DuelInput =
        DuelBot.decide(DuelBot.mind(level, 1L), "me", state, arena).second

    private fun DuelFighter.cooling() = copy(dashCooldownMs = 1_000L)

    @Test
    fun hardPrefersTheHighGround() {
        // The opponent stands 6 cm from the bottom edge, below and right of the book. NORMAL walks straight above it (to
        // push it down); HARD takes the spot on the book that pushes it to the corner, from 3 cm up (x1.25).
        val arena = DuelArenas.CUP_AND_BOOK
        val state = fight(arena, Ability.HIDE, Vec2(0.03f, -0.05f), Vec2(0.1f, -0.14f)) { a, b -> a.cooling() to b }
        fun ground(i: DuelInput) = Physics.groundHeight(arena, (i as DuelInput.MoveTo).target, 0f)
        assertEquals(0.03f, ground(answer(BotLevel.HARD, state, arena)), "HARD: ${answer(BotLevel.HARD, state, arena)}")
        assertEquals(0f, ground(answer(BotLevel.NORMAL, state, arena)), "NORMAL: ${answer(BotLevel.NORMAL, state, arena)}")
    }

    @Test
    fun aDashComingAtItNearAnEdge() {
        // 4 cm from the bottom edge, the opponent dashing straight down at it from 9 cm.
        val arena = DuelArenas.EMPTY
        fun coming(me: Ability, at: Vec2 = Vec2(0f, -0.16f)) = fight(arena, me, at, at + Vec2(0f, 0.09f)) { a, b ->
            a to b.copy(dashMs = 200L, dashDir = Vec2(0f, -1f), dashCooldownMs = 1_000L)
        }
        // HARD gets out of the way: a dash across the incoming one that ends on the table.
        val side = answer(BotLevel.HARD, coming(Ability.JUMP), arena)
        assertTrue(side is DuelInput.Dash && abs(side.dir!!.v) < 0.5f, "$side")
        assertTrue(DuelArena.insideDistance(arena.boundary!!, Vec2(0f, -0.16f) + side.dir!! * 0.075f) > 0.02f)
        // NORMAL (no special that helps) takes it; with a FLY or a HIDE it lifts off or dodges; EASY does nothing about it.
        assertTrue(answer(BotLevel.NORMAL, coming(Ability.JUMP), arena) !is DuelInput.Dash)
        assertEquals(DuelInput.Special, answer(BotLevel.NORMAL, coming(Ability.FLY), arena))
        assertEquals(DuelInput.Special, answer(BotLevel.NORMAL, coming(Ability.HIDE), arena))
        assertTrue(answer(BotLevel.EASY, coming(Ability.HIDE), arena) != DuelInput.Special)
        // In the middle of the table both dodge: HARD always did (a HIDE dodges for the counter); NORMAL does since T48
        // (`nearEdgeRadii` 20: before, in mid-table a FLY or a HIDE had no special at all); EASY still never.
        val middle = coming(Ability.HIDE, at = Vec2(0f, 0.05f))
        assertEquals(DuelInput.Special, answer(BotLevel.NORMAL, middle, arena))
        assertEquals(DuelInput.Special, answer(BotLevel.HARD, middle, arena))
        assertTrue(answer(BotLevel.EASY, middle, arena) != DuelInput.Special)
    }

    @Test
    fun hardCountersAfterADodge() {
        // The dodge let a hit through: the counter window is open. The opponent is beside it, not toward an edge: only
        // HARD dashes (a counter hits x1.5), NORMAL waits for an aligned dash.
        val arena = DuelArenas.EMPTY
        val state = fight(arena, Ability.HIDE, Vec2(0f, 0.05f), Vec2(0.1f, 0.05f)) { a, b -> a.copy(counterMs = 1_000L) to b }
        assertEquals(DuelInput.Dash(Vec2(1f, 0f)), answer(BotLevel.HARD, state, arena))
        assertTrue(answer(BotLevel.NORMAL, state, arena) !is DuelInput.Dash)
    }

    @Test
    fun theRamAndTheStomp() {
        val arena = DuelArenas.EMPTY
        // A ROLL facing the opponent, which stands 5 cm from the bottom edge right in front of it: NORMAL rams (the
        // opponent is in the path first); HARD does not, since a miss would roll it off the table.
        val nearEdge = fight(arena, Ability.ROLL, Vec2(0f, -0.08f), Vec2(0f, -0.15f)) { a, b ->
            a.cooling().copy(body = a.body.copy(state = a.body.state.copy(facing = Vec2(0f, -1f)))) to b
        }
        assertEquals(DuelInput.Special, answer(BotLevel.NORMAL, nearEdge, arena))
        assertTrue(answer(BotLevel.HARD, nearEdge, arena) != DuelInput.Special)
        // In the middle, where a miss ends on the table, HARD rams; NORMAL waits for the edge. Since T48 "near an edge"
        // for a ram is 8 radii (24 cm; 5 before), which is every spot of the 60 x 40 cm table: the middle of the
        // 120 x 60 cm one, 28 cm from its nearest edge, is where NORMAL still waits.
        val large = DuelArenas.LARGE
        val middle = fight(large, Ability.ROLL, Vec2(0f, 0.05f), Vec2(0f, -0.02f)) { a, b ->
            a.cooling().copy(body = a.body.copy(state = a.body.state.copy(facing = Vec2(0f, -1f)))) to b
        }
        assertEquals(DuelInput.Special, answer(BotLevel.HARD, middle, large))
        assertTrue(answer(BotLevel.NORMAL, middle, large) != DuelInput.Special)
        // A JUMP with the opponent within the stomp's reach, on the side of its edge: NORMAL stomps, EASY never does.
        val stomp = fight(arena, Ability.JUMP, Vec2.ZERO, Vec2(0f, -0.065f)) { a, b -> a.cooling() to b }
        assertEquals(DuelInput.Special, answer(BotLevel.NORMAL, stomp, arena))
        assertTrue(answer(BotLevel.EASY, stomp, arena) != DuelInput.Special)
    }

    // -- holes (CLIFF objects inside the boundary): no dash path, ram path or walk target into one ------------------

    private fun hole(center: Vec2, w: Float, d: Float) = LevelObject("hole", "hole", ObjectKind.CLIFF, Polygon.rect(center, w, d), 0f)

    private fun DuelFighter.facing(f: Vec2) = copy(body = body.copy(state = body.state.copy(facing = f)))

    @Test
    fun aRamNeverCrossesAHole() {
        // HARD ROLL 11 cm above the opponent, facing it, a 4 x 1 cm hole half way: a ram that missed would end on the
        // table (6.5 cm below the opponent) but cross the hole on the way. Without the hole HARD rams.
        val gap = hole(Vec2(0f, 0.045f), 0.04f, 0.01f)
        fun safeRam(arena: Level) = fight(arena, Ability.ROLL, Vec2(0f, 0.1f), Vec2(0f, -0.01f)) { a, b -> a.cooling().facing(Vec2(0f, -1f)) to b }
        assertEquals(DuelInput.Special, answer(BotLevel.HARD, safeRam(DuelArenas.EMPTY), DuelArenas.EMPTY))
        val holed = DuelArenas.EMPTY.copy(objects = listOf(gap))
        assertTrue(answer(BotLevel.HARD, safeRam(holed), holed) != DuelInput.Special, "HARD rammed across the hole")
        // NORMAL rams the opponent near the edge because it is in the path first: not when a hole lies before it.
        val before = hole(Vec2(0f, -0.06f), 0.04f, 0.01f)
        fun pathRam(arena: Level) = fight(arena, Ability.ROLL, Vec2(0f, -0.03f), Vec2(0f, -0.13f)) { a, b -> a.cooling().facing(Vec2(0f, -1f)) to b }
        assertEquals(DuelInput.Special, answer(BotLevel.NORMAL, pathRam(DuelArenas.EMPTY), DuelArenas.EMPTY))
        val holed2 = DuelArenas.EMPTY.copy(objects = listOf(before))
        assertTrue(answer(BotLevel.NORMAL, pathRam(holed2), holed2) != DuelInput.Special, "NORMAL rammed into the hole before the opponent")
    }

    @Test
    fun aSidestepNeverDashesAcrossAHole() {
        // HARD 4 cm from the bottom edge, a dash coming down at it; its first way out (+u) runs over a 1 x 4 cm hole 2 cm
        // to its right, whose end would be fine: it takes the other way.
        fun coming(arena: Level) = fight(arena, Ability.JUMP, Vec2(0f, -0.16f), Vec2(0f, -0.07f)) { a, b ->
            a to b.copy(dashMs = 200L, dashDir = Vec2(0f, -1f), dashCooldownMs = 1_000L)
        }
        assertEquals(DuelInput.Dash(Vec2(1f, 0f)), answer(BotLevel.HARD, coming(DuelArenas.EMPTY), DuelArenas.EMPTY))
        val holed = DuelArenas.EMPTY.copy(objects = listOf(hole(Vec2(0.02f, -0.16f), 0.01f, 0.04f)))
        val side = answer(BotLevel.HARD, coming(holed), holed)
        assertTrue(side is DuelInput.Dash && side.dir!!.u < -0.9f, "$side")
    }

    @Test
    fun aWalkTargetNeverLandsInAHoleAtTheRim() {
        // The same dash coming, HARD's own dash cooling: it steps aside on foot. To the right the edge margin and a 6 x 2
        // cm hole at the rim push the step back and forth (it used to end on the hole's edge, (0.06, -0.14)); the
        // target it sends keeps its whole circle off the hole.
        val rim = hole(Vec2(0.06f, -0.13f), 0.06f, 0.02f)
        val holed = DuelArenas.EMPTY.copy(objects = listOf(rim))
        val state = fight(holed, Ability.JUMP, Vec2(0f, -0.16f), Vec2(0f, -0.07f)) { a, b ->
            a.cooling() to b.copy(dashMs = 200L, dashDir = Vec2(0f, -1f), dashCooldownMs = 1_000L)
        }
        val step = answer(BotLevel.HARD, state, holed)
        assertTrue(step is DuelInput.MoveTo, "$step")
        val p = step.target
        assertTrue(!rim.footprint.contains(p) && distanceToPolygon(p, rim.footprint) >= R, "target $p is ${distanceToPolygon(p, rim.footprint)} m from the hole")
        assertTrue(DuelArena.insideDistance(holed.boundary!!, p) >= TUNING_DEFAULT.edgeMarginRadii * R - 1e-4f)
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
