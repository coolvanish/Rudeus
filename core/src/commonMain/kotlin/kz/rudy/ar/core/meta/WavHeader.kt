package kz.rudy.ar.core.meta

/**
 * Minimal RIFF/WAVE writer for Muse Voice Transcribe, which only accepts `audioEncoding: "WAV"` = a RIFF/WAVE
 * container holding mono 16-bit integer PCM at 16 kHz or 24 kHz; raw PCM is rejected with 400
 * (https://dev.meta.ai/docs/api-reference/voice/schemas, "Transcribe Request"). The phone records raw
 * little-endian PCM with `AudioRecord`, so this wraps it with the canonical 44-byte header.
 */
object WavHeader {
    const val HEADER_SIZE = 44
    /** Sample rates the transcribe endpoint accepts. */
    val SUPPORTED_SAMPLE_RATES = setOf(16_000, 24_000)

    /** Returns `header + pcm`: PCM format 1, 1 channel, 16 bits, [sampleRate] Hz; all fields little-endian. */
    fun wrapPcm16Mono(pcm: ByteArray, sampleRate: Int): ByteArray {
        require(sampleRate in SUPPORTED_SAMPLE_RATES) { "sampleRate must be one of $SUPPORTED_SAMPLE_RATES (got $sampleRate)" }
        require(pcm.size % 2 == 0) { "16-bit PCM must have an even byte count (got ${pcm.size})" }
        val channels = 1
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(HEADER_SIZE + pcm.size)
        var p = 0
        fun ascii(s: String) { for (c in s) out[p++] = c.code.toByte() }
        fun u16(v: Int) { out[p++] = v.toByte(); out[p++] = (v ushr 8).toByte() }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        ascii("RIFF"); u32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(channels); u32(sampleRate); u32(sampleRate * blockAlign); u16(blockAlign); u16(bitsPerSample)
        ascii("data"); u32(pcm.size)
        pcm.copyInto(out, HEADER_SIZE)
        return out
    }

    /** The `fmt ` fields of a RIFF/WAVE file. */
    data class Format(val audioFormat: Int, val channels: Int, val sampleRate: Int, val bitsPerSample: Int)

    /** Reads the `fmt ` chunk of [bytes] (walking the RIFF chunks); null when it is not a RIFF/WAVE with one. */
    fun parseFormat(bytes: ByteArray): Format? {
        if (!isWav(bytes)) return null
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = bytes.decodeToString(p, p + 4)
            val size = u32(p + 4)
            if (id == "fmt " && size >= 16 && p + 8 + 16 <= bytes.size) {
                return Format(audioFormat = u16(p + 8), channels = u16(p + 10), sampleRate = u32(p + 12).toInt(), bitsPerSample = u16(p + 22))
            }
            val next = p + 8 + size + (size and 1) // chunks are word-aligned
            if (next > Int.MAX_VALUE || next <= p) return null
            p = next.toInt()
        }
        return null
    }

    /**
     * Rejects a WAV the transcribe endpoint would 400 on: not mono, not 16-bit, or a rate outside
     * [SUPPORTED_SAMPLE_RATES]. Throws [MetaError.Parse] so the caller never spends a request on it.
     */
    fun requireSupported(bytes: ByteArray) {
        val f = parseFormat(bytes) ?: throw MetaError.Parse("audio is not a RIFF/WAVE file with a fmt chunk")
        if (f.channels != 1 || f.bitsPerSample != 16 || f.sampleRate !in SUPPORTED_SAMPLE_RATES) {
            throw MetaError.Parse(
                "WAV must be mono 16-bit PCM at ${SUPPORTED_SAMPLE_RATES.joinToString(" or ")} Hz " +
                    "(got ${f.channels} ch, ${f.bitsPerSample} bit, ${f.sampleRate} Hz)",
            )
        }
    }

    /** True when [bytes] already start with a RIFF/WAVE header (so callers never wrap twice). */
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WAVE"
}
