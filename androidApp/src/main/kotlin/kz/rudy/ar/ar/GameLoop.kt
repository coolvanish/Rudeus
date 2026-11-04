package kz.rudy.ar.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Choreography
import kz.rudy.ar.core.emote.EmotePlayer
import kz.rudy.ar.core.emote.EmotePose
import kz.rudy.ar.core.physics.Cast
import kz.rudy.ar.core.physics.CastEvents
import kz.rudy.ar.core.physics.CastPhysics
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
// HAND-REVIEW: T32 — the loop holds a `Cast` (T28) instead of one body: `CastPhysics.step` steps every member with the
// same dt clamp, `CastPhysics.pause` / `resume` on tracking (PAUSED still freezes every member and never resets), and the
// events come from `CastEvents.between(lastReported, cast)` per member. Everything per member (walk phase, landing clock,
// the running emote) lives in `MemberClocks`, keyed by id. `add` / `remove` are the host's cast sync; a new member is
// also put into `lastReported`, so joining yields no event (the host announces PLACED itself). The emote clock is the
// SIMULATED clock (start = simMs at `emote`, sampled at simMs), so a pause freezes a running emote with the physics;
// physics never sees an emote (the poser applies it to the node only). `restartAtSpawn`, `state` and the scenario act
// on the FIRST member only.

/** `landedAtMs` before any landing: far in the past, but `now - NEVER_LANDED` cannot overflow. */
internal const val NEVER_LANDED = Long.MIN_VALUE / 2

/** Frame-to-frame dt is clamped so a hitch (GC, app switch, slow frame) cannot teleport the character. */
internal const val MAX_STEP_DT_MS = 50L

/** HAND-REVIEW: T32 — cut line 4: false steps the cast without `CastPhysics.separate` (members may overlap). */
internal const val SEPARATION_ENABLED = true

/** Host-side clocks of one member (main thread only). */
internal class MemberClocks {
    /** Milliseconds spent in [Mode.WALKING]; drives the walk bob. Never reset (a pause just stops it). */
    var walkPhaseMs: Long = 0L

    /** Host clock (`SystemClock.elapsedRealtime`) of the last [CharacterEvent.LANDED]; [NEVER_LANDED] before any. */
    var landedAtMs: Long = NEVER_LANDED

    /** The running emote and the simulated time it started at; null when none runs. */
    var emote: Choreography? = null
    var emoteStartSimMs: Long = 0L
}

