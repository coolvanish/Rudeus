package kz.rudy.ar.core.meta

import kotlinx.serialization.Serializable

/** Axis-aligned box in pixel coordinates of the segmented image. */
@Serializable
data class BBox(val x: Int, val y: Int, val w: Int, val h: Int) {
    val area: Int get() = w * h
}

/**
 * One segmentation mask, normalised from whatever the provider returned.
 *
 * [rle] is COCO-style *uncompressed* RLE over the row-major pixel grid: alternating run lengths
 * starting with an OFF run (`[off, on, off, on, ...]`), summing to `width * height`.
 * Either [rle] or [bbox] (or both) is present; a mask with neither is "object absent".
 */
@Serializable
data class MaskResult(
    val label: String,
    val width: Int,
    val height: Int,
    val rle: List<Int>? = null,
    val bbox: BBox? = null,
    /** Provider confidence in 0..1 when available. */
    val score: Float? = null,
) {
    /** Number of ON pixels (RLE) or the bbox area, or 0 when empty. */
    val area: Int
        get() = when {
            rle != null -> rle.filterIndexed { i, _ -> i % 2 == 1 }.sum()
            bbox != null -> bbox.area
            else -> 0
        }

    val isEmpty: Boolean get() = area == 0

    /** Expands the mask into a row-major `BooleanArray` of `width * height` (true = inside the object). */
    fun toBooleanArray(): BooleanArray {
        val out = BooleanArray(width * height)
        if (rle != null) {
            var pos = 0
            var on = false
            for (run in rle) {
                if (on) for (i in 0 until run) if (pos + i < out.size) out[pos + i] = true
                pos += run
                on = !on
            }
        } else if (bbox != null) {
            for (y in bbox.y until (bbox.y + bbox.h).coerceAtMost(height)) {
                for (x in bbox.x until (bbox.x + bbox.w).coerceAtMost(width)) {
                    if (x >= 0 && y >= 0) out[y * width + x] = true
                }
            }
        }
        return out
    }

    /** Tight bounding box of the ON pixels (RLE) or [bbox]; null when empty. */
    fun bounds(): BBox? {
        if (rle == null) return bbox
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        val bits = toBooleanArray()
        for (i in bits.indices) if (bits[i]) {
            val x = i % width; val y = i / width
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        return if (maxX < 0) null else BBox(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    companion object {
        /** Encodes a row-major boolean mask into uncompressed RLE (starts with an OFF run, possibly 0). */
        fun encodeRle(bits: BooleanArray): List<Int> {
            val out = ArrayList<Int>()
            var current = false
            var run = 0
            for (b in bits) {
                if (b == current) run++ else { out += run; current = b; run = 1 }
            }
            out += run
            return out
        }

        fun fromBits(label: String, width: Int, height: Int, bits: BooleanArray, score: Float? = null): MaskResult =
            MaskResult(label, width, height, encodeRle(bits), null, score)
    }
}

/** Result of one segmentation call: zero or more masks (one per prompt for table scans). */
@Serializable
data class SegmentResult(val masks: List<MaskResult>) {
    fun largest(): MaskResult? = masks.maxByOrNull { it.area }
    fun byLabel(label: String): MaskResult? = masks.firstOrNull { it.label.equals(label, ignoreCase = true) }
}

/** Failures of a [MetaModels] implementation. The pipeline records them in metrics and gives up on the character. */
sealed class MetaError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : MetaError(message, cause)
    class Http(val code: Int, val body: String) : MetaError("HTTP $code: ${body.take(300)}")
    class Parse(message: String, cause: Throwable? = null) : MetaError(message, cause)
    class FixtureMissing(val path: String) : MetaError("fixture missing: $path")
    class Provider(message: String, cause: Throwable? = null) : MetaError(message, cause)
}
