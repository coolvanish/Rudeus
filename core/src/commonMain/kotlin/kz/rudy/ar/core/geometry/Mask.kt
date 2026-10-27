package kz.rudy.ar.core.geometry

import kotlinx.serialization.Serializable

/** Axis-aligned pixel rectangle, inclusive bounds. */
@Serializable
data class PixelRect(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
}

/**
 * Binary mask of [width] x [height] pixels, row-major: pixel (x, y) is `bits[y * width + x]`.
 * Pixel coordinates follow the image convention (x right, y down, origin top-left).
 */
@Serializable
class BooleanMask(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {
    init {
        require(width >= 0 && height >= 0) { "Mask size must be non-negative" }
        require(bits.size == width * height) { "bits.size=${bits.size} != $width*$height" }
    }

    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]

    operator fun set(x: Int, y: Int, value: Boolean) {
        bits[y * width + x] = value
    }

    val isEmpty: Boolean get() = bits.none { it }

    /** Number of set pixels. */
    fun count(): Int = bits.count { it }

    /** Bounding box of set pixels, or null for an empty mask. */
    fun boundingBox(): PixelRect? {
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) if (bits[row + x]) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else PixelRect(minX, minY, maxX, maxY)
    }

    /**
     * The largest 4-connected component as a new mask of the same size (empty mask for an empty input).
     * Iterative BFS with an IntArray queue; O(width * height) time and memory, fine for 1920x1080.
     */
    fun largestComponent(): BooleanMask {
        val n = width * height
        val label = IntArray(n) // 0 = unlabelled
        val queue = IntArray(n)
        var bestLabel = 0
        var bestSize = 0
        var current = 0
        for (seed in 0 until n) {
            if (!bits[seed] || label[seed] != 0) continue
            current++
            var head = 0; var tail = 0
            queue[tail++] = seed
            label[seed] = current
            var size = 0
            while (head < tail) {
                val i = queue[head++]
                size++
                val x = i % width
                val y = i / width
                if (x > 0) push(i - 1, current, label, queue, tail).also { tail = it }
                if (x < width - 1) push(i + 1, current, label, queue, tail).also { tail = it }
                if (y > 0) push(i - width, current, label, queue, tail).also { tail = it }
                if (y < height - 1) push(i + width, current, label, queue, tail).also { tail = it }
            }
            if (size > bestSize) { bestSize = size; bestLabel = current }
        }
        val out = BooleanArray(n)
        if (bestLabel != 0) for (i in 0 until n) out[i] = label[i] == bestLabel
        return BooleanMask(width, height, out)
    }

    private fun push(j: Int, current: Int, label: IntArray, queue: IntArray, tail: Int): Int {
        if (!bits[j] || label[j] != 0) return tail
        label[j] = current
        queue[tail] = j
        return tail + 1
    }

    fun copy(): BooleanMask = BooleanMask(width, height, bits.copyOf())

    override fun equals(other: Any?): Boolean =
        other is BooleanMask && other.width == width && other.height == height && other.bits.contentEquals(bits)

    override fun hashCode(): Int = 31 * (31 * width + height) + bits.contentHashCode()

    override fun toString(): String = "BooleanMask(${width}x$height, set=${count()})"

    companion object {
        /** Builds a mask from a predicate over pixel coordinates. */
        inline fun generate(width: Int, height: Int, inside: (x: Int, y: Int) -> Boolean): BooleanMask {
            val bits = BooleanArray(width * height)
            for (y in 0 until height) for (x in 0 until width) bits[y * width + x] = inside(x, y)
            return BooleanMask(width, height, bits)
        }

        /**
         * Decodes an uncompressed run-length encoding: alternating run lengths starting with an *unset* run,
         * in row-major order (the COCO "counts" layout but row-major, as SAM masks come from the API).
         */
        fun fromRle(width: Int, height: Int, counts: IntArray): BooleanMask {
            val bits = BooleanArray(width * height)
            var pos = 0
            var value = false
            for (run in counts) {
                val end = minOf(pos + run, bits.size)
                if (value) for (i in pos until end) bits[i] = true
                pos = end
                value = !value
            }
            return BooleanMask(width, height, bits)
        }
    }
}
