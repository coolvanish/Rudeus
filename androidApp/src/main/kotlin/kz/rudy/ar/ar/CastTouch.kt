package kz.rudy.ar.ar

import android.util.Log
import android.view.MotionEvent
import kz.rudy.ar.core.domain.Vec2

// HAND-REVIEW: T32 — finger-led characters. ARSceneView's raw `onTouchEvent: ((MotionEvent, HitResult?) -> Boolean)?`
// (ARSceneView.kt:758 in the shipped 4.31.0 sources) runs before the gesture detector and returning true keeps the event
// from it (ARSceneView.kt:1401). This class only keeps the pointerId -> memberId bindings and the move throttle; the hit
// test, the grab radius and the walk go through [CastTable], which RudeusArView implements over the same anchor and
// GameLoop as the taps. No ARCore/SceneView type here, so the bookkeeping reads in one screen.
// Per stream (ACTION_DOWN .. ACTION_UP/CANCEL): a DOWN / POINTER_DOWN on a member binds that pointer to it and makes
// it active; a pointer that lands on the table binds nothing. MOVE leads every bound pointer's member at most every
// GRAB_MOVE_MS and only when the table point moved GRAB_MOVE_MIN_M (a resting finger sends nothing). POINTER_UP / UP /
// CANCEL unbind (the last target stays, the member finishes its walk). Return value: false until a pointer of the
// stream is bound, from then on true for the rest of the stream, so the existing single-tap path (anchor placement,
// walk the ACTIVE member) keeps working for a finger that never touched a character. Consuming the whole stream once
// bound, not only the bound pointers' events, is deliberate: the detector would otherwise see DOWN(table) .. UP(table)
// around a grab by a second finger and walk the newly active member to the first finger (a ghost tap).
// HAND-REVIEW: T32 review fix — (1) the grab test is in SCREEN space (`grabbable(x, y)`: a finger on the sprite's head hits
// the table 4-10 cm behind the feet, so a table-point test missed the body); the table point is only the lead target.
// (2) POINTER_UP / UP lead once more from the lifting finger's last position, so a flick does not stop up to 60 ms short.

/** The grab radius never goes below this (times the surface scale), so a small character is still easy to catch. */
internal const val GRAB_MIN_M = 0.03f

/** A bound pointer leads its member at most this often (one hit test per lead). */
internal const val GRAB_MOVE_MS = 60L

/** ... and only when the table point under the finger moved at least this far (metres). */
internal const val GRAB_MOVE_MIN_M = 0.005f

/** The table under a finger: plane coords and, when the Depth API saw something there, its height above the plane. */
internal class TableHit(val uv: Vec2, val depthGapM: Float?)

/** What the touch bookkeeping needs from the AR host; every call is on the main thread. */
internal interface CastTable {
    /** The anchor's surface under screen pixel (x, y), null when the ray misses it. */
    fun hitUv(x: Float, y: Float): TableHit?

    /** The member whose quad is under screen pixel (x, y) (nearest when several), or null. */
    fun grabbable(x: Float, y: Float): String?

    /** Lead member [id] toward [hit] (clamped to the level's boundary, refused past a depth edge; the walk is physics'). */
    fun lead(id: String, hit: TableHit)

    /** A finger landed on [id]: it is the active member now. */
    fun onGrabbed(id: String)
}

internal class CastTouch {
    private class Binding(val id: String, var lastUv: Vec2?, var lastLeadMs: Long)

    private val bindings = HashMap<Int, Binding>() // pointerId -> member
    private var captured = false

    /** True while any finger leads a member. */
    val active: Boolean get() = bindings.isNotEmpty()

    /** A member that left the table: its finger leads nothing any more. */
    fun unbindMember(id: String) {
        val gone = bindings.filterValues { it.id == id }.keys
        for (pointer in gone) release(pointer)
    }

    /** Returns true when the event belongs to this stream (see the header). [nowMs] = SystemClock.elapsedRealtime. */
    fun onTouch(event: MotionEvent, nowMs: Long, table: CastTable): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { // a new stream: nothing from the last one survives
                    for (pointer in bindings.keys.toList()) release(pointer)
                    captured = false
                }
                val i = event.actionIndex
                val id = table.grabbable(event.getX(i), event.getY(i)) ?: return captured
                if (bindings.values.any { it.id == id }) return captured // one finger per member
                val pointer = event.getPointerId(i)
                bindings[pointer] = Binding(id, lastUv = null, lastLeadMs = nowMs) // the first move always leads
                captured = true
                table.onGrabbed(id)
                Log.i(GATE_TAG, "grab pointer=$pointer id=$id")
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val b = bindings[event.getPointerId(i)] ?: continue
                if (nowMs - b.lastLeadMs < GRAB_MOVE_MS) continue
                b.lastLeadMs = nowMs
                lead(b, event.getX(i), event.getY(i), table)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                bindings[event.getPointerId(i)]?.let { lead(it, event.getX(i), event.getY(i), table) }
                release(event.getPointerId(i))
            }
            MotionEvent.ACTION_UP -> {
                bindings[event.getPointerId(0)]?.let { lead(it, event.getX(0), event.getY(0), table) }
                for (pointer in bindings.keys.toList()) release(pointer)
                val was = captured
                captured = false
                return was
            }
            MotionEvent.ACTION_CANCEL -> {
                for (pointer in bindings.keys.toList()) release(pointer)
                val was = captured
                captured = false
                return was
            }
        }
        return captured
    }

    /** One hit test; leads when the finger is on the table and its point moved GRAB_MOVE_MIN_M since the last lead. */
    private fun lead(b: Binding, x: Float, y: Float, table: CastTable) {
        val hit = table.hitUv(x, y) ?: return // off the table: the last target stays
        val last = b.lastUv
        if (last != null && hit.uv.distanceTo(last) < GRAB_MOVE_MIN_M) return
        b.lastUv = hit.uv
        table.lead(b.id, hit)
    }

    private fun release(pointer: Int) {
        val b = bindings.remove(pointer) ?: return
        Log.i(GATE_TAG, "release pointer=$pointer id=${b.id}")
    }
}
