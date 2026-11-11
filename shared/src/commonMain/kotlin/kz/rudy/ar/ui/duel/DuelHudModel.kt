package kz.rudy.ar.ui.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.duel.DuelFighter
import kz.rudy.ar.core.duel.DuelMatchState
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelPhysics
import kz.rudy.ar.core.duel.DuelRules
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.ui.StringId

/** The UI's own difficulty (T55's bot levels are mapped to it in T50). */
enum class DuelDifficulty { EASY, NORMAL, HARD }

/** One fighter as the HUD draws it: [immune] = lifted or dodging (cannot be pushed), [out] = over the edge right now. */
data class SideModel(val id: String, val name: String, val score: Int, val immune: Boolean, val out: Boolean)

/** The one large message in the middle of the HUD; null = none. */
sealed interface DuelBanner {
    /** COUNTDOWN: 3, 2, 1. */
    data class Count(val n: Int) : DuelBanner

    /** The first [GO_MS] of FIGHT. */
    data object Go : DuelBanner

    /** POINT: [mine] true = the player scored, false = the other side did, null = nobody scored (replay). */
    data class Point(val mine: Boolean?) : DuelBanner

    /** RESULT: [won] null = a draw. */
    data class Result(val won: Boolean?) : DuelBanner
}

/** A thumb button: [cooldown] is the fraction still to run, 0 = ready (1 = just used). */
data class ActionButton(val label: StringId, val enabled: Boolean, val cooldown: Float)

/** Everything the duel HUD draws, from the player's side. [special] null = the fighter has no ability. */
data class DuelHudModel(
    val phase: MatchPhase,
    val round: Int,
    val pointsToWin: Int,
    val me: SideModel,
    val foe: SideModel,
    /** FIGHT only: the round's seconds left, rounded up. */
    val clockSeconds: Int?,
    val banner: DuelBanner?,
    val dash: ActionButton,
    val jump: ActionButton,
    val special: ActionButton?,
)

/** How long "Go" stays up after FIGHT begins. */
const val GO_MS = 800L

/** The label of [ability]'s special: stomp (JUMP), lift (FLY), ram (ROLL), dodge (HIDE). */
fun specialLabel(ability: Ability): StringId = when (ability) {
    Ability.JUMP -> StringId.special_stomp
    Ability.FLY -> StringId.special_lift
    Ability.ROLL -> StringId.special_ram
    Ability.HIDE -> StringId.special_dodge
}

/**
 * Pure: everything the HUD draws for the player [myId], from the state alone (no events, no clock). Pass the [rules] and
 * [params] the match runs with: the helpers' defaults are the table's and wrong on the floor. [myId] must be one of the
 * two fighters (a programming error otherwise).
 *
 * Buttons are enabled only in FIGHT and only when the physics would take the input: Dash and Special by
 * [DuelPhysics.canDash] / [DuelPhysics.canSpecial]; Jump when the fighter accepts input, stands on the ground and its
 * body can jump (a ROLL body cannot, `Physics.jump` ignores it).
 */
fun duelHud(
    state: DuelMatchState,
    myId: String,
    arena: Level,
    nameOf: (String) -> String,
    rules: DuelRules = DuelRules(),
    params: DuelParams = DuelParams.DEFAULT,
): DuelHudModel {
    val a = state.duel.a
    val b = state.duel.b
    require(myId == a.id || myId == b.id) { "'$myId' is not in this duel ('${a.id}' vs '${b.id}')" }
    val mine = if (myId == a.id) a else b
    val theirs = if (myId == a.id) b else a
    fun side(f: DuelFighter) = SideModel(
        id = f.id,
        name = nameOf(f.id),
        score = state.scoreOf(f.id),
        immune = DuelPhysics.isImmune(f),
        out = DuelPhysics.isOut(f, arena),
    )

    val fight = state.phase == MatchPhase.FIGHT
    val ground = mine.body.state.mode.let { it == Mode.IDLE || it == Mode.WALKING }
    val canJump = fight && ground && mine.body.state.ability != Ability.ROLL && DuelPhysics.acceptsInput(mine, arena, params)
    return DuelHudModel(
        phase = state.phase,
        round = state.round,
        pointsToWin = rules.pointsToWin,
        me = side(mine),
        foe = side(theirs),
        clockSeconds = if (fight) secondsUp(state.phaseLeftMs) else null,
        banner = banner(state, myId, rules, params),
        dash = ActionButton(StringId.duel_dash, fight && DuelPhysics.canDash(mine, arena, params), DuelPhysics.dashCooldownLeft(mine, params)),
        jump = ActionButton(StringId.play_jump, canJump, 0f),
        special = mine.ability?.let {
            ActionButton(specialLabel(it), fight && DuelPhysics.canSpecial(mine, arena, params), DuelPhysics.specialCooldownLeft(mine, params))
        },
    )
}

/** [ms] in whole seconds, rounded up; never negative. */
private fun secondsUp(ms: Long): Int = if (ms <= 0L) 0 else ((ms + 999L) / 1000L).toInt()

private fun banner(state: DuelMatchState, myId: String, rules: DuelRules, params: DuelParams): DuelBanner? = when (state.phase) {
    MatchPhase.COUNTDOWN -> DuelBanner.Count(secondsUp(state.phaseLeftMs).coerceIn(1, 3))
    MatchPhase.FIGHT -> if (roundLengthMs(rules, params) - state.phaseLeftMs < GO_MS) DuelBanner.Go else null
    MatchPhase.POINT -> DuelBanner.Point(state.lastPoint?.winner?.let { it == myId })
    MatchPhase.RESULT -> DuelBanner.Result(state.winner?.let { it == myId })
}

/** The round clock as the match runs it: [DuelRules.roundMs] rounded up to whole sub-steps. */
private fun roundLengthMs(rules: DuelRules, params: DuelParams): Long {
    val sub = params.subStepMs
    return (rules.roundMs + sub - 1L) / sub * sub
}
