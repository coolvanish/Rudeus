package kz.rudy.ar.core.domain

/**
 * Things the HUD reacts to (speech bubbles, hints, sounds), derived from consecutive [CharacterState]s so the AR
 * host never interprets physics itself. Produced by [CharacterEvents.between] once per physics step.
 */
enum class CharacterEvent {
    /** The session anchor exists and the character stands on the surface (emitted by the host, not by [CharacterEvents]). */
    PLACED,
    STARTED_WALKING,
    ARRIVED,
    /** Walking but not getting closer to the target: an obstacle is in the way. */
    BLOCKED,
    STEPPED_UP,
    JUMPED,
    LANDED,
    FELL,
    RESPAWNED,
    PAUSED,
    RESUMED,
}

object CharacterEvents {
    /**
     * Events implied by the transition [prev] -> [next] (one physics step). Deterministic; an unchanged state yields
     * nothing. Order: mode transitions first, then height changes.
     */
    fun between(prev: CharacterState, next: CharacterState): List<CharacterEvent> {
        if (prev == next) return emptyList()
        val out = ArrayList<CharacterEvent>(2)
        val pm = prev.mode
        val nm = next.mode
        if (pm != nm) {
            when {
                nm == Mode.PAUSED -> out += CharacterEvent.PAUSED
                pm == Mode.PAUSED -> out += CharacterEvent.RESUMED
                nm == Mode.WALKING -> out += CharacterEvent.STARTED_WALKING
                nm == Mode.JUMPING -> out += CharacterEvent.JUMPED
                nm == Mode.FALLING -> out += CharacterEvent.FELL
                nm == Mode.IDLE && pm == Mode.RESPAWNING -> out += CharacterEvent.RESPAWNED
                nm == Mode.IDLE && pm == Mode.WALKING && prev.target != null && next.target == null -> out += CharacterEvent.ARRIVED
                nm == Mode.IDLE && pm == Mode.JUMPING -> out += CharacterEvent.LANDED
                nm == Mode.WALKING && pm == Mode.JUMPING -> out += CharacterEvent.LANDED
                else -> Unit
            }
        }
        if (pm != Mode.JUMPING && pm != Mode.FALLING && nm != Mode.JUMPING && nm != Mode.FALLING && next.h > prev.h + Vec2.EPS) {
            out += CharacterEvent.STEPPED_UP
        }
        return out
    }
}
