package kz.rudy.ar.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.google.android.filament.MaterialInstance
import io.github.sceneview.NodeScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kotlin.math.PI
import kotlin.math.sqrt

// HAND-REVIEW: T21 — occlusion, Plan A / Plan B (ai/build-plan.md cut line 4: depth -> occluder boxes -> none).
// Plan A is SceneView's depth-aware camera stream (`ARCameraStream.isDepthOcclusionEnabled`, RudeusArView): the
// camera fragments carry ARCore's per-pixel depth, so the character is depth-tested against the real cup. Plan B is
// this file: one invisible, depth-writing node per solid LevelObject under the session AnchorNode. The decision is
// made once per session from `Session.isDepthModeSupported` in `occlusionFor` and logged as `occlusion=...`.
// Flip for the Pixel side-by-side: FORCE_OCCLUDER_BOXES = true (boxes even though depth is supported);
// OCCLUDER_BOXES_WHEN_NO_DEPTH = false ("none" on a device without depth).
// Material: SceneView 4.31.0 ships `materialLoader.createOcclusionInstance()` (assets/materials/occlusion.filamat:
// opaque pass, writes depth, emits no colour, double-sided) — the brief's hand-rolled `createColorInstance(Black)` +
// Filament `setColorWrite(false)` / `setDepthWrite(true)` is the same thing without the purpose-built shader; the
// shipped one is used. ONE instance shared by every node (MaterialLoader destroys it with the scene).
// Geometry: nodes live directly in the anchor frame (local X = u, Y = h, Z = v; PlaneCoords.kt), so unlike
// FootprintOverlay no rotation and no v sign flip is needed. A round footprint (area / bbox area >= CUP_ROUNDNESS)
// becomes a CylinderNode of equal area; anything else a CubeNode on the footprint's bounding box. Base on the plane
// (center y = heightM / 2), keyed on (id, footprint, heightM) like the overlay, so a re-scan rebuilds instead of
// resizing. The character quad is TRANSPARENT blending, drawn after the opaque pass and depth-tested against it:
// no render priorities are touched. PIXEL CHECK: with boxes on, the debug footprints (2 mm above the plane) sit
// inside the boxes and are hidden by them — expected; turn FORCE_OCCLUDER_BOXES off to see both.

/** Use occluder boxes even when the device supports depth (side-by-side on the Pixel). */
internal const val FORCE_OCCLUDER_BOXES = false

/** Without depth support fall back to occluder boxes (true) or to no occlusion at all (false). */
internal const val OCCLUDER_BOXES_WHEN_NO_DEPTH = true

/** `footprint.area / bboxArea` at or above this is "a cup": a cylinder, not a box. */
internal const val CUP_ROUNDNESS = 0.7f

/** Cylinder facets for a cup occluder. */
internal const val OCCLUDER_SIDES = 16

/** Which occlusion the session runs; decided once from depth support (logged as `occlusion=`). */
internal enum class Occlusion { DEPTH, BOXES, NONE }

internal fun occlusionFor(depthSupported: Boolean): Occlusion = when {
    FORCE_OCCLUDER_BOXES -> Occlusion.BOXES
    depthSupported -> Occlusion.DEPTH
    OCCLUDER_BOXES_WHEN_NO_DEPTH -> Occlusion.BOXES
    else -> Occlusion.NONE
}

/** Solid objects get an occluder; a CLIFF is the absence of a table. */
private fun LevelObject.isSolid(): Boolean = kind != ObjectKind.CLIFF && heightM > 0f

/** Plan B occluders. Call inside the session `AnchorNode { }` only while `occlusion == Occlusion.BOXES`. */
@Composable
internal fun NodeScope.Occluders(level: Level, material: MaterialInstance) {
    for (o in level.objects) {
        if (!o.isSolid()) continue
        key(o.id, o.footprint, o.heightM) {
            val pts = o.footprint.points
            val minU = pts.minOf { it.u }
            val maxU = pts.maxOf { it.u }
            val minV = pts.minOf { it.v }
            val maxV = pts.maxOf { it.v }
            val bboxArea = (maxU - minU) * (maxV - minV)
            val round = bboxArea > 0f && o.footprint.area / bboxArea >= CUP_ROUNDNESS
            if (round) {
                val c = o.footprint.centroid()
                CylinderNode(
                    radius = sqrt(o.footprint.area / PI.toFloat()),
                    height = o.heightM,
                    center = Position(y = o.heightM / 2f),
                    sideCount = OCCLUDER_SIDES,
                    materialInstance = material,
                    position = Position(x = c.u, z = c.v),
                    apply = { isTouchable = false },
                )
            } else {
                CubeNode(
                    size = Size(x = maxU - minU, y = o.heightM, z = maxV - minV),
                    center = Position(y = o.heightM / 2f),
                    materialInstance = material,
                    position = Position(x = (minU + maxU) / 2f, z = (minV + maxV) / 2f),
                    apply = { isTouchable = false },
                )
            }
        }
    }
}
