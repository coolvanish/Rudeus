package kz.rudy.ar.core.io

import kz.rudy.ar.core.pipeline.SpriteMatte
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRasterTest {
    /** The real Muse Image reply for drawing-01 (1408x1536, opaque white background, black outline, pale-pink face). */
    private fun museOutput(): ByteArray {
        val dir = listOf(".", "..", "../..").map { File(it, "docs/api-samples/muse-stylize") }.first { it.isDirectory }
        return File(dir, "drawing-01.out-1.png").readBytes()
    }

    @Test
    fun matteCutsTheRealMuseBackground() {
        val src = assertNotNull(JvmRaster.decode(museOutput()))
        val result = SpriteMatte.matte(src)
        assertTrue(result.ok, "opaque fraction ${result.opaqueFraction}")
        assertTrue(result.opaqueFraction in 0.10f..0.80f, "opaque fraction ${result.opaqueFraction}")

        val png = assertNotNull(JvmRaster.matte(museOutput()))
        val m = assertNotNull(JvmRaster.decode(png))
        assertTrue(m.width < src.width || m.height < src.height, "cropped to the character: ${m.width}x${m.height}")
        for ((x, y) in listOf(0 to 0, m.width - 1 to 0, 0 to m.height - 1, m.width - 1 to m.height - 1)) {
            assertTrue(m.argb(x, y).alpha < 128, "corner ($x,$y) transparent")
        }
        // The centre of the character (inside the outline) stays opaque.
        assertEquals(255, m.argb(m.width / 2, m.height / 2).alpha)
        // The undecodable / degenerate cases return null.
        assertNull(JvmRaster.matte(byteArrayOf(1, 2, 3)))
        assertNull(JvmRaster.matte(JvmRaster.encodePng(ArrayRaster.solid(16, 16, argbOf(255, 255, 255, 255)))))
    }

    @Test
    fun downscaleKeepsAspectAndNeverUpscales() {
        val wide = JvmRaster.encodePng(ArrayRaster.solid(300, 100, argbOf(128, 200, 40, 40)))
        val small = assertNotNull(JvmRaster.decode(JvmRaster.downscale(wide, 60)))
        assertEquals(60, small.width)
        assertEquals(20, small.height)
        assertEquals(128, small.argb(30, 10).alpha, "PNG keeps alpha")

        val tall = assertNotNull(JvmRaster.decode(JvmRaster.downscale(JvmRaster.encodePng(ArrayRaster.solid(90, 1000, -1)), 512)))
        assertEquals(46, tall.width)
        assertEquals(512, tall.height)

        // Already within the bound (or exactly on it): bytes unchanged, never upscaled.
        assertContentEquals(wide, JvmRaster.downscale(wide, 300))
        assertContentEquals(wide, JvmRaster.downscale(wide, 4096))
        // Undecodable input passes through.
        assertContentEquals(byteArrayOf(9, 9), JvmRaster.downscale(byteArrayOf(9, 9), 10))

        // JPEG stays JPEG.
        val img = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        val jpegIn = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        val jpeg = JvmRaster.downscale(jpegIn, 400)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val decoded = assertNotNull(JvmRaster.decode(jpeg))
        assertEquals(400, decoded.width)
        assertEquals(300, decoded.height)

        // The real 1408x1536 Muse output fits 1024 on its long side.
        val muse = assertNotNull(JvmRaster.decode(JvmRaster.downscale(museOutput(), 1024)))
        assertEquals(1024, muse.height)
        assertEquals(939, muse.width)
    }
}
