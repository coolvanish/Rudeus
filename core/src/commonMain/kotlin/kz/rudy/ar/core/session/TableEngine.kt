package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.EmoteSound

// T53 — moved here from :androidApp (ar/TableEngine.kt, split out of RudeusArView's closures) as it was, and pinned by
// TableEngineTest, the same tests that ran against the host copy: the surface lock, the size, the active member, the
// abilities, the commands, the cast sync and the anchor step. The Android host keeps the session, the anchor, the frame,
// taps and scans, and reads `loop` until S3b, T54 and S5 take those over. Two guards are new and cannot fire from the
// Android host (Compose keys and its one anchor already ensure them): a second anchorPlaced is refused
// (`anchor_refused`), and a serial offered twice in a row is applied once, so a host without Compose gets both rules.

/** One character the engine keeps on the table: what physics and the emotes need. No pictures. */
class Member(val id: String, val ability: Ability, val emotes: EmoteSet)

/** The engine's view of one `ArSlot`: what the UI wants and where it listens. One immutable value per slot. */
class TableSlot(
    /** In cast order; the first is the one Play started with. */
    val members: List<Member>,
    /** Whose taps and buttons (`ArSlot.activeId`); null = the first. */
    val activeId: String?,
    /** The last command, edge-triggered on its serial; null when nothing is pending. */
    val command: CharacterCommand?,
    /** The Settings sheet's Sounds toggle: an emote's sound plays only while true. */
    val soundsOn: Boolean,
    /** The session anchor exists. */
    val onAnchorPlaced: () -> Unit,
    /** Per member: PLACED from the engine, the physics transitions from the frame. */
    val onCastEvent: (String, CharacterEvent) -> Unit,
)

/**
 * The game on the table from the moment the anchor exists: it holds the [GameLoop], locks the surface, keeps the
 * physics cast in step with the slot's members, knows whose buttons they are and applies each command once.
 * Main thread only: no lock, no coroutine. Callbacks run on the caller's stack and must not call back into the engine.
 * Lives as long as the AR view's composition (one session); nothing is carried to the next one.
 */
