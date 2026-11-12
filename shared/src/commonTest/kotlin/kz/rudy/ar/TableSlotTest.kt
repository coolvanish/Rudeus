package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.session.CharacterAction
import kz.rudy.ar.core.session.CharacterCommand
import kz.rudy.ar.core.session.TableEngine
import kz.rudy.ar.core.session.TableSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// T53: the one pure piece of rememberTableEngine. The engine reads an ArSlot only through this conversion, so what it
// carries, and what it does not, is what the engine can see. T56: the size, the debug flag and the tracking and
// cast-state callbacks too.
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
            debug = true,
            soundsOn = false,
            onAnchorPlaced = { calls += "anchor" },
            onTracking = { calls += "tracking $it" },
            characterSize = 1.5f,
            characters = listOf(a, b),
            activeId = "b",
            command = command,
            onCastEvent = { id, e -> calls += "$id $e" },
            onCastState = { calls += "state ${it.keys}" },
        )

        // The members are the ones passed in (who the host renders), in that order, not slot.characters.
        val view = slot.toTableSlot(listOf(b, a))
        assertEquals(listOf("b", "a"), view.members.map { it.id })
        assertEquals(listOf(Ability.ROLL, Ability.FLY), view.members.map { it.ability })
        assertSame(spark, view.members[1].emotes)
        assertEquals("b", view.activeId)
        assertSame(command, view.command)
        assertFalse(view.soundsOn)
        assertEquals(1.5f, view.characterSize)
        assertTrue(view.debug)
        view.onAnchorPlaced()
        view.onCastEvent("a", CharacterEvent.ARRIVED)
        view.onTracking(false)
        view.onCastState(mapOf("a" to CharacterState()))
        assertEquals(listOf("anchor", "a ARRIVED", "tracking false", "state [a]"), calls)

        // The engine hears the slot's callbacks through it: the anchor, PLACED per member in the given order, then tracking.
        calls.clear()
        val engine = TableEngine(
            { view }, slot.surface, playEmoteSound = {}, onMemberRemoved = {}, log = {},
            feetDepthBias = 0.6f, reappearAfterTrackingMs = 800L,
        )
        engine.anchorPlaced { Vec2(1f, 0f) }
        assertEquals(listOf("anchor", "b PLACED", "a PLACED", "tracking true"), calls)
        assertEquals("b", engine.activeId())

        val bare = ArSlot(spritePng = null, surface = Surface.FLOOR).toTableSlot(emptyList())
        assertEquals(emptyList(), bare.members.map { it.id })
        assertNull(bare.activeId)
        assertNull(bare.command)
        assertTrue(bare.soundsOn)
        assertEquals(CharacterSize.DEFAULT, bare.characterSize)
        assertFalse(bare.debug)
        bare.onTracking(true) // the slot's defaults: nobody listens
        bare.onCastState(emptyMap())

        // A TableSlot built without the four T56 fields means "as before T56": size 1x, no debug, nobody listening.
        val old = TableSlot(emptyList(), null, null, true, {}, { _, _ -> })
        assertEquals(CharacterSize.DEFAULT, old.characterSize)
        assertFalse(old.debug)
        old.onTracking(false)
        old.onCastState(emptyMap())
    }
}
