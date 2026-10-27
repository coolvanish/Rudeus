package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.alpha
import kz.rudy.ar.core.io.argbOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpriteMatteTest {
    private val white = argbOf(255, 255, 255, 255)
    private val offWhite = argbOf(255, 246, 245, 242)
    private val black = argbOf(255, 12, 12, 12)
    private val palePink = argbOf(255, 250, 222, 222)

    /** 40x40: off-white background, black ring (radius 9..12) centred at (20,20), white/pale-pink inside. */
    private fun ringSprite(): ArrayRaster {
        val r = ArrayRaster.solid(40, 40, offWhite)
        for (y in 0 until 40) for (x in 0 until 40) {
            val dx = x - 20; val dy = y - 20
            val d2 = dx * dx + dy * dy
            when {
                d2 in 81..144 -> r.set(x, y, black)
                d2 < 81 -> r.set(x, y, if (y < 20) white else palePink)
            }
        }
        return r
    }

    @Test
    fun matteRemovesWhiteBackgroundKeepsEnclosedWhite() {
        val result = SpriteMatte.matte(ringSprite())
        assertTrue(result.ok, "fraction=${result.opaqueFraction}")
        val m = assertNotNull(result.raster)
        // Cropped to the ring's bounds (x, y in 8..32 -> 25 px).
        assertEquals(25, m.width)
        assertEquals(25, m.height)
        // Corners of the crop lie outside the ring: background, transparent.
        for ((x, y) in listOf(0 to 0, 24 to 0, 0 to 24, 24 to 24)) {
            assertEquals(SpriteMatte.TRANSPARENT_WHITE, m.argb(x, y), "corner ($x,$y) is transparent WHITE, not black")
        }
        // Centre (white) and lower interior (pale pink) are enclosed: fully opaque, colour kept.
        assertEquals(white, m.argb(12, 8))
        assertEquals(palePink, m.argb(12, 16))
        // The outline stays opaque inside, feathered only on its outer boundary.
        assertEquals(255, m.argb(12, 2).alpha)
        val edge = m.argb(12, 0).alpha
        assertTrue(edge in 1..254, "outer outline pixel is feathered, alpha=$edge")
        // Opaque share of the 40x40 frame: the disc of radius 12 ~ 453 px / 1600.
        assertTrue(result.opaqueFraction in 0.2f..0.35f, "fraction=${result.opaqueFraction}")

        val hard = SpriteMatte.matte(ringSprite(), feather = false)
        assertEquals(255, assertNotNull(hard.raster).argb(12, 0).alpha)
    }

    @Test
    fun matteDegenerateReportsFailure() {
        val allWhite = SpriteMatte.matte(ArrayRaster.solid(32, 32, white))
        assertFalse(allWhite.ok)
        assertNull(allWhite.raster)
        assertEquals(0f, allWhite.opaqueFraction)

        val noWhite = SpriteMatte.matte(ArrayRaster.solid(32, 32, argbOf(255, 200, 40, 40)))
        assertFalse(noWhite.ok)
        assertNull(noWhite.raster)
        assertEquals(1f, noWhite.opaqueFraction)

        // A border of 1 px white around a solid block: 98 % opaque, still degenerate.
        val thinBorder = ArrayRaster.solid(200, 200, white)
        for (y in 1 until 199) for (x in 1 until 199) thinBorder.set(x, y, black)
        assertFalse(SpriteMatte.matte(thinBorder).ok)

        assertFalse(SpriteMatte.matte(ArrayRaster(0, 0)).ok)
    }

    @Test
    fun matteDoesNotLeakThroughDiagonalGapsAndTreatsTransparentAsBackground() {
        // Diagonal 1 px outline (a closed diamond): 4-connected fill must not enter the interior.
        val r = ArrayRaster.solid(21, 21, white)
        for (y in 0 until 21) for (x in 0 until 21) if (kotlin.math.abs(x - 10) + kotlin.math.abs(y - 10) == 7) r.set(x, y, black)
        val m = assertNotNull(SpriteMatte.matte(r, feather = false).raster)
        assertEquals(15, m.width)
        assertEquals(white, m.argb(7, 7), "enclosed white centre stays opaque")

        // Already-transparent input pixels are background too.
        val t = ArrayRaster.solid(20, 20, 0)
        for (y in 5 until 15) for (x in 5 until 15) t.set(x, y, black)
        val tm = SpriteMatte.matte(t, feather = false)
        assertTrue(tm.ok)
        assertEquals(10, assertNotNull(tm.raster).width)
        assertEquals(0.25f, tm.opaqueFraction)
    }
}
