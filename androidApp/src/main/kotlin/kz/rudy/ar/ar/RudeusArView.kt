package kz.rudy.ar.ar

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kz.rudy.ar.core.domain.Vec2

// HAND-REVIEW: minimal AR host for the Day 1/3 gates. Plane detection + ONE anchor per session (locked decision).
// T02 adds the billboard quad and the physics game loop; keep this file the single owner of the session anchor.
// HAND-REVIEW: T01 Stream C — the `probe.*`, `recorder.*` and GateRecordButton lines are Day 1 gate probes
// (see GateProbes.kt). They only log to tag RudeusGate and toggle ARCore recording; remove after the gate.
@Composable
fun RudeusArView(
    modifier: Modifier = Modifier,
    onAnchorPlaced: () -> Unit = {},
    onWalkTo: (Vec2) -> Unit = {},
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // Plain holder, not state: a Frame arrives every ~16 ms and must not recompose the tree.
    val latestFrame = remember { arrayOfNulls<Frame>(1) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val probe = remember { GateProbe() }
    val recorder = rememberARRecorder()

    Box(modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
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
            },
            onTrackingFailureChanged = { reason -> Log.i(GATE_TAG, "tracking_failure=$reason") },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    val frame = latestFrame[0] ?: return@rememberOnGestureListener
                    val hit = frame.hitTest(event).firstOrNull {
                        val t = it.trackable
                        t is Plane && t.isPoseInPolygon(it.hitPose)
                    } ?: return@rememberOnGestureListener
                    val a = anchor
                    if (a == null) {
                        val created = hit.createAnchor()
                        anchor = created
                        probe.onAnchorPlaced(created)
                        onAnchorPlaced()
                    } else {
                        val uv = planeCoords(a, hit.hitPose)
                        probe.onTap(a, hit.hitPose, uv)
                        onWalkTo(uv)
                    }
                }
            ),
        ) {
            anchor?.let { a ->
                AnchorNode(anchor = a) {
                    // T02: billboard quad node for the character goes here, positioned via toWorld(a, pos, h).
                }
            }
        }
        GateRecordButton(
            recorder = recorder,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
        )
    }
}
