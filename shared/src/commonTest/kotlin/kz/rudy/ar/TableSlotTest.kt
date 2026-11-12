package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.session.CharacterAction
import kz.rudy.ar.core.session.CharacterCommand
import kz.rudy.ar.core.session.TableEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// T53: the one pure piece of rememberTableEngine. The engine reads an ArSlot only through this conversion, so what it
// carries, and what it does not, is what the engine can see.
class TableSlotTest {

    @Test
    fun tableSlotCarriesTheSlotsFieldsAndCallbacks() {
        val calls = ArrayList<String>()
        val spark = EmoteSet(EmoteSet.SOURCE_SPARK, emptyMap())
        val a = ArCharacter("a", "A", spritePng = null, ability = Ability.FLY, emotes = spark)
        val b = ArCharacter("b", "B", spritePng = byteArrayOf(1), ability = Ability.ROLL, emotes = EmoteSet.defaults())
        val command = CharacterCommand(4, "b", CharacterAction.Emote(Emote.DANCE))
        val slot = ArSlot(
            spritePng = null,
            surface = Surface.TABLE,
            soundsOn = false,
            onAnchorPlaced = { calls += "anchor" },
            onTracking = { calls += "tracking $it" }, // not the engine's: the host still calls it
            characters = listOf(a, b),
            activeId = "b",
            command = command,
            onCastEvent = { id, e -> calls += "$id $e" },
        )

        // The members are the ones passed in (who the host renders), in that order, not slot.characters.
        val view = slot.toTableSlot(listOf(b, a))
        assertEquals(listOf("b", "a"), view.members.map { it.id })
        assertEquals(listOf(Ability.ROLL, Ability.FLY), view.members.map { it.ability })
        assertSame(spark, view.members[1].emotes)
        assertEquals("b", view.activeId)
        assertSame(command, view.command)
        assertFalse(view.soundsOn)
        view.onAnchorPlaced()
        view.onCastEvent("a", CharacterEvent.ARRIVED)
        assertEquals(listOf("anchor", "a ARRIVED"), calls)

        // The engine hears the slot's callbacks through it: the anchor, then PLACED per member in the given order.
        calls.clear()
        val engine = TableEngine({ view }, slot.surface, playEmoteSound = {}, onMemberRemoved = {}, log = {})
        engine.anchorPlaced { Vec2(1f, 0f) }
        assertEquals(listOf("anchor", "b PLACED", "a PLACED"), calls)
        assertEquals("b", engine.activeId())

        val bare = ArSlot(spritePng = null, surface = Surface.FLOOR).toTableSlot(emptyList())
        assertEquals(emptyList(), bare.members.map { it.id })
        assertNull(bare.activeId)
        assertNull(bare.command)
        assertTrue(bare.soundsOn)
    }
}
