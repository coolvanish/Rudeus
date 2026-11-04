package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.intersectRayPlane
import kz.rudy.ar.core.geometry.pixelRay

/**
 * How well one view saw an object, 0..1: a rescan replaces a known footprint only with a better one ([TableScan]).
 *
 * [SceneBuilder] reads a base from the near rim of a side view. Too steep and there is no side to read (it is not even
 * built past [ScanAngle.MAX_ELEVATION_RAD]); too low and what lies beyond the near rim is a guess (a plush toy seen at
 * 14° read 42 cm deep, `RealScanTest`). In between, the error in [ScanAngle]'s table is about flat, so the best view is
 * a middle one, ~55°, and the score falls smoothly to 0 at 20° and at 75°. Farther away a pixel covers more table: no
 * gain closer than 0.5 m, half the score at 1.5 m, and never 0, so any real sighting in (20°, 75°) beats an unknown 0.
 *
 * Only the order matters: [TableScan] compares two scores of one object, never a score with a threshold.
 */
object ViewQuality {
    /** At or below this elevation (20°) a view scores 0. */
    const val MIN_ELEVATION_RAD = 0.34906584f

    /** The best elevation, ~55°. */
    const val BEST_ELEVATION_RAD = 0.9599311f

    /** Closer than this a view gains nothing, metres. */
    const val NEAR_M = 0.5f

    /** At this distance a view scores half what it would from [NEAR_M], metres. */
    const val HALF_AT_M = 1.5f

    /**
     * 0..1. Best at an elevation of ~55° above the plane from <= 0.5 m; 0 at or above [ScanAngle.MAX_ELEVATION_RAD] (75°)
     * and at or below 20°; distance halves it by 1.5 m. Monotone on each side of the best elevation.
     */
    fun of(elevationRad: Float, distanceM: Float): Float {
        if (elevationRad.isNaN() || distanceM.isNaN()) return 0f
        val t = when {
            elevationRad <= MIN_ELEVATION_RAD || elevationRad >= ScanAngle.MAX_ELEVATION_RAD -> return 0f
            elevationRad <= BEST_ELEVATION_RAD -> (elevationRad - MIN_ELEVATION_RAD) / (BEST_ELEVATION_RAD - MIN_ELEVATION_RAD)
            else -> (ScanAngle.MAX_ELEVATION_RAD - elevationRad) / (ScanAngle.MAX_ELEVATION_RAD - BEST_ELEVATION_RAD)
        }
        val angle = t * t * (3f - 2f * t)
        val far = (distanceM - NEAR_M).coerceAtLeast(0f) / (HALF_AT_M - NEAR_M)
        return (angle / (1f + far)).coerceIn(0f, 1f)
    }

    /**
     * At the centre of the mask's largest component (the pixel [ScanAngle] judges), from the scan's camera: the ray's
     * elevation and its distance to the table. 0 for an empty mask or a ray that never reaches the table.
     */
    fun of(mask: BooleanMask, input: ScanInput): Float {
        val component = mask.largestComponent()
        var sumX = 0f; var sumY = 0f; var n = 0
        for (y in 0 until component.height) {
            val row = y * component.width
            for (x in 0 until component.width) if (component.bits[row + x]) { sumX += x; sumY += y; n++ }
        }
        if (n == 0) return 0f
        val ray = pixelRay(sumX / n + 0.5f, sumY / n + 0.5f, input.intrinsics, input.cameraPose)
        val hit = intersectRayPlane(ray, input.plane) ?: return 0f
        return of(ScanAngle.elevationRad(ray.dir, input.plane), hit.distanceTo(ray.origin))
    }
}
