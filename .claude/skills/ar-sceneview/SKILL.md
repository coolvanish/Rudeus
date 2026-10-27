---
name: ar-sceneview
description: SceneView 4.x / ARCore patterns for the Rudeus AR host (:androidApp): session config, one anchor per session, (u,v,h) to Pose, tap-to-walk hit tests, frame grab for capture, occluder boxes, recording/playback. Load before touching anything under androidApp/ or when :core needs a new AR-facing contract.
---

# AR host rules (SceneView `io.github.sceneview:arsceneview`, ARCore underneath)

Everything here is the **hand-review zone**. Keep diffs small, mark them `// HAND-REVIEW:`, and verify with
Context7 `/sceneview/sceneview` before using any API you are not certain about. Never invent an API.

## Shape of the AR screen
```kotlin
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val materialLoader = rememberMaterialLoader(engine)
val latestFrame = remember { arrayOfNulls<Frame>(1) }     // plain holder, NOT state: no recomposition per frame
var anchor by remember { mutableStateOf<Anchor?>(null) }  // ONE anchor per session (locked decision)

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,          // tables only
    depthMode = Config.DepthMode.AUTOMATIC,                        // auto-downgrades when unsupported
    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR,
    planeRenderer = anchor == null,                                // hide dots once placed
    onSessionUpdated = { _, frame -> latestFrame[0] = frame },
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { event, node ->
            val frame = latestFrame[0] ?: return@rememberOnGestureListener
            val hit = frame.hitTest(event).firstOrNull {
                val t = it.trackable; t is Plane && t.isPoseInPolygon(it.hitPose)
            } ?: return@rememberOnGestureListener
            if (anchor == null) anchor = hit.createAnchor()      // first tap places the level origin
            else onWalkTo(planeCoords(anchor!!, hit.hitPose))   // later taps = tap-to-walk, handled by :core
        }
    ),
) {
    anchor?.let { a -> AnchorNode(anchor = a) { /* billboard quad + occluders as child nodes */ } }
}
```
Typed `Config.*Mode` params are applied before `sessionConfiguration`; use the lambda only for what the typed
params cannot express. `isDepthModeSupported` is checked for you when using the typed `depthMode` param.

## Plane coordinates <-> Pose (the only place this conversion lives)
The anchor pose defines the level frame. Its local X axis is `u`, local Z axis is `v`, local Y is `h` (up).
```kotlin
fun planeCoords(anchor: Anchor, world: Pose): Vec2 {
    val local = anchor.pose.inverse().compose(world)      // world -> anchor-local
    return Vec2(local.tx(), local.tz())
}
fun toWorld(anchor: Anchor, p: Vec2, h: Float): Pose =
    anchor.pose.compose(Pose.makeTranslation(p.u, h, p.v))
```
Character is a **billboard quad, Y-only**: rotate the node so its forward faces the camera projected onto the plane.

## Scan (explicit button, never per-frame)
1. `frame.acquireCameraImage()` (YUV_420_888) -> JPEG (1080p is enough) -> pipeline. Close the image in `finally`.
2. Save with the frame: `frame.camera.pose`, `frame.camera.imageIntrinsics` (fx, fy, cx, cy, image size), anchor pose, plane id.
3. `:core` `SceneBuilder` turns masks + saved pose/intrinsics into footprints via ray–plane. No Depth API in geometry.

## Occlusion plan
- Plan A: `depthMode = AUTOMATIC` and SceneView's depth occlusion (`sceneUnderstanding` on 4.10+; check Context7).
- Plan B: occluder boxes: a `CubeNode` per `LevelObject` sized from the footprint and `heightM`, with a material that
  writes depth but not color (`depthWrite = true`, `colorWrite = false`). Decide by Day 5 noon.

## Tracking loss
`TrackingState.PAUSED` -> freeze physics (`Mode.PAUSED`), keep the anchor, never reset the level. Resume on `TRACKING`.

## Dev loop without the table
ARCore Recording & Playback: record one session of the real table on Day 1 (`ARRecorder` or `session.startRecording`),
replay with `ARSceneView(playbackDataset = file)`. Keep the MP4 out of git (`release/*.mp4` is ignored; store under
`docs/recordings/` locally only).

## Gates this code must hit (ai/build-plan.md)
Plane < 10 s, drift < 1 cm/60 s, quad walks to tap, cup footprint within 1–2 cm via ray–plane.
