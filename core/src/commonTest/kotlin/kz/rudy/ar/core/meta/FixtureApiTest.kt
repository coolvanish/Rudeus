package kz.rudy.ar.core.meta

import kotlinx.coroutines.test.runTest
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.io.MapFixtureSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureApiTest {
    private val root = "docs/api-samples"

    private fun source(): MapFixtureSource {
        val s = MapFixtureSource()
        s.putText(
            "$root/spark-score/meta.json",
            """{"job":"spark-score","endpoint":"/chat/completions","model":"spark-1.3","latencyMs":1200,"costUsd":0.002,"probedAt":"","synthetic":true}""",
        )
        s.putText(
            "$root/spark-score/sample-02.fixture.json",
            """{"schema":1,"job":"spark-score","ref":"sample-02","synthetic":true,"scores":[0.55,0.65,0.75]}""",
        )
        s.putText(
            "$root/sam-cutout/sample-02.fixture.json",
            """{"schema":1,"job":"sam-cutout","ref":"sample-02","synthetic":true,
                "masks":[{"label":"drawing","width":4,"height":2,"rle":[1,2,3,1,1],"score":0.9},
                         {"label":"toy","width":4,"height":2,"rle":[8]}]}""",
        )
        s.putText(
            "$root/muse-stylize/sample-02.fixture.json",
            """{"schema":1,"job":"muse-stylize","ref":"sample-02","synthetic":true,"outputs":["sample-02.out-1.png","sample-02.out-2.png"]}""",
        )
        s.put("$root/muse-stylize/sample-02.out-1.png", byteArrayOf(1))
        s.put("$root/muse-stylize/sample-02.out-2.png", byteArrayOf(2))
        s.putText(
            "$root/spark-sheet/sample-02.fixture.json",
            """{"schema":1,"job":"spark-sheet","ref":"sample-02","synthetic":true,
                "sheet":{"name":"Қоян","personality":"Shy but quick.","ability":"fly",
                         "lines":{"kk":["Сәлем!","Ұшайық!","Мен мұндамын!","Қарап тұр!","Жарайсың!"],
                                  "ru":["Привет!","Летим!","Я здесь!","Смотри!","Молодец!"]}}}""",
        )
        s.putText("$root/voice/sample-02.fixture.json", """{"schema":1,"job":"voice","ref":"sample-02","synthetic":true,"text":"секір"}""")
        return s
    }

    @Test
    fun fixtureApiLoadsSampleAndReplaysScores() = runTest {
        val api = FixtureApi(source(), root)
        val img = byteArrayOf(0)

        assertEquals(0.55f, api.scoreStyle("sample-02", img, emptyList()))
        assertEquals(0.65f, api.scoreStyle("sample-02", img, emptyList()))
        assertEquals(0.75f, api.scoreStyle("sample-02", img, emptyList()))
        assertEquals(0.75f, api.scoreStyle("sample-02", img, emptyList()), "last score repeats")

        val seg = api.segment("sample-02", img, Jobs.CUTOUT_PROMPTS)
        assertEquals(2, seg.masks.size)
        assertEquals("drawing", seg.largest()?.label)
        assertTrue(seg.byLabel("toy")!!.isEmpty)

        assertContentEquals(byteArrayOf(1), api.stylize("sample-02", img, emptyList()))
        assertContentEquals(byteArrayOf(2), api.stylize("sample-02", img, emptyList()))
        assertContentEquals(byteArrayOf(2), api.stylize("sample-02", img, emptyList()), "last output repeats")

        val sheet = api.characterSheet("sample-02", "a rabbit")
        assertEquals("Қоян", sheet.name)
        assertEquals(Ability.FLY, sheet.ability)
        assertEquals(5, sheet.lines.getValue("kk").size)

        assertEquals("секір", api.transcribe("sample-02", img, "kk"))
        assertEquals(1200L, api.meta(Jobs.SPARK_SCORE)?.latencyMs)
        assertEquals(listOf("sample-02"), api.listRefs(Jobs.SAM_CUTOUT))
    }

    @Test
    fun fixtureApiMissingFixtureThrowsFixtureMissing() = runTest {
        val api = FixtureApi(source(), root)
        val e = assertFailsWith<MetaError.FixtureMissing> { api.scoreStyle("nope-99", byteArrayOf(), emptyList()) }
        assertEquals("$root/spark-score/nope-99.fixture.json", e.path)

        // A fixture that references a missing sibling image is reported with the image path.
        val s = source()
        s.putText("$root/muse-stylize/broken.fixture.json", """{"schema":1,"job":"muse-stylize","outputs":["broken.out.png"]}""")
        val e2 = assertFailsWith<MetaError.FixtureMissing> { FixtureApi(s, root).stylize("broken", byteArrayOf(), emptyList()) }
        assertEquals("$root/muse-stylize/broken.out.png", e2.path)
    }

    @Test
    fun rleExpandsToExpectedMask() {
        val mask = MaskResult("drawing", 4, 2, rle = listOf(1, 2, 3, 1, 1))
        val expected = booleanArrayOf(
            false, true, true, false,
            false, false, true, false,
        )
        assertContentEquals(expected, mask.toBooleanArray())
        assertEquals(3, mask.area)
        assertEquals(BBox(1, 0, 2, 2), mask.bounds())
        assertEquals(listOf(1, 2, 3, 1, 1), MaskResult.encodeRle(expected))

        val fromBbox = MaskResult("cup", 4, 2, bbox = BBox(2, 0, 2, 2))
        assertContentEquals(booleanArrayOf(false, false, true, true, false, false, true, true), fromBbox.toBooleanArray())
        assertTrue(MaskResult("absent", 4, 2).isEmpty)
    }

    @Test
    fun scoreParserAcceptsNumbersJsonAndPercent() {
        assertEquals(0.82f, parseScore("0.82"))
        assertEquals(0.82f, parseScore("{\"score\": 0.82}"))
        assertEquals(0.82f, parseScore("Score: 82%"))
        assertEquals(1f, parseScore("1"))
        assertFailsWith<MetaError.Parse> { parseScore("no idea") }
    }

    /** T01 brief: the converted REAL Day 1 sam-table probe (docs/api-samples/sam-table/table-01.*) loads through FixtureApi. */
    @Test
    fun fixtureApiLoadsRealSamTableResponse() = runTest {
        val api = FixtureApi(DiskFixtureSource.repoRoot(), root)
        val fx = api.load(Jobs.SAM_TABLE, "table-01")
        assertFalse(fx.synthetic, "table-01 is real probe output")
        val prompts = listOf("laptop", "remote", "speaker", "toy car", "knife")
        val seg = api.segment("table-01", byteArrayOf(), prompts)
        assertTrue(seg.masks.size >= 5)
        assertEquals(prompts, seg.masks.map { it.label }, "prompts.json order")
        assertTrue(seg.masks.all { it.width == 900 && it.height == 1600 }, "every mask is the 900x1600 frame")
        val laptop = seg.byLabel("laptop")!!
        assertNotNull(laptop.bbox)
        assertEquals(900 * 1600, laptop.rle!!.sum())
        assertFalse(laptop.isEmpty)
        val speaker = seg.byLabel("speaker")!!
        assertNull(speaker.rle, "absent object: no rle")
        assertNull(speaker.bbox, "absent object: no bbox")
        assertTrue(speaker.isEmpty)
    }

    @Test
    fun fixtureApiLoadsRealSamCutoutResponse() = runTest {
        val api = FixtureApi(DiskFixtureSource.repoRoot(), root)
        assertFalse(api.load(Jobs.SAM_CUTOUT, "drawing-01").synthetic)
        val seg = api.segment("drawing-01", byteArrayOf(), Jobs.CUTOUT_PROMPTS)
        assertEquals(listOf("drawing", "toy"), seg.masks.map { it.label })
        assertTrue(seg.masks.all { it.width == 532 && it.height == 577 })
        val drawing = seg.byLabel("drawing")!!
        assertEquals(BBox(126, 20, 335, 552), drawing.bbox)
        assertEquals(532 * 577, drawing.rle!!.sum())
        assertEquals(drawing, seg.largest(), "the pipeline picks the drawing")
        val toy = seg.byLabel("toy")!!
        assertNull(toy.rle)
        assertNull(toy.bbox)
    }
}
