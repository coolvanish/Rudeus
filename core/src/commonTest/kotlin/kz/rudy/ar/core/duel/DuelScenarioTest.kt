package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.DuelEvent.Clash
import kz.rudy.ar.core.duel.DuelEvent.DashStarted
import kz.rudy.ar.core.duel.DuelEvent.Hit
import kz.rudy.ar.core.duel.DuelEvent.RingOut
import kz.rudy.ar.core.duel.DuelEvent.WallSlam
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.Special
import kz.rudy.ar.core.physics.Physics
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Mirror across the v axis: (u, v) -> (-u, v). */
private fun m(p: Vec2) = Vec2(-p.u, p.v)

private fun swapIds(e: DuelEvent, x: String = "a", y: String = "b"): DuelEvent {
    fun s(id: String) = if (id == x) y else if (id == y) x else id
    return when (e) {
        is DashStarted -> DashStarted(s(e.id))
        is Hit -> e.copy(attacker = s(e.attacker), victim = s(e.victim))
        is Clash -> Clash(s(e.a), s(e.b))
        is WallSlam -> e.copy(id = s(e.id))
        is RingOut -> RingOut(s(e.id))
    }
}

/** Order-free form of one step's events (a clash names both fighters either way round). */
private fun bag(events: List<DuelEvent>): List<String> =
    events.map { if (it is Clash) Clash(minOf(it.a, it.b), maxOf(it.a, it.b)) else it }.map { it.toString() }.sorted()

/** A level symmetric under [m]: rect footprints are exactly mirrored in floating point. */
private val mirrorLevel = Level(
    objects = listOf(
        LevelObject("cup-l", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.2f, 0.1f), 0.06f, 0.06f), 0.09f),
        LevelObject("cup-r", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.2f, 0.1f), 0.06f, 0.06f), 0.09f),
        book("book-l", Vec2(-0.1f, -0.12f), 0.1f, 0.08f),
        book("book-r", Vec2(0.1f, -0.12f), 0.1f, 0.08f),
    ),
    boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f),
    spawn = Vec2(0f, -0.15f),
)

private fun mirrorId(id: String) = when {
    id.endsWith("-l") -> id.dropLast(2) + "-r"
    id.endsWith("-r") -> id.dropLast(2) + "-l"
    else -> id
}

class DuelScenarioTest {

