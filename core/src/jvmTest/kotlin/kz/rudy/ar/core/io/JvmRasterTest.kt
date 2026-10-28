package kz.rudy.ar.core.io

import kz.rudy.ar.core.meta.MaskResult
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
        // Background decodes as transparent WHITE (a model dropping alpha sees white, not black), also after a downscale.
        assertAllTransparentAreWhite(m)
        val small = assertNotNull(JvmRaster.decode(JvmRaster.downscale(png, 512)))
        assertEquals(512, maxOf(small.width, small.height))
        assertAllTransparentAreWhite(small)
        // The undecodable / degenerate cases return null.
        assertNull(JvmRaster.matte(byteArrayOf(1, 2, 3)))
        assertNull(JvmRaster.matte(JvmRaster.encodePng(ArrayRaster.solid(16, 16, argbOf(255, 255, 255, 255)))))
    }

    private fun assertAllTransparentAreWhite(r: RasterImage) {
        var transparent = 0
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val p = r.argb(x, y)
            if (p.alpha == 0) {
                transparent++
                assertEquals(SpriteMatte.TRANSPARENT_WHITE, p, "($x,$y)")
            }
        }
        assertTrue(transparent > 0, "has a transparent background")
    }

    @Test
    fun cutoutBackgroundIsTransparentWhite() {
        val photo = JvmRaster.encodePng(ArrayRaster.solid(4, 4, argbOf(255, 30, 120, 30)))
        // An L-shaped mask inside a 3x3 box, so the cropped cutout keeps background pixels.
        val mask = MaskResult.fromBits("drawing", 4, 4, BooleanArray(16) { i -> i in setOf(5, 6, 7, 9, 13) })
        val m = assertNotNull(JvmRaster.decode(JvmRaster.cutout(photo, mask)))
        assertEquals(3, m.width)
        assertEquals(3, m.height)
        assertEquals(argbOf(255, 30, 120, 30), m.argb(0, 0))
        assertAllTransparentAreWhite(m)
    }

    @Test
    fun exifOrientationIsAppliedAndUprightReencodes() {
        // 16x8 JPEG: left half red, right half blue.
        val img = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 8) for (x in 0 until 16) img.setRGB(x, y, if (x < 8) 0xFF0000 else 0x0000FF)
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        assertEquals(1, JvmRaster.exifOrientation(plain))
        assertContentEquals(plain, JvmRaster.upright(plain), "no EXIF: unchanged")

        for (bigEndian in listOf(true, false)) {
            val rotated = withExifOrientation(plain, 6, bigEndian) // "rotate 90 CW to display"
            assertEquals(6, JvmRaster.exifOrientation(rotated))
            val d = assertNotNull(JvmRaster.decode(rotated))
            assertEquals(8, d.width)
            assertEquals(16, d.height)
            // Rotated clockwise: the left (red) half is now on top, the blue half at the bottom.
            assertTrue(d.argb(4, 3).red > 200 && d.argb(4, 3).blue < 60, "top is red")
            assertTrue(d.argb(4, 12).blue > 200 && d.argb(4, 12).red < 60, "bottom is blue")

            val up = JvmRaster.upright(rotated)
            assertEquals(1, JvmRaster.exifOrientation(up), "re-encoded without the tag")
            val u = assertNotNull(JvmRaster.decode(up))
            assertEquals(8 to 16, u.width to u.height)
            assertTrue(u.argb(4, 3).red > 200, "upright copy keeps the orientation")
            // Downscale agrees with decode.
            val ds = assertNotNull(JvmRaster.decode(JvmRaster.downscale(rotated, 8)))
            assertEquals(4 to 8, ds.width to ds.height)
        }
        assertEquals(3, JvmRaster.exifOrientation(withExifOrientation(plain, 3, bigEndian = false)))
        val flipped = assertNotNull(JvmRaster.decode(withExifOrientation(plain, 3, bigEndian = true)))
        assertEquals(16 to 8, flipped.width to flipped.height)
        assertTrue(flipped.argb(2, 4).blue > 200, "rotated 180: blue on the left")

        // PNG, garbage, truncated Exif: orientation 1, unchanged bytes.
        val png = JvmRaster.encodePng(ArrayRaster.solid(2, 2, -1))
        assertContentEquals(png, JvmRaster.upright(png))
        assertEquals(1, JvmRaster.exifOrientation(byteArrayOf(1, 2, 3)))
        val truncated = withExifOrientation(plain, 6, bigEndian = true).copyOf(30)
        assertEquals(1, JvmRaster.exifOrientation(truncated))
    }

    /** Inserts a minimal APP1 Exif segment (IFD0 with one SHORT Orientation entry) after the JFIF APP0 segment. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int, bigEndian: Boolean): ByteArray {
        fun u16(v: Int) = if (bigEndian) listOf(v shr 8, v and 0xFF) else listOf(v and 0xFF, v shr 8)
        fun u32(v: Int) = if (bigEndian) u16(v shr 16) + u16(v and 0xFFFF) else u16(v and 0xFFFF) + u16(v shr 16)
        val tiff = (if (bigEndian) listOf(0x4D, 0x4D) else listOf(0x49, 0x49)) + u16(0x2A) + u32(8) +
            u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        val payload = "Exif".map { it.code } + listOf(0, 0) + tiff
        val len = payload.size + 2
        val segment = (listOf(0xFF, 0xE1, len shr 8, len and 0xFF) + payload).map { it.toByte() }.toByteArray()
        // SOI, then APP0 (FFE0 + length) if present, then our APP1.
        val at = if ((jpeg[3].toInt() and 0xFF) == 0xE0) 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)) else 2
        return jpeg.copyOfRange(0, at) + segment + jpeg.copyOfRange(at, jpeg.size)
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
