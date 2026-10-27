package kz.rudy.ar.core.meta

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.io.MapFixtureSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Raw replies below are hand-written in the shapes the docs specify (https://dev.meta.ai/docs, verified 2026-10-28);
 * SAM replies are non-streaming Responses objects whose text is SAM token output, one reply per prompt.
 */
class FixtureConverterTest {
    private fun convert(job: String, raw: String, ref: String = "probe-01", latencyMs: Long? = null) =
        FixtureConverter.fromResponse(job, ref, raw, latencyMs, synthetic = false, note = "converted from test")

    private fun convertSam(job: String, replies: List<Pair<String, String>>, ref: String = "probe-01", latencyMs: Long? = null) =
        FixtureConverter.fromSamResponses(job, ref, replies, latencyMs, synthetic = false, note = "converted from test")

    /** One SAM record: the [rows] raster (one_bit, encoded with the documented scheme) at (x1, y1) of a fw x fh frame. */
    private fun record(id: Int, x1: Int, y1: Int, rows: List<String>, fw: Int, fh: Int, c: Float? = null): String {
        val mw = rows[0].length; val mh = rows.size
        val payload = SamMaskCodec.encodeOneBit(BooleanArray(mw * mh) { rows[it / mw][it % mw] == '1' }, mw, mh)
        val conf = c?.let { ";c=$it" }.orEmpty()
        return "$id<|box;x1=$x1;y1=$y1;x2=${x1 + mw - 1};y2=${y1 + mh - 1};w=$fw;h=$fh$conf|><|mask;x=0;y=0$conf;data=$mh,$mw,$payload|>"
    }

    /** Responses wrapper with the token [text] in output[0].content[0].text (and optionally top-level output_text). */
    private fun reply(text: String, topLevel: Boolean = false): String {
        val t = JsonPrimitive(text).toString()
        val top = if (topLevel) ""","output_text":$t""" else ""
        return """{"id":"resp_redacted","object":"response","status":"completed","model":"sam-3.1",
            "output":[{"id":"msg_1","type":"message","role":"assistant","status":"completed",
            "content":[{"type":"output_text","text":$t,"annotations":[]}]}]$top,
            "usage":{"input_tokens":900,"output_tokens":40,"total_tokens":940}}"""
    }

    @Test
    fun samCutoutRepliesBecomeOneMaskPerPrompt() {
        val drawing = reply("<0f>" + record(0, 1, 0, listOf("11", "01"), 4, 2, c = 0.91f) + "\n")
        val toy = reply("")
        val fx = convertSam(Jobs.SAM_CUTOUT, listOf("drawing" to drawing, "toy" to toy))
        assertEquals(Fixture.SCHEMA, fx.schema)
        assertEquals(Jobs.SAM_CUTOUT, fx.job)
        assertEquals("probe-01", fx.ref)
        assertFalse(fx.synthetic)
        assertEquals(listOf("drawing", "toy"), fx.masks!!.map { it.label })
        val m = fx.masks!![0]
        assertEquals(3, m.area)
        assertEquals(listOf(1, 2, 3, 1, 1), m.rle, "row-major RLE: OFF1 ON2 OFF3 ON1 OFF1 over 4x2")
        assertEquals(0.91f, m.score)
        assertTrue(fx.masks!![1].isEmpty)
        assertEquals(4 to 2, fx.masks!![1].width to fx.masks!![1].height, "absent mask has the frame size of its sibling")
        assertNull(fx.scores)
    }

    @Test
    fun samTableRepliesKeepOneMaskPerPromptInOrder() {
        val labels = listOf("cup", "book", "phone", "bottle", "hand", "object")
        val replies = labels.mapIndexed { i, l ->
            l to if (l == "object") reply("") else reply("<0f>" + record(0, i, i, listOf("11", "11", "11"), 8, 8, c = 0.8f), topLevel = i % 2 == 0)
        }
        val fx = convertSam(Jobs.SAM_TABLE, replies, ref = "table-01", latencyMs = 2300)
        assertEquals(6, fx.masks!!.size)
        assertEquals(labels, fx.masks!!.map { it.label })
        assertEquals(BBox(2, 2, 2, 3), fx.masks!![2].bbox)
        assertEquals(BBox(2, 2, 2, 3), fx.masks!![2].bounds())
        assertTrue(fx.masks!!.take(5).all { it.rle!!.sum() == 64 })
        assertTrue(fx.masks!![5].isEmpty, "absent object is an empty mask, not an error")
        assertEquals(2300L, fx.latencyMs)
    }

    @Test
    fun samRigRepliesBecomeMasks() {
        val fx = convertSam(Jobs.SAM_RIG, listOf(
            "arm" to reply("<0f>" + record(3, 0, 0, listOf("1"), 2, 2)),
            "leg" to reply("<0f>" + record(4, 1, 1, listOf("1"), 2, 2)),
        ))
        assertEquals(listOf("arm", "leg"), fx.masks!!.map { it.label })
        assertEquals(listOf(listOf(0, 1, 3), listOf(3, 1)), fx.masks!!.map { it.rle })
    }

    @Test
    fun samJobsRefuseTheSingleReplyPath() {
        assertFailsWith<IllegalArgumentException> { convert(Jobs.SAM_TABLE, reply("")) }
        assertFailsWith<IllegalArgumentException> { convertSam(Jobs.SPARK_SCORE, listOf("x" to "{}")) }
        assertFailsWith<IllegalArgumentException> { convertSam(Jobs.SAM_TABLE, emptyList()) }
    }

    @Test
    fun malformedSamTextFailsWithParseNamingJobAndPrompt() {
        val bad = reply("<0f>0<|box;x1=0;y1=0;x2=9;y2=0;w=4;h=2|><|mask;x=0;y=0;data=1,10,!!!!!!|>")
        val e = assertFailsWith<MetaError.Parse> { convertSam(Jobs.SAM_TABLE, listOf("cup" to reply(""), "book" to bad)) }
        val msg = e.message!!
        assertTrue(msg.startsWith("sam-table:"), msg)
        assertTrue("prompt 'book'" in msg, msg)
        assertTrue(bad.take(200) in msg, "message carries the start of the raw body")
        assertFailsWith<MetaError.Parse> { convertSam(Jobs.SAM_CUTOUT, listOf("drawing" to "not json")) }
    }

    @Test
    fun samReplyFileNamesSplitLikeTheProbeWritesThem() {
        assertEquals("table-01" to "cup", FixtureConverter.splitSamResponseName("table-01.cup.response.json"))
        assertEquals("sample-01" to "drawing", FixtureConverter.splitSamResponseName("sample-01.input.drawing.response.json"))
        assertEquals("table-01" to "red_cup", FixtureConverter.splitSamResponseName("table-01.red_cup.response.json"))
        assertNull(FixtureConverter.splitSamResponseName("table-01.response.json"))
        assertNull(FixtureConverter.splitSamResponseName("table-01.cup.headers.txt"))
        assertEquals("red_cup", FixtureConverter.samPromptToken("red cup"))
        assertEquals("cup", FixtureConverter.samPromptToken("cup"))
    }

    @Test
    fun museRedactedReplyPointsAtSiblingPng() {
        val raw = """{"created":1761000000,"data":[{"b64_json":"<base64 48211 bytes>"}],"output_format":"png","background":"opaque",
            "usage":{"input_tokens":9831,"output_tokens":749,"total_tokens":10580}}"""
        val fx = convert(Jobs.MUSE_STYLIZE, raw, ref = "cat-01")
        assertEquals(listOf("cat-01.out-1.png"), fx.outputs)
        assertTrue("cat-01.out-1.png must be saved" in fx.note, fx.note)
        assertNull(FixtureConverter.inlineStylizeBase64(raw), "redacted payload has no inline image")

        val inline = """{"data":[{"b64_json":"iVBORw0KGgo="}]}"""
        assertEquals("iVBORw0KGgo=", FixtureConverter.inlineStylizeBase64(inline))
        assertEquals("converted from test", convert(Jobs.MUSE_STYLIZE, inline).note)
    }

    @Test
    fun sparkScoreReplyBecomesScoresAndRoundTripsThroughFixtureApi() = runTest {
        val raw = """{"id":"chatcmpl-x","object":"chat.completion","model":"muse-spark-1.3-contributor",
            "choices":[{"index":0,"message":{"role":"assistant","content":"0.85"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":900,"completion_tokens":2}}"""
        val fx = convert(Jobs.SPARK_SCORE, raw, ref = "sample-01")
        assertEquals(listOf(0.85f), fx.scores)

        val source = MapFixtureSource()
        source.putText("root/spark-score/sample-01.fixture.json", FixtureApi.json.encodeToString(Fixture.serializer(), fx))
        assertEquals(0.85f, FixtureApi(source, "root").scoreStyle("sample-01", byteArrayOf(), emptyList()))
    }

    @Test
    fun sparkSheetReplyKeepsModelJson() = runTest {
        val content = "```json\n{\"name\":\"Қоян\",\"personality\":\"Shy.\",\"ability\":\"swim\"," +
            "\"lines\":{\"kk\":[\"Сәлем!\"],\"ru\":[\"Привет!\"]}}\n```"
        val raw = """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]}"""
        val fx = convert(Jobs.SPARK_SHEET, raw, ref = "sample-01")
        val sheet = fx.sheet!!
        assertTrue("ability" in sheet)
        assertEquals(JsonPrimitive("swim"), sheet["ability"], "stored as returned, not normalised")

        val source = MapFixtureSource()
        source.putText("root/spark-sheet/sample-01.fixture.json", FixtureApi.json.encodeToString(Fixture.serializer(), fx))
        val parsed = FixtureApi(source, "root").characterSheet("sample-01", "")
        assertEquals(Ability.JUMP, parsed.ability)
        assertEquals(5, parsed.lines.getValue("kk").size)
    }

    @Test
    fun voiceReplyBecomesTranscript() {
        val raw = """{"sessionId":"9f1c","transcript":"секір","audioDurationMs":900,"turns":[]}"""
        assertEquals("секір", convert(Jobs.VOICE, raw).text)
        val diarized = """{"sessionId":"s","transcript":"ұш","audioDurationMs":1200,
            "turns":[{"turnId":1,"startMs":100,"endMs":900,"transcript":"ұш","speaker":"A"}]}"""
        assertEquals("ұш", convert(Jobs.VOICE, diarized).text)
        assertFailsWith<MetaError.Parse> { convert(Jobs.VOICE, """{"text":"секір"}""") } // `text` is not a field of this API
    }

    @Test
    fun samTableAndMuseFixturesRoundTripThroughFixtureApi() = runTest {
        val table = convertSam(Jobs.SAM_TABLE, listOf(
            "cup" to reply("<0f>" + record(0, 1, 0, listOf("11", "11"), 4, 2, c = 0.8f)),
            "book" to reply("<0f>" + record(0, 0, 0, listOf("1"), 4, 2)),
            "hand" to reply(""),
        ), ref = "table-01")
        val muse = convert(Jobs.MUSE_STYLIZE, """{"data":[{"b64_json":"<base64 900 bytes>"}]}""", ref = "table-01")
        val source = MapFixtureSource()
        source.putText("root/sam-table/table-01.fixture.json", FixtureApi.json.encodeToString(Fixture.serializer(), table))
        source.putText("root/muse-stylize/table-01.fixture.json", FixtureApi.json.encodeToString(Fixture.serializer(), muse))
        source.put("root/muse-stylize/table-01.out-1.png", byteArrayOf(7))
        val api = FixtureApi(source, "root")

        val seg = api.segment("table-01", byteArrayOf(), listOf("cup", "book", "hand"))
        assertEquals(table.masks, seg.masks)
        assertEquals(BBox(1, 0, 2, 2), seg.byLabel("cup")!!.bbox)
        assertEquals(listOf(0, 1, 7), seg.byLabel("book")!!.rle)
        assertTrue(seg.byLabel("hand")!!.isEmpty)
        assertContentEquals(byteArrayOf(7), api.stylize("table-01", byteArrayOf(), emptyList()))
    }

    @Test
    fun malformedReplyAndUnknownJobFail() {
        val e = assertFailsWith<MetaError.Parse> { convert(Jobs.SPARK_SCORE, """{"choices":[]}""") }
        assertTrue(e.message!!.startsWith("spark-score:"), e.message)
        assertFailsWith<MetaError.Parse> { convert(Jobs.VOICE, "not json") }
        assertFailsWith<IllegalArgumentException> { convert("sam-everything", "{}") }
    }
}
