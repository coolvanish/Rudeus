# T09 — style-ref-culler

status: done
created: 2026-10-28
day: Day 2
gate: "8 culled style refs" (feeds ≥7/10 first-try gate passes)
cut-if-slips: pick the 8 refs by eye instead

## Goal
Given a folder of style-reference candidates (generated or hand-picked), choose the 8 that agree most with each other in
palette and line weight, deterministically, and say why every other one was dropped. Consistent refs are what make Muse
output consistent and the 0.7 gate passable on the first try.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/RefCuller.kt (replace the stub body; keep the public types)
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/RefCullerTest.kt (new)

Not yours: everything else. `StyleGate` is owned by T07 — call its companion functions (`palette`, `paletteScore`,
`outlineFraction`, `outlineScore`, `Lab`), do not edit the file. If you need a helper it lacks, write it privately in RefCuller.kt.

## Frozen contract (keep exactly)
```kotlin
data class RefCandidate(val name: String, val image: RasterImage)
data class CullResult(val kept: List<String>, val dropped: List<Dropped>) { data class Dropped(val name: String, val reason: String) }
object RefCuller { fun cull(candidates: List<RefCandidate>, keep: Int = 8): CullResult }
```
You may add fields to `CullResult` **with defaults** (e.g. per-name mean distance) and public constants.

## Contract
- Style refs are opaque images on a plain (near-white) background. Measure the *foreground* only: treat pixels with
  alpha < `StyleGate.OPAQUE_ALPHA` or near-white (high L*, low chroma; named constants) as background.
- Hard rejects first, each with a human-readable reason: smaller than `MIN_SIDE` (256 px) on either side; foreground
  share < 3 % ("empty") or > 90 % ("no background"); duplicate names (keep the first).
- Features per candidate: foreground palette (k = 6), foreground outline fraction.
- Pairwise distance in 0..1: `PALETTE_WEIGHT × (1 − symmetric paletteScore) + (1 − PALETTE_WEIGHT) × (1 − outlineScore)`;
  symmetric = mean of both directions. Reuse `StyleGate.PALETTE_WEIGHT`.
- Near-duplicates (distance < `DUPLICATE_DISTANCE`, e.g. 0.02): drop the later name ("near-duplicate of X") — a ref set
  needs variety of subject, not copies.
- Then greedy elimination: while more than `keep` remain, drop the one with the highest mean distance to the others
  ("outlier: mean distance 0.31"). Ties → the lexicographically later name goes. `kept` ordered best first (lowest mean
  distance), ties by name. Fewer survivors than `keep` → keep them all.
- Deterministic regardless of input order. Pure Kotlin, commonMain, no new dependencies.
- Performance: 30 candidates of 1024×1024 must cull in a few seconds on the JVM — downsample internally (stride sampling
  to ≤ 256 px long side is fine) rather than requiring callers to resize.

## Tests required
- `dropsPaletteOutlier` — 7 consistent synthetic images + 1 off-palette, keep 7 → the off-palette one dropped as outlier.
- `dropsOutlineOutlier` — same palette, one with a much heavier outline.
- `nearDuplicateDropped`.
- `rejectsTooSmallAndEmpty`.
- `deterministicUnderShuffle` — shuffled candidates give identical `kept` and `dropped`.
- `keepsAllWhenFewerThanKeep`.
Tests not required: decoding files (the CLI does that), the CLI command.

## Steps
1. Read CLAUDE.md, this brief, `core/.../pipeline/StyleGate.kt`.
2. Tests first (synthetic `ArrayRaster`s), then the implementation. Commit `core: ...`.
3. `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest --console=plain -q`; then
   `ANDROID_HOME=$HOME/Library/Android/sdk scripts/verify.sh` once at the end.

Rules: no network, never read local.properties, no Gradle edits.

## Done when
- `scripts/verify.sh` exit 0
- all six tests green

## Result (filled on acceptance)
- measured: 30 synthetic 1024x1024 candidates cull in ~0.16–0.18 s on the Mac (JVM, warmed / cold); 8 RefCullerTest tests green.
- review (rudeus-reviewer): merge after fixes → fixed in a2e5471 (duplicate names deterministic by content key;
  near-duplicate = same picture via a 16x16 content signature, not same style; name bookkeeping after hard rejects).
  Known nit left: stride sampling can alias 1–3 px strokes on 1024 px refs (outline share varies a little).
- cut/deferred: nothing. `DUPLICATE_DISTANCE = 0.03` and `BACKGROUND_MIN_L = 90` are tuned on synthetic images only;
  check the `cull.json` reasons on the first real candidate folder.
