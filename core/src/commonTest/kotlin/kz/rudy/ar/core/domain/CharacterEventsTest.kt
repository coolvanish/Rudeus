package kz.rudy.ar.core.domain

import kz.rudy.ar.core.domain.CharacterEvent.ARRIVED
import kz.rudy.ar.core.domain.CharacterEvent.BLOCKED
import kz.rudy.ar.core.domain.CharacterEvent.FELL
import kz.rudy.ar.core.domain.CharacterEvent.JUMPED
import kz.rudy.ar.core.domain.CharacterEvent.LANDED
import kz.rudy.ar.core.domain.CharacterEvent.PAUSED
import kz.rudy.ar.core.domain.CharacterEvent.RESPAWNED
import kz.rudy.ar.core.domain.CharacterEvent.RESUMED
import kz.rudy.ar.core.domain.CharacterEvent.STARTED_WALKING
import kz.rudy.ar.core.domain.CharacterEvent.STEPPED_UP
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DT = 16L

private val empty = Level(emptyList())

private fun cup(center: Vec2, radius: Float = 0.04f) =
    LevelObject("cup", "cup", ObjectKind.OBSTACLE, Polygon.circle(center, radius), 0.09f)

private fun book(center: Vec2, heightM: Float = 0.03f) =
    LevelObject("book", "book", ObjectKind.PLATFORM, Polygon.rect(center, 0.1f, 0.1f), heightM)

/** [bodies] followed by fixed-dt steps until [done] holds (checked after each step) or [maxSteps] elapse. */
private fun stepUntil(
    bodies: List<PhysicsBody>,
    level: Level,
    maxSteps: Int = 4000,
    done: (PhysicsBody) -> Boolean,
): List<PhysicsBody> {
    val out = ArrayList(bodies)
    var b = out.last()
    var i = 0
    while (i < maxSteps) {
        b = Physics.step(b, level, DT)
        out += b
        i++
        if (done(b)) break
    }
    return out
}

/** Every event the host would see while replaying [bodies] (one `between` per consecutive pair). */
private fun events(bodies: List<PhysicsBody>): List<CharacterEvent> =
    bodies.zipWithNext().flatMap { (a, b) -> CharacterEvents.between(a, b) }

private fun idleAt(pos: Vec2 = Vec2.ZERO, h: Float = 0f, ability: Ability = Ability.JUMP) =
    PhysicsBody(CharacterState(pos = pos, h = h, ability = ability))

class CharacterEventsTest {

    @Test
    fun startedWalking() {
        val idle = idleAt()
        val walking = Physics.walkTo(idle, Vec2(0.1f, 0f))
        assertEquals(listOf(STARTED_WALKING), CharacterEvents.between(idle, walking))
        // An IDLE state that already carries a target starts walking on its first step.
        val armed = PhysicsBody(CharacterState(target = Vec2(0.1f, 0f)))
        val stepped = Physics.step(armed, empty, DT)
        assertEquals(listOf(STARTED_WALKING), CharacterEvents.between(armed, stepped))
        // Mid-walk steps emit nothing.
        assertEquals(emptyList(), CharacterEvents.between(walking, Physics.step(walking, empty, DT)))
    }

    @Test
    fun arrived() {
        val idle = idleAt()
        val walk = stepUntil(listOf(idle, Physics.walkTo(idle, Vec2(0.1f, 0.05f))), empty) { it.state.mode == Mode.IDLE }
        assertEquals(listOf(STARTED_WALKING, ARRIVED), events(walk))
        val prev = CharacterState(pos = Vec2(0.099f, 0f), target = Vec2(0.1f, 0f), mode = Mode.WALKING)
        assertEquals(listOf(ARRIVED), CharacterEvents.between(prev, prev.copy(pos = Vec2(0.1f, 0f), target = null, mode = Mode.IDLE)))
    }

    @Test
    fun jumped() {
        val idle = idleAt()
        val jumped = Physics.jump(idle)
        assertEquals(listOf(JUMPED), CharacterEvents.between(idle, jumped))
        // The CharacterState-only overload is announced the same way.
        assertEquals(listOf(JUMPED), CharacterEvents.between(idle.state, Physics.jump(idle.state)))
        val hop = stepUntil(listOf(idle, jumped), empty) { it.state.mode == Mode.IDLE }
        assertEquals(listOf(JUMPED, LANDED), events(hop))
    }

