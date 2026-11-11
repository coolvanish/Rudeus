package kz.rudy.ar.core.emote

import kz.rudy.ar.core.domain.SpriteView
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpriteViewsTest {
    private fun png(n: Int) = byteArrayOf(n.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun spriteViewsNeverStoreTheBase() {
        val v = SpriteViews.of(SpriteView.SIDE, false, mapOf(SpriteView.SIDE to png(1), SpriteView.BACK to png(2), SpriteView.FRONT to ByteArray(0)))
        assertEquals(setOf(SpriteView.BACK), v.drawn.keys, "the base is the sprite; an empty picture is no picture")
        assertEquals(setOf(SpriteView.SIDE, SpriteView.BACK), v.available)
        assertTrue(v.has(SpriteView.SIDE))
        assertTrue(v.has(SpriteView.BACK))
        assertFalse(v.has(SpriteView.FRONT))
        assertNull(v.picture(SpriteView.SIDE))
        assertContentEquals(png(2), v.picture(SpriteView.BACK))

        val front = SpriteViews.fromFiles(SpriteView.FRONT, true, mapOf("view-front.png" to png(1), "view-side.png" to png(3)))
        assertEquals(setOf(SpriteView.SIDE), front.drawn.keys, "a stored file of the base view is dropped on read")
        assertEquals(SpriteView.FRONT, front.base)
        assertTrue(front.baseFacesRight)

        assertSame(SpriteViews.NONE, SpriteViews.of(SpriteView.SIDE, false, emptyMap()))
        assertSame(SpriteViews.NONE, SpriteViews.of(SpriteView.SIDE, false, mapOf(SpriteView.SIDE to png(1))))
        assertEquals(setOf(SpriteView.SIDE), SpriteViews.NONE.available)
        assertFalse(SpriteViews.NONE.baseFacesRight, "today's host constant GENERATED_SPRITE_FACES_RIGHT = false")
        assertNotEquals(SpriteViews.NONE, SpriteViews.of(SpriteView.FRONT, false, emptyMap()))
    }

    @Test
    fun spriteViewsFilesRoundTrip() {
        val v = SpriteViews.of(SpriteView.SIDE, true, mapOf(SpriteView.FRONT to png(1), SpriteView.BACK to png(2)))
        val files = v.files()
        assertEquals(setOf("view-front.png", "view-back.png"), files.keys)
        assertContentEquals(png(2), files.getValue("view-back.png"))
        val back = SpriteViews.fromFiles(SpriteView.SIDE, true, files)
        assertEquals(v, back)
        assertEquals(v.hashCode(), back.hashCode())
        assertNotEquals(v, SpriteViews.fromFiles(SpriteView.SIDE, false, files), "the direction is part of it")
        assertNotEquals(v, SpriteViews.of(SpriteView.SIDE, true, mapOf(SpriteView.FRONT to png(1), SpriteView.BACK to png(9))), "content, not identity")
        assertEquals(emptyMap(), SpriteViews.NONE.files())

        for (view in SpriteView.entries) assertEquals(view, SpriteViews.parseFileName(SpriteViews.fileName(view)))
        assertEquals("view-back.png", SpriteViews.fileName(SpriteView.BACK))
        assertNull(SpriteViews.parseFileName("view-up.png"))
        assertNull(SpriteViews.parseFileName("back.png"))
    }

    @Test
    fun fromFilesIgnoresPoseFrames() {
        val files = mapOf(
            "hello-1.png" to png(1),
            "roar-2.png" to png(2),
            "view-back.png" to png(3),
            "view-left.png" to png(4),
            "sheet.json" to png(5),
        )
        val views = SpriteViews.fromFiles(SpriteView.SIDE, false, files)
        assertEquals(setOf(SpriteView.BACK), views.drawn.keys)
        assertContentEquals(png(3), views.picture(SpriteView.BACK))
        // And the other way round: the frames ignore the views stored in the same folder.
        val frames = PoseFrames.fromFiles(files)
        assertEquals(mapOf(Emote.HELLO to 1), frames.counts(), "roar-2 without roar-1 is not contiguous; view-back is not a frame")
        assertEquals(PoseFrames.EMPTY, PoseFrames.fromFiles(SpriteViews.of(SpriteView.SIDE, false, mapOf(SpriteView.BACK to png(1), SpriteView.FRONT to png(2))).files()))
    }

    @Test
    fun missingAsksForTheOtherTwoBackFirst() {
        assertEquals(listOf(SpriteView.BACK, SpriteView.FRONT), SpriteViews.missing(SpriteView.SIDE))
        assertEquals(listOf(SpriteView.BACK, SpriteView.SIDE), SpriteViews.missing(SpriteView.FRONT))
        assertEquals(listOf(SpriteView.SIDE, SpriteView.FRONT), SpriteViews.missing(SpriteView.BACK))
    }
}
