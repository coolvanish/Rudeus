package kz.rudy.ar.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Choreographies
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.scenario.ScenarioRunner
import kz.rudy.ar.core.scenario.Scenarios
import kz.rudy.ar.core.session.BillboardPoser
import kz.rudy.ar.core.session.CameraFrame
import kz.rudy.ar.core.session.FrameInput
import kz.rudy.ar.core.session.NodePose
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Characterization tests of the table engine's frame step (T56): the tracking edge, the scenario start and feed,
// physics, the cast-state report, the reappear timer and one billboard pose per member, as they ran inside
// RudeusArView's onSessionUpdated and its scenario effect. Written against the host-local copy first; the same file,
// with only the package line and the class name changed, runs against the copy in :core. Frames go through
// `engine.onFrame`, the way the host calls it; a pose is compared with a bare BillboardPoser fed as the host fed its
// CharacterPoser. A log line with a float is asserted by its key and its integer fields only.

private const val FRAME_MS = 16L

/** An arbitrary host clock origin (SystemClock.elapsedRealtime is never small on a phone). */
private const val T0 = 1_000_000L

/** The host's FEET_DEPTH_BIAS (androidApp ar/CharacterNode.kt), the value the Pixel runs with. */
private const val BIAS = 0.6f

/** The host's REAPPEAR_AFTER_TRACKING_MS (androidApp ar/RudeusArView.kt). */
private const val REAPPEAR_MS = 800L

/** The Day 5 table without its objects: a +-0.35 m square. */
private val square = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.70f, 0.70f))

private fun member(id: String, ability: Ability = Ability.JUMP) = Member(id, ability, EmoteSet.defaults())

/**
 * Drives one engine the way RudeusArView and rememberTableEngine do: [latest] is the slot of the last composition,
 * every slot callback and every frame event lands in [calls] in order, the log in [lines]. The camera is plain
 * values the tests move: [cameraPos] / [cameraH] in plane coordinates, [right] its screen-right (asked at the anchor
 * as the host's `cameraRight(created, frame)`, and handed in every frame as `CameraFrame.right`).
 */
private class FrameHost(surface: Surface = Surface.TABLE, reappearMs: Long = REAPPEAR_MS) {
    val lines = ArrayList<String>()
    val calls = ArrayList<String>()

    /** Every onCastState report: the host clock it came at, and the map. */
    val reports = ArrayList<Pair<Long, Map<String, CharacterState>>>()

    var cameraPos = Vec2(0f, 0.5f)
    var cameraH = 0.3f
    var right = Vec2(1f, 0f)
    var rightAsked = 0
    val cameraRight: () -> Vec2 = { rightAsked++; right }

    var now = T0
    var latest: TableSlot = slotOf()
    val engine = TableEngine(
        { latest }, surface, {}, {}, { lines += it },
        feetDepthBias = BIAS, reappearAfterTrackingMs = reappearMs,
    )
    val loop get() = engine.loop

    fun slotOf(vararg members: Member, activeId: String? = null, size: Float = 1f, debug: Boolean = false) = TableSlot(
        members.toList(), activeId, command = null, soundsOn = true,
        onAnchorPlaced = { calls += "anchor" },
        onCastEvent = { id, e -> calls += "$id $e" },
        characterSize = size,
        debug = debug,
        onTracking = { calls += "tracking $it" },
        onCastState = { reports += now to it; calls += "state " + it.keys.joinToString(",") },
    )

    fun anchor() = engine.anchorPlaced(cameraRight)

    /** One AR frame [gapMs] after the previous one, through the engine; the poses it returns. */
    fun frame(tracking: Boolean = true, gapMs: Long = FRAME_MS): List<NodePose> {
        now += gapMs
        return engine.onFrame(FrameInput(now, tracking, CameraFrame(cameraPos, cameraH, right)))
    }

    /** [n] frames; the poses of the last one. */
    fun frames(n: Int, tracking: Boolean = true): List<NodePose> {
        var last = emptyList<NodePose>()
        repeat(n) { last = frame(tracking) }
        return last
    }

    /** Frames until a log line starts with [prefix] (at most [max]); false when it never came. */
    fun framesUntilLine(prefix: String, max: Int = 2_000): Boolean {
        var n = 0
        while (lines.none { it.startsWith(prefix) }) {
            if (n++ >= max) return false
            frame()
        }
        return true
    }

    fun clear() {
        lines.clear(); calls.clear(); reports.clear(); rightAsked = 0
    }

    fun state(id: String) = assertNotNull(loop.state(id), id)

    /** [poser] fed member [id] on this frame as ar/RudeusArView.kt:758-764 fed the host's CharacterPoser. */
    fun hostPose(poser: BillboardPoser, id: String): NodePose {
        val b = assertNotNull(loop.body(id), id)
        return poser.pose(
            b.state, cameraPos, cameraH, loop.walkPhaseMs(id),
            verticalVelocity = b.verticalVelocity,
            sinceLandedMs = now - loop.landedAtMs(id),
            size = latest.characterSize,
            emotePose = loop.emotePose(id),
        )
    }
}

