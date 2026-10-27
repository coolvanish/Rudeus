package kz.rudy.ar.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import kz.rudy.ar.core.domain.Vec2

// HAND-REVIEW: the ONLY place that converts between ARCore poses and :core plane coordinates.
// Level frame = the session anchor's pose: local X = u, local Z = v, local Y = h (metres).

/** World pose -> (u, v) in the anchor's plane frame. */
fun planeCoords(anchor: Anchor, world: Pose): Vec2 {
    val local = anchor.pose.inverse().compose(world)
    return Vec2(local.tx(), local.tz())
}

/** (u, v, h) in the anchor's plane frame -> world pose (translation only, identity rotation). */
fun toWorld(anchor: Anchor, p: Vec2, h: Float): Pose =
    anchor.pose.compose(Pose.makeTranslation(p.u, h, p.v))
