package kz.rudy.ar.core.meta

import kotlinx.coroutines.test.runTest
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.PoseSpec
import kz.rudy.ar.core.io.MapFixtureSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PoseFixtureTest {
    private val root = "docs/api-samples"

    @Test
    fun fixtureApiPicksTheFrameByNameWhateverTheCallOrder() = runTest {
        val s = MapFixtureSource()
        s.putText(
            "$root/muse-pose/sample-02.fixture.json",
            """{"schema":1,"job":"muse-pose","ref":"sample-02","synthetic":true,"outputs":["sample-02.roar-2.png","sample-02.hello-1.png","roar-1.png"]}""",
        )
        s.put("$root/muse-pose/sample-02.roar-2.png", byteArrayOf(4))
        s.put("$root/muse-pose/sample-02.hello-1.png", byteArrayOf(1))
        s.put("$root/muse-pose/roar-1.png", byteArrayOf(3))
        val api = FixtureApi(s, root)
        val sprite = byteArrayOf(0)

        assertContentEquals(byteArrayOf(4), api.drawPose("sample-02", sprite, emptyList(), PoseSpec(Emote.ROAR, 1)))
        assertContentEquals(byteArrayOf(1), api.drawPose("sample-02", sprite, emptyList(), PoseSpec(Emote.HELLO, 0)))
        assertContentEquals(byteArrayOf(3), api.drawPose("sample-02", sprite, emptyList(), PoseSpec(Emote.ROAR, 0)))
        assertContentEquals(byteArrayOf(4), api.drawPose("sample-02", sprite, emptyList(), PoseSpec(Emote.ROAR, 1)), "a replay, not a counter")
        assertFailsWith<MetaError.Parse> { api.drawPose("sample-02", sprite, emptyList(), PoseSpec(Emote.HELLO, 1)) }
        assertFailsWith<MetaError.FixtureMissing> { api.drawPose("sample-03", sprite, emptyList(), PoseSpec(Emote.HELLO, 0)) }
    }

    @Test
    fun theSpikeFramesReplayForToyDino() = runTest {
        val api = FixtureApi(DiskFixtureSource.repoRoot(), root)
        val sprite = byteArrayOf(0)
        for (spec in PoseSpec.DRAWN) {
            val png = api.drawPose("toy_dino", sprite, emptyList(), spec)
            assertTrue(png.size > 10_000, "${spec.name}: ${png.size} bytes")
            assertEquals(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.take(4), "${spec.name} is a PNG")
        }
        val meta = api.meta(Jobs.MUSE_POSE)!!
        assertEquals(false, meta.synthetic)
        assertEquals(0.01, meta.costUsd)
        assertEquals(false, api.load(Jobs.MUSE_POSE, "toy_dino").synthetic)
    }

    @Test
    fun posePromptsCoverEveryDrawnFrameAndNothingElse() {
        for (spec in PoseSpec.DRAWN) assertTrue(Prompts.pose(spec)!!.contains("Plain white background"), spec.name)
        assertEquals(null, Prompts.pose(PoseSpec(Emote.DANCE, 0)))
        assertEquals(null, Prompts.pose(PoseSpec(Emote.HELLO, 2)))
        assertTrue(Prompts.pose(PoseSpec(Emote.HELLO, 0))!!.contains("waving one arm high"))
        assertTrue(Prompts.pose(PoseSpec(Emote.ROAR, 1))!!.contains("eyes squeezed"))
    }
}
