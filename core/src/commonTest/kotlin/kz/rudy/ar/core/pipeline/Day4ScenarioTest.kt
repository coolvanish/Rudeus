package kz.rudy.ar.core.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.meta.DiskFixtureSource
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.Jobs
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.pipeline.ScenarioFixtures.Box
import kz.rudy.ar.core.pipeline.ScenarioFixtures.Cylinder
import kz.rudy.ar.core.pipeline.ScenarioFixtures.Solid
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T18: the whole Day 4 chain on the JVM, the way the phone runs it (scan masks -> [ScanInput.fromAnchor] ->
 * [SceneBuilder.build] -> [Level] -> [Physics]). Single-purpose checks live elsewhere and are not repeated here:
 * the anchor frame in `GeometryTest.scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame`, footprint accuracy and id
 * rules in `SceneBuilderTest`, push-out / step-up / cliff / pause rules in `PhysicsTest`.
 */
class Day4ScenarioTest {
    private val root = "docs/api-samples"
    private val profile = SurfaceProfile.TABLE
    private val params = PhysicsParams.forSurface(Surface.TABLE)
    private val dtMs = 16L

    /** Anchor at the world origin on a horizontal table; camera 0.5 m above it and 0.5 m back, 45° down at it. */
    private val anchor = Pose3.IDENTITY
    private val camera = Pose3.lookAt(Vec3(0f, 0.5f, 0.5f), anchor.position)

    // ---------------------------------------------------------------- 1. real SAM fixture -> Level

    @Test
    fun realSamTableFixtureBuildsALevelWithStableObjects() = runTest {
        val disk = DiskFixtureSource.repoRoot()
        val prompts = Json.decodeFromString<List<String>>(disk.readText("$root/${Jobs.SAM_TABLE}/prompts.json")!!)
        val result = FixtureApi(disk, root).segment("table-01", byteArrayOf(), prompts)
        val masks = ScanMasks.of(result)
        val w = masks.first().mask.width
        val h = masks.first().mask.height
        val intr = Intrinsics.fromFov(hFovForLongSide(LONG_SIDE_FOV_RAD, w, h), w, h)
        val input = ScanInput.fromAnchor(anchor, camera, intr, masks)
        assertNull(input.boundary, "no plane polygon -> unbounded level")

        val level = SceneBuilder().build(input)
        val lines = level.objects.map { o ->
            "${o.id} ${o.label} ${o.kind} ${cm(o.centroid.u)} ${cm(o.centroid.v)} ${cm(o.heightM)}"
        }
        println("T18 table-01 objects (id label kind u_cm v_cm h_cm):\n" + lines.joinToString("\n"))

        assertTrue(level.objects.size >= 4, "objects: ${level.objects.size}\n$lines")
        assertEquals(level.objects.size, level.objects.map { it.id }.toSet().size, "ids are distinct: $lines")
        val maxHeight = SceneParams().maxHeightM
        for (o in level.objects) {
            val n = o.id.removePrefix("${o.label}-")
            assertTrue(o.id.startsWith("${o.label}-") && n.toIntOrNull()?.let { it >= 1 } == true, "id form <label>-<n>: ${o.id}")
            val c = o.centroid
            assertTrue(c.u.isFinite() && c.v.isFinite(), "finite centroid ${o.id}: $c")
            assertTrue(c.length <= 0.6f, "${o.id} centroid $c is ${c.length} m from the anchor")
            assertTrue(o.heightM > 0f && o.heightM <= maxHeight, "${o.id} height ${o.heightM}")
            assertEquals(Semantics.kindFor(o.label, o.heightM, profile), o.kind, "kind of ${o.id}")
        }

        // Determinism: the same scan builds the same level, ids included.
        val again = SceneBuilder().build(input)
        assertEquals(level.objects.map { it.id }, again.objects.map { it.id })
        assertEquals(level, again)
    }

    // ---------------------------------------------------------------- 2. character on a Day 4 level

