package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.domain.defaultFor
import kz.rudy.ar.core.duel.BotLevel
import kz.rudy.ar.core.duel.Contestant
import kz.rudy.ar.core.duel.DuelArena
import kz.rudy.ar.core.duel.DuelArenas
import kz.rudy.ar.core.duel.DuelBot
import kz.rudy.ar.core.duel.DuelInput
import kz.rudy.ar.core.duel.DuelMatchState
import kz.rudy.ar.core.duel.DuelParams
import kz.rudy.ar.core.duel.DuelRules
import kz.rudy.ar.core.duel.DuelRunner
import kz.rudy.ar.core.duel.DuelSim
import kz.rudy.ar.core.duel.MatchEvent
import kz.rudy.ar.core.duel.MatchPhase
import kz.rudy.ar.core.duel.RunnerState
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// T58: the duel as the table engine's second stage, through the engine as a host drives it: slots that carry a
// DuelSetup and record every callback, frames of a given length on the host clock, taps and commands between frames.
// The match itself (DuelMatch, DuelRunner, DuelBot) has its own tests; these pin what the stage feeds it and what the
// slot hears. A log line with a float is asserted by its key only.

private const val DUEL_FRAME_MS = 16L

/** An arbitrary host clock origin (SystemClock.elapsedRealtime is never small on a phone). */
private const val DUEL_T0 = 1_000_000L

private const val WAY = 6
private const val LEAD_WAY = 2
private const val HIDDEN_MS = 800L

/** A depth reading well below the plane: the floor past the table's edge. */
private const val DROP_M = -0.30f

private val RED = FighterStats(power = 7, speed = 6, guard = 5, agility = 2)
private val BLUE = FighterStats(power = 4, speed = 5, guard = 4, agility = 7)

/** EMPTY moved by (0.1, 0.05), spawn included: its marks are (0.02, 0.05) and (0.18, 0.05). */
private val MOVED = Level(emptyList(), boundary = Polygon.rect(Vec2(0.1f, 0.05f), 0.6f, 0.4f), spawn = Vec2(0.1f, 0.05f))

/** A 10 cm table: no room for two fighters. */
private val TINY = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.10f, 0.10f))

private fun duelist(id: String, ability: Ability = Ability.JUMP, stats: FighterStats? = null): Member =
    if (stats == null) Member(id, ability, EmoteSet.defaults()) else Member(id, ability, EmoteSet.defaults(), stats)

private fun wanted(
    serial: Int,
    mine: String = "a",
    foe: String = "b",
    foeLevel: BotLevel = BotLevel.NORMAL,
    seed: Long = 7L,
    mineBot: BotLevel? = null,
    hold: Boolean = false,
) = DuelSetup(serial, mine, foe, foeLevel, seed, mineBot, hold)

/**
 * The AR session as the engine asks it: a view pixel (x, y) is the table point (x, y) in metres; [depth] is the Depth
 * API's reading at a plane point (all nulls by default). Every question lands in [asked] ("hit", "way <n>", "screen").
 */
private class DuelPort : ArPort {
    var depth: (Vec2) -> Float? = { null }
    val asked = ArrayList<String>()

    /** T58 review: the session throws when asked for the way (a host bug, or what a duel tap must survive). */
    var wayThrows = false

    override fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit? {
        asked += "hit"
        val uv = Vec2(xPx, yPx)
        return TableHit(uv, depth(uv))
    }

    override fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?> {
        asked += "way $samples"
        if (wayThrows) throw IllegalStateException("the session is gone")
        return (1..samples).map { i -> depth(from + (to - from) * (i / samples.toFloat())) }
    }

    override fun screenView(): ScreenView? {
        asked += "screen"
        return null
    }
}

/**
 * Drives one engine as RudeusArView and rememberTableEngine do, with T54's constructor: [latest] is the slot of the last
 * composition (rebuilt from the fields by [refresh]), every slot callback lands in [calls] ("<id> <event>", "active
 * <id>", "level", "tracking <b>"), every onDuel in [reports] with the host time it came at, the log in [lines].
 * No feet depth bias by default, so a pose stands exactly on its body.
 */
private class DuelHost(surface: Surface = Surface.TABLE, bias: Float = 0f) {
    val lines = ArrayList<String>()
    val calls = ArrayList<String>()
    val reports = ArrayList<Pair<Long, DuelReport>>()
    val castStates = ArrayList<Long>()
    var anchors = 0
    val port = DuelPort()
    val asked get() = port.asked
    var now = DUEL_T0
    var camera = Vec2(0.5f, 0.3f)
    var cameraH = 0.3f
    var right = Vec2(1f, 0f)
    var members: List<Member> = emptyList()
    var size = 1f
    var debug = false
    var duel: DuelSetup? = null
    var command: CharacterCommand? = null

    /** T58 review: the slot's onCastEvent throws for these (member, event) pairs: something the duel's step calls. */
    var throwOn: ((String, CharacterEvent) -> Boolean)? = null
    private var serial = 0
    var latest: TableSlot = slot()
    val engine = TableEngine(
        { latest }, surface, {}, port, { lines += it },
        feetDepthBias = bias, reappearAfterTrackingMs = HIDDEN_MS, walkDepthSamples = WAY, leadDepthSamples = LEAD_WAY,
    )
    val loop get() = engine.loop
    val match: DuelMatchState get() = assertNotNull(engine.duelMatch, "a duel runs")

    fun slot() = TableSlot(
        members = members,
        activeId = null,
        command = command,
        soundsOn = true,
        onAnchorPlaced = { anchors++; calls += "anchor" },
        onCastEvent = { id, e ->
            calls += "$id $e"
            if (throwOn?.invoke(id, e) == true) throw IllegalStateException("the slot failed on $id $e")
        },
        characterSize = size,
        debug = debug,
        onTracking = { calls += "tracking $it" },
        onCastState = { castStates += now },
        onActiveChanged = { calls += "active $it" },
        onLevel = { calls += "level" },
        duel = duel,
        onDuel = { reports += now to it },
    )

    fun refresh() {
        latest = slot()
    }

    /** The slot with [ms], the size effect, the anchor, the level written by a scan, one walking frame; then clear. */
    fun start(vararg ms: Member, level: Level = DuelArenas.EMPTY, size: Float = 1f, debug: Boolean = false): DuelHost {
        members = ms.toList()
        this.size = size
        this.debug = debug
        refresh()
        engine.configure(size)
        engine.anchorPlaced { right }
        engine.level = level
        frame()
        clear()
        return this
    }

    fun want(setup: DuelSetup?) {
        duel = setup
        refresh()
    }

    /** One AR frame [gapMs] after the previous one, through the engine; the poses it returns. */
    fun frame(tracking: Boolean = true, gapMs: Long = DUEL_FRAME_MS): List<NodePose> {
        now += gapMs
        return engine.onFrame(FrameInput(now, tracking, CameraFrame(camera, cameraH, right)))
    }

    fun frames(n: Int, tracking: Boolean = true) = repeat(n) { frame(tracking) }

    /** Frames until [done] (at most [max]); false when it never came. */
    fun until(max: Int = 20_000, done: () -> Boolean): Boolean {
        var n = 0
        while (!done()) {
            if (n++ >= max) return false
            frame()
        }
        return true
    }

    fun fight() = assertTrue(until { match.phase == MatchPhase.FIGHT }, "the fight starts")

    /** Frames until one brings a report (at most [max]). */
    fun untilReport(max: Int = 20) {
        var n = 0
        do {
            frame()
            assertTrue(n++ < max, "a report within $max frames")
        } while (reports.lastOrNull()?.first != now)
    }

    /** A tap whose table point is [uv], at its pixel, as the host reports it (the depth reading as a lambda). */
    fun tap(uv: Vec2) = engine.onTap(uv.u, uv.v, uv) {
        asked += "depth"
        port.depth(uv)
    }

    /** A button for member [id]: the slot's command with the next serial, offered as the host's effect offers it. */
    fun press(action: CharacterAction, id: String = "a") {
        command = CharacterCommand(++serial, id, action)
        refresh()
        engine.command(latest)
    }

    fun touch(action: TouchAction, at: Vec2): Boolean = engine.onTouch(TouchEvent(action, 0, listOf(TouchPoint(0, at.u, at.v)), now))

