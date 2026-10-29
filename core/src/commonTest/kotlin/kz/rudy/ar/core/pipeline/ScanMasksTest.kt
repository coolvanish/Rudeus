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

    /** A filled pixel rectangle [x0, x1) x [y0, y1) in a 20 x 10 frame. */
    private fun rect(label: String, x0: Int, y0: Int, x1: Int, y1: Int, score: Float): MaskResult {
        val bits = BooleanArray(20 * 10) { i -> i % 20 in x0 until x1 && i / 20 in y0 until y1 }
        return MaskResult.fromBits(label, 20, 10, bits, score)
    }

    private fun prompt(label: String, vararg instances: MaskResult) =
        SegmentResult(listOf(rect(label, 0, 0, 20, 10, 0.9f).copy(instances = instances.toList())))

    @Test
    fun overlappingDetectionsOfOnePromptBecomeOneMask() {
        val cup = rect("cup", 2, 2, 8, 8, 0.9f) // 36 px
        // A near-duplicate (IoU 30/36 > 0.5) and a part fully inside the cup: both dropped, the best (whole) cup kept.
        val dup = rect("cup", 3, 2, 8, 8, 0.8f)
        val handle = rect("cup", 6, 4, 9, 6, 0.7f) // 6 px, 4 of them inside the cup: IoU 4/38, containment 4/6 = 0.67
        val inside = rect("cup", 3, 3, 5, 5, 0.6f) // fully inside: containment 1.0
        val one = ScanMasks.of(prompt("cup", cup, dup, inside))
        assertEquals(1, one.size)
        assertEquals(36, one.single().mask.count())
        // The handle is under both thresholds (it sticks out): kept as its own small mask.
        assertEquals(2, ScanMasks.of(prompt("cup", cup, handle)).size)

        // Two disjoint detections: two masks, in score order.
        val other = rect("cup", 12, 2, 18, 8, 0.8f)
        val two = ScanMasks.of(prompt("cup", cup, other))
        assertEquals(listOf(36, 36), two.map { it.mask.count() })
        assertEquals(true, two[0].mask[2, 2])
        assertEquals(true, two[1].mask[12, 2])

        // The part scored above the whole: the whole cup takes the part's slot, the far cup stays second.
        val partFirst = ScanMasks.of(prompt("cup", rect("cup", 3, 3, 5, 5, 0.95f), other, cup))
        assertEquals(listOf(36, 36), partFirst.map { it.mask.count() })
        assertEquals(true, partFirst[0].mask[2, 2], "whole cup replaced its part")

        // Overlap is only checked within one prompt: a book on the same pixels as a cup is a separate object.
        val mixed = SegmentResult(listOf(rect("cup", 0, 0, 1, 1, 0.9f).copy(instances = listOf(cup)), rect("book", 0, 0, 1, 1, 0.9f).copy(instances = listOf(rect("book", 2, 2, 8, 8, 0.9f)))))
        assertEquals(listOf("cup", "book"), ScanMasks.of(mixed).map { it.label })
    }
}
