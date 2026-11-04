package kz.rudy.ar.ui.screens

import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.emote.Choreography
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.StringId

/**
 * What the character says when something happens (DESIGN.md "Play"). Pure: App feeds every
 * `ArSlot.onCharacterEvent` through [next] and shows [State.text] in the speech bubble.
 *
 * Mapping: PLACED -> sheet line 0; JUMPED -> sheet line 1; BLOCKED / FELL / RESPAWNED -> the copy-table lines
 * `blocked` / `fell` / `respawn`; every 3rd ARRIVED -> sheet line `arrivals % lines.size` (deterministic, so the same
 * walk always says the same thing); STARTED_WALKING, LANDED, STEPPED_UP, PAUSED, RESUMED -> nothing.
 * Bubbles are at least [MIN_GAP_MS] apart, except FELL and RESPAWNED, which always replace the current one — but
 * "Back up" never cuts "Oops! Fell" short: a cliff fall respawns in ~0.26 s, so a RESPAWNED within [FELL_HOLD_MS] of
 * the FELL bubble is queued as [State.pending] and shown by [tick] when the hold ends.
 *
 * T31: with two or more characters on the table every line is name-first ("Мұрат: Сәлем!"), so the child knows who
 * spoke: the caller passes [speaker] (null with a cast of one) to [next] and [say]. One bubble for the whole cast; the
 * gap and the arrival count are shared.
 */
object HudSpeech {
    const val MIN_GAP_MS = 2500L
    const val ARRIVALS_PER_LINE = 3
    /** "Oops! Fell" stays at least this long before "Back up" may replace it. */
    const val FELL_HOLD_MS = 1200L

    data class State(
        /** The line on screen (already in the player's language); null = no bubble. */
        val text: String? = null,
        /** When the current bubble appeared; null = never. */
        val shownAtMs: Long? = null,
        /** ARRIVED events seen so far (including ones that did not speak). */
        val arrivals: Int = 0,
        /** Bumps on every new bubble, so the caller can restart the dismiss timer even when the text repeats. */
        val serial: Int = 0,
        /** The event behind the current bubble (null for a [say] line). */
        val event: CharacterEvent? = null,
        /** A line waiting for [pendingAtMs] (only "Back up" after a fresh "Oops! Fell"); [tick] shows it. */
        val pending: String? = null,
        val pendingAtMs: Long? = null,
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

    /** T31: whose name prefixes a line: [name] with a cast of two or more, null (no prefix) with one. */
    fun speaker(name: String, castSize: Int): String? = if (castSize > 1) name else null

    /** T31: [line] as the bubble shows it: "[speaker]: [line]", or [line] alone when [speaker] is null. */
    fun spoken(line: String, speaker: String?): String = if (speaker == null) line else "$speaker: $line"

    /**
     * T31: the line an emote says when it starts: [lines] (the sheet's, already for the language) at
     * [Choreography.line]; the first line when the sheet is shorter; null when it has none.
     */
    fun emoteLine(choreography: Choreography, lines: List<String>): String? =
        lines.getOrNull(choreography.line) ?: lines.firstOrNull()

    /** Applies [event] at [nowMs]; returns [state] with a new bubble, or unchanged apart from the arrival count. */
    fun next(state: State, event: CharacterEvent, nowMs: Long, lines: List<String>, lang: Lang, speaker: String? = null): State {
        val arrivals = if (event == CharacterEvent.ARRIVED) state.arrivals + 1 else state.arrivals
        val counted = state.copy(arrivals = arrivals)
        if (event == CharacterEvent.ARRIVED && arrivals % ARRIVALS_PER_LINE != 0) return counted
        val line = lineFor(event, lines, lang, arrivals)?.let { spoken(it, speaker) } ?: return counted
        val overrides = event == CharacterEvent.FELL || event == CharacterEvent.RESPAWNED
        if (!overrides && !gapElapsed(state, nowMs)) return counted
        if (event == CharacterEvent.RESPAWNED && state.event == CharacterEvent.FELL && state.text != null) {
            val holdEnds = (state.shownAtMs ?: nowMs) + FELL_HOLD_MS
            if (nowMs < holdEnds) return counted.copy(pending = line, pendingAtMs = holdEnds)
        }
        return show(counted, line, nowMs, event)
    }

    /** Shows the [State.pending] line once its time has come; otherwise returns [state] unchanged. */
    fun tick(state: State, nowMs: Long): State {
        val line = state.pending ?: return state
        val at = state.pendingAtMs ?: return state
        if (nowMs < at) return state
        return show(state, line, nowMs, CharacterEvent.RESPAWNED)
    }

    /**
     * T31 review: "Oops! Fell" is up with "Back up" queued: a line the child asks for (an emote, the name pill) is not
     * shown, so it cannot drop the queued line (Fell and Back up always win, DESIGN.md "Play").
     */
    fun holdsFall(state: State): Boolean = state.event == CharacterEvent.FELL && state.pending != null

    /** A bubble the child asked for (the ability chip): shown now, and it holds the gap like any other. */
    fun say(state: State, text: String, nowMs: Long, speaker: String? = null): State = show(state, spoken(text, speaker), nowMs, event = null)

    private fun show(state: State, text: String, nowMs: Long, event: CharacterEvent?): State =
        state.copy(text = text, shownAtMs = nowMs, serial = state.serial + 1, event = event, pending = null, pendingAtMs = null)

    /** The bubble timed out (the caller's dismiss timer); the gap still counts from [State.shownAtMs]. */
    fun dismissed(state: State): State = state.copy(text = null)

    private fun gapElapsed(state: State, nowMs: Long): Boolean {
        val at = state.shownAtMs ?: return true
        return nowMs - at >= MIN_GAP_MS
    }
}
