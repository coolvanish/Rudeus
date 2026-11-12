package kz.rudy.ar.core.session

// T53 — moved here from :shared (ArSlot.kt) unchanged but for the package, so the table engine in this package can
// apply a command without depending on the UI module.

/** T31: what one [CharacterCommand] asks of one character. */
sealed interface CharacterAction {
    data object Jump : CharacterAction
    data object WalkForward : CharacterAction
    data class Emote(val emote: kz.rudy.ar.core.emote.Emote) : CharacterAction
}

/** T31: edge-triggered on [serial] (strictly rising within a session; the host applies each new serial once). */
data class CharacterCommand(val serial: Int, val id: String, val action: CharacterAction)
