package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmotePose
import kz.rudy.ar.core.physics.PhysicsBody

// T58 — the seam inside the table engine. What every game on the table shares stays the engine's: the anchor, the
// tracking edge, the cast sync, the reappear timer, the posers and NodePose, the cast-state report. What differs between
// the walking game and a duel is a Stage: what a tap and a command mean, how a frame steps, where everyone stands, and
// what the pose loop reads for each member. The walking game is the first stage (TableEngine's `Walk`, its code moved
// there as it was); a duel is the second. The pose loop stays one loop over the walking loop's cast, in the engine.

/** What differs between the walking game and a duel. Main thread, between and inside frames, as the engine. */
internal interface Stage {
    /** May a finger pick, grab and lead members ([CastTouch])? False: every touch stream is left to the tap detector. */
    val fingers: Boolean

    /** A tap on the table at view pixel ([xPx], [yPx]), table point [uv]; after the engine's anchor guard. */
    fun tap(xPx: Float, yPx: Float, uv: Vec2, depthGapM: () -> Float?)

    /** Command [c] from the slot [from] that sent it; after the engine's serial, anchor and membership guards. */
    fun command(c: CharacterCommand, from: TableSlot)

    /** One anchored frame, after the tracking edge and the cast sync: physics, and its events to the slot. */
    fun step(input: FrameInput)

    /** Where every member stands, in cast order (the scan's merge reads it). */
    fun standing(): List<CharacterState>

    /** Member [id] of the loop's cast is drawn; false = posed with `visible = false`. */
    fun shows(id: String): Boolean

    /** The body member [id] is posed from. */
    fun body(id: String): PhysicsBody

    /** Member [id]'s time spent walking (the walk bob). */
    fun walkPhaseMs(id: String): Long

    /** Host clock of member [id]'s last landing (the landing squash); [NEVER_LANDED] before any. */
    fun landedAtMs(id: String): Long

    /** Member [id]'s running emote pose; null when none runs. */
    fun emotePose(id: String): EmotePose?
}
