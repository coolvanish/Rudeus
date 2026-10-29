package kz.rudy.ar

import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.screens.HudSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudSpeechTest {
    private val lines = listOf("Sälem!", "Sekiremin!", "Oi, kese!", "Kitapqa!", "Qaida?")
    private val lang = Lang.KK

    private fun HudSpeech.State.on(event: CharacterEvent, nowMs: Long) = HudSpeech.next(this, event, nowMs, lines, lang)

    @Test
    fun eventsMapToSheetLinesAndCopy() {
        assertEquals("Sälem!", HudSpeech.lineFor(CharacterEvent.PLACED, lines, lang))
        assertEquals("Sekiremin!", HudSpeech.lineFor(CharacterEvent.JUMPED, lines, lang))
        assertEquals(StringId.blocked.kk, HudSpeech.lineFor(CharacterEvent.BLOCKED, lines, lang))
        assertEquals(StringId.fell.ru, HudSpeech.lineFor(CharacterEvent.FELL, lines, Lang.RU))
        assertEquals(StringId.respawn.kk, HudSpeech.lineFor(CharacterEvent.RESPAWNED, lines, lang))
        for (silent in listOf(
            CharacterEvent.STARTED_WALKING, CharacterEvent.LANDED, CharacterEvent.STEPPED_UP,
            CharacterEvent.PAUSED, CharacterEvent.RESUMED,
        )) {
            assertNull(HudSpeech.lineFor(silent, lines, lang), "$silent must be silent")
            assertEquals(HudSpeech.State(), HudSpeech.State().on(silent, 10_000))
        }
        // A sheet without lines: sheet events are silent, copy events still speak.
        assertNull(HudSpeech.lineFor(CharacterEvent.PLACED, emptyList(), lang))
        assertEquals(StringId.fell.kk, HudSpeech.lineFor(CharacterEvent.FELL, emptyList(), lang))
    }

    @Test
    fun bubblesAreAtLeast2500MsApart() {
        val placed = HudSpeech.State().on(CharacterEvent.PLACED, 1_000)
        assertEquals("Sälem!", placed.text)
        assertEquals(1, placed.serial)

        val tooSoon = placed.on(CharacterEvent.BLOCKED, 1_000 + HudSpeech.MIN_GAP_MS - 1)
        assertEquals("Sälem!", tooSoon.text)
        assertEquals(1, tooSoon.serial)

        val later = tooSoon.on(CharacterEvent.BLOCKED, 1_000 + HudSpeech.MIN_GAP_MS)
        assertEquals(StringId.blocked.kk, later.text)
        assertEquals(2, later.serial)
        assertEquals(1_000 + HudSpeech.MIN_GAP_MS, later.shownAtMs)

        // The gap counts from when the bubble appeared, even after it was dismissed.
        val dismissed = HudSpeech.dismissed(later)
        val jump = dismissed.on(CharacterEvent.JUMPED, 1_000 + HudSpeech.MIN_GAP_MS + 100)
        assertNull(jump.text)
    }

    @Test
    fun fellAndRespawnedAlwaysReplace() {
        val placed = HudSpeech.State().on(CharacterEvent.PLACED, 5_000)
        val fell = placed.on(CharacterEvent.FELL, 5_010)
        assertEquals(StringId.fell.kk, fell.text)
        assertEquals(2, fell.serial)
        // RESPAWNED replaces any bubble that is not a fresh "Oops! Fell" at once.
        val blocked = HudSpeech.State().on(CharacterEvent.BLOCKED, 7_000)
        val back = blocked.on(CharacterEvent.RESPAWNED, 7_020)
        assertEquals(StringId.respawn.kk, back.text)
        assertEquals(2, back.serial)
        // ...and they hold the gap for the next ordinary event.
        assertEquals(back, back.on(CharacterEvent.JUMPED, 7_030))
    }

    @Test
    fun backUpWaitsUntilOopsFellWasReadable() {
        val fell = HudSpeech.State().on(CharacterEvent.FELL, 10_000)
        // A cliff fall respawns ~0.26 s later: "Oops! Fell" stays, "Back up" is queued for the end of the hold.
        val queued = fell.on(CharacterEvent.RESPAWNED, 10_260)
        assertEquals(StringId.fell.kk, queued.text)
        assertEquals(fell.serial, queued.serial)
        assertEquals(StringId.respawn.kk, queued.pending)
        assertEquals(10_000 + HudSpeech.FELL_HOLD_MS, queued.pendingAtMs)

        assertEquals(queued, HudSpeech.tick(queued, 10_000 + HudSpeech.FELL_HOLD_MS - 1))
        val shown = HudSpeech.tick(queued, 10_000 + HudSpeech.FELL_HOLD_MS)
        assertEquals(StringId.respawn.kk, shown.text)
        assertEquals(fell.serial + 1, shown.serial)
        assertNull(shown.pending)

        // After the hold, RESPAWNED replaces "Oops! Fell" directly.
        val late = fell.on(CharacterEvent.RESPAWNED, 10_000 + HudSpeech.FELL_HOLD_MS)
        assertEquals(StringId.respawn.kk, late.text)
        assertNull(late.pending)
        // A second fall while "Back up" is queued drops the stale line.
        assertNull(queued.on(CharacterEvent.FELL, 10_500).pending)
    }

    @Test
    fun everyThirdArrivalSaysTheIndexedSheetLine() {
        var s = HudSpeech.State()
        var t = 0L
        val said = ArrayList<Pair<Int, String?>>()
        repeat(9) {
            t += 10_000 // well past the gap
            val before = s.serial
            s = s.on(CharacterEvent.ARRIVED, t)
            said += s.arrivals to (if (s.serial != before) s.text else null)
        }
        assertEquals(9, s.arrivals)
        assertEquals(
            listOf(3 to lines[3], 6 to lines[6 % lines.size], 9 to lines[9 % lines.size]),
            said.filter { it.second != null },
        )
        // An arrival inside the gap still counts towards the next third.
        val busy = HudSpeech.say(HudSpeech.State(arrivals = 2), "Jump!", 0)
        val counted = busy.on(CharacterEvent.ARRIVED, 100)
        assertEquals(3, counted.arrivals)
        assertEquals("Jump!", counted.text)
    }
}
