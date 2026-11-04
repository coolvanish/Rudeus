package kz.rudy.ar.ar

import android.util.Log
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.node.PlaneNode
import kz.rudy.ar.core.domain.CharacterState
import java.util.Locale

// HAND-REVIEW: Pixel 2026-11-04 — logs only, nothing here changes the game; delete this file and the spriteProbe.* hooks in
// RudeusArView once the cause is known. A screen recording showed the first character's quad standing at the anchor, no
// emote on it, while its physics reached every tap (`jump u= v=`) and later members moved normally. What each line tells:
//   `sprite ... node= local=`: `node` keeps changing but `local` does not -> the poser's writes miss this entity.
//   `sprite ... local= world=`: `local` changes but `world` does not -> the parent link is broken.
//   `sprite_stale=true`: SceneView's cached `Node.transformInstance` (Node.kt:788) differs from Filament's real instance.
//   two `node_created` for one id with no `rig_disposed`: the quad on screen is not the node the poser writes.
//   `sprite_skipped`: the poser never reached the member (no rig or no node yet).
//   `planes subsumed=`: PlaneVisualizer.destroy (PlaneVisualizer.kt:365) leaves a dead transform that Filament collects later
//   without SceneView's generation bump; compare its time with `sprite_stale`. `footprints_on=false` destroys nodes, which bumps it.

/** False silences every line below. */
internal const val SPRITE_PROBE_ENABLED = true

private const val SPRITE_LOG_PERIOD_MS = 1_000L
private const val PLANE_LOG_PERIOD_MS = 250L

private fun hash(o: Any): String = Integer.toHexString(System.identityHashCode(o))

/** Main thread only (frame callback and composition); plain holders, so a frame recomposes nothing. */
internal class SpriteProbe(private val engine: Engine) {
    private class Member(var lastLogMs: Long = 0L, var stale: Boolean = false)

    private val members = HashMap<String, Member>()
    private val local = FloatArray(16)
    private val world = FloatArray(16)
    private var lastPlaneCheckMs = 0L
    private var lastPlanes = ""

    /** [what] = created or disposed. */
    fun onRig(what: String, id: String, rig: Any) {
        if (SPRITE_PROBE_ENABLED) Log.i(GATE_TAG, "rig_$what id=$id rig=${hash(rig)}")
    }

    fun onNode(id: String, rig: Any, node: PlaneNode) {
        if (!SPRITE_PROBE_ENABLED) return
        Log.i(GATE_TAG, "node_created id=$id rig=${hash(rig)} node=${hash(node)} entity=${node.entity} instance=${node.transformInstance}")
    }

    /** At most [PLANE_LOG_PERIOD_MS] apart and only when a count changed. */
    fun onFrame(nowMs: Long, session: Session) {
        if (!SPRITE_PROBE_ENABLED || nowMs - lastPlaneCheckMs < PLANE_LOG_PERIOD_MS) return
        lastPlaneCheckMs = nowMs
        val planes = session.getAllTrackables(Plane::class.java)
        val line = "total=${planes.size} tracking=${planes.count { it.trackingState == TrackingState.TRACKING }} " +
            "subsumed=${planes.count { it.subsumedBy != null }} stopped=${planes.count { it.trackingState == TrackingState.STOPPED }}"
        if (line != lastPlanes) {
            lastPlanes = line
            Log.i(GATE_TAG, "planes $line")
        }
    }

    /** The frame loop could not pose member [id]. Once a second at most. */
    fun onSkipped(nowMs: Long, id: String, hasRig: Boolean) {
        if (!SPRITE_PROBE_ENABLED) return
        val m = members.getOrPut(id) { Member() }
        if (nowMs - m.lastLogMs < SPRITE_LOG_PERIOD_MS) return
        m.lastLogMs = nowMs
        Log.i(GATE_TAG, "sprite_skipped id=$id rig=$hasRig node=false")
    }

    /** Right after the poser wrote [node]: one JNI lookup per frame, the full line once a second. */
    fun onPosed(nowMs: Long, id: String, state: CharacterState, node: PlaneNode, rig: Any, anchor: Anchor) {
        if (!SPRITE_PROBE_ENABLED) return
        val tm = engine.transformManager
        val cached = node.transformInstance
        val actual = tm.getInstance(node.entity)
        val m = members.getOrPut(id) { Member() }
        if ((cached != actual) != m.stale) {
            m.stale = cached != actual
            Log.i(GATE_TAG, "sprite_stale=${m.stale} id=$id cached=$cached actual=$actual node=${hash(node)}")
        }
        if (nowMs - m.lastLogMs < SPRITE_LOG_PERIOD_MS) return
        m.lastLogMs = nowMs
        if (actual == 0) {
            Log.i(GATE_TAG, "sprite id=$id no_transform cached=$cached node=${hash(node)}")
            return
        }
        tm.getTransform(actual, local)
        tm.getWorldTransform(actual, world)
        val want = toWorld(anchor, state.pos, state.h)
        val n = node.position
        Log.i(
            GATE_TAG,
            ("sprite id=%s mode=%s phys=(%.3f,%.3f,%.3f) node=(%.3f,%.3f,%.3f) local=(%.3f,%.3f,%.3f) " +
                "world=(%.3f,%.3f,%.3f) want=(%.3f,%.3f,%.3f) cached=%d actual=%d parent=%d anchor_node=%s visible=%b rig=%s node=%s").format(
                Locale.US, id, state.mode, state.pos.u, state.pos.v, state.h, n.x, n.y, n.z,
                local[12], local[13], local[14], world[12], world[13], world[14], want.tx(), want.ty(), want.tz(),
                cached, actual, tm.getParent(actual), node.parent?.entity, node.isVisible, hash(rig), hash(node),
            ),
        )
    }
}
