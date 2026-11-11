package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Choreographies
import kz.rudy.ar.core.emote.EmotePlayer
import kz.rudy.ar.core.physics.CastPhysics
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.scenario.ScenarioPlayer
import kz.rudy.ar.core.scenario.ScenarioRunner
import kz.rudy.ar.core.scenario.ScenarioStep
import kz.rudy.ar.core.scenario.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Characterization tests of GameLoop, the AR host's physics driver (T47). Written against the class as it is; the
// same assertions run against the copy in :core. They pin what the loop does, including the locked decision
// "PAUSED freezes physics, never resets".

private const val FRAME_MS = 16L

/** An arbitrary host clock origin (SystemClock.elapsedRealtime is never small on a phone). */
private const val T0 = 1_000_000L

/** The Day 5 table without its objects: a +-0.35 m square. */
private val square = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.70f, 0.70f))

/** Drives a loop the way RudeusArView does: a host clock, frames, and the events the HUD would get. */
private class Driver(val loop: GameLoop, var now: Long = T0) {
    val events = ArrayList<Pair<String, CharacterEvent>>()

    /** One AR frame [gapMs] after the previous one. */
    fun frame(tracking: Boolean = true, gapMs: Long = FRAME_MS, beforeStep: (() -> Unit)? = null) {
        now += gapMs
        loop.onFrame(now, tracking, { id, e -> events += id to e }, beforeStep)
    }

    fun frames(n: Int, tracking: Boolean = true) = repeat(n) { frame(tracking) }

    /** The events since the last drain. */
    fun drain(): List<Pair<String, CharacterEvent>> = events.toList().also { events.clear() }
}

private fun GameLoop.pos(id: String): Vec2 = body(id)!!.state.pos

private fun GameLoop.mode(id: String): Mode = body(id)!!.state.mode

class GameLoopTest {

    @Test
    fun firstFrameStepsWithZeroDt() {
        val loop = GameLoop(square)
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.walkTo("a", Vec2(0.3f, 0.1f))
        val cast = loop.onFrame(T0, tracking = true)
        assertSame(loop.cast, cast)
        assertEquals(Vec2(0.1f, 0.1f), loop.pos("a"))
        assertEquals(Mode.WALKING, loop.mode("a"))
        assertEquals(0L, loop.simMs)
        assertEquals(0L, loop.walkPhaseMs("a"))
    }

    @Test
    fun dtIsTheFrameGapClampedToFiftyMs() {
        assertEquals(50L, MAX_STEP_DT_MS)
        val loop = GameLoop(square)
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.walkTo("a", Vec2(0.3f, 0.1f))
        loop.onFrame(T0, true)
        loop.onFrame(T0 + 16, true)
        assertEquals(16L, loop.simMs, "a 16 ms gap steps 16")
        loop.onFrame(T0 + 1_016, true)
        assertEquals(66L, loop.simMs, "a 1000 ms gap steps 50")
        loop.onFrame(T0 + 500, true)
        assertEquals(66L, loop.simMs, "a clock that went back steps 0")
        loop.onFrame(T0 + 516, true)
        assertEquals(82L, loop.simMs, "the frame that went back is the new reference")
        // The body moved by exactly the stepped time at walk speed (5 cm/s), not by the wall-clock gaps.
        assertEquals(PhysicsParams.DEFAULT.walkSpeedMps * 0.082f, loop.pos("a").u - 0.1f, 1e-6f)
        assertEquals(82L, loop.walkPhaseMs("a"))
    }

    @Test
    fun simClockIsTheSumOfSteppedDt() {
        // (host ms after T0, tracking); stepped dt: 0, 16, 17, 33, 0, 50, -, -, 0 (first back), 16 = 132.
        val frames = listOf(
            0L to true, 16L to true, 33L to true, 66L to true, 66L to true, 186L to true,
            200L to false, 300L to false, 316L to true, 332L to true,
        )
        val withCast = GameLoop(square).apply { add("a", Vec2(0.1f, 0.1f), Ability.JUMP) }
        val empty = GameLoop(square)
        for (loop in listOf(withCast, empty)) {
            for ((t, tracking) in frames) loop.onFrame(T0 + t, tracking)
            assertEquals(132L, loop.simMs, "cast of ${loop.ids.size}")
        }
    }

