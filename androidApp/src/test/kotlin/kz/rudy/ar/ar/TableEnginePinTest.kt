package kz.rudy.ar.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.Choreographies
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.emote.EmotePlayer
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.EmoteSound
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.session.CharacterAction
import kz.rudy.ar.core.session.CharacterCommand
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Characterization tests of the table engine (T53): the surface lock, the cast sync, the active member and the
// commands, as they ran inside RudeusArView's closures. Written against the host-local copy first; the same file, with
// only the package line and the class name changed, runs against the copy in :core. Frames are driven here with
// `engine.loop.onFrame`, as the host's frame callback does. A log line with a float is asserted by its key and its
// integer fields only (the float text may differ on iOS).

private const val FRAME_MS = 16L

/** An arbitrary host clock origin (SystemClock.elapsedRealtime is never small on a phone). */
private const val T0 = 1_000_000L

private fun member(id: String, ability: Ability = Ability.JUMP, emotes: EmoteSet = EmoteSet.defaults()) = Member(id, ability, emotes)

private fun cmd(serial: Int, id: String, action: CharacterAction) = CharacterCommand(serial, id, action)

private fun hello(serial: Int, id: String = "a") = cmd(serial, id, CharacterAction.Emote(Emote.HELLO))

/** This slot with [command] instead of its own: what App composes when a button is pressed. */
private fun TableSlot.sending(command: CharacterCommand?) =
    TableSlot(members, activeId, command, soundsOn, onAnchorPlaced, onCastEvent)

/**
 * Drives one engine the way RudeusArView and rememberTableEngine do: [latest] is the slot of the last composition,
 * every slot callback and every frame event lands in [calls] in order, the log in [lines].
 */
private class Host(surface: Surface = Surface.TABLE) {
    val lines = ArrayList<String>()
    val sounds = ArrayList<EmoteSound>()
    val released = ArrayList<String>()
    val calls = ArrayList<String>()

    /** Camera-right in plane coords as the host answers it (any length); counted. */
    var right = Vec2(0f, -3f)
    var rightAsked = 0
    val cameraRight: () -> Vec2 = { rightAsked++; right }

    var now = T0
    var latest: TableSlot = slotOf()
    val engine = TableEngine({ latest }, surface, { sounds += it }, { released += it }, { lines += it })
    val loop get() = engine.loop

    fun slotOf(vararg members: Member, activeId: String? = null, soundsOn: Boolean = true) = TableSlot(
        members.toList(), activeId, command = null, soundsOn = soundsOn,
        onAnchorPlaced = { calls += "anchor" },
        onCastEvent = { id, e -> calls += "$id $e" },
    )

    fun anchor() = engine.anchorPlaced(cameraRight)

    fun sync() = engine.syncCast(cameraRight)

    /** [c] composed into the latest slot and offered from it, as the command effect does. */
    fun send(c: CharacterCommand?) {
        latest = latest.sending(c)
        engine.command(latest)
    }

    /** One AR frame [gapMs] after the previous one; its events go to the latest slot, as the host routes them. */
    fun frame(tracking: Boolean = true, gapMs: Long = FRAME_MS) {
        now += gapMs
        loop.onFrame(now, tracking, { id, e -> latest.onCastEvent(id, e) })
    }

    fun frames(n: Int, tracking: Boolean = true) = repeat(n) { frame(tracking) }

