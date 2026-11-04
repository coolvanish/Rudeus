package kz.rudy.ar.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Choreography
import kz.rudy.ar.core.emote.EmotePlayer
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams

// HAND-REVIEW: T02 — the AR host's physics driver. A plain mutable holder written only from the main thread
// (ARSceneView's onSessionUpdated and the tap handler), never Compose state, so a frame recomposes nothing.
// No game rule lives here: every transition is a Physics.* call from :core. This file only measures dt and
// maps "camera + anchor TRACKING" to Physics.pause / Physics.resume (locked decision: PAUSED freezes, never resets).
// HAND-REVIEW: T14 — (1) `params` and the body's collision radius come from the SurfaceProfile (table or floor);
// `configure` is called from a LaunchedEffect keyed on the locked surface, so it runs before the anchor exists; after
// it only the T25 size re-runs it (the lock lives in RudeusArView; there is no guard here).
// (2) `onFrame` reports the CharacterEvents of everything since the last report (a walkTo from the tap handler, this
// frame's pause/resume/step) — the diff is against `lastReported`, not the previous frame's body, so a transition
// made by the tap handler between two frames is never lost.
// HAND-REVIEW: T21 — `jump` / `setAbility` are the Play HUD's Jump button and the character sheet's ability, main
// thread only, both plain Physics.* calls (ROLL cannot jump, airborne or PAUSED is a no-op: `jump` returns false so
// the host can log `jump_ignored`). `landedAtMs` is the host clock of the last LANDED event, read by CharacterPoser
// for the landing squash; it is recorded from the same event diff the HUD gets, so a landing is never missed.
// `simMs` is the simulated clock: the sum of the clamped dt physics actually stepped while tracking. The scenario
// player runs on it, not on the wall clock, so a tracking stall or a frame hitch cannot push a scripted tap out of its
// beat window. `restartAtSpawn` is the debug scenario replay putting the body back at `level.spawn` (every run starts
// the same); it is NOT the tracking path — PAUSED still freezes and never resets (locked decision, untouched).

/** `landedAtMs` before any landing: far in the past, but `now - NEVER_LANDED` cannot overflow. */
internal const val NEVER_LANDED = Long.MIN_VALUE / 2

/** Frame-to-frame dt is clamped so a hitch (GC, app switch, slow frame) cannot teleport the character. */
internal const val MAX_STEP_DT_MS = 50L

