package kz.rudy.ar.core.io

import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.sniffImageMime
import kz.rudy.ar.core.pipeline.SpriteMatte
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * PNG/JPEG decoding and encoding on the JVM via javax.imageio. The Android app supplies its own decoder.
 *
 * ImageIO ignores the EXIF Orientation tag, so phone photos would decode sideways; every function here that reads
 * pixels ([decode], [cutout], [downscale], [upright]) applies the tag itself ([exifOrientation]), so masks, cutouts
 * and downscales agree on one upright frame. Transparent pixels written here are transparent WHITE
 * ([SpriteMatte.TRANSPARENT_WHITE]), so a model that drops alpha still sees a white background.
 */
object JvmRaster {
    /** Decodes PNG/JPEG bytes, upright per EXIF; null when the bytes are not an image ImageIO understands. */
    fun decode(bytes: ByteArray): RasterImage? {
        val img = read(bytes) ?: return null
        val w = img.width; val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        return ArrayRaster(w, h, pixels)
    }

    /** Encodes a raster as PNG (with alpha). */
    fun encodePng(raster: RasterImage): ByteArray {
        val img = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until raster.height) for (x in 0 until raster.width) img.setRGB(x, y, raster.argb(x, y))
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /**
     * Applies [mask] to the photo: pixels outside the mask become transparent white, the result is cropped to the
     * mask bounds and encoded as PNG. Returns the photo unchanged when it cannot be decoded or sizes mismatch.
     */
    fun cutout(imageBytes: ByteArray, mask: MaskResult): ByteArray {
        val src = decode(imageBytes) ?: return imageBytes
        if (src.width != mask.width || src.height != mask.height) return imageBytes
        val bounds = mask.bounds() ?: return imageBytes
        val bits = mask.toBooleanArray()
        val out = ArrayRaster(bounds.w, bounds.h)
        for (y in 0 until bounds.h) for (x in 0 until bounds.w) {
            val sx = bounds.x + x; val sy = bounds.y + y
            val inside = bits[sy * mask.width + sx]
            out.set(x, y, if (inside) src.argb(sx, sy) or (0xFF shl 24) else SpriteMatte.TRANSPARENT_WHITE)
        }
        return encodePng(out)
    }

    /**
     * [SpriteMatte] on a Muse output: white background -> transparent white, cropped, encoded as PNG. Null when the
     * bytes cannot be decoded or the matte is degenerate (the pipeline then falls back to SAM).
     */
    fun matte(png: ByteArray): ByteArray? {
        val src = decode(png) ?: return null
        val result = SpriteMatte.matte(src)
        return result.raster?.let { encodePng(it) }
    }

