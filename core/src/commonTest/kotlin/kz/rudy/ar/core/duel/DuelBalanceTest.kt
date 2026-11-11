package kz.rudy.ar.core.duel

import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Stat
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T48: the balance matrix's arithmetic, the defaults it settled on, and what they promise on the smallest and largest table. */
class DuelBalanceTest {

    @Test
    fun defaultsAreValid() {
        // Each default passes its own init checks (constructing it runs them) and is what the companions hand out.
        assertEquals(DuelParams.DEFAULT, DuelParams())
        assertEquals(DuelRules.DEFAULT, DuelRules())
        val tuning = BotTuning()
        tuning.requireFits(DuelParams.DEFAULT)
        for (level in BotLevel.entries) assertEquals(tuning.skill(level), BotTuning().skill(level))
        // Rebuilding each from its own fields passes the checks again (nothing only valid by accident of construction order).
        assertEquals(DuelParams.DEFAULT, Json.decodeFromString(DuelParams.serializer(), Json.encodeToString(DuelParams.serializer(), DuelParams.DEFAULT)))
        assertEquals(DuelRules.DEFAULT, Json.decodeFromString(DuelRules.serializer(), Json.encodeToString(DuelRules.serializer(), DuelRules.DEFAULT)))
        assertEquals(tuning, Json.decodeFromString(BotTuning.serializer(), Json.encodeToString(BotTuning.serializer(), tuning)))
        // A partial JSON (what `duel matrix --params` reads) fills the rest with the defaults.
        assertEquals(DuelParams.DEFAULT.copy(knockTauMs = 200L), Json.decodeFromString(DuelParams.serializer(), """{"knockTauMs": 200}"""))
        // The arenas the matrix measures, the large table among them, all have marks.
        assertEquals(listOf("empty", "cup-and-book", "small", "large"), DuelArenas.MATRIX.keys.toList())
        for ((name, arena) in DuelArenas.MATRIX) assertNull(DuelArena.startProblem(arena, DuelArenas.marks(arena), R), name)
    }

    // -- the matrix's arithmetic, on hand-made results ------------------------------------------------------------

    private fun result(winner: String?, ringOuts: Int, timeouts: Int, hits: Int, matchMs: Long, finished: Boolean = true) = SimResult(
        winner = winner, scoreA = if (winner == "a") 2 else 0, scoreB = if (winner == "b") 2 else 0, rounds = ringOuts + timeouts,
        finished = finished, matchMs = matchMs, ringOuts = ringOuts, timeouts = timeouts, replays = 0, hits = hits, wallSlams = 1, specials = 2,
    )

    private fun abilityCell(x: Ability, y: Ability, arena: String = "t", level: BotLevel = BotLevel.NORMAL) =
        MatrixCell(CellGroup.ABILITY, arena, level, Contestant("a", FighterStats.defaultFor(x), x), Contestant("b", FighterStats.defaultFor(y), y), x.name, y.name)