class TableEngine(
    /** The latest slot, read on every use (`currentSlot` in the host). */
    private val current: () -> TableSlot,
    /** The first slot's surface; followed until the anchor ([offerSurface]). */
    surface: Surface,
    /** Plays an emote's sound; called only when the slot that sent the command has `soundsOn`. */
    private val playEmoteSound: (EmoteSound) -> Unit,
    /** A member left the table: the host releases its finger (CastTouch), until T54. */
    private val onMemberRemoved: (String) -> Unit,
    /** One `RudeusGate` line, as the host logged it. */
    private val log: (String) -> Unit,
    /** The host's SEPARATION_ENABLED cut switch (T32 cut line 4). */
    separate: Boolean = true,
) {
    /** The physics driver. Read by the host's frame, tap and scan code until S3b, T54 and S5 take them. */
    val loop = GameLoop(separate = separate)

    /** The one the sprite and physics are built for: follows the slot until the anchor, then locked. */
    var surface: Surface = surface
        private set

    val profile: SurfaceProfile get() = SurfaceProfile.of(surface)

    /** The session's one anchor exists ([anchorPlaced]); never cleared. */
    var anchored = false
        private set

    private var ignoredSurface: Surface? = null // logged once per requested value after the anchor

    /** The serial of the last command offered to [command] (null: none yet, or a slot without one). */
    private var lastOffered: Int? = null

    /**
     * Composition time, every pass: the slot's [wanted] surface. Followed until the anchor; after it a different one
     * is ignored and logged once per value (T14: the HUD hides the chips once anchored).
     */
    fun offerSurface(wanted: Surface) {
        if (!anchored) {
            surface = wanted
        } else if (surface != wanted && ignoredSurface != wanted) {
            ignoredSurface = wanted
            log("surface_change_ignored anchored=${surface} requested=${wanted}")
        }
    }

    /** T25: the size re-sizes physics mid-game, for the locked surface. Effect keyed on (surface, size). */
    fun configure(size: Float) {
        loop.configure(profile, size)
        log("character_size size=${size} radius=${loop.radiusM}")
    }

    /** Effect keyed on the slot's active id: one log line per new id. */
    fun activeOffered(id: String?) {
        id?.let { log("active id=$it") }
    }

    /** T32: abilities are per member (set again at add time; before the anchor the id is not in the loop yet). */
    fun abilities(members: List<Member>) {
        for (c in members) loop.setAbility(c.id, c.ability)
    }

    /**
     * T32: one command per serial (T31 edge trigger), from the slot [from] that sent it. A serial offered twice in a
     * row is applied once (T53; Compose keys already ensure it on Android). Jump keeps T21's `jump_ignored` (before
     * the anchor the body is IDLE, so a jump would fire on the frame the anchor appears: rejected); WalkForward is
     * T24's 10 cm x scale step along that member's facing; Emote starts the member's choreography on the simulated
     * clock and plays its sound.
     */
    fun command(from: TableSlot) {
        val serial = from.command?.serial
        if (serial != null && serial == lastOffered) return
        lastOffered = serial
        val c = from.command ?: return
        if (!anchored || !loop.has(c.id)) {
            log("command_ignored serial=${c.serial} id=${c.id} anchored=${anchored}")
            return
        }
        when (val action = c.action) {
            CharacterAction.Jump -> {
                val s = loop.state(c.id) ?: return
                if (!loop.jump(c.id)) log("jump_ignored id=${c.id} mode=${s.mode} ability=${s.ability}")
                else log("jump id=${c.id} ability=${s.ability} u=${s.pos.u} v=${s.pos.v}") // Pixel evidence
            }
            CharacterAction.WalkForward -> {
                val s = loop.state(c.id) ?: return
                val target = s.pos + s.facing * (0.10f * profile.scale)
                val boundary = loop.level.boundary
                if (boundary != null && !boundary.contains(target)) {
                    log("voice_walk_outside_boundary id=${c.id} u=${target.u} v=${target.v}")
                } else {
                    loop.walkTo(c.id, target) // its STARTED_WALKING event is reported by the next frame's loop.onFrame
                }
            }
            is CharacterAction.Emote -> {
                val member = from.members.firstOrNull { it.id == c.id } ?: return
                val choreo = member.emotes[action.emote]
                loop.emote(c.id, choreo)
                if (from.soundsOn) playEmoteSound(choreo.sound)
                log("emote id=${c.id} emote=${action.emote} source=${member.emotes.source} ms=${choreo.durationMs}")
            }
        }
    }

    /** T32: whose taps and buttons: the slot's active member when it is on the table, else the first. */
    fun activeId(): String? = current().activeId?.takeIf(loop::has) ?: loop.firstId

    /**
     * T32: the physics cast follows the slot's members. Main thread, from the anchor and from every anchored frame (a
     * list of at most Cast.MAX ids compared by id; no allocation when nothing changed). [cameraRight] (plane coords,
     * any length) is asked only when a member joins.
     */
    fun syncCast(cameraRight: () -> Vec2) {
        val wanted = current().members
        for (id in loop.ids) if (wanted.none { it.id == id }) {
            loop.remove(id)
            onMemberRemoved(id)
            log("cast_remove id=$id")
        }
        for (c in wanted) if (!loop.has(c.id)) {
            val near = activeId()?.let(loop::state)?.pos ?: Vec2.ZERO
            val pos = loop.spawnFor(near, cameraRight())
            loop.add(c.id, pos, c.ability)
            log("cast_add id=${c.id} n=${loop.ids.size} u=${pos.u} v=${pos.v}")
            current().onCastEvent(c.id, CharacterEvent.PLACED)
        }
    }

    /** The session's ONE anchor exists: the surface locks, the slot hears it, every waiting member is placed. */
    fun anchorPlaced(cameraRight: () -> Vec2) {
        if (anchored) {
            log("anchor_refused")
            return
        }
        anchored = true // the surface is now locked (sprite, physics, tolerance)
        val s = current()
        s.onAnchorPlaced()
        syncCast(cameraRight) // T32: every waiting member is placed now, each with onCastEvent(id, PLACED)
    }
}