    fun walkUntilArrived(id: String, max: Int = 3_000): Boolean {
        var n = 0
        while (state(id).target != null) {
            if (n++ >= max) return false
            frame()
        }
        return true
    }

    fun duelLines() = lines.filter { it.startsWith("duel_") }

    fun lastRunning(): DuelReport.Running = assertIs(reports.last().second)

    fun clear() {
        lines.clear(); calls.clear(); reports.clear(); castStates.clear(); asked.clear()
    }

    fun state(id: String) = assertNotNull(loop.state(id), id)
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

private fun assertNear(want: Vec2, got: Vec2?, what: String, eps: Float = 1e-5f) {
    assertNotNull(got, what)
    assertTrue(abs(want.u - got.u) <= eps && abs(want.v - got.v) <= eps, "$what: $got, want $want")
}

/** What the stage logs for one match event, from the match state after the call that produced it. */
private fun eventLine(e: MatchEvent, m: DuelMatchState): String = when (e) {
    is MatchEvent.RoundStarted -> "duel_round n=${e.round} at_ms=${e.atMs}"
    is MatchEvent.Point ->
        "duel_point round=${m.lastPoint?.round ?: m.round} winner=${e.winner} reason=${e.reason} at_ms=${e.atMs} score=${m.scoreA}:${m.scoreB}"
    is MatchEvent.MatchOver -> "duel_over winner=${e.winner} at_ms=${e.atMs} rounds=${m.round}"
    is MatchEvent.Fight -> "duel_fight ${e.event}"
}

class DuelStageTest {

    // --- Start and stop ---

    @Test
    fun aDuelStartsOnTheFrameItsSerialAppears() {
        val h = DuelHost().start(duelist("a", stats = RED), duelist("b", Ability.HIDE, BLUE))
        h.frames(3)
        assertNull(h.engine.duelMatch)
        h.want(wanted(1))
        assertNull(h.engine.duelMatch, "nothing happens between frames")
        assertTrue(h.reports.isEmpty())
        h.frame()
        val m = h.match
        assertEquals(MatchPhase.COUNTDOWN, m.phase)
        assertEquals(1, m.round)
        assertEquals(0 to 0, m.scoreA to m.scoreB)
        assertEquals(0L, m.duel.timeMs)
        assertEquals("a" to "b", m.duel.a.id to m.duel.b.id)
        val marks = assertNotNull(DuelArena.marks(DuelArenas.EMPTY, SurfaceProfile.TABLE.radiusM, DuelArenas.SEPARATION_M))
        val a = m.duel.a.body.state
        val b = m.duel.b.body.state
        assertEquals(setOf(marks.first, marks.second), setOf(a.pos, b.pos))
        assertEquals(m.startA to m.startB, a.pos to b.pos)
        assertNear((b.pos - a.pos).normalized(), a.facing, "a faces b")
        assertNear((a.pos - b.pos).normalized(), b.facing, "b faces a")
        assertEquals(Mode.IDLE to Mode.IDLE, a.mode to b.mode)
        assertLine("duel_start serial=1 mine=a foe=b level=NORMAL seed=7 marks_ms=", h.duelLines().single())
        val (at, report) = h.reports.single()
        assertEquals(h.now, at)
        val running = assertIs<DuelReport.Running>(report)
        assertEquals(1, running.serial)
        assertSame(m, running.state)
    }

    @Test
    fun myFighterTakesTheMarkNearerTheCamera() {
        val (low, high) = DuelArenas.marks(DuelArenas.EMPTY) // the first mark is the lower one along u
        assertTrue(low.u < high.u)
        val sides = listOf(
            Vec2(0.5f, 0.3f) to high,
            Vec2(-0.5f, 0.3f) to low,
            Vec2(0.4f, -0.7f) to high,
            Vec2(-0.1f, -0.6f) to low,
            Vec2(0f, 0.5f) to low, // a tie: the first mark
        )
        for ((cam, mark) in sides) for ((mine, foe) in listOf("a" to "b", "b" to "a")) {
            val h = DuelHost().start(duelist("a"), duelist("b"))
            h.camera = cam
            h.want(wanted(1, mine = mine, foe = foe))
            h.frame()
            val m = h.match
            assertEquals(mine to foe, m.duel.a.id to m.duel.b.id)
            assertEquals(mark, m.duel.a.body.state.pos, "camera $cam, mine $mine")
            assertEquals(if (mark == low) high else low, m.duel.b.body.state.pos, "camera $cam, mine $mine")
        }
    }

