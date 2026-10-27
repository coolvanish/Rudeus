package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.ArrayRaster
import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.io.alpha
import kz.rudy.ar.core.io.blue
import kz.rudy.ar.core.io.green
import kz.rudy.ar.core.io.red
import kotlin.math.sqrt

/** Outcome of [SpriteMatte.matte]. [raster] is null when the matte failed (see [ok]). */
data class MatteResult(
    /** The matted sprite cropped to its opaque bounds; null when [ok] is false. */
    val raster: ArrayRaster?,
    /** Share (0..1) of the ORIGINAL frame that stays opaque. */
    val opaqueFraction: Float,
    val ok: Boolean,
)

/**
 * Alpha matte for Muse Image output. Muse always answers `background: "opaque"` and our prompt asks for a plain
 * white background, so the sprite is a dark-outlined character on near-white. A flood fill from every border pixel
 * through "background-like" pixels (already transparent, or near-white: high L*, low chroma in CIE Lab) marks the
 * background; it is 4-connected, so it cannot leak through a diagonal gap in the outline, and light regions enclosed
 * by the outline (a white belly, a pale-pink face) stay opaque. The opaque boundary is feathered by 1 px (alpha =
 * share of opaque pixels in its 3x3 neighbourhood) and the result is cropped to the opaque bounds. Background pixels
 * are [TRANSPARENT_WHITE].
 *
 * A result with less than [MIN_OPAQUE] or more than [MAX_OPAQUE] of the frame opaque is degenerate (all background,
 * or no background found) and reported as failed. Pure and deterministic.
 */
object SpriteMatte {
    /** Background pixels have L* at or above this (white = 100; RGB 235,235,235 is ~93). */
    const val BG_MIN_L = 90f

    /** ... and chroma `sqrt(a*² + b*²)` at or below this (a pale-pink fill is ~10..20). */
    const val BG_MAX_CHROMA = 8f

    /** Pixels with alpha below this count as transparent (input) and as background-like. */
    const val TRANSPARENT_ALPHA = 128

    /** Cheap pre-check before the Lab conversion: a near-white pixel has every channel at least this bright. */
    private const val BG_MIN_CHANNEL = 200

    /**
     * What background pixels become: alpha 0 over WHITE RGB, never transparent black. A model that drops the alpha
     * channel instead of compositing then still sees the character on white (the background Muse and the refs use).
     */
    const val TRANSPARENT_WHITE = 0x00FFFFFF

    /** Degenerate-matte bounds on the opaque share of the frame. */
    const val MIN_OPAQUE = 0.05f
    const val MAX_OPAQUE = 0.97f

    fun matte(img: RasterImage, feather: Boolean = true): MatteResult {
        val w = img.width; val h = img.height
        val n = w * h
        if (n == 0) return MatteResult(null, 0f, false)

        val background = BooleanArray(n)
        val stack = IntArray(n)
        var top = 0
        fun seed(x: Int, y: Int) {
            val i = y * w + x
            if (!background[i] && isBackgroundLike(img.argb(x, y))) { background[i] = true; stack[top++] = i }
        }
        for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
        for (y in 0 until h) { seed(0, y); seed(w - 1, y) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w; val y = i / w
            if (x > 0) seed(x - 1, y)
            if (x < w - 1) seed(x + 1, y)
            if (y > 0) seed(x, y - 1)
            if (y < h - 1) seed(x, y + 1)
        }

        var opaque = 0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (background[y * w + x]) continue
            opaque++
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val fraction = opaque.toFloat() / n
        if (fraction < MIN_OPAQUE || fraction > MAX_OPAQUE) return MatteResult(null, fraction, false)

        val cw = maxX - minX + 1; val ch = maxY - minY + 1
        val out = ArrayRaster.solid(cw, ch, TRANSPARENT_WHITE)
        for (y in 0 until ch) for (x in 0 until cw) {
            val sx = minX + x; val sy = minY + y
            if (background[sy * w + sx]) continue
            val p = img.argb(sx, sy)
            val a = if (feather) featherAlpha(background, w, h, sx, sy) else 255
            out.set(x, y, (minOf(a, p.alpha) shl 24) or (p and 0x00FFFFFF))
        }
        return MatteResult(out, fraction, true)
    }

    /** Near-white (high L*, low chroma) or already transparent. */
    fun isBackgroundLike(argb: Int): Boolean {
        if (argb.alpha < TRANSPARENT_ALPHA) return true
        if (argb.red < BG_MIN_CHANNEL || argb.green < BG_MIN_CHANNEL || argb.blue < BG_MIN_CHANNEL) return false
        val lab = Lab.fromArgb(argb)
        return lab.l >= BG_MIN_L && sqrt(lab.a * lab.a + lab.b * lab.b) <= BG_MAX_CHROMA
    }

    /** 255 inside; on the boundary the share of opaque pixels in the 3x3 neighbourhood (frame edge counts as opaque). */
    private fun featherAlpha(background: BooleanArray, w: Int, h: Int, x: Int, y: Int): Int {
        var bg = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h && background[ny * w + nx]) bg++
        }
        return if (bg == 0) 255 else 255 * (9 - bg) / 9
    }
}
