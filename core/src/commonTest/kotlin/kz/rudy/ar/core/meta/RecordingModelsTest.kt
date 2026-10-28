package kz.rudy.ar.core.meta

import kotlinx.coroutines.test.runTest
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.io.MapFixtureSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingModelsTest {
    private val root = "rec"

    /** In-memory [FixtureWriter] that doubles as the [FixtureSource] the replay reads. */
    private class MemoryWriter(private val root: String) : FixtureWriter {
        val source = MapFixtureSource()
        val written = ArrayList<String>()
        override fun write(path: String, bytes: ByteArray) {
            written += path
            source.put("$root/$path", bytes)
        }
    }

    /** Advances 250 ms per reading, so every timed call measures 250 ms. 2026-10-28T12:00:00Z. */
    private fun clock(): () -> Long {
        var t = 1_793_188_800_000L
        return { t.also { t += 250 } }
    }

    @Test
    fun recordingRoundTripsThroughFixtureApi() = runTest {
        val sheet = FakeModels.DEFAULT_SHEET.copy(ability = Ability.FLY)
        val fake = FakeModels(scores = listOf(0.55f, 0.8f), sheet = sheet)
        val writer = MemoryWriter(root)
        val rec = RecordingModels(fake, writer, clock())
        val photo = byteArrayOf(9)

        // A 2-attempt character, in pipeline order.
        val masks = rec.segment("cat-01", photo, Jobs.CUTOUT_PROMPTS).masks
        fake.sprite = byteArrayOf(1, 1)
        rec.stylize("cat-01", photo, emptyList())
        assertEquals(0.55f, rec.scoreStyle("cat-01", photo, emptyList()))
        fake.sprite = byteArrayOf(2, 2)
        rec.stylize("cat-01", photo, emptyList())
        assertEquals(0.8f, rec.scoreStyle("cat-01", photo, emptyList()))
        assertEquals(sheet, rec.characterSheet("cat-01", ""))

        val replay = FixtureApi(writer.source, root)
        assertEquals(masks, replay.segment("cat-01", photo, Jobs.CUTOUT_PROMPTS).masks)
        assertContentEquals(byteArrayOf(1, 1), replay.stylize("cat-01", photo, emptyList()))
        assertEquals(0.55f, replay.scoreStyle("cat-01", photo, emptyList()))
        assertContentEquals(byteArrayOf(2, 2), replay.stylize("cat-01", photo, emptyList()))
        assertEquals(0.8f, replay.scoreStyle("cat-01", photo, emptyList()))
        assertEquals(sheet, replay.characterSheet("cat-01", ""))

        for (job in listOf(Jobs.SAM_CUTOUT, Jobs.MUSE_STYLIZE, Jobs.SPARK_SCORE, Jobs.SPARK_SHEET)) {
            val fx = replay.load(job, "cat-01")
            assertFalse(fx.synthetic, job)
            assertEquals(Fixture.SCHEMA, fx.schema, job)
            assertEquals("recorded by cli character --live on 2026-10-28", fx.note, job)
            assertEquals(250L, fx.latencyMs, "$job latency is the first call's wall time")
        }
        assertEquals(listOf("cat-01.out-1.png", "cat-01.out-2.png"), replay.load(Jobs.MUSE_STYLIZE, "cat-01").outputs)
        assertEquals("fly", replay.load(Jobs.SPARK_SHEET, "cat-01").sheet!!["ability"].toString().trim('"'))
    }

    @Test
    fun recordingKeepsStyleAndTechniqueScoresApart() = runTest {
        val fake = FakeModels(scores = listOf(0.3f, 0.85f), techniqueScores = listOf(0.5f))
        val writer = MemoryWriter(root)
        val rec = RecordingModels(fake, writer, clock())
        val img = byteArrayOf(1)

        // Pipeline order: style fails, colour-free look fails, retry passes on style.
        rec.scoreStyle("cat-01", img, emptyList())
        rec.scoreTechnique("cat-01", img, emptyList())
        rec.scoreStyle("cat-01", img, emptyList())

        val replay = FixtureApi(writer.source, root)
        val fx = replay.load(Jobs.SPARK_SCORE, "cat-01")
        assertEquals(listOf(0.3f, 0.85f), fx.scores, "a technique write keeps the style list")
        assertEquals(listOf(0.5f), fx.techniqueScores)
        assertEquals(0.3f, replay.scoreStyle("cat-01", img, emptyList()))
        assertEquals(0.5f, replay.scoreTechnique("cat-01", img, emptyList()))
        assertEquals(0.85f, replay.scoreStyle("cat-01", img, emptyList()), "the two lists advance independently")
    }

    @Test
    fun techniqueScoreFromAnOldFixtureThrows() = runTest {
        val writer = MemoryWriter(root)
        RecordingModels(FakeModels(scores = listOf(0.9f)), writer, clock()).scoreStyle("cat-01", byteArrayOf(1), emptyList())
        val replay = FixtureApi(writer.source, root)
        assertEquals(null, replay.load(Jobs.SPARK_SCORE, "cat-01").techniqueScores, "absent, not an empty list")
        val e = kotlin.test.assertFailsWith<MetaError.Parse> { replay.scoreTechnique("cat-01", byteArrayOf(1), emptyList()) }
        assertTrue("techniqueScores" in (e.message ?: ""))
    }

    @Test
    fun recordingSkipsSecondSegmentForSameRef() = runTest {
        val fake = FakeModels()
        val writer = MemoryWriter(root)
        val rec = RecordingModels(fake, writer, clock())

        rec.segment("cat-01", byteArrayOf(1), Jobs.CUTOUT_PROMPTS)
        val first = writer.source.readText("$root/sam-cutout/cat-01.fixture.json")
        fake.masks = listOf(MaskResult("drawing", 2, 2, rle = listOf(0, 4)))
        val second = rec.segment("cat-01", byteArrayOf(2), Jobs.CUTOUT_PROMPTS)

        assertEquals(fake.masks, second.masks, "the call still reaches the inner model")
        assertEquals(2, fake.calls.count { it == "segment:cat-01" })
        assertEquals(listOf("sam-cutout/cat-01.fixture.json"), writer.written, "the matte-fallback call is not recorded")
        assertEquals(first, writer.source.readText("$root/sam-cutout/cat-01.fixture.json"))

        // A different ref is recorded on its own first call; a table scan is never recorded.
        rec.segment("dog-01", byteArrayOf(1), listOf("cup", "book"))
        rec.segment("toy-01", byteArrayOf(1), Jobs.CUTOUT_PROMPTS)
        assertEquals(listOf("sam-cutout/cat-01.fixture.json", "sam-cutout/toy-01.fixture.json"), writer.written)
        assertTrue(writer.source.exists("$root/sam-cutout/toy-01.fixture.json"))
    }
}
