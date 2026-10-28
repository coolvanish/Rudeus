package kz.rudy.ar.ar

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kz.rudy.ar.core.domain.Vec2
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

/** How long the "wait, finding the table" hint stays after a premature tap. */
private const val HINT_VISIBLE_MS = 1_500L

/**
 * HAND-REVIEW: after a tracking loss (camera covered, too little texture) ARCore re-localises and corrects the anchor
 * pose over the next frames; on the Pixel the dino visibly slid ~20 cm when the hand came off the camera. Keep the
 * character hidden this long after tracking returns, so the correction happens while it is invisible.
 */
private const val REAPPEAR_AFTER_TRACKING_MS = 800L

/**
 * HAND-REVIEW: walk taps only count on the anchor's own surface, within this height of the anchor. Otherwise a tap
 * past the table edge hits the floor plane and the character walks off into the air (planeCoords drops the height).
 */
private const val WALK_TAP_HEIGHT_TOLERANCE_M = 0.03f

@Composable
fun RudeusArView(
    modifier: Modifier = Modifier,
    onAnchorPlaced: () -> Unit = {},
    onWalkTo: (Vec2) -> Unit = {},
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Plain holders, not state: a Frame arrives every ~16 ms and must not recompose the tree.
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    val anchorRef = remember { arrayOfNulls<Anchor>(1) } // mirror of `anchor` for the frame callback
    val characterNode = remember { arrayOfNulls<PlaneNode>(1) }
    val loop = remember { GameLoop() }
    val reappearAtMs = remember { longArrayOf(0L) } // character hidden until then (after a tracking loss)
    val settle = remember { PlaneSettle() }
    // Texture + material instance live as long as the engine; the MaterialLoader owns the instance (SceneView docs).
    val sprite = remember(engine, materialLoader) { spriteFromAssets(engine, materialLoader, context.assets) }
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
                } else {
                    val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
                        a.trackingState == TrackingState.TRACKING
                    val state = loop.onFrame(now, tracking)
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
                    val hit = frame.hitTest(event).firstOrNull {
                        val t = it.trackable
                        t is Plane && t.isPoseInPolygon(it.hitPose) &&
                            (a == null || abs(it.hitPose.ty() - a.pose.ty()) <= WALK_TAP_HEIGHT_TOLERANCE_M)
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
                        probe.onAnchorPlaced(created)
                        Log.i(GATE_TAG, "anchor_plane_age_ms=$age")
                        onAnchorPlaced()
                    } else if (hit != null) {
                        val uv = planeCoords(a, hit.hitPose)
                        probe.onTap(a, hit.hitPose, uv)
                        loop.walkTo(uv)
                        onWalkTo(uv)
                    }
                }
            ),
        ) {
            anchor?.let { a ->
                // HAND-REVIEW: SceneView's AnchorNode defaults to isPositionEditable = true; a drag would detach and
                // recreate the session anchor (AnchorNode.onMoveBegin/onMoveEnd) and freeze the game on a dead anchor.
                AnchorNode(anchor = a, apply = { isPositionEditable = false }) {
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
        if (showHint) {
            ScrimPill(t(StringId.ar_wait_table), modifier = Modifier.align(Alignment.Center))
        }
        // Moved from TopEnd to CenterEnd: the shared Play HUD owns the top-right corner.
        GateRecordButton(
            recorder = recorder,
            modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
        )
    }
}