    @Test
    fun landed() {
        // Jumping while walking: the landing keeps the target, so the mode goes JUMPING -> WALKING. That is LANDED,
        // not a second STARTED_WALKING.
        val idle = idleAt()
        val walking = stepUntil(listOf(idle, Physics.walkTo(idle, Vec2(0.3f, 0f))), empty, maxSteps = 10) { false }
        val hop = stepUntil(walking + Physics.jump(walking.last()), empty) { it.state.mode == Mode.IDLE }
        assertEquals(listOf(STARTED_WALKING, JUMPED, LANDED, ARRIVED), events(hop))
        val air = CharacterState(pos = Vec2(0.1f, 0f), h = 0.004f, target = Vec2(0.3f, 0f), mode = Mode.JUMPING)
        assertEquals(listOf(LANDED), CharacterEvents.between(air, air.copy(h = 0f, mode = Mode.WALKING)))
        assertEquals(listOf(LANDED), CharacterEvents.between(air.copy(target = null), air.copy(h = 0f, target = null, mode = Mode.IDLE)))

        // Walking off a book is a short fall that ends on the table: FELL then LANDED, the walk goes on.
        val level = Level(listOf(book(Vec2(0.1f, 0f))))
        val onBook = idleAt(Vec2(0.1f, 0f), h = 0.03f)
        val drop = stepUntil(listOf(onBook, Physics.walkTo(onBook, Vec2(0.3f, 0f))), level) { it.state.mode == Mode.IDLE }
        assertEquals(listOf(STARTED_WALKING, FELL, LANDED, ARRIVED), events(drop))
        val falling = CharacterState(h = 0.002f, mode = Mode.FALLING)
        assertEquals(listOf(LANDED), CharacterEvents.between(falling, falling.copy(h = 0f, mode = Mode.IDLE)))
    }

    @Test
    fun fell() {
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = Vec2(0.02f, 0.01f))
        val idle = idleAt()
        val offTheEdge = stepUntil(listOf(idle, Physics.walkTo(idle, Vec2(0.25f, 0f))), level) { it.state.mode == Mode.IDLE }
        assertEquals(listOf(STARTED_WALKING, FELL, RESPAWNED), events(offTheEdge))

