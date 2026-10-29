# T15 — core-scan-detections-planeframe

status: doing
created: 2026-10-29
day: Day 4 prep (pure :core, done ahead of T04 so the AR host brief is small)
gate: Day 4 — 5 objects within 2 cm, character routes around; scan < 6 s
cut-if-slips: (2) ScanInput.fromAnchor first (T04 can do the frame maths in androidApp); never (1)

## Goal
The level builder gets one footprint per detected object, not one merged blob per prompt (the Day 1 "remote" prompt
matched the remote AND the speaker and merged them into one 438x223 bbox — `TODO(T04)` in SamTokenParser), and the
AR host can build a `ScanInput` from the anchor pose with one call and no plane maths of its own.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/SamTokenParser.kt, Types.kt (MaskResult additions only), MetaApi.kt
  (only if parseSamResponse needs the per-detection path), FixtureApi.kt / FixtureConverter.kt (only to carry instances)
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/SceneBuilder.kt (ScanInput helpers), geometry/Plane.kt
- core/src/commonTest/** (new + edits)
Not yours: CharacterPipeline (cutout keeps using the merged mask via `largest()`), androidApp/**, shared/**, cli/**.

## Contract
1. **Per-detection masks.** `MaskResult` gains `instances: List<MaskResult> = emptyList()` (last field; old fixtures
   parse as empty): one entry per SAM detection of that prompt — its own rle/bbox/score, same frame size, best score
   first, `instances` empty on each entry, `label` = the prompt. The merged `MaskResult` (today's behaviour) stays the
   top-level value so cutout (`largest()`) is unchanged. `SamTokenParser.maskResult(...)` fills `instances` from
   `parse(text)`; `merge` is unchanged. Strict/lenient semantics apply per detection as today.
   The FixtureApi/FixtureConverter must carry `instances` through the saved fixtures only if they are serialised from
   `MaskResult` (check; if fixtures store the raw SAM text, nothing to do). Redaction rules apply (meta-fixtures skill).
2. **Masks for the level.** `ScanMasks.of(result: SegmentResult): List<LabeledMask>` (pipeline package): one `LabeledMask`
   per instance (label = prompt), falling back to the merged mask when a prompt has no instances but a non-empty mask;
   empty masks dropped; `BooleanMask.fromRle` for rle, filled bbox otherwise; a prompt listed in `failedPrompts` yields nothing.
3. **ScanInput from the anchor.** `PlaneFrame.fromAnchorPose(anchor: Pose3): PlaneFrame` — origin = position,
   uAxis = rotation·(+X), vAxis = rotation·(+Z), normal = rotation·(+Y), all normalised (the ARCore plane-local convention
   in Model.kt/PlaneCoords.kt: X = u, Z = v, Y = h). `ScanInput.fromAnchor(anchor: Pose3, cameraPose: Pose3,
   intrinsics: Intrinsics, masks: List<LabeledMask>, planePolygonWorld: List<Vec3>? = null, spawn: Vec2 = Vec2.ZERO)`
   builds the frame and projects the polygon with `toPlane` into `boundary` (null when no polygon or < 3 points).
4. SceneBuilder.build is unchanged in behaviour; ids for two instances of the same label must stay distinct and stable
   across a re-scan by centroid (already the rule; add the test).

## Tests required
- `SamTokenParserTest.instancesKeepEachDetectionSeparate` — two detections of one prompt -> `instances.size == 2`,
  merged bbox is the union, best score first. Use the real Day 1 "remote" sample from docs/api-samples if it is there
  (grep for `remote`), else a synthetic two-detection token text in the parser's format.
- `ScanMasksTest.oneLabeledMaskPerInstanceAndMergedFallback`.
- `GeometryTest.scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame` (the name T04 asks for): a rotated anchor pose ->
  axes unit length, pairwise orthogonal, `uAxis × vAxis == −normal` (Plane.kt doc), `toPlane` of a point 10 cm along
  the anchor's local X is (0.1, 0).
- `SceneBuilderTest.twoCupsFromOnePromptGetDistinctStableIds`.
Tests not required: MetaApi network path, CLI.

## Steps
1. Read the meta-fixtures skill, SamTokenParser.kt, Types.kt, FixtureApi/FixtureConverter, SceneBuilder.kt, Plane.kt,
   Math3.kt (Quat.rotate), SceneBuilderTest helpers.
2. Tests first, then 1 → 3 → 2 → 4. `./gradlew :core:jvmTest --console=plain -q`; read the XML.

## Done when
- `./gradlew :core:jvmTest` green (was 158); `:cli:compileKotlin` compiles; no public signature removed.

## Result
