package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
}