        // A FLY character coming down over the cliff stays JUMPING until it respawns; that is a fall too.
        val flyer = idleAt(Vec2(0.1f, 0f), ability = Ability.FLY)
        val walking = Physics.walkTo(flyer, Vec2(0.6f, 0f))
        val glide = stepUntil(listOf(flyer, walking, Physics.jump(walking)), level) { it.state.mode == Mode.IDLE }
        assertTrue(glide.zipWithNext().any { (a, b) -> a.state.mode == Mode.JUMPING && b.state.mode == Mode.RESPAWNING })
        assertEquals(listOf(STARTED_WALKING, JUMPED, FELL, RESPAWNED), events(glide))
    }

    @Test
    fun respawned() {
        val sinking = CharacterState(pos = Vec2(0.2f, 0f), h = -0.06f, target = Vec2(0.25f, 0f), mode = Mode.RESPAWNING)
        val respawned = Physics.step(PhysicsBody(sinking), empty, DT).state
        assertEquals(Mode.IDLE, respawned.mode)
        // h goes from below the plane back to 0: that is the respawn, not a step up.
        assertEquals(listOf(RESPAWNED), CharacterEvents.between(sinking, respawned))
    }

    @Test
    fun pausedAndResumed() {
        val idle = idleAt()
        val walking = stepUntil(listOf(idle, Physics.walkTo(idle, Vec2(0.2f, 0f))), empty, maxSteps = 5) { false }.last()
        val paused = Physics.pause(walking)
        assertEquals(listOf(PAUSED), CharacterEvents.between(walking, paused))
        assertEquals(emptyList(), CharacterEvents.between(paused, Physics.step(paused, empty, DT)), "a paused step is silent")
        assertEquals(listOf(RESUMED), CharacterEvents.between(paused, Physics.resume(paused)))

        // Pausing mid-jump and resuming is RESUMED only, never a second JUMPED.
        val midJump = stepUntil(listOf(Physics.jump(walking)), empty, maxSteps = 5) { false }.last()
        val pausedJump = Physics.pause(midJump)
        assertEquals(listOf(PAUSED), CharacterEvents.between(midJump, pausedJump))
        assertEquals(listOf(RESUMED), CharacterEvents.between(pausedJump, Physics.resume(pausedJump)))
        assertEquals(listOf(RESUMED), CharacterEvents.between(pausedJump.state, Physics.resume(pausedJump.state)))
    }

    @Test
    fun steppedUp() {
        val level = Level(listOf(book(Vec2(0.1f, 0f))))
        val idle = idleAt()
        val onto = stepUntil(listOf(idle, Physics.walkTo(idle, Vec2(0.1f, 0f))), level) { it.state.mode == Mode.IDLE }
        assertEquals(listOf(STARTED_WALKING, STEPPED_UP, ARRIVED), events(onto))
        // Rising during a jump is not a step up.
        val air = CharacterState(h = 0.01f, mode = Mode.JUMPING)
        assertEquals(emptyList(), CharacterEvents.between(air, air.copy(h = 0.02f)))
    }

    @Test
    fun unchangedStateEmitsNothing() {
        for (mode in Mode.entries) {
            val s = CharacterState(pos = Vec2(0.01f, 0.02f), h = 0.01f, target = Vec2(0.1f, 0f), mode = mode)
            assertEquals(emptyList(), CharacterEvents.between(s, s.copy()), "$mode")
            val b = PhysicsBody(s, verticalVelocity = 0.1f, blocked = true)
            assertEquals(emptyList(), CharacterEvents.between(b, b.copy()), "$mode, blocked")
        }
        // PLACED belongs to the host: no physics trace ever produces it. Frames as the host runs them: inputs, one step,
        // then the events since the last frame.
        val level = Level(listOf(cup(Vec2(0.1f, 0f)), book(Vec2(-0.1f, 0f))), boundary = Polygon.rect(Vec2.ZERO, 0.4f, 0.4f))
        var b = idleAt()
        val seen = HashSet<CharacterEvent>()
        repeat(2000) { i ->
            val reported = b
            if (i == 0) b = Physics.walkTo(b, Vec2(0.25f, 0f))
            if (i % 70 == 35) b = Physics.jump(b)
            if (i == 600) b = Physics.walkTo(b, Vec2(-0.1f, 0f))
            if (i == 1200) b = Physics.walkTo(b, Vec2(0.3f, 0f))
            b = Physics.step(b, level, DT)
            seen += CharacterEvents.between(reported, b)
        }
        assertTrue(CharacterEvent.PLACED !in seen, "$seen")
        assertTrue(seen.containsAll(listOf(JUMPED, LANDED, BLOCKED)), "$seen")
    }

    @Test
    fun blockedFiresOnceOnRisingEdge() {
        // Pinned against a 9 cm cup for 300 steps: one BLOCKED, not one per step.
        val level = Level(listOf(cup(Vec2.ZERO)))
        val start = Physics.walkTo(idleAt(Vec2(-0.15f, 0f)), Vec2(0.15f, 0f))
        val pinned = stepUntil(listOf(start), level, maxSteps = 400) { false }
        assertTrue(pinned.last().blocked)
        assertEquals(1, events(pinned).count { it == BLOCKED }, "${events(pinned)}")
        assertEquals(listOf(BLOCKED), CharacterEvents.between(pinned[pinned.indexOfFirst { it.blocked } - 1], pinned.first { it.blocked }))

        // The child taps behind the cup again (a new target): announced again, once, whether the host diffs per step
        // or "last reported" (before the tap) against "now" (after the next step).
        val tapped = Physics.walkTo(pinned.last(), Vec2(0.16f, 0f))
        val again = stepUntil(listOf(tapped), level, maxSteps = 100) { false }
        assertTrue(again.drop(1).all { it.blocked })
        assertEquals(listOf(BLOCKED), events(listOf(pinned.last()) + again))
        assertEquals(listOf(BLOCKED), CharacterEvents.between(pinned.last(), again[1]))
        // The same target re-sent (a host repeating the tap every frame) is not a new walk: silent.
        val resent = Physics.walkTo(pinned.last(), Vec2(0.15f, 0f))
        assertEquals(pinned.last(), resent)
        assertEquals(emptyList(), CharacterEvents.between(pinned.last(), Physics.step(resent, level, DT)))

        // The rule itself, on hand-made bodies: rising edges only; falling edges and plateaus are silent.
        val s = CharacterState(pos = Vec2(-0.07f, 0f), target = Vec2(0.15f, 0f), mode = Mode.WALKING)
        val pattern = listOf(false, true, true, false, false, true, true).map { PhysicsBody(s, blocked = it) }
        assertEquals(listOf(BLOCKED, BLOCKED), events(pattern))
        assertEquals(emptyList(), CharacterEvents.between(pattern[1], pattern[3]))
        val retargeted = PhysicsBody(s.copy(target = Vec2(0.15f, 0.01f)), blocked = true)
        assertEquals(listOf(BLOCKED), CharacterEvents.between(pattern[1], retargeted))
        // BLOCKED comes after the state's own events.
        val idle = PhysicsBody(s.copy(mode = Mode.IDLE))
        assertEquals(listOf(STARTED_WALKING, BLOCKED), CharacterEvents.between(idle, PhysicsBody(s, blocked = true)))
    }

    @Test
    fun trackingBlipDoesNotReannounceBlocked() {
        // Frames as the AR host runs them (GameLoop): not tracking -> pause; tracking -> resume, then one step whose dt
        // is 0 on the first frame after a pause; events are diffed against the last reported body.
        val level = Level(listOf(cup(Vec2.ZERO)))
        var body = Physics.walkTo(idleAt(Vec2(-0.15f, 0f)), Vec2(0.15f, 0f))
        var reported = body
        var lastMs: Long? = null
        val seen = ArrayList<CharacterEvent>()
        fun frame(nowMs: Long, tracking: Boolean) {
            if (!tracking) {
                body = Physics.pause(body)
                lastMs = null
            } else {
                body = Physics.resume(body)
                val dt = lastMs?.let { nowMs - it } ?: 0L
                lastMs = nowMs
                body = Physics.step(body, level, dt)
            }
            seen += CharacterEvents.between(reported, body)
            reported = body
        }
        var now = 0L
        repeat(200) { frame(now, tracking = true); now += DT } // pinned from step ~101
        assertTrue(body.blocked)
        assertEquals(listOf(BLOCKED), seen)
        repeat(3) { frame(now, tracking = false); now += DT } // tracking blip
        repeat(50) { frame(now, tracking = true); now += DT } // resume: a dt = 0 step, then normal steps, still pinned
        assertTrue(body.blocked)
        assertEquals(listOf(BLOCKED, PAUSED, RESUMED), seen, "the blip does not re-announce BLOCKED")
        // A dt = 0 step keeps the flag only while it still walks toward a target.
        val stopped = body.copy(state = body.state.copy(target = null, mode = Mode.IDLE))
        assertEquals(false, Physics.step(stopped, level, 0L).blocked)
    }

    @Test
    fun arrivedNotEmittedForIdleToIdle() {
        val armed = CharacterState(target = Vec2(0.1f, 0f), mode = Mode.IDLE)
        assertEquals(emptyList(), CharacterEvents.between(armed, armed.copy(target = null)))
        // A hand shoving an idle character is not an arrival either.
        val idle = CharacterState(pos = Vec2(0.05f, 0f))
        assertEquals(emptyList(), CharacterEvents.between(idle, idle.copy(pos = Vec2(0.07f, 0f))))
        // A paused walk resumed straight to IDLE (no target) is RESUMED only.
        val paused = CharacterState(target = null, mode = Mode.PAUSED)
        assertEquals(listOf(RESUMED), CharacterEvents.between(paused, paused.copy(mode = Mode.IDLE)))
    }
}
