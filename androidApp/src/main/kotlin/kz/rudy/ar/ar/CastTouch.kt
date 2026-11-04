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

/** The grab radius never goes below this (times the surface scale), so a small character is still easy to catch. */
internal const val GRAB_MIN_M = 0.03f

/** A bound pointer leads its member at most this often (one hit test per lead). */
internal const val GRAB_MOVE_MS = 60L

/** ... and only when the table point under the finger moved at least this far (metres). */
internal const val GRAB_MOVE_MIN_M = 0.005f

/** What the touch bookkeeping needs from the AR host; every call is on the main thread. */
internal interface CastTable {
    /** Plane coords under screen pixel (x, y), null when the ray misses the anchor's surface. */
    fun hitUv(x: Float, y: Float): Vec2?

    /** The nearest member on the ground within the grab radius of [uv], or null. */
    fun grabbable(uv: Vec2): String?

    /** Lead member [id] toward [uv] (clamped to the level's boundary; the walk is physics'). */
    fun lead(id: String, uv: Vec2)

    /** A finger landed on [id]: it is the active member now. */
    fun onGrabbed(id: String)
}

internal class CastTouch {
    private class Binding(val id: String, var lastUv: Vec2, var lastLeadMs: Long)

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
                val uv = table.hitUv(event.getX(i), event.getY(i)) ?: return captured
                val id = table.grabbable(uv) ?: return captured
                if (bindings.values.any { it.id == id }) return captured // one finger per member
                val pointer = event.getPointerId(i)
                bindings[pointer] = Binding(id, uv, nowMs)
                captured = true
                table.onGrabbed(id)
                Log.i(GATE_TAG, "grab pointer=$pointer id=$id")
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val b = bindings[event.getPointerId(i)] ?: continue
                if (nowMs - b.lastLeadMs < GRAB_MOVE_MS) continue
                val uv = table.hitUv(event.getX(i), event.getY(i)) ?: continue // off the table: the last target stays
                b.lastLeadMs = nowMs
                if (uv.distanceTo(b.lastUv) < GRAB_MOVE_MIN_M) continue
                b.lastUv = uv
                table.lead(b.id, uv)
            }
            MotionEvent.ACTION_POINTER_UP -> release(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (pointer in bindings.keys.toList()) release(pointer)
                val was = captured
                captured = false
                return was
            }
        }
        return captured
    }

    private fun release(pointer: Int) {
        val b = bindings.remove(pointer) ?: return
        Log.i(GATE_TAG, "release pointer=$pointer id=${b.id}")
    }
}