    @Test
    fun notTrackingPausesEveryMemberAndStepsNothing() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.1f, -0.1f), Ability.FLY)
        loop.walkTo("a", Vec2(0.3f, 0.1f))
        d.frames(10)
        val before = loop.cast
        val simBefore = loop.simMs
        val phaseA = loop.walkPhaseMs("a")
        assertTrue(phaseA > 0L)
        var hookCalls = 0
        repeat(20) { d.frame(tracking = false, beforeStep = { hookCalls++ }) }
        d.frame(tracking = false, gapMs = 5_000L, beforeStep = { hookCalls++ })
        assertEquals(before.ids, loop.ids)
        for (m in before.members) {
            val now = loop.body(m.id)!!.state
            assertEquals(Mode.PAUSED, now.mode, m.id)
            assertEquals(m.body.state.pos, now.pos, m.id)
            assertEquals(m.body.state.h, now.h, m.id)
            assertEquals(m.body.state.target, now.target, m.id)
        }
        assertEquals(simBefore, loop.simMs)
        assertEquals(phaseA, loop.walkPhaseMs("a"))
        assertEquals(0L, loop.walkPhaseMs("b"))
        assertEquals(0, hookCalls, "beforeStep runs only on tracking frames")
    }

    @Test
    fun resumeGivesBackModeAndTargetAndNeverResets() {
        val level = square
        val d = Driver(GameLoop(level))
        val loop = d.loop
        val target = Vec2(0.3f, 0.1f)
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.1f, -0.1f), Ability.JUMP)
        loop.walkTo("a", target)
        d.frames(10)
        val ids = loop.ids
        val posA = loop.pos("a")
        val posB = loop.pos("b")
        repeat(625) { d.frame(tracking = false) } // 10 s of untracked frames
        d.frame() // the first frame back steps dt = 0
        assertEquals(ids, loop.ids)
        assertEquals(posA, loop.pos("a"))
        assertEquals(posB, loop.pos("b"))
        assertEquals(target, loop.body("a")!!.state.target)
        assertSame(level, loop.level)
        assertEquals(Mode.WALKING, loop.mode("a"))
        assertEquals(Mode.IDLE, loop.mode("b"))
        d.frames(400) // 0.2 m at 5 cm/s is 4 s
        assertEquals(target, loop.pos("a"))
        assertEquals(Mode.IDLE, loop.mode("a"))
        assertNull(loop.body("a")!!.state.target)
        assertEquals(posB, loop.pos("b"))
    }

    @Test
    fun pauseAndResumeAreReportedOncePerBlip() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(-0.3f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.1f, -0.1f), Ability.JUMP)
        loop.walkTo("a", Vec2(0.3f, 0.1f)) // 12 s of walking: it never arrives here
        d.frames(5)
        d.drain()
        repeat(2) {
            repeat(30) { d.frame(tracking = false) }
            d.frames(5)
        }
        val blip = listOf(
            "a" to CharacterEvent.PAUSED, "b" to CharacterEvent.PAUSED,
            "a" to CharacterEvent.RESUMED, "b" to CharacterEvent.RESUMED,
        )
        assertEquals(blip + blip, d.drain())
    }

    @Test
    fun walkToBetweenFramesIsReportedByTheNextFrame() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        d.frames(3)
        d.drain()
        loop.walkTo("a", Vec2(0.3f, 0.1f)) // the tap handler, between two frames
        assertTrue(d.events.isEmpty())
        d.frame()
        assertEquals(listOf("a" to CharacterEvent.STARTED_WALKING), d.drain())
        loop.walkTo("a", Vec2(0.3f, 0.1f)) // the same target again
        d.frames(10)
        assertEquals(emptyList(), d.drain())
    }

    @Test
    fun eventsCarryMemberIdsInCastOrder() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("zed", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("amy", Vec2(-0.1f, -0.1f), Ability.JUMP)
        assertEquals(listOf("zed", "amy"), loop.ids)
        assertEquals("zed", loop.firstId)
        d.frames(2)
        loop.walkTo("amy", Vec2(-0.2f, -0.1f))
        loop.walkTo("zed", Vec2(0.2f, 0.1f))
        d.frame()
        assertEquals(listOf("zed" to CharacterEvent.STARTED_WALKING, "amy" to CharacterEvent.STARTED_WALKING), d.drain())
        assertEquals(loop.body("zed")!!.state, loop.state)
    }

    @Test
    fun addYieldsNoEventAndRemoveForgetsTheMember() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        d.frames(3)
        d.drain()
        loop.add("b", Vec2(-0.1f, -0.1f), Ability.JUMP)
        d.frame()
        assertEquals(emptyList(), d.drain(), "joining is announced by the host (PLACED), not by the loop")
        val cast = loop.cast
        loop.add("b", Vec2(0.2f, 0.2f), Ability.ROLL)
        loop.add("a", Vec2(-0.2f, 0.2f), Ability.FLY)
        assertSame(cast, loop.cast, "a second add of the same id changes nothing")
        // Give b every clock: a walk, a landing, a running emote.
        loop.walkTo("b", Vec2(-0.25f, -0.1f))
        d.frames(5)
        assertTrue(loop.jump("b"))
        d.frames(60)
        assertTrue(d.drain().contains("b" to CharacterEvent.LANDED))
        loop.emote("b", Choreographies.HELLO)
        assertTrue(loop.walkPhaseMs("b") > 0L)
        assertTrue(loop.landedAtMs("b") != NEVER_LANDED)
        assertNotNull(loop.emotePose("b"))

        loop.remove("b")
        assertFalse(loop.has("b"))
        assertNull(loop.body("b"))
        assertNull(loop.state("b"))
        assertEquals(0L, loop.walkPhaseMs("b"))
        assertEquals(NEVER_LANDED, loop.landedAtMs("b"))
        assertNull(loop.emotePose("b"))
        assertEquals(listOf("a"), loop.ids)
        d.frame()
        assertEquals(emptyList(), d.drain())
        val afterRemove = loop.cast
        loop.remove("ghost")
        assertSame(afterRemove, loop.cast)

        loop.add("b", Vec2(-0.1f, -0.1f), Ability.JUMP) // back again: fresh clocks, still no event
        assertEquals(0L, loop.walkPhaseMs("b"))
        assertEquals(NEVER_LANDED, loop.landedAtMs("b"))
        d.frame()
        assertEquals(emptyList(), d.drain())
    }

    @Test
    fun spawnForTheFirstMemberIsTheOriginThenSpawnNear() {
        val level = Scenarios.day5Level().copy(spawn = Vec2(0.05f, -0.05f))
        val loop = GameLoop(level)
        assertNull(loop.firstId)
        assertEquals(CharacterState(), loop.state, "a default state while the cast is empty")
        assertEquals(Vec2.ZERO, loop.spawnFor(Vec2(0.1f, 0.1f), Vec2(0f, 1f)), "not level.spawn: the origin")
        loop.configure(SurfaceProfile.TABLE, 1.5f)
        loop.add("a", loop.spawnFor(Vec2(0.1f, 0.1f), Vec2(0f, 1f)), Ability.JUMP)
        assertEquals(Vec2.ZERO, loop.pos("a"))
        for (preferred in listOf(Vec2(1f, 0f), Vec2(0f, -1f), Vec2(-0.6f, 0.8f))) {
            val near = loop.pos("a")
            val p = loop.spawnFor(near, preferred)
            assertEquals(CastPhysics.spawnNear(level, loop.cast, loop.radiusM, near, preferred, loop.params), p)
            assertTrue(p != Vec2.ZERO)
        }
    }

    @Test
    fun jumpReturnsFalseWhenPhysicsIgnoresIt() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("hop", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("roll", Vec2(-0.1f, -0.1f), Ability.ROLL)
        d.frames(2)
        val cast = loop.cast
        assertFalse(loop.jump("roll"), "ROLL cannot jump")
        assertFalse(loop.jump("ghost"), "unknown id")
        assertSame(cast, loop.cast)
        assertTrue(loop.jump("hop"), "an idle JUMP character")
        assertEquals(Mode.JUMPING, loop.mode("hop"))
        val airborne = loop.cast
        assertFalse(loop.jump("hop"), "airborne")
        assertSame(airborne, loop.cast)
        d.frames(3)
        assertTrue(loop.body("hop")!!.state.h > 0f)
        assertFalse(loop.jump("hop"), "still airborne")
        d.frames(60)
        assertEquals(Mode.IDLE, loop.mode("hop"))
        d.frame(tracking = false)
        assertFalse(loop.jump("hop"), "paused")
        assertEquals(Mode.PAUSED, loop.mode("hop"))
        d.frame()
        assertTrue(loop.jump("hop"), "tracking again")
    }

    @Test
    fun landedStampsTheHostClockOfThatFrame() {
        assertEquals(Long.MIN_VALUE / 2, NEVER_LANDED)
        val d = Driver(GameLoop(square), now = 5_000_000L)
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.1f, -0.1f), Ability.JUMP)
        assertEquals(NEVER_LANDED, loop.landedAtMs("a"))
        assertEquals(NEVER_LANDED, loop.landedAtMs("ghost"))
        d.frames(2)
        assertTrue(loop.jump("a"))
        d.frames(10)
        repeat(60) { d.frame(tracking = false) } // a stall mid-air: the host clock runs on, the sim clock does not
        var landedFrame: Long? = null
        repeat(100) {
            val seen = d.events.size
            d.frame()
            if (d.events.drop(seen).contains("a" to CharacterEvent.LANDED)) landedFrame = d.now
        }
        assertNotNull(landedFrame)
        assertEquals(1, d.events.count { it == "a" to CharacterEvent.LANDED })
        assertEquals(landedFrame, loop.landedAtMs("a"))
        assertTrue(loop.landedAtMs("a") != loop.simMs)
        assertEquals(NEVER_LANDED, loop.landedAtMs("b"))
        assertTrue(d.now - loop.landedAtMs("b") > 0L)
        assertTrue(Long.MAX_VALUE / 2 - NEVER_LANDED > 0L, "now - NEVER_LANDED cannot overflow for any clock up to 2^62")
    }

    @Test
    fun walkPhaseCountsOnlySteppedWalkingTime() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(-0.3f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(0.1f, -0.2f), Ability.JUMP)
        loop.walkTo("a", Vec2(0.3f, 0.1f))
        d.frame()
        assertEquals(0L, loop.walkPhaseMs("a"), "first frame: dt = 0")
        d.frames(10)
        assertEquals(160L, loop.walkPhaseMs("a"))
        d.frame(gapMs = 1_000L)
        assertEquals(210L, loop.walkPhaseMs("a"), "a hitch adds the clamped 50 ms")
        repeat(5) { d.frame(tracking = false) }
        assertEquals(210L, loop.walkPhaseMs("a"), "paused")
        d.frame()
        assertEquals(210L, loop.walkPhaseMs("a"), "first frame back: dt = 0")
        d.frames(5)
        assertEquals(290L, loop.walkPhaseMs("a"))
        assertEquals(0L, loop.walkPhaseMs("b"), "an idle member")
        loop.walkTo("a", loop.pos("a")) // arrives on the next step
        d.frame()
        assertEquals(Mode.IDLE, loop.mode("a"))
        assertEquals(290L, loop.walkPhaseMs("a"), "the arriving step ends IDLE: not counted")
        d.frames(10)
        assertEquals(290L, loop.walkPhaseMs("a"))
    }

    @Test
    fun emoteRunsOnTheSimulatedClockAndFreezesWithIt() {
        val hello = Choreographies.HELLO
        val spin = Choreographies.SPIN
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        d.frames(4)
        loop.emote("a", hello)
        val start = loop.simMs
        assertEquals(48L, start)
        assertEquals(EmotePlayer.sample(hello, 0L), loop.emotePose("a"))
        d.frames(10)
        assertEquals(EmotePlayer.sample(hello, loop.simMs - start), loop.emotePose("a"))
        val frozen = loop.emotePose("a")
        repeat(60) { d.frame(tracking = false) }
        assertEquals(frozen, loop.emotePose("a"), "a pause freezes the emote")
        d.frame()
        assertEquals(frozen, loop.emotePose("a"), "first frame back: dt = 0")
        while (loop.simMs - start < hello.durationMs - FRAME_MS) d.frame()
        assertEquals(EmotePlayer.sample(hello, loop.simMs - start), assertNotNull(loop.emotePose("a")))
        while (loop.simMs - start < hello.durationMs) d.frame()
        assertNull(loop.emotePose("a"), "finished")
        d.frames(3)
        assertNull(loop.emotePose("a"))

        loop.emote("a", hello)
        d.frames(5)
        loop.emote("a", spin) // replaces the running one
        val start2 = loop.simMs
        assertEquals(EmotePlayer.sample(spin, 0L), loop.emotePose("a"))
        d.frames(5)
        assertEquals(EmotePlayer.sample(spin, loop.simMs - start2), loop.emotePose("a"))

        loop.emote("ghost", hello)
        assertNull(loop.emotePose("ghost"))
    }

    @Test
    fun beforeStepRunsAfterResumeAndBeforeTheStep() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        d.frames(3)
        d.drain()
        // An ordinary frame: the hook sees the clock before this frame's step, and its jump is stepped and reported.
        var simInHook = -1L
        d.frame(beforeStep = {
            simInHook = loop.simMs
            assertTrue(loop.jump("a"))
        })
        assertEquals(loop.simMs - FRAME_MS, simInHook)
        assertTrue(loop.body("a")!!.state.h > 0f)
        assertEquals(listOf("a" to CharacterEvent.JUMPED), d.drain())
        d.frames(60)
        assertEquals(Mode.IDLE, loop.mode("a"))
        repeat(10) { d.frame(tracking = false) }
        d.drain()
        // The first frame back from a pause: the hook runs after resume, so the jump is accepted.
        var modeInHook: Mode? = null
        var accepted = false
        d.frame(beforeStep = {
            modeInHook = loop.mode("a")
            accepted = loop.jump("a")
        })
        assertEquals(Mode.IDLE, modeInHook)
        assertTrue(accepted)
        assertEquals(Mode.JUMPING, loop.mode("a"))
        // Pinned as found (T47): that frame's diff is PAUSED -> JUMPING, which CharacterEvents.between names RESUMED
        // (one transition per diff), so this jump is never reported as JUMPED. Its landing is reported as usual.
        assertEquals(listOf("a" to CharacterEvent.RESUMED), d.drain())
        d.frames(60)
        assertEquals(listOf("a" to CharacterEvent.LANDED), d.drain())
    }

    @Test
    fun restartAtSpawnPutsTheFirstMemberBackWithoutEvents() {
        val spawn = Vec2(0.1f, -0.1f)
        val d = Driver(GameLoop(square.copy(spawn = spawn)))
        val loop = d.loop
        GameLoop(square).restartAtSpawn() // an empty cast: a no-op
        loop.configure(SurfaceProfile.TABLE, 1.5f)
        loop.add("a", Vec2(-0.1f, 0.1f), Ability.FLY)
        loop.add("b", Vec2(0.2f, 0.2f), Ability.JUMP)
        loop.walkTo("a", Vec2(-0.25f, 0.1f))
        loop.walkTo("b", Vec2(0.25f, 0.25f))
        d.frames(3)
        assertTrue(loop.jump("a"))
        d.frames(10)
        assertTrue(loop.body("a")!!.state.h > 0f)
        d.drain()
        val b = loop.body("b")
        loop.restartAtSpawn()
        val a = loop.body("a")!!
        assertEquals(spawn, a.state.pos)
        assertEquals(0f, a.state.h)
        assertNull(a.state.target)
        assertEquals(Mode.IDLE, a.state.mode)
        assertEquals(0f, a.verticalVelocity)
        assertEquals(Ability.FLY, a.state.ability)
        assertEquals(SurfaceProfile.TABLE.radiusAt(1.5f), a.state.radiusM)
        assertSame(b, loop.body("b"), "the second member is untouched")
        d.frame()
        assertEquals(emptyList(), d.drain(), "the teleport is not a transition")

        // Paused: stays PAUSED, resumes to IDLE.
        loop.walkTo("a", Vec2(-0.25f, 0.1f))
        d.frames(3)
        d.frame(tracking = false)
        d.drain()
        loop.restartAtSpawn()
        assertEquals(Mode.PAUSED, loop.mode("a"))
        assertEquals(spawn, loop.pos("a"))
        d.frame(tracking = false)
        assertEquals(emptyList(), d.drain())
        d.frame()
        assertEquals(Mode.IDLE, loop.mode("a"))
        assertEquals(listOf("a" to CharacterEvent.RESUMED, "b" to CharacterEvent.RESUMED), d.drain())

        // Pinned as found (T47): the reset of the event diff covers the whole cast, so a transition of ANOTHER member
        // made since the last frame (b's jump) is not reported either.
        assertTrue(loop.jump("b"))
        loop.restartAtSpawn()
        d.frame()
        assertEquals(Mode.JUMPING, loop.mode("b"))
        assertEquals(emptyList(), d.drain())
    }

    @Test
    fun configureResizesEveryMemberAndKeepsMotion() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.15f, -0.15f), Ability.JUMP)
        d.frames(2)
        assertTrue(loop.jump("a"))
        d.frames(5)
        val inAir = loop.body("a")!!
        assertEquals(Mode.JUMPING, inAir.state.mode)
        val bPos = loop.pos("b")
        loop.configure(SurfaceProfile.TABLE, 2f)
        val r = SurfaceProfile.TABLE.radiusAt(2f)
        assertEquals(PhysicsParams.forProfile(SurfaceProfile.TABLE, 2f), loop.params)
        assertEquals(r, loop.radiusM)
        for (id in loop.ids) assertEquals(r, loop.body(id)!!.state.radiusM, id)
        assertEquals(inAir.copy(state = inAir.state.copy(radiusM = r)), loop.body("a"), "only the radius changed")
        assertEquals(bPos, loop.pos("b"))
        d.drain()
        d.frames(60)
        assertTrue(d.drain().contains("a" to CharacterEvent.LANDED), "the jump carries on")
        loop.add("c", Vec2(0.15f, -0.15f), Ability.JUMP)
        assertEquals(r, loop.body("c")!!.state.radiusM, "a member added later")

        d.frame(tracking = false)
        loop.configure(SurfaceProfile.TABLE, 0.5f)
        for (id in loop.ids) {
            assertEquals(Mode.PAUSED, loop.mode(id), id)
            assertEquals(SurfaceProfile.TABLE.radiusAt(0.5f), loop.body(id)!!.state.radiusM, id)
        }
        loop.configure(SurfaceProfile.FLOOR)
        assertEquals(SurfaceProfile.FLOOR.radiusAt(1f), loop.radiusM)
        assertEquals(PhysicsParams.forProfile(SurfaceProfile.FLOOR), loop.params)
    }

    @Test
    fun setAbilityChangesOnlyTheAbility() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.1f, -0.1f), Ability.JUMP)
        loop.walkTo("a", Vec2(0.3f, 0.1f))
        d.frames(3)
        d.drain()
        val before = loop.body("a")!!
        val b = loop.body("b")
        loop.setAbility("a", Ability.ROLL)
        assertEquals(before.copy(state = before.state.copy(ability = Ability.ROLL)), loop.body("a"))
        assertSame(b, loop.body("b"))
        val cast = loop.cast
        loop.setAbility("a", Ability.ROLL)
        loop.setAbility("ghost", Ability.FLY)
        assertSame(cast, loop.cast)
        val u0 = loop.pos("a").u
        d.frame()
        assertEquals(2f * PhysicsParams.DEFAULT.walkSpeedMps * 0.016f, loop.pos("a").u - u0, 1e-6f, "ROLL walks twice as fast")
        assertEquals(emptyList(), d.drain())
        assertFalse(loop.jump("a"))
    }

    @Test
    fun levelSwapTakesEffectOnTheNextStepWithoutAReset() {
        val d = Driver(GameLoop(square))
        val loop = d.loop
        loop.add("a", Vec2(0.1f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(-0.15f, -0.15f), Ability.JUMP)
        loop.walkTo("a", Vec2(0.3f, 0.1f))
        d.frames(5)
        d.drain()
        val posA = loop.pos("a")
        val b = loop.body("b")
        val book = LevelObject("book-1", "book", ObjectKind.PLATFORM, Polygon.rect(posA, 0.10f, 0.10f), 0.02f)
        val scanned = square.copy(objects = listOf(book))
        val cast = loop.cast
        loop.level = scanned // what a scan does
        assertSame(cast, loop.cast, "assigning a level moves nobody")
        assertSame(scanned, loop.level)
        d.frame()
        assertEquals(0.02f, loop.body("a")!!.state.h, "the next step stands on the new book")
        assertEquals(listOf("a" to CharacterEvent.STEPPED_UP), d.drain())
        assertEquals(PhysicsParams.DEFAULT.walkSpeedMps * 0.016f, loop.pos("a").u - posA.u, 1e-6f)
        assertEquals(b, loop.body("b"))
        assertEquals(Mode.WALKING, loop.mode("a"))
    }

    @Test
    fun overlappingMembersAreSeparated() {
        val loop = GameLoop(square)
        loop.add("a", Vec2(0f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(0.02f, 0.1f), Ability.JUMP)
        loop.onFrame(T0, true) // even the first frame (dt = 0) separates
        assertTrue(Vec2(-0.02f, 0.1f).distanceTo(loop.pos("a")) < 1e-6f, "${loop.pos("a")}")
        assertTrue(Vec2(0.04f, 0.1f).distanceTo(loop.pos("b")) < 1e-6f, "${loop.pos("b")}")
    }

    @Test
    fun scenarioOnTheSimulatedClockMatchesScenarioRunner() {
        val level = Scenarios.day5Level()
        val script = Scenarios.DAY5
        for (ability in listOf(Ability.JUMP, Ability.FLY)) {
            for (stallAtFrame in listOf<Int?>(null, 437)) { // 437 x 16 ms = 7 s: on the book, mid-walk
                val loop = GameLoop(level)
                loop.add("hero", loop.spawnFor(Vec2.ZERO, Vec2(1f, 0f)), ability)
                loop.restartAtSpawn()
                val player = ScenarioPlayer(script, loop.simMs)
                val feed: () -> Unit = {
                    for (step in player.due(loop.simMs)) when (step) {
                        is ScenarioStep.WalkTo -> loop.walkTo("hero", step.target)
                        is ScenarioStep.Jump -> loop.jump("hero")
                    }
                }
                val events = ArrayList<CharacterEvent>()
                var now = T0
                var frame = 0
                while (loop.simMs <= script.durationMs + FRAME_MS) {
                    if (frame == stallAtFrame) repeat(44) { // 704 ms untracked
                        now += FRAME_MS
                        loop.onFrame(now, false, { _, e -> events += e }, feed)
                    }
                    now += FRAME_MS
                    loop.onFrame(now, true, { _, e -> events += e }, feed)
                    frame++
                }
                assertTrue(player.finished)
                val expected = ScenarioRunner.run(script, level, ability, loop.params, loop.radiusM, FRAME_MS).eventNames()
                val label = "$ability stall=$stallAtFrame"
                if (stallAtFrame == null) {
                    assertEquals(expected, events, label)
                } else {
                    val blip = listOf(CharacterEvent.PAUSED, CharacterEvent.RESUMED)
                    assertEquals(blip, events.filter { it in blip }, label)
                    val i = events.indexOf(CharacterEvent.PAUSED)
                    assertEquals(CharacterEvent.RESUMED, events[i + 1], label)
                    assertEquals(expected, events.filter { it !in blip }, label)
                }
            }
        }
    }

    /** :core only: the constructor parameter that replaced the host's file-level `SEPARATION_ENABLED`. */
    @Test
    fun separationCanBeSwitchedOff() {
        val loop = GameLoop(square, separate = false)
        loop.add("a", Vec2(0f, 0.1f), Ability.JUMP)
        loop.add("b", Vec2(0.02f, 0.1f), Ability.JUMP)
        loop.onFrame(T0, true)
        repeat(10) { loop.onFrame(T0 + (it + 1) * FRAME_MS, true) }
        assertEquals(Vec2(0f, 0.1f), loop.pos("a"))
        assertEquals(Vec2(0.02f, 0.1f), loop.pos("b"))
    }
}
