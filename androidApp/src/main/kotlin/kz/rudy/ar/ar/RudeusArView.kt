package kz.rudy.ar.ar

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.rudy.ar.ArSlot
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.scenario.ScenarioPlayer
import kz.rudy.ar.core.scenario.ScenarioStep
import kz.rudy.ar.core.scenario.Scenarios
import kz.rudy.ar.scan.FootprintOverlay
import kz.rudy.ar.scan.GRAB_MAX_TRIES
import kz.rudy.ar.scan.ScanPrompts
import kz.rudy.ar.scan.TableScanner
import kz.rudy.ar.scan.footprintMaterials
import kz.rudy.ar.scan.grabScanFrame
import kz.rudy.ar.scan.runTableScan
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.ScrimPill
import kz.rudy.ar.ui.t
import kotlin.math.abs

// HAND-REVIEW: minimal AR host for the Day 1/3 gates. Plane detection + ONE anchor per session (locked decision).
// This file is the single owner of the session anchor.
// HAND-REVIEW: T01 Stream C — the `probe.*`, `recorder.*` and GateRecordButton lines are Day 1 gate probes
// (see GateProbes.kt). They only log to tag RudeusGate and toggle ARCore recording; remove after the gate.
// HAND-REVIEW: T02 — (1) the first tap creates the anchor only once the hit plane has been TRACKING for
// ANCHOR_PLANE_SETTLE_MS (PlaneSettle.kt, Day 1 drift measurement); earlier taps show a hint. (2) The character is
// a billboard PlaneNode child of the AnchorNode (CharacterNode.kt). (3) onSessionUpdated drives :core Physics via
// GameLoop (dt clamped to 50 ms) and writes the node transform; camera/anchor not TRACKING -> Physics.pause, back to
// TRACKING -> Physics.resume; nothing is ever reset. All per-frame state lives in plain holders, not Compose state.
// HAND-REVIEW: T14 — the view implements the ArSlot contract (shared/.../ArSlot.kt). (1) The surface (table/floor)
// sizes the sprite, the physics and the walk-tap tolerance via SurfaceProfile; it is LOCKED at anchor time — a later
// slot.surface change is logged and ignored (the HUD hides the chips once anchored). (2) Callbacks are read through
// rememberUpdatedState so a new slot never recreates the ARSceneView; they are edge-triggered from plain holders.
// (3) A new sprite (bytes or surface) re-keys the character node, so the old node is detached before the old
// Texture/MaterialInstance are destroyed in the DisposableEffect (Compose forgets deeper slots first).
// HAND-REVIEW: T04 — scan-to-level. `slot.scanRequest` (one increment = one scan, from the Scan/Rescan button, never
// per frame) sets a plain `scanPending` flag; the NEXT onSessionUpdated grabs that frame (scan/ScanCapture.kt:
// camera image, Camera.getPose, image intrinsics, anchor pose, anchor plane polygon) on the main thread and launches
// the IO half (JPEG -> SAM via the host-only TableScanner -> :core SceneBuilder). The loop keeps stepping the old
// level meanwhile; the new Level is swapped into `loop.level` on the main thread, reported through `onLevel`, and the
// scan is answered exactly once through `onScanned` (count, or null: no anchor, not tracking, busy, no frame, error).
// The anchor's Plane is kept (`anchorPlane`) so the scan can read its polygon as the level boundary (cliffs) — on
// the TABLE only: a floor has no edge and ARCore's floor patches are partial, so FLOOR scans pass no plane. The
// debug overlay (scan/FootprintOverlay.kt) lives under the AnchorNode and is composed only while `slot.debug`.
// HAND-REVIEW: T04 review fixes — (1) every request is answered exactly once: `scanOwed` counts the requests not yet
// answered; a request while a scan is pending or running joins it and the running scan answers all of them with its
// result (no instant NO_PLANE during a tracking blip). (2) A frame without a camera image yet (NotYetAvailable) keeps
// the request pending for up to GRAB_MAX_TRIES frames. (3) `scanBusy` is cleared in `finally`, so a cancelled or
// failed scan can never leave Rescan dead for the session. (4) Walk taps outside `loop.level.boundary` are ignored:
// the boundary is a snapshot of the plane polygon at scan time, while hit tests use the live, growing polygon.
// HAND-REVIEW: T21 — (a) occlusion: `sessionConfiguration` decides ONCE per session from `isDepthModeSupported`
// (Occluders.kt `occlusionFor`): DEPTH turns on SceneView's depth-aware camera stream (`rememberARCameraStream` +
// `ARCameraStream.isDepthOcclusionEnabled`, 4.31.0), BOXES composes invisible depth-writing nodes per LevelObject
// under the AnchorNode (Occluders.kt), NONE does nothing; logged as `occlusion=... depth_supported=...`.
// (b) jump + ability: `slot.ability` -> loop.setAbility; each `slot.jumpRequest` increment -> loop.jump() (one
// Physics.jump; ignored by physics while PAUSED / airborne / ROLL -> `jump_ignored`). The poser gets the body's
// vertical velocity and the time since LANDED for the squash-stretch. (c) scenario replay (debug HUD): each
// `slot.scenarioRequest` increment puts the body back at `level.spawn` (`GameLoop.restartAtSpawn`, a debug replay,
// not a tracking reset: PAUSED still never resets) and starts a :core ScenarioPlayer over `Scenarios.DAY5` (scaled by
// the locked surface profile) on the SIMULATED clock `GameLoop.simMs` (the clamped dt physics actually stepped), so a
// tracking stall or a frame hitch cannot move a scripted tap out of its 0.2-0.3 s beat window; onSessionUpdated feeds
// the due steps to the loop while tracking (`scenario_step`, `scenario_done ms=` in simulated time). The player is a
// plain holder; a scan meanwhile just swaps the level, the script goes on. A request before the anchor exists is
// dropped (`scenario_rejected`).
// HAND-REVIEW: T24 — voice "go": each `slot.walkForwardRequest` increment is one loop.walkTo 10 cm x profile.scale
// along the character's `facing`, from where it stands; a target outside `loop.level.boundary` (the scanned snapshot,
// same rule as walk taps) is skipped and logged `voice_walk_outside_boundary`; before the anchor it is dropped.

