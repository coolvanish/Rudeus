package kz.rudy.ar.core.voice

import kz.rudy.ar.core.voice.VoiceCommand.JUMP
import kz.rudy.ar.core.voice.VoiceCommand.WALK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T24: the push-to-talk keyword parser. */
class VoiceCommandsTest {

    @Test
    fun englishJumpAndGo() {
        assertEquals(JUMP, VoiceCommands.parse("jump"))
        assertEquals(JUMP, VoiceCommands.parse("hop"))
        assertEquals(JUMP, VoiceCommands.parse("it jumped"))
        assertEquals(WALK, VoiceCommands.parse("go"))
        assertEquals(WALK, VoiceCommands.parse("walk"))
        assertEquals(WALK, VoiceCommands.parse("run"))
        assertEquals(WALK, VoiceCommands.parse("move forward"))
        // The T01 probe phrase: "jump" comes first.
        assertEquals(JUMP, VoiceCommands.parse("jump over the cup now fly to the book"))
    }

    @Test
    fun russianAndKazakhKeywords() {
        assertEquals(JUMP, VoiceCommands.parse("прыгай"))
        assertEquals(JUMP, VoiceCommands.parse("прыг"))
        assertEquals(JUMP, VoiceCommands.parse("прыжок"))
        assertEquals(JUMP, VoiceCommands.parse("секір"))
        assertEquals(WALK, VoiceCommands.parse("иди"))
        assertEquals(WALK, VoiceCommands.parse("вперёд"))
        assertEquals(WALK, VoiceCommands.parse("вперед"))
        assertEquals(WALK, VoiceCommands.parse("жүр"))
        assertEquals(WALK, VoiceCommands.parse("бар"))
    }

    @Test
    fun firstKeywordWins() {
        assertEquals(WALK, VoiceCommands.parse("go and then jump"))
        assertEquals(JUMP, VoiceCommands.parse("jump then go"))
        assertEquals(WALK, VoiceCommands.parse("иди и прыгай"))
    }

    @Test
    fun punctuationAndCaseIgnored() {
        assertEquals(JUMP, VoiceCommands.parse("JUMP!"))
        assertEquals(JUMP, VoiceCommands.parse("  Jump, jump.  "))
        assertEquals(WALK, VoiceCommands.parse("Go?"))
        assertEquals(WALK, VoiceCommands.parse("\"Иди!\""))
        assertEquals(JUMP, VoiceCommands.parse("Секір!"))
        assertEquals(WALK, VoiceCommands.parse("ok...go"))
    }

    @Test
    fun unknownIsNull() {
        assertNull(VoiceCommands.parse(""))
        assertNull(VoiceCommands.parse("   "))
        assertNull(VoiceCommands.parse("hello there"))
        // Whole words only: a keyword inside another word does not count.
        assertNull(VoiceCommands.parse("good morning"))
        assertNull(VoiceCommands.parse("барлық"))
    }
}
