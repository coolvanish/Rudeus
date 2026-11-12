package kz.rudy.ar.core.session

// T54 — a touch as the table engine reads it: the platform's event in plain values (Android: a MotionEvent mapped by
// RudeusArView, iOS: UITouch phases). View pixels, origin top-left, y down. The host's clock, not the event's.

/** One touch event. UP carries the lifting pointer at index 0; [actionIndex] names the pointer of a POINTER_DOWN / POINTER_UP. */
class TouchEvent(
    val action: TouchAction,
    val actionIndex: Int,
    val pointers: List<TouchPoint>,
    /** The host's monotonic clock when the callback ran (Android: SystemClock.elapsedRealtime), not the event's time. */
    val nowMs: Long,
)

/** What happened, as Android's masked actions name it; anything else is [OTHER]. */
enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL, OTHER }

/** One pointer of an event: its id for the whole stream, and where it is, view pixels. */
class TouchPoint(val id: Int, val xPx: Float, val yPx: Float)
