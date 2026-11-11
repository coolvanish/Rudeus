package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private val X = Contestant("x", FighterStats(6, 5, 5, 4), Ability.JUMP)
private val Y = Contestant("y", FighterStats(4, 6, 4, 6), Ability.FLY)

class DuelSimTest {

    @Test
    fun sameSeedSameMatch() {
        for ((name, arena) in DuelArenas.ALL) {
            val one = DuelSim.match(X, Y, arena, BotLevel.NORMAL, BotLevel.HARD, 77L)
            val two = DuelSim.match(X, Y, arena, BotLevel.NORMAL, BotLevel.HARD, 77L)
            assertEquals(one.result, two.result, name)
            assertEquals(one.final, two.final, name)
            assertTrue(one.result.finished, "$name: ${one.result}")
            assertEquals(one.result, DuelSim.run(X, Y, arena, BotLevel.NORMAL, BotLevel.HARD, 77L), name)
            // Any frame length: the same match.
            for (stepMs in listOf(16L, 50L, 7L)) {
                val other = DuelSim.match(X, Y, arena, BotLevel.NORMAL, BotLevel.HARD, 77L, stepMs = stepMs)
                assertEquals(one.final, other.final, "$name at $stepMs ms")
                assertEquals(one.result, other.result, "$name at $stepMs ms")
            }
        }
    }

    @Test
    fun differentSeedsDiffer() {
        for ((name, arena) in DuelArenas.ALL) {
            val runs = (0L until 6L).map { DuelSim.match(X, Y, arena, BotLevel.NORMAL, BotLevel.NORMAL, it) }
            val finals = runs.map { it.final }.toSet()
            val results = runs.map { it.result }.toSet()
            assertEquals(runs.size, finals.size, "$name: two seeds played the same match")
            assertEquals(runs.size, results.size, "$name: ${runs.map { it.result }}")
            // The bots' generators really are separate: a's seed differs from b's in one match.
            val start = runs[0].final
            assertNotEquals(start.botA!!.rng, start.botB!!.rng)
        }
    }

    @Test
    fun everyMatchFinishes() {
        // Every level pair x every ability pair x the three arenas x 3 seeds (1296 matches, default rules, archetype
        // stats): every one reaches RESULT within maxMatchMs, and its books add up.
        val maxMs = 600_000L
        var matches = 0
        var draws = 0
        var timeouts = 0
        var rounds = 0
        val mark = TimeSource.Monotonic.markNow()
        for ((name, arena) in DuelArenas.ALL) {
            for (la in BotLevel.entries) for (lb in BotLevel.entries) {
                for (aa in Ability.entries) for (ab in Ability.entries) {
                    val a = Contestant("a", FighterStats.defaultFor(aa), aa)
                    val b = Contestant("b", FighterStats.defaultFor(ab), ab)
                    for (seed in 0L until 3L) {
                        val r = DuelSim.run(a, b, arena, la, lb, seed * 1_000L + matches, maxMatchMs = maxMs)
                        assertTrue(r.finished && r.matchMs <= maxMs, "$name $la($aa) v $lb($ab) seed $seed: $r")
                        assertEquals(r.rounds, r.ringOuts + r.timeouts, "every round ended: $r")
                        val rules = DuelRules()
                        if (r.winner == null) {
                            assertEquals(rules.maxRounds, r.rounds, "a draw only after maxRounds: $r")
                            draws++
                        } else {
                            assertEquals(rules.pointsToWin, if (r.winner == "a") r.scoreA else r.scoreB, "$r")
                        }
                        assertEquals(r.rounds - r.replays, r.scoreA + r.scoreB, "$r")
                        timeouts += r.timeouts
                        rounds += r.rounds
                        matches++
                    }
                }
            }
        }
        val sec = mark.elapsedNow().inWholeMilliseconds / 1000.0
        println("everyMatchFinishes: $matches matches finished in $sec s, $draws draws, $timeouts of $rounds rounds by the clock")
        assertEquals(1296, matches)
    }