    @Test
    fun characterIsBlockedByCupStepsOntoBookAndRespawnsOffTheEdge() {
        val cupH = 0.09f
        val bookH = 0.02f
        val cup = LevelObject("cup-1", "cup", Semantics.kindFor("cup", cupH, profile), Polygon.circle(Vec2(0.15f, 0f), 0.04f), cupH)
        val book = LevelObject("book-1", "book", Semantics.kindFor("book", bookH, profile), Polygon.rect(Vec2(0f, 0.15f), 0.15f, 0.10f), bookH)
        assertEquals(ObjectKind.OBSTACLE, cup.kind)
        assertEquals(ObjectKind.PLATFORM, book.kind)
        val level = Level(listOf(cup, book), boundary = Polygon.rect(Vec2.ZERO, 0.60f, 0.60f), spawn = Vec2.ZERO)
        val start = PhysicsBody(CharacterState(pos = Vec2.ZERO, radiusM = profile.radiusM))
        val maxSteps = (10_000L / dtMs).toInt()

        // a) Into the cup: BLOCKED exactly once, never inside the push-out radius.
        val toCup = run(Physics.walkTo(start, Vec2(0.30f, 0f)), level, maxSteps) { it.state.mode == Mode.IDLE }
        assertEquals(1, toCup.events.count { it == CharacterEvent.BLOCKED }, "BLOCKED events: ${toCup.events}")
        val minGap = profile.radiusM + 0.04f - 0.001f
        for (b in toCup.bodies) {
            val p = b.state.pos
            assertTrue(p.u.isFinite() && p.v.isFinite() && b.state.h.isFinite(), "finite: ${b.state}")
            assertTrue(p.distanceTo(cup.centroid) >= minGap, "centre ${p.distanceTo(cup.centroid)} m from the cup")
        }
        assertEquals(Mode.WALKING, toCup.last.state.mode, "still pressing against the cup")

        // b) Onto the 2 cm book: step-up, arrives on top.
        val onBook = run(Physics.walkTo(start, Vec2(0f, 0.15f)), level, maxSteps) { it.state.mode == Mode.IDLE }
        assertTrue(onBook.last.state.pos.distanceTo(Vec2(0f, 0.15f)) <= 0.005f, "ends at ${onBook.last.state.pos}")
        assertEquals(bookH, onBook.last.state.h, 0.002f)
        assertTrue(CharacterEvent.STEPPED_UP in onBook.events)
        assertTrue(CharacterEvent.BLOCKED !in onBook.events)

        // c) Off the edge: falls over the cliff, respawns at the spawn within 3 s of leaving the ground.
        val offEdge = run(Physics.walkTo(start, Vec2(0.40f, 0.40f)), level, maxSteps) {
            it.state.mode == Mode.IDLE && it.state.target == null && it.state.pos == level.spawn
        }
        val modes = offEdge.bodies.map { it.state.mode }
        val fell = modes.indexOfFirst { it == Mode.FALLING }
        assertTrue(fell > 0, "never fell: ${modes.distinct()}")
        assertTrue(Physics.isOverCliff(level, offEdge.bodies[fell].state.pos), "falls over the cliff")
        val respawning = modes.indices.filter { it > 0 && modes[it] == Mode.RESPAWNING && modes[it - 1] != Mode.RESPAWNING }
        assertEquals(1, respawning.size, "RESPAWNING entered once")
        val end = offEdge.last.state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(level.spawn, end.pos)
        assertEquals(0f, end.h)
        assertTrue((offEdge.bodies.size - 1 - fell) * dtMs <= 3_000L, "respawn took ${(offEdge.bodies.size - 1 - fell) * dtMs} ms")
        assertEquals(1, offEdge.events.count { it == CharacterEvent.RESPAWNED })
        assertTrue(CharacterEvent.BLOCKED !in offEdge.events)

        // d) PAUSED mid-walk freezes for 2 s; resume continues to the book.
        val midWalk = run(Physics.walkTo(start, Vec2(0f, 0.15f)), level, 40) { false }.last
        assertEquals(Mode.WALKING, midWalk.state.mode)
        val paused = Physics.pause(midWalk)
        val frozen = run(paused, level, (2_000L / dtMs).toInt()) { false }
        assertTrue(frozen.bodies.all { it == paused }, "paused steps are the identity")
        assertEquals(midWalk.state.pos, frozen.last.state.pos)
        val resumed = run(Physics.resume(frozen.last), level, maxSteps) { it.state.mode == Mode.IDLE }
        assertTrue(resumed.bodies[1].state.pos.v > midWalk.state.pos.v, "moves on after resume")
        assertTrue(resumed.last.state.pos.distanceTo(Vec2(0f, 0.15f)) <= 0.005f)

        // e) PAUSED while pinned against the cup: no second BLOCKED across pause, 2 s frozen, resume.
        val pinned = toCup.bodies[toCup.bodies.size / 2]
        assertTrue(pinned.blocked, "pinned against the cup")
        val pausedPinned = Physics.pause(pinned)
        val chain = listOf(pinned) +
            run(pausedPinned, level, (2_000L / dtMs).toInt()) { false }.bodies +
            run(Physics.resume(pausedPinned), level, 60) { false }.bodies
        val events = chain.zipWithNext { a, b -> CharacterEvents.between(a, b) }.flatten()
        assertTrue(CharacterEvent.BLOCKED !in events, "BLOCKED re-announced: $events")
        assertEquals(listOf(CharacterEvent.PAUSED, CharacterEvent.RESUMED), events)
        assertEquals(pinned.state.pos, chain.last().state.pos, "still pinned at the same spot")
    }

