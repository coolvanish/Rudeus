package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats

/**
 * The match rules of a duel ([DuelMatch]); the physics numbers are in [DuelParams]. Times in ms of simulated time (they
 * run only while the AR session tracks). Every phase runs in whole physics sub-steps: [countdownMs], [roundMs] and
 * [pointPauseMs] are rounded up to a multiple of [DuelParams.subStepMs] when a phase starts (1500 -> 1504 with 8 ms), so
 * every phase starts and ends on the sub-step grid and the round clock and the bodies never disagree.
 */
@Serializable
data class DuelRules(
    /** Points that win the match (2 = best of three). */
    val pointsToWin: Int = 2,
    /** COUNTDOWN before every round. */
    val countdownMs: Long = 3000,
    /** FIGHT: the round clock. At 0 the fighter farther inside the boundary takes the point. */
    val roundMs: Long = 60_000,
    /** POINT: after a point, before the next countdown (the loser's fall plays out). */
    val pointPauseMs: Long = 1500,
    /** Rounds played, replays included; then the match is a draw. */
    val maxRounds: Int = 5,
    /** [DuelMatch.step] clamps `dtMs` to this, so a hitch cannot run hundreds of sub-steps. */
    val maxStepMs: Long = 250,
    /** At the round clock's end, distances to the boundary that differ by no more than this are a tie: a replay. */
    val timeoutTieM: Float = 0.001f,
) {
    init {
        require(pointsToWin >= 1) { "pointsToWin must be >= 1, got $pointsToWin" }
        require(maxRounds >= 1) { "maxRounds must be >= 1, got $maxRounds" }
        require(countdownMs >= 0L) { "countdownMs must be >= 0, got $countdownMs" }
        require(roundMs > 0L) { "roundMs must be > 0, got $roundMs" }
        require(pointPauseMs >= 0L) { "pointPauseMs must be >= 0, got $pointPauseMs" }
        require(maxStepMs > 0L) { "maxStepMs must be > 0, got $maxStepMs" }
        require(timeoutTieM >= 0f) { "timeoutTieM must be >= 0, got $timeoutTieM" } // NaN fails too
    }

    companion object {
        val DEFAULT = DuelRules()
    }
}

/** One side of a duel as the match needs it: who, how strong, which special. */
@Serializable
data class Contestant(val id: String, val stats: FighterStats, val ability: Ability)

/** Where a match is. */
@Serializable
enum class MatchPhase {
    /** Both on their marks, input ignored, the countdown runs. */
    COUNTDOWN,

    /** Inputs go to [DuelPhysics]; the round clock runs. */
    FIGHT,

    /** A point was just decided: input ignored, the bodies finish what they were doing (a fall), nothing can hit. */
    POINT,

    /** The match is over; terminal. */
    RESULT,
}

/** Why a round ended. */
@Serializable
enum class PointReason { RING_OUT, TIMEOUT }

/** How round [round] ended: [winner] took the point, or nobody did (null: the round is replayed). */
@Serializable
data class PointResult(val round: Int, val winner: String?, val reason: PointReason)
