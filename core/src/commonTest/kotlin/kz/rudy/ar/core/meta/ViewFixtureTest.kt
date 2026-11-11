package kz.rudy.ar.core.meta

import kotlinx.coroutines.test.runTest
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.io.MapFixtureSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T41: `drawView` through every [MetaModels] in core/meta, the committed muse-view sample and the view prompts. */
class ViewFixtureTest {
    private val root = "docs/api-samples"
    private val sprite = byteArrayOf(0)

    @Test
    fun fixtureApiReplaysViewsByName() = runTest {
        val s = MapFixtureSource()
        s.putText(
            "$root/muse-view/cat-01.fixture.json",
            """{"schema":1,"job":"muse-view","ref":"cat-01","synthetic":true,"outputs":["cat-01.view-front.png","view-back.png"]}""",
        )
        s.put("$root/muse-view/cat-01.view-front.png", byteArrayOf(2))
        s.put("$root/muse-view/view-back.png", byteArrayOf(1))
        val api = FixtureApi(s, root)

        assertContentEquals(byteArrayOf(1), api.drawView("cat-01", sprite, emptyList(), SpriteView.BACK))
        assertContentEquals(byteArrayOf(2), api.drawView("cat-01", sprite, emptyList(), SpriteView.FRONT))
        assertContentEquals(byteArrayOf(1), api.drawView("cat-01", sprite, emptyList(), SpriteView.BACK), "a replay, not a counter")
        assertFailsWith<MetaError.Parse> { api.drawView("cat-01", sprite, emptyList(), SpriteView.SIDE) }
        assertFailsWith<MetaError.FixtureMissing> { api.drawView("cat-02", sprite, emptyList(), SpriteView.BACK) }

        // The committed hand-made sample: every view (its T40 sheet is a FRONT base; a SIDE base would ask for FRONT),
        // flagged synthetic, never "probed".
        val disk = FixtureApi(DiskFixtureSource.repoRoot(), root)
        for (view in SpriteView.entries) {
            val png = disk.drawView("sample-01", sprite, emptyList(), view)
            assertEquals(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.take(4), "$view is a PNG")
        }
        assertTrue(disk.load(Jobs.MUSE_VIEW, "sample-01").synthetic)
        val meta = disk.meta(Jobs.MUSE_VIEW)!!
        assertTrue(meta.synthetic)
        assertEquals("", meta.probedAt)
        assertEquals(0.01, meta.costUsd)
    }

    /** In-memory [FixtureWriter] that doubles as the source the replay reads. */
    private class MemoryWriter(private val root: String) : FixtureWriter {
        val source = MapFixtureSource()
        val written = ArrayList<String>()
        override fun write(path: String, bytes: ByteArray) {
            written += path
            source.put("$root/$path", bytes)
        }
    }

    @Test
    fun recordingModelsForwardDrawView() = runTest {
        val fake = FakeModels(views = mapOf(SpriteView.BACK to byteArrayOf(7), SpriteView.FRONT to byteArrayOf(8)))
        val writer = MemoryWriter("rec")
        var t = 1_793_188_800_000L // 2026-10-28T12:00:00Z
        val rec = RecordingModels(fake, writer, clock = { t.also { t += 250 } })

        assertContentEquals(byteArrayOf(7), rec.drawView("cat-01", sprite, emptyList(), SpriteView.BACK))
        assertContentEquals(byteArrayOf(8), rec.drawView("cat-01", sprite, emptyList(), SpriteView.FRONT))
        assertContentEquals(byteArrayOf(7), rec.drawView("cat-01", sprite, emptyList(), SpriteView.BACK))
        assertEquals(listOf("drawView:cat-01:back", "drawView:cat-01:front", "drawView:cat-01:back"), fake.calls)
        assertTrue("muse-view/cat-01.view-back.png" in writer.written)
        assertTrue("muse-view/cat-01.view-front.png" in writer.written)

        val replay = FixtureApi(writer.source, "rec")
        val fx = replay.load(Jobs.MUSE_VIEW, "cat-01")
        assertEquals(listOf("cat-01.view-back.png", "cat-01.view-front.png"), fx.outputs, "each view listed once")
        assertFalse(fx.synthetic)
        assertEquals(250L, fx.latencyMs)
        assertContentEquals(byteArrayOf(8), replay.drawView("cat-01", sprite, emptyList(), SpriteView.FRONT))
        assertContentEquals(byteArrayOf(7), replay.drawView("cat-01", sprite, emptyList(), SpriteView.BACK))

        // A failed call records nothing and rethrows.
        fake.viewError = MetaError.Http(500, "muse down")
        assertFailsWith<MetaError.Http> { rec.drawView("cat-02", sprite, emptyList(), SpriteView.BACK) }
        assertTrue(writer.written.none { it.contains("cat-02") })
    }

    @Test
    fun offlineGuardForwardsDrawView() = runTest {
        val fake = FakeModels(views = mapOf(SpriteView.SIDE to byteArrayOf(3)))
        val guard = OfflineGuard(fake)
        val refs = listOf(byteArrayOf(4))
        assertContentEquals(byteArrayOf(3), guard.drawView("g", sprite, refs, SpriteView.SIDE))
        assertEquals(listOf("drawView:g:side"), fake.calls)
        assertContentEquals(sprite, fake.viewInputs.getValue(SpriteView.SIDE))

        fake.viewError = MetaError.Network("offline")
        assertFailsWith<MetaError.Network> { guard.drawView("g", sprite, refs, SpriteView.SIDE) }
        assertTrue(guard.offline.value)
        fake.viewError = null
        guard.drawView("g", sprite, refs, SpriteView.SIDE)
        assertFalse(guard.offline.value)
    }

    @Test
    fun aModelThatDoesNotForwardDrawViewThrowsAProviderError() = runTest {
        val bare = object : MetaModels by FakeModels() {
            override suspend fun drawView(ref: String, sprite: ByteArray, refs: List<ByteArray>, view: SpriteView): ByteArray =
                super<MetaModels>.drawView(ref, sprite, refs, view)
        }
        assertFailsWith<MetaError.Provider> { bare.drawView("x", sprite, emptyList(), SpriteView.BACK) }
    }

    @Test
    fun viewPromptsKeepTheCharacterAndTheWhiteBackground() {
        for (view in SpriteView.entries) {
            val p = ViewPrompts.view(view)
            assertTrue(p.startsWith("the very same character, identical colours, outline and proportions, "), "$view: $p")
            assertTrue(p.endsWith("Keep the drawing style of the first image. Plain white background, nothing else in the picture."), "$view: $p")
        }
        assertTrue(ViewPrompts.view(SpriteView.FRONT).contains("from the front"))
        assertTrue(ViewPrompts.view(SpriteView.BACK).contains("from behind"))
        assertTrue(ViewPrompts.view(SpriteView.SIDE).contains("looking to the right"), "ViewPicker relies on drawn SIDE views looking right")
        // docs/api-samples/muse-view/request.json.tmpl shows the BACK prompt.
        val tmpl = DiskFixtureSource.repoRoot().readText("$root/muse-view/request.json.tmpl")!!
        val prompt = Regex("\"prompt\": \"([^\"]*)\"").find(tmpl)!!.groupValues[1]
        assertEquals(ViewPrompts.view(SpriteView.BACK), prompt)
        assertTrue(Jobs.MUSE_VIEW in Jobs.ALL)
        assertTrue(Jobs.MUSE_VIEW in FixtureConverter.JOBS)
    }
}