    @Test
    fun thirtyAndSixtyHertzAgree() {
        // Table from u = -0.09 to 0.25; a cup just right of the middle, a book behind.
        val level = Level(
            objects = listOf(cup("cup-1", Vec2(0.12f, 0f)), book("book-1", Vec2(0f, 0.13f), 0.1f, 0.06f)),
            boundary = Polygon(listOf(Vec2(-0.09f, -0.2f), Vec2(0.25f, -0.2f), Vec2(0.25f, 0.2f), Vec2(-0.09f, 0.2f))),
            spawn = Vec2(0.1f, -0.12f),
        )
        val st = FighterStats(power = 8, speed = 4, guard = 3, agility = 5)
        val start = DuelState(
            fighter("a", Vec2(-0.05f, 0f), st, facing = Vec2(1f, 0f)),
            fighter("b", Vec2(0.03f, 0f), st, facing = Vec2(-1f, 0f)),
        )
        // The scripted exchange: a dashes b into the cup (hit, wall slam), walks, jumps; b recovers and dashes the
        // airborne a off the left edge (hit, ring-out); a respawns and walks again.
        val script = mapOf(
            0L to inputs("a" to Dash()),
            33L * 10 to inputs("a" to MoveTo(Vec2(-0.03f, 0.03f)), "b" to MoveTo(Vec2(0.2f, 0.15f))),
            33L * 20 to inputs("a" to Jump, "b" to Special),
            33L * 40 to inputs("b" to Dash(Vec2(-1f, 0f))),
            33L * 90 to inputs("a" to MoveTo(Vec2(0.15f, 0.1f)), "b" to MoveTo(Vec2(0f, 0f))),
        )
        val totalMs = 33L * 130
        val thirty = runDuel(start, level, totalMs, dtMs = 33L) { s -> script[s.timeMs] ?: emptyMap() }

        val states16 = arrayListOf(start)
        val events16 = ArrayList<DuelEvent>()
        var s = start
        var k = 0
        while (s.timeMs < totalMs) {
            val out = DuelPhysics.step(s, level, if (k++ % 2 == 0) 16L else 17L, script[s.timeMs] ?: emptyMap())
            s = out.state
            states16 += s
            events16 += out.events
        }
        assertEquals(thirty.states.size * 2 - 1, states16.size)

        val types = thirty.all.map { it::class.simpleName }.toSet()
        assertTrue(types.containsAll(listOf("DashStarted", "Hit", "WallSlam", "RingOut")), "the exchange covers the events: ${thirty.all}")
        assertEquals(thirty.all, events16, "same events in the same order")
        var worst = 0f
        for ((i, x) in thirty.states.withIndex()) {
            val y = states16[2 * i]
            assertEquals(x.timeMs, y.timeMs)
            for ((f, g) in listOf(x.a to y.a, x.b to y.b)) {
                val d = f.pos.distanceTo(g.pos)
                worst = maxOf(worst, d, abs(f.body.state.h - g.body.state.h))
                assertTrue(d <= 0.001f, "${f.id} differs by $d m at ${x.timeMs} ms")
                assertEquals(f.body.state.mode, g.body.state.mode, "${f.id} mode at ${x.timeMs} ms")
            }
        }
        println("thirtyAndSixtyHertzAgree: worst position difference $worst m over ${thirty.all.size} events")

        // Stronger than the 1 mm the game needs: sub-steps sit on a grid of timeMs, so any split of each 33 ms window
        // (here random frames of 1..17 ms) runs the same sub-steps and lands on exactly the same state.
        val rnd = Random(7)
        var r = start
        val eventsRandom = ArrayList<DuelEvent>()
        for ((i, x) in thirty.states.withIndex()) {
            assertEquals(x, r, "random frames differ at ${x.timeMs} ms")
            if (i == thirty.states.size - 1) break
            val windowEnd = r.timeMs + 33L
            var first = true
            while (r.timeMs < windowEnd) {
                val out = DuelPhysics.step(r, level, minOf(1L + rnd.nextLong(17L), windowEnd - r.timeMs), if (first) script[r.timeMs] ?: emptyMap() else emptyMap())
                first = false
                r = out.state
                eventsRandom += out.events
            }
        }
        assertEquals(thirty.all, eventsRandom)
    }

