package kz.rudy.ar.core.emote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmoteTest {
    private fun pose(tilt: Float = 0f, yaw: Float = 0f, scale: Float = 1f, hop: Float = 0f) = EmotePose(scale, scale, yaw, tilt, hop)

    @Test
    fun defaultsAreAlreadySanitized() {
        assertEquals(Emote.entries.toSet(), Choreographies.DEFAULT.keys)
        for ((emote, c) in Choreographies.DEFAULT) {
            assertEquals(emote, c.emote)
            assertEquals(c, ChoreographyRules.sanitize(c), "$emote must already be within the rules")
            assertTrue(c.keys.size <= ChoreographyRules.MAX_KEYS)
        }
        assertEquals(EmoteSet.SOURCE_DEFAULT, EmoteSet.defaults().source)
        assertEquals(Choreographies.DEFAULT.getValue(Emote.ROAR), EmoteSet(EmoteSet.SOURCE_SPARK, emptyMap())[Emote.ROAR])
    }

    @Test
    fun sampleIsRestOutsideTheChoreography() {
        val hello = Choreographies.DEFAULT.getValue(Emote.HELLO)
        assertEquals(EmotePose.REST, EmotePlayer.sample(hello, -1))
        assertEquals(EmotePose.REST, EmotePlayer.sample(hello, hello.durationMs))
        assertEquals(EmotePose.REST, EmotePlayer.sample(hello, hello.durationMs + 5_000))
        assertTrue(EmotePlayer.sample(hello, 250).tiltDeg > 14f, "mid-wave the quad leans")
        assertFalse(EmotePlayer.finished(hello, hello.durationMs - 1))
        assertTrue(EmotePlayer.finished(hello, hello.durationMs))
    }

    @Test
    fun sampleBlendsBetweenKeysWithSmoothstep() {
        val c = Choreography(Emote.HELLO, 1000, listOf(EmoteKey(0), EmoteKey(400, pose(tilt = 20f)), EmoteKey(1000)))
        assertEquals(10f, EmotePlayer.sample(c, 200).tiltDeg, 1e-4f)
        assertEquals(20f * 0.15625f, EmotePlayer.sample(c, 100).tiltDeg, 1e-4f) // smoothstep(0.25) = 0.15625, not 0.25
        assertEquals(20f, EmotePlayer.sample(c, 400).tiltDeg, 1e-4f)
        assertEquals(10f, EmotePlayer.sample(c, 700).tiltDeg, 1e-4f)
    }

    @Test
    fun aSpinTurnsTheQuadBackToTheCamera() {
        val raw = Choreography(Emote.SPIN, 1000, listOf(EmoteKey(0), EmoteKey(500, pose(yaw = 300f)), EmoteKey(1000, pose(yaw = 610f, tilt = 10f))))
        val c = assertNotNull(ChoreographyRules.sanitize(raw))
        val last = c.keys.last()
        assertEquals(1000L, last.atMs)
        assertTrue(last.pose.yawDeg % 360f == 0f)
        // 610 is nearest 720, but 720 is 420 degrees past the key at 300: the nearest whole turn within one step is 360.
        assertEquals(360f, last.pose.yawDeg)
        assertTrue(c.keys.zipWithNext().all { (a, b) -> kotlin.math.abs(b.pose.yawDeg - a.pose.yawDeg) <= ChoreographyRules.MAX_YAW_STEP_DEG })
        assertEquals(EmotePose.REST, last.pose.copy(yawDeg = 0f))
        assertEquals(EmotePose.REST, EmotePlayer.sample(c, c.durationMs))
        // Yaw blends linearly in degrees: halfway through 0 -> 300 is 150, the long way round never shortcut.
        assertEquals(150f, EmotePlayer.sample(c, 250).yawDeg, 1e-3f)
        val spin = Choreographies.DEFAULT.getValue(Emote.SPIN)
        assertEquals(360f, EmotePlayer.sample(spin, 600).yawDeg, 1e-3f)
    }

    @Test
    fun sanitizeClampsOrdersAndCapsKeys() {
        val raw = Choreography(
            Emote.DANCE, 2000,
            listOf(
                EmoteKey(900, pose(tilt = 80f, scale = 3f, hop = 2f)),
                EmoteKey(300, pose(tilt = 5f)),
                EmoteKey(300, pose(tilt = -5f)), // equal time: the last one wins
                EmoteKey(5_000, pose(yaw = 1_000f)), // past the end: clamped to durationMs, forced to rest
                EmoteKey(600, pose(yaw = 900f)), // a step of more than one turn
            ),
            line = 9,
        )
        val c = assertNotNull(ChoreographyRules.sanitize(raw))
        assertEquals(listOf(0L, 300L, 600L, 900L, 2000L), c.keys.map { it.atMs })
        assertEquals(EmotePose.REST, c.keys[0].pose, "a REST key is inserted at 0")
        assertEquals(-5f, c.keys[1].pose.tiltDeg)
        assertEquals(360f, c.keys[2].pose.yawDeg, "yaw step clamped to one turn")
        val k = c.keys[3].pose
        assertEquals(ChoreographyRules.MAX_TILT_DEG, k.tiltDeg)
        assertEquals(1.5f, k.scaleX)
        assertEquals(0.5f, k.hop)
        assertEquals(0f, c.keys[3].pose.yawDeg)
        assertEquals(360f, c.keys.last().pose.yawDeg) // 1000 from 0: clamped to one turn
        assertEquals(ChoreographyRules.MAX_LINE, c.line)

        val many = Choreography(Emote.ROAR, 2000, (0 until 17).map { EmoteKey(it * 100L) })
        assertNull(ChoreographyRules.sanitize(many), "more than MAX_KEYS keys")
        val dupes = Choreography(Emote.ROAR, 2000, (0 until 20).map { EmoteKey((it % 4) * 100L) } + EmoteKey(2000))
        assertEquals(5, assertNotNull(ChoreographyRules.sanitize(dupes)).keys.size, "counted after dedupe")
        val lone = assertNotNull(ChoreographyRules.sanitize(Choreography(Emote.BYE, 800, listOf(EmoteKey(0, pose(tilt = 10f))))))
        assertEquals(listOf(0L, 800L), lone.keys.map { it.atMs })
    }

    @Test
    fun sanitizeRejectsEmptyOrOutOfRangeDuration() {
        assertNull(ChoreographyRules.sanitize(Choreography(Emote.HELLO, 1000, emptyList())))
        assertNull(ChoreographyRules.sanitize(Choreography(Emote.HELLO, 299, listOf(EmoteKey(0)))))
        assertNull(ChoreographyRules.sanitize(Choreography(Emote.HELLO, 4001, listOf(EmoteKey(0)))))
        assertNotNull(ChoreographyRules.sanitize(Choreography(Emote.HELLO, 300, listOf(EmoteKey(0)))))
        assertNotNull(ChoreographyRules.sanitize(Choreography(Emote.HELLO, 4000, listOf(EmoteKey(0)))))
    }

    @Test
    fun sanitizeIsIdempotent() {
        val inputs = listOf(
            Choreography(Emote.SPIN, 1000, listOf(EmoteKey(0, pose(yaw = -10f)), EmoteKey(1000, pose(yaw = 350f)))),
            Choreography(Emote.DANCE, 1500, listOf(EmoteKey(700, pose(yaw = 1e9f, tilt = Float.NaN, scale = 0.1f)), EmoteKey(200, pose(hop = -1f)))),
            Choreography(Emote.ROAR, 3000, (0 until 15).map { EmoteKey(100L + it * 37L, pose(yaw = it * 333.3f, tilt = it * 7f - 50f)) }),
            Choreography(Emote.BYE, 400, listOf(EmoteKey(-50, pose(yaw = -540f)), EmoteKey(390, pose(yaw = -181f))), line = -3),
        ) + Choreographies.DEFAULT.values
        for (raw in inputs) {
            val once = assertNotNull(ChoreographyRules.sanitize(raw), "${raw.emote}")
            assertEquals(once, ChoreographyRules.sanitize(once), "${raw.emote}")
            assertTrue(once.keys.last().pose.yawDeg % 360f == 0f)
            assertTrue(once.keys.all { it.pose.scaleX in ChoreographyRules.SCALE && it.pose.tiltDeg.isFinite() })
            assertTrue(
                once.keys.zipWithNext().all { (a, b) -> kotlin.math.abs(b.pose.yawDeg - a.pose.yawDeg) <= ChoreographyRules.MAX_YAW_STEP_DEG },
                "${raw.emote}: no step over one turn, the last one included",
            )
        }
    }
}

