package kz.rudy.ar.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamTokenParserTest {

    /** A record whose mask is a hand-built raster encoded with the documented one_bit scheme. */
    private fun record(id: Int, x1: Int, y1: Int, rows: List<String>, fw: Int, fh: Int, c: String? = null): String {
        val mw = rows[0].length; val mh = rows.size
        val raster = BooleanArray(mw * mh) { rows[it / mw][it % mw] == '1' }
        val payload = SamMaskCodec.encodeOneBit(raster, mw, mh)
        val conf = c?.let { ";c=$it" }.orEmpty()
        return "$id<|box;x1=$x1;y1=$y1;x2=${x1 + mw - 1};y2=${y1 + mh - 1};w=$fw;h=$fh$conf|><|mask;x=0;y=0$conf;data=$mh,$mw,$payload|>"
    }

    private fun on(m: MaskResult): Set<Pair<Int, Int>> =
        m.toBooleanArray().withIndex().filter { it.value }.map { (it.index % m.width) to (it.index / m.width) }.toSet()

    @Test
    fun boxTokenParsesToHalfOpenSourcePixels() {
        val text = "<0f>1<|box;x1=444;y1=625;x2=679;y2=850;w=1800;h=1200;c=0.9609375|>" +
            "<|mask;x=0;y=0;c=0.9609375;data=1,1,${SamMaskCodec.encodeOneBit(booleanArrayOf(true), 1, 1)}|>"
        // data=1,1 does not match the 236x226 box: parse() still reads it; only placement cares.
        val d = SamTokenParser.parse(text).single()
        assertEquals("1", d.objectId)
        assertEquals(SamTokenParser.Box(444, 625, 680, 851, 1800, 1200, 0.9609375f), d.box)
        assertEquals(236, d.box.width)
        assertEquals(226, d.box.height)
        assertEquals(SamMaskCodec.Encoding.ONE_BIT, d.mask.encoding)
        assertEquals(0.9609375f, d.mask.confidence)
    }

    @Test
    fun handBuiltMaskLandsAtItsBoxOffsetInsideTheFullFrame() {
        val text = "<0f>" + record(0, x1 = 3, y1 = 2, rows = listOf("101", "110"), fw = 8, fh = 6, c = "0.8")
        val m = SamTokenParser.maskResult("cup", text)
        assertEquals("cup", m.label)
        assertEquals(8, m.width)
        assertEquals(6, m.height)
        assertEquals(48, m.rle!!.sum(), "row-major RLE sums to w*h")
        assertEquals(setOf(3 to 2, 5 to 2, 3 to 3, 4 to 3), on(m))
        assertEquals(BBox(3, 2, 3, 2), m.bbox)
        assertEquals(0.8f, m.score)
        assertEquals(BBox(3, 2, 3, 2), m.bounds())
    }

    @Test
    fun capturedLiveReplyFromMetaConformanceCorpusDecodesInPlace() {
        val m = SamTokenParser.maskResult("wheel", SamMaskCodecTest.CAPTURED_LINE)
        assertEquals(1440, m.width)
        assertEquals(900, m.height)
        assertEquals(1440 * 900, m.rle!!.sum())
        assertEquals(89, m.area)
        assertEquals(BBox(513, 371, 11, 11), m.bounds())
        val raster = SamMaskCodecTest.CAPTURED_RASTER
        val expected = raster.indices.filter { raster[it] == '1' }.map { (513 + it % 11) to (371 + it / 11) }.toSet()
        assertEquals(expected, on(m))
        assertNull(m.score, "no include_confidence -> no score")
    }

    @Test
    fun emptyTextIsAnAbsentObjectNotAnError() {
        val m = SamTokenParser.maskResult("bottle", "", emptyWidth = 8, emptyHeight = 6)
        assertTrue(m.isEmpty)
        assertNull(m.rle)
        assertNull(m.bbox)
        assertEquals(8, m.width)
        assertTrue(SamTokenParser.parse("\n  \n").isEmpty())
    }

    @Test
    fun twoDetectionsOfOnePromptMergeIntoOneMask() {
        val text = "<0f>" + record(0, 0, 0, listOf("11", "11"), 10, 4, c = "0.6") + "," +
            record(2, 7, 1, listOf("111"), 10, 4, c = "0.9")
        assertEquals(listOf("0", "2"), SamTokenParser.parse(text).map { it.objectId })
        val m = SamTokenParser.maskResult("cup", text)
        assertEquals(7, m.area)
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 7 to 1, 8 to 1, 9 to 1), on(m))
        assertEquals(BBox(0, 0, 10, 2), m.bbox, "union of the boxes")
        assertEquals(0.9f, m.score, "highest confidence")
    }

    @Test
    fun fieldOrderWhitespaceProseAndUnknownTokensAreTolerated() {
        val payload = SamMaskCodec.encodeOneBit(booleanArrayOf(true, false), 2, 1)
        val text = "Here you go\n<0f;extra=1>5<|mask; data=1,2,$payload ; y=0;x=0|><|note;a=b|><|box;h=3;w=4;y2=0;x2=2;y1=0;x1=1;z=9|>\n"
        val m = SamTokenParser.maskResult("phone", text)
        assertEquals(setOf(1 to 0), on(m))
        assertEquals(4, m.width)
        assertEquals(3, m.height)
    }

    @Test
    fun malformedLinesFailWithParse() {
        val ok = record(0, 0, 0, listOf("1"), 4, 4)
        assertFailsWith<MetaError.Parse> { SamTokenParser.parse("<1f>$ok") } // image must be frame 0
        assertFailsWith<MetaError.Parse> { SamTokenParser.parse("<0f>0<|box;x1=0;y1=0;x2=3;y2=4;w=4;h=4|><|mask;x=0;y=0;data=5,4,!x|>") } // box outside frame
        assertFailsWith<MetaError.Parse> { SamTokenParser.parse("<0f>0<|box;x1=0;y1=0;x2=0;y2=0;w=4;h=4|>") } // no mask
        assertFailsWith<MetaError.Parse> { SamTokenParser.parse("<0f>") } // bare frame header
        assertFailsWith<MetaError.Parse> { SamTokenParser.parse("<0f>0<|box;x1=0;x1=0;y1=0;x2=0;y2=0;w=4;h=4|><|mask;x=0;y=0;data=1,1,!!!!!!|>") } // repeated key
    }

    @Test
    fun undecodablePayloadIsFatalWhenStrictAndFallsBackToTheBoxOnThePhone() {
        val text = "<0f>0<|box;x1=1;y1=1;x2=2;y2=2;w=4;h=4|><|mask;x=0;y=0;data=2,2,!!!!!(QO(0lu8?|>" // a 5x5 payload
        assertFailsWith<MetaError.Parse> { SamTokenParser.maskResult("book", text, strict = true) }
        val lenient = SamTokenParser.maskResult("book", text, strict = false)
        assertEquals(setOf(1 to 1, 2 to 1, 1 to 2, 2 to 2), on(lenient))
    }

    @Test
    fun responsesWrapperYieldsTheTextLane() {
        val line = "<0f>" + record(0, 0, 0, listOf("1"), 2, 2)
        val fromParts = ResponsesResponse(
            status = "completed",
            output = listOf(
                ResponsesOutputItem(type = "reasoning"),
                ResponsesOutputItem(type = "message", content = listOf(ResponsesOutputContent("output_text", line))),
            ),
        )
        assertEquals(line, fromParts.text())
        assertEquals(line, ResponsesResponse(outputText = line, output = emptyList()).text())
        assertEquals("", ResponsesResponse(status = "completed", output = listOf(ResponsesOutputItem(type = "message", content = emptyList()))).text())
        assertFailsWith<MetaError.Provider> { ResponsesResponse(status = "failed", error = ResponsesErrorDto("server_error", "boom")).text() }

        val masks = withFrameSize(listOf(parseSamResponse("cup", fromParts), parseSamResponse("hand", ResponsesResponse(outputText = ""))))
        assertEquals(listOf("cup", "hand"), masks.map { it.label })
        assertEquals(2 to 2, masks[1].width to masks[1].height, "absent mask takes the frame size of its siblings")
        assertTrue(masks[1].isEmpty)
    }

    @Test
    fun lenientParseSkipsMalformedLinesAndKeepsTheRest() {
        val good = "<0f>" + record(0, 1, 1, listOf("1"), 4, 4)
        val bad = "<0f>0<|box;x1=0;y1=0;x2=9;y2=0;w=4;h=4|><|mask;x=0;y=0;data=1,10,!!!!!!|>"
        val text = "$bad\n$good\n<0f>7<|box;x1=0;y1=0;x2=0;y2=0;w=4;h=4|>"
        assertFailsWith<MetaError.Parse> { SamTokenParser.parse(text) }
        assertFailsWith<MetaError.Parse> { SamTokenParser.maskResult("cup", text, strict = true) }
        assertEquals(listOf("0"), SamTokenParser.parse(text, strict = false).map { it.objectId })
        val m = SamTokenParser.maskResult("cup", text, strict = false)
        assertEquals(setOf(1 to 1), on(m))
        assertTrue(SamTokenParser.maskResult("cup", bad, strict = false).isEmpty, "only malformed lines -> absent")
    }

    @Test
    fun incompleteIsTruncatedAndRefusalIsAProviderError() {
        val line = "<0f>" + record(0, 0, 0, listOf("1"), 2, 2)
        val msg = { c: List<ResponsesOutputContent> -> listOf(ResponsesOutputItem(type = "message", content = c)) }
        val e = assertFailsWith<MetaError.Parse> { ResponsesResponse(status = "incomplete", output = msg(listOf(ResponsesOutputContent("output_text", line)))).text() }
        assertTrue("truncated" in e.message!!, e.message)
        val r = assertFailsWith<MetaError.Provider> {
            ResponsesResponse(status = "completed", output = msg(listOf(ResponsesOutputContent("refusal", refusal = "cannot help")))).text()
        }
        assertTrue("cannot help" in r.message!!, r.message)
        val decoded = MetaApi.json.decodeFromString(ResponsesResponse.serializer(),
            """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}""")
        assertFailsWith<MetaError.Provider> { decoded.text() }
    }

    @Test
    fun realProbeTokenOrderWithConfidenceBeforeData() {
        // Exactly the shape of the Day 1 sam-cutout reply: box c after h, mask c between y and data.
        val payload = SamMaskCodec.encodeOneBit(booleanArrayOf(true, true, false, true), 2, 2)
        val text = "<0f>0<|box;x1=126;y1=20;x2=127;y2=21;w=532;h=577;c=0.9452055096626282|>" +
            "<|mask;x=0;y=0;c=0.9452055096626282;data=2,2,$payload|>"
        val d = SamTokenParser.parse(text).single()
        assertEquals(0.9452055f, d.box.confidence)
        assertEquals(0.9452055f, d.mask.confidence)
        assertEquals(2 to 2, d.mask.height to d.mask.width)
        val m = SamTokenParser.maskResult("drawing", text)
        assertEquals(532 to 577, m.width to m.height)
        assertEquals(setOf(126 to 20, 127 to 20, 127 to 21), on(m))
        assertEquals(0.9452055f, m.score)
    }

    @Test
    fun instancesKeepEachDetectionSeparate() {
        // The real Day 1 "remote" reply (docs/api-samples/sam-table): it matched the remote AND the speaker.
        val raw = DiskFixtureSource.repoRoot().readText("docs/api-samples/sam-table/table-01.input.remote.response.json")!!
        val m = parseSamResponse("remote", MetaApi.json.decodeFromString(ResponsesResponse.serializer(), raw), strict = true)
        assertEquals(900 to 1600, m.width to m.height)
        assertEquals(BBox(133, 866, 438, 223), m.bbox, "merged bbox is the union (the 438x223 blob)")
        assertEquals(2, m.instances.size)
        val (best, other) = m.instances
        assertEquals(BBox(406, 950, 165, 139), best.bbox, "best score first")
        assertEquals(BBox(133, 866, 114, 156), other.bbox)
        assertTrue(best.score!! > other.score!!)
        assertEquals(m.score, best.score, "merged score is the best instance's")
        for (inst in m.instances) {
            assertEquals("remote", inst.label)
            assertEquals(m.width to m.height, inst.width to inst.height)
            assertTrue(inst.instances.isEmpty())
            assertTrue(inst.area > 0)
        }
        assertEquals(m.area, best.area + other.area, "disjoint detections: the merge is their union")
        assertEquals(on(m), on(best) + on(other))

        // Synthetic: order follows the score, not the reply; lenient decoding falls back to the box per detection.
        val bad = "1<|box;x1=6;y1=0;x2=7;y2=1;w=10;h=4;c=0.9|><|mask;x=0;y=0;data=2,2,!!!!!(QO(0lu8?|>"
        val text = "<0f>" + record(0, 0, 0, listOf("11", "01"), 10, 4, c = "0.6") + "," + bad
        assertFailsWith<MetaError.Parse> { SamTokenParser.maskResult("cup", text, strict = true) }
        val lenient = SamTokenParser.maskResult("cup", text, strict = false)
        assertEquals(listOf(0.9f, 0.6f), lenient.instances.map { it.score })
        assertEquals(setOf(6 to 0, 7 to 0, 6 to 1, 7 to 1), on(lenient.instances[0]))
        assertEquals(setOf(0 to 0, 1 to 0, 1 to 1), on(lenient.instances[1]))
        assertTrue(SamTokenParser.maskResult("cup", "", emptyWidth = 10, emptyHeight = 4).instances.isEmpty(), "absent: no instances")

        // Fixture JSON: instances round-trip; an empty list is not written and old fixtures parse as empty.
        val json = FixtureApi.json
        assertEquals(m, json.decodeFromString(MaskResult.serializer(), json.encodeToString(MaskResult.serializer(), m)))
        val plain = MaskResult("cup", 2, 1, rle = listOf(1, 1))
        assertTrue("instances" !in json.encodeToString(MaskResult.serializer(), plain))
        assertEquals(plain, json.decodeFromString(MaskResult.serializer(), """{"label":"cup","width":2,"height":1,"rle":[1,1]}"""))
    }
}