    @Test
    fun mirroredFightersGetMirroredOutcomes() {
        val st = FighterStats(power = 6, speed = 4, guard = 5, agility = 5)
        val start = DuelState(
            fighter("a", Vec2(-0.12f, 0f), st, facing = Vec2(1f, 0f)),
            fighter("b", Vec2(0.12f, 0f), st, facing = Vec2(-1f, 0f)),
        )
        // Mirrored inputs for a and b: walk in, clash, walk onto the books, jump, dash at the cups, walk off the
        // edges (both ring out and respawn on the same spot), clash again.
        fun both(f: (sign: Float) -> DuelInput) = inputs("a" to f(-1f), "b" to f(1f))
        val script = mapOf(
            0L to both { s -> MoveTo(Vec2(s * 0.03f, 0.02f)) },
            400L to both { Dash() },
            1504L to both { s -> MoveTo(Vec2(s * 0.1f, -0.12f)) },
            2208L to both { Jump },
            3008L to both { s -> Dash(Vec2(s, 0.4f)) },
            3808L to both { s -> MoveTo(Vec2(s * 0.4f, 0f)) },
            6000L to both { s -> Dash(Vec2(-s, 0f)) },
        )
        val t = runDuel(start, mirrorLevel, 7000L) { s -> script[s.timeMs] ?: emptyMap() }
        val kinds = t.all.map { it::class.simpleName }.toSet()
        assertTrue(kinds.containsAll(listOf("DashStarted", "Clash", "RingOut")), "the script covers the events: ${t.all}")
        for ((i, s) in t.states.withIndex()) {
            val (a, b) = s.a to s.b
            assertTrue(m(a.pos).distanceTo(b.pos) < 1e-5f, "step $i (${s.timeMs} ms): a ${a.pos} vs b ${b.pos}")
            assertEquals(a.body.state.h, b.body.state.h, 1e-6f, "h at ${s.timeMs} ms")
            assertEquals(a.body.state.mode, b.body.state.mode, "mode at ${s.timeMs} ms")
            assertTrue(m(a.knock).distanceTo(b.knock) < 1e-5f, "knock at ${s.timeMs} ms: ${a.knock} vs ${b.knock}")
            assertEquals(listOf(a.stunMs, a.dashMs, a.dashCooldownMs), listOf(b.stunMs, b.dashMs, b.dashCooldownMs), "timers at ${s.timeMs} ms")
            assertEquals(bag(t.events[i]), bag(t.events[i].map { swapIds(it) }), "events of step $i are symmetric")
        }

        // One-sided play mirrored as a whole: a dashes b into a cup, b walks off; then everything mirrored.
        val strong = FighterStats(power = 9, speed = 3, guard = 4, agility = 4)
        val quick = FighterStats(power = 3, speed = 9, guard = 4, agility = 4)
        val lop = DuelState(fighter("a", Vec2(0.04f, 0.1f), strong), fighter("b", Vec2(0.04f + NEAR, 0.1f), quick))
        val lopScript = mapOf(
            0L to inputs("a" to Dash()),
            1504L to inputs("b" to MoveTo(Vec2(0.4f, -0.1f)), "a" to MoveTo(Vec2(-0.05f, -0.12f))),
            2400L to inputs("a" to Jump),
        )
        fun mirrorInputs(i: Map<String, DuelInput>) = i.mapValues { (_, v) ->
            when (v) {
                is MoveTo -> MoveTo(m(v.target))
                is Dash -> Dash(v.dir?.let(::m))
                else -> v
            }
        }
        fun mirror(f: DuelFighter) = f.copy(body = f.body.copy(state = f.body.state.copy(pos = m(f.pos), facing = m(f.body.state.facing))))
        val mirrored = DuelState(mirror(lop.a), mirror(lop.b))
        val x = runDuel(lop, mirrorLevel, 6000L) { s -> lopScript[s.timeMs] ?: emptyMap() }
        val y = runDuel(mirrored, mirrorLevel, 6000L) { s -> lopScript[s.timeMs]?.let(::mirrorInputs) ?: emptyMap() }
        assertTrue(x.all.any { it is WallSlam } && x.all.any { it is RingOut }, "the one-sided play slams and rings out: ${x.all}")
        for ((i, s) in x.states.withIndex()) {
            val r = y.states[i]
            for ((f, g) in listOf(s.a to r.a, s.b to r.b)) {
                assertTrue(m(f.pos).distanceTo(g.pos) < 1e-5f, "${f.id} at ${s.timeMs} ms: ${f.pos} vs mirrored ${g.pos}")
                assertEquals(f.body.state.mode, g.body.state.mode)
            }
            val mirroredEvents = x.events[i].map { if (it is WallSlam) it.copy(objectId = mirrorId(it.objectId)) else it }
            assertEquals(mirroredEvents, y.events[i], "step $i")
        }

        // The slot a fighter sits in does not matter either: swap a and b in the state, same per-fighter result.
        val swapped = runDuel(DuelState(lop.b, lop.a), mirrorLevel, 6000L) { s -> lopScript[s.timeMs] ?: emptyMap() }
        for ((i, s) in x.states.withIndex()) {
            val r = swapped.states[i]
            assertEquals(s.a, r.b, "a at ${s.timeMs} ms")
            assertEquals(s.b, r.a, "b at ${s.timeMs} ms")
            assertEquals(bag(x.events[i]), bag(swapped.events[i]), "events of step $i")
        }
    }

