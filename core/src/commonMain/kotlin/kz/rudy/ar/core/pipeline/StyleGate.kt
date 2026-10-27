package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.RasterImage
import kz.rudy.ar.core.io.alpha
import kz.rudy.ar.core.io.blue
import kz.rudy.ar.core.io.green
import kz.rudy.ar.core.io.red
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** A colour in CIE L*a*b* (D65). */
data class Lab(val l: Float, val a: Float, val b: Float) {
    /** ΔE76 — Euclidean distance in Lab. ~2.3 is a just-noticeable difference. */
    fun deltaE(o: Lab): Float = sqrt((l - o.l) * (l - o.l) + (a - o.a) * (a - o.a) + (b - o.b) * (b - o.b))

    companion object {
        fun fromArgb(argb: Int): Lab = fromRgb(argb.red, argb.green, argb.blue)

        fun fromRgb(r8: Int, g8: Int, b8: Int): Lab {
            val r = linear(r8 / 255f); val g = linear(g8 / 255f); val b = linear(b8 / 255f)
            // sRGB -> XYZ (D65), normalised by the white point.
            val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 1.00000f
            val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
            val fx = f(x); val fy = f(y); val fz = f(z)
            return Lab(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
        }

        private fun linear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        private fun f(t: Float): Float = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    }
}

/** One dominant colour and its share (0..1) of the opaque pixels. */
data class PaletteEntry(val lab: Lab, val weight: Float)

/**
 * The gate decides whether a stylized sprite is "on style". Two halves:
 *  - model half: Spark vision's `scoreStyle` in 0..1;
 *  - deterministic half ([deterministicScore]): dominant-palette distance in CIE Lab (ΔE76) and an
 *    outline-thickness proxy (share of dark pixels), both against the style references.
 * `score = modelWeight * model + (1 - modelWeight) * deterministic`; with no decoded rasters the model
 * score is used alone. Scores are rounded to 3 decimals so `0.7` compares exactly with the threshold.
 */
class StyleGate(
    val threshold: Float = 0.7f,
    val modelWeight: Float = 0.6f,
    /** Dominant colours kept per image. */
    val paletteSize: Int = 6,
) {
    fun combine(modelScore: Float, deterministicScore: Float?): Float {
        val m = modelScore.coerceIn(0f, 1f)
        val s = if (deterministicScore == null) m else modelWeight * m + (1f - modelWeight) * deterministicScore.coerceIn(0f, 1f)
        return round(s * 1000f) / 1000f
    }

    fun passes(score: Float): Boolean = score >= threshold

    /** Null when the sprite or every reference could not be decoded or is fully transparent. */
    fun deterministicScore(stylized: RasterImage?, refs: List<RasterImage?>): Float? {
        if (stylized == null) return null
        val refImages = refs.filterNotNull()
        if (refImages.isEmpty()) return null
        val spritePalette = palette(stylized, paletteSize)
        val refPalette = refImages.flatMap { palette(it, paletteSize) }
        if (spritePalette.isEmpty() || refPalette.isEmpty()) return null
        val paletteScore = paletteScore(spritePalette, refPalette)
        val refOutline = refImages.map { outlineFraction(it) }.average().toFloat()
        val outlineScore = outlineScore(outlineFraction(stylized), refOutline)
        return (PALETTE_WEIGHT * paletteScore + (1f - PALETTE_WEIGHT) * outlineScore).coerceIn(0f, 1f)
    }

    companion object {
        /** Share of the deterministic half given to the palette; the rest is the outline proxy. */
        const val PALETTE_WEIGHT = 0.7f

        /** Mean ΔE at which the palette score reaches 0. */
        const val MAX_DELTA_E = 50f

        /** Difference in dark-pixel share at which the outline score reaches 0. */
        const val MAX_OUTLINE_DIFF = 0.25f

        /** Pixels with L* below this count as outline/ink. */
        const val DARK_L = 25f

        /** Pixels with alpha below this are background. */
        const val OPAQUE_ALPHA = 128

        /**
         * Dominant palette by uniform quantisation: 4 bits per channel (4096 bins), the [k] most populated
         * bins, each represented by the mean colour of its pixels. Deterministic and cheap.
         */
        fun palette(img: RasterImage, k: Int = 6): List<PaletteEntry> {
            val counts = HashMap<Int, IntArray>() // bin -> [count, sumR, sumG, sumB]
            var opaque = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.argb(x, y)
                if (p.alpha < OPAQUE_ALPHA) continue
                opaque++
                val bin = ((p.red shr 4) shl 8) or ((p.green shr 4) shl 4) or (p.blue shr 4)
                val acc = counts.getOrPut(bin) { IntArray(4) }
                acc[0]++; acc[1] += p.red; acc[2] += p.green; acc[3] += p.blue
            }
            if (opaque == 0) return emptyList()
            return counts.values
                .sortedByDescending { it[0] }
                .take(k)
                .map { acc -> PaletteEntry(Lab.fromRgb(acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0]), acc[0].toFloat() / opaque) }
        }

        /** Weighted mean of each sprite colour's distance to the nearest reference colour, mapped to 0..1. */
        fun paletteScore(sprite: List<PaletteEntry>, refs: List<PaletteEntry>): Float {
            if (sprite.isEmpty() || refs.isEmpty()) return 0f
            var sum = 0f; var w = 0f
            for (e in sprite) {
                val nearest = refs.minOf { it.lab.deltaE(e.lab) }
                sum += nearest * e.weight; w += e.weight
            }
            val meanDe = if (w == 0f) MAX_DELTA_E else sum / w
            return (1f - meanDe / MAX_DELTA_E).coerceIn(0f, 1f)
        }

        /** Share of opaque pixels that are dark (ink). A proxy for outline thickness. */
        fun outlineFraction(img: RasterImage): Float {
            var opaque = 0; var dark = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.argb(x, y)
                if (p.alpha < OPAQUE_ALPHA) continue
                opaque++
                if (Lab.fromArgb(p).l < DARK_L) dark++
            }
            return if (opaque == 0) 0f else dark.toFloat() / opaque
        }

        fun outlineScore(sprite: Float, refs: Float): Float = (1f - abs(sprite - refs) / MAX_OUTLINE_DIFF).coerceIn(0f, 1f)
    }
}
