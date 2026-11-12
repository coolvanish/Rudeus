package kz.rudy.ar.ar

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import android.opengl.Matrix as GlMatrix
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import com.google.ar.core.DepthPoint
import io.github.sceneview.NULL_ENTITY
import io.github.sceneview.rememberEngine
import io.github.sceneview.safeDestroyTransformable
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.rudy.ar.ArCharacter
import kz.rudy.ar.ArSlot
import kz.rudy.ar.ScanAnswer
import kz.rudy.ar.audio.Sounds
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.pipeline.AutoScanPolicy
import kz.rudy.ar.core.pipeline.ScanAngle
import kz.rudy.ar.core.pipeline.ScanSectors
import kz.rudy.ar.core.pipeline.SurfaceBoundary
import kz.rudy.ar.core.session.CameraFrame
import kz.rudy.ar.core.session.FrameInput
import kz.rudy.ar.core.session.TableEngine
import kz.rudy.ar.rememberTableEngine
import kz.rudy.ar.scan.FootprintOverlay
import kz.rudy.ar.scan.GRAB_MAX_TRIES
import kz.rudy.ar.scan.ScanPrompts
import kz.rudy.ar.scan.TableScanner
import kz.rudy.ar.scan.footprintMaterials
import kz.rudy.ar.scan.grabScanFrame
import kz.rudy.ar.scan.runTableScan
import kz.rudy.ar.scan.toPose3
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
// scan is answered exactly once through `onScanned` (Found count; Failed: no anchor, not tracking, no frame, error; TooSteep).
// The anchor's Plane is kept (`anchorPlane`) so the scan can read its polygon as the level boundary (cliffs) — on
// the TABLE only: a floor has no edge and ARCore's floor patches are partial, so FLOOR scans pass no plane. The
// debug overlay (scan/FootprintOverlay.kt) lives under the AnchorNode and is composed only while `slot.debug`.
// HAND-REVIEW: T04 review fixes — (1) every request is answered exactly once: `scanOwed` counts the requests not yet
// answered; a request while a scan is pending or running joins it and the running scan answers all of them with its
// result (no instant NO_PLANE during a tracking blip). (2) A frame without a camera image yet (NotYetAvailable) keeps
// the request pending for up to GRAB_MAX_TRIES frames. (3) `scanBusy` is cleared in `finally`, so a cancelled or
// failed scan can never leave Rescan dead for the session. (4) was "ignore walk taps past the scanned snapshot";
// T25 replaced it with `confirmSurface` below, because that refused taps on the real table.
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
// along the character's `facing`, from where it stands; a target outside `loop.level.boundary` is skipped and logged
// `voice_walk_outside_boundary` (no hit test confirms that surface, unlike a tap); before the anchor it is dropped.
// HAND-REVIEW: T32 — the cast. (1) Members come from `slot.characters` (keyed by id; a member's sprite is rebuilt on a
// new `spritePng` identity): one `MemberRig` (sprite + poser + node) per member, the rig built here and its PlaneNode
// under the AnchorNode keyed `(id, rig)` so the node is detached before the sprite is destroyed (T14's order). The
// physics side is synced from the frame callback (`syncCast`): a member not yet in the loop is added once the anchor
// exists — the first at ZERO, the others at `CastPhysics.spawnNear` of the active member toward camera-right — and
// announced with `onCastEvent(id, PLACED)`; a member gone from the slot is removed. Members added before the anchor
// wait and are placed on the frame the anchor appears. `onCharacterEvent` is never called by this host.
// (2) Commands: `slot.command` is edge-triggered on its serial; Jump / WalkForward / Emote act on the command's id. The
// legacy `jumpRequest` / `walkForwardRequest` / `ability` are ignored (App still mirrors them for a v1.0 host).
// (3) Fingers: `ARSceneView(onTouchEvent)` (CastTouch.kt) binds a pointer to the member under it; `CastTable` below does
// the hit test (same plane + tolerance rule as a tap), the grab radius `max(radiusAt(size) * 1.5, GRAB_MIN_M * scale)`
// and the lead: `level.nearestInside(uv)` then `loop.walkTo`. No depth-gap sampling per move (a tap does 7 hit tests;
// a drag at 60 ms would do hundreds): the boundary clamp keeps the walk on the scanned surface. A tap on a member
// (CAST_TOUCH_ENABLED = false, or a finger the grab did not consume) only makes it active.
// (4) Emotes: `loop.emote(id, member.emotes[e])` runs on the simulated clock and is handed to the poser as an EmotePose
// (CharacterNode.kt); its sound plays through `sounds` when `slot.soundsOn`. Physics never sees an emote.
// Locked decisions untouched: one anchor per session; PAUSED freezes every member and every emote clock, never resets;
// the FIRST scan stays a button and quiet scans sit behind `slot.autoScan` (T37, below); plane coords live in :core, the
// Pose conversions in PlaneCoords.kt only.
// HAND-REVIEW: T32 review fixes — (a) the grab is tested in SCREEN space: each ground member's feet and head are projected
// with ARCore `Camera.getViewMatrix` / `getProjectionMatrix` (display-rotation aware; near/far do not touch x, y) into the
// view's pixels (`viewSize` from onSizeChanged on the ARSceneView's AndroidView, ARSceneView.kt:1512), the quad's width at
// the feet depth is `P00 * w / 2 / depth` px per metre, and the touch must fall in that rectangle widened by GRAB_MARGIN_DP;
// nearest centre wins. Without a view size yet the old table-point test is the fallback. (b) A lead is refused past a
// depth edge like a tap (`depthGap` at the target + `depthGapsOnWay` with LEAD_DEPTH_SAMPLES from the member's feet,
// `SurfaceBoundary.crossesEdge`, log `lead_past_edge`): before a scan `boundary` is null and nothing else clamps. (c) With
// exactly one member there is no grab and no tap-select, so the v1.0 small steps next to the character still walk it.
// (d) A new node starts invisible; the poser shows it once it is placed, so it is never drawn unposed at the origin.
// HAND-REVIEW: T37 — quiet background scans. After the first BUTTON scan built a level (`firstLevelScanned`), while
// `slot.autoScan`, every anchored frame with no scan pending or running feeds :core's `AutoScanPolicy` (T35: <= 4 per session, one per new sector
// around the anchor, >= 8 s apart, camera still and not too steep). A sector arms the SAME pending path as the button
// (`scanQuiet` set beside `scanPending`), so the next frame's grab, the retry on NotYetAvailable, the IO half, the level
// swap and the boundary fold are shared; the differences are: nothing is owed through `onScanned` (no overlay, no lines, no
// toast; a button request that joins a quiet scan still gets its answer from `finally`), ref `auto-NN`, and
// `recordScan(background = true)`. A button scan calls `policy.markScanned` so its sector counts as visited; `placeAnchor`
// resets the policy. The merge (T34) gets the members' positions (`standing`) so the object under a character never changes.
// `slot.autoScan = false`: the policy is not fed and a quiet scan still waiting for its frame is dropped (`auto_scan_dropped`);
// one already running finishes (the switch stops new frames, not the one in flight). A button press that joins a quiet
// scan is answered and recorded as manual. Still never per-frame: the policy decides, the scan is one grab, and at most
// four of them a session.
// HAND-REVIEW: T47 — GameLoop (the physics driver) comes from :core (core.session), moved as it was and pinned by
// JVM tests. PAUSED still freezes every member and never resets; one anchor per session; nothing else changed here.
// HAND-REVIEW: T52 — the poser's maths is a pure BillboardPoser returning a NodePose; nothing else changed here.
// HAND-REVIEW: T53 — the surface lock, the cast sync, the active member and the commands are a pure
// TableEngine fed by rememberTableEngine (now `core.session.TableEngine` and `:shared` `rememberTableEngine`, pinned by
// JVM tests); one anchor per session and PAUSED never resets are untouched.
// HAND-REVIEW: T56 — the frame step (tracking edge, scenario, physics, cast-state report,
// reappear timer, poses) is TableEngine.onFrame (now `core.session.TableEngine.onFrame`, pinned by JVM tests): this
// file builds a FrameInput and writes each NodePose into its
// node; the scan reads the level and the members through the engine. PAUSED still freezes and never resets, one
// anchor per session, scans stay a button plus the quiet ones and are never per frame.

