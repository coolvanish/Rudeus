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

/** Raw replies below are hand-written in the shape MetaDtos.kt currently assumes (unverified until the Day 1 probe). */
class FixtureConverterTest {
    private fun convert(job: String, raw: String, ref: String = "probe-01", latencyMs: Long? = null) =
        FixtureConverter.fromResponse(job, ref, raw, latencyMs, synthetic = false, note = "converted from test")

    @Test
    fun samCutoutReplyBecomesMasks() {
        val raw = """{"width":4,"height":2,"request_id":"redacted","masks":[
            {"prompt":"drawing","rle":[1,2,3,1,1],"score":0.91},
            {"prompt":"toy","rle":[8],"score":0.02}]}"""
        val fx = convert(Jobs.SAM_CUTOUT, raw)
        assertEquals(Fixture.SCHEMA, fx.schema)
        assertEquals(Jobs.SAM_CUTOUT, fx.job)
        assertEquals("probe-01", fx.ref)
        assertFalse(fx.synthetic)
        assertEquals(listOf("drawing", "toy"), fx.masks!!.map { it.label })
        assertEquals(3, fx.masks!![0].area)
        assertEquals(0.91f, fx.masks!![0].score)
        assertTrue(fx.masks!![1].isEmpty)
        assertNull(fx.scores)
    }

    @Test
    fun samTableReplyKeepsOneMaskPerPromptInOrder() {
        val labels = listOf("cup", "book", "phone", "bottle", "hand", "box")
        val masks = labels.mapIndexed { i, l ->
            if (l == "box") """{"label":"$l","width":8,"height":8,"rle":[64]}"""
            else """{"label":"$l","width":8,"height":8,"bbox":[$i,$i,2,3],"score":0.8}"""
        }
        val raw = """{"masks":[${masks.joinToString(",")}]}"""
        val fx = convert(Jobs.SAM_TABLE, raw, ref = "table-01", latencyMs = 2300)
        assertEquals(6, fx.masks!!.size)
        assertEquals(labels, fx.masks!!.map { it.label })
        assertEquals(BBox(2, 2, 2, 3), fx.masks!![2].bbox)
        assertTrue(fx.masks!![5].isEmpty, "absent object is an empty mask, not an error")
        assertEquals(2300L, fx.latencyMs)
    }

    @Test
    fun samRigReplyBecomesMasks() {
        val raw = """{"width":2,"height":2,"masks":[{"label":"arm","rle":[0,1,3]},{"label":"leg","rle":[3,1]}]}"""
        val fx = convert(Jobs.SAM_RIG, raw)
        assertEquals(listOf("arm", "leg"), fx.masks!!.map { it.label })
        assertEquals(listOf(1, 1), fx.masks!!.map { it.area })
    }

    @Test
    fun museRedactedReplyPointsAtSiblingPng() {
        val raw = """{"created":1761000000,"data":[{"b64_json":"<base64 48211 bytes>"}]}"""
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
        val raw = """{"id":"chatcmpl-x","object":"chat.completion","model":"spark-1.3",
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
    fun voiceReplyBecomesText() {
        assertEquals("секір", convert(Jobs.VOICE, """{"text":"секір","language":"kk"}""").text)
        assertEquals("ұш", convert(Jobs.VOICE, """{"transcript":"ұш"}""").text)
    }

    @Test
    fun rleNotSummingToImageSizeFailsWithParse() {
        val raw = """{"width":4,"height":2,"masks":[{"label":"drawing","rle":[1,2,3]}]}"""
        val e = assertFailsWith<MetaError.Parse> { convert(Jobs.SAM_CUTOUT, raw) }
        val msg = e.message!!
        assertTrue(msg.startsWith("sam-cutout:"), msg)
        assertTrue("rle sums to 6, expected 8" in msg, msg)
        assertTrue(raw.take(200) in msg, "message carries the start of the raw body")
    }

    @Test
    fun samTableAndMuseFixturesRoundTripThroughFixtureApi() = runTest {
        val raw = """{"width":4,"height":2,"masks":[
            {"label":"cup","bbox":[1,0,2,2],"score":0.8},{"label":"book","rle":[1,2,3,1,1]},{"label":"hand","rle":[8]}]}"""
        val table = convert(Jobs.SAM_TABLE, raw, ref = "table-01")
        val muse = convert(Jobs.MUSE_STYLIZE, """{"data":[{"b64_json":"<base64 900 bytes>"}]}""", ref = "table-01")
        val source = MapFixtureSource()
        source.putText("root/sam-table/table-01.fixture.json", FixtureApi.json.encodeToString(Fixture.serializer(), table))
        source.putText("root/muse-stylize/table-01.fixture.json", FixtureApi.json.encodeToString(Fixture.serializer(), muse))
        source.put("root/muse-stylize/table-01.out-1.png", byteArrayOf(7))
        val api = FixtureApi(source, "root")

        val seg = api.segment("table-01", byteArrayOf(), listOf("cup", "book", "hand"))
        assertEquals(table.masks, seg.masks)
        assertEquals(BBox(1, 0, 2, 2), seg.byLabel("cup")!!.bbox)
        assertEquals(listOf(1, 2, 3, 1, 1), seg.byLabel("book")!!.rle)
        assertTrue(seg.byLabel("hand")!!.isEmpty)
        assertContentEquals(byteArrayOf(7), api.stylize("table-01", byteArrayOf(), emptyList()))
    }

    @Test
    fun nullMasksFailLikeTheLiveDecoder() {
        val e = assertFailsWith<MetaError.Parse> { convert(Jobs.SAM_TABLE, """{"width":4,"height":2,"masks":null}""") }
        assertTrue(e.message!!.startsWith("sam-table:"), e.message)
    }

    @Test
    fun malformedReplyAndUnknownJobFail() {
        val e = assertFailsWith<MetaError.Parse> { convert(Jobs.SPARK_SCORE, """{"choices":[]}""") }
        assertTrue(e.message!!.startsWith("spark-score:"), e.message)
        assertFailsWith<MetaError.Parse> { convert(Jobs.VOICE, "not json") }
        assertFailsWith<IllegalArgumentException> { convert("sam-everything", "{}") }
    }
}