/** How long the "wait, finding the table" hint stays after a premature tap. */
private const val HINT_VISIBLE_MS = 1_500L

/**
 * HAND-REVIEW: after a tracking loss (camera covered, too little texture) ARCore re-localises and corrects the anchor
 * pose over the next frames; on the Pixel the dino visibly slid ~20 cm when the hand came off the camera. Keep the
 * character hidden this long after tracking returns, so the correction happens while it is invisible.
 */
private const val REAPPEAR_AFTER_TRACKING_MS = 800L

/**
 * HAND-REVIEW: T03 — the stylize prompt fixes no facing direction, so a generated sprite is assumed to face left like
 * the demo dino; CharacterPoser flips it by walking direction. Check on the Pixel: a sprite drawn facing right walks
 * backwards. Per-sprite detection is a follow-up, not a T03 item.
 */
internal const val GENERATED_SPRITE_FACES_RIGHT = false

/**
 * Plain holders for the slot-facing state written by the frame callback and the tap handler (main thread only).
 * `surface` is the one the sprite and physics are built for; `anchored` freezes it.
 */
private class SlotState(var surface: Surface) {
    var anchored = false
    var ignoredSurface: Surface? = null // logged once per requested value after the anchor
    var surfaceReady = false
    var tracking: Boolean? = null // null until the anchor exists
    val profile: SurfaceProfile get() = SurfaceProfile.of(surface)
}

/**
 * The AR view behind Scanning and Play. [slot] carries the sprite, the surface and the HUD callbacks (ArSlot.kt):
 * `onSurfaceReady` once a settled upward plane exists (false again if it goes away before the anchor),
 * `onAnchorPlaced` + `onCharacterEvent(PLACED)` on the first accepted tap, `onTracking` on every change after that,
 * `onCharacterEvent` for each physics transition. The ARSceneView, the session and the anchor survive slot changes.
 * [scanner] (T04) runs the SAM call and records the scan; null (no host) answers every scan request with failure.
 */
