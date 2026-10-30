# T18 — day4-scenario-test

status: doing
created: 2026-10-31
day: Day 4
gate: 5 objects within 2 cm, character routes around; scan < 6 s (this brief is the JVM-side evidence for "routes around")
cut-if-slips: never cut (tests only); drop test 3 first if time is short

## Goal
One `:core` test class exercises the whole Day 4 chain the way the phone will run it, so the Pixel session only has
to measure what a JVM cannot (ruler accuracy, wall time): real SAM output from the saved table fixture →
`ScanMasks.of` → `ScanInput.fromAnchor` → `SceneBuilder.build` → a `Level`; then a character on that kind of level
is BLOCKED by an obstacle, steps onto a ≤ 4 cm platform, falls off the boundary and respawns at the spawn, freezes
on PAUSED, and keeps object ids across a rescan. Numbers the tests observe (object count, kinds, centroids) go into
this brief's Result so the human can compare them with the footprint overlay on the table.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/Day4ScenarioTest.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/ScenarioFixtures.kt (new, optional helpers)
- core/src/commonMain/kotlin/kz/rudy/ar/core/physics/** and core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/SceneBuilder.kt
  — ONLY for a minimal fix of a real bug a test exposes; explain it in the Result. (T16 owns `core/meta/`; do not touch it.)
- this brief

## Contract (tests)
Use the existing helpers: `DiskFixtureSource` + `FixtureApi` (see `FixtureApiTest`, `ScanMasksTest`), the pose/intrinsics
idiom in `SceneBuilderTest` (`scan(...)`, `scanOf(...)`), `PlaneFrame.fromAnchorPose`, `ScanInput.fromAnchor`,
`Physics.step/walkTo/pause/resume`, `PhysicsBody` events, `SurfaceProfile.TABLE`.

1. `realSamTableFixtureBuildsALevelWithStableObjects`
   - `FixtureApi` over `docs/api-samples` (the test source set already reads it), ref `table-01`, prompts from
     `docs/api-samples/sam-table/prompts.json`; `ScanMasks.of(result)`.
   - Anchor pose at the origin (horizontal plane); camera 0.5 m above the anchor pitched ~45° down looking at it;
     `Intrinsics.fromFov(60° , w, h)` for the fixture's mask grid size; `boundary = null`.
   - Assert: ≥ 4 objects; all ids distinct and of the form `<label>-<n>`; every centroid is finite and within 0.6 m of
     the anchor; every `heightM` in `(0, SceneParams().maxHeightM]`; `kind == Semantics.kindFor(label, heightM, TABLE)`
     for each object. Print (or collect) the per-object line `id label kind u_cm v_cm h_cm` and copy it into the Result.
   - Building twice from the same input yields the same ids (determinism).

2. `characterIsBlockedByCupStepsOntoBookAndRespawnsOffTheEdge`
   - Synthetic `Level`: boundary square ±0.30 m; `cup-1` OBSTACLE radius 0.04 m at (0.15, 0) h 0.09; `book-1`
     PLATFORM 0.02 m high, 0.15 × 0.10 m at (0, 0.15) (use `Semantics.kindFor` to derive the kinds, do not hard-code).
   - Walk from (0,0) to (0.30, 0): step at 16 ms for ≤ 10 s; BLOCKED rises exactly once; the body's centre never comes
     closer than `radius_character + 0.04 - 1 mm` to the cup's centroid; position stays finite.
   - Walk from (0,0) to (0, 0.15): the body ends within 5 mm of the target with feet height 0.02 ± 0.002 (step-up).
   - Walk from (0,0) to (0.40, 0.40) (outside the boundary): mode becomes RESPAWNING once over the cliff, and within
     3 s the body is back at the spawn with h = 0 and no BLOCKED event.
   - PAUSED: pause mid-walk, step 2 s of time → position unchanged; resume → it continues and BLOCKED is not re-announced
     if it was already blocked.
   Use `PhysicsParams.DEFAULT` with `SurfaceProfile.TABLE`; if the table profile has its own params use those.

3. `rescanKeepsIdsWithinFiveCmAndReplacesDynamicObjects`
   - Build a level from a synthetic scan with a cup and a hand; rescan with the cup moved 3 cm → same id; moved 8 cm →
     a new id; the hand (DYNAMIC) is rebuilt every scan per `SceneBuilder`'s KDoc (assert whatever the KDoc promises,
     not more). Reuse `SceneBuilderTest`'s rasteriser idiom rather than copying its tests.

Do not duplicate `scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame` (T15, exists) or the single-purpose tests in
`SceneBuilderTest`/`PhysicsTest`; reference them.

## Tests required
- The three above. Each must fail if its behaviour is broken (sanity-check one assertion by flipping it locally, then
  flip it back).
Tests not required: rendering, overlay, CLI, the host.

## Steps
1. Read `SceneBuilder.kt`, `Physics.kt`, `PhysicsParams.kt`, `Model.kt` (`Level`, `LevelObject`, `Semantics`),
   `SceneBuilderTest.kt`, `PhysicsTest.kt`, `FixtureApiTest.kt`, and `ai/tasks/T04-scan-to-level.md` "Result".
2. Write the tests; run `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest --console=plain -q` from your
   worktree. Read failures from `core/build/test-results/jvmTest/*.xml`.
3. If a test exposes a real bug, fix minimally inside the owned files and say so in the Result; if it is a test
   misunderstanding, fix the test.
4. Fill the Result; commit on your branch: `core: Day 4 scenario tests (T18) — ...`.

## Done when
- `:core:jvmTest` green in the worktree (orchestrator runs `scripts/verify.sh` after merging).
- Result lists the real-fixture objects (id, label, kind, u/v/h in cm) and any core fix made.

## Result (filled on acceptance)
- real fixture objects: ...
- core fixes: none | ...
- cut/deferred: ...