    @Test
    fun matrixArithmeticOnHandMadeResults() {
        // One cell's sums: 3 matches, a wins 2, b none, one unfinished.
        val t = DuelMatrix.tally(
            listOf(
                result("a", ringOuts = 2, timeouts = 0, hits = 10, matchMs = 30_000L),
                result("a", ringOuts = 1, timeouts = 1, hits = 8, matchMs = 50_000L),
                result(null, ringOuts = 0, timeouts = 3, hits = 6, matchMs = 70_000L, finished = false),
            ),
        )
        assertEquals(CellTally(matches = 3, winsA = 2, winsB = 0, draws = 0, unfinished = 1, matchMs = 150_000L, rounds = 7, ringOuts = 3, timeouts = 4, hits = 24, wallSlams = 3, specials = 6), t)
        assertEquals(2f / 3f, t.shareA, 1e-6f)
        assertEquals(0f, t.shareB)
        assertEquals(4f / 7f, t.clockShare, 1e-6f, "rounds ended by the clock / rounds ended")
        assertEquals(8f, t.hitsPerRingOut, 1e-6f)
        assertEquals(50f, t.meanMatchS, 1e-6f)
        assertEquals(Float.POSITIVE_INFINITY, CellTally(matches = 1, hits = 5).hitsPerRingOut)
        assertEquals(t.copy(winsA = 0, winsB = 2), t.swapped())

        // Against the field: JUMP's cells against the other three, from JUMP's side whichever slot it sat in; the
        // same-ability cell is not part of it.
        fun cell(x: Ability, y: Ability, winsA: Int, winsB: Int, clock: Int = 0) =
            CellResult(abilityCell(x, y), CellTally(matches = 10, winsA = winsA, winsB = winsB, draws = 10 - winsA - winsB, ringOuts = 20 - clock, timeouts = clock, hits = 100, matchMs = 600_000L))
        val results = listOf(
            cell(Ability.JUMP, Ability.JUMP, 5, 5, clock = 20),
            cell(Ability.JUMP, Ability.FLY, 7, 3),
            cell(Ability.JUMP, Ability.ROLL, 3, 7),
            cell(Ability.HIDE, Ability.JUMP, 4, 6), // JUMP in slot b: its 6 wins count
            cell(Ability.FLY, Ability.ROLL, 4, 5), // and a draw
            cell(Ability.FLY, Ability.HIDE, 4, 6),
            cell(Ability.ROLL, Ability.HIDE, 9, 1, clock = 6),
        )
        val jump = assertNotNull(DuelMatrix.againstField(results, "t", BotLevel.NORMAL, Ability.JUMP))
        assertEquals(30, jump.matches)
        assertEquals((7 + 3 + 6) / 30f, jump.shareA, 1e-6f)
        assertEquals((3 + 7 + 4) / 30f, jump.shareB, 1e-6f)
        val roll = assertNotNull(DuelMatrix.againstField(results, "t", BotLevel.NORMAL, Ability.ROLL))
        assertEquals((7 + 5 + 9) / 30f, roll.shareA, 1e-6f)
        assertEquals(1, roll.draws)
        assertNull(DuelMatrix.againstField(results, "t", BotLevel.HARD, Ability.ROLL), "no cell at that level")
        // The arena total (A4, A5) sums every ability cell, the same-ability one too.
        val total = assertNotNull(DuelMatrix.arenaTotal(results, "t", BotLevel.NORMAL))
        assertEquals(70, total.matches)
        assertEquals(60f, total.meanMatchS, 1e-4f)
        assertEquals(700f / 114f, total.hitsPerRingOut, 1e-4f) // 140 rounds, 26 by the clock

        // The verdicts: ROLL takes 70 % of the field (A1 out), ROLL v HIDE is 90 / 10 (A2 out on both sides), the JUMP
        // mirror ended every round by the clock and ROLL v HIDE 30 % (A3 out), hits per ring-out 6.1 and a 60 s mean
        // pass A4 and A5.
        val verdicts = DuelMatrix.acceptance(results, stepsAgree = true).associateBy { it.id }
        assertEquals(listOf("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8"), verdicts.keys.toList())
        assertFalse(verdicts.getValue("A1").pass)
        assertEquals(listOf("t NORMAL ROLL 70.0 %"), verdicts.getValue("A1").failures)
        assertEquals("36.7 %..70.0 %", verdicts.getValue("A1").measured)
        assertEquals(listOf("t NORMAL ROLL v HIDE 90.0 %", "t NORMAL HIDE v ROLL 10.0 %"), verdicts.getValue("A2").failures)
        assertEquals(listOf("t NORMAL JUMP v JUMP 100.0 %", "t NORMAL ROLL v HIDE 30.0 %"), verdicts.getValue("A3").failures)
        assertTrue(verdicts.getValue("A4").pass && verdicts.getValue("A5").pass)
        assertEquals("6.1..6.1", verdicts.getValue("A4").measured)
        // No stat cells: A6 and A7 cannot pass on nothing; A8 as given.
        assertFalse(verdicts.getValue("A6").pass || verdicts.getValue("A7").pass)
        assertTrue(verdicts.getValue("A8").pass)
        assertFalse(DuelMatrix.acceptance(results, stepsAgree = null).last().pass, "A8 not checked is not a pass")
        // The large table has its own clock bound.
        val large = results.map { it.copy(cell = it.cell.copy(arena = "large")) }
        assertEquals(listOf("large NORMAL JUMP v JUMP 100.0 %"), DuelMatrix.acceptance(large, true).first { it.id == "A3" }.failures)

        // The stat cells: specialists within 40..65 %, 8/8/8/8 at least 65 %.
        val stat = DuelMatrix.statCells(listOf("t"), listOf(BotLevel.NORMAL))
        assertEquals(20, stat.size)
        assertEquals(FighterStats(4, 8, 4, 4), stat.first { it.labelA == "SPEED" }.a.stats)
        assertTrue(stat.all { it.b.stats == FighterStats.BALANCED && it.a.ability == it.b.ability })
        val statResults = stat.map { c ->
            val wins = if (c.a.stats == DuelMatrix.BOOSTED) 7 else if (c.labelA == Stat.GUARD.name) 3 else 5
            CellResult(c, CellTally(matches = 10, winsA = wins, winsB = 10 - wins))
        }
        val sv = DuelMatrix.acceptance(results + statResults, true).associateBy { it.id }
        assertEquals(Ability.entries.map { "t NORMAL $it GUARD 30.0 %" }, sv.getValue("A6").failures)
        assertTrue(sv.getValue("A7").pass)
        assertEquals("70.0 %..70.0 %", sv.getValue("A7").measured)

        // The numbers print the same everywhere.
        assertEquals("45.7 %", DuelMatrix.pct(0.4567f))
        assertEquals("-0.5", DuelMatrix.one(-0.46f))
        assertEquals("inf", DuelMatrix.one(Float.POSITIVE_INFINITY))
        // The ability cells: 10 unordered pairs per arena and level, archetype stats.
        val cells = DuelMatrix.abilityCells(listOf("x", "y"), listOf(BotLevel.NORMAL, BotLevel.HARD))
        assertEquals(40, cells.size)
        assertEquals(4, cells.count { it.arena == "x" && it.level == BotLevel.HARD && it.a.ability == it.b.ability })
    }