/** [line] has the words of [shape] in order; a word `key=` in [shape] stands for that key with any number. */
private fun assertLine(shape: String, line: String?) {
    assertNotNull(line, shape)
    val want = shape.split(' ')
    val got = line.split(' ')
    assertEquals(want.size, got.size, "'$line' against '$shape'")
    for ((w, g) in want.zip(got)) {
        if (w.endsWith("=")) assertTrue(g.startsWith(w) && g.removePrefix(w).toFloatOrNull() != null, "$w in '$line'")
        else assertEquals(w, g, "'$line' against '$shape'")
    }
}

class TableFramePinTest {

    // --- Tracking and anchor ---

    @Test
    fun trackingEdgeIsReportedOnce() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.clear()
        h.frame(tracking = false)
        assertEquals(listOf("tracking false", "a PAUSED"), h.calls)
        h.frames(20, tracking = false)
        h.frame(tracking = false, gapMs = 3_000)
        assertEquals(listOf("tracking false", "a PAUSED"), h.calls, "nothing while the loss lasts")
        h.frame()
        assertEquals(listOf("tracking false", "a PAUSED", "tracking true", "a RESUMED"), h.calls)
        h.frames(20)
        assertEquals(4, h.calls.size, "nothing while tracking lasts")
        assertEquals(emptyList(), h.lines)
    }

    @Test
    fun anchorThenTrackingKeepTodaysOrder() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        assertEquals(listOf("anchor", "a PLACED", "b PLACED", "tracking true"), h.calls)
        h.calls.clear()
        assertEquals(listOf("a", "b"), h.frame().map { it.id })
        assertEquals(emptyList(), h.calls, "the first tracked frame after the anchor reports no edge")
        // A first frame without tracking is an edge like any other.
        val g = FrameHost()
        g.latest = g.slotOf(member("a"))
        g.anchor()
        g.calls.clear()
        g.frame(tracking = false)
        assertEquals(listOf("tracking false", "a PAUSED"), g.calls)
    }

    @Test
    fun anEdgeReportedEarlyIsNotReportedAgainByTheFrame() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.clear()
        // The Android host: trackingEdge before its scan block, then onFrame, on every frame.
        h.engine.trackingEdge(false)
        assertEquals(listOf("tracking false"), h.calls)
        h.frame(tracking = false)
        assertEquals(listOf("tracking false", "a PAUSED"), h.calls)
        repeat(5) {
            h.engine.trackingEdge(false)
            h.frame(tracking = false)
        }
        assertEquals(2, h.calls.size)
        h.engine.trackingEdge(true)
        assertEquals("tracking true", h.calls.last())
        h.frame()
        assertEquals(listOf("tracking false", "a PAUSED", "tracking true", "a RESUMED"), h.calls)
    }

    @Test
    fun onFrameBeforeTheAnchorDoesNothing() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"), member("b"), debug = true)
        h.engine.configure(1f)
        h.clear()
        h.engine.trackingEdge(false)
        h.engine.trackingEdge(true)
        for (tracking in listOf(true, false, true, true)) assertEquals(emptyList(), h.frame(tracking))
        h.engine.trackingEdge(false)
        assertEquals(emptyList(), h.calls, "no edge, no PLACED, no event, no cast state")
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.loop.ids)
        assertEquals(0L, h.loop.simMs, "physics never stepped")
        // The anchor still reports everything once, as if no frame had come before it.
        h.anchor()
        assertEquals(listOf("anchor", "a PLACED", "b PLACED", "tracking true"), h.calls)
    }

    @Test
    fun syncCastBeforeTheAnchorPlacesNobody() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"), member("b"))
        repeat(3) { h.engine.syncCast(h.cameraRight) }
        assertEquals(emptyList(), h.loop.ids)
        assertEquals(emptyList(), h.calls)
        assertEquals(emptyList(), h.lines)
        assertEquals(0, h.rightAsked)
        h.anchor()
        assertEquals(listOf("a", "b"), h.loop.ids)
        assertEquals(listOf("anchor", "a PLACED", "b PLACED", "tracking true"), h.calls)
        assertEquals(2, h.rightAsked)
    }

    // --- Frame order ---

    @Test
    fun oneFrameReportsTheEdgeThenPlacedThenEventsThenCastState() {
        val h = FrameHost()
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a"), debug = true)
        h.anchor()
        h.engine.level = square
        h.loop.walkTo("a", Vec2(0.2f, 0f))
        h.frames(10)
        h.clear()
        // The frame tracking is lost on: b joins, then the whole cast pauses, then the report.
        h.latest = h.slotOf(member("a"), member("b"), debug = true)
        assertEquals(emptyList(), h.frame(tracking = false, gapMs = 100))
        assertEquals(listOf("tracking false", "b PLACED", "a PAUSED", "b PAUSED", "state a,b"), h.calls)
        assertEquals(h.loop.cast.members.associate { it.id to it.body.state }, h.reports.single().second)
        h.clear()
        // The frame it comes back on: c joins, both resume, the report, then the poses.
        h.latest = h.slotOf(member("a"), member("b"), member("c"), debug = true)
        assertEquals(listOf("a", "b", "c"), h.frame(gapMs = 100).map { it.id })
        assertEquals(listOf("tracking true", "c PLACED", "a RESUMED", "b RESUMED", "state a,b,c"), h.calls)
        h.clear()
        // A tracked frame: a tap made between frames is reported by it, after the joiner and before the report.
        h.loop.walkTo("b", Vec2(-0.2f, -0.2f))
        h.latest = h.slotOf(member("a"), member("b"), member("c"), member("d"), debug = true)
        assertEquals(listOf("a", "b", "c", "d"), h.frame(gapMs = 100).map { it.id })
        assertEquals(listOf("d PLACED", "b STARTED_WALKING", "state a,b,c,d"), h.calls)
    }

    @Test
    fun aMemberAddedBetweenFramesIsPosedOnTheFrameItJoins() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.clear()
        h.latest = h.slotOf(member("a"), member("b"))
        val poses = h.frame()
        assertEquals(listOf("b PLACED"), h.calls)
        assertEquals(listOf("a", "b"), poses.map { it.id })
        val fresh = BillboardPoser("b", SurfaceProfile.TABLE.characterHeightM, false, BIAS)
        assertEquals(h.hostPose(fresh, "b"), poses[1], "a new poser, fed this frame")
        assertTrue(poses[1].visible)
    }

    @Test
    fun aMemberThatJoinsDuringALossIsPlacedWithThatFramesCameraRight() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.frame(tracking = false)
        val aPos = h.state("a").pos
        val before = h.loop.spawnFor(aPos, h.right)
        h.right = Vec2(0f, -2f) // the phone turned while the lens was covered
        val want = h.loop.spawnFor(aPos, h.right)
        assertNotEquals(before, want, "the two camera-rights give two spawns")
        h.clear()
        h.latest = h.slotOf(member("a"), member("b"))
        assertEquals(emptyList(), h.frame(tracking = false), "no pose during the loss")
        assertEquals(want, h.state("b").pos)
        assertEquals(listOf("b PLACED", "b PAUSED"), h.calls)
        assertEquals(Mode.PAUSED, h.state("b").mode)
    }

    // --- PAUSED ---

    @Test
    fun aTrackingLossFreezesEveryMemberAndNeverResets() {
        val h = FrameHost()
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.engine.level = square
        h.frames(3)
        h.engine.scenario(1) // a plays the replay
        h.loop.walkTo("b", Vec2(-0.2f, -0.2f))
        h.loop.emote("b", Choreographies.DANCE)
        h.frames(50) // 0.8 s: a walks its first beat, b walks and dances
        val frozen = h.loop.cast
        val sim = h.loop.simMs
        val dance = assertNotNull(h.loop.emotePose("b"))
        h.clear()
        repeat(30) { assertEquals(emptyList(), h.frame(tracking = false)) }
        assertEquals(emptyList(), h.frame(tracking = false, gapMs = 5_000))
        for (m in frozen.members) assertEquals(m.body.state.copy(mode = Mode.PAUSED), h.state(m.id), m.id)
        assertEquals(sim, h.loop.simMs, "the simulated clock stands still")
        assertEquals(dance, h.loop.emotePose("b"), "the emote stands still with it")
        assertEquals(emptyList(), h.lines, "no scripted tap while lost")
        // Back: nothing was reset. The first frame back steps 0 ms, then everyone goes on from where they stood.
        h.frame()
        for (m in frozen.members) {
            val was = m.body.state
            val s = h.state(m.id)
            assertEquals(was.pos, s.pos, m.id)
            assertEquals(was.target, s.target, m.id)
            assertEquals(was.mode, s.mode, m.id)
        }
        assertEquals(dance, h.loop.emotePose("b"))
        assertTrue(h.framesUntilLine("scenario_step idx=1"), "the replay goes on")
        assertEquals(listOf("scenario_step idx=1 at_ms=4000"), h.lines, "from where it was: the first beat is not played again")
        h.frames(100)
        assertEquals(Vec2(-0.2f, -0.2f), h.state("b").pos, "b finished its walk")
        assertEquals(listOf("tracking false", "a PAUSED", "b PAUSED", "tracking true", "a RESUMED", "b RESUMED"), h.calls.take(6))
    }

    @Test
    fun noPoseWhileNotTracking() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        assertEquals(2, h.frame().size)
        assertEquals(emptyList(), h.frame(tracking = false))
        h.latest = h.slotOf(member("a"), member("b"), member("c"), size = 2f)
        repeat(10) { assertEquals(emptyList(), h.frame(tracking = false)) }
        assertEquals(emptyList(), h.frame(tracking = false, gapMs = 2_000))
        val back = h.frame()
        assertEquals(listOf("a", "b", "c"), back.map { it.id }, "every member is posed again on the first frame back")
        assertTrue(back.none { it.visible }, "hidden while the anchor settles")
    }

    @Test
    fun hiddenFor800MsAfterTrackingReturns() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        assertTrue(h.frame().single().visible, "before any loss a member is visible from its first pose")
        h.frames(10)
        h.frame(tracking = false)
        h.frame(tracking = false, gapMs = 2_000)
        val last = h.now // the last untracked frame
        val hidden = h.frame().single()
        assertFalse(hidden.visible)
        val fresh = BillboardPoser("a", SurfaceProfile.TABLE.characterHeightM, false, BIAS)
        assertEquals(h.hostPose(fresh, "a").copy(visible = false), hidden, "computed as usual, only hidden")
        assertFalse(h.frame(gapMs = REAPPEAR_MS - 1 - FRAME_MS).single().visible, "799 ms after the last untracked frame")
        assertEquals(last + REAPPEAR_MS - 1, h.now)
        assertTrue(h.frame(gapMs = 1).single().visible, "800 ms: shown")
        assertTrue(h.frames(5).single().visible)
        // Counted from the LAST untracked frame: a second short loss restarts it.
        h.frame(tracking = false)
        assertFalse(h.frame().single().visible)
        h.frame(tracking = false, gapMs = 600)
        val last2 = h.now
        assertFalse(h.frame(gapMs = 300).single().visible, "300 ms after the second loss")
        assertFalse(h.frame(gapMs = REAPPEAR_MS - 301).single().visible)
        assertEquals(last2 + REAPPEAR_MS - 1, h.now)
        assertTrue(h.frame(gapMs = 1).single().visible)
        // Delay 0: shown on the first frame back.
        val z = FrameHost(reappearMs = 0)
        z.latest = z.slotOf(member("a"))
        z.anchor()
        z.frames(3)
        z.frame(tracking = false)
        assertTrue(z.frame().single().visible)
    }

    // --- Poses ---

    @Test
    fun posesFollowCastOrderAndSkipNobody() {
        val h = FrameHost()
        h.latest = h.slotOf(member("c"), member("a"), member("b", Ability.FLY))
        h.anchor()
        assertEquals(listOf("c", "a", "b"), h.frame().map { it.id })
        assertTrue(h.loop.jump("a"))
        h.loop.walkTo("b", Vec2(0.1f, 0.1f))
        repeat(20) { assertEquals(listOf("c", "a", "b"), h.frame().map { it.id }, "airborne and walking are posed too") }
        h.latest = h.slotOf(member("c"), member("b", Ability.FLY))
        assertEquals(listOf("c", "b"), h.frame().map { it.id })
        // A member who joins later stands last in the cast, wherever the slot lists it.
        h.latest = h.slotOf(member("d"), member("c"), member("b", Ability.FLY))
        assertEquals(listOf("c", "b", "d"), h.frame().map { it.id })
        assertEquals(h.loop.ids, h.frame().map { it.id })
        assertTrue(h.frames(5).all { it.visible })
    }

    @Test
    fun engineAndHostPoserAgreeBitForBit() {
        var compared = 0
        val mirroredSeen = HashSet<Boolean>()
        for (surface in Surface.entries) for (aRight in listOf(false, true)) {
            val label = "$surface aRight=$aRight"
            val profile = SurfaceProfile.of(surface)
            val k = profile.scale
            val h = FrameHost(surface)
            h.right = Vec2(0f, -1f)
            h.latest = h.slotOf(member("a"), member("b"))
            h.engine.spriteBuilt("a", aRight)
            h.engine.spriteBuilt("b", !aRight)
            h.engine.configure(1f)
            val bare = mapOf(
                "a" to BillboardPoser("a", profile.characterHeightM, aRight, BIAS),
                "b" to BillboardPoser("b", profile.characterHeightM, !aRight, BIAS),
            )
            h.anchor()
            h.engine.level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.70f * k, 0.70f * k))
            var lastUntracked: Long? = null
            for (i in 0 until 2_000) {
                when (i) {
                    10 -> {
                        h.loop.walkTo("a", Vec2(0.20f, 0.10f) * k)
                        h.loop.walkTo("b", Vec2(-0.15f, -0.10f) * k)
                    }
                    300 -> assertTrue(h.loop.jump("a"), label)
                    500 -> h.loop.emote("b", Choreographies.DANCE)
                    700 -> {
                        h.latest = h.slotOf(member("a"), member("b"), size = 1.5f)
                        h.engine.configure(1.5f)
                    }
                    1_000 -> {
                        h.loop.walkTo("a", Vec2(-0.20f, 0.15f) * k)
                        h.loop.walkTo("b", Vec2(0.25f, -0.20f) * k)
                    }
                    1_200 -> assertTrue(h.loop.jump("b"), label)
                    1_400 -> h.loop.emote("a", Choreographies.ROAR)
                    1_500 -> {
                        h.latest = h.slotOf(member("a"), member("b"), size = 0.75f)
                        h.engine.configure(0.75f)
                    }
                }
                // The camera circles the table, rises and dips; screen-right is the circle's tangent.
                val angle = i * 0.004f
                h.cameraPos = Vec2(0.5f * k * sin(angle), 0.5f * k * cos(angle))
                h.cameraH = (0.3f + 0.1f * sin(i * 0.01f)) * k
                h.right = Vec2(cos(angle), -sin(angle))
                val tracking = i !in 900 until 944
                val gap = when {
                    i == 320 || i == 1_230 -> 200L // a hitch: physics steps 50 ms, the host clock 200
                    i % 7 == 0 -> 33L
                    else -> FRAME_MS
                }
                val poses = h.frame(tracking, gap)
                if (!tracking) {
                    lastUntracked = h.now
                    assertEquals(emptyList(), poses, label)
                    continue
                }
                val visible = lastUntracked?.let { h.now >= it + REAPPEAR_MS } ?: true
                val want = h.loop.cast.members.map { m ->
                    val p = h.hostPose(bare.getValue(m.id), m.id)
                    if (visible) p else p.copy(visible = false)
                }
                assertEquals(want, poses, "$label frame $i")
                compared += poses.size
                for (p in poses) mirroredSeen += p.mirrored
            }
            // The run did what it says.
            for (e in listOf("a JUMPED", "a LANDED", "b JUMPED", "b LANDED", "a ARRIVED", "b ARRIVED", "a PAUSED", "a RESUMED")) {
                assertTrue(e in h.calls, "$label: $e")
            }
        }
        assertEquals(4 * (2_000 - 44) * 2, compared)
        assertEquals(setOf(false, true), mirroredSeen, "both flips were posed")
    }

    @Test
    fun sinceLandedIsOnTheHostClock() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        for (hitch in listOf(200L, 100L)) {
            assertTrue(h.loop.jump("a"))
            h.calls.clear()
            var landing: NodePose? = null
            var n = 0
            while (landing == null && n++ < 200) {
                val p = h.frame().single()
                if ("a LANDED" in h.calls) landing = p
            }
            val l = assertNotNull(landing, "landed")
            assertEquals(Mode.IDLE, h.state("a").mode)
            assertTrue(l.scaleX > l.scaleY, "the landing frame squashes")
            val sim = h.loop.simMs
            val after = h.frame(gapMs = hitch).single()
            assertEquals(sim + 50, h.loop.simMs, "physics stepped the clamped 50 ms")
            if (hitch >= 120) {
                assertEquals(after.scaleX, after.scaleY, "$hitch ms on the host clock: the squash is over")
            } else {
                assertTrue(after.scaleX > after.scaleY, "$hitch ms on the host clock: still squashing")
            }
            h.frames(10)
        }
    }

    @Test
    fun aRebuiltSpriteStartsUnflippedAgain() {
        val h = FrameHost()
        h.cameraPos = Vec2(0f, 1f) // +v: screen-right is +u around the origin
        h.right = Vec2(1f, 0f)
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        // A new member faces +u, toward screen-right: a picture drawn facing left is mirrored.
        assertEquals(listOf(true, true), h.frame().map { it.mirrored })
        // Both walk straight at the camera: inside the flip deadband the flip holds.
        for (id in listOf("a", "b")) {
            val pos = h.state(id).pos
            h.loop.walkTo(id, pos + (h.cameraPos - pos).normalized() * 0.05f)
        }
        h.frames(5)
        assertEquals(listOf(true, true), h.frame().map { it.mirrored }, "the deadband holds the flip")
        h.engine.spriteBuilt("a", false) // a's picture was rebuilt (a new spritePng)
        assertEquals(listOf(false, true), h.frame().map { it.mirrored }, "a's new picture starts unflipped; b keeps its flip")
        assertEquals(listOf(false, true), h.frames(5).map { it.mirrored })
    }

    @Test
    fun theDrawnFacingComesFromSpriteBuiltAndDefaultsToLeft() {
        val h = FrameHost()
        h.cameraPos = Vec2(0f, 1f)
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a"), member("b"), member("c"))
        h.engine.spriteBuilt("b", true) // at composition, before the anchor
        h.engine.spriteBuilt("c", false)
        h.engine.spriteBuilt("zz", true) // nobody of that id: the others do not hear it
        h.anchor()
        // Everyone faces +u, toward screen-right: only a picture drawn facing left is mirrored.
        assertEquals(listOf(true, false, true), h.frame().map { it.mirrored }, "a was never announced: drawn facing left")
        h.engine.spriteBuilt("a", true)
        h.engine.spriteBuilt("b", false)
        assertEquals(listOf(false, true, true), h.frame().map { it.mirrored })
    }

    @Test
    fun sizeIsTheLatestSlotsAndTheHeightTheLockedSurfaces() {
        val h = FrameHost(Surface.TABLE)
        h.engine.offerSurface(Surface.FLOOR)
        h.engine.configure(1f)
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.engine.offerSurface(Surface.TABLE) // ignored: locked
        val floor = BillboardPoser("a", SurfaceProfile.FLOOR.characterHeightM, false, BIAS)
        val table = BillboardPoser("a", SurfaceProfile.TABLE.characterHeightM, false, BIAS)
        val p = h.frame().single()
        assertEquals(h.hostPose(floor, "a"), p, "the locked surface's character height")
        assertNotEquals(h.hostPose(table, "a"), p)
        // The latest slot's size, read on every frame; the physics was not re-sized for it.
        for (size in listOf(2f, 0.5f, 3f)) {
            h.latest = h.slotOf(member("a"), size = size)
            assertEquals(h.hostPose(floor, "a"), h.frame().single(), "size $size")
        }
        assertEquals(SurfaceProfile.FLOOR.radiusAt(1f), h.loop.radiusM, "configure was not called again")
    }

    // --- Cast state ---

    @Test
    fun castStateAtMostEvery100MsAndOnlyInDebug() {
        val h = FrameHost()
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.loop.walkTo("a", Vec2(0.2f, 0f))
        h.frames(20)
        assertEquals(emptyList(), h.reports, "debug off: never")
        h.latest = h.slotOf(member("a"), member("b"), debug = true)
        h.frame()
        val first = h.now
        assertEquals(listOf(first), h.reports.map { it.first }, "at once on the first debug frame")
        assertEquals(h.loop.cast.members.associate { it.id to it.body.state }, h.reports.last().second)
        h.frame(gapMs = 99)
        assertEquals(1, h.reports.size, "99 ms: too soon")
        h.frame(gapMs = 1)
        assertEquals(listOf(first, first + 100), h.reports.map { it.first }, "100 ms: due")
        h.frames(12) // 16 ms frames: the next one at +112
        assertEquals(listOf(first, first + 100, first + 212), h.reports.map { it.first })
        // During a loss too, on the host clock.
        h.reports.clear()
        h.frame(tracking = false, gapMs = 100)
        val lost = h.now
        h.frames(20, tracking = false)
        assertEquals(listOf(lost, lost + 112, lost + 224), h.reports.map { it.first })
        assertTrue(h.reports.all { r -> r.second.values.all { it.mode == Mode.PAUSED } })
        assertEquals(setOf("a", "b"), h.reports.last().second.keys)
        // Debug off again: none.
        h.latest = h.slotOf(member("a"), member("b"))
        h.reports.clear()
        h.frames(20)
        h.frames(20, tracking = false)
        assertEquals(emptyList(), h.reports)
    }

    // --- Scenario ---

    @Test
    fun scenarioRequestBeforeTheAnchorIsRejected() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.engine.scenario(0)
        assertEquals(emptyList(), h.lines, "0: nothing asked")
        h.engine.scenario(1)
        assertEquals(listOf("scenario_rejected anchored=false"), h.lines)
        h.anchor()
        h.engine.level = square
        h.frames(3)
        h.lines.clear()
        h.engine.scenario(1) // the same key: Compose would not run the effect again
        h.frames(30)
        assertEquals(emptyList(), h.lines, "not retried after the anchor")
        assertNull(h.state("a").target)
        h.engine.scenario(2)
        assertLine("scenario_start id=day5 steps=5 surface=TABLE sim_ms=", h.lines.single())
    }

    @Test
    fun aNewRequestRestartsTheReplayFromSpawn() {
        val spawn = Vec2(0.05f, -0.05f)
        val h = FrameHost()
        h.right = Vec2(0f, 1f)
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.engine.level = square.copy(spawn = spawn)
        h.loop.walkTo("a", Vec2(-0.2f, -0.2f))
        h.frames(100)
        h.clear()
        h.engine.scenario(1)
        var a = h.state("a")
        assertEquals(spawn, a.pos)
        assertEquals(0f, a.h)
        assertNull(a.target)
        assertEquals(Mode.IDLE, a.mode)
        assertEquals(listOf("scenario_start id=day5 steps=5 surface=TABLE sim_ms=${h.loop.simMs}"), h.lines)
        h.frame()
        assertEquals(listOf("a STARTED_WALKING"), h.calls, "the teleport is no event; the first beat is")
        // Mid-replay, past the second beat: b jumps between two frames, and a new request comes before the next frame.
        assertTrue(h.framesUntilLine("scenario_step idx=1"))
        h.frames(30)
        h.clear()
        assertTrue(h.loop.jump("b"))
        h.engine.scenario(2)
        a = h.state("a")
        assertEquals(spawn, a.pos)
        assertNull(a.target)
        assertEquals(Mode.IDLE, a.mode)
        assertLine("scenario_start id=day5 steps=5 surface=TABLE sim_ms=", h.lines.single())
        h.frame()
        assertEquals("scenario_step idx=0 at_ms=0", h.lines.last(), "from the first beat again")
        // T47's finding 2, as found: the restart resets the whole cast's event diff, so b's jump is never reported.
        assertEquals(listOf("a STARTED_WALKING"), h.calls)
        assertEquals(Mode.JUMPING, h.state("b").mode)
        h.frames(100)
        assertTrue("b LANDED" in h.calls)
        assertFalse("b JUMPED" in h.calls)
    }

    @Test
    fun theSameRequestOfferedTwiceStartsOnce() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.engine.level = square
        h.frames(3)
        h.clear()
        h.engine.scenario(1)
        h.engine.scenario(1)
        assertEquals(1, h.lines.count { it.startsWith("scenario_start") })
        h.frames(50)
        val pos = h.state("a").pos
        assertNotEquals(Vec2.ZERO, pos)
        h.engine.scenario(1)
        assertEquals(1, h.lines.count { it.startsWith("scenario_start") })
        assertEquals(pos, h.state("a").pos, "not restarted")
        // Down to 0 and up again is a new request, as a keyed effect would run it.
        h.engine.scenario(0)
        assertEquals(1, h.lines.count { it.startsWith("scenario_start") })
        h.engine.scenario(1)
        assertEquals(2, h.lines.count { it.startsWith("scenario_start") })
        assertEquals(Vec2.ZERO, h.state("a").pos)
    }

    @Test
    fun scenarioFeedMatchesScenarioRunner() {
        val level = Scenarios.day5Level()
        val script = Scenarios.DAY5
        for (ability in listOf(Ability.JUMP, Ability.FLY)) {
            for (stallAtFrame in listOf<Int?>(null, 437)) { // 437 x 16 ms = 7 s: on the book, mid-walk
                val label = "$ability stall=$stallAtFrame"
                val h = FrameHost()
                h.latest = h.slotOf(member("hero", ability))
                h.engine.configure(1f)
                h.anchor()
                h.engine.level = level
                h.frames(3)
                h.clear()
                h.engine.scenario(1)
                var frame = 0
                while (h.lines.none { it.startsWith("scenario_done") } && frame < 2_000) {
                    if (frame == stallAtFrame) repeat(44) { h.frame(tracking = false) } // 704 ms untracked
                    h.frame()
                    frame++
                }
                val expected = ScenarioRunner.run(script, level, ability, h.loop.params, h.loop.radiusM, FRAME_MS).eventNames()
                val events = h.calls.filter { it.startsWith("hero ") }.map { CharacterEvent.valueOf(it.removePrefix("hero ")) }
                val edges = h.calls.filter { it.startsWith("tracking ") }
                if (stallAtFrame == null) {
                    assertEquals(expected, events, label)
                    assertEquals(emptyList(), edges, label)
                } else {
                    val blip = listOf(CharacterEvent.PAUSED, CharacterEvent.RESUMED)
                    assertEquals(blip, events.filter { it in blip }, label)
                    assertEquals(CharacterEvent.RESUMED, events[events.indexOf(CharacterEvent.PAUSED) + 1], label)
                    assertEquals(expected, events.filter { it !in blip }, label)
                    assertEquals(listOf("tracking false", "tracking true"), edges, label)
                }
                val steps = h.lines.filter { it.startsWith("scenario_step") }
                assertEquals(script.steps.indices.map { "scenario_step idx=$it at_ms=${script.steps[it].atMs}" }, steps, label)
                val done = h.lines.single { it.startsWith("scenario_done") }
                val ms = assertNotNull(done.removePrefix("scenario_done ms=").toLongOrNull(), done)
                assertTrue(ms in 15_000L..15_016L, "$label: $done")
            }
        }
    }

    @Test
    fun scenarioDrivesTheFirstMemberOnly() {
        val h = FrameHost()
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a"), member("b"), activeId = "b")
        h.anchor()
        h.engine.level = square
        h.frames(3)
        val b = h.loop.body("b")
        h.clear()
        h.engine.scenario(1)
        assertEquals(b, h.loop.body("b"), "the restart moves the first member only")
        h.frames(400) // 6.4 s: two beats
        assertEquals(b, h.loop.body("b"), "b, the active one, is never driven")
        assertEquals(emptyList(), h.calls.filter { it.startsWith("b ") })
        assertTrue("a STARTED_WALKING" in h.calls)
        assertEquals(2, h.lines.count { it.startsWith("scenario_step") })
    }

    @Test
    fun aBeatDueOnTheResumeFrameIsAppliedButItsEventIsLost() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.engine.level = square
        h.frames(3)
        h.frame(tracking = false)
        h.clear()
        h.engine.scenario(1) // asked under the Paused scrim
        assertLine("scenario_start id=day5 steps=5 surface=TABLE sim_ms=", h.lines.single())
        assertEquals(Mode.PAUSED, h.state("a").mode, "the restart keeps the pause")
        h.frames(10, tracking = false)
        h.frame(tracking = false, gapMs = 1_000)
        assertEquals(1, h.lines.size, "no beat while lost")
        h.calls.clear()
        h.frame() // back
        assertEquals("scenario_step idx=0 at_ms=0", h.lines.last(), "the first walk is applied on the frame back")
        assertEquals(Vec2(0.30f, 0f), h.state("a").target)
        assertEquals(Mode.WALKING, h.state("a").mode)
        assertEquals(listOf("tracking true", "a RESUMED"), h.calls, "RESUMED only: its STARTED_WALKING is lost (T47 finding 1)")
        h.frames(100)
        assertFalse("a STARTED_WALKING" in h.calls)
        assertTrue(h.state("a").pos.u > 0.05f, "and it walks")
    }

    @Test
    fun aScriptedJumpThatPhysicsIgnoresIsLogged() {
        val h = FrameHost()
        h.latest = h.slotOf(member("a", Ability.ROLL))
        h.anchor()
        h.engine.level = square
        h.frames(3)
        h.clear()
        h.engine.scenario(1)
        assertTrue(h.framesUntilLine("scenario_done"))
        val tail = h.lines.filter { it.startsWith("jump_ignored") || it.startsWith("scenario_step idx=3") || it.startsWith("scenario_step idx=4") }
        assertEquals(
            listOf(
                "jump_ignored mode=WALKING ability=ROLL scenario=true",
                "scenario_step idx=3 at_ms=10400",
                "jump_ignored mode=WALKING ability=ROLL scenario=true",
                "scenario_step idx=4 at_ms=11400",
            ),
            tail,
        )
        assertFalse("a JUMPED" in h.calls)
    }

    // --- The level seam ---

    @Test
    fun levelSwapBetweenFramesTakesEffectOnTheNextStepWithoutAReset() {
        val h = FrameHost()
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.engine.level = square
        assertTrue(h.engine.level === square && h.loop.level === square, "a plain get / set of the loop's level")
        h.frames(3)
        h.engine.scenario(1) // a walks its first beat toward (0.3, 0)
        h.frames(20)
        h.clear()
        // Tracked: a scan swaps in a book under a's feet between two frames.
        val posA = h.state("a").pos
        val bookA = LevelObject("book-1", "book", ObjectKind.PLATFORM, Polygon.rect(posA, 0.10f, 0.10f), 0.02f)
        val scanned = square.copy(objects = listOf(bookA))
        val cast = h.loop.cast
        h.engine.level = scanned
        assertTrue(h.engine.level === scanned && h.loop.level === scanned)
        assertTrue(cast === h.loop.cast, "assigning a level moves nobody")
        h.frame()
        assertEquals(0.02f, h.state("a").h, "the next step stands on the new book")
        assertEquals(listOf("a STEPPED_UP"), h.calls)
        assertEquals(Mode.WALKING, h.state("a").mode)
        // During a loss: another scan puts a book under b. Nobody moves until tracking is back.
        h.frame(tracking = false)
        h.clear()
        val posB = h.state("b").pos
        val bookB = LevelObject("book-2", "book", ObjectKind.PLATFORM, Polygon.rect(posB, 0.06f, 0.06f), 0.02f)
        val second = scanned.copy(objects = listOf(bookA, bookB))
        val frozen = h.loop.cast
        h.engine.level = second
        h.frames(10, tracking = false)
        assertEquals(frozen, h.loop.cast, "PAUSED: the new level is not stepped")
        assertEquals(0f, h.state("b").h)
        h.frame() // back: the 0 ms step already stands on it
        assertEquals(0.02f, h.state("b").h)
        assertEquals(posB, h.state("b").pos)
        // As found (T47 finding 1 again): the step-up happens on the resume frame's diff, so it is reported as RESUMED.
        assertEquals(listOf("tracking true", "a RESUMED", "b RESUMED"), h.calls)
        // The replay goes on against the new level.
        assertTrue(h.framesUntilLine("scenario_step idx=1"))
        assertEquals(second, h.engine.level)
    }

    @Test
    fun standingIsEveryMembersStateInCastOrder() {
        val h = FrameHost()
        assertEquals(listOf(CharacterState()), h.engine.standing(), "nobody on the table: one default state")
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("c"), member("a"), member("b", Ability.FLY))
        h.anchor()
        h.loop.walkTo("a", Vec2(0.1f, 0.1f))
        h.frames(10)
        assertEquals(listOf(h.state("c"), h.state("a"), h.state("b")), h.engine.standing())
        h.frame(tracking = false)
        assertEquals(h.loop.cast.members.map { it.body.state }, h.engine.standing(), "paused states while lost")
        h.latest = h.slotOf(member("c"), member("b", Ability.FLY))
        h.frame()
        assertEquals(listOf(h.state("c"), h.state("b")), h.engine.standing())
        h.latest = h.slotOf()
        h.frame()
        assertEquals(emptyList(), h.loop.ids)
        assertEquals(listOf(CharacterState()), h.engine.standing(), "everyone left: one default state again")
    }

    // --- Evidence ---

    @Test
    fun frameLogLinesKeepTheirKeys() {
        val h = FrameHost()
        h.right = Vec2(0f, -1f)
        h.latest = h.slotOf(member("a", Ability.ROLL))
        h.engine.scenario(1)
        h.anchor()
        h.engine.level = square
        h.frames(3)
        h.latest = h.slotOf(member("a", Ability.ROLL), member("b"))
        h.frame() // b joins through the frame's cast sync
        h.engine.scenario(2)
        assertTrue(h.framesUntilLine("scenario_done"))
        h.latest = h.slotOf(member("a", Ability.ROLL))
        h.frame()
        h.frames(5, tracking = false)
        h.frames(5)
        val shapes = listOf(
            "scenario_rejected anchored=false",
            "cast_add id=a n=1 u= v=",
            "cast_add id=b n=2 u= v=",
            "scenario_start id=day5 steps=5 surface=TABLE sim_ms=",
            "scenario_step idx=0 at_ms=0",
            "scenario_step idx=1 at_ms=4000",
            "scenario_step idx=2 at_ms=9000",
            "jump_ignored mode=WALKING ability=ROLL scenario=true",
            "scenario_step idx=3 at_ms=10400",
            "jump_ignored mode=WALKING ability=ROLL scenario=true",
            "scenario_step idx=4 at_ms=11400",
            "scenario_done ms=",
            "cast_remove id=b",
        )
        assertEquals(shapes.size, h.lines.size, h.lines.joinToString("\n"))
        for ((shape, line) in shapes.zip(h.lines)) assertLine(shape, line)
    }
}
