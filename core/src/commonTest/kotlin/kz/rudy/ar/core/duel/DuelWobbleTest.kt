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
import kz.rudy.ar.core.duel.DuelEvent.Hit
import kz.rudy.ar.core.duel.DuelEvent.RingOut
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.duel.DuelInput.Jump
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.duel.DuelInput.Special
import kz.rudy.ar.core.physics.Physics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val P = DuelParams.DEFAULT

/** T42's fuzz table: a cup, a bottle, a book, a low lid, a tall box, an irregular edge. */
private val TABLE = Level(
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

/** T48's wobble: every hit taken in a round makes the next one push farther, capped, gone when a round starts. */
class DuelWobbleTest {

    /**
     * [n] plain dashes of "a" (origin, facing +u) into "b" standing [NEAR] ahead, each from a fresh start that keeps only
     * b's wobble: (the hit's speed, b's slide, b's wobble after it) per hit.
     */
    private fun hits(n: Int, params: DuelParams = P, first: Float = 0f): List<Triple<Float, Float, Float>> {
        var wobble = first
        return (1..n).map {
            val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(NEAR, 0f)).copy(wobble = wobble))
            val t = runDuel(start, OPEN, 3000L, params = params, script = atStart("a" to Dash()))
            val hit = t.all.filterIsInstance<Hit>().single()
            wobble = t.last.b.wobble
            Triple(hit.speedMps, t.last.b.pos.u - NEAR, wobble)
        }
    }

    @Test
    fun wobbleZeroIsTodaysPhysics() {
        // No wobble per hit: every hit is the plain knockback, nobody gains wobble, and the other wobble numbers do
        // nothing, so the physics is the one before T48 (every T42 / T45 test passed with it so, commit 404017c).
        val off = P.copy(wobblePerHit = 0f)
        val plain = P.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED)
        for ((speed, _, wobble) in hits(5, off)) {
            assertEquals(plain, speed)
            assertEquals(0f, wobble)
        }
        assertEquals(1, hits(5, off).map { it.second }.distinct().size, "every hit slides the same")
        // A random fight on the fuzz table, every ability pair: the same states and events whatever the other wobble
        // numbers are, every Hit exactly the stat formula (x the ram / counter / stomp / high-ground factor).
        for ((i, abilities) in Ability.entries.flatMap { x -> Ability.entries.map { y -> x to y } }.withIndex()) {
            val runs = listOf(off, off.copy(wobbleKnockScale = 1f, wobbleMax = 9f), off.copy(wobbleKnockScale = 0f, wobbleMax = 0f, wobbleDecayPerS = 3f))
                .map { params -> fuzz(seed = 300 + i, steps = 800, abilities = abilities, params = params) }
            assertEquals(runs[0], runs[1], "$abilities")
            assertEquals(runs[0], runs[2], "$abilities")
            val (end, events) = runs[0]
            assertEquals(0f to 0f, end.a.wobble to end.b.wobble)
            for (h in events.filterIsInstance<Hit>()) {
                // In the physics' own order: (stat formula, x high ground) x the dash kind; a stomp x stompScale.
                val attacker = end[h.attacker]!!.stats
                val victim = end[h.victim]!!.stats
                val plainHits = listOf(false, true).flatMap { above ->
                    val v = P.knockSpeedMpsFor(attacker, victim, above)
                    listOf(v * 1f, v * P.ramKnockScale, v * P.counterKnockScale)
                } + P.knockSpeedMpsFor(attacker, victim) * P.stompScale
                assertTrue(h.speedMps in plainHits, "$abilities: $h is not a plain hit (one of $plainHits)")
            }
        }
    }

    @Test
    fun wobbleGrowsKnockbackAndIsCapped() {
        // Defaults: + 1 per hit taken, x (1 + 0.8 w), at most 4: hit n knocks 1, 1.8, 2.6, 3.4, then 4.2 x (T57; T48's 0.7:
        // 1, 1.7, 2.4, 3.1, then 3.8 x).
        val plain = P.knockSpeedMpsFor(FighterStats.BALANCED, FighterStats.BALANCED)
        val series = hits(7)
        val factors = listOf(1f, 1.8f, 2.6f, 3.4f, 4.2f, 4.2f, 4.2f)
        assertEquals(factors, (0 until 7).map { P.wobbleFactor(it.toFloat()) }.map { (it * 10f).let { x -> kotlin.math.round(x) / 10f } })
        for ((k, hit) in series.withIndex()) {
            assertEquals(plain * factors[k], hit.first, 1e-6f, "hit ${k + 1}")
            assertEquals(minOf(k + 1f, P.wobbleMax), hit.third, "wobble after hit ${k + 1}")
        }
        // The slides grow with it, then stay.
        val slides = series.map { it.second }
        for (k in 1 until 5) assertTrue(slides[k] > slides[k - 1] * 1.15f, "slide ${k + 1}: $slides")
        assertEquals(slides[4], slides[6], 1e-4f)
        assertTrue(slides[0] in 0.040f..0.050f && slides[4] > 3f * slides[0], "$slides")
        // A fighter made with more than the cap is knocked as if at the cap and keeps no more than it.
        val over = hits(1, first = 99f).single()
        assertEquals(plain * P.wobbleFactor(P.wobbleMax), over.first, 1e-6f)
        assertEquals(P.wobbleMax, over.third)

        // Each side of a clash counts as a hit taken, and each is knocked by its own wobble.
        val left = fighter("a", Vec2(-0.04f, 0f), facing = Vec2(1f, 0f)).copy(wobble = 1f)
        val right = fighter("b", Vec2(0.04f, 0f), facing = Vec2(-1f, 0f)).copy(wobble = 3f)
        // (8 ms frames: the state right after the clash's sub-step, before any decay.)
        val clash = runDuel(DuelState(left, right), OPEN, 200L, dtMs = 8L, script = atStart("a" to Dash(), "b" to Dash()))
        val k = clash.indexOf { it is Clash }
        assertEquals(2f to 4f, clash.states[k].a.wobble to clash.states[k].b.wobble)
        assertEquals(plain * P.wobbleFactor(1f), clash.states[k].a.knock.length, 1e-6f)
        assertEquals(plain * P.wobbleFactor(3f), clash.states[k].b.knock.length, 1e-6f)

        // A ram, a counter and a stomp count too, each on top of its own factor.
        val ram = runDuel(
            DuelState(gifted("a", Vec2.ZERO, Ability.ROLL), fighter("b", Vec2(NEAR, 0f)).copy(wobble = 2f)), OPEN, 600L,
            script = atStart("a" to Special),
        )
        assertEquals(plain * P.ramKnockScale * P.wobbleFactor(2f), ram.all.filterIsInstance<Hit>().single().speedMps, 1e-6f)
        assertEquals(3f, ram.last.b.wobble)
        val stomp = runDuel(
            DuelState(gifted("a", Vec2.ZERO, Ability.JUMP), fighter("b", Vec2(2 * R, 0f)).copy(wobble = 1f)), OPEN, 1500L,
            script = atStart("a" to Special),
        )
        assertEquals(plain * P.stompScale * P.wobbleFactor(1f), stomp.all.filterIsInstance<Hit>().single().speedMps, 1e-6f)
        assertEquals(2f, stomp.last.b.wobble)
        val counter = runDuel(
            DuelState(fighter("a", Vec2.ZERO).copy(wobble = 1f), gifted("b", Vec2(NEAR, 0f), Ability.HIDE, facing = Vec2(-1f, 0f)).copy(counterMs = 800L)),
            OPEN, 600L, script = atStart("b" to Dash()),
        )
        assertEquals(plain * P.counterKnockScale * P.wobbleFactor(1f), counter.all.filterIsInstance<Hit>().single().speedMps, 1e-6f)
        // The attacker gains nothing by hitting.
        assertEquals(0f, ram.last.a.wobble)

        // An optional slow fade: 0.5 per second takes 2 down to 1 in 2 s of fight, never below 0, and a paused
        // fighter keeps its wobble.
        val fade = P.copy(wobbleDecayPerS = 0.5f)
        val standing = DuelState(fighter("a", Vec2.ZERO).copy(wobble = 2f), fighter("b", Vec2(0.3f, 0f)).copy(wobble = 0.3f))
        val faded = runDuel(standing, OPEN, 2000L, dtMs = 8L, params = fade).last
        assertEquals(1f, faded.a.wobble, 1e-4f)
        assertEquals(0f, faded.b.wobble)
        val paused = standing.copy(a = standing.a.copy(body = Physics.pause(standing.a.body)), b = standing.b.copy(body = Physics.pause(standing.b.body)))
        assertEquals(2f, runDuel(paused, OPEN, 2000L, params = fade).last.a.wobble)
        // And the numbers are checked by name, the largest factor too (a sanity bound on a --params file).
        for ((name, make) in listOf<Pair<String, () -> DuelParams>>(
            "wobblePerHit" to { P.copy(wobblePerHit = -1f) },
            "wobbleKnockScale" to { P.copy(wobbleKnockScale = Float.NaN) },
            "wobbleMax" to { P.copy(wobbleMax = -0.5f) },
            "wobbleDecayPerS" to { P.copy(wobbleDecayPerS = Float.POSITIVE_INFINITY) },
            "1 + wobbleKnockScale * wobbleMax" to { P.copy(wobbleKnockScale = 3f, wobbleMax = 4f) },
            "1 + wobbleKnockScale * wobbleMax" to { P.copy(wobbleKnockScale = 1f, wobbleMax = 9.5f) },
        )) {
            val e = assertFailsWith<IllegalArgumentException>(name) { make() }
            assertTrue(name in (e.message ?: ""), "${e.message}")
        }
        assertEquals(DuelParams.MAX_WOBBLE_FACTOR, 1f + P.copy(wobbleKnockScale = 1f, wobbleMax = 9f).let { it.wobbleKnockScale * it.wobbleMax })
    }

    @Test
    fun aWobblyHitNeverGoesThroughAThinWall() {
        // The review's case, the hardest hits the defaults can give: a 10/1/1/8 attacker on a 3 cm book hits an 8/1/1/10
        // victim at the wobble cap (from above, x 1.25, x 3.8) standing on the table against a 10 cm wall. A HIDE counter
        // (x 2.0) is 4.89 m/s, 3.8 cm in the first sub-step, more than a radius; before the sweep the victim came out
        // on the far side of a wall 0.5, 1.0 or 1.5 cm thick. Now, for a plain dash, a ram and a counter and every
        // thickness: the victim stays on its side, slammed into the wall.
        val victimAt = Vec2(-0.03f + NEAR, 0f)
        val face = victimAt.u + R // the wall's near face: the victim's circle touches it
        val book = book("book", Vec2(-0.06f, 0f), 0.1f, 0.1f, 0.03f)
        val strong = FighterStats(10, 1, 1, 8)
        val weak = FighterStats(8, 1, 1, 10)
        val kinds = listOf(Triple("dash", Ability.JUMP, 1f), Triple("ram", Ability.ROLL, P.ramKnockScale), Triple("counter", Ability.HIDE, P.counterKnockScale))
        val ends = ArrayList<String>()
        for (thickness in listOf(0.005f, 0.01f, 0.015f, 0.02f)) {
            val wall = LevelObject("wall", "wall", ObjectKind.OBSTACLE, Polygon.rect(Vec2(face + thickness / 2f, 0f), thickness, 0.3f), 0.1f)
            val level = Level(listOf(book, wall))
            for ((kind, ability, scale) in kinds) {
                val attacker = fighter("a", Vec2(-0.03f, 0f), strong, h = 0.03f, ability = ability)
                    .copy(ability = ability, counterMs = if (ability == Ability.HIDE) 800L else 0L)
                val victim = fighter("b", victimAt, weak).copy(wobble = P.wobbleMax)
                val input = if (ability == Ability.ROLL) Special else Dash()
                val t = runDuel(DuelState(attacker, victim), level, 1500L, script = atStart("a" to input))
                val what = "$kind into a ${thickness * 1000} mm wall"
                val hit = t.all.filterIsInstance<Hit>().single()
                assertTrue(hit.fromAbove, what)
                assertEquals(P.knockSpeedMpsFor(strong, weak, fromAbove = true) * scale * P.wobbleFactor(P.wobbleMax), hit.speedMps, 1e-5f, what)
                assertEquals(listOf("wall"), t.all.filterIsInstance<DuelEvent.WallSlam>().filter { it.id == "b" }.map { it.objectId }, "$what: ${t.all}")
                for (s in t.states) {
                    assertTrue(s.b.pos.u + R <= face + 1e-3f, "$what: the victim got into or past the wall at ${s.timeMs} ms: ${s.b.pos}")
                }
                ends += "$what ends at u = ${t.last.b.pos.u}"
            }
        }
        // The counter's first sub-step is three pieces of at most half a radius.
        assertEquals(3, DuelPhysics.sweepPieces(4.89f * 0.0078247f, R))
        assertEquals(1, DuelPhysics.sweepPieces(DuelPhysics.SWEEP_PIECE_RADII * R, R))
        println("aWobblyHitNeverGoesThroughAThinWall: ${ends.joinToString("; ")}")
    }

    @Test
    fun wobbleClearsWhenARoundStarts() {
        // A 40 x 40 cm table, 1 s rounds (the clock ends them): both fighters made wobbly in the fight start the next
        // round steady, and the match starts steady.
        val square = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.4f, 0.4f))
        val rules = DuelRules(roundMs = 1000L)
        val red = Contestant("red", FighterStats.BALANCED, Ability.JUMP)
        val blue = Contestant("blue", FighterStats(4, 5, 4, 7), Ability.HIDE)
        val start = DuelMatch.start(red, blue, square, Vec2(-0.06f, 0f) to Vec2(0.06f, 0f), rules)
        assertEquals(0f to 0f, start.duel.a.wobble to start.duel.b.wobble)
        var s = start
        while (s.phase == MatchPhase.COUNTDOWN) s = DuelMatch.step(s, square, 16L, rules = rules).state
        s = s.copy(duel = s.duel.copy(a = s.duel.a.copy(wobble = 3f), b = s.duel.b.copy(wobble = 1.5f)))
        val seen = ArrayList<DuelMatchState>()
        while (s.round == 1 || s.phase != MatchPhase.FIGHT) {
            s = DuelMatch.step(s, square, 16L, rules = rules).state
            seen += s
        }
        // Kept through the fight and the point (the fall), gone from the rebuild on the marks on.
        assertTrue(seen.filter { it.round == 1 }.all { it.duel.a.wobble == 3f && it.duel.b.wobble == 1.5f })
        val next = seen.filter { it.round == 2 }
        assertTrue(next.isNotEmpty() && next.all { it.duel.a.wobble == 0f && it.duel.b.wobble == 0f })
        assertEquals(MatchPhase.FIGHT, s.phase)
    }

    @Test
    fun hardHitsAtAFenceNeverCrossIt() {
        // 400 seeded hits into a fence 5 mm thick: the victim on a random side of it, up to 1 cm off touching, the
        // attacker behind it at a random angle up to 60° off square, a random kind (dash, ram, counter), random stats,
        // a random wobble, from the table or from a 3 cm book, with the largest wobble factor DuelParams allows (10 x).
        // The victim's circle never gets into the fence, it never ends up on the other side, and a hit pressing into it
        // fast enough is a WallSlam.
        val params = P.copy(wobbleKnockScale = 1f, wobbleMax = 9f)
        val rnd = Random(48)
        var slams = 0
        var fastest = 0f
        repeat(400) { trial ->
            val side = if (rnd.nextBoolean()) 1f else -1f
            val fence = LevelObject("fence", "fence", ObjectKind.OBSTACLE, Polygon.rect(Vec2.ZERO, 0.005f, 0.4f), 0.1f)
            val victimAt = Vec2(side * (0.0025f + R + rnd.nextFloat() * 0.01f), (rnd.nextFloat() - 0.5f) * 0.1f)
            val angle = (rnd.nextFloat() * 2f - 1f) * (kotlin.math.PI.toFloat() / 3f)
            val toFence = Vec2(-side * kotlin.math.cos(angle), kotlin.math.sin(angle))
            val attackerAt = victimAt - toFence * NEAR
            val onBook = rnd.nextBoolean()
            val book = book("book", attackerAt - toFence * 0.02f, 0.06f, 0.06f, 0.03f)
            val level = Level(if (onBook) listOf(fence, book) else listOf(fence))
            val kind = rnd.nextInt(3)
            val ability = listOf(Ability.JUMP, Ability.ROLL, Ability.HIDE)[kind]
            val stats = FighterStats(10 - rnd.nextInt(3), 1, 1 + rnd.nextInt(2), 8)
            val attacker = fighter("a", attackerAt, stats, facing = toFence, h = if (onBook) 0.03f else 0f, ability = ability)
                .copy(ability = ability, counterMs = if (ability == Ability.HIDE) 800L else 0L)
            val victim = fighter("b", victimAt, FighterStats(8, 1, 1, 10)).copy(wobble = rnd.nextFloat() * params.wobbleMax)
            val input = if (ability == Ability.ROLL) Special else Dash(toFence)
            val t = runDuel(DuelState(attacker, victim), level, 400L, params = params, script = atStart("a" to input))
            t.all.filterIsInstance<Hit>().forEach { fastest = maxOf(fastest, it.speedMps) }
            slams += t.all.count { it is DuelEvent.WallSlam && it.id == "b" }
            for (s in t.states) {
                val u = s.b.pos.u
                assertTrue(u * side > 0f && kotlin.math.abs(u) >= 0.0025f + R - 1e-3f, "trial $trial: the victim crossed or entered the fence at ${s.timeMs} ms: ${s.b.pos} (started ${victimAt})")
            }
        }
        println("hardHitsAtAFenceNeverCrossIt: 400 hits, fastest ${fastest} m/s (${fastest * 0.0078247f * 100f} cm in a sub-step), $slams wall slams")
        assertTrue(fastest * 0.0078247f > 2f * R, "the storm reached more than two radii a sub-step: $fastest")
        assertTrue(slams > 200, "$slams")
    }

    @Test
    fun fuzzWithWobbleKeepsTheInvariants() {
        // T42's fuzz table plus a fence 5 mm thick and 10 cm tall, and the strongest wobble DuelParams allows (+3 a hit,
        // x (1 + w), up to 10 x the knockback, fading 0.25 per s): every ability pair, random play. Wobble stays within
        // 0..max, nobody ends a step inside what blocks it or overlapping the other on the ground, nobody's path over a
        // step crosses what blocks it, and every exit from the table is a ring-out. (Random play seldom lands the
        // hardest hit square on the fence: this passes without the sweep too. The tunnelling itself is pinned by
        // aWobblyHitNeverGoesThroughAThinWall and hardHitsAtAFenceNeverCrossIt.)
        val strong = P.copy(wobblePerHit = 3f, wobbleKnockScale = 1f, wobbleMax = 9f, wobbleDecayPerS = 0.25f)
        // (At least a fighter's diameter from every other blocking object: a gap narrower than that, at u = 0.06 next
        // to the cup, has no free spot at all, and a FLY gliding down into it from the box stayed inside the cup.)
        val fence = LevelObject("fence", "fence", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0f, 0.17f), 0.005f, 0.16f), 0.1f)
        val level = TABLE.copy(objects = TABLE.objects + fence)
        val counts = HashMap<String, Int>()
        var top = 0f
        var checked = 0
        for ((i, abilities) in Ability.entries.flatMap { x -> Ability.entries.map { y -> x to y } }.withIndex()) {
            fuzz(seed = 700 + i, steps = 1500, abilities = abilities, params = strong, level = level) { prev, out ->
                val s = out.state
                for (e in out.events) {
                    counts[e::class.simpleName!!] = (counts[e::class.simpleName!!] ?: 0) + 1
                    if (e is DuelEvent.WallSlam) counts["WallSlam ${e.objectId}"] = (counts["WallSlam ${e.objectId}"] ?: 0) + 1
                }
                for (f in listOf(s.a, s.b)) {
                    val st = f.body.state
                    val before = prev[f.id]!!
                    top = maxOf(top, f.wobble)
                    assertTrue(f.wobble in 0f..strong.wobbleMax, "wobble ${f.wobble} at ${s.timeMs} ms")
                    assertTrue(listOf(st.pos.u, st.pos.v, st.h, f.knock.u, f.knock.v, f.wobble).all { it.isFinite() }, "non-finite $f")
                    val inside = level.objects.firstOrNull {
                        Physics.blocks(it, st.h) && (it.footprint.contains(st.pos) || distanceToPolygon(st.pos, it.footprint) < st.radiusM - 0.001f)
                    }
                    assertEquals(null, inside?.id, "${f.id} overlaps ${inside?.id} at ${s.timeMs} ms: $f")
                    // The straight path over the step crosses nothing that blocks at both its ends' feet heights (a
                    // fighter out of the level, falling or respawning, is skipped: Physics moves it, not the duel).
                    val b0 = before.body.state
                    if (!DuelPhysics.isOut(before, level) && !DuelPhysics.isOut(f, level) && st.mode != Mode.PAUSED) {
                        checked++
                        val crossed = level.objects.firstOrNull {
                            it.kind != ObjectKind.CLIFF && Physics.blocks(it, b0.h) && Physics.blocks(it, st.h) && crosses(b0.pos, st.pos, it.footprint)
                        }
                        assertEquals(null, crossed?.id, "${f.id} went through ${crossed?.id} at ${s.timeMs} ms: ${b0.pos} -> ${st.pos}")
                    }
                    val boundary = level.boundary!!
                    val rang = out.events.of<RingOut>(f.id)
                    if (boundary.contains(before.pos) && !boundary.contains(st.pos)) assertEquals(1, rang.size, "${f.id} left without a RingOut at ${s.timeMs} ms")
                    if (st.mode == Mode.PAUSED) assertEquals(before, f, "a paused fighter is frozen")
                }
                val (a, b) = s.a.body.state to s.b.body.state
                val ground = setOf(Mode.IDLE, Mode.WALKING)
                if (a.mode in ground && b.mode in ground) assertTrue(a.pos.distanceTo(b.pos) >= a.radiusM + b.radiusM - 1e-4f, "overlap at ${s.timeMs} ms")
            }
        }
        println("fuzzWithWobbleKeepsTheInvariants: highest wobble $top, $checked paths checked, events $counts")
        // (Sampled after a step, so a sub-step's fade, 0.002, may already be off the cap.)
        assertTrue(top >= strong.wobbleMax - 0.01f, "the fuzz reached the cap: $top")
        for (kind in listOf("Hit", "Clash", "WallSlam", "RingOut", "WallSlam fence")) assertTrue((counts[kind] ?: 0) > 0, "never a $kind: $counts")
    }

    /** The segment [p]-[q] enters [poly]: an end inside it, or an edge crossed or touched. */
    private fun crosses(p: Vec2, q: Vec2, poly: Polygon): Boolean {
        if (poly.contains(p) || poly.contains(q)) return true
        fun orient(a: Vec2, b: Vec2, c: Vec2) = (b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u)
        for ((a, b) in poly.edges()) {
            val d1 = orient(a, b, p)
            val d2 = orient(a, b, q)
            val d3 = orient(p, q, a)
            val d4 = orient(p, q, b)
            if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))) return true
        }
        return false
    }

    /**
     * T45's random play on [level] ([TABLE] by default) (frames of 16..50 ms, plans, host pauses, specials) with [params]. Returns the end
     * state and every event; [check] sees each step.
     */
    private fun fuzz(
        seed: Int,
        steps: Int,
        abilities: Pair<Ability, Ability>,
        params: DuelParams,
        level: Level = TABLE,
        check: (prev: DuelState, out: DuelStep) -> Unit = { _, _ -> },
    ): Pair<DuelState, List<DuelEvent>> {
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
        var state = DuelState(
            gifted("a", Vec2(-0.1f, -0.05f), abilities.first, randomStats()),
            gifted("b", Vec2(0.1f, -0.02f), abilities.second, randomStats(), facing = Vec2(-1f, 0f)),
        )
        val events = ArrayList<DuelEvent>()
        var paused = false
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
                    val reach = if (rnd.nextInt(4) == 0) 0.6f else 0.3f
                    wander[k] = Vec2((rnd.nextFloat() * 2f - 1f) * reach, (rnd.nextFloat() * 2f - 1f) * reach * 0.7f)
                }
                val toOther = other.pos - me.pos
                val roll = rnd.nextInt(100)
                val input: DuelInput? = when {
                    roll < 3 -> Jump
                    roll < 6 -> Special
                    roll < 7 -> Dash(if (rnd.nextBoolean()) null else Vec2(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f))
                    plan[k] == 0 && toOther.length < 0.1f && roll < 25 && DuelPhysics.canSpecial(me, level, params) -> Special
                    plan[k] == 0 && toOther.length < 0.12f && DuelPhysics.canDash(me, level, params) -> Dash(toOther)
                    plan[k] == 0 && roll < 40 -> MoveTo(other.pos)
                    plan[k] == 1 && roll < 15 -> MoveTo(wander[k])
                    else -> null
                }
                if (input != null) inputs[me.id] = input
            }
            val prev = state
            val out = DuelPhysics.step(state, level, rnd.nextLong(16L, 51L), inputs, params)
            check(prev, out)
            events += out.events
            state = out.state
        }
        return state to events
    }
}