    @Test
    fun fuzzNeverProducesNaNOrOverlapOrEscapes() {
        val level = Level(
            objects = listOf(
                cup("cup", Vec2(0.15f, 0.08f)),
                LevelObject("bottle", "bottle", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.2f, -0.12f), 0.07f, 0.07f), 0.2f),
                book("book", Vec2(-0.12f, 0.1f), 0.14f, 0.1f, 0.03f),
                LevelObject("lid", "lid", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.05f, -0.15f), 0.08f, 0.04f), 0.02f),
                LevelObject("box", "box", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.26f, -0.1f), 0.08f, 0.08f), 0.06f),
            ),
            boundary = Polygon(listOf(Vec2(-0.4f, -0.25f), Vec2(0.35f, -0.28f), Vec2(0.42f, 0.2f), Vec2(-0.1f, 0.3f), Vec2(-0.42f, 0.15f))),
            spawn = Vec2(0f, 0.05f),
        )
        val steps = 10_000
        val counts = HashMap<String, Int>()
        val first = fuzz(level, seed = 42, steps = steps) { prev, out, _ ->
            val s = out.state
            for (e in out.events) counts[e::class.simpleName!!] = (counts[e::class.simpleName!!] ?: 0) + 1
            for (f in listOf(s.a, s.b)) {
                val st = f.body.state
                val before = prev[f.id]!!
                val all = listOf(st.pos.u, st.pos.v, st.h, f.knock.u, f.knock.v, f.dashDir.u, f.dashDir.v, f.body.verticalVelocity)
                assertTrue(all.all { it.isFinite() }, "non-finite fighter ${f.id} at ${s.timeMs} ms: $f")
                assertTrue(f.stunMs >= 0 && f.dashMs >= 0 && f.dashCooldownMs >= 0, "negative timer: $f")
                val grounded = st.mode == Mode.IDLE || st.mode == Mode.WALKING
                // Grounded: exactly outside every footprint that blocks at its feet. Airborne: Physics resolves against
                // the feet height before the vertical move, so allow one sub-step of vertical travel.
                val feet = if (grounded) st.h else st.h + 0.01f
                val inside = level.objects.firstOrNull { Physics.blocks(it, feet) && it.footprint.contains(st.pos) }
                assertEquals(null, inside?.id, "${f.id} inside ${inside?.id} at ${s.timeMs} ms: $st")
                if (grounded) assertTrue(!Physics.isOverCliff(level, st.pos), "${f.id} stands past the edge at ${s.timeMs} ms: $st")
                if (st.mode == Mode.PAUSED) assertEquals(before, f, "a paused fighter is frozen")
                // Every fall over the edge has exactly one RingOut, on the step it starts.
                val wasOut = isOut(before, level)
                val nowOut = isOut(f, level)
                val rang = out.events.count { it == RingOut(f.id) }
                assertEquals(if (!wasOut && nowOut) 1 else 0, rang, "${f.id} ring-out at ${s.timeMs} ms: $before -> $f")
            }
            val (a, b) = s.a.body.state to s.b.body.state
            val ground = setOf(Mode.IDLE, Mode.WALKING)
            if (a.mode in ground && b.mode in ground) {
                val dist = a.pos.distanceTo(b.pos)
                assertTrue(dist >= a.radiusM + b.radiusM - 1e-4f, "fighters overlap by ${a.radiusM + b.radiusM - dist} at ${s.timeMs} ms")
            }
        }
        println("fuzz: $steps steps, events $counts")
        for (kind in listOf("DashStarted", "Hit", "Clash", "WallSlam", "RingOut")) {
            assertTrue((counts[kind] ?: 0) > 0, "the fuzz never produced $kind: $counts")
        }

        // Deterministic: the same seed replays exactly. Timed without the checks (best of two replays: the JIT is still
        // warming up during the first).
        var best = Double.MAX_VALUE
        repeat(2) {
            val mark = TimeSource.Monotonic.markNow()
            val again = fuzz(level, seed = 42, steps = steps) { _, _, _ -> }
            best = minOf(best, mark.elapsedNow().inWholeMicroseconds / 1e6)
            assertEquals(first, again)
        }
        println("fuzz: ${(steps / best).toLong()} steps/s (${first.timeMs} ms of duel in ${best}s)")
    }

    /** Committed to a fall off the table (written out here, not taken from DuelPhysics.isOut). */
    private fun isOut(f: DuelFighter, level: Level): Boolean {
        val st = f.body.state
        val over = Physics.isOverCliff(level, st.pos)
        return st.mode == Mode.RESPAWNING || (st.mode == Mode.FALLING && over) || (st.mode == Mode.JUMPING && st.h < 0f && over)
    }

    /** Random play from a fixed seed: frames of 16..50 ms, seeking and random inputs, host pauses. Returns the end state. */
    private fun fuzz(
        level: Level,
        seed: Int,
        steps: Int,
        check: (prev: Map<String, DuelFighter>, out: DuelStep, inputs: Map<String, DuelInput>) -> Unit,
    ): DuelState {
        val rnd = Random(seed)
        fun randomStats(): FighterStats {
            val s = IntArray(4) { FighterStats.MIN }
            repeat(FighterStats.BUDGET - 4 * FighterStats.MIN) {
                var i = rnd.nextInt(4)
                while (s[i] >= FighterStats.MAX) i = (i + 1) % 4
                s[i]++
            }
            return FighterStats(s[0], s[1], s[2], s[3])
        }
        fun ability() = Ability.entries[rnd.nextInt(Ability.entries.size)]
        var state = DuelState(
            fighter("a", Vec2(-0.1f, -0.05f), randomStats(), ability = ability()),
            fighter("b", Vec2(0.1f, -0.02f), randomStats(), ability = ability(), facing = Vec2(-1f, 0f)),
        )
        var paused = false
        // Each fighter follows a plan for a while: 0 chase the other (walk at it, dash when close), 1 wander to a spot
        // (a quarter of them off the table), 2 stand. On top: random jumps, dashes and specials.
        val plan = intArrayOf(0, 1)
        val wander = arrayOf(Vec2(0.5f, 0f), Vec2(-0.5f, 0.1f))
        repeat(steps) {
            if (!paused && rnd.nextInt(1000) < 3) {
                paused = true
                state = state.copy(a = state.a.copy(body = Physics.pause(state.a.body)), b = state.b.copy(body = Physics.pause(state.b.body)))
            } else if (paused && rnd.nextInt(100) < 5) {
                paused = false
                state = state.copy(a = state.a.copy(body = Physics.resume(state.a.body)), b = state.b.copy(body = Physics.resume(state.b.body)))
            }
            val inputs = HashMap<String, DuelInput>()
            for ((k, pair) in listOf(state.a to state.b, state.b to state.a).withIndex()) {
                val (me, other) = pair
                if (rnd.nextInt(100) < 3) {
                    plan[k] = rnd.nextInt(3)
                    val far = rnd.nextInt(4) == 0
                    val reach = if (far) 0.6f else 0.3f
                    wander[k] = Vec2((rnd.nextFloat() * 2f - 1f) * reach, (rnd.nextFloat() * 2f - 1f) * reach * 0.7f)
                }
                val toOther = other.pos - me.pos
                val roll = rnd.nextInt(100)
                val input: DuelInput? = when {
                    roll < 3 -> Jump
                    roll < 4 -> Special
                    roll < 5 -> Dash(if (rnd.nextBoolean()) null else Vec2(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f))
                    plan[k] == 0 && toOther.length < 0.12f && DuelPhysics.canDash(me, level) -> Dash(toOther)
                    plan[k] == 0 && roll < 40 -> MoveTo(other.pos)
                    plan[k] == 1 && roll < 15 -> MoveTo(wander[k])
                    else -> null
                }
                if (input != null) inputs[me.id] = input
            }
            val prev = mapOf(state.a.id to state.a, state.b.id to state.b)
            val dt = rnd.nextLong(16L, 51L)
            val out = DuelPhysics.step(state, level, dt, inputs)
            // Simulated time only: the clock moves by exactly the dt it is given (T45's match clock runs on it).
            assertEquals(state.timeMs + dt, out.state.timeMs)
            check(prev, out, inputs)
            state = out.state
        }
        return state
    }
}