/** HAND-REVIEW: T32 review fix — the grab rectangle grows by this on every side (16 dp = about one finger radius on the Pixel). */
private const val GRAB_MARGIN_DP = 16f

/** Depth readings on the way of a lead (a tap takes WALK_DEPTH_SAMPLES; a lead runs up to 16 times a second). */
private const val LEAD_DEPTH_SAMPLES = 2

/** Clip planes for the grab projection; x and y of a point do not depend on them. */
private const val PROJECTION_NEAR_M = 0.01f
private const val PROJECTION_FAR_M = 30f

/** HAND-REVIEW: T32 — the cast cut switch: false shows only the first member of `slot.characters` (v1.0 flow). */
private const val CAST_ENABLED = true

/** HAND-REVIEW: T32 — cut line 5: false turns the finger grab off; a tap on a member makes it active, a tap on the table walks the active one. */
private const val CAST_TOUCH_ENABLED = true

/** HAND-REVIEW: T32 — cut line 4: false steps the cast without `CastPhysics.separate` (members may overlap). */
private const val SEPARATION_ENABLED = true

/** The member id used when a slot carries no cast at all (a v1.0 slot): the legacy sprite / ability fields. */
private const val LEGACY_ID = "legacy"

/** How long the "wait, finding the table" hint stays after a premature tap. */
private const val HINT_VISIBLE_MS = 1_500L

/**
 * HAND-REVIEW: 2026-11-04 — cut switch. A tap before the plane settled was refused, so the child waited and tapped again.
 * true: the tap is held and the anchor lands at that screen point once a settled plane is under it, same settle gate
 * (ANCHOR_PLANE_SETTLE_MS), still one anchor per session. false: refused with the hint, as before.
 */
private const val HOLD_EARLY_TAP = true

/** A tap waiting for a settled plane: view pixels, and when it came (`anchor_tap_held_ms=`). */
private class HeldTap(val x: Float, val y: Float, val atMs: Long)

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

// HAND-REVIEW: Pixel 2026-11-03 — depth readings per tap along the walk (depthGapsOnWay): a gap between two surfaces is 30 cm or more, this is about one every 15 cm on a 1 m walk.
private const val WALK_DEPTH_SAMPLES = 6

