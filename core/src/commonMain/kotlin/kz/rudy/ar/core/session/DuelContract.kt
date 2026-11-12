package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.duel.BotLevel
import kz.rudy.ar.core.duel.DuelMatchState
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelRules

// T58 / T59 contract (commit K): what the UI asks of the table engine for a duel and what it hears back. Inert until
// T58's duel stage reads it; T59 fills `TableSlot.duel` and draws from `DuelReport`.

/** The duel the UI wants on the table; `TableSlot.duel == null` is the walking game. Immutable, one per slot. */
class DuelSetup(
    /**
     * Rising; a serial the engine has not seen starts a new match, a seen one never restarts. The engine takes a serial
     * on the first tracked frame it sees it, and ignores one at or below the highest it has taken.
     */
    val serial: Int,
    /** The child's fighter: a member id. */
    val mineId: String,
    /** The opponent: another member id, played by DuelBot. */
    val foeId: String,
    val foeLevel: BotLevel,
    /** The bots' PRNG: `2 * seed + 1` for the opponent, `2 * seed` for mine when it is a bot. */
    val seed: Long,
    /** "Watch": my side is a bot too; taps and commands then do nothing. */
    val mineBot: BotLevel? = null,
    /** Read on every frame: while true a COUNTDOWN's clock does not run. */
    val hold: Boolean = false,
)

/** What the engine tells the slot about the setup with [serial]. */
sealed interface DuelReport {
    val serial: Int

    /** The match as it stands, with everything `duelHud(...)` needs to draw it. */
    class Running(
        override val serial: Int,
        val state: DuelMatchState,
        val arena: Level,
        val rules: DuelRules,
        val params: DuelParams,
    ) : DuelReport

    /** The match did not start, or ended before RESULT; the table is the walking game's again. */
    class Refused(override val serial: Int, val reason: DuelRefusal) : DuelReport
}

/**
 * Why a duel did not start or ended before RESULT. NO_BOUNDARY also covers a boundary with a non-finite vertex; FAILED:
 * the duel's own code threw inside a frame, a tap or a command (T58 review), and the table went back to the walking game.
 */
enum class DuelRefusal { NO_BOUNDARY, NO_ROOM, NO_FIGHTER, FAILED }
