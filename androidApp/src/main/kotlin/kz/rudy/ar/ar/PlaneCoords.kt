package kz.rudy.ar.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import io.github.sceneview.math.Position
import kz.rudy.ar.core.domain.Vec2

// HAND-REVIEW: the ONLY place that converts between ARCore poses and :core plane coordinates.
// Level frame = the session anchor's pose: local X = u, local Z = v, local Y = h (metres).
// T02: added `localPosition` — the character node is a child of SceneView's AnchorNode, whose transform IS the
// anchor pose, so a child's local position is the plane frame directly (the inverse of `planeCoords`).

/** World pose -> (u, v) in the anchor's plane frame. Also used for the camera (billboard yaw). */
fun planeCoords(anchor: Anchor, world: Pose): Vec2 {
    val local = anchor.pose.inverse().compose(world)
    return Vec2(local.tx(), local.tz())
}

/** (u, v, h) in the anchor's plane frame -> world pose (translation only, identity rotation). */
fun toWorld(anchor: Anchor, p: Vec2, h: Float): Pose =
    anchor.pose.compose(Pose.makeTranslation(p.u, h, p.v))

/** (u, v, h) -> local position of a node parented to the session AnchorNode. Same frame as [toWorld]. */
fun localPosition(p: Vec2, h: Float): Position = Position(x = p.u, y = h, z = p.v)
