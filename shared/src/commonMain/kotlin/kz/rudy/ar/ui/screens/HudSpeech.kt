package kz.rudy.ar.ui.screens

import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.StringId

/**
 * What the character says when something happens (DESIGN.md "Play"). Pure: App feeds every
 * `ArSlot.onCharacterEvent` through [next] and shows [State.text] in the speech bubble.
 *
 * Mapping: PLACED -> sheet line 0; JUMPED -> sheet line 1; BLOCKED / FELL / RESPAWNED -> the copy-table lines
 * `blocked` / `fell` / `respawn`; every 3rd ARRIVED -> sheet line `arrivals % lines.size` (deterministic, so the same
 * walk always says the same thing); STARTED_WALKING, LANDED, STEPPED_UP, PAUSED, RESUMED -> nothing.
 * Bubbles are at least [MIN_GAP_MS] apart, except FELL and RESPAWNED, which always replace the current one.
 */
object HudSpeech {
    const val MIN_GAP_MS = 2500L
    const val ARRIVALS_PER_LINE = 3

    data class State(
        /** The line on screen (already in the player's language); null = no bubble. */
        val text: String? = null,
        /** When the current bubble appeared; null = never. */
        val shownAtMs: Long? = null,
        /** ARRIVED events seen so far (including ones that did not speak). */
        val arrivals: Int = 0,
        /** Bumps on every new bubble, so the caller can restart the dismiss timer even when the text repeats. */
        val serial: Int = 0,
    )

    /**
     * The line [event] would say with the sheet's [lines] (already resolved for [lang]), or null when the event is
     * silent or the sheet has no such line. [arrivals] is the arrival count used for ARRIVED.
     */
    fun lineFor(event: CharacterEvent, lines: List<String>, lang: Lang, arrivals: Int = 0): String? = when (event) {
        CharacterEvent.PLACED -> lines.firstOrNull()
        CharacterEvent.JUMPED -> lines.getOrNull(1) ?: lines.firstOrNull()
        CharacterEvent.BLOCKED -> StringId.blocked.get(lang)
        CharacterEvent.FELL -> StringId.fell.get(lang)
        CharacterEvent.RESPAWNED -> StringId.respawn.get(lang)
        CharacterEvent.ARRIVED -> if (lines.isEmpty()) null else lines[arrivals % lines.size]
        CharacterEvent.STARTED_WALKING, CharacterEvent.LANDED, CharacterEvent.STEPPED_UP,
        CharacterEvent.PAUSED, CharacterEvent.RESUMED -> null
    }

    /** Applies [event] at [nowMs]; returns [state] with a new bubble, or unchanged apart from the arrival count. */
    fun next(state: State, event: CharacterEvent, nowMs: Long, lines: List<String>, lang: Lang): State {
        val arrivals = if (event == CharacterEvent.ARRIVED) state.arrivals + 1 else state.arrivals
        val counted = state.copy(arrivals = arrivals)
        if (event == CharacterEvent.ARRIVED && arrivals % ARRIVALS_PER_LINE != 0) return counted
        val line = lineFor(event, lines, lang, arrivals) ?: return counted
        val overrides = event == CharacterEvent.FELL || event == CharacterEvent.RESPAWNED
        if (!overrides && !gapElapsed(state, nowMs)) return counted
        return counted.copy(text = line, shownAtMs = nowMs, serial = state.serial + 1)
    }

    /** A bubble the child asked for (the ability chip): shown now, and it holds the gap like any other. */
    fun say(state: State, text: String, nowMs: Long): State =
        state.copy(text = text, shownAtMs = nowMs, serial = state.serial + 1)

    /** The bubble timed out (the caller's dismiss timer); the gap still counts from [State.shownAtMs]. */
    fun dismissed(state: State): State = state.copy(text = null)

    private fun gapElapsed(state: State, nowMs: Long): Boolean {
        val at = state.shownAtMs ?: return true
        return nowMs - at >= MIN_GAP_MS
    }
}
