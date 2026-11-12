package kz.rudy.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.duel.BotLevel
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.session.ArPort
import kz.rudy.ar.core.session.CharacterAction
import kz.rudy.ar.core.session.CharacterCommand
import kz.rudy.ar.core.session.DuelRefusal
import kz.rudy.ar.core.session.DuelReport
import kz.rudy.ar.core.session.DuelSetup
import kz.rudy.ar.core.session.ScreenView
import kz.rudy.ar.core.session.TableEngine
import kz.rudy.ar.core.session.TableHit
import kz.rudy.ar.core.session.TableSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// T53: the one pure piece of rememberTableEngine. The engine reads an ArSlot only through this conversion, so what it
// carries, and what it does not, is what the engine can see. T56: the size, the debug flag and the tracking and
// cast-state callbacks too. T54: the active-member and level callbacks. T59: the members' fighter stats, the duel setup
// and its report callback.
class TableSlotTest {

    @Test
    fun tableSlotCarriesTheSlotsFieldsAndCallbacks() {
        val calls = ArrayList<String>()
        val spark = EmoteSet(EmoteSet.SOURCE_SPARK, emptyMap())
        val a = ArCharacter("a", "A", spritePng = null, ability = Ability.FLY, emotes = spark)
        val stats = FighterStats(power = 8, speed = 4, guard = 5, agility = 3)
        val b = ArCharacter("b", "B", spritePng = byteArrayOf(1), ability = Ability.ROLL, emotes = EmoteSet.defaults(), stats = stats)
        val setup = DuelSetup(serial = 3, mineId = "b", foeId = "a", foeLevel = BotLevel.NORMAL, seed = 42L)
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
            onActiveChanged = { calls += "active $it" },
            onLevel = { calls += "level ${it.objects.size}" },
            duel = setup,
            onDuel = { calls += "duel ${it.serial}" },
        )

        // The members are the ones passed in (who the host renders), in that order, not slot.characters.
        val view = slot.toTableSlot(listOf(b, a))
        assertEquals(listOf("b", "a"), view.members.map { it.id })
        assertEquals(listOf(Ability.ROLL, Ability.FLY), view.members.map { it.ability })
        assertSame(spark, view.members[1].emotes)
        // A member's stats reach the engine; none (a v1.0 sheet) = the archetype of its ability.
        assertEquals(listOf(stats, FighterStats.defaultFor(Ability.FLY)), view.members.map { it.stats })
        assertSame(setup, view.duel)
        assertEquals("b", view.activeId)
        assertSame(command, view.command)
        assertFalse(view.soundsOn)
        assertEquals(1.5f, view.characterSize)
        assertTrue(view.debug)
        view.onAnchorPlaced()
        view.onCastEvent("a", CharacterEvent.ARRIVED)
        view.onTracking(false)
        view.onCastState(mapOf("a" to CharacterState()))
        view.onActiveChanged("b")
        view.onLevel(Level(emptyList()))
        view.onDuel(DuelReport.Refused(3, DuelRefusal.NO_ROOM))
        assertEquals(listOf("anchor", "a ARRIVED", "tracking false", "state [a]", "active b", "level 0", "duel 3"), calls)

        // The engine hears the slot's callbacks through it: the anchor, PLACED per member in the given order, then tracking.
        calls.clear()
        val port = object : ArPort { // T54: no tap and no touch here, so nothing to answer
            override fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit? = null
            override fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?> = List(samples) { null }
            override fun screenView(): ScreenView? = null
        }
        val engine = TableEngine(
            { view }, slot.surface, playEmoteSound = {}, port = port, log = {},
            feetDepthBias = 0.6f, reappearAfterTrackingMs = 800L, walkDepthSamples = 6, leadDepthSamples = 2,
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
        assertNull(bare.duel) // free play
        bare.onDuel(DuelReport.Refused(1, DuelRefusal.NO_BOUNDARY)) // a no-op
        bare.onTracking(true) // the slot's defaults: nobody listens
        bare.onCastState(emptyMap())
        bare.onActiveChanged("a")
        bare.onLevel(Level(emptyList()))

        // A TableSlot built without the four T56 fields means "as before T56": size 1x, no debug, nobody listening;
        // without the two T54 fields nobody hears a pick or a grown boundary.
        val old = TableSlot(emptyList(), null, null, true, {}, { _, _ -> })
        assertEquals(CharacterSize.DEFAULT, old.characterSize)
        assertFalse(old.debug)
        old.onTracking(false)
        old.onCastState(emptyMap())
        old.onActiveChanged("a")
        old.onLevel(Level(emptyList()))
    }
}