    @Test
    fun arenasAreTheBriefs() {
        val box = { a: kz.rudy.ar.core.domain.Level -> a.boundary!!.points.let { p -> listOf(p.minOf { it.u }, p.maxOf { it.u }, p.minOf { it.v }, p.maxOf { it.v }) } }
        assertEquals(listOf(-0.3f, 0.3f, -0.2f, 0.2f), box(DuelArenas.EMPTY))
        assertEquals(listOf(-0.3f, 0.3f, -0.2f, 0.2f), box(DuelArenas.CUP_AND_BOOK))
        assertEquals(listOf(-0.15f, 0.15f, -0.15f, 0.15f), box(DuelArenas.SMALL))
        assertEquals(listOf("empty", "cup-and-book", "small"), DuelArenas.ALL.keys.toList())
        val (cup, book) = DuelArenas.CUP_AND_BOOK.objects
        assertEquals(ObjectKind.OBSTACLE to 0.09f, cup.kind to cup.heightM)
        assertEquals(ObjectKind.PLATFORM to 0.03f, book.kind to book.heightM)
        for ((name, arena) in DuelArenas.ALL) {
            assertEquals(Vec2.ZERO, arena.spawn, name)
            val (p, q) = DuelArenas.marks(arena)
            assertNull(DuelArena.startProblem(arena, p to q, R), name)
            // Mirror images across u = 0, the cup and the book off the line between them: swapping marks swaps nothing else.
            assertTrue(abs(p.u + q.u) < 1e-6f && abs(p.v - q.v) < 1e-6f, "$name: $p $q")
            assertEquals(DuelArenas.SEPARATION_M, p.distanceTo(q), 1e-4f, name)
            for (o in arena.objects) for (pt in o.footprint.points) assertTrue(o.footprint.points.any { abs(it.u + pt.u) < 1e-5f && abs(it.v - pt.v) < 1e-5f }, "${o.id} is not symmetric")
        }
    }

    @Test
    fun simIsFastEnough() {
        // Three level pairs on the three arenas, two abilities: timed without checks (best of two).
        val pairs = listOf(BotLevel.NORMAL to BotLevel.NORMAL, BotLevel.HARD to BotLevel.EASY, BotLevel.HARD to BotLevel.NORMAL)
        var best = Double.MAX_VALUE
        var matches = 0
        var matchMs = 0L
        repeat(2) {
            matches = 0
            matchMs = 0L
            val mark = TimeSource.Monotonic.markNow()
            for (arena in DuelArenas.ALL.values) for ((la, lb) in pairs) for (ab in listOf(Ability.JUMP, Ability.HIDE)) {
                val s = DuelSim.series(Contestant("a", FighterStats.BALANCED, ab), Contestant("b", FighterStats.BALANCED, ab), arena, la, lb, 4, 900L)
                matches += s.matches
                matchMs += s.meanMatchMs * s.matches
            }
            best = minOf(best, mark.elapsedNow().inWholeMicroseconds / 1e6)
        }
        val perSecond = matches / best
        println(
            "simIsFastEnough: $matches matches in $best s: ${perSecond.toLong()} matches/s, ${(matchMs / 33.0 / best).toLong()} runner steps/s " +
                "at 33 ms, ${(matchMs / 1000.0 / best).toLong()} s of match per s",
        )
        assertTrue(perSecond >= 20.0, "$perSecond matches/s")
    }

    @Test
    fun theLevelTableOnEmpty() {
        // Measured, not asserted (the brief): every level pair on EMPTY, equal archetype stats and ability on both sides,
        // 10 matches per ability. Clock = share of rounds ended by the round clock.
        for (la in BotLevel.entries) for (lb in BotLevel.entries) {
            var winsA = 0
            var winsB = 0
            var ms = 0L
            var ringOuts = 0
            var timeouts = 0
            var hits = 0
            for (ab in Ability.entries) {
                val c = FighterStats.defaultFor(ab)
                val s = DuelSim.series(Contestant("a", c, ab), Contestant("b", c, ab), DuelArenas.EMPTY, la, lb, 10, 20_000L)
                winsA += s.winsA
                winsB += s.winsB
                ms += s.meanMatchMs * s.matches
                ringOuts += s.ringOuts
                timeouts += s.timeouts
                hits += s.hits
            }
            println(
                "levelTable empty $la v $lb: a ${winsA * 100 / 40} %, b ${winsB * 100 / 40} %, clock ${timeouts * 100 / maxOf(1, ringOuts + timeouts)} %, " +
                    "mean ${ms / 40 / 1000} s, hits per ring-out ${if (ringOuts == 0) "-" else (hits / ringOuts).toString()}",
            )
        }
    }
}