class PoseFrameKeysTest {
    private fun k(atMs: Long, frame: Int? = null, tilt: Float = 0f) = EmoteKey(atMs, EmotePose(tiltDeg = tilt), frame)

    @Test
    fun sanitizeKeepsFramesInRangeAndEndsOnTheBaseSprite() {
        val raw = Choreography(Emote.HELLO, 1000, listOf(k(300, frame = 1, tilt = 10f), k(600, frame = 7), k(800, frame = -1), k(1000, frame = 0)))
        val c = assertNotNull(ChoreographyRules.sanitize(raw))
        assertEquals(listOf(null, 1, null, null, null), c.keys.map { it.frame }, "inserted key at 0, out-of-range cleared, last key cleared")
        assertEquals(10f, c.keys[1].pose.tiltDeg)
        assertEquals(c, ChoreographyRules.sanitize(c))
        assertEquals(1, ChoreographyRules.MAX_FRAME)
    }

    @Test
    fun frameAtHoldsAKeyUntilTheNext() {
        val c = assertNotNull(ChoreographyRules.sanitize(Choreography(Emote.ROAR, 1000, listOf(k(0), k(200, frame = 0), k(500, frame = 1), k(700), k(1000)))))
        assertNull(EmotePlayer.frameAt(c, -1))
        assertNull(EmotePlayer.frameAt(c, 0))
        assertNull(EmotePlayer.frameAt(c, 199))
        assertEquals(0, EmotePlayer.frameAt(c, 200))
        assertEquals(0, EmotePlayer.frameAt(c, 499))
        assertEquals(1, EmotePlayer.frameAt(c, 500))
        assertEquals(1, EmotePlayer.frameAt(c, 699))
        assertNull(EmotePlayer.frameAt(c, 700), "a key without a frame goes back to the base sprite")
        assertNull(EmotePlayer.frameAt(c, 1000))
        assertEquals(EmotePose.REST, EmotePlayer.sample(c, 1000))
        // The pose is untouched by frames: a keyed choreography samples like the unkeyed one.
        val unkeyed = c.copy(keys = c.keys.map { it.copy(frame = null) })
        for (t in 0L until 1000L step 50L) assertEquals(EmotePlayer.sample(unkeyed, t), EmotePlayer.sample(c, t))
    }

