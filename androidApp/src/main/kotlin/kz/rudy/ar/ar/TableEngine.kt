package kz.rudy.ar.ar

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.emote.EmoteSound
import kz.rudy.ar.core.pipeline.SurfaceBoundary
import kz.rudy.ar.core.scenario.ScenarioPlayer
import kz.rudy.ar.core.scenario.ScenarioStep
import kz.rudy.ar.core.scenario.Scenarios
import kz.rudy.ar.core.session.ArPort
import kz.rudy.ar.core.session.BillboardPoser
import kz.rudy.ar.core.session.CharacterAction
import kz.rudy.ar.core.session.CharacterCommand
import kz.rudy.ar.core.session.FrameInput
import kz.rudy.ar.core.session.GameLoop
import kz.rudy.ar.core.session.NodePose
import kz.rudy.ar.core.session.ScreenView
import kz.rudy.ar.core.session.TableHit
import kz.rudy.ar.core.session.TouchEvent
import kotlin.math.abs

// HAND-REVIEW: T54 — host-local copy for one brief: core/session/TableEngine.kt made by sed (package, `internal`), plus
// taps, picking and the grab (onTap, onTouch, the CastTable over ArPort, the picture sizes, spriteGone). The view runs on
// it until commit C deletes it for the :core file, which gets the same lines and runs the same tests (TableTouchPinTest
// here, TableTouchTest there).
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
// T54 — what a finger does after the anchor moved here from :androidApp (RudeusArView's tap handler, confirmSurface and
// its CastTable, and ar/CastTouch.kt, now CastTouch.kt in this package) as it was, and pinned by TableTouchTest, the same
// tests that ran against the host copy: the tap (pick a member or walk the active one, the depth edge on the way, the
// boundary growth), the screen-space pick, the grab, the lead and the release. The host maps its touch to a TouchEvent
// and answers ArPort (the table under a pixel, the depth on the way, the camera as a ScreenView when a touch needs a
// pick); it names no member, no level and no loop, and `loop` is internal. New and silent: onTap and onTouch do nothing
// before the anchor, and spriteGone forgets a picture's size, poser and drawn facing. Nothing here looks at tracking,
// as before: a walk set during a loss waits for it, and PAUSED still freezes and never resets.

/** Footprints mini-map: how often the host reports every member's physics state (`ArSlot.onCastState`) while debug chrome is on. */
internal const val CAST_STATE_PERIOD_MS = 100L

/** One character the engine keeps on the table: what physics and the emotes need. No pictures. */
internal class Member(val id: String, val ability: Ability, val emotes: EmoteSet)

/** The engine's view of one `ArSlot`: what the UI wants and where it listens. One immutable value per slot. */
internal class TableSlot(
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
    /** T54: a finger or a tap chose this member (`ArSlot.onActiveChanged`); the HUD makes it the active one. */
    val onActiveChanged: (String) -> Unit = {},
    /** T54: a tap grew the level's boundary (`ArSlot.onLevel`); the host's scans call the slot's own. */
    val onLevel: (Level) -> Unit = {},
)

/**
 * The game on the table from the moment the anchor exists: it holds the [GameLoop], locks the surface, keeps the
 * physics cast in step with the slot's members, knows whose buttons they are and applies each command once.
 * Main thread only: no lock, no coroutine. Callbacks run on the caller's stack and must not call back into the engine.
 * Lives as long as the AR view's composition (one session); nothing is carried to the next one.
 */
