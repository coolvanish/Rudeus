package kz.rudy.ar.ar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kz.rudy.ar.core.domain.Vec2

// HAND-REVIEW: minimal AR host for the Day 1/3 gates. Plane detection + ONE anchor per session (locked decision).
// T02 adds the billboard quad and the physics game loop; keep this file the single owner of the session anchor.
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

    ARSceneView(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = anchor == null,
        sessionConfiguration = { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode =
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
                else Config.DepthMode.DISABLED
            config.focusMode = Config.FocusMode.AUTO
        },
        onSessionUpdated = { _, frame -> latestFrame[0] = frame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, _ ->
                val frame = latestFrame[0] ?: return@rememberOnGestureListener
                val hit = frame.hitTest(event).firstOrNull {
                    val t = it.trackable
                    t is Plane && t.isPoseInPolygon(it.hitPose)
                } ?: return@rememberOnGestureListener
                val a = anchor
                if (a == null) {
                    anchor = hit.createAnchor()
                    onAnchorPlaced()
                } else {
                    onWalkTo(planeCoords(a, hit.hitPose))
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
}