internal class GameLoop(
    /** Empty table until T04 wires SceneBuilder: no objects and `boundary = null` (unbounded, no cliffs). */
    var level: Level = Level(objects = emptyList(), boundary = null, spawn = Vec2.ZERO),
) {
    var cast: Cast = Cast()
        private set

    /** Physics constants of the chosen surface ([configure]); the table defaults until then. */
    var params: PhysicsParams = PhysicsParams.DEFAULT
        private set

    private var profile: SurfaceProfile = SurfaceProfile.TABLE
    private var size: Float = CharacterSize.DEFAULT

    /** Simulated time: milliseconds physics has stepped (clamped dt, tracking only). The scenario and emote clock. */
    var simMs: Long = 0L
        private set

    private var lastStepMs: Long? = null

    /** The cast whose state was last diffed for events; see [onFrame]. */
    private var lastReported: Cast = cast

    private val clocks = HashMap<String, MemberClocks>()

    /** Member ids in cast order (the first is the one Play started with). */
    val ids: List<String> get() = cast.ids

    val firstId: String? get() = cast.members.firstOrNull()?.id

    fun has(id: String): Boolean = cast[id] != null

    fun body(id: String): PhysicsBody? = cast[id]

    fun state(id: String): CharacterState? = cast[id]?.state

    /** The FIRST member's state (scenario, scan and legacy logs); a default state while the cast is empty. */
    val state: CharacterState get() = cast.members.firstOrNull()?.body?.state ?: CharacterState()

    /**
     * Size the physics for [profile] and the player's [size]: walk speed, gravity, arrive epsilon and the collision radius.
     * HAND-REVIEW: T25 — now also called mid-game, on every Bigger / Smaller tap. Only the radius and the horizontal
     * speeds change, so a jump in the air carries on and a paused body stays paused; the next step pushes the bigger
     * circle out of any object.
     */
    fun configure(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT) {
        this.profile = profile
        this.size = size
        params = PhysicsParams.forProfile(profile, size)
        val r = profile.radiusAt(size)
        for (m in cast.members) if (m.body.state.radiusM != r) cast = cast.with(m.id, m.body.copy(state = m.body.state.copy(radiusM = r)))
    }

    /** Collision radius of a member at the configured size. */
    val radiusM: Float get() = profile.radiusAt(size)

    /**
     * Where a new member stands: the first member at [Vec2.ZERO] (as v1.0), later ones at `CastPhysics.spawnNear`
     * [near] (the active member), trying [preferred] (camera-right in plane coords) first.
     */
    fun spawnFor(near: Vec2, preferred: Vec2): Vec2 =
        if (cast.members.isEmpty()) Vec2.ZERO else CastPhysics.spawnNear(level, cast, radiusM, near, preferred, params)

    /** Adds member [id] at [pos], IDLE with [ability]; an id already on the table is left alone. No event: the host says PLACED. */
    fun add(id: String, pos: Vec2, ability: Ability) {
        if (cast[id] != null) return
        val body = PhysicsBody(state = CharacterState(pos = pos, ability = ability, radiusM = radiusM))
        cast = cast.with(id, body)
        lastReported = lastReported.with(id, body)
        clocks[id] = MemberClocks()
    }

    /** Drops member [id] (unknown: no-op). Its node is the host's to detach. */
    fun remove(id: String) {
        cast = cast.without(id)
        lastReported = lastReported.without(id)
        clocks.remove(id)
    }

    /** Later taps and the finger lead: route via :core. Plane coords come from `planeCoords(anchor, hitPose)`. */
    fun walkTo(id: String, target: Vec2) {
        cast = CastPhysics.walkTo(cast, id, target)
    }

    /** The Jump button: `Physics.jump`. Returns false when physics ignored it (ROLL, airborne, paused, respawning, unknown id). */
    fun jump(id: String): Boolean {
        val before = cast
        cast = CastPhysics.jump(cast, id, params)
        return cast !== before
    }

    /** The character sheet's ability; physics reads it on every step (ROLL speed, FLY gravity, jump permission). */
    fun setAbility(id: String, ability: Ability) {
        val b = cast[id] ?: return
        if (b.state.ability != ability) cast = cast.with(id, b.copy(state = b.state.copy(ability = ability)))
    }

    /** Starts [c] on member [id] now (simulated time); a running emote is replaced. Unknown id: no-op. */
    fun emote(id: String, c: Choreography) {
        val k = clocks[id] ?: return
        k.emote = c
        k.emoteStartSimMs = simMs
    }

    /** The running emote's pose for the poser, null when none runs (a finished one is dropped here). */
    fun emotePose(id: String): EmotePose? {
        val k = clocks[id] ?: return null
        val c = k.emote ?: return null
        val t = simMs - k.emoteStartSimMs
        if (EmotePlayer.finished(c, t)) {
            k.emote = null
            return null
        }
        return EmotePlayer.sample(c, t)
    }

    fun walkPhaseMs(id: String): Long = clocks[id]?.walkPhaseMs ?: 0L

    fun landedAtMs(id: String): Long = clocks[id]?.landedAtMs ?: NEVER_LANDED

    /**
     * Debug scenario replay only, FIRST member only: feet at [Level.spawn], h = 0, no target, IDLE, no vertical velocity,
     * ability and radius kept. A paused body stays paused (the restored mode becomes IDLE) so a replay requested under
     * the Paused scrim still waits for tracking. Not a tracking reset.
     */
    fun restartAtSpawn() {
        val first = cast.members.firstOrNull() ?: return
        val s = first.body.state
        val paused = s.mode == Mode.PAUSED
        val body = PhysicsBody(
            state = s.copy(pos = level.spawn, h = 0f, target = null, mode = if (paused) Mode.PAUSED else Mode.IDLE),
            verticalVelocity = 0f,
            modeBeforePause = if (paused) Mode.IDLE else null,
        )
        cast = cast.with(first.id, body)
        // HAND-REVIEW: T21 review fix — the teleport is not a transition: no LANDED / RESPAWNED for the HUD or the poser.
        lastReported = cast
    }

    /**
     * One AR frame at [nowMs] (SystemClock.elapsedRealtime). [tracking] = camera and session anchor both
     * TRACKING. Returns the cast to render. While not tracking every member is paused and the gap is not
     * simulated: the first step after resume uses dt = 0. Every (id, [CharacterEvent]) implied since the previous
     * frame's report (including a tap's walkTo) goes to [onEvent], in cast order, before this returns.
     */
    fun onFrame(
        nowMs: Long,
        tracking: Boolean,
        onEvent: (String, CharacterEvent) -> Unit = { _, _ -> },
        /** HAND-REVIEW: T21 review fix — scripted taps run here, AFTER resume and BEFORE the step, so a Jump due on the first frame back from a pause is not dropped by `canJump`. */
        beforeStep: (() -> Unit)? = null,
    ): Cast {
        if (!tracking) {
            cast = CastPhysics.pause(cast)
            lastStepMs = null
        } else {
            cast = CastPhysics.resume(cast) // no-op unless paused
            beforeStep?.invoke()
            val dt = lastStepMs?.let { (nowMs - it).coerceIn(0L, MAX_STEP_DT_MS) } ?: 0L
            lastStepMs = nowMs
            cast = CastPhysics.step(cast, level, dt, params, separate = SEPARATION_ENABLED)
            simMs += dt
            for (m in cast.members) if (m.body.state.mode == Mode.WALKING) clocks[m.id]?.let { it.walkPhaseMs += dt }
        }
        // PhysicsBody overload (T12): adds BLOCKED on the rising edge of `PhysicsBody.blocked` and per new target.
        if (lastReported !== cast) {
            for ((id, e) in CastEvents.between(lastReported, cast)) {
                if (e == CharacterEvent.LANDED) clocks[id]?.landedAtMs = nowMs
                onEvent(id, e)
            }
            lastReported = cast
        }
        return cast
    }
}
