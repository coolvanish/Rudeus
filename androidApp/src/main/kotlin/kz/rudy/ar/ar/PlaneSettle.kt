package kz.rudy.ar.ar

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

// HAND-REVIEW: T02 — anchor placement gate. Measured on the Pixel 9 Pro XL on Oct 28 (Day 1 probes): an anchor
// placed 3.7 s after plane detection jumped 4.7 cm in its first 10 s; one placed later drifted 0.07 cm over 50 s.
// So the first tap creates the session anchor only once the hit plane has been TRACKING for ANCHOR_PLANE_SETTLE_MS;
// earlier taps show a hint and do nothing (RudeusArView). Plain holder, main thread only, no Compose state.

/**
 * How long a horizontal plane must have been continuously TRACKING before a tap on it may create the anchor.
 * HAND-REVIEW: 10 s, not 2 s (review of draft 1): the only data points are "3.7 s after detection: 4.7 cm jump" and
 * "~21 s after: 0.07 cm / 50 s", so 2 s sat inside the measured bad window. Lower it once the Pixel shows a shorter
 * wait is enough.
 */
internal const val ANCHOR_PLANE_SETTLE_MS = 10_000L

internal class PlaneSettle {
    /** ARCore Planes are hashable (same object across frames); a table yields a handful of them. */
    private val firstTrackingMs = HashMap<Plane, Long>()

    /** Per frame, only the planes ARCore updated this frame; one map write each. Stop calling once anchored. */
    fun onFrame(frame: Frame, nowMs: Long) {
        for (p in frame.getUpdatedTrackables(Plane::class.java)) {
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (p.trackingState == TrackingState.TRACKING) {
                if (p !in firstTrackingMs) firstTrackingMs[p] = nowMs
            } else {
                firstTrackingMs.remove(p) // PAUSED / STOPPED: the clock restarts when it tracks again
            }
        }
    }

    /** Milliseconds [plane] has been continuously TRACKING, or null if it is not tracking now. */
    fun trackingAgeMs(plane: Plane, nowMs: Long): Long? = firstTrackingMs[plane]?.let { nowMs - it }

    fun isSettled(plane: Plane, nowMs: Long): Boolean =
        (trackingAgeMs(plane, nowMs) ?: return false) >= ANCHOR_PLANE_SETTLE_MS

    /** T14: true when any upward plane is settled, i.e. a tap on it would create the anchor (`ArSlot.onSurfaceReady`). */
    fun anySettled(nowMs: Long): Boolean {
        for (first in firstTrackingMs.values) if (nowMs - first >= ANCHOR_PLANE_SETTLE_MS) return true
        return false
    }
}
