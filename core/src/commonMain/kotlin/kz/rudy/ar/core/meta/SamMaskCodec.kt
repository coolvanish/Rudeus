/*
 * This file is licensed under the SAM License (see LICENSES/SAM-LICENSE.txt), not the repository's MIT licence;
 * derived from @meta-sam/parser 0.1.0, Copyright (c) Meta Platforms, Inc. and affiliates.
 */
package kz.rudy.ar.core.meta

/**
 * Decoder (and a `one_bit` encoder) for SAM 3.1 mask payloads, the `<enc>payload` part of
 * `<|mask;x=0;y=0;data=H,W,<enc>payload|>`.
 *
 * Specification: meta-models/meta-sam `protocol/sam3.md` ("Mask payload encoding") and the docs page
 * https://dev.meta.ai/docs/sam/reading-segmentation. The docs only say "base85, not base64"; the exact
 * scheme below is a line-by-line Kotlin port of Meta's reference decoder `@meta-sam/parser` 0.1.0
 * (`dist/mask-codec.js`, SAM License, Copyright (c) Meta Platforms, Inc. and affiliates), checked against
 * Meta's shared conformance vectors (see SamMaskCodecTest):
 *
 * - Marker: the first char, `~` = lossless, `!` = one_bit. The rest is base85 digits.
 * - Base85 alphabet: ASCII `!`..`{` in order minus `" \ , ; < > |` (85 digits). A 5-digit big-endian
 *   length prefix (byte count), then 4 bytes -> 5 digits; a tail of r bytes -> r+1 digits.
 * - one_bit: binary range coder over the row-major raster with a 12-neighbour context model; the payload
 *   must round-trip canonically.
 * - lossless: first byte selects a spatial predictor (0..4), then a range-coded residual stream of 8-bit
 *   coverage values; a pixel is ON when coverage >= 129.
 *
 * Unsigned 32-bit arithmetic of the JS original is done in [Long] masked with [MASK32].
 */
object SamMaskCodec {
    enum class Encoding(val marker: Char, val wire: String) { LOSSLESS('~', "lossless"), ONE_BIT('!', "one_bit") }

    /** Thrown for any malformed payload; callers map it to [MetaError.Parse]. */
    class InvalidMask(message: String) : IllegalArgumentException(message)

    /** The 85 base85 digits, in value order. */
    val ALPHABET: String = buildString {
        for (c in 0x21..0x7b) {
            val ch = c.toChar()
            if (ch !in "\"\\,;<>|") append(ch)
        }
    }

    private const val RADIX = 85
    private const val PREFIX_LENGTH = 5
    private const val MASK32 = 0xFFFF_FFFFL
    private const val TOP = 1L shl 24
    private const val BOTTOM = 1L shl 16

    // one_bit model
    private const val INCREMENT = 14
    private const val COUNT_LIMIT = 4096
    private const val CONTEXT_COUNT = 1 shl 12
    private const val PADDING = 2

    // lossless model
    private const val SYMBOLS = 256
    private const val TOTAL_INDEX = SYMBOLS
    private const val SPATIAL_MODES = 5
    private const val ZERO_INCREMENT = 32
    private const val ZERO_LIMIT = 16384
    private const val ORDER_ONE_INCREMENT = 56
    private const val ORDER_ONE_LIMIT = 8192
    private const val ORDER_TWO_STEP = 14 * 44
    private const val ORDER_TWO_LIMIT = 14 * 3584
    private const val ORDER_ZERO_STEP = 2 * 16
    private const val ORDER_ZERO_LIMIT = 2 * 2048
    private const val ORDER_ZERO_INITIAL = 2
    private const val ORDER_ONE_INITIAL = 1

    /** Guard against absurd dimensions in a malformed token (a 12 MP photo is ~12.2 M). */
    const val MAX_AREA = 64_000_000L

    private val digitValues = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encodingOf(payload: String): Encoding = when (payload.firstOrNull()) {
        '~' -> Encoding.LOSSLESS
        '!' -> Encoding.ONE_BIT
        else -> throw InvalidMask("mask payload must start with ~ (lossless) or ! (one_bit)")
    }

