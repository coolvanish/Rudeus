package kz.rudy.ar.capture

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.sniffImageMime
import kz.rudy.ar.core.pipeline.SpriteMatte
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

// HAND-REVIEW: T03 (b) — Android counterparts of core/src/jvmMain/.../io/JvmRaster.kt, wired into CharacterPipeline
// by di/AndroidCaptureHost. Same contract as the JVM: every decode is EXIF-upright (phone photos carry the tag;
// SAM masks, cutouts and downscales must share one pixel grid), transparent pixels are transparent WHITE
// (SpriteMatte.TRANSPARENT_WHITE, so a model that drops alpha sees the white background), downscale keeps the
// aspect and never upscales, JPEG stays JPEG and PNG stays PNG. Bitmaps are decoded premultiplied (alpha-0 pixels
// come back as RGB 0), so every PNG we write goes through encodePng, which whitens them on a non-premultiplied
// Bitmap before compressing. No Compose, no AR types: pure image glue.

/** Decoded ARGB pixels of a [Bitmap], copied once (`getPixels` returns un-premultiplied colours). */
class AndroidRaster(bitmap: Bitmap) : RasterImage {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    private val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

    override fun argb(x: Int, y: Int): Int = pixels[y * width + x]
}

object AndroidImages {
    /** Long-side bound of a photo handed to the pipeline (brief (b)): SAM gets this, the models get downscales. */
    const val PHOTO_MAX_SIDE = 2048

    private const val JPEG_QUALITY = 92

    /** Decodes PNG/JPEG bytes, upright per EXIF; null when they are not an image. */
    fun decode(bytes: ByteArray): RasterImage? = decodeBitmap(bytes)?.let(::AndroidRaster)

    /**
     * Applies [mask] to the photo: pixels outside the mask become transparent white, the result is cropped to the
     * mask bounds and encoded as PNG. Returns the photo unchanged when it cannot be decoded or sizes mismatch
     * (same as `JvmRaster.cutout`).
     */
    fun cutout(imageBytes: ByteArray, mask: MaskResult): ByteArray {
        val src = decodeBitmap(imageBytes) ?: return imageBytes
        if (src.width != mask.width || src.height != mask.height) return imageBytes
        val bounds = mask.bounds() ?: return imageBytes
        val bits = mask.toBooleanArray()
        val pixels = IntArray(bounds.w * bounds.h)
        src.getPixels(pixels, 0, bounds.w, bounds.x, bounds.y, bounds.w, bounds.h)
        for (y in 0 until bounds.h) for (x in 0 until bounds.w) {
            val i = y * bounds.w + x
            val inside = bits[(bounds.y + y) * mask.width + bounds.x + x]
            pixels[i] = if (inside) pixels[i] or (0xFF shl 24) else SpriteMatte.TRANSPARENT_WHITE
        }
        return encodePng(pixels, bounds.w, bounds.h)
    }

    /** [SpriteMatte] on a Muse output -> cropped transparent PNG; null when undecodable or degenerate. */
    fun matte(png: ByteArray): ByteArray? {
        val src = decodeBitmap(png) ?: return null
        val raster = SpriteMatte.matte(AndroidRaster(src)).raster ?: return null
        return encodePng(raster.pixels, raster.width, raster.height)
    }

    /**
     * Scales [bytes] down so the long side is at most [maxSide]; never upscales (a fitting image comes back unchanged,
     * a fitting JPEG with an EXIF rotation comes back upright). `inSampleSize` does the power-of-two part at decode
     * time (bounded memory), a filtered `createScaledBitmap` the rest.
     */
    fun downscale(bytes: ByteArray, maxSide: Int): ByteArray {
        require(maxSide > 0) { "maxSide must be positive" }
        val b = bounds(bytes) ?: return bytes
        val longSide = max(b.outWidth, b.outHeight)
        val jpeg = sniffImageMime(bytes) == "image/jpeg"
        if (longSide <= maxSide) return if (jpeg && exifOrientation(bytes) != 1) upright(bytes) else bytes
        val src = decodeBitmap(bytes, sampleFor(longSide, maxSide)) ?: return bytes
        val scaled = scaleTo(src, maxSide)
        return if (jpeg) encodeJpeg(scaled) else encodePng(pixelsOf(scaled), scaled.width, scaled.height)
    }

