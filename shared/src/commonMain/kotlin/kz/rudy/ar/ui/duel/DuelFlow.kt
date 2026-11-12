package kz.rudy.ar.ui.duel

import kz.rudy.ar.ArCharacter
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.duel.BotLevel
import kz.rudy.ar.core.physics.Cast
import kz.rudy.ar.core.session.DuelReport
import kz.rudy.ar.core.session.DuelSetup
import kz.rudy.ar.ui.screens.BundledCharacter

/** Where the duel flow is: the opponent picker, the VS card, or the match on the table (its RESULT included). */
enum class DuelStep { PICK, VERSUS, FIGHT }

/**
 * T59: the duel flow over Play; `null` in App = free play. RESULT is FIGHT whose report says `MatchPhase.RESULT`: the
 * flow decides which screen shows, never who won (the engine's match does).
 */
data class DuelUi(
    val step: DuelStep = DuelStep.PICK,
    /** The picked opponent's card id; null until a card is tapped. */
    val foeId: String? = null,
    /** NORMAL first: the level a child meets first (`ai/v2-human.md` §4.3). */
    val difficulty: DuelDifficulty = DuelDifficulty.NORMAL,
    /** Watch: both sides are bots at [difficulty]; the child's buttons stay grey. */
    val watch: Boolean = false,
)

/** T59: the pure rules of the duel flow (no Compose, tested on the JVM). DESIGN.md "Duel". */
object DuelFlow {
    /** How long the VS card stays when nobody taps it. */
    const val VERSUS_MS = 2500L

    /** The bot level of the same name. */
    fun botLevel(d: DuelDifficulty): BotLevel = when (d) {
        DuelDifficulty.EASY -> BotLevel.EASY
        DuelDifficulty.NORMAL -> BotLevel.NORMAL
        DuelDifficulty.HARD -> BotLevel.HARD
    }

    /** A duel needs a scanned edge: a level with a boundary (the engine refuses one without, so no floor duel yet). */
    fun canDuel(level: Level?): Boolean = level?.boundary != null

    /** [saved] then [bundled], each id once, without [mineId] and [alsoMine] (the saved card of my photo). */
    fun opponents(
        saved: List<BundledCharacter>,
        bundled: List<BundledCharacter>,
        mineId: String,
        alsoMine: Set<String> = emptySet(),
    ): List<BundledCharacter> = (saved + bundled).distinctBy { it.id }.filter { it.id != mineId && it.id !in alsoMine }

    /**
     * A cast member as a shelf card, for the VS card: its id, [sheet], sprite, emotes, frames. A photo member has no
     * shelf card of its own until its save lands, so the VS card draws it from the cast.
     */
    fun card(member: ArCharacter, sheet: CharacterSheet): BundledCharacter = BundledCharacter(
        id = member.id,
        sheet = sheet,
        spritePng = member.spritePng,
        emotes = member.emotes,
        frames = member.frames,
    )

    /**
     * [cast] with [foe] on it: unchanged (the same instance) when it is; appended when there is room (`Cast.MAX`); else
     * in the place of the last member that is not [mineId]. The cast is not restored after the duel.
     */
    fun castFor(cast: List<ArCharacter>, mineId: String, foe: ArCharacter): List<ArCharacter> {
        if (cast.any { it.id == foe.id }) return cast
        if (cast.size < Cast.MAX) return cast + foe
        val seat = cast.indexOfLast { it.id != mineId }
        if (seat < 0) return cast // a full table of mine alone cannot happen (ids are unique, Cast.MAX > 1)
        return cast.toMutableList().also { it[seat] = foe }
    }

    /**
     * What the slot carries: null unless [ui] is at FIGHT with a foe and [mineId] is known (and is not the foe). Watch =
     * `mineBot` at the same level as the opponent. [hold]: a countdown waits while true (a scan runs between rounds).
     */
    fun setup(ui: DuelUi?, serial: Int, mineId: String?, seed: Long, hold: Boolean = false): DuelSetup? {
        if (ui == null || ui.step != DuelStep.FIGHT) return null
        val foeId = ui.foeId ?: return null
        if (mineId == null || mineId == foeId) return null
        val level = botLevel(ui.difficulty)
        return DuelSetup(
            serial = serial,
            mineId = mineId,
            foeId = foeId,
            foeLevel = level,
            seed = seed,
            mineBot = if (ui.watch) level else null,
            hold = hold,
        )
    }

    /**
     * `duelHud` of a running report with its own arena, rules and params; null for no report, a refusal, or a report
     * [mineId] is not in (`duelHud` would throw). [watch]: Dash, Jump and Special are not enabled; the rest is as is.
     */
    fun hud(report: DuelReport?, mineId: String, watch: Boolean, nameOf: (String) -> String): DuelHudModel? {
        val running = report as? DuelReport.Running ?: return null
        val duel = running.state.duel
        if (mineId != duel.a.id && mineId != duel.b.id) return null
        val model = duelHud(running.state, mineId, running.arena, nameOf, running.rules, running.params)
        if (!watch) return model
        return model.copy(
            dash = model.dash.copy(enabled = false),
            jump = model.jump.copy(enabled = false),
            special = model.special?.copy(enabled = false),
        )
    }
}
