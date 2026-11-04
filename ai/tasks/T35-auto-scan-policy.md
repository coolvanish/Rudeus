# T35 — auto-scan-policy

status: done (JVM)
created: 2026-11-04
day: Day 10 (submission day, feature behind the HUD toggle of T36)
gate: four quiet scans per session at most, one per new sector, never while steep, moving, busy or within 8 s of the last
cut-if-slips: T37 never calls the policy; this file stays as dead, tested code

## Goal
The decision "scan now, quietly" lives in `:core` as a pure, time-injected state machine so it is tested on the JVM and
the AR host (T37) only feeds it frames. Four sectors around the anchor; the first time the camera **settles** in a sector
not yet scanned, one background scan fires. Background scans are marked in the metrics so README numbers derived from
`runs/*.json` keep meaning "manual scan".

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/AutoScan.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/MetricsLog.kt (`ScanMetrics.background`, `recordScan` parameter, summary)
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/AutoScanTest.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/metrics/MetricsLogTest.kt (additions)
- cli/** only if `recordScan` call sites break (add `background = false`)
Read-only: `ScanAngle.kt` (`cameraTooSteep`, `MAX_ELEVATION_RAD`), `geometry/Plane.kt` (`PlaneFrame.toPlane`,
`fromAnchorPose`), `Pose3`. T34 owns `TableScan.kt`, `ViewQuality.kt`, `Model.kt` at the same time: do not touch them.
Load the `metrics` skill before touching `MetricsLog`.

## Contract (`package kz.rudy.ar.core.pipeline`)
```kotlin
object ScanSectors {
    const val COUNT = 4
    /** A sector is left only once the camera's bearing is this far past its edge (no flapping on a boundary). */
    const val HYSTERESIS_RAD = 0.1745f // 10°
    /** 0..3 by the bearing atan2(uv.v - centre.v, uv.u - centre.u): quadrants starting at -45°, so the anchor's +u axis is the middle of sector 0. */
    fun sectorOf(cameraUv: Vec2, centre: Vec2 = Vec2.ZERO): Int
    /** As above but sticky: returns [current] unless the bearing is HYSTERESIS_RAD past that sector's edge. */
    fun sectorOf(cameraUv: Vec2, centre: Vec2, current: Int?): Int
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

/** One per AR session. Not thread-safe: the host calls it from the frame callback only. */
class AutoScanPolicy(val params: AutoScanParams = AutoScanParams()) {
    val fired: Int            // background scans fired so far
    val visited: Set<Int>     // sectors scanned (manual or background)
    val sector: Int?          // the sticky sector of the last frame
    /** A manual scan launched from here: its sector counts as visited and the cooldown restarts. */
    fun markScanned(cameraUv: Vec2, nowMs: Long, centre: Vec2 = Vec2.ZERO)
    /** New anchor / new session: forget everything. */
    fun reset()
    /**
     * One frame. Returns the sector to scan now, else null. Returning a sector marks it visited, counts the scan and
     * restarts the cooldown: the host MUST start the scan when it gets a sector.
     * Fires only when ALL hold: `fired < maxScans`; `tracking`; `!busy`; `nowMs - lastScanMs >= cooldownMs`;
     * the sticky sector is not in `visited`; `!ScanAngle.cameraTooSteep(cameraPose, plane)`; and the camera has been
     * still (stillDistM / stillAngleRad against the pose when the dwell began) for >= dwellMs. Any failed condition
     * except the cooldown/max/busy ones restarts the dwell; a sector change restarts it too.
     */
    fun onFrame(nowMs: Long, cameraPose: Pose3, plane: PlaneFrame, centre: Vec2, tracking: Boolean, busy: Boolean): Int?
}
```
Camera uv = `plane.toPlane(cameraPose.position)`. Elevation via `ScanAngle.cameraTooSteep` (optical axis = `-Z` of the pose).

### Metrics
- `ScanMetrics(objects, ms, withinCm, background: Boolean = false)`; `MetricsLog.recordScan(objects, ms, withinCm, background: Boolean = false)`.
- Every summary figure that today averages/counts scans (if any) stays over **manual** scans only; add a
  `backgroundScans: Int` count next to it (0 for old runs). Old `runs/*.json` without the field must still parse.

## Tests required (`AutoScanTest`, use `PlaneFrame.horizontal(0f)` and poses built like `ScanViewFixture.pose`)
- `firesOnceWhenTheCameraSettlesInANewSector` — frames at t = 0, 200, 400 -> null; t = 600 -> sector; t = 700 -> null
- `movingRestartsTheDwell` — a 5 cm move at t = 300 -> nothing before t = 800
- `aSteepCameraNeverFires` — 80° elevation, still for 5 s -> null
- `notTrackingOrBusyNeverFires`
- `theCooldownHoldsBetweenSectors` — sector 1 fired at t; sector 2 settled at t + 2 s -> null; t + 8.5 s -> fires
- `atMostMaxScansPerSession` — four fire, a fifth sector never does; `reset()` allows again
- `aManualScanMarksItsSector` — `markScanned` in sector 0, then settling in sector 0 -> null; in sector 1 -> fires
- `theSectorIsStickyOnTheBoundary` — bearing 44° then 46° stays sector 0; 56° leaves it
- `MetricsLogTest`: a background scan lands in `scans[]` with `background = true`, the manual-only summary ignores it,
  `backgroundScans` counts it; a JSON without the field decodes with `background = false`.
Tests not required: timing on a device; anything in `:androidApp`.

## Steps
1. Read `ScanAngle.kt`, `geometry/Plane.kt`, `ScanViewFixture.pose`, `MetricsLog.kt` + the `metrics` skill.
2. Tests first, then `AutoScan.kt`, then the metrics field.
3. `./gradlew :core:jvmTest --console=plain -q` (other agents build in this worktree at the same time: Gradle serialises;
   use `--tests` filters while iterating, the whole `:core:jvmTest` once at the end). `:cli:compileKotlin` if you touched cli.

## Done when
- `./gradlew :core:jvmTest` green (do not run `scripts/verify.sh`; the orchestrator runs it after T34–T37 land)
- `git diff --stat` touches only the paths under Owns
- Final message: the exact public API as written, any deviation from the contract, the test count before/after
