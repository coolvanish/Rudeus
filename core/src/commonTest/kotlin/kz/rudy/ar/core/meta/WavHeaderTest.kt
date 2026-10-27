package kz.rudy.ar.core.meta

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavHeaderTest {
    private fun ByteArray.ascii(at: Int) = decodeToString(at, at + 4)
    private fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(at: Int) = u16(at) or (u16(at + 2) shl 16)

    @Test
    fun headerIs44LittleEndianBytesForMono16BitPcm() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val wav = WavHeader.wrapPcm16Mono(pcm, 16_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", wav.ascii(0))
        assertEquals(36 + 6, wav.u32(4), "RIFF chunk size = 36 + data")
        assertEquals("WAVE", wav.ascii(8))
        assertEquals("fmt ", wav.ascii(12))
        assertEquals(16, wav.u32(16), "fmt chunk size")
        assertEquals(1, wav.u16(20), "PCM")
        assertEquals(1, wav.u16(22), "mono")
        assertEquals(16_000, wav.u32(24))
        assertEquals(32_000, wav.u32(28), "byte rate")
        assertEquals(2, wav.u16(32), "block align")
        assertEquals(16, wav.u16(34), "bits per sample")
        assertEquals("data", wav.ascii(36))
        assertEquals(6, wav.u32(40))
        assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
        // byte order: 16000 = 0x3E80 -> 80 3E 00 00
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x3E, 0, 0), wav.copyOfRange(24, 28))
    }

    @Test
    fun rate24kAndDetection() {
        val wav = WavHeader.wrapPcm16Mono(ByteArray(0), 24_000)
        assertEquals(44, wav.size)
        assertEquals(48_000, wav.u32(28))
        assertTrue(WavHeader.isWav(wav))
        assertFalse(WavHeader.isWav(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        assertFailsWith<IllegalArgumentException> { WavHeader.wrapPcm16Mono(byteArrayOf(1, 2, 3), 16_000) }
    }

    @Test
    fun unsupportedRatesAndWavFormatsAreRejectedBeforeSending() {
        assertFailsWith<IllegalArgumentException> { WavHeader.wrapPcm16Mono(ByteArray(4), 44_100) }
        assertFailsWith<IllegalArgumentException> { WavHeader.wrapPcm16Mono(ByteArray(4), 8_000) }

        val ok = WavHeader.wrapPcm16Mono(ByteArray(8), 16_000)
        assertEquals(WavHeader.Format(audioFormat = 1, channels = 1, sampleRate = 16_000, bitsPerSample = 16), WavHeader.parseFormat(ok))
        WavHeader.requireSupported(ok)
        WavHeader.requireSupported(WavHeader.wrapPcm16Mono(ByteArray(8), 24_000))

        fun patched(at: Int, vararg b: Int) = ok.copyOf().also { w -> b.forEachIndexed { i, v -> w[at + i] = v.toByte() } }
        val stereo = patched(22, 2, 0)
        val eightBit = patched(34, 8, 0)
        val cd = patched(24, 0x44, 0xAC, 0, 0) // 44100 Hz
        for (bad in listOf(stereo, eightBit, cd)) assertFailsWith<MetaError.Parse> { WavHeader.requireSupported(bad) }
        assertEquals(44_100, WavHeader.parseFormat(cd)!!.sampleRate)
        assertFailsWith<MetaError.Parse> { WavHeader.requireSupported(ByteArray(20)) } // not RIFF
    }

    @Test
    fun fmtChunkIsFoundAfterAnExtraChunk() {
        val ok = WavHeader.wrapPcm16Mono(ByteArray(4), 24_000)
        // RIFF header, then a 3-byte (padded to 4) "LIST" chunk, then the original fmt + data chunks
        val extra = byteArrayOf(0x4C, 0x49, 0x53, 0x54, 3, 0, 0, 0, 9, 9, 9, 0)
        val wav = ok.copyOfRange(0, 12) + extra + ok.copyOfRange(12, ok.size)
        assertEquals(24_000, WavHeader.parseFormat(wav)!!.sampleRate)
    }
}
