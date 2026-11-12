package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Vec2

// T54 — the touch seam: the three questions only the AR session can answer once the anchor exists. The table engine asks
// them when a tap or a finger needs them (never per frame), on the main thread, about the latest frame. Plain values in
// plane coordinates and view pixels; the host converts from its session (Android: RudeusArView's SessionPort over
// PlaneCoords.kt, iOS: the current ARFrame). Nothing here can create an anchor.

/** What only the AR session can answer: main thread, after the anchor, about the latest frame. */
interface ArPort {
    /**
     * The anchor's surface under view pixel ([xPx], [yPx]): a plane hit inside its polygon, within [toleranceM] of the
     * anchor plane, with the depth reading there. Null: the ray misses it, no frame, or the camera is not tracking.
     */
    fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit?

    /**
     * Depth readings at [samples] points of the walk (point i at `from + (to - from) * i / samples`, i = 1..samples), as
     * height above the plane in metres, null where there is none. No depth sensor: all nulls, which never refuses a walk.
     */
    fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?>

    /**
     * The latest frame's camera as a screen. Asked only when a touch needs a pick, never per frame. Null: no frame, no
     * anchor, or the view is not laid out; the engine then picks by the table point.
     */
    fun screenView(): ScreenView?
}

/** The table under a finger: plane coords and, when the Depth API saw something there, its height above the plane. */
class TableHit(val uv: Vec2, val depthGapM: Float?)

/**
 * The camera of one frame as a screen, for picking a character under a finger. Column-major 4x4 matrices, as OpenGL,
 * ARCore and ARKit give them.
 */
class ScreenView(
    /** 16: (u, h, v, 1) in plane coordinates -> eye space (-Z ahead), display-oriented. */
    val planeToEye: FloatArray,
    /** 16: this view's projection; `[0]` is 2 * focal / width. */
    val eyeToClip: FloatArray,
    /** The AR view, pixels, > 0. */
    val widthPx: Int,
    /** The AR view, pixels, > 0. */
    val heightPx: Int,
    /** A pick rectangle grows by this on every side (Android: GRAB_MARGIN_DP * density). */
    val pickMarginPx: Float,
) {
    companion object {
        /**
         * [planeToEye] = [worldToEye] x [planeToWorld] (the anchor's model matrix), column-major. Fresh arrays: the host
         * may reuse its own scratch arrays for the next pick.
         */
        fun of(
            worldToEye: FloatArray,
            planeToWorld: FloatArray,
            eyeToClip: FloatArray,
            widthPx: Int,
            heightPx: Int,
            pickMarginPx: Float,
        ): ScreenView {
            val planeToEye = FloatArray(16)
            for (c in 0 until 4) for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += worldToEye[k * 4 + r] * planeToWorld[c * 4 + k]
                planeToEye[c * 4 + r] = s
            }
            return ScreenView(planeToEye, eyeToClip.copyOf(), widthPx, heightPx, pickMarginPx)
        }
    }
}