internal class TableEngine(
    /** The latest slot, read on every use (`currentSlot` in the host). */
    private val current: () -> TableSlot,
    /** The first slot's surface; followed until the anchor ([offerSurface]). */
    surface: Surface,
    /** Plays an emote's sound; called only when the slot that sent the command has `soundsOn`. */
    private val playEmoteSound: (EmoteSound) -> Unit,
    /** T54: what only the AR session can answer (the table under a pixel, the depth on the way, the camera as a screen). */
    private val port: ArPort,
    /** One `RudeusGate` line, as the host logged it. */
    private val log: (String) -> Unit,
    /** The host's FEET_DEPTH_BIAS (a Pixel number, passed to every [BillboardPoser]); 0f = off. */
    private val feetDepthBias: Float,
    /** The host's REAPPEAR_AFTER_TRACKING_MS: members stay hidden this long after the last untracked frame. */
    private val reappearAfterTrackingMs: Long,
    /** The host's WALK_DEPTH_SAMPLES: depth readings on the way of a tap. */
    private val walkDepthSamples: Int,
    /** The host's LEAD_DEPTH_SAMPLES: depth readings on the way of a finger's lead. */
    private val leadDepthSamples: Int,
    /** The host's SEPARATION_ENABLED cut switch (T32 cut line 4). */
    separate: Boolean = true,
) {
    /** The physics driver. No host reads it (T54); the tests read physics state through it. */
    internal val loop = GameLoop(separate = separate)

    /** The one the sprite and physics are built for: follows the slot until the anchor, then locked. */
    var surface: Surface = surface
        private set

    val profile: SurfaceProfile get() = SurfaceProfile.of(surface)

    /** The session's one anchor exists ([anchorPlaced]); never cleared. */
    var anchored = false
        private set

    /**
     * The level physics steps against: the scan reads it, and swaps a new one in between frames (the next step stands
     * on it; nobody moves, nothing resets). A plain get / set of `loop.level`; a tap that grows the boundary writes it
     * too ([onTap], then `TableSlot.onLevel`).
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
    private val touch = CastTouch(log) // T54: the fingers' bindings and the lead throttle
    private val quads = HashMap<String, Quad>() // T54: each member's picture size, for picking ([spriteBuilt])
    private val plane = FloatArray(4) // T54: projection scratch, one pick at a time (main thread)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)

    /** A member's picture on the table at size 1: the quad's width and height, metres. */
    private class Quad(val widthM: Float, val heightM: Float)

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
            touch.unbindMember(id)
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
     * T54: the picture is [widthM] x [heightM] at size 1; both > 0 make the member pickable (a member without a picture
     * is not picked, as before), anything else forgets the size.
     */
    fun spriteBuilt(id: String, facesRight: Boolean, widthM: Float = 0f, heightM: Float = 0f) {
        drawnRight[id] = facesRight
        posers.remove(id)
        if (widthM > 0f && heightM > 0f) quads[id] = Quad(widthM, heightM) else quads.remove(id)
    }

    /**
     * T54, composition time: the host dropped member [id]'s picture (the member left its slot). Its size, poser and drawn
     * facing are forgotten: it is not picked, and a picture built for it later starts as a new one.
     */
    fun spriteGone(id: String) {
        quads.remove(id)
        posers.remove(id)
        drawnRight.remove(id)
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

    /**
     * T54: a tap on the table at view pixel ([xPx], [yPx]); [uv] is the table under it (the host's hit test, which its
     * placement tap shares: a tap that misses the table is not reported). A tap on a member only makes it active; any
     * other tap walks the active member there, unless the depth on the way shows an edge (BLOCKED), and grows the
     * boundary to hold it first. [depthGapM] is the Depth API's reading at the tap, asked at most once, after the pick
     * and only for a tap that walks. Main thread, between frames. Before the anchor: nothing.
     */
    fun onTap(xPx: Float, yPx: Float, uv: Vec2, depthGapM: () -> Float?) {
        if (!anchored) return
        // T32: a tap on a member only makes it active (the finger grab normally consumes this tap first;
        // with one member on the table `grabbable` is always null and the tap walks it, as v1.0).
        val tapped = table.grabbable(xPx, yPx)
        if (tapped != null) {
            if (tapped != activeId()) current().onActiveChanged(tapped)
            return
        }
        val active = activeId() ?: return
        val from = loop.state(active)?.pos ?: return
        val depthGap = depthGapM()
        // Pixel 2026-11-03 — ARCore's polygon runs past the table (a tap there read 29 cm below the plane),
        // so "in the polygon" is not "on the table". A drop in depth on the way or at the tap refuses it; :core decides.
        // No depth reading (null) never refuses. The boundary is not touched. The character says it cannot go there
        // (the BLOCKED line), so a refused tap is not a dead tap.
        val gaps = port.depthGapsOnWay(from, uv, walkDepthSamples) + depthGap
        log("tap_depth_gap=$depthGap feet_h=${loop.state(active)?.h} way=$gaps id=$active")
        if (SurfaceBoundary.crossesEdge(gaps, profile)) {
            log("tap_past_edge u=${uv.u} v=${uv.v}")
            current().onCastEvent(active, CharacterEvent.BLOCKED)
        } else {
            confirmSurface(uv) // the boundary is convex, so the straight walk there stays inside it
            loop.walkTo(active, uv) // its STARTED_WALKING event is reported by the next frame's loop.onFrame
        }
    }

    /**
     * T54: one touch event after the anchor, from the host's raw touch listener (before its tap detector). True: the
     * stream belongs to a finger that leads a member, and the host's tap detector must not see it ([CastTouch]).
     * Before the anchor: false, nothing else.
     */
    fun onTouch(e: TouchEvent): Boolean {
        if (!anchored) return false
        return touch.onTouch(e, table)
    }

    /**
     * T25 — the plane polygon is only the table seen so far, so a tap the hit test accepted is table: it
     * grows the boundary instead of being refused. Only the tap: on the Pixel the live polygon ran past the end of the
     * table and the hull kept that (2026-11-03). On the floor the boundary stays null. Main thread, called per tap, never
     * per frame. One anchor per session stays: only the Level changes. T54: the host mirrors it from [level].
     */
    private fun confirmSurface(uv: Vec2) {
        if (loop.level.boundary == null) return
        val grown = SurfaceBoundary.grown(loop.level, standing = listOf(uv))
        if (grown === loop.level) return
        loop.level = grown
        current().onLevel(grown)
        log("boundary_grown n=${grown.boundary?.size} u=${uv.u} v=${uv.v}")
    }

    // T32: what CastTouch needs; the hit test is the port's with the tap's tolerance, the grab a screen-space quad test
    // (review fix (a)), the lead a boundary-clamped, edge-checked walkTo (review fix (b)).
    private val table = object : CastTable {
        private val feet = FloatArray(3)
        private val head = FloatArray(3)

        override fun hitUv(x: Float, y: Float): TableHit? = port.hitTable(x, y, profile.tapHeightToleranceM)

        override fun grabbable(x: Float, y: Float): String? {
            if (loop.cast.members.size < 2) return null // review fix (c): one character walks as in v1.0, no grab
            val ground = loop.cast.members.filter { it.body.state.mode == Mode.IDLE || it.body.state.mode == Mode.WALKING }
            val view = port.screenView() ?: return hitUv(x, y)?.let { hit -> nearestOnGround(ground, hit.uv) }
            val size = current().characterSize
            val margin = view.pickMarginPx
            var best: String? = null
            var bestD2 = Float.MAX_VALUE
            for (m in ground) {
                val s = m.body.state
                val sprite = quads[m.id] ?: continue
                if (!project(view, s.pos, s.h, feet)) continue
                if (!project(view, s.pos, s.h + sprite.heightM * size, head)) continue
                val halfW = pxPerM(view, feet[2]) * sprite.widthM * size * 0.5f + margin
                val cx = (feet[0] + head[0]) * 0.5f
                val cy = (feet[1] + head[1]) * 0.5f
                if (abs(x - cx) > halfW) continue
                if (y < minOf(feet[1], head[1]) - margin || y > maxOf(feet[1], head[1]) + margin) continue
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d2 < bestD2) { bestD2 = d2; best = m.id }
            }
            return best
        }

        /** The fallback before the view is laid out: the brief's table-point grab radius. */
        private fun nearestOnGround(ground: List<kz.rudy.ar.core.physics.CastMember>, uv: Vec2): String? {
            val reach = maxOf(profile.radiusAt(current().characterSize) * 1.5f, GRAB_MIN_M * profile.scale)
            return ground.minByOrNull { it.body.state.pos.distanceTo(uv) }?.takeIf { it.body.state.pos.distanceTo(uv) <= reach }?.id
        }

        override fun lead(id: String, hit: TableHit) {
            val from = loop.state(id)?.pos ?: return
            val target = loop.level.nearestInside(hit.uv) // inside the scanned hull (null boundary before a scan: unclamped)
            val gaps = port.depthGapsOnWay(from, target, leadDepthSamples) + hit.depthGapM
            if (SurfaceBoundary.crossesEdge(gaps, profile)) {
                log("lead_past_edge id=$id u=${target.u} v=${target.v} way=$gaps")
                return // the last target stays
            }
            loop.walkTo(id, target)
        }

        override fun onGrabbed(id: String) = current().onActiveChanged(id)
    }

    /** [out] = (x px, y px, eye depth m) of the plane point ([pos], [h]) in [view]; false when it is behind the camera. */
    private fun project(view: ScreenView, pos: Vec2, h: Float, out: FloatArray): Boolean {
        plane[0] = pos.u; plane[1] = h; plane[2] = pos.v; plane[3] = 1f
        mulMV(eye, view.planeToEye, plane)
        mulMV(clip, view.eyeToClip, eye)
        if (clip[3] <= 1e-4f) return false
        out[0] = (clip[0] / clip[3] + 1f) * 0.5f * view.widthPx
        out[1] = (1f - clip[1] / clip[3]) * 0.5f * view.heightPx
        out[2] = -eye[2]
        return true
    }

    /** Pixels per metre across the view at eye depth [depth] (P00 = 2 * focal / width). */
    private fun pxPerM(view: ScreenView, depth: Float): Float = view.eyeToClip[0] * view.widthPx * 0.5f / depth

    /** [out] = the column-major 4x4 [m] times [v], summed in android.opengl.Matrix.multiplyMV's order. */
    private fun mulMV(out: FloatArray, m: FloatArray, v: FloatArray) {
        for (i in 0 until 4) out[i] = m[i] * v[0] + m[4 + i] * v[1] + m[8 + i] * v[2] + m[12 + i] * v[3]
    }
}
