package kz.rudy.ar.core.domain

import kz.rudy.ar.core.physics.PhysicsBody

/**
 * Things the HUD reacts to (speech bubbles, hints, sounds), derived from consecutive [CharacterState]s so the AR
 * host never interprets physics itself. Produced by [CharacterEvents.between] once per physics step.
 */
enum class CharacterEvent {
    /** The session anchor exists and the character stands on the surface (emitted by the host, not by [CharacterEvents]). */
    PLACED,
    /** IDLE -> WALKING: a walk began (a tap, or an idle state that carries a target). */
    STARTED_WALKING,
    /** WALKING -> IDLE with the target reached (cleared). */
    ARRIVED,
    /**
     * Walking but pushed back by an obstacle: the rising edge of [PhysicsBody.blocked], once per walk target (only the
     * [PhysicsBody] overload of [CharacterEvents.between] can see it).
     */
    BLOCKED,
    /** Feet went up while on the ground (walked onto a platform). */
    STEPPED_UP,
    /** Left the ground in a jump. */
    JUMPED,
    /** JUMPING / FALLING -> back on the ground (IDLE, or WALKING when the walk goes on). */
    LANDED,
    /** Started falling: walked off a platform or an edge, a jump came down over a cliff, or went straight to respawn. */
    FELL,
    /** RESPAWNING -> IDLE at the spawn point. */
    RESPAWNED,
    PAUSED,
    RESUMED,
}

object CharacterEvents {
    /**
     * Events implied by the transition [prev] -> [next] (one physics step). Deterministic; an unchanged state yields
     * nothing. Order: the mode transition (at most one event), then a step up. Never [CharacterEvent.PLACED] or
     * [CharacterEvent.BLOCKED] (the [PhysicsBody] overload adds the latter).
     *
     * Mode transitions: -> PAUSED: PAUSED; PAUSED -> any: RESUMED; -> JUMPING: JUMPED; -> FALLING: FELL;
     * -> RESPAWNING from anything but FALLING (a FLY character that came down over a cliff): FELL;
     * RESPAWNING -> any: RESPAWNED; JUMPING / FALLING -> IDLE or WALKING: LANDED; IDLE -> WALKING: STARTED_WALKING;
     * WALKING -> IDLE with the target cleared: ARRIVED. Anything else (FALLING -> RESPAWNING, IDLE -> IDLE) is silent.
     * STEPPED_UP: `h` rose while both states are on the ground (IDLE / WALKING); never for a jump, a fall or a respawn.
     */
    fun between(prev: CharacterState, next: CharacterState): List<CharacterEvent> {
        if (prev == next) return emptyList()
        val out = ArrayList<CharacterEvent>(2)
        val pm = prev.mode
        val nm = next.mode
        if (pm != nm) {
            val transition = when {
                nm == Mode.PAUSED -> CharacterEvent.PAUSED
                pm == Mode.PAUSED -> CharacterEvent.RESUMED
                nm == Mode.JUMPING -> CharacterEvent.JUMPED
                nm == Mode.FALLING -> CharacterEvent.FELL
                nm == Mode.RESPAWNING -> if (pm == Mode.FALLING) null else CharacterEvent.FELL
                pm == Mode.RESPAWNING -> CharacterEvent.RESPAWNED
                pm == Mode.JUMPING || pm == Mode.FALLING -> CharacterEvent.LANDED
                nm == Mode.WALKING -> CharacterEvent.STARTED_WALKING
                nm == Mode.IDLE && pm == Mode.WALKING && prev.target != null && next.target == null -> CharacterEvent.ARRIVED
                else -> null
            }
            if (transition != null) out += transition
        }
        if (pm.onGround && nm.onGround && next.h > prev.h + Vec2.EPS) out += CharacterEvent.STEPPED_UP
        return out
    }

    /**
     * [between] on the states, plus [CharacterEvent.BLOCKED] (last) on the rising edge of [PhysicsBody.blocked]: once
     * per pinned stretch of one walk, never once per step. A walk to a new target is a new walk: blocked again, it is
     * announced again, also when [prev] was taken before the tap ([prev] blocked toward the old target, [next] toward
     * the new one), so a host that diffs "last reported" against "now" gets it too.
     */
    fun between(prev: PhysicsBody, next: PhysicsBody): List<CharacterEvent> {
        val events = between(prev.state, next.state)
        val sameBlockedWalk = prev.blocked && prev.state.target == next.state.target
        return if (next.blocked && !sameBlockedWalk) events + CharacterEvent.BLOCKED else events
    }

    private val Mode.onGround: Boolean get() = this == Mode.IDLE || this == Mode.WALKING
}
