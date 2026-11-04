package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.floor

/** The four quarters around the anchor a background scan is spent on, one each. Pure plane geometry. */
object ScanSectors {
    const val COUNT = 4

    /** A sector is left only once the camera's bearing is this far past its edge (no flapping on a boundary). */
    const val HYSTERESIS_RAD = 0.1745f // 10°

    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val WIDTH = TWO_PI / COUNT

    /**
     * 0..3 by the bearing atan2(uv.v - centre.v, uv.u - centre.u): quadrants starting at -45°, so the anchor's +u
     * axis is the middle of sector 0 (sector 1 is +v, 2 is -u, 3 is -v). A camera right above [centre] is sector 0.
     */
    fun sectorOf(cameraUv: Vec2, centre: Vec2 = Vec2.ZERO): Int {
        val a = wrap(bearing(cameraUv, centre) + WIDTH / 2f)
        return floor(a / WIDTH).toInt().coerceIn(0, COUNT - 1)
    }

    /** As above but sticky: returns [current] unless the bearing is HYSTERESIS_RAD past that sector's edge. */
    fun sectorOf(cameraUv: Vec2, centre: Vec2, current: Int?): Int {
        if (current == null || current !in 0 until COUNT) return sectorOf(cameraUv, centre)
        val offMiddle = abs(wrap(bearing(cameraUv, centre) - current * WIDTH + PI.toFloat()) - PI.toFloat())
        return if (offMiddle <= WIDTH / 2f + HYSTERESIS_RAD) current else sectorOf(cameraUv, centre)
    }

    private fun bearing(uv: Vec2, centre: Vec2): Float = atan2(uv.v - centre.v, uv.u - centre.u)

    /** Into [0, 2π). */
    private fun wrap(a: Float): Float {
        val r = a % TWO_PI
        return if (r < 0f) r + TWO_PI else r
    }
}

data class AutoScanParams(
    val maxScans: Int = 4,
    val cooldownMs: Long = 8_000,
    val dwellMs: Long = 500,
    val maxElevationRad: Float = ScanAngle.MAX_ELEVATION_RAD,
    /** "Still": the camera moved less than this since the dwell began, metres... */
    val stillDistM: Float = 0.03f,
    /** ...and its optical axis turned less than this, radians. */
    val stillAngleRad: Float = 0.1f,
)

/**
 * When to fire a quiet background scan: the first time the camera settles in a sector around the anchor that has not
 * been scanned yet, at most [AutoScanParams.maxScans] times per session. Time comes in through [onFrame], so the whole
 * machine runs on the JVM in tests; the AR host only feeds it frames and starts the scan it is told to.
 *
 * One per AR session. Not thread-safe: the host calls it from the frame callback only.
 */
class AutoScanPolicy(val params: AutoScanParams = AutoScanParams()) {
    /** Background scans fired so far. */
    var fired: Int = 0
        private set

    /** Sectors scanned (manual or background). */
    var visited: Set<Int> = emptySet()
        private set

    /** The sticky sector of the last tracked frame; null before the first one. */
    var sector: Int? = null
        private set

    private var lastScanMs: Long? = null
    private var dwellStartMs = 0L
    private var dwellPose: Pose3? = null

    /** A manual scan launched from here: its sector counts as visited and the cooldown restarts. */
    fun markScanned(cameraUv: Vec2, nowMs: Long, centre: Vec2 = Vec2.ZERO) {
        visited = visited + ScanSectors.sectorOf(cameraUv, centre, sector)
        lastScanMs = nowMs
        dwellPose = null
    }

    /** New anchor / new session: forget everything. */
    fun reset() {
        fired = 0
        visited = emptySet()
        sector = null
        lastScanMs = null
        dwellPose = null
    }

    /**
     * One frame. Returns the sector to scan now, else null. Returning a sector marks it visited, counts the scan and
     * restarts the cooldown: the host MUST start the scan when it gets a sector.
     * Fires only when ALL hold: `fired < maxScans`; `tracking`; `!busy`; `nowMs - lastScanMs >= cooldownMs`;
     * the sticky sector is not in `visited`; `!ScanAngle.cameraTooSteep(cameraPose, plane)`; and the camera has been
     * still (stillDistM / stillAngleRad against the pose when the dwell began) for >= dwellMs. Any failed condition
     * except the cooldown/max/busy ones restarts the dwell; a sector change restarts it too.
     *
     * The elevation limit is [AutoScanParams.maxElevationRad] (by default [ScanAngle.MAX_ELEVATION_RAD], the same test
     * as [ScanAngle.cameraTooSteep]). While not tracking the pose is not trusted, so the sticky sector is kept as it was.
     */
    fun onFrame(nowMs: Long, cameraPose: Pose3, plane: PlaneFrame, centre: Vec2, tracking: Boolean, busy: Boolean): Int? {
        if (!tracking) return restartDwell()
        val s = ScanSectors.sectorOf(plane.toPlane(cameraPose.position), centre, sector)
        if (s != sector) {
            sector = s
            dwellPose = null
        }
        if (s in visited) return restartDwell()
        if (tooSteep(cameraPose, plane)) return restartDwell()

        val start = dwellPose
        if (start == null || !still(start, cameraPose)) {
            dwellPose = cameraPose
            dwellStartMs = nowMs
        }
        if (nowMs - dwellStartMs < params.dwellMs) return null

        // Held back, but the dwell keeps running: a camera still all along fires on the first frame these clear.
        if (fired >= params.maxScans || busy) return null
        val last = lastScanMs
        if (last != null && nowMs - last < params.cooldownMs) return null

        fired++
        visited = visited + s
        lastScanMs = nowMs
        dwellPose = null
        return s
    }

    private fun restartDwell(): Int? {
        dwellPose = null
        return null
    }

    /** [ScanAngle.cameraTooSteep] with the limit taken from [params]. */
    private fun tooSteep(cameraPose: Pose3, plane: PlaneFrame): Boolean =
        ScanAngle.elevationRad(opticalAxis(cameraPose), plane) > params.maxElevationRad

    private fun still(from: Pose3, to: Pose3): Boolean {
        if (from.position.distanceTo(to.position) >= params.stillDistM) return false
        val cos = opticalAxis(from).normalized().dot(opticalAxis(to).normalized()).coerceIn(-1f, 1f)
        return acos(cos) < params.stillAngleRad
    }

    private fun opticalAxis(pose: Pose3): Vec3 = pose.transformDirection(Vec3(0f, 0f, -1f))
}