    fun clear() {
        lines.clear(); sounds.clear(); released.clear(); calls.clear(); rightAsked = 0
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

private fun assertNear(want: Vec2, got: Vec2?, what: String) {
    assertNotNull(got, what)
    assertTrue(abs(want.u - got.u) <= 1e-6f && abs(want.v - got.v) <= 1e-6f, "$what: $got, want $want")
}

class TableEnginePinTest {

    // --- Surface ---

    @Test
    fun surfaceFollowsTheSlotUntilTheAnchor() {
        val h = Host(Surface.TABLE)
        assertEquals(Surface.TABLE, h.engine.surface)
        assertSame(SurfaceProfile.TABLE, h.engine.profile)
        h.engine.offerSurface(Surface.FLOOR)
        assertEquals(Surface.FLOOR, h.engine.surface)
        assertSame(SurfaceProfile.FLOOR, h.engine.profile)
        h.engine.offerSurface(Surface.TABLE)
        h.engine.offerSurface(Surface.TABLE)
        assertEquals(Surface.TABLE, h.engine.surface)
        assertSame(SurfaceProfile.TABLE, h.engine.profile)
        assertEquals(Surface.FLOOR, Host(Surface.FLOOR).engine.surface, "the first slot's surface")
        assertFalse(h.engine.anchored)
        assertEquals(emptyList(), h.lines, "nothing is logged before the anchor")
    }

    @Test
    fun surfaceIsLockedAtTheAnchorAndAnIgnoredChangeIsLoggedOnce() {
        val h = Host(Surface.TABLE)
        h.latest = h.slotOf(member("a"))
        h.engine.offerSurface(Surface.FLOOR)
        h.anchor()
        assertTrue(h.engine.anchored)
        h.clear()
        h.engine.offerSurface(Surface.FLOOR) // the locked one: nothing to say
        repeat(3) { h.engine.offerSurface(Surface.TABLE) }
        h.engine.offerSurface(Surface.FLOOR)
        h.engine.offerSurface(Surface.TABLE) // the same wish again, after the locked one: still logged once
        assertEquals(Surface.FLOOR, h.engine.surface)
        assertSame(SurfaceProfile.FLOOR, h.engine.profile)
        assertEquals(listOf("surface_change_ignored anchored=FLOOR requested=TABLE"), h.lines)
    }

    @Test
    fun configureSizesTheLoopForTheLockedSurfaceAndLogsIt() {
        val h = Host(Surface.TABLE)
        h.engine.offerSurface(Surface.FLOOR)
        h.engine.configure(1.5f)
        assertEquals(SurfaceProfile.FLOOR.radiusAt(1.5f), h.loop.radiusM)
        assertEquals(PhysicsParams.forProfile(SurfaceProfile.FLOOR, 1.5f), h.loop.params)
        assertLine("character_size size= radius=", h.lines.single())
        h.latest = h.slotOf(member("a"))
        h.anchor()
        assertEquals(SurfaceProfile.FLOOR.radiusAt(1.5f), h.state("a").radiusM, "placed at the configured radius")
        h.engine.offerSurface(Surface.TABLE) // ignored: locked
        h.clear()
        h.engine.configure(1f)
        assertEquals(SurfaceProfile.FLOOR.radiusAt(1f), h.loop.radiusM, "the locked surface, not the latest wish")
        assertEquals(PhysicsParams.forProfile(SurfaceProfile.FLOOR, 1f), h.loop.params)
        assertEquals(SurfaceProfile.FLOOR.radiusAt(1f), h.state("a").radiusM, "a member on the table is resized")
        assertLine("character_size size= radius=", h.lines.single())
    }

    // --- Anchor and cast ---

    @Test
    fun nobodyIsOnTheTableBeforeTheAnchor() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"), activeId = "b")
        h.engine.offerSurface(Surface.TABLE)
        h.engine.configure(1f)
        h.engine.activeOffered("b")
        h.engine.abilities(h.latest.members)
        h.send(cmd(1, "a", CharacterAction.Jump))
        h.frames(5)
        assertFalse(h.engine.anchored)
        assertEquals(emptyList(), h.loop.ids)
        assertNull(h.engine.activeId())
        assertEquals(emptyList(), h.calls, "no PLACED, no physics event")
        assertEquals(0, h.rightAsked)
        assertEquals(emptyList(), h.released)
    }

    @Test
    fun anchorPlacedReportsTheAnchorThenPlacedPerMemberInSlotOrder() {
        val h = Host()
        h.latest = h.slotOf(member("c"), member("a"), member("b"))
        h.anchor()
        assertTrue(h.engine.anchored)
        assertEquals(listOf("anchor", "c PLACED", "a PLACED", "b PLACED"), h.calls)
        assertEquals(listOf("c", "a", "b"), h.loop.ids)
        assertEquals(3, h.lines.size)
        assertLine("cast_add id=c n=1 u= v=", h.lines[0])
        assertLine("cast_add id=a n=2 u= v=", h.lines[1])
        assertLine("cast_add id=b n=3 u= v=", h.lines[2])
        for (id in h.loop.ids) assertEquals(Mode.IDLE, h.state(id).mode, id)
    }

    @Test
    fun aSecondAnchorIsRefused() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        val cast = h.loop.cast
        h.clear()
        h.latest = h.slotOf(member("a"), member("b")) // someone waiting: the refused call does not place them either
        h.anchor()
        assertEquals(listOf("anchor_refused"), h.lines)
        assertEquals(emptyList(), h.calls, "no second onAnchorPlaced, no PLACED")
        assertSame(cast, h.loop.cast)
        assertEquals(0, h.rightAsked)
        assertTrue(h.engine.anchored)
    }

