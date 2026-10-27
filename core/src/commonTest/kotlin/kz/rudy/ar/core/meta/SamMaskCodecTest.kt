/*
 * This file is licensed under the SAM License (see LICENSES/SAM-LICENSE.txt), not the repository's MIT licence;
 * derived from @meta-sam/parser 0.1.0, Copyright (c) Meta Platforms, Inc. and affiliates.
 */
package kz.rudy.ar.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The SAM mask codec against Meta's own vectors: meta-models/meta-sam `conformance/cases` JSON files (SAM License,
 * Copyright (c) Meta Platforms, Inc. and affiliates) and the payloads printed on
 * https://dev.meta.ai/docs/sam/reading-segmentation. A raster string is row-major, '1' = ON.
 */
class SamMaskCodecTest {
    private fun bits(s: String) = BooleanArray(s.length) { s[it] == '1' }
    private fun str(b: BooleanArray) = b.joinToString("") { if (it) "1" else "0" }

    @Test
    fun alphabetIsTheDocumented85Digits() {
        assertEquals("!#$%&'()*+-./0123456789:=?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abcdefghijklmnopqrstuvwxyz{", SamMaskCodec.ALPHABET)
        assertEquals(85, SamMaskCodec.ALPHABET.length)
    }

    @Test
    fun oneBitConformanceVectorsDecode() {
        // compact-api-confidence / synthetic-image-chunking: 5x5
        assertEquals(ONE_BIT_5X5, str(SamMaskCodec.decode("!!!!!(QO(0lu8?", 5, 5)))
        // synthetic-video-basic: width 3, height 2
        assertEquals("101010", str(SamMaskCodec.decode("!!!!!&[{Vkw", 3, 2)))
    }

    @Test
    fun oneBitEncoderReproducesMetaPayloadsExactly() {
        assertEquals("!!!!!(QO(0lu8?", SamMaskCodec.encodeOneBit(bits(ONE_BIT_5X5), 5, 5))
        assertEquals("!!!!!&[{Vkw", SamMaskCodec.encodeOneBit(bits("101010"), 3, 2))
    }

    @Test
    fun oneBitRoundTripsAnOddShape() {
        val w = 13; val h = 7
        val raster = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; (x - 6) * (x - 6) + (y - 3) * (y - 3) <= 9 || x == 0 }
        val payload = SamMaskCodec.encodeOneBit(raster, w, h)
        assertEquals('!', payload[0])
        assertTrue(payload.drop(1).all { it in SamMaskCodec.ALPHABET }, payload)
        assertEquals(str(raster), str(SamMaskCodec.decode(payload, w, h)))
    }

    @Test
    fun losslessConformanceVectorsDecode() {
        // compact-api-image-lossless: width 3, height 2, asymmetric threshold vector
        assertEquals("001101", str(SamMaskCodec.decode("~!!!!.!0^zlTde]:)]`W", 3, 2)))
        // captured-api-image-lossless: a verbatim live SAM 3 reply, 11x11
        assertEquals(CAPTURED_RASTER, str(SamMaskCodec.decode(CAPTURED_PAYLOAD, 11, 11)))
    }

    @Test
    fun docsPagePayloadsDecodeToNonEmptyMasks() {
        val a = SamMaskCodec.decode("~!!!!M!0c[0o91w?q1!pIH4pPRVp2B3'7`e.ioeAf6-k/#Xd8%dX9x(", 60, 27)
        val b = SamMaskCodec.decode("~!!!!J!0c[q=Pj_zs=*4C4(/./x#:/`S_GnD`=o3?X{emCgO\$y@", 48, 27)
        assertEquals(27 * 60, a.size)
        assertTrue(a.count { it } > 27 * 60 / 4, "button mask should cover a good part of its box")
        assertTrue(b.count { it } > 27 * 48 / 4)
    }

    @Test
    fun malformedPayloadsAreRejected() {
        assertFailsWith<SamMaskCodec.InvalidMask> { SamMaskCodec.decode("?!!!!(QO(0lu8?", 5, 5) } // no marker
        assertFailsWith<SamMaskCodec.InvalidMask> { SamMaskCodec.decode("!!!!!(QO(0lu8", 5, 5) } // length != prefix
        assertFailsWith<SamMaskCodec.InvalidMask> { SamMaskCodec.decode("!!!!!(QO(0lu8}", 5, 5) } // '}' is not a digit
        assertFailsWith<SamMaskCodec.InvalidMask> { SamMaskCodec.decode("!!!!!(QO(0lu8?", 5, 4) } // wrong dims -> not canonical
        assertFailsWith<SamMaskCodec.InvalidMask> { SamMaskCodec.decode("!!!!!(QO(0lu8?", 0, 5) }
        assertFailsWith<SamMaskCodec.InvalidMask> { SamMaskCodec.decode("~!!!!", 3, 2) } // truncated lossless
    }

    companion object {
        /** Rows 10010 / 00110 / 00000 / 10111 / 10001 (compact-api-confidence). */
        const val ONE_BIT_5X5 = "1001000110000001011110001"
        const val CAPTURED_PAYLOAD = "~!!!!r!kCq#\$.pF[_cZIw.?wO3R8-&NJofiCI\$_J0aA.1VC*YyK_'z*(LEG8!+H9evY.82Z-4.@C[.9C@T86o\$3E=-&8w3W=]xS."
        const val CAPTURED_RASTER =
            "0000110000000111111100011111111100111111111011111111111111111111110111111111101111111111011111111100011111111000001110000"
        const val CAPTURED_LINE = "<0f>0<|box;x1=513;y1=371;x2=523;y2=381;w=1440;h=900|><|mask;x=0;y=0;data=11,11,$CAPTURED_PAYLOAD|>"
    }
}
