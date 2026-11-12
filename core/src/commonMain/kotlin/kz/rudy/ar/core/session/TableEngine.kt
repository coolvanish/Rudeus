package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.EmoteSound
import kz.rudy.ar.core.scenario.ScenarioPlayer
import kz.rudy.ar.core.scenario.ScenarioStep
import kz.rudy.ar.core.scenario.Scenarios

// T53 — moved here from :androidApp (ar/TableEngine.kt, split out of RudeusArView's closures) as it was, and pinned by
// TableEngineTest, the same tests that ran against the host copy: the surface lock, the size, the active member, the
// abilities, the commands, the cast sync and the anchor step. The Android host keeps the session, the anchor, the frame,
// taps and scans, and reads `loop` until S3b, T54 and S5 take those over. Two guards are new and cannot fire from the
// Android host (Compose keys and its one anchor already ensure them): a second anchorPlaced is refused
// (`anchor_refused`), and a serial offered twice in a row is applied once, so a host without Compose gets both rules.
// T56 — the frame step moved here from :androidApp (RudeusArView's onSessionUpdated and its scenario effect, and the
// per-sprite CharacterPoser of ar/CharacterNode.kt) as it was, and pinned by TableFrameTest, the same tests that ran
// against the host copy: the tracking edge, the scenario start and feed, physics, the cast-state report, the reappear
// timer and one BillboardPoser per member. The host builds a FrameInput from its camera and writes each NodePose into
// its node; its scan reads and swaps the level through [TableEngine.level] and [TableEngine.standing]. New and silent:
// nothing happens before the anchor (onFrame, trackingEdge, syncCast), and `scenario` keeps the last request it saw,
// so a host without Compose keys gets "once per request" too. PAUSED still freezes every member and never resets.

