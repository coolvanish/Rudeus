package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Vec2

// T56 — the frame seam: what a host hands the table engine once per AR frame after the anchor exists. Plain values in
// plane coordinates; the host converts from its AR session's poses (Android: PlaneCoords.kt), nothing here knows one.
// Later fields are appended with a default that means "as today" (S4 appends the clip matrix and the view size).

/** One AR frame after the anchor exists. Plain values; the engine keeps none of them. */
class FrameInput(
    val nowMs: Long,          // the host's monotonic clock (Android: SystemClock.elapsedRealtime)
    val tracking: Boolean,    // camera AND session anchor TRACKING; false = physics pauses, nothing resets
    val camera: CameraFrame,  // read on every frame, trusted only while [tracking]
)

/** Where the AR camera is this frame, in the anchor's plane frame. */
class CameraFrame(
    val pos: Vec2,            // camera position in plane coordinates, metres (`planeCoords`)
    val heightM: Float,       // its height above the plane, metres (`planeHeight`)
    val right: Vec2,          // screen-right in the plane (the display-oriented camera's +X), any length
)
