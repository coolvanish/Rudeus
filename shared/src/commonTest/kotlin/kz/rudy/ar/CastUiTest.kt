package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterSheet
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.physics.Cast
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.components.CastUi
import kz.rudy.ar.ui.screens.BundledCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CastUiTest {
    private fun card(id: String) = BundledCharacter(
        id,
        CharacterSheet(name = "kk-$id", personality = "", ability = Ability.JUMP, lines = emptyMap(), names = mapOf("en" to "en-$id")),
    )

    private val saved = listOf(card("saved-2"), card("saved-1"))
    private val bundled = listOf(card("murat"), card("koshka"), card("saved-1"))

    private fun castOf(vararg ids: String) = ids.map { CastUi.member(card(it), Lang.KK) }

    @Test
    fun addableCharactersHideThoseAlreadyOnTheTable() {
        assertEquals(listOf("saved-2", "saved-1", "murat", "koshka"), CastUi.addable(saved, bundled, emptyList()).map { it.id })
        assertEquals(listOf("saved-2", "koshka"), CastUi.addable(saved, bundled, castOf("murat", "saved-1")).map { it.id })

        // Adding: in order, once each, never past Cast.MAX.
        var cast = castOf("murat")
        cast = CastUi.add(cast, CastUi.member(card("koshka"), Lang.EN))
        assertEquals("en-koshka", cast.last().name)
        assertSame(cast, CastUi.add(cast, CastUi.member(card("koshka"), Lang.EN)))
        while (CastUi.canAdd(cast)) cast = CastUi.add(cast, CastUi.member(card("c${cast.size}"), Lang.KK))
        assertEquals(Cast.MAX, cast.size)
        assertFalse(CastUi.canAdd(cast))
        assertSame(cast, CastUi.add(cast, CastUi.member(card("late"), Lang.KK)))
        assertEquals(listOf("murat", "koshka", "c2", "c3"), cast.map { it.id })

        // The active one: the picked id while it is on the table, else the first.
        assertEquals("koshka", CastUi.active(cast, "koshka")?.id)
        assertEquals("murat", CastUi.active(cast, "gone")?.id)
        assertEquals("murat", CastUi.active(cast, null)?.id)
        assertNull(CastUi.active(emptyList(), "murat"))
        assertTrue(CastUi.addable(emptyList(), listOf(card("murat")), cast).isEmpty())
    }

    @Test
    fun commandSerialRisesPerAction() {
        val actions = listOf(
            "murat" to CharacterAction.Jump,
            "murat" to CharacterAction.Emote(Emote.HELLO),
            "koshka" to CharacterAction.Emote(Emote.HELLO), // same action, other member: still a new serial
            "koshka" to CharacterAction.Emote(Emote.HELLO), // same action again: a new serial too (edge-triggered)
            "murat" to CharacterAction.WalkForward,
        )
        var serial = 0
        val commands = actions.map { (id, action) -> CastUi.next(serial, id, action).also { serial = it.serial } }
        assertEquals(listOf(1, 2, 3, 4, 5), commands.map { it.serial })
        assertEquals(actions, commands.map { it.id to it.action })
        // After a session end App keeps the serial: the first command of the next session is still higher.
        assertEquals(6, CastUi.next(serial, "murat", CharacterAction.Jump).serial)
    }
}
