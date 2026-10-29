package kz.rudy.ar.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.google.android.filament.MaterialInstance
import io.github.sceneview.NodeScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Position2
import io.github.sceneview.math.Rotation
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.ui.theme.RudeusColors

// HAND-REVIEW: T04 — debug footprint overlay, children of the session AnchorNode (local frame = plane frame, see
// PlaneCoords.kt). One flat Filament polygon per LevelObject: SceneView 4.31 `ShapeNode` (Earcut-triangulated
// `Shape` geometry) in the ObjectKind tint from DESIGN.md / RudeusColors.footprint, lifted FOOTPRINT_LIFT_M above the
// plane so it does not z-fight the camera image. The boundary (CLIFF) is a closed `PathNode` line loop (Filament
// lines are 1 px, not dashed: good enough for a debug toggle). Materials: `createUnlitColorInstance` with alpha <
// 1 -> SceneView's transparent_unlit_colored.filamat, which is DOUBLE-SIDED (MAT_DOSI=1 in the package), so the
// winding Earcut picks cannot hide a footprint. The MaterialLoader owns and destroys these instances with the scene.
//
// Geometry frame: Shape builds its vertices in the node's XY plane (Position(it) of a Position2, z = 0) regardless of
// `normal`, so the node is rotated -90 deg about X (right-handed Euler, as CharacterPoser's yaw): (x, y, 0) ->
// (x, 0, -y). Hence Position2(u, -v) lands on local (u, 0, v) = plane coords. PIXEL CHECK: the cup's footprint must
// sit on the cup, not mirrored across the anchor's u axis; if mirrored, flip the sign of v here.
// Every node is keyed on its polygon: a re-scan builds new nodes instead of rewriting a Shape's vertex buffer with a
// different vertex count (SceneView keys geometry updates on the count).

/** Footprints float this far above the plane (brief: h = 0.002 m). */
internal const val FOOTPRINT_LIFT_M = 0.002f

/** One unlit transparent material per kind, in the DESIGN.md tints. Main thread (MaterialLoader). */
internal fun footprintMaterials(materialLoader: MaterialLoader): Map<ObjectKind, MaterialInstance> =
    ObjectKind.entries.associateWith { kind ->
        val alpha = if (kind == ObjectKind.CLIFF) RudeusColors.FootOutlineAlpha else RudeusColors.FootFillAlpha
        materialLoader.createUnlitColorInstance(RudeusColors.footprint(kind).copy(alpha = alpha))
    }

/** Call inside the session `AnchorNode { }` only while `slot.debug` is on. */
@Composable
internal fun NodeScope.FootprintOverlay(level: Level, materials: Map<ObjectKind, MaterialInstance>) {
    for (o in level.objects) {
        key(o.id, o.footprint) {
            ShapeNode(
                polygonPath = o.footprint.points.map { Position2(it.u, -it.v) },
                materialInstance = materials[o.kind],
                position = Position(y = FOOTPRINT_LIFT_M),
                rotation = Rotation(x = -90f),
                apply = { isTouchable = false },
            )
        }
    }
    level.boundary?.let { b ->
        key(b) {
            PathNode(
                points = b.points.map { Position(x = it.u, y = FOOTPRINT_LIFT_M, z = it.v) },
                closed = true,
                materialInstance = materials[ObjectKind.CLIFF],
                apply = { isTouchable = false },
            )
        }
    }
}