internal class GameLoop(
    /** Empty table until T04 wires SceneBuilder: no objects and `boundary = null` (unbounded, no cliffs). */
    var level: Level = Level(objects = emptyList(), boundary = null, spawn = Vec2.ZERO),
) {
    var body: PhysicsBody = PhysicsBody()
        private set

    /** Physics constants of the chosen surface ([configure]); the table defaults until then. */
    var params: PhysicsParams = PhysicsParams.DEFAULT
        private set

    /** Milliseconds spent in [Mode.WALKING]; drives the walk bob. Never reset (a pause just stops it). */
    var walkPhaseMs: Long = 0L
        private set

    /** Simulated time: milliseconds physics has stepped (clamped dt, tracking only). The scenario clock. */
    var simMs: Long = 0L
        private set

    private var lastStepMs: Long? = null

    /** The body whose state was last diffed for events; see [onFrame]. */
    private var lastReported: PhysicsBody = body

    val state: CharacterState get() = body.state

    /**
     * Size the physics for [profile] and the player's [size]: walk speed, gravity, arrive epsilon and the collision radius.
     * HAND-REVIEW: T25 — now also called mid-game, on every Bigger / Smaller tap. Only the radius and the horizontal
     * speeds change, so a jump in the air carries on and a paused body stays paused; the next step pushes the bigger
     * circle out of any object.
     */
    fun configure(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT) {
        params = PhysicsParams.forProfile(profile, size)
        body = body.copy(state = body.state.copy(radiusM = profile.radiusAt(size)))
    }

    /** Later taps: route via :core. Plane coords come from `planeCoords(anchor, hitPose)`. */
    fun walkTo(target: Vec2) {
        body = Physics.walkTo(body, target)
    }

    /** Host clock (`SystemClock.elapsedRealtime`) of the last [CharacterEvent.LANDED]; [NEVER_LANDED] before any (safe to subtract). */
    var landedAtMs: Long = NEVER_LANDED
        private set

    /** The Jump button: `Physics.jump`. Returns false when physics ignored it (ROLL, airborne, paused, respawning). */
    fun jump(): Boolean {
        val before = body
        body = Physics.jump(body, params)
        return body !== before
    }

    /** The character sheet's ability; physics reads it on every step (ROLL speed, FLY gravity, jump permission). */
    fun setAbility(ability: Ability) {
        if (body.state.ability != ability) body = body.copy(state = body.state.copy(ability = ability))
    }

    // HAND-REVIEW: T33 (the emote half of T32's contract) — the running emote is presentation only: a choreography and
    // its start on the SIMULATED clock, so PAUSED freezes it and never resets it (locked), exactly like the scenario.
    // Physics never reads it; the view samples EmotePlayer on it per frame and hands the pose to CharacterPoser. A new
    // emote replaces the running one; `restartAtSpawn` drops it with the rest of the presentation.
    /** The choreography now playing, null when none; read only through [emoteTimeMs]. */
    var emote: Choreography? = null
        private set
    private var emoteStartSimMs = 0L

    fun playEmote(c: Choreography) {
        emote = c
        emoteStartSimMs = simMs
    }

    /** Milliseconds into [emote] on [simMs], or null when none runs; an emote past its duration is dropped here. */
    fun emoteTimeMs(): Long? {
        val c = emote ?: return null
        val t = simMs - emoteStartSimMs
        if (EmotePlayer.finished(c, t)) {
            emote = null
            return null
        }
        return t
    }

    /**
     * Debug scenario replay only: feet at [Level.spawn], h = 0, no target, IDLE, no vertical velocity, ability and
     * radius kept. A paused body stays paused (the restored mode becomes IDLE) so a replay requested under the Paused
     * scrim still waits for tracking. Not a tracking reset.
     */
    fun restartAtSpawn() {
        val s = body.state
        val paused = s.mode == Mode.PAUSED
        body = PhysicsBody(
            state = s.copy(pos = level.spawn, h = 0f, target = null, mode = if (paused) Mode.PAUSED else Mode.IDLE),
            verticalVelocity = 0f,
            modeBeforePause = if (paused) Mode.IDLE else null,
        )
        // HAND-REVIEW: T21 review fix — the teleport is not a transition: no LANDED / RESPAWNED for the HUD or the poser.
        lastReported = body
        emote = null
    }

    /**
     * One AR frame at [nowMs] (SystemClock.elapsedRealtime). [tracking] = camera and session anchor both
     * TRACKING. Returns the state to render. While not tracking the body is paused and the gap is not
     * simulated: the first step after resume uses dt = 0. Every [CharacterEvent] implied since the previous
     * frame's report (including a tap's walkTo) goes to [onEvent], in order, before this returns.
     */
    fun onFrame(
        nowMs: Long,
        tracking: Boolean,
        onEvent: (CharacterEvent) -> Unit = {},
        /** HAND-REVIEW: T21 review fix — scripted taps run here, AFTER resume and BEFORE the step, so a Jump due on the first frame back from a pause is not dropped by `canJump`. */
        beforeStep: (() -> Unit)? = null,
    ): CharacterState {
        if (!tracking) {
            body = Physics.pause(body)
            lastStepMs = null
        } else {
            body = Physics.resume(body) // no-op unless paused
            beforeStep?.invoke()
            val dt = lastStepMs?.let { (nowMs - it).coerceIn(0L, MAX_STEP_DT_MS) } ?: 0L
            lastStepMs = nowMs
            body = Physics.step(body, level, dt, params)
            simMs += dt
            if (body.state.mode == Mode.WALKING) walkPhaseMs += dt
        }
        // PhysicsBody overload (T12): adds BLOCKED on the rising edge of `PhysicsBody.blocked` and per new target.
        if (lastReported !== body) {
            for (e in CharacterEvents.between(lastReported, body)) {
                if (e == CharacterEvent.LANDED) landedAtMs = nowMs
                onEvent(e)
            }
            lastReported = body
        }
        return body.state
    }
}