    /**
     * Decodes [payload] (marker included) into a row-major raster of `width * height` booleans.
     * @throws InvalidMask on any structural problem (alphabet, length prefix, truncation, non-canonical one_bit).
     */
    fun decode(payload: String, width: Int, height: Int): BooleanArray {
        if (width <= 0 || height <= 0) throw InvalidMask("mask dimensions must be positive (got ${width}x$height)")
        if (width.toLong() * height > MAX_AREA) throw InvalidMask("mask ${width}x$height exceeds $MAX_AREA pixels")
        return when (encodingOf(payload)) {
            Encoding.LOSSLESS -> {
                val coverage = decodeLossless(payload, width, height)
                BooleanArray(coverage.size) { coverage[it] >= 129 }
            }
            Encoding.ONE_BIT -> {
                val raster = decodeOneBit(payload, width, height)
                if (encodeOneBit(raster, width, height) != payload) throw InvalidMask("one_bit payload is not canonical")
                raster
            }
        }
    }

    /** Encodes a row-major raster as a `one_bit` payload (`!` + base85). Used for tests and synthetic fixtures. */
    fun encodeOneBit(raster: BooleanArray, width: Int, height: Int): String {
        if (width <= 0 || height <= 0 || width * height != raster.size) throw InvalidMask("raster size does not match ${width}x$height")
        val encoder = RangeEncoder()
        val counts = IntArray(CONTEXT_COUNT * 2) { 1 }
        val paddedWidth = width + 2 * PADDING
        val padded = IntArray(paddedWidth * (height + PADDING))
        for (y in 0 until height) {
            val row = (y + PADDING) * paddedWidth + PADDING
            for (x in 0 until width) {
                val position = row + x
                val offset = contextAt(padded, position, position - paddedWidth, position - 2 * paddedWidth) shl 1
                val zero = counts[offset]
                val one = counts[offset + 1]
                val bit = if (raster[y * width + x]) 1 else 0
                if (bit == 0) encoder.encode(0, zero.toLong(), (zero + one).toLong())
                else encoder.encode(zero.toLong(), one.toLong(), (zero + one).toLong())
                padded[position] = bit
                updateCounts(counts, offset, bit)
            }
        }
        return "!" + pack(encoder.finish())
    }

    // ------------------------------------------------------------ base85 envelope

    private fun digit(c: Char): Int {
        val v = if (c.code < digitValues.size) digitValues[c.code] else -1
        if (v < 0 || v >= RADIX) throw InvalidMask("mask payload contains an invalid character '$c'")
        return v
    }

    internal fun pack(input: ByteArray): String {
        var length = input.size.toLong()
        val prefix = CharArray(PREFIX_LENGTH)
        for (i in PREFIX_LENGTH - 1 downTo 0) {
            prefix[i] = ALPHABET[(length % RADIX).toInt()]
            length /= RADIX
        }
        if (length != 0L) throw InvalidMask("mask payload is too large")
        val out = StringBuilder().append(prefix)
        var offset = 0
        val full = input.size - input.size % 4
        val digits = IntArray(5)
        while (offset < full) {
            var value = ((input[offset].toLong() and 0xFF) shl 24) or ((input[offset + 1].toLong() and 0xFF) shl 16) or
                ((input[offset + 2].toLong() and 0xFF) shl 8) or (input[offset + 3].toLong() and 0xFF)
            for (i in 4 downTo 0) { digits[i] = (value % RADIX).toInt(); value /= RADIX }
            for (d in digits) out.append(ALPHABET[d])
            offset += 4
        }
        val remainder = input.size - offset
        if (remainder > 0) {
            var value = 0L
            for (i in 0 until 4) value = ((value shl 8) or (if (i < remainder) input[offset + i].toLong() and 0xFF else 0L)) and MASK32
            for (i in 4 downTo 0) { digits[i] = (value % RADIX).toInt(); value /= RADIX }
            for (i in 0 until remainder + 1) out.append(ALPHABET[digits[i]])
        }
        return out.toString()
    }