    @Test
    fun statsAbilityRadiusAndParamsComeFromTheMembersTheSurfaceAndTheSize() {
        val h = DuelHost().start(duelist("a", Ability.ROLL, RED), duelist("b", Ability.FLY), level = DuelArenas.LARGE, size = 1.5f)
        h.want(wanted(1))
        h.frame()
        val m = h.match
        assertEquals(RED, m.duel.a.stats)
        assertEquals(Ability.ROLL to Ability.ROLL, m.duel.a.ability to m.duel.a.body.state.ability)
        assertEquals(FighterStats.defaultFor(Ability.FLY), m.duel.b.stats, "a member without stats fights with its archetype")
        assertEquals(Ability.FLY to Ability.FLY, m.duel.b.ability to m.duel.b.body.state.ability)
        val radius = SurfaceProfile.TABLE.radiusAt(1.5f)
        assertEquals(radius to radius, m.duel.a.body.state.radiusM to m.duel.b.body.state.radiusM)
        val physics = PhysicsParams.forProfile(SurfaceProfile.TABLE, 1.5f)
        assertEquals(physics, h.loop.params)
        val marks = assertNotNull(DuelArena.marks(DuelArenas.LARGE, radius, DuelArenas.SEPARATION_M * 1.5f, physics))
        assertEquals(setOf(marks.first, marks.second), setOf(m.startA, m.startB))
        println("statsAbilityRadius...: ${h.lines.single { it.startsWith("duel_start") }} (LARGE, size 1.5)")
        val params = h.lastRunning().params
        assertEquals(DuelParams.forProfile(SurfaceProfile.TABLE, 1.5f), params)
        assertNotEquals(DuelParams.DEFAULT, params)
        // A size change during the duel reaches the loop only.
        h.size = 1f
        h.refresh()
        h.engine.configure(1f)
        h.frames(10)
        assertEquals(radius, h.match.duel.a.body.state.radiusM)
        assertEquals(SurfaceProfile.TABLE.radiusAt(1f), h.loop.radiusM)
        assertEquals(params, h.lastRunning().params)
        // The floor: its radius, separation, speeds and physics.
        val floor = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 2.4f, 1.6f))
        val f = DuelHost(Surface.FLOOR).start(duelist("a"), duelist("b", Ability.HIDE), level = floor)
        f.camera = Vec2(2f, 1.2f)
        f.want(wanted(1))
        f.frame()
        val fm = f.match
        assertEquals(SurfaceProfile.FLOOR.radiusM, fm.duel.a.body.state.radiusM)
        val floorPhysics = PhysicsParams.forProfile(SurfaceProfile.FLOOR)
        val floorMarks = assertNotNull(
            DuelArena.marks(floor, SurfaceProfile.FLOOR.radiusM, DuelArenas.SEPARATION_M * SurfaceProfile.FLOOR_SCALE, floorPhysics),
        )
        assertEquals(setOf(floorMarks.first, floorMarks.second), setOf(fm.startA, fm.startB))
        assertEquals(DuelParams.forProfile(SurfaceProfile.FLOOR), f.lastRunning().params)
        assertEquals(FighterStats.defaultFor(Ability.JUMP), fm.duel.a.stats)
        assertEquals(FighterStats.defaultFor(Ability.HIDE), fm.duel.b.stats)
    }

    @Test
    fun withoutABoundaryTheDuelIsRefusedOnceAndWalkingGoesOn() {
        val h = DuelHost().start(duelist("a"), duelist("b"), level = Level(emptyList()))
        h.tap(Vec2(0.25f, 0.15f))
        h.want(wanted(3))
        h.frames(30)
        assertNull(h.engine.duelMatch)
        assertEquals(listOf("duel_refused serial=3 reason=NO_BOUNDARY"), h.duelLines())
        val refused = assertIs<DuelReport.Refused>(h.reports.single().second)
        assertEquals(3 to DuelRefusal.NO_BOUNDARY, refused.serial to refused.reason)
        assertEquals(Mode.WALKING, h.state("a").mode, "the walking game goes on")
        assertTrue(h.state("a").pos.u > 0.01f, "${h.state("a")}")
        // Not retried: the same serial, even once the table has a boundary.
        h.engine.level = DuelArenas.EMPTY
        h.want(wanted(3))
        h.frames(10)
        assertNull(h.engine.duelMatch)
        assertEquals(1, h.duelLines().size)
        assertEquals(1, h.reports.size)
    }

    @Test
    fun withoutRoomForTwoTheDuelIsRefused() {
        val h = DuelHost().start(duelist("a"), duelist("b"), level = TINY)
        h.want(wanted(1))
        h.frames(20)
        assertNull(h.engine.duelMatch)
        assertEquals(listOf("duel_refused serial=1 reason=NO_ROOM"), h.duelLines())
        val refused = assertIs<DuelReport.Refused>(h.reports.single().second)
        assertEquals(1 to DuelRefusal.NO_ROOM, refused.serial to refused.reason)
        assertSame(TINY, h.engine.level)
    }

    @Test
    fun aFighterThatIsNotOnTheTableRefusesTheDuel() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        for (setup in listOf(wanted(1, foe = "zz"), wanted(2, mine = "zz"), wanted(3, mine = "a", foe = "a"))) {
            h.want(setup)
            h.frames(3)
            assertNull(h.engine.duelMatch)
        }
        assertEquals((1..3).map { "duel_refused serial=$it reason=NO_FIGHTER" }, h.duelLines())
        assertEquals((1..3).map { it to DuelRefusal.NO_FIGHTER }, h.reports.map { assertIs<DuelReport.Refused>(it.second).let { r -> r.serial to r.reason } })
        h.want(wanted(4))
        h.frame()
        assertNotNull(h.engine.duelMatch)
    }

    @Test
    fun aNewSerialIsANewMatchAndTheSameSerialNeverRestarts() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 1L))
        h.frame()
        h.fight()
        h.frames(30)
        val mid = h.match
        // Mid-match: a new DuelSetup instance with the old serial (another seed, another level) changes nothing.
        h.want(wanted(1, foeLevel = BotLevel.EASY, seed = 99L))
        h.frame()
        assertEquals(mid.duel.timeMs + DUEL_FRAME_MS, h.match.duel.timeMs, "the same match goes on")
        assertEquals(1, h.lines.count { it.startsWith("duel_start") })
        // A new serial mid-match is a rematch, at once.
        h.want(wanted(2, foeLevel = BotLevel.HARD, seed = 1L))
        h.frame()
        assertEquals(MatchPhase.COUNTDOWN to 0L, h.match.phase to h.match.duel.timeMs)
        assertEquals(2, h.lines.count { it.startsWith("duel_start") })
        // After RESULT: the old serial changes nothing, a new one starts again.
        assertTrue(h.until { h.match.phase == MatchPhase.RESULT })
        val over = h.match
        h.want(wanted(2, seed = 3L))
        h.frames(10)
        assertSame(over, h.match)
        h.want(wanted(3, seed = 1L))
        h.frame()
        assertEquals(MatchPhase.COUNTDOWN to 0L, h.match.phase to h.match.duel.timeMs)
        assertEquals(3, h.lines.count { it.startsWith("duel_start") })
        // Another seed gives other bot decisions; the same seed the same match.
        fun after(seed: Long): DuelMatchState {
            val g = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE))
            g.want(wanted(1, foeLevel = BotLevel.NORMAL, seed = seed))
            g.frame()
            g.fight()
            g.frames(300)
            return g.match
        }
        assertEquals(after(1L), after(1L))
        assertNotEquals(after(1L), after(2L))
    }

    @Test
    fun leavingTheDuelGivesTheWalkingGameBackWhereItStood() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        val target = Vec2(0.2f, -0.12f)
        h.tap(target)
        h.frames(5)
        assertEquals(Mode.WALKING, h.state("a").mode)
        val stood = h.loop.cast
        h.want(wanted(1))
        h.frame()
        h.frames(250) // into the fight
        assertEquals(MatchPhase.FIGHT, h.match.phase)
        assertEquals(stood, h.loop.cast, "the walking loop stands still in a duel")
        h.clear()
        h.want(null)
        assertEquals(stood, h.loop.cast)
        h.frame()
        assertNull(h.engine.duelMatch)
        assertEquals(1, h.duelLines().size)
        assertTrue(h.duelLines().single().startsWith("duel_end serial=1 phase=FIGHT score="), h.duelLines().single())
        assertEquals(Mode.WALKING, h.state("a").mode)
        assertTrue(h.state("a").pos.distanceTo(stood["a"]!!.state.pos) < 0.01f, "one clamped step, not the whole duel")
        assertTrue(h.walkUntilArrived("a"))
        assertNear(target, h.state("a").pos, "the walk set before the duel", eps = 1e-3f)
    }

    @Test
    fun aFighterLeavingTheCastEndsTheDuel() {
        for (gone in listOf("b", "a")) {
            val h = DuelHost().start(duelist("a"), duelist("b"), duelist("c"))
            h.want(wanted(1))
            h.frame()
            h.fight()
            h.frames(10)
            h.clear()
            h.members = h.members.filter { it.id != gone }
            h.refresh()
            h.frame()
            assertTrue("cast_remove id=$gone" in h.lines)
            assertEquals(2, h.duelLines().size, "${h.duelLines()}")
            assertEquals("duel_refused serial=1 reason=NO_FIGHTER", h.duelLines()[0])
            assertTrue(h.duelLines()[1].startsWith("duel_end serial=1 phase=FIGHT score="), h.duelLines()[1])
            val refused = assertIs<DuelReport.Refused>(h.reports.single().second)
            assertEquals(1 to DuelRefusal.NO_FIGHTER, refused.serial to refused.reason)
            assertNull(h.engine.duelMatch)
            h.frames(20)
            assertNull(h.engine.duelMatch, "the same serial is not restarted")
            assertEquals(2, h.duelLines().size)
            val left = h.members.first().id
            h.loop.walkTo(left, Vec2(0.25f, 0.15f))
            h.frames(5)
            assertEquals(Mode.WALKING, h.state(left).mode, "the walking game is back")
        }
    }

    @Test
    fun aDuelNeverTouchesTheAnchor() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        assertEquals(1, h.anchors)
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.frames(10, tracking = false)
        h.frames(10)
        h.want(wanted(2))
        h.frames(5)
        h.want(null)
        h.frames(5)
        h.want(wanted(3, foe = "zz"))
        h.frames(5)
        assertEquals(1, h.anchors)
        assertTrue(h.engine.anchored)
        assertTrue(h.lines.none { it.startsWith("anchor") }, "${h.lines}")
    }

    // --- Frame ---

    @Test
    fun benchMembersAreHiddenAndFrozenAndComeBack() {
        val h = DuelHost().start(duelist("a"), duelist("b"), duelist("c"), duelist("d"), level = DuelArenas.LARGE)
        val walk = Vec2(0.4f, 0.2f)
        h.loop.walkTo("c", walk)
        h.frames(3)
        val before = h.loop.cast
        h.want(wanted(1, mine = "b", foe = "d"))
        val first = h.frame()
        assertEquals(listOf("a", "b", "c", "d"), first.map { it.id }, "one pose each, in cast order")
        assertEquals(listOf(false, true, false, true), first.map { it.visible })
        assertEquals(h.state("a").pos, first[0].pos, "a bench member is posed where the loop holds it")
        assertEquals(h.match.duel.a.body.state.pos, first[1].pos)
        repeat(300) {
            val poses = h.frame()
            assertEquals(listOf(false, true, false, true), poses.map { it.visible })
        }
        assertEquals(before, h.loop.cast, "the bench stands still")
        h.want(null)
        val back = h.frame()
        assertEquals(listOf("a", "b", "c", "d"), back.map { it.id })
        assertEquals(listOf(true, true, true, true), back.map { it.visible })
        h.frames(10)
        assertNotEquals(before["c"]!!.state.pos, h.state("c").pos, "c walks on")
        assertEquals(walk, h.state("c").target)
    }

    @Test
    fun fightersArePosedFromTheirDuelBodies() {
        // A watched match in which a fighter stays down over a mark the winner stands on (found with DuelRunner).
        val h = DuelHost().start(duelist("a", Ability.FLY), duelist("b", Ability.JUMP))
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 5L, mineBot = BotLevel.NORMAL))
        var posed = 0
        var hidden = 0
        var frames = 0
        while (h.engine.duelMatch?.phase != MatchPhase.RESULT && frames++ < 6_000) {
            val poses = h.frame()
            val m = h.match
            assertEquals(listOf("a", "b"), poses.map { it.id })
            for ((p, f) in listOf(poses[0] to m.duel.a, poses[1] to m.duel.b)) {
                assertEquals(f.body.state.pos, p.pos, "at ${m.duel.timeMs}")
                assertEquals(f.body.state.h, p.h, "at ${m.duel.timeMs}")
                val down = f.body.state.mode == Mode.RESPAWNING
                assertEquals(!down, p.visible, "at ${m.duel.timeMs}")
                if (down) hidden++ else posed++
            }
        }
        assertTrue(hidden > 0, "a RESPAWNING fighter was seen")
        assertTrue(posed > 1_000)
    }

    @Test
    fun dtIsClampedToFiftyMs() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        val steps = listOf(16L to 16L, 200L to 66L, 50L to 116L, 51L to 166L, 0L to 166L, 1_000L to 216L, -40L to 216L, 16L to 232L)
        for ((gap, at) in steps) {
            h.frame(gapMs = gap)
            assertEquals(at, h.match.duel.timeMs, "a frame $gap ms after the last")
        }
        h.fight()
        val t = h.match.duel.timeMs
        h.frame(gapMs = 700)
        assertEquals(t + MAX_STEP_DT_MS, h.match.duel.timeMs)
    }

    @Test
    fun theFirstFrameAndTheFrameBackStepNothing() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.frames(10)
        h.want(wanted(1))
        h.frame(gapMs = 40)
        assertEquals(0L, h.match.duel.timeMs, "the start frame")
        h.frame()
        assertEquals(16L, h.match.duel.timeMs)
        repeat(5) { h.frame(tracking = false, gapMs = 300) }
        assertEquals(16L, h.match.duel.timeMs)
        h.frame(gapMs = 40)
        assertEquals(16L, h.match.duel.timeMs, "the frame back")
        assertNotEquals(Mode.PAUSED, h.match.duel.a.body.state.mode)
        h.frame()
        assertEquals(32L, h.match.duel.timeMs)
        h.fight()
        val t = h.match.duel.timeMs
        h.frame(tracking = false)
        h.frame(gapMs = 45)
        assertEquals(t, h.match.duel.timeMs, "the frame back in the fight")
        h.frame(gapMs = 45)
        assertEquals(t + 45, h.match.duel.timeMs)
    }

    @Test
    fun aLossFreezesTheMatchAndNothingResets() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE))
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.press(CharacterAction.Dash)
        h.frame()
        h.frames(3)
        val before = h.match
        assertEquals(MatchPhase.FIGHT, before.phase)
        assertTrue(before.duel.a.dashCooldownMs > 0L)
        val lostAt = h.now
        var frames = 0
        while (h.now - lostAt < 70_000L) {
            assertTrue(h.frame(tracking = false).isEmpty(), "no poses")
            frames++
            val m = h.match
            assertEquals(Mode.PAUSED to Mode.PAUSED, m.duel.a.body.state.mode to m.duel.b.body.state.mode)
            assertEquals(before.duel.timeMs, m.duel.timeMs)
            assertEquals(before.phaseLeftMs, m.phaseLeftMs, "the round clock")
            assertEquals(listOf(before.phase, before.round, before.scoreA, before.scoreB), listOf(m.phase, m.round, m.scoreA, m.scoreB))
            for ((was, f) in listOf(before.duel.a to m.duel.a, before.duel.b to m.duel.b)) {
                assertEquals(was.body.state.pos, f.body.state.pos)
                assertEquals(was.body.state.h, f.body.state.h)
                assertEquals(listOf(was.dashMs, was.dashCooldownMs, was.specialMs, was.specialCooldownMs, was.stunMs), listOf(f.dashMs, f.dashCooldownMs, f.specialMs, f.specialCooldownMs, f.stunMs))
                assertEquals(was.knock, f.knock)
            }
        }
        assertTrue(frames > 4_000)
        h.frame()
        val back = h.match
        assertEquals(before.duel.timeMs, back.duel.timeMs)
        assertEquals(before.duel.a.body.state, back.duel.a.body.state, "resumed as it stood")
        assertEquals(before.duel.b.body.state, back.duel.b.body.state, "resumed as it stood")
        h.frames(10)
        assertEquals(before.duel.timeMs + 160L, h.match.duel.timeMs, "the match goes on")
        assertEquals(before.phaseLeftMs - 160L, h.match.phaseLeftMs)
    }

    @Test
    fun aWatchedMatchIsTheSimulatorsMatch() {
        val h = DuelHost().start(duelist("a", Ability.ROLL, RED), duelist("b", Ability.HIDE, BLUE), level = DuelArenas.CUP_AND_BOOK)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 11L, mineBot = BotLevel.NORMAL))
        h.frame()
        val marks = h.match.startA to h.match.startB
        assertTrue(h.until(max = 40_000) { h.match.phase == MatchPhase.RESULT })
        val sim = DuelSim.match(
            Contestant("a", RED, Ability.ROLL), Contestant("b", BLUE, Ability.HIDE), DuelArenas.CUP_AND_BOOK,
            BotLevel.NORMAL, BotLevel.HARD, 11L, stepMs = 16L, marks = marks,
        )
        assertTrue(sim.result.finished)
        assertEquals(sim.final.match, h.match)
    }

    @Test
    fun aHumanMatchIsTheRunnersMatch() {
        val arena = DuelArenas.EMPTY
        val h = DuelHost().start(duelist("a", Ability.ROLL, RED), duelist("b", Ability.HIDE, BLUE), level = arena)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 3L))
        h.frame()
        val start = RunnerState(h.match, null, DuelBot.mind(BotLevel.HARD, 2L * 3L + 1L))
        val given = HashMap<Long, DuelInput>()
        val seen = LinkedHashMap<Long, DuelMatchState>()
        while (h.match.phase != MatchPhase.RESULT && h.match.duel.timeMs < 120_000L) {
            val m = h.match
            val t = m.duel.timeMs
            if (t > 0L && t % 528L == 0L && t !in seen) {
                seen[t] = m
                when ((t / 528L) % 4L) {
                    0L -> if (m.phase == MatchPhase.FIGHT) {
                        val foe = m.duel.b.body.state.pos
                        h.tap(foe)
                        given[t] = DuelInput.MoveTo(arena.nearestInside(foe))
                    }
                    1L -> h.press(CharacterAction.Dash).also { given[t] = DuelInput.Dash() }
                    2L -> h.press(CharacterAction.Jump).also { given[t] = DuelInput.Jump }
                    else -> h.press(CharacterAction.Special).also { given[t] = DuelInput.Special }
                }
            }
            h.frame()
        }
        assertEquals(MatchPhase.RESULT, h.match.phase)
        assertTrue(given.size > 20, "${given.size} inputs")
        // The same inputs at the same match times, straight into the runner, in 48 ms calls (528 = 11 x 48).
        var s = start
        val end = h.match.duel.timeMs
        var compared = 0
        while (s.match.duel.timeMs < end && s.match.phase != MatchPhase.RESULT) {
            val t = s.match.duel.timeMs
            seen[t]?.let { assertEquals(it, s.match, "at $t"); compared++ }
            val inputs = given[t]?.let { mapOf("a" to it) } ?: emptyMap()
            s = DuelRunner.step(s, arena, minOf(48L, end - t), inputs).state
        }
        assertEquals(seen.size, compared)
        assertEquals(h.match, s.match)
    }

    @Test
    fun theSameSeedAndInputsGiveTheSameMatchAt16And33MsFrames() {
        fun run(frameMs: Long, lossAtMs: Long? = null): Pair<Map<Long, DuelMatchState>, DuelMatchState> {
            val h = DuelHost().start(duelist("a", Ability.JUMP, RED), duelist("b", Ability.FLY, BLUE))
            h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 21L))
            h.frame()
            val seen = LinkedHashMap<Long, DuelMatchState>()
            var lost = lossAtMs == null
            while (h.match.phase != MatchPhase.RESULT && h.match.duel.timeMs < 120_000L) {
                val m = h.match
                val t = m.duel.timeMs
                if (t > 0L && t % 528L == 0L && t !in seen) {
                    seen[t] = m
                    when ((t / 528L) % 4L) {
                        0L -> if (m.phase == MatchPhase.FIGHT) h.tap(m.duel.b.body.state.pos)
                        1L -> h.press(CharacterAction.Dash)
                        2L -> h.press(CharacterAction.Jump)
                        else -> h.press(CharacterAction.Special)
                    }
                }
                if (!lost && t >= lossAtMs!!) {
                    lost = true
                    assertNotEquals(0L, t % 528L)
                    repeat((2_000L / frameMs).toInt()) { h.frame(tracking = false, gapMs = frameMs) }
                }
                h.frame(gapMs = frameMs)
            }
            assertEquals(MatchPhase.RESULT, h.match.phase)
            assertTrue(lost)
            return seen to h.match
        }
        val sixteen = run(16L)
        val thirtyThree = run(33L)
        assertTrue(sixteen.first.size > 20)
        assertEquals(sixteen.first, thirtyThree.first)
        assertEquals(sixteen.second, thirtyThree.second)
        val withLoss = run(33L, lossAtMs = 33L * 150L) // 4950 ms: in the fight, between two inputs
        assertEquals(sixteen.first, withLoss.first)
        assertEquals(sixteen.second, withLoss.second)
    }

    @Test
    fun aFullMatchRunsToResultThroughTheEngine() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frame()
        assertTrue(h.until(max = 10_000) { h.match.phase == MatchPhase.RESULT })
        val m = h.match
        println("aFullMatchRunsToResultThroughTheEngine: ${m.scoreA}:${m.scoreB} at ${m.duel.timeMs} ms, ${h.lines.size} log lines")
        val winner = assertNotNull(m.winner)
        assertEquals(2, m.scoreOf(winner))
        assertLine("duel_over winner=$winner at_ms= rounds=", h.lines.single { it.startsWith("duel_over") })
        assertTrue(h.lines.count { it.startsWith("duel_point") } >= 2)
        assertEquals(MatchPhase.RESULT, h.lastRunning().state.phase)
    }

    // --- Input ---

    @Test
    fun aTapIsMoveToForMyFighterOnly() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        val level = h.engine.level
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.clear()
        val uv = Vec2(0.1f, 0.12f)
        h.tap(uv)
        assertEquals(listOf("depth", "way $WAY"), h.asked, "no pick: the depth and the way only")
        h.frame()
        assertEquals(uv, h.match.duel.a.body.state.target)
        assertNotEquals(uv, h.match.duel.b.body.state.target)
        // A tap on the opponent is a MoveTo too: nobody is picked.
        val foe = h.match.duel.b.body.state.pos
        h.tap(foe)
        h.frame()
        assertEquals(foe, h.match.duel.a.body.state.target)
        assertTrue(h.calls.none { it.startsWith("active") || it == "level" }, "${h.calls}")
        assertSame(level, h.engine.level)
        assertSame(level, h.lastRunning().arena)
        assertTrue(h.lines.none { it.startsWith("tap_") || it.startsWith("boundary_grown") }, "${h.lines}")
    }

    @Test
    fun aTapOutsideTheArenaIsClampedInside() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.clear()
        val uv = Vec2(0.9f, -0.5f)
        h.tap(uv)
        h.frame()
        val target = assertNotNull(h.match.duel.a.body.state.target)
        assertEquals(DuelArenas.EMPTY.nearestInside(uv), target)
        assertTrue(DuelArenas.EMPTY.boundary!!.contains(target))
        assertTrue(abs(target.u - 0.3f) < 0.002f && abs(target.v + 0.2f) < 0.002f, "$target")
        assertSame(DuelArenas.EMPTY, h.engine.level)
        assertTrue(h.calls.none { it == "level" })
    }

    @Test
    fun aTapPastADepthEdgeIsRefused() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.clear()
        h.port.depth = { p -> if (p.u > 0.2f) DROP_M else 0f } // the table under the fighter, then the floor
        val uv = Vec2(0.25f, 0f)
        h.tap(uv)
        assertEquals(listOf("depth", "way $WAY"), h.asked, "the same questions, in the same order as free play")
        assertLine("duel_tap_past_edge u= v=", h.lines.single())
        h.frame()
        assertNull(h.match.duel.a.body.state.target)
        assertTrue(h.calls.none { it.endsWith(" BLOCKED") }, "${h.calls}")
        // No reading never refuses.
        h.port.depth = { null }
        h.tap(uv)
        h.frame()
        assertEquals(uv, h.match.duel.a.body.state.target)
    }

    @Test
    fun tapsOutsideAFightAskNothingAndDoNothing() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frame()
        fun check(phase: MatchPhase) {
            assertEquals(phase, h.match.phase)
            h.clear()
            h.tap(Vec2(0.05f, 0.05f))
            assertTrue(h.asked.isEmpty(), "$phase: ${h.asked}")
            assertTrue(h.lines.isEmpty(), "$phase: ${h.lines}")
            h.frame()
            assertNull(h.match.duel.a.body.state.target, "$phase")
        }
        check(MatchPhase.COUNTDOWN)
        assertTrue(h.until { h.match.phase == MatchPhase.POINT })
        check(MatchPhase.POINT)
        assertTrue(h.until { h.match.phase == MatchPhase.RESULT })
        check(MatchPhase.RESULT)
    }

    @Test
    fun dashJumpAndSpecialAreCommandsForMyFighter() {
        for (ability in Ability.entries) for (action in listOf(CharacterAction.Dash, CharacterAction.Jump, CharacterAction.Special)) {
            val what = "$ability $action"
            val h = DuelHost().start(duelist("a", ability), duelist("b", Ability.HIDE))
            h.want(wanted(1))
            h.frame()
            h.fight()
            val before = h.match.duel.a
            h.clear()
            h.press(action)
            assertTrue(h.lines.isEmpty(), "$what: ${h.lines}")
            h.frame()
            val a = h.match.duel.a
            when (action) {
                CharacterAction.Dash -> {
                    assertTrue(a.dashMs > 0L && a.dashCooldownMs > 0L, what)
                    assertNear(before.body.state.facing, a.dashDir, "$what: along the facing")
                }
                CharacterAction.Jump ->
                    if (ability == Ability.ROLL) assertNotEquals(Mode.JUMPING, a.body.state.mode, "$what: ROLL cannot jump")
                    else assertEquals(Mode.JUMPING, a.body.state.mode, what)
                else -> {
                    assertTrue(a.specialCooldownMs > 0L, what)
                    assertTrue(h.lines.any { it.startsWith("duel_fight SpecialStarted(id=a, ability=$ability,") }, "$what: ${h.lines}")
                }
            }
        }
    }

    @Test
    fun anActionWinsOverATapOfTheSameFrameAndTheTapFollows() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        h.fight()
        val uv = Vec2(-0.05f, 0.12f)
        h.tap(uv)
        h.press(CharacterAction.Jump)
        h.frame()
        assertEquals(Mode.JUMPING, h.match.duel.a.body.state.mode)
        assertNull(h.match.duel.a.body.state.target, "the tap waits a frame")
        h.frame()
        assertEquals(uv, h.match.duel.a.body.state.target, "and follows")
        // Two buttons between frames: the last pressed wins.
        val g = DuelHost().start(duelist("a"), duelist("b"))
        g.want(wanted(1))
        g.frame()
        g.fight()
        g.press(CharacterAction.Jump)
        g.press(CharacterAction.Dash)
        g.frame()
        assertTrue(g.match.duel.a.dashMs > 0L)
        assertNotEquals(Mode.JUMPING, g.match.duel.a.body.state.mode)
        g.frames(3)
        assertNotEquals(Mode.JUMPING, g.match.duel.a.body.state.mode, "the jump was not kept")
    }

    @Test
    fun inputsGivenDuringALossAreDropped() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.frame(tracking = false)
        h.tap(Vec2(-0.05f, 0.12f))
        h.press(CharacterAction.Dash)
        h.frame(tracking = false)
        h.frames(3)
        var a = h.match.duel.a
        assertNull(a.body.state.target)
        assertEquals(0L to 0L, a.dashMs to a.dashCooldownMs)
        // Given just before a loss: dropped too (a Jump pressed under the cover does nothing).
        h.tap(Vec2(-0.05f, 0.12f))
        h.press(CharacterAction.Jump)
        h.frame(tracking = false)
        h.frames(3)
        a = h.match.duel.a
        assertNull(a.body.state.target)
        assertNotEquals(Mode.JUMPING, a.body.state.mode)
    }

    @Test
    fun otherCommandsAreIgnoredInADuelAndDuelCommandsOutsideOne() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.press(CharacterAction.Dash)
        h.press(CharacterAction.Special)
        assertEquals(listOf("duel_command_ignored serial=1 id=a duel=false", "duel_command_ignored serial=2 id=a duel=false"), h.lines)
        h.want(wanted(1))
        h.frame()
        h.fight()
        h.clear()
        h.press(CharacterAction.WalkForward)
        h.press(CharacterAction.Emote(Emote.HELLO))
        h.press(CharacterAction.Jump, id = "b")
        h.press(CharacterAction.Dash, id = "b")
        assertEquals((3..6).map { "duel_command_ignored serial=$it id=${if (it < 5) "a" else "b"} duel=true" }, h.lines)
        h.press(CharacterAction.Jump, id = "zz")
        assertEquals("command_ignored serial=7 id=zz anchored=true", h.lines.last())
        h.frame()
        val a = h.match.duel.a
        assertNull(a.body.state.target)
        assertEquals(Mode.IDLE, a.body.state.mode)
        // No replay in a duel, and the request is not retried after it.
        h.clear()
        h.engine.scenario(1)
        assertEquals(listOf("scenario_rejected duel=true"), h.lines)
        h.want(null)
        h.frame()
        h.clear()
        h.engine.scenario(1)
        assertTrue(h.lines.isEmpty())
        h.engine.scenario(2)
        assertTrue(h.lines.single().startsWith("scenario_start id=day5"), "${h.lines}")
    }

    @Test
    fun fingersGrabNobodyInADuel() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        val b = h.state("b").pos
        assertTrue(h.touch(TouchAction.DOWN, b), "free play: a finger on b grabs it")
        assertEquals("grab pointer=0 id=b", h.lines.last())
        h.clear()
        h.want(wanted(1))
        h.frame()
        assertEquals("release pointer=0 id=b", h.lines.first(), "a finger held when the duel starts is released")
        assertTrue(h.lines[1].startsWith("duel_start"))
        h.fight()
        h.clear()
        val a = h.match.duel.a.body.state.pos
        assertFalse(h.touch(TouchAction.DOWN, a))
        assertFalse(h.touch(TouchAction.MOVE, a + Vec2(0.03f, 0f)))
        assertFalse(h.touch(TouchAction.UP, a + Vec2(0.03f, 0f)))
        assertTrue(h.asked.isEmpty(), "${h.asked}")
        assertTrue(h.lines.isEmpty() && h.calls.isEmpty(), "${h.lines} ${h.calls}")
    }

    @Test
    fun inAWatchedDuelTapsAndCommandsDoNothing() {
        fun run(poke: Boolean): DuelMatchState {
            val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE))
            h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 4L, mineBot = BotLevel.NORMAL))
            h.frame()
            h.fight()
            repeat(200) { i ->
                if (poke && i % 10 == 0) {
                    h.clear()
                    h.tap(Vec2(0.1f, 0.1f))
                    assertTrue(h.asked.isEmpty())
                    h.press(CharacterAction.Dash)
                    h.press(CharacterAction.Jump)
                    assertEquals(2, h.lines.count { it.startsWith("duel_command_ignored") && it.endsWith(" id=a duel=true") })
                }
                h.frame()
            }
            return h.match
        }
        assertEquals(run(false), run(true))
    }

    // --- Level ---

    @Test
    fun standingIsEveryMemberInCastOrderWithTheFightersDuelStates() {
        val h = DuelHost().start(duelist("a"), duelist("b"), duelist("c"))
        assertEquals(h.loop.cast.members.map { it.body.state }, h.engine.standing())
        h.want(wanted(1, mine = "c", foe = "a"))
        h.frame()
        h.fight()
        h.frames(20)
        val m = h.match
        assertEquals(listOf(m.duel.b.body.state, h.state("b"), m.duel.a.body.state), h.engine.standing())
        assertNotEquals(h.state("a"), m.duel.b.body.state)
    }

    @Test
    fun aLevelThatArrivesInAFightWaitsForTheRoundsEnd() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frame()
        h.fight()
        h.frames(5)
        val old = h.match.startA to h.match.startB
        h.engine.level = MOVED
        assertSame(MOVED, h.engine.level, "the write is kept at once")
        while (h.match.phase == MatchPhase.FIGHT) {
            h.frame()
            assertEquals(old, h.match.startA to h.match.startB)
            assertSame(DuelArenas.SMALL, h.lastRunning().arena)
        }
        assertEquals(MatchPhase.POINT, h.match.phase)
        assertTrue(h.lines.none { it.startsWith("duel_arena") }, "the frame that ended the round started in the fight")
        h.frame()
        val (p, q) = DuelArenas.marks(MOVED)
        assertEquals(setOf(p, q), setOf(h.match.startA, h.match.startB))
        assertSame(MOVED, h.lastRunning().arena)
        assertEquals(h.now, h.reports.last().first)
        val line = h.lines.single { it.startsWith("duel_arena") }
        assertTrue(line.startsWith("duel_arena marks=") && line.contains(" phase=POINT marks_ms="), line)
        assertTrue(h.until { h.match.phase == MatchPhase.COUNTDOWN })
        assertEquals(h.match.startA to h.match.startB, h.match.duel.a.body.state.pos to h.match.duel.b.body.state.pos)
    }

    @Test
    fun aLevelThatArrivesBetweenRoundsMovesTheMarksAndKeepsTheSides() {
        val (p, q) = DuelArenas.marks(MOVED)
        assertTrue(p.u < q.u)
        for (cam in listOf(Vec2(0.5f, 0.3f), Vec2(-0.5f, 0.3f))) {
            val h = DuelHost().start(duelist("a"), duelist("b"))
            h.camera = cam
            h.want(wanted(1))
            h.frame()
            h.frames(3)
            val old = h.match
            val mineHigh = old.startA.u > old.startB.u
            assertEquals(cam.u > 0f, mineHigh)
            h.clear()
            h.engine.level = MOVED
            h.frame()
            val m = h.match
            val (mine, foe) = if (mineHigh) q to p else p to q
            assertEquals(mine to foe, m.startA to m.startB, "camera $cam: each on the new mark on its own side")
            assertEquals(mine to foe, m.duel.a.body.state.pos to m.duel.b.body.state.pos, "moved at once in the countdown")
            assertNear((foe - mine).normalized(), m.duel.a.body.state.facing, "facing each other")
            assertEquals(MatchPhase.COUNTDOWN, m.phase)
            assertEquals(old.phaseLeftMs - DUEL_FRAME_MS, m.phaseLeftMs, "the countdown goes on")
            assertLine("duel_arena marks=0 phase=COUNTDOWN marks_ms=", h.duelLines().single().replace(Regex("marks=[^ ]+ "), "marks=0 "))
            val (at, report) = h.reports.single()
            assertEquals(h.now, at)
            assertSame(MOVED, assertIs<DuelReport.Running>(report).arena)
        }
    }

    @Test
    fun aLevelWithoutRoomKeepsTheOldArena() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        val old = h.match.startA to h.match.startB
        h.clear()
        h.engine.level = TINY
        assertSame(TINY, h.engine.level)
        h.frame()
        assertLine("duel_arena_kept reason=NO_ROOM marks_ms=", h.duelLines().single())
        assertEquals(old, h.match.startA to h.match.startB)
        val open = Level(emptyList())
        h.engine.level = open
        h.frame()
        assertEquals("duel_arena_kept reason=NO_BOUNDARY", h.duelLines().last())
        h.frames(20)
        assertEquals(2, h.duelLines().size, "once per write")
        assertTrue(h.reports.all { assertIs<DuelReport.Running>(it.second).arena === DuelArenas.EMPTY })
        assertEquals(old, h.match.startA to h.match.startB)
        h.want(null)
        h.frame()
        assertSame(open, h.engine.level, "the walking game stands on the last level written")
    }

    @Test
    fun aHeldCountdownWaitsAndGoesOnWhereItStopped() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L, hold = true))
        h.frame()
        val full = h.match.phaseLeftMs
        h.frames(200)
        assertEquals(MatchPhase.COUNTDOWN to 0L, h.match.phase to h.match.duel.timeMs)
        assertEquals(full, h.match.phaseLeftMs)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frames(10)
        assertEquals(160L to full - 160L, h.match.duel.timeMs to h.match.phaseLeftMs)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L, hold = true))
        h.frames(50)
        assertEquals(160L, h.match.duel.timeMs, "held again where it stood")
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frame()
        assertEquals(176L, h.match.duel.timeMs, "on again with one frame, not the held time")
        h.fight()
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L, hold = true))
        var t = h.match.duel.timeMs
        h.frames(10)
        assertEquals(t + 160L, h.match.duel.timeMs, "FIGHT is not held")
        assertTrue(h.until { h.match.phase == MatchPhase.POINT })
        t = h.match.duel.timeMs
        h.frames(10)
        assertEquals(MatchPhase.POINT, h.match.phase)
        assertEquals(t + 160L, h.match.duel.timeMs, "POINT is not held")
        assertTrue(h.until { h.match.phase == MatchPhase.COUNTDOWN })
        t = h.match.duel.timeMs
        h.frames(10)
        assertEquals(t, h.match.duel.timeMs, "the next countdown is held")
    }

    // --- Reports and events ---

    @Test
    fun reportsComeEvery100MsAndAtOnceOnAChange() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frame()
        val start = h.now
        assertEquals(listOf(start), h.reports.map { it.first }, "the start")
        h.frames(30)
        assertEquals((0..4).map { start + 112L * it }, h.reports.map { it.first }, "every 100 ms at 16 ms frames")
        // A new phase.
        h.untilReport()
        assertTrue(h.until { h.match.phase == MatchPhase.FIGHT })
        assertEquals(h.now, h.reports.last().first)
        assertEquals(MatchPhase.FIGHT, h.lastRunning().state.phase)
        // Not due 16 ms after a report, unless the child's input was applied.
        h.untilReport()
        h.frame()
        assertNotEquals(h.now, h.reports.last().first)
        h.untilReport()
        h.press(CharacterAction.Dash)
        h.frame()
        assertEquals(h.now, h.reports.last().first, "an applied input")
        assertTrue(h.lastRunning().state.duel.a.dashCooldownMs > 0L)
        // A loss: the paused match once; the first frame back at once.
        h.untilReport()
        val n = h.reports.size
        h.frames(40, tracking = false)
        val paused = h.reports.drop(n)
        assertEquals(1, paused.size, "a paused match reports once")
        assertEquals(Mode.PAUSED, assertIs<DuelReport.Running>(paused.single().second).state.duel.a.body.state.mode)
        h.frame()
        assertEquals(h.now, h.reports.last().first, "the first frame back")
        assertNotEquals(Mode.PAUSED, h.lastRunning().state.duel.a.body.state.mode)
        // A point: score and phase at once.
        while (h.match.phase == MatchPhase.FIGHT) h.frame()
        assertEquals(h.now, h.reports.last().first, "a point")
        assertEquals(1, h.lastRunning().state.scoreA + h.lastRunning().state.scoreB)
        // A rearranged arena.
        h.untilReport()
        h.engine.level = MOVED
        h.frame()
        assertEquals(h.now, h.reports.last().first, "a rearranged arena")
        assertSame(MOVED, h.lastRunning().arena)
        // A new round.
        assertTrue(h.until { h.match.phase == MatchPhase.COUNTDOWN })
        assertEquals(h.now, h.reports.last().first, "a new round")
        assertEquals(2, h.lastRunning().state.round)
        // RESULT once.
        assertTrue(h.until { h.match.phase == MatchPhase.RESULT })
        val reportsAtResult = h.reports.size
        h.frames(100)
        h.frames(20, tracking = false)
        h.frames(20)
        assertEquals(reportsAtResult, h.reports.size)
        assertEquals(1, h.reports.count { assertIs<DuelReport.Running>(it.second).state.phase == MatchPhase.RESULT })
        // Never one instance twice, and never more often than a frame.
        val states = h.reports.map { assertIs<DuelReport.Running>(it.second).state }
        for (i in states.indices) for (j in i + 1 until states.size) assertNotSame(states[i], states[j])
        assertEquals(h.reports.size, h.reports.map { it.first }.distinct().size)
    }

    @Test
    fun aReportCarriesTheArenaRulesAndParamsTheMatchRunsWith() {
        val h = DuelHost().start(duelist("a"), duelist("b"), level = DuelArenas.LARGE, size = 0.75f)
        h.want(wanted(5))
        h.frame()
        h.frames(40)
        assertTrue(h.reports.size >= 5)
        for ((_, r) in h.reports) {
            val running = assertIs<DuelReport.Running>(r)
            assertEquals(5, running.serial)
            assertSame(DuelArenas.LARGE, running.arena)
            assertEquals(DuelRules.DEFAULT, running.rules)
            assertEquals(DuelParams.forProfile(SurfaceProfile.TABLE, 0.75f), running.params)
        }
        h.engine.level = MOVED
        h.frame()
        assertSame(MOVED, h.lastRunning().arena)
        assertSame(h.match, h.lastRunning().state)
    }

    @Test
    fun fightersTransitionsReachOnCastEvent() {
        val h = DuelHost().start(duelist("a"), duelist("b", Ability.HIDE), level = DuelArenas.SMALL)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 2L))
        h.frame()
        h.fight()
        h.clear()
        h.press(CharacterAction.Jump)
        h.frame()
        assertTrue("a JUMPED" in h.calls, "${h.calls}")
        var landed: List<NodePose>? = null
        var n = 0
        while (landed == null && n++ < 200) {
            val poses = h.frame()
            if ("a LANDED" in h.calls) landed = poses
        }
        val pose = assertNotNull(landed, "a LANDED")[0]
        assertEquals(JUMP_LAND_SX to JUMP_LAND_SY, pose.scaleX to pose.scaleY, "the landing clock is this frame's host time")
        val next = h.frame()[0]
        assertTrue(next.scaleX < JUMP_LAND_SX && next.scaleX > 1f, "${next.scaleX}")
        // A ring-out: the loser FELL.
        assertTrue(h.until { h.match.phase == MatchPhase.POINT })
        val point = assertNotNull(h.match.lastPoint)
        assertEquals("b", point.winner)
        assertTrue("a FELL" in h.calls, "${h.calls}")
        // Mine first, also when mine is the second in cast order: a loss pauses both on one frame, as the walking game.
        val g = DuelHost().start(duelist("a"), duelist("b"))
        g.want(wanted(1, mine = "b", foe = "a"))
        g.frame()
        g.fight()
        g.clear()
        g.frame(tracking = false)
        assertEquals(listOf("tracking false", "b PAUSED", "a PAUSED"), g.calls)
        g.clear()
        g.frame()
        assertEquals(listOf("tracking true", "b RESUMED", "a RESUMED"), g.calls)
    }

    @Test
    fun matchEventsAreLoggedOnceEach() {
        val arena = DuelArenas.SMALL
        val h = DuelHost().start(duelist("a", Ability.ROLL), duelist("b", Ability.HIDE), level = arena)
        h.want(wanted(1, foeLevel = BotLevel.HARD, seed = 3L, mineBot = BotLevel.NORMAL))
        h.frame()
        var s = RunnerState(h.match, DuelBot.mind(BotLevel.NORMAL, 6L), DuelBot.mind(BotLevel.HARD, 7L))
        assertTrue(h.until { h.match.phase == MatchPhase.RESULT })
        val want = ArrayList<String>()
        while (s.match.phase != MatchPhase.RESULT) {
            val out = DuelRunner.step(s, arena, 16L)
            s = out.state
            for (e in out.events) want += eventLine(e, s.match)
        }
        val logged = h.lines.filter { it.startsWith("duel_round") || it.startsWith("duel_point") || it.startsWith("duel_over") || it.startsWith("duel_fight") }
        assertEquals(want, logged)
        assertTrue(logged.count { it.startsWith("duel_round") } >= 2)
        assertEquals(1, logged.count { it.startsWith("duel_over") })
        assertTrue(logged.any { it.startsWith("duel_fight ") })
    }

    @Test
    fun noCastStateReportDuringADuel() {
        val h = DuelHost().start(duelist("a"), duelist("b"), debug = true)
        h.frames(20)
        assertTrue(h.castStates.isNotEmpty())
        h.want(wanted(1))
        h.frame()
        h.castStates.clear()
        h.frames(100)
        h.frames(10, tracking = false)
        h.frames(10)
        assertTrue(h.castStates.isEmpty())
        h.want(null)
        h.frames(20)
        assertTrue(h.castStates.isNotEmpty())
    }

    // --- T58 review: the duel cannot end the AR session ---

    @Test
    fun anExceptionInTheDuelStepEndsTheDuelAndFreePlayGoesOn() {
        // Inside a frame: the slot's onCastEvent, which the duel's step calls for my fighter's JUMPED, throws.
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        h.fight()
        val stood = h.loop.cast
        h.throwOn = { id, e -> id == "a" && e == CharacterEvent.JUMPED }
        h.clear()
        h.press(CharacterAction.Jump)
        val poses = h.frame()
        assertNull(h.engine.duelMatch, "the duel ended")
        assertEquals(
            listOf("duel_failed serial=1 phase=FIGHT what=IllegalStateException", "duel_end serial=1 phase=FIGHT score=0:0"),
            h.duelLines(),
        )
        val (at, report) = h.reports.single()
        assertEquals(h.now, at)
        val refused = assertIs<DuelReport.Refused>(report)
        assertEquals(1 to DuelRefusal.FAILED, refused.serial to refused.reason)
        assertEquals(stood, h.loop.cast, "the walking game stands where it stood, as after duel = null")
        assertEquals(listOf(true, true), poses.map { it.visible }, "the same frame poses the walking cast")
        assertEquals(stood["a"]!!.state.pos, poses[0].pos)
        h.throwOn = null
        val target = Vec2(0.25f, 0.15f)
        h.tap(target)
        h.frames(5)
        assertEquals(Mode.WALKING, h.state("a").mode, "free play goes on")
        assertEquals(target, h.state("a").target)
        assertEquals(1, h.reports.size)
        // Between frames: the session throws inside a duel tap. The duel ends at once, the slot hears it on the next frame.
        val g = DuelHost().start(duelist("a"), duelist("b"))
        g.want(wanted(1))
        g.frame()
        g.fight()
        g.clear()
        g.port.wayThrows = true
        g.tap(Vec2(0.1f, 0.1f))
        assertNull(g.engine.duelMatch)
        assertEquals(
            listOf("duel_failed serial=1 phase=FIGHT what=IllegalStateException", "duel_end serial=1 phase=FIGHT score=0:0"),
            g.duelLines(),
        )
        assertTrue(g.reports.isEmpty(), "onDuel is a frame's callback")
        g.port.wayThrows = false
        g.frame()
        val (gAt, gReport) = g.reports.single()
        assertEquals(g.now, gAt)
        assertEquals(DuelRefusal.FAILED, assertIs<DuelReport.Refused>(gReport).reason)
        g.frames(5)
        assertEquals(1, g.reports.size)
    }

    @Test
    fun aFailedSerialIsNotRetried() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(4))
        h.frame()
        h.fight()
        h.throwOn = { id, e -> id == "a" && e == CharacterEvent.JUMPED }
        h.press(CharacterAction.Jump)
        h.frame()
        assertNull(h.engine.duelMatch)
        h.throwOn = null
        h.clear()
        h.frames(30)
        h.want(wanted(4, seed = 9L)) // a new instance with the failed serial
        h.frames(10)
        h.want(wanted(3)) // a lower one
        h.frames(10)
        assertNull(h.engine.duelMatch)
        assertTrue(h.duelLines().isEmpty(), "${h.duelLines()}")
        assertTrue(h.reports.isEmpty())
        h.want(wanted(5))
        h.frame()
        assertNotNull(h.engine.duelMatch, "a new serial starts")
    }

    @Test
    fun aNonFiniteBoundaryIsRefusedWithoutSearchingMarks() {
        fun table(bad: Vec2) = Level(
            emptyList(),
            boundary = Polygon(listOf(Vec2(-0.3f, -0.2f), bad, Vec2(0.3f, 0.2f), Vec2(-0.3f, 0.2f))),
        )
        val bad = listOf(
            table(Vec2(Float.POSITIVE_INFINITY, -0.2f)),
            table(Vec2(0.3f, Float.NEGATIVE_INFINITY)),
            table(Vec2(Float.NaN, -0.2f)),
        )
        for (level in bad) {
            val h = DuelHost().start(duelist("a"), duelist("b"), level = level)
            h.want(wanted(1))
            h.frame()
            assertNull(h.engine.duelMatch)
            assertEquals(listOf("duel_refused serial=1 reason=NO_BOUNDARY"), h.duelLines(), "${level.boundary}")
        }
        // At a rearrange: the old arena is kept, and no marks_ms= shows a search.
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        val old = h.match.startA to h.match.startB
        for (level in bad) {
            h.clear()
            h.engine.level = level
            h.frame()
            assertEquals(listOf("duel_arena_kept reason=NO_BOUNDARY"), h.duelLines())
            assertEquals(old, h.match.startA to h.match.startB)
            assertEquals(MatchPhase.COUNTDOWN, h.match.phase)
        }
    }

    // --- T58 review: serials rise, and every duel that started ends with one duel_end ---

    @Test
    fun aSerialAtOrBelowTheHighestTakenIsIgnored() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(5))
        h.frame()
        h.frames(10)
        val running = h.match
        h.clear()
        for (old in listOf(3, 5, 4)) {
            h.want(wanted(old, seed = old.toLong()))
            h.frame()
        }
        assertTrue(h.duelLines().isEmpty(), "${h.duelLines()}")
        assertEquals(running.duel.timeMs + 3 * DUEL_FRAME_MS, h.match.duel.timeMs, "the same match goes on")
        h.want(wanted(6)) // A -> B -> A: B is a rematch, A again is ignored
        h.frame()
        h.want(wanted(5))
        h.frames(5)
        assertEquals(listOf("duel_end serial=5", "duel_start serial=6"), h.duelLines().map { it.split(' ').take(2).joinToString(" ") })
        assertEquals(6, h.lastRunning().serial)
    }

    @Test
    fun everyDuelThatStartedLogsOneDuelEnd() {
        val h = DuelHost().start(duelist("a"), duelist("b"))
        h.want(wanted(1))
        h.frame()
        h.want(wanted(2)) // a rematch
        h.frame()
        h.want(wanted(3, foe = "zz")) // a refused new serial ends the running one
        h.frame()
        h.want(wanted(4))
        h.frame()
        h.members = listOf(duelist("a")) // a fighter leaves
        h.refresh()
        h.frame()
        h.members = listOf(duelist("a"), duelist("b"))
        h.refresh()
        h.frame()
        h.want(wanted(5))
        h.frame()
        h.fight()
        h.throwOn = { id, e -> id == "a" && e == CharacterEvent.JUMPED } // a failure
        h.press(CharacterAction.Jump)
        h.frame()
        h.throwOn = null
        h.want(wanted(6))
        h.frame()
        h.want(null) // the slot drops it
        h.frame()
        val kinds = h.duelLines().filter { !it.startsWith("duel_round") && !it.startsWith("duel_fight") }
            .map { it.split(' ').take(2).joinToString(" ") }
        assertEquals(
            listOf(
                "duel_start serial=1", "duel_end serial=1",
                "duel_start serial=2", "duel_end serial=2", "duel_refused serial=3",
                "duel_start serial=4", "duel_refused serial=4", "duel_end serial=4",
                "duel_start serial=5", "duel_failed serial=5", "duel_end serial=5",
                "duel_start serial=6", "duel_end serial=6",
            ),
            kinds,
        )
    }
}
