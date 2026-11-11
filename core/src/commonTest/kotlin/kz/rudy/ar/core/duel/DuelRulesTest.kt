package kz.rudy.ar.core.duel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T45's checks on the numbers T48 will search: every rule and every special parameter refuses a bad value by name. */
class DuelRulesTest {

    private fun refusedByName(cases: List<Pair<String, () -> Any>>) {
        for ((name, make) in cases) {
            val e = assertFailsWith<IllegalArgumentException>(name) { make() }
            assertTrue(name in (e.message ?: ""), "the message names $name: ${e.message}")
        }
    }

    @Test
    fun badRulesAreRejectedByName() {
        refusedByName(
            listOf(
                "pointsToWin" to { DuelRules(pointsToWin = 0) },
                "maxRounds" to { DuelRules(maxRounds = 0) },
                "countdownMs" to { DuelRules(countdownMs = -1L) },
                "roundMs" to { DuelRules(roundMs = 0L) },
                "pointPauseMs" to { DuelRules(pointPauseMs = -1L) },
                "maxStepMs" to { DuelRules(maxStepMs = 0L) },
                "timeoutTieM" to { DuelRules(timeoutTieM = -0.001f) },
                "timeoutTieM" to { DuelRules(timeoutTieM = Float.NaN) },
            ),
        )
        // The edges that are allowed: no countdown, no pause, an exact tie only, a one-round match.
        val edge = DuelRules(countdownMs = 0L, pointPauseMs = 0L, timeoutTieM = 0f, pointsToWin = 1, maxRounds = 1)
        assertEquals(1, edge.maxRounds)
        assertEquals(DuelRules(), DuelRules.DEFAULT)
    }

    @Test
    fun badSpecialParamsAreRejectedByName() {
        refusedByName(
            listOf(
                "specialCooldownMs" to { DuelParams(specialCooldownMs = -1L) },
                "liftMs" to { DuelParams(liftMs = -1L) },
                "dodgeMs" to { DuelParams(dodgeMs = -1L) },
                "counterMs" to { DuelParams(counterMs = -1L) },
                "stompRadiusRadii" to { DuelParams(stompRadiusRadii = -1f) },
                "stompRadiusRadii" to { DuelParams(stompRadiusRadii = Float.NaN) },
                "stompScale" to { DuelParams(stompScale = 0f) },
                "liftSpeedScale" to { DuelParams(liftSpeedScale = 0f) },
                "liftHeightRadii" to { DuelParams(liftHeightRadii = 0f) }, // a hover on the ground would launch a jump
                "ramDashScale" to { DuelParams(ramDashScale = 0f) },
                "ramDashScale" to { DuelParams(ramDashScale = Float.POSITIVE_INFINITY) },
                "ramKnockScale" to { DuelParams(ramKnockScale = -1.5f) },
                "counterKnockScale" to { DuelParams(counterKnockScale = Float.NaN) },
            ),
        )
        // Zero times and a zero stomp reach are allowed (a special that does nothing, a stomp that reaches nobody).
        val off = DuelParams(specialCooldownMs = 0L, liftMs = 0L, dodgeMs = 0L, counterMs = 0L, stompRadiusRadii = 0f)
        assertEquals(0L, off.specialCooldownMsFor(stats()))
        // The special numbers are times or dimensionless: the surface scaling leaves them alone.
        val floor = DuelParams.forSurface(kz.rudy.ar.core.domain.Surface.FLOOR, 2f)
        assertEquals(
            listOf(DuelParams.DEFAULT.specialCooldownMs, DuelParams.DEFAULT.liftMs, DuelParams.DEFAULT.dodgeMs, DuelParams.DEFAULT.counterMs),
            listOf(floor.specialCooldownMs, floor.liftMs, floor.dodgeMs, floor.counterMs),
        )
        assertEquals(
            listOf(DuelParams.DEFAULT.stompRadiusRadii, DuelParams.DEFAULT.liftHeightRadii, DuelParams.DEFAULT.stompScale, DuelParams.DEFAULT.ramDashScale),
            listOf(floor.stompRadiusRadii, floor.liftHeightRadii, floor.stompScale, floor.ramDashScale),
        )
    }
}
