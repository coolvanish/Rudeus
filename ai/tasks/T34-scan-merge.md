# T34 — scan-merge

status: done (JVM; review fixes 2026-11-04: ghost under the same object's mask adopts the id, a shift > 2 cm beats quality, a blank scan forgets nothing)
created: 2026-11-04
day: Day 10 (submission day, feature behind the HUD toggle of T36)
gate: a level scanned from four sides keeps every object; a rescan never drops an object it could not see
cut-if-slips: `TableScan.build` keeps today's behaviour when `standing` is empty and no object has `unseen > 0`; T37 stays off

## Goal
A rescan **merges** into the level in play instead of replacing it. Today `TableScan.build` swaps the level and keeps a
previous object only through three narrow exceptions (failed prompt, too-steep mask, mask cut by the frame). Turn the
phone left and the cup on the right vanishes with the wall. After T34: an object disappears only when its place was in
the frame, nothing covered it, and it was empty **two scans in a row**; new objects appear at once; a known object's
footprint is replaced only by a **better view**; an object a character stands on never changes. This is the ground for
the quiet background scans of T35/T37 and already improves the manual Rescan button.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/TableScan.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/ViewQuality.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/Model.kt — ONLY the two new `LevelObject` fields below
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/TableScanTest.kt, ScanMergeTest.kt (new), ViewQualityTest.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/{Day4ScenarioTest,RealScanTest,SceneBuilderTest}.kt only if a
  changed rule breaks them (fix the expectation, explain in the test)
Read-only: `ScanAngle.kt`, `SceneBuilder.kt` (`assignIds` keeps ids by centroid within 5 cm — reuse, do not change),
`SurfaceBoundary.kt`, `geometry/*`. Nothing outside `:core`. T35 owns `AutoScan.kt` and `metrics/*` at the same time:
do not touch them.

## Contract
### `LevelObject` (domain/Model.kt) — two serializable fields with defaults (JSON of old runs/fixtures still parses)
```kotlin
/** How well the view that produced [footprint] saw this object, 0..1 ([ViewQuality]); 0 = unknown (fixtures, old levels). */
val quality: Float = 0f,
/** Scans in a row whose frame held this object's place, uncovered and empty. Reset to 0 on every sighting. */
val unseen: Int = 0,
```

### `ViewQuality` (new, pure)
```kotlin
object ViewQuality {
    /** 0..1. Best at an elevation of ~55° above the plane from <= 0.5 m; 0 at or above ScanAngle.MAX_ELEVATION_RAD (75°)
     *  and at or below 20°; distance halves it by 1.5 m. Monotone on each side of the best elevation. */
    fun of(elevationRad: Float, distanceM: Float): Float
    /** At the centre of the mask's largest component (the pixel ScanAngle judges), from the scan's camera. */
    fun of(mask: BooleanMask, input: ScanInput): Float
}
```
Tests must pin the ordering, not the exact numbers: 55° from 0.6 m > 74° from 0.6 m; 55° from 0.6 m > 55° from 1.4 m;
30° > 20°; 75° == 0; the unknown default 0 always loses to any real sighting.

### `TableScan.build(input, failedPrompts, previous, profile, standing: List<Vec2> = emptyList()): Result`
`Result` keeps `level, carried, tooSteep, missed, cutByFrame` and adds
`removed: List<String> = emptyList(), replaced: List<String> = emptyList(), kept: List<String> = emptyList()` (ids, for the log).
Rules, in this order, for every **non-DYNAMIC** previous object `o` (DYNAMIC is re-placed every scan, as now):
1. **Matched** (SceneBuilder gave a built object `o.id`): if a `standing` point lies inside `o.footprint` -> the previous
   object stays byte-for-byte (`kept`). Else if the new sighting's `quality > o.quality` -> the built object with its own
   `quality`, `unseen = 0` (`replaced`); else the previous footprint/height/quality with `unseen = 0` (`kept`).
2. **Unmatched, out of frame**: a footprint vertex at h = 0 or h = `heightM` projects outside the image rect inset by
   `FRAME_MARGIN_PX`, behind the camera, or does not project -> carried unchanged.
3. **Unmatched, covered**: the projection of the object's middle (`plane.fromPlane(centroid, heightM/2)`, as `covers()`
   does today) lies inside the bounding box of ANY mask of this scan (usable, too-steep or cut) -> carried unchanged.
   This subsumes today's too-steep and cut-by-frame carry; keep the `failedPrompts` carry as it is.