/**
 * Plain holders for the slot-facing state written by the frame callback and the tap handler (main thread only).
 * `surface` is the one the sprite and physics are built for: the engine's, locked at the anchor (T53).
 */
private class SlotState(private val game: TableEngine) {
    var surfaceReady = false
    val surface: Surface get() = game.surface
    val profile: SurfaceProfile get() = game.profile
}

/** T32: one member's render side. `node` is written by the PlaneNode's `apply` and read by the frame callback. */
private class MemberRig(val id: String, val sprite: CharacterSprite) {
    var node: PlaneNode? = null
}

/**
 * T32 review fix: world -> view pixels through this frame's ARCore camera (column-major GL matrices, `android.opengl.Matrix`).
 * Scratch arrays are reused: main thread only, one grab test at a time.
 */
private class ScreenProjector {
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val world = FloatArray(4)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)

    fun load(frame: Frame) {
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(proj, 0, PROJECTION_NEAR_M, PROJECTION_FAR_M)
    }

    /** [out] = (x px, y px, eye depth m) of [pose]'s position in a [w] x [h] view; false when it is behind the camera. */
    fun project(pose: Pose, w: Int, h: Int, out: FloatArray): Boolean {
        world[0] = pose.tx(); world[1] = pose.ty(); world[2] = pose.tz(); world[3] = 1f
        GlMatrix.multiplyMV(eye, 0, view, 0, world, 0)
        GlMatrix.multiplyMV(clip, 0, proj, 0, eye, 0)
        if (clip[3] <= 1e-4f) return false
        out[0] = (clip[0] / clip[3] + 1f) * 0.5f * w
        out[1] = (1f - clip[1] / clip[3]) * 0.5f * h
        out[2] = -eye[2]
        return true
    }

    /** Pixels per metre across the view at eye depth [depth] (P00 = 2 * focal / width). */
    fun pxPerM(depth: Float, w: Int): Float = proj[0] * w * 0.5f / depth
}

/** T32: the members this host renders: the slot's cast (the first only with CAST_ENABLED off), else the legacy fields as one member. */
private fun ArSlot.members(): List<ArCharacter> = when {
    characters.isEmpty() -> listOf(ArCharacter(LEGACY_ID, "", spritePng, ability, EmoteSet.defaults()))
    CAST_ENABLED -> characters
    else -> characters.take(1)
}

/**
 * The AR view behind Scanning and Play. [slot] carries the sprite, the surface and the HUD callbacks (ArSlot.kt):
 * `onSurfaceReady` once a settled upward plane exists (false again if it goes away before the anchor, or while a tap is held),
 * `onAnchorPlaced` + `onCharacterEvent(PLACED)` on the first accepted tap (or when a held one lands), `onTracking` on every change after that,
 * `onCharacterEvent` for each physics transition. The ARSceneView, the session and the anchor survive slot changes.
 * [scanner] (T04) runs the SAM call and records the scan; null (no host) answers every scan request with failure.
 * [sounds] (T32) plays the emote sounds; the event sounds ride on the slot's callbacks (MainActivity).
 */