/** Footprints mini-map: how often the host reports every member's physics state (`ArSlot.onCastState`) while debug chrome is on. */
internal const val CAST_STATE_PERIOD_MS = 100L

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
    /** T25: the Bigger / Smaller step; the poses read the latest slot's on every frame. */
    val characterSize: Float = CharacterSize.DEFAULT,
    /** Debug chrome (footprints) on: only then is [onCastState] called. */
    val debug: Boolean = false,
    /** Camera and anchor TRACKING: true at the anchor, then on every change (the HUD's Paused scrim). */
    val onTracking: (Boolean) -> Unit = {},
    /** Every member's physics state, at most every [CAST_STATE_PERIOD_MS] while [debug] (the footprints mini-map). */
    val onCastState: (Map<String, CharacterState>) -> Unit = {},
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
    /** The host's FEET_DEPTH_BIAS (a Pixel number, passed to every [BillboardPoser]); 0f = off. */
    private val feetDepthBias: Float,
    /** The host's REAPPEAR_AFTER_TRACKING_MS: members stay hidden this long after the last untracked frame. */
    private val reappearAfterTrackingMs: Long,
    /** The host's SEPARATION_ENABLED cut switch (T32 cut line 4). */
    separate: Boolean = true,
) {
    /** The physics driver. After T56 read only by the host's tap and grab code, until T54. */
    val loop = GameLoop(separate = separate)

    /** The one the sprite and physics are built for: follows the slot until the anchor, then locked. */
    var surface: Surface = surface
        private set

    val profile: SurfaceProfile get() = SurfaceProfile.of(surface)

    /** The session's one anchor exists ([anchorPlaced]); never cleared. */
    var anchored = false
        private set

    /**
     * The level physics steps against: the scan reads it, and swaps a new one in between frames (the next step stands
     * on it; nobody moves, nothing resets). A plain get / set of `loop.level`: the tap's boundary growth still writes
     * `loop.level` until T54.
     */
    var level: Level
        get() = loop.level
        set(v) {
            loop.level = v
        }

    private var ignoredSurface: Surface? = null // logged once per requested value after the anchor

    /** The serial of the last command offered to [command] (null: none yet, or a slot without one). */
    private var lastOffered: Int? = null

    private var tracking: Boolean? = null // null until the anchor exists; then the last value reported to the slot
    private var replay: ScenarioPlayer? = null // the running scenario (T21 (c)); null when none runs
    private var replayStartMs = 0L // its start on the simulated clock
    private var lastScenario = 0 // the last request offered to [scenario]; 0 = nothing asked
    private var reappearAtMs = 0L // members hidden until then on the host clock (after a tracking loss)
    private var castStateAtMs = 0L // last onCastState report (footprints mini-map)
    private val posers = HashMap<String, BillboardPoser>() // one per member, made on first use
    private val drawnRight = HashMap<String, Boolean>() // the facing of each member's picture ([spriteBuilt])

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
     * T37: where every member stands, in cast order, for the scan's merge (the object under a character never changes)
     * and its boundary fold; one default state while nobody is on the table.
     */
    fun standing(): List<CharacterState> = loop.cast.members.map { it.body.state }.ifEmpty { listOf(loop.state) }

    /**
     * T32: the physics cast follows the slot's members. Main thread, from the anchor and from every anchored frame (a
     * list of at most Cast.MAX ids compared by id; no allocation when nothing changed). [cameraRight] (plane coords,
     * any length) is asked only when a member joins. Before the anchor it does nothing (T56).
     */
    fun syncCast(cameraRight: () -> Vec2) {
        if (!anchored) return
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

    /**
     * The session's ONE anchor exists: the surface locks, the slot hears it, every waiting member is placed, and then
     * the slot hears that it is tracking (the order the HUD always saw).
     */
    fun anchorPlaced(cameraRight: () -> Vec2) {
        if (anchored) {
            log("anchor_refused")
            return
        }
        anchored = true // the surface is now locked (sprite, physics, tolerance)
        tracking = true
        val s = current()
        s.onAnchorPlaced()
        syncCast(cameraRight) // T32: every waiting member is placed now, each with onCastEvent(id, PLACED)
        s.onTracking(true)
    }

    /**
     * Composition time: the host built a picture for member [id] that looks to its own right when [facesRight]. That
     * member's pose starts unflipped again, as a new sprite's poser always did. A member never announced faces left.
     */
    fun spriteBuilt(id: String, facesRight: Boolean) {
        drawnRight[id] = facesRight
        posers.remove(id)
    }

    /**
     * T21 (c): one increment = one replay from spawn (restartAtSpawn); a replay in progress is restarted. Effect keyed
     * on the slot's `scenarioRequest`; the same request offered twice in a row starts once, 0 = nothing asked. Before
     * the anchor it is rejected and not retried.
     */
    fun scenario(request: Int) {
        if (request == lastScenario) return
        lastScenario = request
        if (request == 0) return
        if (!anchored) {
            log("scenario_rejected anchored=false")
            return
        }
        val script = Scenarios.DAY5.scaled(profile.scale)
        loop.restartAtSpawn() // every run starts at spawn (FIRST member only, T32): a FLY run ends past the gap and would skip the cup beat
        val startMs = loop.simMs // simulated time, see the T21 header
        replay = ScenarioPlayer(script, startMs)
        replayStartMs = startMs
        log("scenario_start id=${script.id} steps=${script.steps.size} surface=${surface} sim_ms=$startMs")
    }

    /** Camera and anchor TRACKING ([tracking]) or not: the slot hears every change once. Before the anchor: nothing. */
    fun trackingEdge(tracking: Boolean) {
        if (!anchored) return
        if (tracking != this.tracking) {
            this.tracking = tracking
            current().onTracking(tracking)
        }
    }

    /**
     * One AR frame after the anchor: the tracking edge, the cast sync, the scenario's due taps, physics (PAUSED while
     * not tracking: nothing steps, nothing resets), the cast-state report, the reappear timer, then one pose per member
     * in cast order. Empty while not tracking (the host's anchor hides its children) and before the anchor.
     */
    fun onFrame(input: FrameInput): List<NodePose> {
        if (!anchored) return emptyList()
        trackingEdge(input.tracking)
        val now = input.nowMs
        val tracking = input.tracking
        // T21 (c): scripted taps due on the simulated clock go to the loop like real taps, inside the
        // loop's frame (after resume, before the step: review fix). The lambda exists only while a script runs.
        syncCast { input.camera.right } // T32: members added or removed since the last frame
        val player = replay
        val scripted: (() -> Unit)? = if (player == null) null else {
            {
                val first = player.issued
                val hero = loop.firstId // T32: the scenario drives the FIRST member only
                player.due(loop.simMs).forEachIndexed { i, step ->
                    when (step) {
                        is ScenarioStep.WalkTo -> if (hero != null) loop.walkTo(hero, step.target)
                        is ScenarioStep.Jump -> if (hero == null || !loop.jump(hero)) {
                            log("jump_ignored mode=${loop.state.mode} ability=${loop.state.ability} scenario=true")
                        }
                    }
                    log("scenario_step idx=${first + i} at_ms=${step.atMs}")
                }
                if (player.finished) {
                    replay = null
                    log("scenario_done ms=${loop.simMs - replayStartMs}")
                }
            }
        }
        val cast = loop.onFrame(now, tracking, { id, e -> current().onCastEvent(id, e) }, beforeStep = scripted)
        // footprints mini-map — the HUD never saw physics positions, so its dot stood still while the sprite
        // walked. Report every member's state about 10x a second while debug chrome is on; a small map, never per frame.
        if (current().debug && now - castStateAtMs >= CAST_STATE_PERIOD_MS) {
            castStateAtMs = now
            current().onCastState(cast.members.associate { it.id to it.body.state })
        }
        if (!tracking) reappearAtMs = now + reappearAfterTrackingMs
        // While not tracking the camera pose is meaningless and the AnchorNode hides its children.
        if (!tracking) return emptyList()
        val cameraLocal = input.camera.pos
        val cameraHeightM = input.camera.heightM
        val visible = now >= reappearAtMs
        val poses = ArrayList<NodePose>(cast.members.size)
        for (m in cast.members) {
            val p = poser(m.id).pose(
                m.body.state, cameraLocal, cameraHeightM, loop.walkPhaseMs(m.id),
                verticalVelocity = m.body.verticalVelocity,
                sinceLandedMs = now - loop.landedAtMs(m.id),
                size = current().characterSize,
                emotePose = loop.emotePose(m.id),
            )
            poses += if (visible) p else p.copy(visible = false)
        }
        return poses
    }

    /** Member [id]'s poser: made on first use and again after [spriteBuilt], at the locked surface's character height. */
    private fun poser(id: String): BillboardPoser =
        posers.getOrPut(id) { BillboardPoser(id, profile.characterHeightM, drawnRight[id] ?: false, feetDepthBias) }
}
