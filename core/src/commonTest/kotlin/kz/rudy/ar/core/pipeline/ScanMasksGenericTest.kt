package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.meta.DiskFixtureSource
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.meta.SegmentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Day 10 probe (2026-11-04, `docs/api-samples/sam-table/table3-day10`): 12 prompts on a real table, the generic
 * "small object" last. It found the toy, the remote and the phone again, plus a knife, a speaker and a piece of bread
 * that no named prompt had. [ScanMasks.of] must keep the named masks and only the new "small object" ones.
 */
class ScanMasksGenericTest {
    private val fixture = FixtureApi(DiskFixtureSource.repoRoot(), "docs/api-samples").load(Jobs.SAM_TABLE, "table3-day10")
    private val result = SegmentResult(fixture.masks!!)

    private fun iou(a: BooleanMask, b: BooleanMask): Float {
        var inter = 0
        var union = 0
        for (i in a.bits.indices) {
            if (a.bits[i] && b.bits[i]) inter++
            if (a.bits[i] || b.bits[i]) union++
        }
        return if (union == 0) 0f else inter.toFloat() / union
    }

    /** Share of [a]'s pixels inside [b]. */
    private fun inside(a: BooleanMask, b: BooleanMask): Float {
        var inter = 0
        for (i in a.bits.indices) if (a.bits[i] && b.bits[i]) inter++
        return inter.toFloat() / a.count()
    }

    @Test
    fun smallObjectAndItemAreGenericLabels() {
        assertEquals(setOf("object", "small object", "item"), ScanMasks.GENERIC_LABELS)
        assertEquals("small object", result.masks.last().label, "the probe listed it last")
        assertEquals(6, result.masks.last().instances.size, "six raw detections before dedupe")
    }

    @Test
    fun theDay10ProbeKeepsTheNamedObjectsAndOnlyTheNewSmallObjects() {
        val out = ScanMasks.of(result)
        val labels = out.map { it.label }
        val firstGeneric = labels.indexOfFirst { it.lowercase() in ScanMasks.GENERIC_LABELS }
        assertTrue(firstGeneric > 0, "named objects first: $labels")
        assertTrue(labels.drop(firstGeneric).all { it == "small object" }, "nothing named after the generic ones: $labels")
        for (named in listOf("phone", "toy", "laptop", "remote")) assertTrue(named in labels, "$named kept: $labels")

        val generic = out.drop(firstGeneric)
        assertTrue(generic.size >= 2, "the knife, speaker and bread survive (probe: 3), got ${generic.size}")
        val known = out.take(firstGeneric).filter { it.label in setOf("laptop", "toy", "remote", "phone") }
        for (g in generic) for (k in known) {
            val box = g.mask.boundingBox()
            assertTrue(iou(g.mask, k.mask) <= ScanMasks.MAX_IOU, "small object at $box is the ${k.label} again")
            assertTrue(inside(g.mask, k.mask) <= ScanMasks.MAX_CONTAINMENT, "small object at $box lies inside the ${k.label}")
        }
        // Each dropped "small object" detection really was a named object: the toy, the second remote, the phone.
        val raw = result.masks.last().instances
        assertEquals(3, raw.size - generic.size, "three duplicates of named objects dropped")
        assertFalse(out.any { it.label == "small object" && it.mask.boundingBox()?.minX == 372 }, "the toy is a toy")
    }
}