4. **Unmatched, standing on it** (a `standing` point inside its footprint) -> carried unchanged, `unseen` untouched.
5. **Unmatched, in frame, uncovered, empty**: `unseen + 1 >= 2` -> dropped (`removed`); else carried with `unseen + 1`.
Unchanged: built objects without a previous id are added at once; `level == null` when nothing built and a real object
was too steep; `missed` counts too-steep real objects nothing stands in for; `isCutByFrame`, `FRAME_MARGIN_PX`,
`Semantics.isCompact` exceptions; the `carried` list means "previous objects not rebuilt this scan" (rules 2–5).
`SceneBuilder` output objects get their `quality` from `ViewQuality.of(mask, input)` — do it in `TableScan`, not in
`SceneBuilder` (keep `extract`/`build` signatures; you may need the mask per built object: match by id through
`assignIds`'s order or re-run `extract` per usable mask — pick the cheaper that keeps `SceneBuilder` untouched).

## Tests required (`ScanMergeTest`, with `ScanViewFixture`; a side view at ~55° from 0.64 m builds a cup fine)
- `anObjectOutsideTheFrameIsCarried` — cup at the far left, camera turned right: carried, not removed, `unseen == 0`
- `anObjectCoveredByAMaskIsCarried` — old book under a new cup mask of another label: carried
- `anEmptyPlaceIsForgottenOnlyAfterTwoScans` — scan 1: carried with `unseen == 1`; scan 2 (same view): `removed`
- `aSightingResetsUnseen` — after `unseen == 1` the object seen again -> `unseen == 0`
- `aNewObjectAppearsAtOnce`
- `aBetterViewReplacesTheFootprint` — previous `quality` 0.3 (steep) vs a 55° sighting -> `replaced`, new footprint
- `aWorseViewKeepsTheFootprint` — previous `quality` 0.9 vs a 72° sighting -> `kept`, old footprint byte-for-byte
- `anObjectUnderAStandingCharacterNeverChanges` — matched with a better view AND unmatched-empty twice: both keep it
- `aHandIsNeverCarried` (DYNAMIC) — unchanged from today
- `ViewQualityTest` — the orderings above
Existing `TableScanTest` cases keep passing; `aCupThatMovedAwayLeavesNoGhostBehind` must be rewritten: the moved cup
must be IN frame and uncovered, so it is carried once with `unseen == 1` and gone on the second scan (document why).
Tests not required: JSON round-trip of the new fields (defaults; one assertion in an existing serializer test is enough).

## Steps
1. Read `TableScan.kt`, `ScanAngle.kt`, `SceneBuilder.assignIds`, `ScanViewFixture.kt`, `TableScanTest.kt`.
2. Write `ScanMergeTest` and `ViewQualityTest` first (red), then `ViewQuality`, then the rules.
3. `./gradlew :core:jvmTest --console=plain -q` (other agents build in this worktree at the same time: Gradle serialises;
   use `--tests 'kz.rudy.ar.core.pipeline.*'` while iterating, run the whole `:core:jvmTest` once at the end).

## Done when
- `./gradlew :core:jvmTest` green (the full verify runs after T35–T37 land; do not run `scripts/verify.sh` yourself)
- `git diff --stat` touches only the paths under Owns
- Final message: the rules as implemented, any deviation from the contract, the test count before/after