@Composable
fun RudeusArView(modifier: Modifier = Modifier, slot: ArSlot, scanner: TableScanner? = null, sounds: Sounds? = null) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val currentSlot by rememberUpdatedState(slot)
    // Plain holders, not state: a Frame arrives every ~16 ms and must not recompose the tree.
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val anchorRef = remember { arrayOfNulls<Anchor>(1) } // mirror of `anchor` for the frame callback
    val anchorPlane = remember { arrayOfNulls<Plane>(1) } // the plane the anchor was created on (scan boundary)
    val rigs = remember { HashMap<String, MemberRig>() } // T32: id -> rig, written in composition, read per frame
    val touch = remember { CastTouch() }
    val viewSize = remember { intArrayOf(0, 0) } // the ARSceneView's pixels, for the screen-space grab (0 until laid out)
    val grabMarginPx = remember { floatArrayOf(0f) }
    grabMarginPx[0] = GRAB_MARGIN_DP * LocalDensity.current.density
    val projector = remember { ScreenProjector() }
    val members = slot.members()
    val game = rememberTableEngine(slot, members, playEmoteSound = { sounds?.play(it) }, onMemberRemoved = touch::unbindMember, log = { Log.i(GATE_TAG, it) }, feetDepthBias = FEET_DEPTH_BIAS, reappearAfterTrackingMs = REAPPEAR_AFTER_TRACKING_MS, separate = SEPARATION_ENABLED)
    val loop = game.loop
    val scanPending = remember { booleanArrayOf(false) } // set by a scan request, consumed by the next frame
    val scanQuiet = remember { booleanArrayOf(false) } // T37: the pending scan is a quiet one (no onScanned); cleared with scanPending
    val policy = remember { AutoScanPolicy() } // T37: fed from the frame callback only; reset with the anchor
    // HAND-REVIEW: T37 review fix — the "first level on the button" gate: true once a NON-quiet scan swapped a level in (not
    // at the grab: a failed or too-steep first button scan must not let a quiet scan build the session's first level).
    val firstLevelScanned = remember { booleanArrayOf(false) }
    val scanBusy = remember { booleanArrayOf(false) } // one scan at a time; a request meanwhile joins it
    val scanOwed = remember { intArrayOf(0) } // requests not yet answered through onScanned
    val scanTries = remember { intArrayOf(0) } // frames the pending request has waited for a camera image
    val scanCount = remember { intArrayOf(0) }
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf<Level?>(null) } // written per scan and per boundary-growing tap (overlay), never per frame
    val footprintMaterials = remember(materialLoader) { footprintMaterials(materialLoader) }
    val settle = remember { PlaneSettle() }
    val slotState = remember { SlotState(game) }
    val surface = slotState.surface
    val profile = slotState.profile
    // HAND-REVIEW: T25 — the size re-sizes physics mid-game; the surface stays locked at anchor time (T14). Main thread,
    // same as the frame callback (the effect is now rememberTableEngine's). The Day 5 scenario beats are timed for size 1×: replay it at 1×.
    // T32: one rig per member, keyed by id; a new spritePng identity (or surface) rebuilds it. The map is written in
    // the remember block so the AnchorNode content, composed later in the same pass, finds the rig. Never destroys a
    // sprite in use: the effect runs only after the node keyed on the rig has been detached (see the T14 header comment).
    for (c in members) key(c.id) {
        val rig = remember(engine, materialLoader, c.spritePng, surface) {
            val png = c.spritePng
            val sprite = if (png != null) spriteFromBytes(engine, materialLoader, context.assets, png, profile.characterHeightM, GENERATED_SPRITE_FACES_RIGHT)
            else spriteFromAssets(engine, materialLoader, context.assets, profile.characterHeightM)
            game.spriteBuilt(c.id, sprite.facesRight) // T56: this picture's pose starts unflipped, as a new CharacterPoser did
            MemberRig(c.id, sprite).also { rigs[c.id] = it }
        }
        DisposableEffect(rig) {
            onDispose {
                if (rigs[c.id] === rig) rigs.remove(c.id)
                rig.sprite.destroy(engine, materialLoader)
            }
        }
    }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var showHint by remember { mutableStateOf(false) }
    var hintTaps by remember { mutableIntStateOf(0) } // each premature tap restarts the hint timer
    val heldTap = remember { arrayOfNulls<HeldTap>(1) } // read by the frame callback, so a plain holder
    var holdHint by remember { mutableStateOf(false) } // the "wait" pill while a tap is held; written per tap, never per frame
    val probe = remember { GateProbe() }
    val recorder = rememberARRecorder()
    // T21 (a): the camera stream is ours so `sessionConfiguration` can flip depth occlusion once depth support is known.
    val cameraStream = rememberARCameraStream(materialLoader)
    var occlusion by remember { mutableStateOf(Occlusion.NONE) } // written once per session, never per frame
    val occluderMaterial = remember(materialLoader) { materialLoader.createOcclusionInstance() }

    LaunchedEffect(hintTaps) {
        if (hintTaps > 0) {
            showHint = true
            delay(HINT_VISIBLE_MS)
            showHint = false
        }
    }
    // HAND-REVIEW: 2026-11-04 — a new surface chip means the child is aiming somewhere else: a held tap must not land on it.
    LaunchedEffect(slot.surface) {
        heldTap[0] = null
        holdHint = false
    }
    /** Answers every request owed so far with [answer] (exactly one onScanned per request). Main thread. */
    fun answerOwed(answer: ScanAnswer) {
        val n = scanOwed[0]
        scanOwed[0] = 0
        repeat(n) { currentSlot.onScanned(answer) }
    }
    // A scan request is armed here and fulfilled by the next frame (acquireCameraImage wants the current frame).
    // Without an anchor or a host nothing can ever scan: answered at once. Otherwise the request is owed; if a scan
    // is already pending or running it joins that one and gets its result.
    LaunchedEffect(slot.scanRequest) {
        if (slot.scanRequest == 0) return@LaunchedEffect
        if (anchorRef[0] == null || scanner == null) {
            Log.i(GATE_TAG, "scan_rejected anchored=${anchorRef[0] != null} host=${scanner != null}")
            currentSlot.onScanned(ScanAnswer.Failed)
            return@LaunchedEffect
        }
        scanOwed[0]++
        if (!scanBusy[0] && !scanPending[0]) {
            scanPending[0] = true
            scanTries[0] = 0
        }
        scanQuiet[0] = false // T37: a button press is never quiet; a quiet scan still pending becomes this button's scan
    }
    fun activeId(): String? = game.activeId()
    /**
     * HAND-REVIEW: T25 — the plane polygon is only the table seen so far, so a tap the hit test accepted is table: it
     * grows the boundary instead of being refused. Only the tap: on the Pixel the live polygon ran past the end of the
     * table and the hull kept that (2026-11-03). On the floor the boundary stays null. Main thread, called per tap, never
     * per frame. One anchor per session stays: only the Level changes.
     */
    fun confirmSurface(uv: Vec2) {
        if (loop.level.boundary == null) return
        val grown = SurfaceBoundary.grown(loop.level, standing = listOf(uv))
        if (grown === loop.level) return
        loop.level = grown
        level = grown
        currentSlot.onLevel(grown)
        Log.i(GATE_TAG, "boundary_grown n=${grown.boundary?.size} u=${uv.u} v=${uv.v}")
    }
    /**
     * Main thread, from onSessionUpdated while a request is pending: grab this frame, then build the level on IO and
     * swap it in on return. Returns true when the request stays pending for the next frame (no image yet).
     * HAND-REVIEW: T37 — [quiet]: a policy-started scan. Nothing was owed for it, so `answerOwed` below answers only the
     * button requests that joined it (none, usually); the early exits log the same keys with `quiet=true`.
     */
    fun startScan(frame: Frame, a: Anchor, tracking: Boolean, quiet: Boolean = scanQuiet[0]): Boolean {
        val host = scanner
        if (host == null || !tracking) {
            Log.i(GATE_TAG, "scan_skipped tracking=$tracking host=${host != null} quiet=$quiet")
            answerOwed(ScanAnswer.Failed)
            return false
        }
        // HAND-REVIEW: T25 — checked before the image copy and the SAM bill. The first scan stays a button; this only
        // answers it sooner, and the level in play is untouched.
        val planeFrame = PlaneFrame.fromAnchorPose(a.pose.toPose3())
        if (ScanAngle.cameraTooSteep(frame.camera.pose.toPose3(), planeFrame)) {
            Log.i(GATE_TAG, "scan_too_steep where=camera quiet=$quiet")
            answerOwed(ScanAnswer.TooSteep)
            return false
        }
        // The level boundary comes from the anchor plane on the table only (see the T04 header).
        val plane = if (slotState.surface == Surface.FLOOR) null else anchorPlane[0]
        val attempt = grabScanFrame(frame, a, plane)
        val grab = attempt.grab
        if (grab == null) {
            if (attempt.retry && ++scanTries[0] < GRAB_MAX_TRIES) return true
            Log.i(GATE_TAG, "scan_skipped no_image tries=${scanTries[0]} quiet=$quiet")
            answerOwed(ScanAnswer.Failed)
            return false
        }
        scanBusy[0] = true
        // T37: one counter for both kinds; the prefix tells them apart in logcat and in the run file's refs.
        val ref = (if (quiet) "auto-" else "scan-") + (++scanCount[0]).toString().padStart(2, '0')
        // T37: a button scan from here marks its sector visited (and restarts the policy's cooldown); the grab's pose is the
        // sensor pose, same frame as the policy sees. Main thread, like every other policy call.
        if (!quiet) policy.markScanned(planeFrame.toPlane(grab.cameraPose.position), SystemClock.elapsedRealtime())
        val previous = game.level
        val profile = slotState.profile
        // T37: where the members stand now (plane coords), for the merge: the object under a character never changes.
        val standingNow = game.standing().map { it.pos }
        scope.launch {
            var answer: ScanAnswer = ScanAnswer.Failed
            try {
                val outcome = runTableScan(grab, host, ScanPrompts.of(profile.surface), previous, profile, ref, standingNow)
                // HAND-REVIEW: T25 — the swap never shrinks the boundary or leaves the character outside it, read from
                // `loop` here (not at grab time) so a tap made during the scan counts. The polygon alone can shift between
                // scans and drop a character standing still, or loop respawns if it misses the spawn. This only changes
                // the Level: PAUSED still freezes and never resets.
                // T32: every member pulls the boundary after it (afterScan keeps the hull it is given, so folding is safe).
                val standing = game.standing()
                val built = outcome.level?.let { l -> standing.fold(l) { acc, s -> SurfaceBoundary.afterScan(acc, game.level, s) } }
                if (built != null) {
                    game.level = built // main thread: the next frame already steps against the new level
                    level = built
                    currentSlot.onLevel(built)
                    answer = ScanAnswer.Found(built.objects.size, outcome.missed)
                    // HAND-REVIEW: T37 review fix — a button request that joined this quiet scan makes it a manual one in the
                    // run file; `scanOwed` is read here on the main thread, before `finally` answers and clears it.
                    val background = quiet && scanOwed[0] == 0
                    if (!background) firstLevelScanned[0] = true
                    host.recordScan(built.objects.size, outcome.ms, background = background)
                } else if (outcome.tooSteep) {
                    answer = ScanAnswer.TooSteep
                }
            } finally {
                scanBusy[0] = false
                // On cancellation (the view left composition) the slot is gone with its session: nobody to answer.
                if (isActive) answerOwed(answer)
            }
        }
        return false
    }
    /**
     * T32: the hit a tap or a finger counts: a plane hit inside its polygon, within the profile's tolerance of the
     * anchor plane (a hit past the table edge is the floor plane and the character would walk off into the air).
     */
    fun tableHit(hits: List<HitResult>, a: Anchor?): HitResult? {
        val tolerance = slotState.profile.tapHeightToleranceM
        return hits.firstOrNull {
            val t = it.trackable
            t is Plane && t.isPoseInPolygon(it.hitPose) && (a == null || abs(it.hitPose.ty() - a.pose.ty()) <= tolerance)
        }
    }
    /** T32: camera-right in plane coords (the display-oriented camera's +X, ARCore Camera.getDisplayOrientedPose), for spawnNear. */
    fun cameraRight(a: Anchor, frame: Frame): Vec2 {
        val x = a.pose.inverse().compose(frame.camera.displayOrientedPose).xAxis
        return Vec2(x[0], x[2])
    }
    /** The session's one anchor, on [hit]: from a tap on a settled plane, or from the frame that lands a held tap. Main thread. */
    fun placeAnchor(hit: HitResult, plane: Plane, frame: Frame, ageMs: Long?) {
        heldTap[0] = null
        holdHint = false
        val created = hit.createAnchor()
        anchorRef[0] = created
        anchorPlane[0] = plane
        anchor = created
        policy.reset() // T37: a new level frame; no sector is visited yet
        firstLevelScanned[0] = false // T37 review fix: the first level of this anchor comes from the button again
        probe.onAnchorPlaced(created)
        Log.i(GATE_TAG, "anchor_plane_age_ms=$ageMs surface=${slotState.surface}")
        game.anchorPlaced { cameraRight(created, frame) }
    }
    // T32: what CastTouch needs; the hit test is the tap's, the grab a screen-space quad test (review fix (a)), the lead a
    // boundary-clamped, edge-checked walkTo (review fix (b)).
    val table = remember {
        object : CastTable {
            private val feet = FloatArray(3)
            private val head = FloatArray(3)

            override fun hitUv(x: Float, y: Float): TableHit? {
                val frame = latestFrame[0] ?: return null
                val a = anchorRef[0] ?: return null
                val hits = frame.hitTest(x, y)
                val hit = tableHit(hits, a) ?: return null
                val depthGap = hits.firstOrNull { it.trackable is DepthPoint }?.let { planeHeight(a, it.hitPose) }
                return TableHit(planeCoords(a, hit.hitPose), depthGap)
            }

            override fun grabbable(x: Float, y: Float): String? {
                if (loop.cast.members.size < 2) return null // review fix (c): one character walks as in v1.0, no grab
                val frame = latestFrame[0] ?: return null
                val a = anchorRef[0] ?: return null
                val ground = loop.cast.members.filter { it.body.state.mode == Mode.IDLE || it.body.state.mode == Mode.WALKING }
                val w = viewSize[0]
                val h = viewSize[1]
                if (w <= 0 || h <= 0) return hitUv(x, y)?.let { hit -> nearestOnGround(ground, hit.uv) }
                projector.load(frame)
                val size = currentSlot.characterSize
                val margin = grabMarginPx[0]
                var best: String? = null
                var bestD2 = Float.MAX_VALUE
                for (m in ground) {
                    val s = m.body.state
                    val sprite = rigs[m.id]?.sprite ?: continue
                    if (!projector.project(toWorld(a, s.pos, s.h), w, h, feet)) continue
                    if (!projector.project(toWorld(a, s.pos, s.h + sprite.heightM * size), w, h, head)) continue
                    val halfW = projector.pxPerM(feet[2], w) * sprite.widthM * size * 0.5f + margin
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
                val profile = slotState.profile
                val reach = maxOf(profile.radiusAt(currentSlot.characterSize) * 1.5f, GRAB_MIN_M * profile.scale)
                return ground.minByOrNull { it.body.state.pos.distanceTo(uv) }?.takeIf { it.body.state.pos.distanceTo(uv) <= reach }?.id
            }

            override fun lead(id: String, hit: TableHit) {
                val frame = latestFrame[0] ?: return
                val a = anchorRef[0] ?: return
                val from = loop.state(id)?.pos ?: return
                val target = loop.level.nearestInside(hit.uv) // inside the scanned hull (null boundary before a scan: unclamped)
                val gaps = depthGapsOnWay(frame, a, from, target, LEAD_DEPTH_SAMPLES) + hit.depthGapM
                if (SurfaceBoundary.crossesEdge(gaps, slotState.profile)) {
                    Log.i(GATE_TAG, "lead_past_edge id=$id u=${target.u} v=${target.v} way=$gaps")
                    return // the last target stays
                }
                loop.walkTo(id, target)
            }

            override fun onGrabbed(id: String) = currentSlot.onActiveChanged(id)
        }
    }
    // HAND-REVIEW: T32 — ARSceneView attaches its touch dispatcher once, at view creation (ARSceneView.kt:1386, 1515),
    // so this lambda is remembered and reads only plain holders; a recomposition would not replace it.
    val onTouch = remember<(MotionEvent, Any?) -> Boolean> {
        { event, _ -> CAST_TOUCH_ENABLED && anchorRef[0] != null && touch.onTouch(event, SystemClock.elapsedRealtime(), table) }
    }

    Box(modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize[0] = it.width; viewSize[1] = it.height },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            cameraStream = cameraStream,
            planeRenderer = anchor == null,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                // HAND-REVIEW: memory audit 2026-11-03 — nothing in the scene reads light: the sprite, footprints and plane
                // dots are unlit, the occluders depth-only. DISABLED skips the HDR estimate, its cubemaps and the IBL rebuild
                // (LightEstimator.kt:421 frees them); the estimator never touches camera exposure, so colours stay the same.
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
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
                // HAND-REVIEW: Pixel 2026-11-04 — the first sprite froze at the anchor while its physics walked (`sprite_stale=true
                // cached=266 actual=264`, 1.3 s after node_created). PlaneVisualizer.destroy (PlaneVisualizer.kt:370) leaves its transform
                // to Filament's lazy GC, which moves the newest component into the freed slot without SceneView's generation bump, so
                // Node.transformInstance (Node.kt:788) kept writing to the old slot. Destroying nothing bumps the generation (Engine.kt:171),
                // so every node looks its slot up again on its next write: one JNI lookup per moving node per frame.
                engine.safeDestroyTransformable(NULL_ENTITY)
                latestFrame[0] = frame
                recorder.recordFrame(session) // AtomicReference.set; no-op unless recording
                probe.onFrame(session)
                val now = SystemClock.elapsedRealtime()
                val a = anchorRef[0]
                if (a == null) {
                    settle.onFrame(frame, now)
                    val settled = settle.anySettled(now)
                    val held = heldTap[0]
                    // HAND-REVIEW: 2026-11-04 — while a tap is held nothing more is asked of the child: "tap to place" stays off and
                    // Scanning keeps saying "look at the table", which is the right ask if the held point is off the plane.
                    val ready = settled && held == null
                    if (ready != slotState.surfaceReady) {
                        slotState.surfaceReady = ready
                        currentSlot.onSurfaceReady(ready)
                    }
                    // HAND-REVIEW: 2026-11-04 — same hit rule and settle gate as a tap. One hit test per frame, only while a tap is held
                    // and a plane has settled. createAnchor needs a tracking camera.
                    if (settled && held != null && frame.camera.trackingState == TrackingState.TRACKING) {
                        val hit = tableHit(frame.hitTest(held.x, held.y), null)
                        val plane = hit?.trackable as? Plane
                        if (hit != null && plane != null && settle.isSettled(plane, now)) {
                            Log.i(GATE_TAG, "anchor_tap_held_ms=${now - held.atMs}")
                            placeAnchor(hit, plane, frame, settle.trackingAgeMs(plane, now))
                        }
                    }
                } else {
                    val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
                        a.trackingState == TrackingState.TRACKING
                    // HAND-REVIEW: T56 — reported here, before the scan block, as before: App drops a scan answer that comes after a
                    // loss (shared/.../App.kt:472, 495). onFrame reports it too; this early call goes when S5 moves the scan.
                    game.trackingEdge(tracking)
                    // HAND-REVIEW: T37 review fix — the switch went off while a quiet scan waited for its frame and nobody asked for
                    // it: dropped before the grab. A quiet scan already running is not cancelled (the switch stops new frames).
                    if (scanPending[0] && scanQuiet[0] && !currentSlot.autoScan && scanOwed[0] == 0) {
                        scanPending[0] = false
                        scanQuiet[0] = false
                        Log.i(GATE_TAG, "auto_scan_dropped")
                    }
                    if (scanPending[0]) {
                        scanPending[0] = startScan(frame, a, tracking)
                        if (!scanPending[0]) scanQuiet[0] = false // T37: the quiet flag lives exactly as long as the pending one
                    }
                    // HAND-REVIEW: T37 — the policy is fed only after the first button scan built a level, while the HUD switch is
                    // on, no scan is pending or running (so `busy = false` is already true) and the policy can still fire (past its
                    // four scans or four sectors the per-frame pose conversions are skipped). A sector arms the pending path above
                    // for the NEXT frame, quietly. `policy.onFrame` is pure :core; the ARCore poses are converted here only.
                    if (currentSlot.autoScan && scanner != null && firstLevelScanned[0] && !scanPending[0] && !scanBusy[0] &&
                        policy.fired < policy.params.maxScans && policy.visited.size < ScanSectors.COUNT
                    ) {
                        val sector = policy.onFrame(
                            now, frame.camera.pose.toPose3(), PlaneFrame.fromAnchorPose(a.pose.toPose3()), Vec2.ZERO,
                            tracking = tracking, busy = false,
                        )
                        if (sector != null) {
                            Log.i(GATE_TAG, "auto_scan sector=$sector fired=${policy.fired}")
                            scanQuiet[0] = true
                            scanPending[0] = true
                            scanTries[0] = 0
                        }
                    }
                    // HAND-REVIEW: T56 — the cast sync, the scenario's taps, physics, the cast-state report, the reappear timer and one
                    // pose per member are TableEngine.onFrame; each NodePose goes into its node (writePose, CharacterNode.kt). A member
                    // without a node yet gets no write, but the engine has posed it (new: its flip can settle 1-2 frames earlier). PAUSED
                    // freezes, never resets: no pose while not tracking. The camera is read on every anchored frame, used only while tracking.
                    val camera = CameraFrame(planeCoords(a, frame.camera.pose), planeHeight(a, frame.camera.pose), cameraRight(a, frame))
                    for (p in game.onFrame(FrameInput(now, tracking, camera))) rigs[p.id]?.node?.writePose(p)
                }
            },
            onTrackingFailureChanged = { reason -> Log.i(GATE_TAG, "tracking_failure=$reason") },
            onGestureListener = run {
                // HAND-REVIEW: Pixel 2026-11-03 — onSingleTapConfirmed waited about 0.25 s to rule out a double tap and ignored
                // double taps: of 31 fast taps on the table only 9 walked (the bee looked stuck). Every tap counts on finger-up now.
                // The detector gives the 1st, 3rd.. tap of a burst to onSingleTapUp and the 2nd only to onDoubleTapEvent, never both.
                fun tableTap(event: MotionEvent) {
                    val frame = latestFrame[0] ?: return
                    val a = anchorRef[0]
                    // Walk taps only count on the anchor's own surface (within the profile's tolerance): a tap past the
                    // table edge hits the floor plane and the character would walk off into the air (tableHit, T32).
                    val hits = frame.hitTest(event)
                    val hit = tableHit(hits, a)
                    if (a == null) {
                        val now = SystemClock.elapsedRealtime()
                        val plane = hit?.trackable as? Plane
                        val age = plane?.let { settle.trackingAgeMs(it, now) }
                        if (hit == null || plane == null || !settle.isSettled(plane, now)) {
                            Log.i(GATE_TAG, "anchor_rejected plane_age_ms=$age need_ms=$ANCHOR_PLANE_SETTLE_MS held=$HOLD_EARLY_TAP")
                            hintTaps++
                            if (HOLD_EARLY_TAP) {
                                heldTap[0] = HeldTap(event.x, event.y, now) // a newer tap replaces it
                                holdHint = true
                            }
                            return
                        }
                        placeAnchor(hit, plane, frame, age)
                    } else if (hit != null) {
                        val uv = planeCoords(a, hit.hitPose)
                        probe.onTap(a, hit.hitPose, uv)
                        // T32: a tap on a member only makes it active (the finger grab normally consumes this tap first;
                        // with one member on the table `grabbable` is always null and the tap walks it, as v1.0).
                        val tapped = table.grabbable(event.x, event.y)
                        if (tapped != null) {
                            if (tapped != activeId()) currentSlot.onActiveChanged(tapped)
                            return
                        }
                        val active = activeId() ?: return
                        val from = loop.state(active)?.pos ?: return
                        // HAND-REVIEW: Pixel 2026-11-03 — for feet that sink or float: where the Depth API sees the table at this
                        // tap, relative to the plane the level stands on (+ = plane too low), and the feet's height.
                        val depthGap = hits.firstOrNull { it.trackable is DepthPoint }?.let { planeHeight(a, it.hitPose) }
                        // HAND-REVIEW: Pixel 2026-11-03 — ARCore's polygon runs past the table (a tap there read 29 cm below the plane),
                        // so "in the polygon" is not "on the table". A drop in depth on the way or at the tap refuses it; :core decides.
                        // No depth reading (null) never refuses. The boundary is not touched. The character says it cannot go there
                        // (the BLOCKED line), so a refused tap is not a dead tap.
                        val gaps = depthGapsOnWay(frame, a, from, uv, WALK_DEPTH_SAMPLES) + depthGap
                        Log.i(GATE_TAG, "tap_depth_gap=$depthGap feet_h=${loop.state(active)?.h} way=$gaps id=$active")
                        if (SurfaceBoundary.crossesEdge(gaps, slotState.profile)) {
                            Log.i(GATE_TAG, "tap_past_edge u=${uv.u} v=${uv.v}")
                            currentSlot.onCastEvent(active, CharacterEvent.BLOCKED)
                        } else {
                            confirmSurface(uv) // the boundary is convex, so the straight walk there stays inside it
                            loop.walkTo(active, uv) // its STARTED_WALKING event is reported by the next frame's loop.onFrame
                        }
                    }
                }
                rememberOnGestureListener(
                    onSingleTapUp = { event, _ -> tableTap(event) },
                    onDoubleTapEvent = { event, _ -> if (event.actionMasked == MotionEvent.ACTION_UP) tableTap(event) },
                )
            },
            onTouchEvent = onTouch, // T32: the finger grab, before the gesture detector (CastTouch.kt)
        ) {
            anchor?.let { a ->
                // HAND-REVIEW: SceneView's AnchorNode defaults to isPositionEditable = true; a drag would detach and
                // recreate the session anchor (AnchorNode.onMoveBegin/onMoveEnd) and freeze the game on a dead anchor.
                AnchorNode(anchor = a, apply = { isPositionEditable = false }) {
                    // T32: one node per member and per sprite: a rebuilt sprite replaces the node instead of swapping its material.
                    for (c in members) key(c.id) {
                        val rig = rigs[c.id]
                        if (rig != null) key(rig) {
                            val sprite = rig.sprite
                            // Billboard quad in the anchor's frame: feet at the node origin (center = height / 2), front = +Z.
                            PlaneNode(
                                size = Size(x = sprite.widthM, y = sprite.heightM, z = 0f),
                                center = Position(y = sprite.heightM / 2f),
                                normal = Direction(z = 1f),
                                materialInstance = sprite.material,
                                apply = {
                                    isTouchable = false // taps go through to the table hit test, drags never reach a node
                                    isVisible = false // review fix (d): shown by the poser on its first placed frame
                                    rig.node = this
                                },
                            )
                        }
                    }
                    // T21 (a) Plan B: invisible depth-writing cup cylinders / book boxes (Occluders.kt).
                    if (occlusion == Occlusion.BOXES) level?.let { Occluders(it, occluderMaterial) }
                    // Debug chrome only: tinted footprints + boundary loop on the plane (scan/FootprintOverlay.kt).
                    if (slot.debug) level?.let { FootprintOverlay(it, footprintMaterials) }
                }
            }
        }
        if (showHint || holdHint) {
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