    /** A JPEG with EXIF orientation 2..8 re-encoded upright (tag dropped); anything else unchanged. */
    fun upright(bytes: ByteArray): ByteArray {
        if (sniffImageMime(bytes) != "image/jpeg" || exifOrientation(bytes) == 1) return bytes
        return decodeBitmap(bytes)?.let(::encodeJpeg) ?: bytes
    }

    /** Reads a gallery/camera image behind [uri] and prepares it with [preparePhoto]; null when unreadable. */
    fun loadPhoto(resolver: ContentResolver, uri: Uri, maxSide: Int = PHOTO_MAX_SIDE): ByteArray? {
        val raw = try { resolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null } ?: return null
        return preparePhoto(raw, maxSide)
    }

    /** Photo intake: decode with downsampling to <= [maxSide] on the long side, EXIF upright, JPEG out. */
    fun preparePhoto(raw: ByteArray, maxSide: Int = PHOTO_MAX_SIDE): ByteArray? {
        val b = bounds(raw) ?: return null
        val bitmap = decodeBitmap(raw, sampleFor(max(b.outWidth, b.outHeight), maxSide)) ?: return null
        return encodeJpeg(scaleTo(bitmap, maxSide))
    }

    /** EXIF Orientation (1..8) of a JPEG; 1 for anything else or on any parse problem. Never throws. */
    fun exifOrientation(bytes: ByteArray): Int = try {
        if (sniffImageMime(bytes) != "image/jpeg") 1
        else ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in 1..8 } ?: 1
    } catch (e: Exception) {
        1
    }

    // ---------------------------------------------------------------- internals

    private fun bounds(bytes: ByteArray): BitmapFactory.Options? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    /** Largest power of two that still leaves the long side >= [maxSide] after decoding. */
    private fun sampleFor(longSide: Int, maxSide: Int): Int {
        var s = 1
        while (longSide / (s * 2) >= maxSide) s *= 2
        return s
    }

    /** Decode (ARGB_8888, premultiplied) + EXIF orientation; null when the bytes are not an image. */
    private fun decodeBitmap(bytes: ByteArray, sample: Int = 1): Bitmap? {
        val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) } catch (e: Exception) { null } ?: return null
        val orientation = exifOrientation(bytes)
        return if (orientation == 1) bmp else orient(bmp, orientation)
    }

    /** Applies EXIF orientation [o] (2..8); the same mapping as JvmRaster.orient, expressed as a Matrix. */
    private fun orient(src: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            2 -> m.postScale(-1f, 1f) // mirror horizontal
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f) // mirror vertical
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) } // transpose
            6 -> m.postRotate(90f) // rotate 90 CW
            7 -> { m.postRotate(90f); m.postScale(1f, -1f) } // transverse
            8 -> m.postRotate(270f) // rotate 90 CCW
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun scaleTo(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(src.width, src.height)
        if (longSide <= maxSide) return src
        val scale = maxSide.toDouble() / longSide
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun pixelsOf(bmp: Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }

    /**
     * HAND-REVIEW: review fix — a gallery PNG with a transparent background would turn black in JPEG (SAM and Muse
     * then see a black backdrop); flatten it on white first, the background the prompts ask for.
     */
    private fun encodeJpeg(bmp: Bitmap): ByteArray {
        val opaque = if (!bmp.hasAlpha()) bmp else {
            Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            }
        }
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /**
     * ARGB pixels -> PNG with alpha. Alpha-0 pixels are forced to transparent white, and the staging Bitmap is
     * non-premultiplied so `setPixels` stores the white instead of zeroing it.
     */
    private fun encodePng(pixels: IntArray, w: Int, h: Int): ByteArray {
        for (i in pixels.indices) if (pixels[i] ushr 24 == 0) pixels[i] = SpriteMatte.TRANSPARENT_WHITE
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.isPremultiplied = false
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
