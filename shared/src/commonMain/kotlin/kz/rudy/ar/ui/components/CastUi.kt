package kz.rudy.ar.ui.components

import kz.rudy.ar.ArCharacter
import kz.rudy.ar.CharacterAction
import kz.rudy.ar.CharacterCommand
import kz.rudy.ar.core.physics.Cast
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.screens.BundledCharacter

/**
 * T31: the pure rules behind the Play cast chips, the Add sheet and the emote row (no Compose, so they are tested on
 * the JVM). The cast is a list in the order the characters joined; the first is the one Play started with.
 */
object CastUi {
    /** [c] as a cast member, its name in [lang]. */
    fun member(c: BundledCharacter, lang: Lang): ArCharacter =
        ArCharacter(id = c.id, name = c.sheet.nameIn(lang.code), spritePng = c.spritePng, ability = c.sheet.ability, emotes = c.emotes)

    /** Room for one more at the table (`Cast.MAX`). */
    fun canAdd(cast: List<ArCharacter>): Boolean = cast.size < Cast.MAX

    /**
     * What the Add sheet lists: [saved] then [bundled], each id once, without those already on the table. [alsoOnTable]
     * = card ids that are on the table under another id (the saved card of the photo member "photo-<ref>").
     */
    fun addable(
        saved: List<BundledCharacter>,
        bundled: List<BundledCharacter>,
        cast: List<ArCharacter>,
        alsoOnTable: Set<String> = emptySet(),
    ): List<BundledCharacter> {
        val onTable = cast.mapTo(HashSet()) { it.id } + alsoOnTable
        return (saved + bundled).distinctBy { it.id }.filter { it.id !in onTable }
    }

    /** [cast] with [member] at the end; unchanged when it is full or [member] is already on it. */
    fun add(cast: List<ArCharacter>, member: ArCharacter): List<ArCharacter> =
        if (!canAdd(cast) || cast.any { it.id == member.id }) cast else cast + member

    /** The member whose taps and buttons it is: [activeId] when on the table, else the first; null for an empty cast. */
    fun active(cast: List<ArCharacter>, activeId: String?): ArCharacter? =
        cast.firstOrNull { it.id == activeId } ?: cast.firstOrNull()

    /**
     * The command after serial [lastSerial] (0 = none yet): one higher, for [id]. App never lowers the serial, not even
     * when `ArSlot.command` resets to null with the session, so a host can never see an old serial twice.
     */
    fun next(lastSerial: Int, id: String, action: CharacterAction): CharacterCommand =
        CharacterCommand(serial = lastSerial + 1, id = id, action = action)
}
