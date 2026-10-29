package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.meta.BBox
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.SegmentResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanMasksTest {
    private val w = 6
    private val h = 4

    private fun mask(label: String, vararg px: Pair<Int, Int>, score: Float? = null): MaskResult {
        val bits = BooleanArray(w * h)
        for ((x, y) in px) bits[y * w + x] = true
        return MaskResult.fromBits(label, w, h, bits, score)
    }

    private fun on(lm: LabeledMask): Set<Pair<Int, Int>> =
        lm.mask.bits.withIndex().filter { it.value }.map { (it.index % w) to (it.index / w) }.toSet()

    @Test
    fun oneLabeledMaskPerInstanceAndMergedFallback() {
        val cupA = mask("cup", 0 to 0, 1 to 0, score = 0.9f)
        val cupB = mask("cup", 4 to 3, score = 0.7f)
        val cups = mask("cup", 0 to 0, 1 to 0, 4 to 3, score = 0.9f).copy(instances = listOf(cupA, cupB))
        val book = mask("book", 2 to 1, 3 to 1) // recorded before T15: no instances -> merged mask
        val phone = MaskResult("phone", w, h, bbox = BBox(1, 2, 2, 2)) // bbox only -> filled box
        val bottle = MaskResult("bottle", w, h) // absent
        val hand = mask("hand", 5 to 0) // its request failed: yields nothing even with a mask
        val remote = mask("remote", 3 to 3).copy(instances = listOf(mask("remote", 3 to 3), MaskResult("remote", w, h)))
        val result = SegmentResult(listOf(cups, book, phone, bottle, hand, remote), failedPrompts = listOf("hand"))

        val out = ScanMasks.of(result)
        assertEquals(listOf("cup", "cup", "book", "phone", "remote"), out.map { it.label })
        assertEquals(setOf(0 to 0, 1 to 0), on(out[0]), "best instance first, alone")
        assertEquals(setOf(4 to 3), on(out[1]))
        assertEquals(setOf(2 to 1, 3 to 1), on(out[2]))
        assertEquals(setOf(1 to 2, 2 to 2, 1 to 3, 2 to 3), on(out[3]))
        assertEquals(setOf(3 to 3), on(out[4]), "the empty instance is dropped")
        out.forEach { assertEquals(w to h, it.mask.width to it.mask.height) }
        assertEquals(emptyList(), ScanMasks.of(SegmentResult(emptyList())))
    }
}