    internal fun unpack(payload: String): ByteArray {
        if (payload.length < PREFIX_LENGTH) throw InvalidMask("mask payload is missing its length prefix")
        var length = 0L
        for (i in 0 until PREFIX_LENGTH) length = length * RADIX + digit(payload[i])
        val remainder = (length % 4).toInt()
        val expected = PREFIX_LENGTH + (length / 4) * 5 + (if (remainder == 0) 0 else remainder + 1)
        if (payload.length.toLong() != expected) throw InvalidMask("mask payload length does not match its prefix")
        val output = ByteArray(length.toInt())
        var source = PREFIX_LENGTH
        var destination = 0
        val full = length.toInt() - remainder
        while (destination < full) {
            var value = 0L
            for (i in 0 until 5) value = value * RADIX + digit(payload[source + i])
            if (value > MASK32) throw InvalidMask("mask payload contains an out-of-range group")
            source += 5
            output[destination] = (value ushr 24).toByte()
            output[destination + 1] = (value ushr 16).toByte()
            output[destination + 2] = (value ushr 8).toByte()
            output[destination + 3] = value.toByte()
            destination += 4
        }
        if (remainder > 0) {
            var value = 0L
            for (i in 0 until 5) value = value * RADIX + (if (i < remainder + 1) digit(payload[source + i]) else RADIX - 1)
            if (value > MASK32) throw InvalidMask("mask payload contains an out-of-range tail")
            for (i in 0 until remainder) output[destination + i] = (value ushr (24 - i * 8)).toByte()
        }
        if (pack(output) != payload) throw InvalidMask("mask payload is not in canonical form")
        return output
    }

    // ------------------------------------------------------------ range coder

    private class RangeEncoder {
        private var low = 0L
        private var range = MASK32
        private val out = ArrayList<Byte>()

        fun encode(cumulative: Long, frequency: Long, total: Long) {
            val scaled = range / total
            low = (low + scaled * cumulative) and MASK32
            range = scaled * frequency
            while (true) {
                if (((low xor ((low + range) and MASK32)) and MASK32) >= TOP) {
                    if (range >= BOTTOM) break
                    range = (-low and MASK32) and (BOTTOM - 1)
                }
                out += (low ushr 24).toByte()
                low = (low shl 8) and MASK32
                range = (range shl 8) and MASK32
            }
        }

        fun finish(): ByteArray {
            repeat(4) {
                out += (low ushr 24).toByte()
                low = (low shl 8) and MASK32
            }
            return out.toByteArray()
        }
    }

    private class RangeDecoder(private val input: ByteArray, start: Int) {
        private var position = start
        private var low = 0L
        private var range = MASK32
        private var code = 0L
        private var scaled = 0L

        init {
            if (input.size - start < 4) throw InvalidMask("mask payload is missing its finalization")
            repeat(4) { code = ((code shl 8) or read()) and MASK32 }
        }

        fun frequency(total: Long): Long {
            scaled = range / total
            if (scaled == 0L) throw InvalidMask("mask payload has invalid coding state")
            val value = ((code - low) and MASK32) / scaled
            return if (value >= total) total - 1 else value
        }

        fun decode(cumulative: Long, frequency: Long) {
            low = (low + scaled * cumulative) and MASK32
            range = scaled * frequency
            while (true) {
                if (((low xor ((low + range) and MASK32)) and MASK32) >= TOP) {
                    if (range >= BOTTOM) break
                    range = (-low and MASK32) and (BOTTOM - 1)
                }
                code = ((code shl 8) or read()) and MASK32
                low = (low shl 8) and MASK32
                range = (range shl 8) and MASK32
            }
        }

        private fun read(): Long {
            if (position >= input.size) throw InvalidMask("mask payload ended before decoding completed")
            return input[position++].toLong() and 0xFF
        }
    }

    // ------------------------------------------------------------ one_bit

    private fun contextAt(r: IntArray, position: Int, prior: Int, earlier: Int): Int =
        r[position - 1] or (r[position - 2] shl 1) or (r[prior - 2] shl 2) or (r[prior - 1] shl 3) or (r[prior] shl 4) or
            (r[prior + 1] shl 5) or (r[prior + 2] shl 6) or (r[earlier - 2] shl 7) or (r[earlier - 1] shl 8) or
            (r[earlier] shl 9) or (r[earlier + 1] shl 10) or (r[earlier + 2] shl 11)

