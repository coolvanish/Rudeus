package kz.rudy.ar.ar

import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import io.github.sceneview.math.Position
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.sqrt

// HAND-REVIEW: the ONLY place that converts between ARCore poses and :core plane coordinates.
// Level frame = the session anchor's pose: local X = u, local Z = v, local Y = h (metres).
// T02: added `localPosition` — the character node is a child of SceneView's AnchorNode, whose transform IS the
// anchor pose, so a child's local position is the plane frame directly (the inverse of `planeCoords`).

/** World pose -> (u, v) in the anchor's plane frame. Also used for the camera (billboard yaw). */
fun planeCoords(anchor: Anchor, world: Pose): Vec2 {
    val local = anchor.pose.inverse().compose(world)
    return Vec2(local.tx(), local.tz())
}

/** HAND-REVIEW: Pixel 2026-11-03 — world pose -> height above the anchor's plane, metres. The h that goes with [planeCoords]. */
fun planeHeight(anchor: Anchor, world: Pose): Float = anchor.pose.inverse().compose(world).ty()

/**
 * HAND-REVIEW: Pixel 2026-11-03 — what the Depth API sees at [samples] points along the walk [from] -> [to], as height
 * above the anchor's plane, null where it sees nothing. `Frame.hitTest(origin, direction)` is the arbitrary-ray form of the
 * tap's hit test. On the Pixel it gave real readings on most taps, but on 9 of 37 all samples came back as one value up
 * to 1.2 m above the plane, not on the ray as far as it looks: only a hit within [ON_RAY_M] of the ray counts, because the same
 * bug looking at the floor would refuse a good tap.
 */
fun depthGapsOnWay(frame: Frame, anchor: Anchor, from: Vec2, to: Vec2, samples: Int): List<Float?> {
    val camera = frame.camera.pose
    val origin = floatArrayOf(camera.tx(), camera.ty(), camera.tz())
    return (1..samples).map { i ->
        val p = toWorld(anchor, from + (to - from) * (i / samples.toFloat()), 0f)
        val d = floatArrayOf(p.tx() - origin[0], p.ty() - origin[1], p.tz() - origin[2])
        val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        if (len < 1e-3f) return@map null
        val direction = floatArrayOf(d[0] / len, d[1] / len, d[2] / len)
        val hit = frame.hitTest(origin, 0, direction, 0).firstOrNull { it.trackable is DepthPoint }?.hitPose ?: return@map null
        val ox = hit.tx() - origin[0]; val oy = hit.ty() - origin[1]; val oz = hit.tz() - origin[2]
        val along = ox * direction[0] + oy * direction[1] + oz * direction[2]
        if (ox * ox + oy * oy + oz * oz - along * along > ON_RAY_M * ON_RAY_M) null else planeHeight(anchor, hit)
    }
}

// HAND-REVIEW: Pixel 2026-11-03 — see depthGapsOnWay: a depth hit farther than this from the ray is not on the walk.
private const val ON_RAY_M = 0.05f

/** (u, v, h) in the anchor's plane frame -> world pose (translation only, identity rotation). */
fun toWorld(anchor: Anchor, p: Vec2, h: Float): Pose =
    anchor.pose.compose(Pose.makeTranslation(p.u, h, p.v))

/** (u, v, h) -> local position of a node parented to the session AnchorNode. Same frame as [toWorld]. */
fun localPosition(p: Vec2, h: Float): Position = Position(x = p.u, y = h, z = p.v)
