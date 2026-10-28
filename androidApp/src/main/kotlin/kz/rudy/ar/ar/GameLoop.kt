package kz.rudy.ar.ar

import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody

// HAND-REVIEW: T02 — the AR host's physics driver. A plain mutable holder written only from the main thread
// (ARSceneView's onSessionUpdated and the tap handler), never Compose state, so a frame recomposes nothing.
// No game rule lives here: every transition is a Physics.* call from :core. This file only measures dt and
// maps "camera + anchor TRACKING" to Physics.pause / Physics.resume (locked decision: PAUSED freezes, never resets).

/** Frame-to-frame dt is clamped so a hitch (GC, app switch, slow frame) cannot teleport the character. */
internal const val MAX_STEP_DT_MS = 50L

internal class GameLoop(
    /** Empty table until T04 wires SceneBuilder: no objects and `boundary = null` (unbounded, no cliffs). */
    var level: Level = Level(objects = emptyList(), boundary = null, spawn = Vec2.ZERO),
) {
    var body: PhysicsBody = PhysicsBody()
        private set

    /** Milliseconds spent in [Mode.WALKING]; drives the walk bob. Never reset (a pause just stops it). */
    var walkPhaseMs: Long = 0L
        private set

    private var lastStepMs: Long? = null

    val state: CharacterState get() = body.state

    /** Later taps: route via :core. Plane coords come from `planeCoords(anchor, hitPose)`. */
    fun walkTo(target: Vec2) {
        body = Physics.walkTo(body, target)
    }

    /**
     * One AR frame at [nowMs] (SystemClock.elapsedRealtime). [tracking] = camera and session anchor both
     * TRACKING. Returns the state to render. While not tracking the body is paused and the gap is not
     * simulated: the first step after resume uses dt = 0.
     */
    fun onFrame(nowMs: Long, tracking: Boolean): CharacterState {
        if (!tracking) {
            body = Physics.pause(body)
            lastStepMs = null
            return body.state
        }
        body = Physics.resume(body) // no-op unless paused
        val dt = lastStepMs?.let { (nowMs - it).coerceIn(0L, MAX_STEP_DT_MS) } ?: 0L
        lastStepMs = nowMs
        body = Physics.step(body, level, dt)
        if (body.state.mode == Mode.WALKING) walkPhaseMs += dt
        return body.state
    }
}