@Composable
fun RudeusArView(modifier: Modifier = Modifier, slot: ArSlot, scanner: TableScanner? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val currentSlot by rememberUpdatedState(slot)
    // Plain holders, not state: a Frame arrives every ~16 ms and must not recompose the tree.
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val anchorRef = remember { arrayOfNulls<Anchor>(1) } // mirror of `anchor` for the frame callback
    val anchorPlane = remember { arrayOfNulls<Plane>(1) } // the plane the anchor was created on (scan boundary)
    val characterNode = remember { arrayOfNulls<PlaneNode>(1) }
    val loop = remember { GameLoop() }
    val scanPending = remember { booleanArrayOf(false) } // set by a scan request, consumed by the next frame
    val scanBusy = remember { booleanArrayOf(false) } // one scan at a time; a request meanwhile joins it
    val scanOwed = remember { intArrayOf(0) } // requests not yet answered through onScanned
    val scanTries = remember { intArrayOf(0) } // frames the pending request has waited for a camera image
    val scanCount = remember { intArrayOf(0) }
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf<Level?>(null) } // written once per scan (overlay), never per frame
    val footprintMaterials = remember(materialLoader) { footprintMaterials(materialLoader) }
    val reappearAtMs = remember { longArrayOf(0L) } // character hidden until then (after a tracking loss)
    val settle = remember { PlaneSettle() }
    val slotState = remember { SlotState(slot.surface) }
    if (!slotState.anchored) {
        slotState.surface = slot.surface
    } else if (slotState.surface != slot.surface && slotState.ignoredSurface != slot.surface) {
        slotState.ignoredSurface = slot.surface
        Log.i(GATE_TAG, "surface_change_ignored anchored=${slotState.surface} requested=${slot.surface}")
    }
    val surface = slotState.surface
    val profile = slotState.profile
    LaunchedEffect(surface) { loop.configure(profile) } // main thread, same as the frame callback
    val sprite = remember(engine, materialLoader, slot.spritePng, surface) {
        val png = slot.spritePng
        if (png != null) spriteFromBytes(engine, materialLoader, context.assets, png, profile.characterHeightM, GENERATED_SPRITE_FACES_RIGHT)
        else spriteFromAssets(engine, materialLoader, context.assets, profile.characterHeightM)
    }
    // Never destroys the sprite in use: the effect for the previous one runs only when a new sprite was remembered,
    // after the node keyed on the old sprite has been detached (see the T14 header comment).
    DisposableEffect(sprite) { onDispose { sprite.destroy(engine, materialLoader) } }
    val poser = remember(sprite) { CharacterPoser(sprite) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var showHint by remember { mutableStateOf(false) }
    var hintTaps by remember { mutableIntStateOf(0) } // each premature tap restarts the hint timer
    val probe = remember { GateProbe() }
    val recorder = rememberARRecorder()
    // T21 (a): the camera stream is ours so `sessionConfiguration` can flip depth occlusion once depth support is known.
    val cameraStream = rememberARCameraStream(materialLoader)
    var occlusion by remember { mutableStateOf(Occlusion.NONE) } // written once per session, never per frame
    val occluderMaterial = remember(materialLoader) { materialLoader.createOcclusionInstance() }
    // T21 (c): the running scenario, plain holders (main thread: the LaunchedEffect below and the frame callback).
    val scenario = remember { arrayOfNulls<ScenarioPlayer>(1) }
    val scenarioStartMs = remember { longArrayOf(0L) }

    LaunchedEffect(hintTaps) {
        if (hintTaps > 0) {
            showHint = true
            delay(HINT_VISIBLE_MS)
            showHint = false
        }
    }
    /** Answers every request owed so far with [objects] (exactly one onScanned per request). Main thread. */
    fun answerOwed(objects: Int?) {
        val n = scanOwed[0]
        scanOwed[0] = 0
        repeat(n) { currentSlot.onScanned(objects) }
    }
    // A scan request is armed here and fulfilled by the next frame (acquireCameraImage wants the current frame).
    // Without an anchor or a host nothing can ever scan: answered at once. Otherwise the request is owed; if a scan
    // is already pending or running it joins that one and gets its result.
    LaunchedEffect(slot.scanRequest) {
        if (slot.scanRequest == 0) return@LaunchedEffect
        if (anchorRef[0] == null || scanner == null) {
            Log.i(GATE_TAG, "scan_rejected anchored=${anchorRef[0] != null} host=${scanner != null}")
            currentSlot.onScanned(null)
            return@LaunchedEffect
        }
        scanOwed[0]++
        if (!scanBusy[0] && !scanPending[0]) {
            scanPending[0] = true
            scanTries[0] = 0
        }
    }
    // T21 (b): the ability is state, applied whenever it changes (before the anchor too); a jump is edge-triggered.
    LaunchedEffect(slot.ability) { loop.setAbility(slot.ability) }
    LaunchedEffect(slot.jumpRequest) {
        if (slot.jumpRequest == 0) return@LaunchedEffect
        // Review fix: before the anchor the body is IDLE, so a jump would fire on the frame the anchor appears.
        if (anchorRef[0] == null) {
            Log.i(GATE_TAG, "jump_ignored anchored=false")
        } else if (!loop.jump()) {
            Log.i(GATE_TAG, "jump_ignored mode=${loop.state.mode} ability=${loop.state.ability}")
        }
    }
    // T24: one voice "go" = one short step forward (the mic only shows once the character is placed).
    LaunchedEffect(slot.walkForwardRequest) {
        if (slot.walkForwardRequest == 0) return@LaunchedEffect
        if (anchorRef[0] == null) {
            Log.i(GATE_TAG, "voice_walk_rejected anchored=false")
            return@LaunchedEffect
        }
        val s = loop.state
        val target = s.pos + s.facing * (0.10f * slotState.profile.scale)
        val boundary = loop.level.boundary
        if (boundary != null && !boundary.contains(target)) {
            Log.i(GATE_TAG, "voice_walk_outside_boundary u=${target.u} v=${target.v}")
            return@LaunchedEffect
        }
        loop.walkTo(target) // its STARTED_WALKING event is reported by the next frame's loop.onFrame
    }
    // T21 (c): one increment = one replay from spawn (restartAtSpawn); a replay in progress is restarted.
    LaunchedEffect(slot.scenarioRequest) {
        if (slot.scenarioRequest == 0) return@LaunchedEffect
        if (anchorRef[0] == null) {
            Log.i(GATE_TAG, "scenario_rejected anchored=false")
            return@LaunchedEffect
        }
        val script = Scenarios.DAY5.scaled(slotState.profile.scale)
        loop.restartAtSpawn() // every run starts at spawn: a FLY run ends past the gap and would skip the cup beat
        val startMs = loop.simMs // simulated time, see the T21 header
        scenario[0] = ScenarioPlayer(script, startMs)
        scenarioStartMs[0] = startMs
        Log.i(GATE_TAG, "scenario_start id=${script.id} steps=${script.steps.size} surface=${slotState.surface} sim_ms=$startMs")
    }
    /**
     * Main thread, from onSessionUpdated while a request is pending: grab this frame, then build the level on IO and
     * swap it in on return. Returns true when the request stays pending for the next frame (no image yet).
     */
    fun startScan(frame: Frame, a: Anchor, tracking: Boolean): Boolean {
        val host = scanner
        if (host == null || !tracking) {
            Log.i(GATE_TAG, "scan_skipped tracking=$tracking host=${host != null}")
            answerOwed(null)
            return false
        }
        // The level boundary comes from the anchor plane on the table only (see the T04 header).
        val plane = if (slotState.surface == Surface.FLOOR) null else anchorPlane[0]
        val attempt = grabScanFrame(frame, a, plane)
        val grab = attempt.grab
        if (grab == null) {
            if (attempt.retry && ++scanTries[0] < GRAB_MAX_TRIES) return true
            Log.i(GATE_TAG, "scan_skipped no_image tries=${scanTries[0]}")
            answerOwed(null)
            return false
        }
        scanBusy[0] = true
        val ref = "scan-" + (++scanCount[0]).toString().padStart(2, '0')
        val previous = loop.level
        val profile = slotState.profile
        scope.launch {
            var answer: Int? = null
            try {
                val outcome = runTableScan(grab, host, ScanPrompts.of(profile.surface), previous, profile, ref)
                val built = outcome.level
                if (built != null) {
                    loop.level = built // main thread: the next frame already steps against the new level
                    level = built
                    currentSlot.onLevel(built)
                    answer = built.objects.size
                    host.recordScan(built.objects.size, outcome.ms)
                }
            } finally {
                scanBusy[0] = false
                // On cancellation (the view left composition) the slot is gone with its session: nobody to answer.
                if (isActive) answerOwed(answer)
            }
        }
        return false
    }

    Box(modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            cameraStream = cameraStream,
            planeRenderer = anchor == null,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                config.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                probe.onDepthConfigured(depthSupported, config.depthMode)
                // T21 (a): Plan A = the depth-aware camera stream; Plan B = Occluders under the AnchorNode (Occluders.kt).
                val chosen = occlusionFor(depthSupported)
                cameraStream.isDepthOcclusionEnabled = chosen == Occlusion.DEPTH
                // HAND-REVIEW: T21 review fix — in BOXES mode the camera quad must be drawn BEFORE the depth-only
                // occluders (its default priority 7 draws after their 4 and is depth-tested, which would leave black
                // cup/book shapes). Priority 0 = first; SceneView's depth setter only touches it when enabling depth.
                if (chosen == Occlusion.BOXES) cameraStream.setPriority(0)
                if (occlusion != chosen) occlusion = chosen
                Log.i(GATE_TAG, "occlusion=${chosen.name.lowercase()} depth_supported=$depthSupported")
            },
            onSessionResumed = { probe.onSessionResumed() },
            onSessionUpdated = { session, frame ->
                latestFrame[0] = frame
                recorder.recordFrame(session) // AtomicReference.set; no-op unless recording
                probe.onFrame(session)
                val now = SystemClock.elapsedRealtime()
                val a = anchorRef[0]
                if (a == null) {
                    settle.onFrame(frame, now)
                    val ready = settle.anySettled(now)
                    if (ready != slotState.surfaceReady) {
                        slotState.surfaceReady = ready
                        currentSlot.onSurfaceReady(ready)
                    }
                } else {
                    val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
                        a.trackingState == TrackingState.TRACKING
                    if (tracking != slotState.tracking) {
                        slotState.tracking = tracking
                        currentSlot.onTracking(tracking)
                    }
                    if (scanPending[0]) scanPending[0] = startScan(frame, a, tracking)
                    // T21 (c): scripted taps due on the simulated clock go to the loop like real taps, inside the
                    // loop's frame (after resume, before the step: review fix). The lambda exists only while a script runs.
                    val player = scenario[0]
                    val scripted: (() -> Unit)? = if (player == null) null else {
                        {
                            val first = player.issued
                            player.due(loop.simMs).forEachIndexed { i, step ->
                                when (step) {
                                    is ScenarioStep.WalkTo -> loop.walkTo(step.target)
                                    is ScenarioStep.Jump -> if (!loop.jump()) {
                                        Log.i(GATE_TAG, "jump_ignored mode=${loop.state.mode} ability=${loop.state.ability} scenario=true")
                                    }
                                }
                                Log.i(GATE_TAG, "scenario_step idx=${first + i} at_ms=${step.atMs}")
                            }
                            if (player.finished) {
                                scenario[0] = null
                                Log.i(GATE_TAG, "scenario_done ms=${loop.simMs - scenarioStartMs[0]}")
                            }
                        }
                    }
                    val state = loop.onFrame(now, tracking, currentSlot.onCharacterEvent, beforeStep = scripted)
                    val node = characterNode[0]
                    if (!tracking) reappearAtMs[0] = now + REAPPEAR_AFTER_TRACKING_MS
                    // While not tracking the camera pose is meaningless and the AnchorNode hides its children.
                    if (tracking && node != null) {
                        poser.apply(
                            node, state, planeCoords(a, frame.camera.pose), loop.walkPhaseMs,
                            verticalVelocity = loop.body.verticalVelocity,
                            sinceLandedMs = now - loop.landedAtMs,
                        )
                        val visible = now >= reappearAtMs[0]
                        if (node.isVisible != visible) node.isVisible = visible
                    }
                }
            },
            onTrackingFailureChanged = { reason -> Log.i(GATE_TAG, "tracking_failure=$reason") },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    val frame = latestFrame[0] ?: return@rememberOnGestureListener
                    val a = anchorRef[0]
                    // Walk taps only count on the anchor's own surface (within the profile's tolerance): a tap past the
                    // table edge hits the floor plane and the character would walk off into the air.
                    val tolerance = slotState.profile.tapHeightToleranceM
                    val hit = frame.hitTest(event).firstOrNull {
                        val t = it.trackable
                        t is Plane && t.isPoseInPolygon(it.hitPose) &&
                            (a == null || abs(it.hitPose.ty() - a.pose.ty()) <= tolerance)
                    }
                    if (a == null) {
                        val now = SystemClock.elapsedRealtime()
                        val plane = hit?.trackable as? Plane
                        val age = plane?.let { settle.trackingAgeMs(it, now) }
                        if (hit == null || plane == null || !settle.isSettled(plane, now)) {
                            Log.i(GATE_TAG, "anchor_rejected plane_age_ms=$age need_ms=$ANCHOR_PLANE_SETTLE_MS")
                            hintTaps++
                            return@rememberOnGestureListener
                        }
                        val created = hit.createAnchor()
                        anchorRef[0] = created
                        anchorPlane[0] = plane
                        anchor = created
                        slotState.anchored = true // the surface is now locked (sprite, physics, tolerance)
                        slotState.tracking = true
                        probe.onAnchorPlaced(created)
                        Log.i(GATE_TAG, "anchor_plane_age_ms=$age surface=${slotState.surface}")
                        val s = currentSlot
                        s.onAnchorPlaced()
                        s.onCharacterEvent(CharacterEvent.PLACED)
                        s.onTracking(true)
                    } else if (hit != null) {
                        val uv = planeCoords(a, hit.hitPose)
                        // The scanned boundary is a snapshot; the hit test used the live (grown) plane polygon. A tap
                        // past the snapshot would send the character over a "cliff" in the middle of the table.
                        val boundary = loop.level.boundary
                        if (boundary != null && !boundary.contains(uv)) {
                            Log.i(GATE_TAG, "walk_tap_outside_boundary u=${uv.u} v=${uv.v}")
                            return@rememberOnGestureListener
                        }
                        probe.onTap(a, hit.hitPose, uv)
                        loop.walkTo(uv) // its STARTED_WALKING event is reported by the next frame's loop.onFrame
                    }
                }
            ),
        ) {
            anchor?.let { a ->
                // HAND-REVIEW: SceneView's AnchorNode defaults to isPositionEditable = true; a drag would detach and
                // recreate the session anchor (AnchorNode.onMoveBegin/onMoveEnd) and freeze the game on a dead anchor.
                AnchorNode(anchor = a, apply = { isPositionEditable = false }) {
                    // One node per sprite: a rebuilt sprite replaces the node instead of swapping its material.
                    key(sprite) {
                        // Billboard quad in the anchor's frame: feet at the node origin (center = height / 2), front = +Z.
                        PlaneNode(
                            size = Size(x = sprite.widthM, y = sprite.heightM, z = 0f),
                            center = Position(y = sprite.heightM / 2f),
                            normal = Direction(z = 1f),
                            materialInstance = sprite.material,
                            apply = {
                                isTouchable = false // taps go through to the table hit test, drags never reach a node
                                characterNode[0] = this
                            },
                        )
                    }
                    // T21 (a) Plan B: invisible depth-writing cup cylinders / book boxes (Occluders.kt).
                    if (occlusion == Occlusion.BOXES) level?.let { Occluders(it, occluderMaterial) }
                    // Debug chrome only: tinted footprints + boundary loop on the plane (scan/FootprintOverlay.kt).
                    if (slot.debug) level?.let { FootprintOverlay(it, footprintMaterials) }
                }
            }
        }
        if (showHint) {
            val hint = if (surface == Surface.FLOOR) StringId.ar_wait_floor else StringId.ar_wait_table
            ScrimPill(t(hint), modifier = Modifier.align(Alignment.Center))
        }
        // Moved from TopEnd to CenterEnd: the shared Play HUD owns the top-right corner. Debug chrome only (T14).
        if (slot.debug) {
            GateRecordButton(
                recorder = recorder,
                modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
            )
        }
    }
}