    private fun halfOrOne(v: Int): Int = (v shr 1).let { if (it == 0) 1 else it }

    private fun updateCounts(counts: IntArray, offset: Int, bit: Int) {
        counts[offset + bit] += INCREMENT
        if (counts[offset] + counts[offset + 1] >= COUNT_LIMIT) {
            counts[offset] = halfOrOne(counts[offset])
            counts[offset + 1] = halfOrOne(counts[offset + 1])
        }
    }

    private fun decodeOneBit(payload: String, width: Int, height: Int): BooleanArray {
        val decoder = RangeDecoder(unpack(payload.substring(1)), 0)
        val counts = IntArray(CONTEXT_COUNT * 2) { 1 }
        val paddedWidth = width + 2 * PADDING
        val padded = IntArray(paddedWidth * (height + PADDING))
        val output = BooleanArray(width * height)
        for (y in 0 until height) {
            val row = (y + PADDING) * paddedWidth + PADDING
            for (x in 0 until width) {
                val position = row + x
                val offset = contextAt(padded, position, position - paddedWidth, position - 2 * paddedWidth) shl 1
                val zero = counts[offset]
                val one = counts[offset + 1]
                val value = decoder.frequency((zero + one).toLong())
                val bit = if (value < zero) 0 else 1
                if (bit == 0) decoder.decode(0, zero.toLong()) else decoder.decode(zero.toLong(), one.toLong())
                padded[position] = bit
                output[y * width + x] = bit == 1
                updateCounts(counts, offset, bit)
            }
        }
        return output
    }

