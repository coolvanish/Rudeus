# T15 — core-scan-detections-planeframe

status: done
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
Implemented 2026-10-29 (opus-high). `./gradlew :core:jvmTest` green: **162 tests, 0 failures, 0 errors, 0 skipped**
(was 158; +4, from core/build/test-results/jvmTest/*.xml). `./gradlew :cli:compileKotlin` compiles. No public
signature removed or changed; no new dependency; no Gradle file touched.

Files changed
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/Types.kt: `MaskResult.instances` (last field).
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/SamTokenParser.kt: `maskResult` fills `instances`; TODO(T04) removed.
- core/src/commonMain/kotlin/kz/rudy/ar/core/geometry/Plane.kt: `PlaneFrame.fromAnchorPose`.
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/SceneBuilder.kt: `ScanMasks` object, `ScanInput.fromAnchor`.
- Tests: meta/SamTokenParserTest.kt (+instancesKeepEachDetectionSeparate), pipeline/ScanMasksTest.kt (new,
  oneLabeledMaskPerInstanceAndMergedFallback), geometry/GeometryTest.kt (+scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame),
  pipeline/SceneBuilderTest.kt (+twoCupsFromOnePromptGetDistinctStableIds).
- Not touched: MetaApi.kt, FixtureApi.kt, FixtureConverter.kt (see below), CharacterPipeline, androidApp, shared, cli, docs.

Public API added
- `MaskResult.instances: List<MaskResult> = emptyList()` with `@EncodeDefault(NEVER)`: an empty list is not written,
  old JSON without the key parses as empty.
- `SamTokenParser.maskResult(...)`: same signature; result = `merge(all)` with `instances` = one single-detection
  `merge` per detection, sorted by score descending (null score last, ties keep reply order). `merge` unchanged.
- `PlaneFrame.Companion.fromAnchorPose(anchor: Pose3): PlaneFrame`.
- `ScanInput.Companion.fromAnchor(anchor, cameraPose, intrinsics, masks, planePolygonWorld: List<Vec3>? = null, spawn = Vec2.ZERO)`.
- `object ScanMasks { fun of(result: SegmentResult): List<LabeledMask> }` (pipeline package).

Notes and deviations
- Fixtures store serialised `MaskResult`s, and FixtureConverter/MetaApi both go through `parseSamResponse` ->
  `SamTokenParser.maskResult`, so `instances` flow through with no change to FixtureApi/FixtureConverter/MetaApi.
  The committed docs/api-samples/sam-table/*.fixture.json were NOT regenerated (docs/ is outside this brief):
  until the CLI convert is re-run, `FixtureApi.segment` returns no instances and `ScanMasks.of` falls back to the
  merged mask (one object per prompt, today's behaviour). Re-converting adds the instances (same mask data, so
  redaction is unaffected).
- Side effect: RecordingModels' cutout recordings (sam-cutout masks) now also carry `instances` (one per
  detection), so a recorded single-detection cutout writes its RLE twice. Harmless; the cutout path still uses
  the merged mask via `largest()`.
- `fromAnchorPose` normalises the quaternion before rotating (an off-unit quaternion would scale the axes), then
  normalises each axis.
- `ScanMasks` lives in SceneBuilder.kt (the owned file) rather than a new ScanMasks.kt. `failedPrompts` match by
  exact label (failed prompts carry an empty mask anyway). A mask with width or height <= 0 is dropped.
- The parser test uses the real Day 1 remote reply (docs/api-samples/sam-table/table-01.input.remote.response.json):
  2 instances, merged bbox 438x223 = union, best (c=0.957, the 165x139 detection) first.

Still needs (outside :core)
- T04: androidApp calls `ScanInput.fromAnchor(...)` with `ScanMasks.of(segmentResult)` and the plane polygon in world
  coordinates (ARCore `Plane.getPolygon()` is plane-local x/z; transform it through `plane.centerPose` first).
- Optional: re-run the CLI fixture convert for sam-table so the saved fixtures carry instances.
- rudeus-reviewer pass; `scripts/verify.sh` not run (not the last step of this brief).

### Review fixes (2026-10-29, after 9df8db1)
`./gradlew :core:jvmTest` green: **164 tests, 0 failures, 0 errors, 0 skipped** (was 162; +2 new tests, plus new
assertions in two existing ones).
1. `ScanMasks.of` (SceneBuilder.kt:33-102) now suppresses overlapping detections of one prompt. It compares
   bounding boxes first, then pixels. An instance is skipped when its IoU with an already-kept mask of the same label
   is > `MAX_IOU` (0.5), or when its own pixels lie > `MAX_CONTAINMENT` (0.8) inside that mask. Beyond the review:
   when a kept mask lies > 0.8 inside the new one (a part that scored above its whole), the whole takes the part's
   slot. Without this the "handle first" order would still stack two cups. Other labels are never compared.
   Test: `ScanMasksTest.overlappingDetectionsOfOnePromptBecomeOneMask` (duplicate or inner part -> one mask;
   disjoint -> two; part first -> whole replaces it; cup vs book not suppressed).
2. `ScanInput.fromAnchor` `require`s every mask to be intrinsics.width x height, with a message naming the label and
   both sizes. Its KDoc now says `cameraPose` must be ARCore `Camera.getPose()` paired with `getImageIntrinsics()`,
   not `getDisplayOrientedPose()`.
3. A boundary whose `abs(signedArea())` is <= `ScanInput.MIN_BOUNDARY_AREA_M2` (1e-4 m²) becomes null.
   Test for 2 and 3: `GeometryTest.scanInputFromAnchorRejectsMismatchedMasksAndDegenerateBoundary` (mismatch throws
   IllegalArgumentException; a sliver and collinear points -> null; a 2x2 cm square kept).
4. `GeometryTest.scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame` gains a hand-computed +90° yaw case:
   u = (0,0,-1), v = (1,0,0), n = (0,1,0).
5. RecordingModels.kt:53 records cutout masks with `instances = emptyList()`. The recorder only ever records cutout
   calls (table scans pass through unrecorded), so there is no table case to keep. Asserted in
   `RecordingModelsTest.recordingSkipsSecondSegmentForSameRef`.
6. Regenerated docs/api-samples/sam-table/table-01.fixture.json offline from its committed raw replies
   (`./gradlew :cli:run --args="fixture --job sam-table --responses-dir docs/api-samples/sam-table --ref table-01
   --latency-ms 2145"`). The merged masks are byte-identical and only the note's date changed. "remote" now carries
   2 instances (165x139 at c=0.957 first, then 114x156 at c=0.766); laptop, toy car and knife carry 1 each; speaker
   is absent with no instances. The file grew from 35.6 KB to 84.9 KB. The pre-commit token regexes match nothing
   in it.
7. Suppression across labels (2026-10-29). Within each prompt the masks are deduplicated first. Then the labels are
   taken in priority order: named labels in input order, then `ScanMasks.GENERIC_LABELS = setOf("object")`. A mask
   is dropped when it is the same object as an already-kept mask of a DIFFERENT label (IoU > 0.5 or own containment
   > 0.8), so the specific label wins regardless of score; "hand" counts as specific. The output follows the
   priority order. Test: `ScanMasksTest.crossLabelGenericObjectLosesToTheNamedLabel`. One assertion in
   `overlappingDetectionsOfOnePromptBecomeOneMask` changed: a cup and a book on the same pixels are now one object,
   and the earlier label (cup) wins. `:core:jvmTest`: 165 tests, 0 failures, 0 errors, 0 skipped (was 164).