    @Test
    fun withFramesSpreadsTheFramesOverTheInteriorKeysInOrder() {
        val hello = Choreographies.withFrames(Choreographies.HELLO, 2)
        assertEquals(Choreographies.HELLO.keys.map { it.atMs }, hello.keys.map { it.atMs }, "no key added or moved")
        assertEquals(Choreographies.HELLO.keys.map { it.pose }, hello.keys.map { it.pose }, "poses untouched")
        val frames = hello.keys.map { it.frame }
        assertNull(frames.first())
        assertNull(frames.last())
        assertEquals(0, frames[1], "the first interior key shows frame 0")
        assertEquals(1, frames[frames.lastIndex - 1], "the last interior key shows the last frame")
        assertTrue(frames.filterNotNull().zipWithNext().all { (a, b) -> a <= b }, "frames never go backwards: $frames")
        assertEquals(hello, ChoreographyRules.sanitize(hello), "a keyed choreography is still sanitized")
        assertEquals(hello, Choreographies.withFrames(hello, 2), "keying is idempotent")
        assertEquals(Choreographies.HELLO, Choreographies.withFrames(hello, 0), "0 frames strips every key")
        assertEquals(Choreographies.withFrames(Choreographies.ROAR, 2), Choreographies.withFrames(Choreographies.ROAR, 9), "capped at MAX_PER_EMOTE")

        val one = Choreographies.withFrames(Choreographies.ROAR, 1)
        assertTrue(one.keys.drop(1).dropLast(1).all { it.frame == 0 }, "one frame: every interior key shows it")
    }

    @Test
    fun withFramesGivesABareChoreographyAKeyToHangTheFrameOn() {
        val bare = assertNotNull(ChoreographyRules.sanitize(Choreography(Emote.HELLO, 1000, listOf(k(0), k(1000)))))
        val keyed = Choreographies.withFrames(bare, 2)
        assertEquals(listOf(0L, 500L, 1000L), keyed.keys.map { it.atMs })
        assertEquals(listOf(null, 0, null), keyed.keys.map { it.frame })
        assertEquals(EmotePose.REST, keyed.keys[1].pose)
        assertEquals(keyed, ChoreographyRules.sanitize(keyed))
    }

    @Test
    fun emoteSetWithFramesKeysOnlyTheEmotesThatHaveThem() {
        val set = EmoteSet.defaults().withFrames(mapOf(Emote.HELLO to 2, Emote.ROAR to 1))
        assertEquals(EmoteSet.SOURCE_DEFAULT, set.source)
        assertTrue(set.hasFrames)
        assertTrue(set[Emote.HELLO].keys.any { it.frame == 1 })
        assertTrue(set[Emote.ROAR].keys.any { it.frame == 0 } && set[Emote.ROAR].keys.none { it.frame == 1 })
        for (e in listOf(Emote.BYE, Emote.DANCE, Emote.SPIN)) assertEquals(Choreographies.DEFAULT.getValue(e), set[e])
        assertEquals(EmoteSet.defaults(), set.withFrames(emptyMap()), "no frames: back to the plain defaults")
        assertFalse(EmoteSet.defaults().hasFrames)
    }
}
