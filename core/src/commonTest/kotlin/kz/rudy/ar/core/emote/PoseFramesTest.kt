package kz.rudy.ar.core.emote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoseFramesTest {
    private fun png(n: Int) = byteArrayOf(n.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun fileNamesRoundTrip() {
        assertEquals("hello-1.png", PoseFrames.fileName(Emote.HELLO, 0))
        assertEquals("roar-2.png", PoseFrames.fileName(Emote.ROAR, 1))
        assertEquals(Emote.HELLO to 0, PoseFrames.parseFileName("hello-1.png"))
        assertEquals(Emote.ROAR to 1, PoseFrames.parseFileName("roar-2.png"))
        for (bad in listOf("hello-0.png", "hello-1.jpg", "hello.png", "wave-1.png", "hello-x.png", "-1.png", "hello-1"))
            assertNull(PoseFrames.parseFileName(bad), bad)
        assertEquals("hello-2", PoseSpec(Emote.HELLO, 1).name)
        assertEquals(listOf("hello-1", "hello-2", "roar-1", "roar-2"), PoseSpec.DRAWN.map { it.name })
    }

    @Test
    fun ofBoundsPerEmoteAndPerCharacter() {
        val frames = PoseFrames.of(
            mapOf(
                Emote.ROAR to listOf(png(3), png(4), png(5)),
                Emote.HELLO to listOf(png(1), ByteArray(0), png(2)),
                Emote.DANCE to listOf(png(6)),
            ),
        )
        assertEquals(listOf(Emote.HELLO, Emote.DANCE, Emote.ROAR), frames.byEmote.keys.toList(), "Emote order")
        assertEquals(2, frames.count(Emote.HELLO), "cut at MAX_PER_EMOTE")
        assertEquals(1, frames.count(Emote.DANCE))
        assertEquals(1, frames.count(Emote.ROAR), "the last emote loses to the per-character cap")
        assertEquals(PoseFrames.MAX_PER_CHARACTER, frames.count)
        assertEquals(mapOf(Emote.HELLO to 2, Emote.DANCE to 1, Emote.ROAR to 1), frames.counts())
        assertTrue(frames.frame(Emote.HELLO, 1)!!.contentEquals(png(2)), "an empty byte array is not a frame")
        assertNull(frames.frame(Emote.HELLO, 2))
        assertNull(frames.frame(Emote.HELLO, -1))
        assertNull(frames.frame(Emote.HELLO, null))
        assertNull(frames.frame(Emote.BYE, 0))
        assertTrue(PoseFrames.of(emptyMap()).isEmpty)
        assertEquals(PoseFrames.EMPTY, PoseFrames.of(mapOf(Emote.HELLO to listOf(ByteArray(0)))))
    }

    @Test
    fun filesRoundTripAndGapsEndAList() {
        val frames = PoseFrames.of(mapOf(Emote.HELLO to listOf(png(1), png(2)), Emote.ROAR to listOf(png(3))))
        val files = frames.files()
        assertEquals(setOf("hello-1.png", "hello-2.png", "roar-1.png"), files.keys)
        assertEquals(frames, PoseFrames.fromFiles(files))
        assertEquals(frames, PoseFrames.fromFiles(files + ("sprite.png" to png(9)) + ("roar-3.png" to png(7))), "unknown names and a gap are ignored")
        assertEquals(PoseFrames.of(mapOf(Emote.HELLO to listOf(png(1)))), PoseFrames.fromFiles(mapOf("hello-1.png" to png(1), "hello-3.png" to png(3))))
        assertEquals(PoseFrames.EMPTY, PoseFrames.fromFiles(mapOf("hello-2.png" to png(2))), "no frame 0, no frames")
        assertNotEquals(frames, PoseFrames.of(mapOf(Emote.HELLO to listOf(png(1), png(2)))))
        assertEquals(frames.hashCode(), PoseFrames.fromFiles(files).hashCode())
    }
}