    // ---------------------------------------------------------------- 3. rescan

    @Test
    fun rescanKeepsIdsWithinFiveCmAndReplacesDynamicObjects() {
        val intr = Intrinsics.fromFov((60.0 * PI / 180.0).toFloat(), 1280, 720)
        val plane = PlaneFrame.fromAnchorPose(anchor)
        fun scan(vararg objects: Pair<String, Solid>) = ScanInput.fromAnchor(
            anchor, camera, intr,
            objects.map { (label, s) -> LabeledMask(label, ScenarioFixtures.rasterise(s, intr, camera, plane)) },
        )
        fun cupAt(u: Float) = Cylinder(u, 0f, 0.04f, 0.09f)
        fun handAt(u: Float, v: Float) = Box(u - 0.04f, u + 0.04f, v - 0.05f, v + 0.05f, 0.03f)

        val builder = SceneBuilder()
        val first = builder.build(scan("cup" to cupAt(0.05f), "hand" to handAt(-0.12f, 0f)))
        assertEquals(listOf("cup-1", "hand-1"), first.objects.map { it.id })
        val hand1 = first.objects.single { it.label == "hand" }
        assertEquals(ObjectKind.DYNAMIC, hand1.kind)

        // Cup moved 3 cm, hand moved 15 cm: the cup keeps its id; the hand is rebuilt from the new mask where it is
        // now, with a fresh id (beyond rematchMaxDistM, like any label), and the old hand is gone.
        val second = builder.build(scan("cup" to cupAt(0.08f), "hand" to handAt(-0.12f, 0.15f)), previous = first)
        val cup2 = second.objects.single { it.label == "cup" }
        assertEquals("cup-1", cup2.id)
        assertEquals(0.03f, cup2.centroid.u - first.objects.single { it.label == "cup" }.centroid.u, 0.01f)
        val hand2 = second.objects.single { it.label == "hand" }
        assertEquals(ObjectKind.DYNAMIC, hand2.kind)
        assertEquals("hand-2", hand2.id)
        assertEquals(0.15f, hand2.centroid.v - hand1.centroid.v, 0.02f)

        // Cup moved 8 cm (from the first scan): a new object with a fresh id. No hand mask -> no hand in the level.
        val third = builder.build(scan("cup" to cupAt(0.13f)), previous = first)
        val cup3 = third.objects.single()
        assertNotEquals("cup-1", cup3.id)
        assertEquals("cup-2", cup3.id)
        assertTrue(third.objects.none { it.kind == ObjectKind.DYNAMIC }, "a hand is never carried over by the builder")
    }

    // ---------------------------------------------------------------- helpers

    private class Trace(val bodies: List<PhysicsBody>, val events: List<CharacterEvent>) {
        val last: PhysicsBody get() = bodies.last()
    }

    /** Steps at [dtMs] until [done] or [maxSteps]; collects every body (index 0 = input) and the HUD events between them. */
    private fun run(body: PhysicsBody, level: Level, maxSteps: Int, done: (PhysicsBody) -> Boolean): Trace {
        val bodies = arrayListOf(body)
        val events = ArrayList<CharacterEvent>()
        var b = body
        var i = 0
        while (i < maxSteps && !done(b)) {
            val next = Physics.step(b, level, dtMs, params)
            events += CharacterEvents.between(b, next)
            bodies += next
            b = next
            i++
        }
        return Trace(bodies, events)
    }

    /**
     * Horizontal FOV that gives [longFovRad] across the frame's long side. For a landscape frame (the sensor-oriented
     * image the phone sends to SAM) this is `longFovRad` itself, i.e. `Intrinsics.fromFov(60°, w, h)`. table-01 is a
     * 900 x 1600 PORTRAIT photo: 60° across its 900 px side would make the vertical FOV ~91°, wider than a phone main
     * camera, and the top rows (the toy car) would graze the table 1.2 m away.
     */
    private fun hFovForLongSide(longFovRad: Float, w: Int, h: Int): Float {
        val f = (max(w, h) / 2f) / tan(longFovRad / 2f)
        return 2f * atan((w / 2f) / f)
    }

    private companion object {
        val LONG_SIDE_FOV_RAD = (60.0 * PI / 180.0).toFloat()
    }

    private fun cm(m: Float): String = ((m * 1000f).roundToInt() / 10.0).toString()
}