    @Test
    fun aSplitSeedRangePlaysTheSameMatches() {
        // What lets the CLI spread a cell over threads: the matches of seeds 4..9 are the same played in one call or two.
        val cell = abilityCell(Ability.ROLL, Ability.HIDE, arena = "small")
        val arena = DuelArenas.SMALL
        val whole = DuelMatrix.play(cell, arena, 4L, 10L)
        assertEquals(whole, DuelMatrix.play(cell, arena, 4L, 7L) + DuelMatrix.play(cell, arena, 7L, 10L))
        // ... and the same as DuelSim.series (marks swapped on odd seeds = every other match from an even start).
        val s = DuelSim.series(cell.a, cell.b, arena, BotLevel.NORMAL, BotLevel.NORMAL, 6, 4L)
        val t = DuelMatrix.tally(whole)
        assertEquals(listOf(s.winsA, s.winsB, s.ringOuts, s.timeouts, s.hits), listOf(t.winsA, t.winsB, t.ringOuts, t.timeouts, t.hits))
    }

    // -- the smallest and the largest table -----------------------------------------------------------------------

    /** Steps [start] on [level] in 16 ms frames until [stop] or [maxMs]; [script] gives the inputs of each frame. */
    private fun drive(start: DuelState, level: Level, maxMs: Long, stop: (DuelStep) -> Boolean, script: (DuelState) -> Map<String, DuelInput>): Pair<DuelState, List<DuelEvent>> {
        var s = start
        val events = ArrayList<DuelEvent>()
        while (s.timeMs < maxMs) {
            val out = DuelPhysics.step(s, level, 16L, script(s))
            events += out.events
            s = out.state
            if (stop(out)) break
        }
        return s to events
    }

