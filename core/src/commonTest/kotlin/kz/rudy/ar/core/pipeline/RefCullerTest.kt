package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.argbOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefCullerTest {
    private val white = argbOf(255, 255, 255, 255)
    private val ink = argbOf(255, 20, 20, 20)

    /**
     * A synthetic style ref on a white page: an outlined rectangle body in [fill] (outline [outlinePx] thick)
     * and a solid [accent] disc. [variant] moves the shapes so consistent refs differ in subject, not style.
     */
    private fun ref(fill: Int, accent: Int, outlinePx: Int = 4, variant: Int = 0, side: Int = 256): ArrayRaster {
        val img = ArrayRaster.solid(side, side, white)
        val x0 = 40 + variant * 6; val y0 = 50 + variant * 4
        val x1 = x0 + 120 + variant * 3; val y1 = y0 + 110 - variant * 2
        for (y in y0 until y1) for (x in x0 until x1) {
            val edge = x - x0 < outlinePx || x1 - 1 - x < outlinePx || y - y0 < outlinePx || y1 - 1 - y < outlinePx
            img.set(x, y, if (edge) ink else fill)
        }
        val cx = 200 - variant * 5; val cy = 200 - variant * 3; val r = 22 + variant
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) img.set(x, y, accent)
        }
        return img
    }

    /** Warm red body with a yellow accent; [i] nudges the shade and the layout. */
    private fun consistent(i: Int, outlinePx: Int = 4): ArrayRaster =
        ref(argbOf(255, 220 - i * 4, 60 + i * 3, 50), argbOf(255, 240, 200 - i * 2, 40), outlinePx, variant = i)

    private fun consistentSet(n: Int): List<RefCandidate> = (0 until n).map { RefCandidate("ref$it", consistent(it)) }

    /** [img] moved by ([dx], [dy]) on a white page, with every pixel of [from] recoloured to [to]. */
    private fun shifted(img: ArrayRaster, dx: Int, dy: Int, from: Int = 0, to: Int = 0): ArrayRaster {
        val out = ArrayRaster.solid(img.width, img.height, white)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val sx = x - dx; val sy = y - dy
            if (sx !in 0 until img.width || sy !in 0 until img.height) continue
            val p = img.argb(sx, sy)
            out.set(x, y, if (p == from) to else p)
        }
        return out
    }

    /** Rows and columns swapped: the same pixels (palette and outline share) in another silhouette. */
    private fun transposed(img: ArrayRaster): ArrayRaster {
        val out = ArrayRaster(img.height, img.width)
        for (y in 0 until img.height) for (x in 0 until img.width) out.set(y, x, img.argb(x, y))
        return out
    }

    /** A "T": a wide bar on top and a stem under it, both outlined; not symmetric under transposition. */
    private fun tee(): ArrayRaster {
        val img = ArrayRaster.solid(256, 256, white)
        val fill = argbOf(255, 210, 70, 50)
        fun box(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) for (x in x0 until x1) {
                val edge = x - x0 < 4 || x1 - 1 - x < 4 || y - y0 < 4 || y1 - 1 - y < 4
                img.set(x, y, if (edge) ink else fill)
            }
        }
        box(30, 30, 230, 80)
        box(105, 80, 150, 230)
        return img
    }

    private fun reasonOf(result: CullResult, name: String): String =
        result.dropped.single { it.name == name }.reason

    @Test
    fun dropsPaletteOutlier() {
        val blue = RefCandidate("odd", ref(argbOf(255, 40, 90, 220), argbOf(255, 40, 200, 120), variant = 3))
        val result = RefCuller.cull(consistentSet(7) + blue, keep = 7)
        assertEquals(7, result.kept.size)
        assertTrue("odd" !in result.kept)
        assertTrue(reasonOf(result, "odd").startsWith("outlier: mean distance "), reasonOf(result, "odd"))
    }

    @Test
    fun dropsOutlineOutlier() {
        val heavy = RefCandidate("heavy", consistent(3, outlinePx = 28))
        val result = RefCuller.cull(consistentSet(7) + heavy, keep = 7)
        assertEquals(7, result.kept.size)
        assertTrue("heavy" !in result.kept)
        assertTrue(reasonOf(result, "heavy").startsWith("outlier: mean distance "), reasonOf(result, "heavy"))
    }

    @Test
    fun nearDuplicateDropped() {
        // A real copy: moved 1 px and the body fill nudged by 2 in red.
        val fill = argbOf(255, 212, 66, 50) // consistent(2)'s body colour
        val copy = RefCandidate("ref2-copy", shifted(consistent(2), 1, 1, from = fill, to = argbOf(255, 214, 66, 50)))
        val result = RefCuller.cull(consistentSet(5) + copy, keep = 8)
        assertEquals(listOf("ref0", "ref1", "ref2", "ref3", "ref4"), result.kept.sorted())
        assertEquals("near-duplicate of ref2", reasonOf(result, "ref2-copy"))
    }

    @Test
    fun sameStyleDifferentShapeBothKept() {
        // Identical pixel histogram (palette and outline share), different silhouette: both are useful refs.
        val result = RefCuller.cull(listOf(RefCandidate("tee", tee()), RefCandidate("tee-sideways", transposed(tee()))), keep = 2)
        assertEquals(listOf("tee", "tee-sideways"), result.kept.sorted())
        assertTrue(result.dropped.isEmpty(), result.dropped.toString())
    }

    @Test
    fun rejectsTooSmallAndEmpty() {
        val candidates = consistentSet(3) + listOf(
            RefCandidate("tiny", ArrayRaster.solid(200, 400, ink)),
            RefCandidate("blank", ArrayRaster.solid(300, 300, white)),
            RefCandidate("full", ArrayRaster.solid(300, 300, argbOf(255, 200, 60, 50))),
            RefCandidate("ref1", consistent(5)),
            // A too-small "x" first must not knock out the valid "x" after it.
            RefCandidate("x", ArrayRaster.solid(100, 100, ink)),
            RefCandidate("x", consistent(6)),
        )
        val result = RefCuller.cull(candidates, keep = 8)
        assertEquals(listOf("ref0", "ref1", "ref2", "x"), result.kept.sorted())
        assertTrue(reasonOf(result, "tiny").startsWith("too small"), reasonOf(result, "tiny"))
        assertTrue(reasonOf(result, "blank").startsWith("empty"), reasonOf(result, "blank"))
        assertTrue(reasonOf(result, "full").startsWith("no background"), reasonOf(result, "full"))
        assertTrue(reasonOf(result, "x").startsWith("too small"), reasonOf(result, "x"))
        // One of the two valid "ref1"s is kept, the other is a duplicate name.
        assertEquals("duplicate name", reasonOf(result, "ref1"))
    }

    @Test
    fun duplicateNamesDeterministicUnderShuffle() {
        val candidates = consistentSet(4) + listOf(
            RefCandidate("ref1", consistent(6)),
            RefCandidate("ref2", ref(argbOf(255, 40, 90, 220), argbOf(255, 40, 200, 120), variant = 3)),
        )
        val expected = RefCuller.cull(candidates, keep = 3)
        assertEquals(2, expected.dropped.count { it.reason == "duplicate name" })
        val rnd = Random(7)
        repeat(8) {
            val shuffled = RefCuller.cull(candidates.shuffled(rnd), keep = 3)
            assertEquals(expected, shuffled)
        }
    }

    @Test
    fun deterministicUnderShuffle() {
        val candidates = consistentSet(8) + listOf(
            RefCandidate("odd", ref(argbOf(255, 40, 90, 220), argbOf(255, 40, 200, 120), variant = 3)),
            RefCandidate("heavy", consistent(3, outlinePx = 28)),
            RefCandidate("ref5-copy", consistent(5)),
            RefCandidate("blank", ArrayRaster.solid(300, 300, white)),
        )
        val expected = RefCuller.cull(candidates, keep = 6)
        assertEquals(6, expected.kept.size)
        assertEquals(candidates.size - 6, expected.dropped.size)
        val rnd = Random(42)
        repeat(5) {
            val shuffled = RefCuller.cull(candidates.shuffled(rnd), keep = 6)
            assertEquals(expected.kept, shuffled.kept)
            assertEquals(expected.dropped, shuffled.dropped)
        }
    }

    @Test
    fun keepsAllWhenFewerThanKeep() {
        val result = RefCuller.cull(consistentSet(3), keep = 8)
        assertEquals(listOf("ref0", "ref1", "ref2"), result.kept.sorted())
        assertTrue(result.dropped.isEmpty())
    }
}