    // ------------------------------------------------------------ lossless

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val estimate = left + up - upperLeft
        val l = kotlin.math.abs(estimate - left)
        val u = kotlin.math.abs(estimate - up)
        val ul = kotlin.math.abs(estimate - upperLeft)
        if (l <= u && l <= ul) return left
        return if (u <= ul) up else upperLeft
    }

    private fun predictByte(output: IntArray, offset: Int, x: Int, y: Int, mode: Int, width: Int): Int {
        if (mode < 0 || mode >= SPATIAL_MODES) throw InvalidMask("lossless mask payload uses an invalid predictor $mode")
        val left = if (x > 0) output[offset - 1] else 0
        val up = if (y > 0) output[offset - width] else 0
        if (mode == 1) return up
        if (mode == 2) return left
        if (mode == 3) return (left + up) shr 1
        val upperLeft = if (x > 0 && y > 0) output[offset - width - 1] else 0
        if (mode == 0) return paeth(left, up, upperLeft)
        if (upperLeft >= maxOf(left, up)) return minOf(left, up)
        if (upperLeft <= minOf(left, up)) return maxOf(left, up)
        return left + up - upperLeft
    }

    private fun unzig(value: Int, prediction: Int): Int {
        val delta = if (value and 1 != 0) -((value + 1) shr 1) else value shr 1
        return (prediction + delta) and 0xFF
    }

    private fun zeroContext(residuals: IntArray, offset: Int, x: Int, y: Int, width: Int): Int {
        val left = if (x > 0) (if (residuals[offset - 1] == 0) 1 else 0) else 1
        val up = if (y > 0) (if (residuals[offset - width] == 0) 1 else 0) else 1
        val upperLeft = if (x > 0 && y > 0) (if (residuals[offset - width - 1] == 0) 1 else 0) else 1
        val upperRight = if (y > 0 && x < width - 1) (if (residuals[offset - width + 1] == 0) 1 else 0) else 1
        return left or (up shl 1) or (upperLeft shl 2) or (upperRight shl 3)
    }

    /** Coverage bytes (0..255), row-major. */
    private fun decodeLossless(payload: String, width: Int, height: Int): IntArray {
        val packed = unpack(payload.substring(1))
        if (packed.size < 5) throw InvalidMask("lossless mask payload is truncated")
        val selector = packed[0].toInt() and 0xFF
        val decoder = RangeDecoder(packed, 1)
        val length = width * height
        val output = IntArray(length)
        val residuals = IntArray(length)
        val zeroCounts = IntArray(32) { 1 }
        val orderOne = IntArray(SYMBOLS * SYMBOLS) { ORDER_ONE_INITIAL }
        val orderOneTotals = IntArray(SYMBOLS) { SYMBOLS * ORDER_ONE_INITIAL }
        val orderZero = IntArray(SYMBOLS) { ORDER_ZERO_INITIAL }
        var orderZeroTotal = SYMBOLS * ORDER_ZERO_INITIAL
        val orderTwo = HashMap<Int, IntArray>()
        var previous = 0
        for (offset in 0 until length) {
            val x = offset % width
            val y = offset / width
            val zeroOffset = zeroContext(residuals, offset, x, y, width) shl 1
            val zero = zeroCounts[zeroOffset]
            val nonzero = zeroCounts[zeroOffset + 1]
            val zeroFrequency = decoder.frequency((zero + nonzero).toLong())
            val bit = if (zeroFrequency < zero) 0 else 1
            if (bit == 0) decoder.decode(0, zero.toLong()) else decoder.decode(zero.toLong(), nonzero.toLong())
            zeroCounts[zeroOffset + bit] += ZERO_INCREMENT
            if (zeroCounts[zeroOffset] + zeroCounts[zeroOffset + 1] >= ZERO_LIMIT) {
                zeroCounts[zeroOffset] = halfOrOne(zeroCounts[zeroOffset])
                zeroCounts[zeroOffset + 1] = halfOrOne(zeroCounts[zeroOffset + 1])
            }
            var symbol = 0
            if (bit == 1) {
                val above = if (offset >= width) residuals[offset - width] else 0
                val second = orderTwo.getOrPut((previous shl 8) or above) { IntArray(SYMBOLS + 1) }
                val firstOffset = previous shl 8
                val excludedZero = second[0] + orderOne[firstOffset] + orderZero[0]
                val total = second[TOTAL_INDEX] + orderOneTotals[previous] + orderZeroTotal - excludedZero
                val target = decoder.frequency(total.toLong())
                var cumulative = 0L
                symbol = 1
                var frequency = (second[1] + orderOne[firstOffset + 1] + orderZero[1]).toLong()
                while (cumulative + frequency <= target) {
                    cumulative += frequency
                    symbol += 1
                    if (symbol >= SYMBOLS) throw InvalidMask("lossless mask payload is malformed")
                    frequency = (second[symbol] + orderOne[firstOffset + symbol] + orderZero[symbol]).toLong()
                }
                decoder.decode(cumulative, frequency)

                second[symbol] += ORDER_TWO_STEP
                second[TOTAL_INDEX] += ORDER_TWO_STEP
                if (second[TOTAL_INDEX] >= ORDER_TWO_LIMIT) {
                    var t = 0
                    for (v in 0 until SYMBOLS) { second[v] = second[v] shr 1; t += second[v] }
                    second[TOTAL_INDEX] = t
                }
                orderOne[firstOffset + symbol] += ORDER_ONE_INCREMENT
                orderOneTotals[previous] += ORDER_ONE_INCREMENT
                if (orderOneTotals[previous] >= ORDER_ONE_LIMIT) {
                    var t = 0
                    for (v in 0 until SYMBOLS) { val s = halfOrOne(orderOne[firstOffset + v]); orderOne[firstOffset + v] = s; t += s }
                    orderOneTotals[previous] = t
                }
                orderZero[symbol] += ORDER_ZERO_STEP
                orderZeroTotal += ORDER_ZERO_STEP
                if (orderZeroTotal >= ORDER_ZERO_LIMIT) {
                    var t = 0
                    for (v in 0 until SYMBOLS) { orderZero[v] = orderZero[v] shr 1; t += orderZero[v] }
                    orderZeroTotal = t
                }
            }
            residuals[offset] = symbol
            output[offset] = unzig(symbol, predictByte(output, offset, x, y, selector, width))
            previous = symbol
        }
        return output
    }
}
