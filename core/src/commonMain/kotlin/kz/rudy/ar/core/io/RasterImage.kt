package kz.rudy.ar.core.io

/**
 * Minimal decoded image: ARGB pixels addressable by (x, y). :core never decodes images itself;
 * `JvmRaster` (jvmMain, javax.imageio) and the Android app (BitmapFactory) supply decoders as
 * `(ByteArray) -> RasterImage?` functions.
 */
interface RasterImage {
    val width: Int
    val height: Int

    /** Packed 0xAARRGGBB. */
    fun argb(x: Int, y: Int): Int
}

/** Row-major ARGB array raster. */
class ArrayRaster(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray = IntArray(width * height),
) : RasterImage {
    init { require(pixels.size == width * height) { "pixels.size=${pixels.size} != $width*$height" } }

    override fun argb(x: Int, y: Int): Int = pixels[y * width + x]

    fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }

    companion object {
        /** Solid image, handy for tests. */
        fun solid(width: Int, height: Int, argb: Int): ArrayRaster = ArrayRaster(width, height, IntArray(width * height) { argb })
    }
}

val Int.alpha: Int get() = (this ushr 24) and 0xFF
val Int.red: Int get() = (this shr 16) and 0xFF
val Int.green: Int get() = (this shr 8) and 0xFF
val Int.blue: Int get() = this and 0xFF

fun argbOf(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