    @Test
    fun aBalancedHitFromTheMarksIsNotARingOutOnSmall() {
        // 5/5/5/5 against 5/5/5/5, a plain dash, the victim on its mark of the 30 x 30 cm table (7 cm from the edge behind
        // it), no wobble yet: from every one of 32 directions the hit leaves it on the table, and it slides less than
        // the 7 cm to that nearest edge.
        val arena = DuelArenas.SMALL
        val (mark, _) = DuelArenas.marks(arena)
        val boundary = arena.boundary!!
        var farthest = 0f
        for (k in 0 until 32) {
            val angle = 2.0 * kotlin.math.PI * k / 32
            val push = Vec2(cos(angle).toFloat(), sin(angle).toFloat())
            val victim = fighter("b", mark)
            val attacker = fighter("a", mark - push * NEAR, facing = push)
            val (end, events) = drive(DuelState(attacker, victim), arena, 3000L, { false }) { s -> if (s.timeMs == 0L) inputs("a" to Dash(push)) else emptyMap() }
            assertEquals(1, events.count { it is DuelEvent.Hit && it.victim == "b" }, "direction $k: ${events.filterIsInstance<DuelEvent.Hit>()}")
            assertTrue(events.none { it is DuelEvent.RingOut && it.id == "b" }, "direction $k: the even hit rang the victim out from its mark")
            assertTrue(boundary.contains(end.b.pos) && end.b.body.state.mode != Mode.FALLING, "direction $k: ${end.b.pos}")
            farthest = maxOf(farthest, end.b.pos.distanceTo(mark))
        }
        val nearestEdge = DuelArena.insideDistance(boundary, mark)
        println("aBalancedHitFromTheMarksIsNotARingOutOnSmall: the mark is ${nearestEdge * 100} cm from the edge, an even hit slides it at most ${farthest * 100} cm")
        assertTrue(farthest < nearestEdge, "slide $farthest m, edge $nearestEdge m")
    }

    @Test
    fun aRingOutIsReachableOnLarge() {
        // The 120 x 60 cm table. The victim starts on its mark (30 cm from the nearest edge) and walks back to it whenever
        // it may; the attacker (5/5/5/5 both) walks round to the side away from that edge and dashes at it whenever it
        // can. Within A4's 8 hits the victim is off the table.
        val arena = DuelArenas.LARGE
        val (mark, other) = DuelArenas.marks(arena)
        val toEdge = Vec2(0f, -1f) // the nearest edge from the mark: v = -0.30
        val standoff = 2 * R + 0.6f * R
        val reach = DuelParams.DEFAULT.dashSpeedMps * DuelParams.DEFAULT.dashMs / 1000f
        val start = DuelState(fighter("a", other, facing = Vec2(-1f, 0f)), fighter("b", mark, facing = Vec2(1f, 0f)))
        val (end, events) = drive(start, arena, 120_000L, { out -> out.events.any { it is DuelEvent.RingOut } }) { s ->
            val ins = HashMap<String, DuelInput>()
            val a = s.a
            val b = s.b
            if (DuelPhysics.acceptsInput(b, arena)) ins["b"] = MoveTo(mark)
            if (DuelPhysics.acceptsInput(a, arena)) {
                val spot = b.pos - toEdge * standoff
                val toB = b.pos - a.pos
                val gap = toB.length - 2 * R
                val behind = (a.pos - b.pos).normalized().dot(-toEdge) > 0.9f
                ins["a"] = if (behind && gap <= reach * 0.8f && DuelPhysics.canDash(a, arena)) Dash(toB) else MoveTo(spot)
            }
            ins
        }
        val hits = events.count { it is DuelEvent.Hit && it.victim == "b" }
        val out = events.filterIsInstance<DuelEvent.RingOut>()
        println("aRingOutIsReachableOnLarge: ${out.map { it.id }} after $hits hits at ${end.timeMs} ms")
        assertEquals(listOf("b"), out.map { it.id }, "the victim (only) went off; $hits hits in ${end.timeMs} ms")
        assertTrue(hits in 1..8, "$hits hits")
    }
}