    @Test
    fun firstMemberStandsAtTheOriginLaterOnesNearTheActiveTowardCameraRight() {
        val h = Host()
        h.right = Vec2(0f, -3f) // screen-right in the plane, any length
        h.latest = h.slotOf(member("a"))
        h.anchor()
        assertEquals(Vec2.ZERO, h.state("a").pos, "the first member stands at the anchor")
        // a walks away, so "near the active" is not "near the origin"
        h.loop.walkTo("a", Vec2(0.2f, 0.1f))
        h.frames(400)
        val aPos = h.state("a").pos
        assertEquals(Vec2(0.2f, 0.1f), aPos)

        val forB = h.loop.spawnFor(aPos, h.right)
        h.latest = h.slotOf(member("a"), member("b"), activeId = "a")
        h.sync()
        assertEquals(forB, h.state("b").pos)
        val r = h.loop.radiusM
        assertNear(aPos + Vec2(0f, -1f) * (2.5f * r), h.state("b").pos, "b: 2.5 radii from a toward camera-right")

        val bPos = h.state("b").pos
        val forC = h.loop.spawnFor(bPos, h.right)
        h.latest = h.slotOf(member("a"), member("b"), member("c"), activeId = "b")
        h.sync()
        assertEquals(forC, h.state("c").pos, "near the active member b")
        assertTrue(h.state("c").pos.distanceTo(bPos) < h.state("c").pos.distanceTo(aPos))

        val forD = h.loop.spawnFor(aPos, h.right)
        h.latest = h.slotOf(member("a"), member("b"), member("c"), member("d"), activeId = "zz")
        h.sync()
        assertEquals(forD, h.state("d").pos, "an active id not on the table: near the first")
    }

