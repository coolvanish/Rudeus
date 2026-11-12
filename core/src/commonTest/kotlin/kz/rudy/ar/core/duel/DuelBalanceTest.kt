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

        // The verdicts (T57's B table; with T48's 35..65 % for the archetype field, so that ROLL's 70 % is out as it was
        // for A1): ROLL takes 70 % of the field (B2 out), ROLL v HIDE is 90 / 10 (B3 out on both sides), the JUMP mirror
        // ended every round by the clock and ROLL v HIDE 30 % (B4 out). B5 and B6 are judged per pair: the JUMP mirror
        // has no ring-out at all (B5 out, though the arena's 6.1 hits per ring-out would pass); every pair's 60 s passes
        // B6. No equal-stats cells: B1 cannot pass on nothing.
        val verdicts = DuelMatrix.acceptance(results, stepsAgree = true, t = Acceptance(fieldMin = 0.35f, fieldMax = 0.65f)).associateBy { it.id }
        assertEquals(listOf("B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9"), verdicts.keys.toList())
        assertFalse(verdicts.getValue("B1").pass)
        assertEquals("no cells", verdicts.getValue("B1").measured)
        assertFalse(verdicts.getValue("B2").pass)
        assertEquals(listOf("t NORMAL ROLL 70.0 %"), verdicts.getValue("B2").failures)
        assertEquals("36.7 %..70.0 %", verdicts.getValue("B2").measured)
        assertTrue(DuelMatrix.acceptance(results, stepsAgree = true).first { it.id == "B2" }.pass, "70 % is inside T57's 30..70 %")
        assertEquals(listOf("t NORMAL archetype ROLL v HIDE 90.0 %", "t NORMAL archetype HIDE v ROLL 10.0 %"), verdicts.getValue("B3").failures)
        assertEquals(listOf("t NORMAL archetype JUMP v JUMP 100.0 %", "t NORMAL archetype ROLL v HIDE 30.0 %"), verdicts.getValue("B4").failures)
        assertFalse(verdicts.getValue("B5").pass)
        assertEquals(listOf("t NORMAL archetype JUMP v JUMP inf"), verdicts.getValue("B5").failures)
        assertEquals("per pair 5.0..inf (per arena 6.1..6.1)", verdicts.getValue("B5").measured)
        assertTrue(verdicts.getValue("B6").pass)
        assertEquals("per pair 60.0 s..60.0 s (per arena 60.0 s..60.0 s)", verdicts.getValue("B6").measured)
        // One pair over the bound fails B5 even when its arena's average is inside (ROLL v HIDE 7.1 -> 10.0).
        val heavy = results.map { if (it.cell.labelA == "ROLL" && it.cell.labelB == "HIDE") it.copy(tally = it.tally.copy(hits = 140)) else it }
        assertEquals(listOf("t NORMAL archetype JUMP v JUMP inf", "t NORMAL archetype ROLL v HIDE 10.0"), DuelMatrix.acceptance(heavy, true).first { it.id == "B5" }.failures)
        // No stat cells: B7 and B8 cannot pass on nothing; B9 as given.
        assertFalse(verdicts.getValue("B7").pass || verdicts.getValue("B8").pass)
        assertTrue(verdicts.getValue("B9").pass)
        assertFalse(DuelMatrix.acceptance(results, stepsAgree = null).last().pass, "B9 not checked is not a pass")
        // The large table has its own clock bound and hits bound (up to 10: ROLL v HIDE's 10 hits pass there), the small
        // one its own shortest match (20 s: a 25 s pair fails B6 elsewhere, passes there).
        val large = results.map { it.copy(cell = it.cell.copy(arena = "large")) }
        assertEquals(listOf("large NORMAL archetype JUMP v JUMP 100.0 %"), DuelMatrix.acceptance(large, true).first { it.id == "B4" }.failures)
        assertEquals(listOf("large NORMAL archetype JUMP v JUMP inf"), DuelMatrix.acceptance(heavy.map { it.copy(cell = it.cell.copy(arena = "large")) }, true).first { it.id == "B5" }.failures)
        val quick = results.map { it.copy(tally = it.tally.copy(matchMs = 250_000L)) } // 25 s a match
        assertEquals(results.size, DuelMatrix.acceptance(quick, true).first { it.id == "B6" }.failures.size)
        assertTrue(DuelMatrix.acceptance(quick.map { it.copy(cell = it.cell.copy(arena = "small")) }, true).first { it.id == "B6" }.pass)

        // T57's equal-stats group (B1): the same pairs with both sides 5/5/5/5, against the field like B2 but at 35..65 %.
        val equal = results.map { r ->
            r.copy(cell = r.cell.copy(group = CellGroup.EQUAL, a = r.cell.a.copy(stats = FighterStats.BALANCED), b = r.cell.b.copy(stats = FighterStats.BALANCED)))
        }
        val eq = DuelMatrix.acceptance(equal, true).associateBy { it.id }
        assertEquals(listOf("t NORMAL ROLL 70.0 %"), eq.getValue("B1").failures)
        assertEquals("no cells", eq.getValue("B2").measured)
        assertEquals(listOf("t NORMAL equal ROLL v HIDE 90.0 %", "t NORMAL equal HIDE v ROLL 10.0 %"), eq.getValue("B3").failures)
        assertEquals(
            DuelMatrix.againstField(results, "t", BotLevel.NORMAL, Ability.ROLL),
            DuelMatrix.againstField(equal, "t", BotLevel.NORMAL, Ability.ROLL, CellGroup.EQUAL),
        )
        val eqCells = DuelMatrix.equalCells(listOf("x", "y"), listOf(BotLevel.NORMAL, BotLevel.HARD))
        assertEquals(40, eqCells.size)
        assertTrue(eqCells.all { it.group == CellGroup.EQUAL && it.a.stats == FighterStats.BALANCED && it.b.stats == FighterStats.BALANCED })

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
        assertEquals(Ability.entries.map { "t NORMAL $it GUARD 30.0 %" }, sv.getValue("B7").failures)
        assertTrue(sv.getValue("B8").pass)
        assertEquals("70.0 %..70.0 %", sv.getValue("B8").measured)

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
    fun acceptanceHoldsOnASample() {
        // A reduced matrix (T57: re-pointed at B1 and B4): the equal-stats cells (all 5/5/5/5) on the four arenas at
        // NORMAL and HARD, 20 matches each (1600), judged by B1 with a widened margin, B4 as it is and B5 (per pair)
        // widened. Not the acceptance itself (`duel matrix --n 200` is, see docs/duel-balance.md): a guard that turns red
        // when a later change breaks the balance badly: an ability that wins far too much or too little against the field
        // at equal stats, rounds left to the clock, or a pair that needs far more hits than B5's 8 to ring anyone out.
        val cells = DuelMatrix.equalCells(DuelArenas.MATRIX.keys.toList(), listOf(BotLevel.NORMAL, BotLevel.HARD))
        val results = cells.map { c -> CellResult(c, DuelMatrix.tally(DuelMatrix.play(c, DuelArenas.MATRIX.getValue(c.arena), 1L, 21L))) }
        val wide = Acceptance(equalMin = 0.10f, equalMax = 0.90f, hitsMax = 16f, hitsMaxLarge = 16f)
        val verdicts = DuelMatrix.acceptance(results, stepsAgree = null, t = wide).associateBy { it.id }
        val strict = DuelMatrix.acceptance(results, stepsAgree = null).associateBy { it.id }
        println(
            "acceptanceHoldsOnASample: B1 ${strict.getValue("B1").measured} (${strict.getValue("B1").failures.size} out of 35..65 %), " +
                "B4 ${strict.getValue("B4").measured}, B5 ${strict.getValue("B5").measured}, B6 ${strict.getValue("B6").measured}",
        )
        for (id in listOf("B1", "B4", "B5")) {
            val v = verdicts.getValue(id)
            assertTrue(v.pass, "$id ${v.measured} out of ${v.threshold}: ${v.failures}")
        }
        // Every one of them ended, and the rounds ended on the table.
        assertTrue(results.all { it.tally.unfinished == 0 && it.tally.matches == 20 })
        assertTrue(strict.getValue("B4").pass, "B4 ${strict.getValue("B4")}")
    }

    @Test
    fun aSplitSeedRangePlaysTheSameMatches() {
        // What lets the CLI spread a cell over threads: the matches of a seed range are the same played in one call or
        // two (the same first seed), and the same as DuelSim.series from that seed (marks swapped every other match from
        // it), from an even and from an odd seed: `duel series --seed N` replays a matrix cell.
        val cell = abilityCell(Ability.ROLL, Ability.HIDE, arena = "small")
        val arena = DuelArenas.SMALL
        for (first in listOf(4L, 5L)) {
            val whole = DuelMatrix.play(cell, arena, first, first + 6L)
            assertEquals(whole, DuelMatrix.play(cell, arena, first, first + 3L) + DuelMatrix.play(cell, arena, first + 3L, first + 6L, first = first), "from $first")
            val s = DuelSim.series(cell.a, cell.b, arena, BotLevel.NORMAL, BotLevel.NORMAL, 6, first)
            val t = DuelMatrix.tally(whole)
            assertEquals(listOf(s.winsA, s.winsB, s.ringOuts, s.timeouts, s.hits, s.wallSlams, s.specials), listOf(t.winsA, t.winsB, t.ringOuts, t.timeouts, t.hits, t.wallSlams, t.specials), "from $first")
            assertEquals(s.meanMatchMs, t.matchMs / 6, "from $first")
            // Match by match: series swaps on the offset, so seed first + i has the marks the other way round for odd i.
            val marks = DuelArenas.marks(arena)
            for ((i, r) in whole.withIndex()) {
                val m = if (i % 2 == 0) marks else marks.second to marks.first
                assertEquals(DuelSim.match(cell.a, cell.b, arena, BotLevel.NORMAL, BotLevel.NORMAL, first + i, marks = m).result, r, "seed ${first + i}")
            }
        }
    }

    // -- the smallest and the largest table -----------------------------------------------------------------------

    /** Steps [start] on [level] in 16 ms frames until [stop] or [maxMs]; [script] gives the inputs of each frame. */
    private fun drive(
        start: DuelState,
        level: Level,
        maxMs: Long,
        stop: (DuelStep) -> Boolean,
        params: DuelParams = DuelParams.DEFAULT,
        script: (DuelState) -> Map<String, DuelInput>,
    ): Pair<DuelState, List<DuelEvent>> {
        var s = start
        val events = ArrayList<DuelEvent>()
        while (s.timeMs < maxMs) {
            val out = DuelPhysics.step(s, level, 16L, script(s), params)
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

    /**
     * The 120 x 60 cm table, 5/5/5/5 both: the victim starts on its mark, 30 cm from the nearest edge; the attacker walks
     * round to the side away from that edge and dashes at it whenever it can, until somebody rings out or 120 s pass.
     * [recovers]: the victim walks back to its mark whenever it may (else it stands). Returns the hits it took, who went
     * off, and when.
     */
    private fun pushOffLarge(recovers: Boolean, params: DuelParams = DuelParams.DEFAULT): Triple<Int, List<String>, Long> {
        val arena = DuelArenas.LARGE
        val (mark, other) = DuelArenas.marks(arena)
        val toEdge = Vec2(0f, -1f) // the nearest edge from the mark: v = -0.30
        val standoff = 2 * R + 0.6f * R
        val reach = params.dashSpeedMps * params.dashMs / 1000f
        val start = DuelState(fighter("a", other, facing = Vec2(-1f, 0f)), fighter("b", mark, facing = Vec2(1f, 0f)))
        val (end, events) = drive(start, arena, 120_000L, { out -> out.events.any { it is DuelEvent.RingOut } }, params) { s ->
            val ins = HashMap<String, DuelInput>()
            val a = s.a
            val b = s.b
            if (recovers && DuelPhysics.acceptsInput(b, arena, params)) ins["b"] = MoveTo(mark)
            if (DuelPhysics.acceptsInput(a, arena, params)) {
                val spot = b.pos - toEdge * standoff
                val toB = b.pos - a.pos
                val gap = toB.length - 2 * R
                val behind = (a.pos - b.pos).normalized().dot(-toEdge) > 0.9f
                ins["a"] = if (behind && gap <= reach * 0.8f && DuelPhysics.canDash(a, arena, params)) Dash(toB) else MoveTo(spot)
            }
            ins
        }
        return Triple(events.count { it is DuelEvent.Hit && it.victim == "b" }, events.filterIsInstance<DuelEvent.RingOut>().map { it.id }, end.timeMs)
    }

    @Test
    fun aRingOutIsReachableOnLarge() {
        // A scripted series of hits (the victim stands) pushes it from its mark off the 120 cm table within A4's 8 hits.
        val (hits, out, ms) = pushOffLarge(recovers = false)
        assertEquals(listOf("b"), out, "the victim (only) went off; $hits hits in $ms ms")
        assertTrue(hits in 1..8, "$hits hits")
        // Measured, not asserted: without wobble the same series needs more hits; a victim that walks back to its mark
        // after every hit (as a child might) needs more again (T48's Result; before T48 it was never pushed off).
        val (flat, flatOut, _) = pushOffLarge(recovers = false, params = DuelParams.DEFAULT.copy(wobblePerHit = 0f))
        val (walking, walkingOut, walkingMs) = pushOffLarge(recovers = true)
        println(
            "aRingOutIsReachableOnLarge: standing victim off after $hits hits ($ms ms); without wobble $flat hits $flatOut; " +
                "walking back to its mark: $walking hits $walkingOut ($walkingMs ms)",
        )
    }
}