    /**
     * Scales [bytes] down so the long side is at most [maxSide], keeping the aspect ratio; never upscales (returns the
     * input unchanged when it already fits or cannot be decoded; a fitting JPEG with an EXIF rotation comes back
     * [upright]). PNG stays PNG with alpha (fully transparent pixels stay transparent white); JPEG input stays JPEG.
     * Progressive bilinear halving keeps thin outlines from aliasing.
     */
    fun downscale(bytes: ByteArray, maxSide: Int): ByteArray {
        require(maxSide > 0) { "maxSide must be positive" }
        val src: BufferedImage = read(bytes) ?: return bytes
        val longSide = maxOf(src.width, src.height)
        if (longSide <= maxSide) return upright(bytes)
        val scale = maxSide.toDouble() / longSide
        val tw = maxOf(1, Math.round(src.width * scale).toInt())
        val th = maxOf(1, Math.round(src.height * scale).toInt())
        val jpeg = sniffImageMime(bytes) == "image/jpeg"
        val type = if (jpeg) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        var img: BufferedImage = src
        var w = src.width; var h = src.height
        do {
            w = maxOf(tw, w / 2); h = maxOf(th, h / 2)
            val next = BufferedImage(w, h, type)
            val g = next.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, 0, 0, w, h, null)
            g.dispose()
            img = next
        } while (w != tw || h != th)
        if (!jpeg) whitenTransparent(img)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, if (jpeg) "jpg" else "png", out)
        return out.toByteArray()
    }

    /**
     * A JPEG whose EXIF Orientation (IFD0 tag 0x0112) is 2..8 comes back as an upright re-encoded JPEG (without the
     * tag); anything else (orientation 1 or absent, PNG, undecodable bytes) comes back unchanged. Call it on camera
     * photos before SAM, so the masks are in the frame every other step decodes.
     */
    fun upright(bytes: ByteArray): ByteArray {
        if (sniffImageMime(bytes) != "image/jpeg" || exifOrientation(bytes) == 1) return bytes
        val img = read(bytes) ?: return bytes
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(rgb, "jpg", out)
        return out.toByteArray()
    }

    /**
     * EXIF Orientation of a JPEG (1..8), parsed by hand from the APP1 `Exif\0\0` segment's TIFF IFD0; 1 when the
     * bytes are not a JPEG, carry no Exif segment, or anything is malformed. Never throws.
     */
    fun exifOrientation(bytes: ByteArray): Int {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (bytes.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(i) != 0xFF) return 1
            val marker = u8(i + 1)
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xD9 || marker == 0xDA) return 1 // EOI / start of scan: no Exif before the pixels
            if (marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue } // standalone markers
            val len = u16be(i + 2)
            if (len < 2 || i + 2 + len > bytes.size) return 1
            if (marker == 0xE1 && len >= 8 && bytes.decodeToString(i + 4, i + 10) == "Exif\u0000\u0000") {
                return tiffOrientation(bytes, i + 10, i + 2 + len) ?: 1
            }
            i += 2 + len
        }
        return 1
    }

    /** Orientation from the TIFF structure in `bytes[start until end]`; null when absent or malformed. */
    private fun tiffOrientation(bytes: ByteArray, start: Int, end: Int): Int? {
        if (end - start < 8) return null
        val little = when (bytes.decodeToString(start, start + 2)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = if (little) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int): Long = if (little) {
            u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        } else {
            (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        }
        if (u16(start + 2) != 0x2A) return null
        val ifd = start + u32(start + 4).toInt().also { if (it < 8) return null }
        if (ifd + 2 > end) return null
        val count = u16(ifd)
        for (k in 0 until count) {
            val e = ifd + 2 + 12 * k
            if (e + 12 > end) return null
            if (u16(e) == 0x0112) {
                val type = u16(e + 2)
                val value = if (type == 3) u16(e + 8) else return null // SHORT
                return value.takeIf { it in 1..8 }
            }
        }
        return null
    }

    /** ImageIO read + EXIF orientation; null when the bytes are not an image. */
    private fun read(bytes: ByteArray): BufferedImage? {
        val img: BufferedImage = try { ImageIO.read(ByteArrayInputStream(bytes)) ?: return null } catch (e: Exception) { return null }
        val o = if (sniffImageMime(bytes) == "image/jpeg") exifOrientation(bytes) else 1
        return if (o == 1) img else orient(img, o)
    }

    /** Applies EXIF orientation [o] (2..8): the returned image is what a viewer honouring the tag would show. */
    private fun orient(src: BufferedImage, o: Int): BufferedImage {
        val w = src.width; val h = src.height
        val swap = o >= 5
        val dw = if (swap) h else w
        val dh = if (swap) w else h
        val pixels = IntArray(w * h)
        src.getRGB(0, 0, w, h, pixels, 0, w)
        val out = IntArray(dw * dh)
        for (y in 0 until h) for (x in 0 until w) {
            val (dx, dy) = when (o) {
                2 -> (w - 1 - x) to y // mirror horizontal
                3 -> (w - 1 - x) to (h - 1 - y) // rotate 180
                4 -> x to (h - 1 - y) // mirror vertical
                5 -> y to x // transpose
                6 -> (h - 1 - y) to x // rotate 90 CW
                7 -> (h - 1 - y) to (w - 1 - x) // transverse
                else -> y to (w - 1 - x) // 8: rotate 90 CCW
            }
            out[dy * dw + dx] = pixels[y * w + x]
        }
        val dst = BufferedImage(dw, dh, if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        dst.setRGB(0, 0, dw, dh, out, 0, dw)
        return dst
    }

    /** Java2D resampling stores alpha-0 pixels as transparent black; put them back to transparent white. */
    private fun whitenTransparent(img: BufferedImage) {
        val w = img.width; val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        for (i in pixels.indices) if (pixels[i] ushr 24 == 0) pixels[i] = SpriteMatte.TRANSPARENT_WHITE
        img.setRGB(0, 0, w, h, pixels, 0, w)
    }
}