    @Test
    fun cameraRightIsAskedOnlyWhenSomeoneJoins() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.configure(1f)
        assertEquals(0, h.rightAsked)
        h.anchor()
        assertEquals(2, h.rightAsked, "once per member that joins, the first too")
        repeat(10) { h.sync(); h.frame() }
        assertEquals(2, h.rightAsked)
        h.latest = h.slotOf(member("a"))
        h.sync()
        assertEquals(2, h.rightAsked, "a removal asks nothing")
        h.latest = h.slotOf(member("a"), member("c"))
        h.sync()
        assertEquals(3, h.rightAsked)
    }

    @Test
    fun aMemberAddedToTheSlotJoinsOnTheNextSyncWithItsAbility() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(2)
        h.clear()
        h.latest = h.slotOf(member("a"), member("b", Ability.FLY))
        assertFalse(h.loop.has("b"))
        h.frame()
        assertFalse(h.loop.has("b"), "a frame alone adds nobody")
        h.sync()
        assertEquals(listOf("a", "b"), h.loop.ids)
        assertEquals(Ability.FLY, h.state("b").ability)
        assertEquals(Mode.IDLE, h.state("b").mode)
        assertEquals(h.loop.radiusM, h.state("b").radiusM)
        assertEquals(listOf("b PLACED"), h.calls)
        assertLine("cast_add id=b n=2 u= v=", h.lines.single())
        h.calls.clear()
        h.frames(3)
        assertEquals(emptyList(), h.calls, "joining is no physics event")
    }

    @Test
    fun aMemberGoneFromTheSlotLeavesTheLoopAndItsFingerIsReleased() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"), member("c"))
        h.anchor()
        h.frames(2)
        h.clear()
        h.latest = h.slotOf(member("a"), member("c"))
        h.sync()
        assertEquals(listOf("a", "c"), h.loop.ids)
        assertNull(h.loop.state("b"))
        assertEquals(listOf("b"), h.released)
        assertEquals(listOf("cast_remove id=b"), h.lines)
        assertEquals(emptyList(), h.calls)
        assertEquals(0, h.rightAsked)
        h.clear()
        h.latest = h.slotOf(member("c"))
        h.sync()
        assertEquals(listOf("c"), h.loop.ids)
        assertEquals(listOf("a"), h.released)
        assertEquals(listOf("cast_remove id=a"), h.lines)
        h.frames(2)
        assertEquals(emptyList(), h.calls, "leaving is no physics event")
    }

    @Test
    fun syncCastWithNothingNewDoesNothing() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.frames(3)
        h.clear()
        val cast = h.loop.cast
        h.latest = h.slotOf(member("a"), member("b"), activeId = "b") // a new slot value with the same members
        repeat(5) { h.sync() }
        assertSame(cast, h.loop.cast)
        assertEquals(emptyList(), h.calls)
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.released)
        assertEquals(0, h.rightAsked)
    }

    @Test
    fun activeIsTheSlotsWhenOnTheTableElseTheFirst() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"), activeId = "b")
        assertNull(h.engine.activeId(), "nobody on the table yet")
        h.anchor()
        assertEquals("b", h.engine.activeId())
        h.latest = h.slotOf(member("a"), member("b"), activeId = null)
        assertEquals("a", h.engine.activeId())
        h.latest = h.slotOf(member("a"), member("b"), member("c"), activeId = "c")
        assertEquals("a", h.engine.activeId(), "c is in the slot but not on the table yet")
        h.sync()
        assertEquals("c", h.engine.activeId())
        h.latest = h.slotOf(member("a"), member("b"), member("c"), activeId = "zz")
        assertEquals("a", h.engine.activeId())
        // The active-id effect: one line per id it is offered, none for null, whether or not the id is on the table.
        h.clear()
        h.engine.activeOffered("b")
        h.engine.activeOffered(null)
        h.engine.activeOffered("zz")
        assertEquals(listOf("active id=b", "active id=zz"), h.lines)
    }

    @Test
    fun abilitiesFollowTheSlotAndUnknownIdsAreIgnored() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.abilities(listOf(member("a", Ability.ROLL))) // before the anchor: nobody to set
        assertEquals(emptyList(), h.loop.ids)
        h.anchor()
        assertEquals(Ability.JUMP, h.state("a").ability, "at add time the latest slot's ability")
        h.engine.abilities(listOf(member("a", Ability.ROLL), member("b", Ability.FLY), member("z", Ability.HIDE)))
        assertEquals(Ability.ROLL, h.state("a").ability)
        assertEquals(Ability.FLY, h.state("b").ability)
        assertFalse(h.loop.has("z"))
        assertEquals(listOf("a", "b"), h.loop.ids)
        val cast = h.loop.cast
        h.engine.abilities(listOf(member("a", Ability.ROLL), member("b", Ability.FLY)))
        assertSame(cast, h.loop.cast, "the same abilities again change nothing")
        assertEquals(listOf("cast_add", "cast_add"), h.lines.map { it.substringBefore(' ') }, "no line of its own")
    }

    // --- Commands ---

    @Test
    fun commandBeforeTheAnchorIsIgnoredAndLogged() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.send(cmd(1, "a", CharacterAction.Jump))
        h.send(hello(2))
        h.send(cmd(3, "a", CharacterAction.WalkForward))
        assertEquals(
            listOf(
                "command_ignored serial=1 id=a anchored=false",
                "command_ignored serial=2 id=a anchored=false",
                "command_ignored serial=3 id=a anchored=false",
            ),
            h.lines,
        )
        assertEquals(emptyList(), h.sounds)
        h.anchor()
        h.frames(30)
        val s = h.state("a")
        assertEquals(Mode.IDLE, s.mode, "nothing was queued")
        assertEquals(0f, s.h)
        assertNull(s.target)
        assertNull(h.loop.emotePose("a"))
        assertEquals(listOf("anchor", "a PLACED"), h.calls)
    }

    @Test
    fun commandForAMemberNotOnTheTableIsIgnoredAndLogged() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.clear()
        h.latest = h.slotOf(member("a"), member("b")) // b waits for the next sync
        h.send(cmd(4, "b", CharacterAction.Jump))
        h.send(hello(5, "zz"))
        assertEquals(listOf("command_ignored serial=4 id=b anchored=true", "command_ignored serial=5 id=zz anchored=true"), h.lines)
        assertEquals(emptyList(), h.sounds)
        assertFalse(h.loop.has("b"))
    }

    @Test
    fun jumpJumpsThatMemberOnly() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.frames(2)
        h.clear()
        h.send(cmd(1, "b", CharacterAction.Jump))
        assertEquals(Mode.JUMPING, h.state("b").mode)
        assertEquals(h.loop.params.jumpVelocityMps, h.loop.body("b")!!.verticalVelocity)
        assertEquals(Mode.IDLE, h.state("a").mode)
        assertLine("jump id=b ability=JUMP u= v=", h.lines.single())
        h.frame()
        assertEquals(listOf("b JUMPED"), h.calls, "reported by the next frame")
        assertTrue(h.state("b").h > 0f)
        assertEquals(0f, h.state("a").h)
    }

    @Test
    fun jumpThatPhysicsIgnoresIsLoggedWithTheModeBeforeIt() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("r", Ability.ROLL))
        h.anchor()
        h.frames(2)
        h.clear()
        val rolling = h.loop.body("r")
        h.send(cmd(1, "r", CharacterAction.Jump))
        assertEquals(listOf("jump_ignored id=r mode=IDLE ability=ROLL"), h.lines)
        assertSame(rolling, h.loop.body("r"))
        h.send(cmd(2, "a", CharacterAction.Jump))
        h.frames(3)
        assertEquals(Mode.JUMPING, h.state("a").mode, "in the air")
        val air = h.loop.body("a")
        h.lines.clear()
        h.send(cmd(3, "a", CharacterAction.Jump))
        assertEquals(listOf("jump_ignored id=a mode=JUMPING ability=JUMP"), h.lines)
        assertSame(air, h.loop.body("a"))
        h.calls.clear()
        h.frames(100)
        assertEquals(listOf("a LANDED"), h.calls, "one jump, one landing")
    }

    @Test
    fun walkForwardIsTenCentimetresTimesTheSurfaceScaleAlongFacing() {
        for (surface in Surface.entries) {
            val h = Host(Surface.TABLE)
            h.engine.offerSurface(surface)
            h.engine.configure(1f)
            h.latest = h.slotOf(member("a"))
            h.anchor()
            h.engine.offerSurface(if (surface == Surface.TABLE) Surface.FLOOR else Surface.TABLE) // ignored: locked
            h.frames(2)
            h.clear()
            val scale = SurfaceProfile.of(surface).scale
            h.send(cmd(1, "a", CharacterAction.WalkForward))
            var s = h.state("a")
            assertEquals(Vec2(1f, 0f), s.facing, "$surface: a new member faces +u")
            assertEquals(Vec2(0.10f * scale, 0f), s.target, "$surface")
            assertEquals(Mode.WALKING, s.mode)
            assertEquals(emptyList(), h.lines, "$surface: an accepted walk logs nothing")
            h.frame()
            assertEquals(listOf("a STARTED_WALKING"), h.calls, "$surface: reported by the next frame")
            h.frames(2_000)
            assertEquals(Vec2(0.10f * scale, 0f), h.state("a").pos, "$surface: arrived")
            // Face -v, then "go" again: along the new facing, from where it stands.
            h.loop.walkTo("a", Vec2(0.10f * scale, -0.05f * scale))
            h.frames(2_000)
            s = h.state("a")
            assertNear(Vec2(0f, -1f), s.facing, "$surface facing")
            h.send(cmd(2, "a", CharacterAction.WalkForward))
            assertNear(s.pos + Vec2(0f, -0.10f * scale), h.state("a").target, "$surface second target")
        }
    }

    @Test
    fun walkForwardOutsideTheBoundaryIsSkippedAndLogged() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.loop.level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.15f, 0.15f)) // +-7.5 cm: 10 cm ahead is out
        h.frames(2)
        h.clear()
        h.send(cmd(1, "a", CharacterAction.WalkForward))
        assertNull(h.state("a").target)
        assertEquals(Mode.IDLE, h.state("a").mode)
        assertLine("voice_walk_outside_boundary id=a u= v=", h.lines.single())
        h.frames(5)
        assertEquals(emptyList(), h.calls)
        assertEquals(Vec2.ZERO, h.state("a").pos)
        h.loop.level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.30f, 0.30f))
        h.send(cmd(2, "a", CharacterAction.WalkForward))
        assertEquals(Vec2(0.10f, 0f), h.state("a").target, "inside a bigger boundary it walks")
    }

    @Test
    fun emoteStartsTheChoreographyAndPlaysItsSoundOnlyWhenSoundsAreOn() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.clear()
        h.send(hello(1))
        assertEquals(listOf(EmoteSound.HELLO), h.sounds)
        assertEquals(listOf("emote id=a emote=HELLO source=default ms=1200"), h.lines)
        assertEquals(EmotePlayer.sample(Choreographies.HELLO, 0), h.loop.emotePose("a"))
        h.frames(10)
        assertEquals(EmotePlayer.sample(Choreographies.HELLO, 160), h.loop.emotePose("a"), "on the simulated clock")

        h.latest = h.slotOf(member("a"), soundsOn = false)
        h.send(cmd(2, "a", CharacterAction.Emote(Emote.ROAR)))
        assertEquals(listOf(EmoteSound.HELLO), h.sounds, "sounds off: no sound")
        assertEquals("emote id=a emote=ROAR source=default ms=1300", h.lines.last())
        assertEquals(EmotePlayer.sample(Choreographies.ROAR, 0), h.loop.emotePose("a"), "the running emote is replaced")

        // A silent choreography with sounds on: its NONE goes to the host, whose player plays nothing for it.
        h.latest = h.slotOf(member("a"), soundsOn = true)
        h.send(cmd(3, "a", CharacterAction.Emote(Emote.BYE)))
        assertEquals(listOf(EmoteSound.HELLO, EmoteSound.NONE), h.sounds)
        assertEquals(Mode.IDLE, h.state("a").mode, "physics never sees an emote")
        assertEquals(emptyList(), h.calls)
    }

    @Test
    fun emoteUsesTheMembersOfTheSlotThatSentIt() {
        val spark = EmoteSet(
            EmoteSet.SOURCE_SPARK,
            mapOf(Emote.HELLO to Choreographies.HELLO.copy(durationMs = 2_000, sound = EmoteSound.ROAR)),
        )
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.clear()
        // The sender holds a's spark set and has sounds off; the latest slot holds the defaults and has them on.
        h.engine.command(h.slotOf(member("a", emotes = spark), member("b"), soundsOn = false).sending(hello(1)))
        assertEquals(listOf("emote id=a emote=HELLO source=spark ms=2000"), h.lines)
        assertEquals(emptyList(), h.sounds)
        assertEquals(EmotePlayer.sample(spark[Emote.HELLO], 0), h.loop.emotePose("a"))
        h.engine.command(h.slotOf(member("a", emotes = spark), soundsOn = true).sending(hello(2)))
        assertEquals(listOf(EmoteSound.ROAR), h.sounds, "the sender's choreography's sound")
        // b is on the table and in the latest slot, but not in the slot that sent the command: nothing happens.
        h.clear()
        h.engine.command(h.slotOf(member("a")).sending(hello(3, "b")))
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.sounds)
        assertNull(h.loop.emotePose("b"))
    }

    @Test
    fun aNullCommandDoesNothing() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.send(null)
        assertEquals(emptyList(), h.lines, "before the anchor: not even command_ignored")
        h.anchor()
        h.frames(2)
        h.clear()
        val cast = h.loop.cast
        h.send(null)
        h.send(null)
        assertSame(cast, h.loop.cast)
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.sounds)
        h.frame()
        assertEquals(emptyList(), h.calls)
    }

    @Test
    fun theSameSerialOfferedTwiceIsAppliedOnce() {
        val h = Host()
        h.latest = h.slotOf(member("a"), member("b"))
        h.anchor()
        h.clear()
        h.send(hello(7))
        h.send(hello(7))
        assertEquals(listOf(EmoteSound.HELLO), h.sounds)
        assertEquals(1, h.lines.size)
        // The guard is on the serial alone: another id or action under the same serial is not applied either.
        h.send(cmd(7, "b", CharacterAction.Jump))
        assertEquals(Mode.IDLE, h.state("b").mode)
        assertEquals(1, h.lines.size)
        h.send(hello(8))
        assertEquals(listOf(EmoteSound.HELLO, EmoteSound.HELLO), h.sounds, "the next serial is applied")

        // A serial ignored before the anchor is not applied after it either (Compose would not re-run its effect).
        val early = Host()
        early.latest = early.slotOf(member("a"))
        early.send(hello(1))
        early.anchor()
        early.send(hello(1))
        assertEquals(emptyList(), early.sounds)
        assertEquals(listOf("command_ignored serial=1 id=a anchored=false"), early.lines.filter { !it.startsWith("cast_add") })
    }

    @Test
    fun aSerialOfferedAgainAfterNullIsApplied() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.clear()
        h.send(hello(3))
        h.send(null)
        h.send(hello(3))
        assertEquals(listOf(EmoteSound.HELLO, EmoteSound.HELLO), h.sounds)
        assertEquals(2, h.lines.count { it.startsWith("emote id=a") })
    }

    // --- Across a tracking loss ---

    @Test
    fun jumpDuringATrackingLossIsIgnoredNotQueued() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.clear()
        h.frame(tracking = false)
        assertEquals(Mode.PAUSED, h.state("a").mode)
        h.send(cmd(1, "a", CharacterAction.Jump))
        assertEquals(listOf("jump_ignored id=a mode=PAUSED ability=JUMP"), h.lines)
        repeat(5) { h.frame(tracking = false) }
        h.frame(tracking = false, gapMs = 3_000)
        var highest = 0f
        repeat(60) {
            h.frame()
            highest = maxOf(highest, h.state("a").h)
        }
        assertEquals(0f, highest, "nobody jumps after the resume")
        assertEquals(Mode.IDLE, h.state("a").mode)
        assertEquals(listOf("a PAUSED", "a RESUMED"), h.calls)
    }

    @Test
    fun walkForwardDuringATrackingLossWalksAfterResumeWithoutItsStartEvent() {
        val h = Host()
        h.latest = h.slotOf(member("a"))
        h.anchor()
        h.frames(3)
        h.clear()
        h.frame(tracking = false)
        h.send(cmd(1, "a", CharacterAction.WalkForward))
        assertEquals(Mode.PAUSED, h.state("a").mode, "the pause is kept")
        assertEquals(Vec2(0.10f, 0f), h.state("a").target, "the target is set")
        assertEquals(emptyList(), h.lines)
        h.frame(tracking = false, gapMs = 3_000)
        assertEquals(Vec2.ZERO, h.state("a").pos)
        h.frame() // the first frame back
        assertEquals(Mode.WALKING, h.state("a").mode)
        assertEquals(listOf("a PAUSED", "a RESUMED"), h.calls, "STARTED_WALKING is folded into RESUMED (T47 finding 1)")
        h.frames(400)
        assertEquals(Vec2(0.10f, 0f), h.state("a").pos)
        assertEquals(listOf("a PAUSED", "a RESUMED", "a ARRIVED"), h.calls)
    }

    // --- Evidence ---

    @Test
    fun logLinesKeepTheirKeys() {
        val h = Host(Surface.TABLE)
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.configure(1f)
        h.engine.activeOffered("b")
        h.send(cmd(1, "a", CharacterAction.Jump))
        h.anchor()
        h.anchor()
        h.engine.offerSurface(Surface.FLOOR)
        h.send(cmd(2, "a", CharacterAction.Jump))
        h.send(cmd(3, "a", CharacterAction.Jump))
        h.send(cmd(4, "b", CharacterAction.Emote(Emote.DANCE)))
        val b = h.state("b").pos
        h.loop.level = Level(emptyList(), boundary = Polygon.rect(b, 0.15f, 0.15f))
        h.send(cmd(5, "b", CharacterAction.WalkForward))
        h.latest = h.slotOf(member("a"))
        h.sync()
        h.send(cmd(6, "b", CharacterAction.Jump))
        val shapes = listOf(
            "character_size size= radius=",
            "active id=b",
            "command_ignored serial=1 id=a anchored=false",
            "cast_add id=a n=1 u= v=",
            "cast_add id=b n=2 u= v=",
            "anchor_refused",
            "surface_change_ignored anchored=TABLE requested=FLOOR",
            "jump id=a ability=JUMP u= v=",
            "jump_ignored id=a mode=JUMPING ability=JUMP",
            "emote id=b emote=DANCE source=default ms=2400",
            "voice_walk_outside_boundary id=b u= v=",
            "cast_remove id=b",
            "command_ignored serial=6 id=b anchored=true",
        )
        assertEquals(shapes.size, h.lines.size, h.lines.joinToString("\n"))
        for ((shape, line) in shapes.zip(h.lines)) assertLine(shape, line)
    }
}
