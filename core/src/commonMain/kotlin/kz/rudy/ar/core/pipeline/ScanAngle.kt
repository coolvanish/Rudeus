package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.geometry.pixelRay
import kotlin.math.asin

/**
 * Which views [SceneBuilder] can be trusted with. It rebuilds a base from the near rim of a side view, so a view from
 * straight above has no side to read: nothing fails, the geometry is just wrong, however good the mask is.
 *
 * The error follows the ray to the object, not the phone's tilt, so an object low in the frame of a moderately tilted
 * phone is as bad as one under a steep phone. Measured with perfect masks of a 9 cm cup (r = 4 cm) from 0.64 m on the
 * 640x480 grid of ARCore's CPU image, the same at every roll (below 85° the height only errs high, and a cup is an
 * obstacle at any height above a jump):
 *
 * | ray elevation | height | centre error | footprint area |
 * |---|---|---|---|
 * | 45° | 10.7 cm | 1.2 cm | x0.58 |
 * | 70° | 12.9 cm | 1.0 cm | x0.63 |
 * | 75° | 13.9 cm | 1.0 cm | x0.64 |
 * | 80° | 14.1 cm | 0.7 cm | x0.73 |
 * | 85° | 30 cm (the clamp) | 3.2 cm | x0.08 |
 * | 89° | 0.6 cm | 4.4 cm | x2.56 |
 *
 * At 1280x720 it degrades sooner (75°: 15.9 cm; 80°: 20.6 cm), and a real mask is noisier than a perfect one, so the
 * limit stays well short of where it falls apart. Not yet measured on real masks from a steep view.
 */
object ScanAngle {
    /** Steepest ray [SceneBuilder] gets, in radians above the table plane: 75°. */
    const val MAX_ELEVATION_RAD = 1.3089969f

    /** Elevation of a view direction above the table plane: 0 = along the table, pi/2 = straight down at it. */
    fun elevationRad(dir: Vec3, plane: PlaneFrame): Float = asin((-dir.normalized().dot(plane.normal)).coerceIn(-1f, 1f))

    /** The optical axis alone is too steep: most of the frame would be thrown away, so a scan is not worth paying for. */
    fun cameraTooSteep(cameraPose: Pose3, plane: PlaneFrame): Boolean =
        elevationRad(cameraPose.transformDirection(Vec3(0f, 0f, -1f)), plane) > MAX_ELEVATION_RAD

    /** [usable] is the input without the masks seen too steeply, which are in [tooSteep]. */
    data class Split(val usable: ScanInput, val tooSteep: List<LabeledMask>)

    fun split(input: ScanInput): Split {
        val (steep, fine) = input.masks.partition { tooSteep(it.mask, input) }
        return Split(input.copy(masks = fine), steep)
    }

    /** Judged at the centre of the mask's largest component, the part [SceneBuilder] will use. */
    private fun tooSteep(mask: BooleanMask, input: ScanInput): Boolean {
        val component = mask.largestComponent()
        var sumX = 0f; var sumY = 0f; var n = 0
        for (y in 0 until component.height) {
            val row = y * component.width
            for (x in 0 until component.width) if (component.bits[row + x]) { sumX += x; sumY += y; n++ }
        }
        if (n == 0) return false
        val ray = pixelRay(sumX / n + 0.5f, sumY / n + 0.5f, input.intrinsics, input.cameraPose)
        return elevationRad(ray.dir, input.plane) > MAX_ELEVATION_RAD
    }
}
