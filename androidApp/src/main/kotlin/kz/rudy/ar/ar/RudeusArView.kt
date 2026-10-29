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
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay
import kz.rudy.ar.ArSlot
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
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
 */
@Composable
fun RudeusArView(modifier: Modifier = Modifier, slot: ArSlot) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val currentSlot by rememberUpdatedState(slot)
    // Plain holders, not state: a Frame arrives every ~16 ms and must not recompose the tree.
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val anchorRef = remember { arrayOfNulls<Anchor>(1) } // mirror of `anchor` for the frame callback
    val characterNode = remember { arrayOfNulls<PlaneNode>(1) }
    val loop = remember { GameLoop() }
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

    LaunchedEffect(hintTaps) {
        if (hintTaps > 0) {
            showHint = true
            delay(HINT_VISIBLE_MS)
            showHint = false
        }
    }

    Box(modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            planeRenderer = anchor == null,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                config.depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                probe.onDepthConfigured(depthSupported, config.depthMode)
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
                    val state = loop.onFrame(now, tracking, currentSlot.onCharacterEvent)
                    val node = characterNode[0]
                    if (!tracking) reappearAtMs[0] = now + REAPPEAR_AFTER_TRACKING_MS
                    // While not tracking the camera pose is meaningless and the AnchorNode hides its children.
                    if (tracking && node != null) {
                        poser.apply(node, state, planeCoords(a, frame.camera.pose), loop.walkPhaseMs)
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
