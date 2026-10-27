package kz.rudy.ar.core.io

import kz.rudy.ar.core.meta.MaskResult
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** PNG/JPEG decoding and encoding on the JVM via javax.imageio. The Android app supplies its own decoder. */
object JvmRaster {
    /** Decodes PNG/JPEG bytes; null when the bytes are not an image ImageIO understands. */
    fun decode(bytes: ByteArray): RasterImage? {
        val img: BufferedImage = try { ImageIO.read(ByteArrayInputStream(bytes)) ?: return null } catch (e: Exception) { return null }
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
     * Applies [mask] to the photo: pixels outside the mask become transparent, the result is cropped to the
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
            out.set(x, y, if (inside) src.argb(sx, sy) or (0xFF shl 24) else 0)
        }
        return encodePng(out)
    }
}
