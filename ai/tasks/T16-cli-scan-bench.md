# T16 — cli-scan-bench

status: review
created: 2026-10-31
day: Day 4
gate: scan < 6 s (measured from the Mac before the Pixel run); 5 objects within 2 cm is the human's half
cut-if-slips: the concurrency knob first (keep the fixed 3); never the bench itself

## Goal
`./gradlew :cli:run --args="scan ..."` runs the Day 4 scan path from the Mac on a saved table photo: JPEG → SAM with
the six table prompts → `ScanMasks.of` → `ScanInput.fromAnchor` → `SceneBuilder.build`, and prints `segment_ms`,
`scan_ms`, one line per level object, and writes a `runs/<ts>.json` whose `scans[]` carries the numbers. The live
run gives the first real evidence for the "scan < 6 s" half of the Day 4 gate and tells us whether six prompts at
`MAX_SAM_CONCURRENCY = 3` (two rounds) fit the budget or the prompt list / concurrency must change (T04 risk 2).

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- cli/src/main/kotlin/kz/rudy/ar/cli/ScanCommand.kt (new)
- cli/src/main/kotlin/kz/rudy/ar/cli/Main.kt — only the `"scan" ->` dispatch line and the usage comment block
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaApi.kt, MetaConfig.kt — only if a `samConcurrency` option is
  needed (default stays 3; behaviour unchanged for every existing caller)
- core/src/commonTest/kotlin/kz/rudy/ar/core/meta/SegmentEachTest.kt — only if the option above is added
- docs/api-samples/sam-table/** — new recorded fixtures only (never overwrite `table-01.*`); redact as the README there says
- runs/ — the bench's output files
- this brief

## Contract
```
scan --image <jpg> [--prompts cup,book,phone,bottle,hand,object | --floor]
     [--live [--provider meta|fireworks] [--budget-usd 0.30] [--record <fixturesDir> [--force]] | --fixtures <dir> --ref <ref>]
     [--concurrency 3] [--camera-height-m 0.5] [--pitch-deg 45] [--hfov-deg 60] [--out runs] [--repeat 1]
```
- Image: decode, downscale to ≤ 1280 px long side, re-encode JPEG q90 (the phone does the same in `ScanCapture`;
  reuse `ImageConvert` / `JvmRaster`). Prompts default to the TABLE list; `--floor` adds box, shoe, bag, ball.
- Segment: `models.segment(ref, jpeg, prompts)` through the same `MetaApi` the phone uses (offline: `FixtureApi`).
  Time it with a monotonic clock → `segment_ms`. Print per prompt: instance count, best score, `failedPrompts`.
- Geometry: synthetic anchor at the origin, horizontal plane; camera `--camera-height-m` above the anchor, looking at
  it pitched `--pitch-deg` down, `Intrinsics.fromFov(hfov, w, h)` for the downscaled JPEG size; then
  `ScanInput.fromAnchor(anchorPose, cameraPose, intrinsics, ScanMasks.of(result), boundary = null)` and
  `SceneBuilder(SceneParams()).build(input, previous = null, SurfaceProfile.TABLE|FLOOR)`.
  Print one line per `LevelObject`: id, label, kind, centroid (u, v) in cm, heightM in cm, footprint vertex count.
  `scan_ms` = segment + geometry wall time.
- Metrics: `MetricsLog.recordScan(objects, scanMs, withinCm = emptyList())` → `runs/<ts>.json` via `FileMetricsSink`.
  `metrics summarise runs` must still work with a run that has scans and no characters.
- `--repeat N`: run the segment N times on the same JPEG, print each `segment_ms` and the median.
- `--concurrency`: if you add it, it is a `MetaConfig` field (default 3) read where `MAX_SAM_CONCURRENCY` is used
  today. No other core behaviour changes. If this needs more than ~20 lines in core, skip it and measure with 3 only.
- Key handling: `--live` gets the key through `KeySource` exactly like `character --live`. Never read, print or
  mention the properties file or the key in any command, log, commit or this brief.
- Budget: default `--budget-usd 0.30` per invocation; stop before a call that would exceed it (reuse the
  `CostMeter`/`Pricing` pattern from `character --live`). Total spend for this brief ≤ $0.60.

## Tests required
- `SegmentEachTest`: a case for a configurable limit **only if** `samConcurrency` is added (N prompts, limit L → at
  most L in flight at once).
Tests not required: the CLI argument parser, JPEG re-encoding, printing.

## Steps
1. Read `ai/tasks/T04-scan-to-level.md` (the phone-side path you are mirroring), `ScanCapture.kt` in
   `androidApp/.../scan/`, `SceneBuilder.kt`, `Main.kt`'s `character --live` path (key, budget, recording).
2. Offline first: `scan --fixtures docs/api-samples --ref table-01 --image docs/api-samples/sam-table/table-01.input.jpg
   --prompts <the labels in docs/api-samples/sam-table/prompts.json>` must build a level from the existing fixture.
3. Live: `--image docs/api-samples/sam-table/table-01.input.jpg` with the default TABLE prompts, `--repeat 2`,
   concurrency 3; then (if the knob exists) concurrency 6, `--repeat 2`. Also look in
   `docs/api-samples/sam-table/images/` for a photo that actually shows a cup/book/phone/bottle and run it once.
   Record one live run as a new fixture ref (e.g. `table-01-day4`) with `--record docs/api-samples` so the
   phone-independent test data exists; run the redaction check the README describes.
4. Fill the Result table below. Commit on the branch you are on (`day4/table-level`, main checkout):
   `cli: scan bench (T16) — ...`.

## Done when
- `scripts/verify.sh` exit 0 (run from the main checkout)
- Result below has `segment_ms` for ≥ 2 live runs at concurrency 3 (and 6 if added) and the offline replay works.
- Verdict line: does the six-prompt scan fit `< 6 s` end to end from a laptop on Wi-Fi? If not, which prompt(s) to drop
  (`object` last — it is the generic catch-all).

## Result (filled on acceptance)
Filled 2026-10-31 (implementer, Mac on home Wi-Fi). `segment_ms` = `MetaModels.segment` wall time (monotonic clock);
`scan_ms` = segment + `ScanMasks.of` + `ScanInput.fromAnchor` + `SceneBuilder.build`. The JPEG prep (decode, <= 1280 px,
q90) is printed separately as `jpeg_ms` (370-620 ms on the cold JVM, not in `scan_ms`). Every live JPEG was 720x1280,
171-180 KB. `table1.jpeg` and `table-01.input.jpg` are the same photo (900x1600 vs 1440x2560 original).

| run | image | prompts | concurrency | segment_ms | scan_ms | objects | failed prompts | $ |
|---|---|---|---|---|---|---|---|---|
| runs/2026-10-30T19-10-46Z #1 | sam-table/table-01.input.jpg | TABLE (6) | 3 | 4532 | 4541 | 0 | - | 0.0150 |
| runs/2026-10-30T19-10-46Z #2 | sam-table/table-01.input.jpg | TABLE (6) | 3 | 5250 | 5250 | 0 | - | 0.0150 |
| runs/2026-10-30T19-11-02Z #1 | sam-table/table-01.input.jpg | TABLE (6) | 6 | 2506 | 2516 | 0 | - | 0.0150 |
| runs/2026-10-30T19-11-02Z #2 | sam-table/table-01.input.jpg | TABLE (6) | 6 | 2527 | 2528 | 0 | - | 0.0150 |
| runs/2026-10-30T19-11-28Z (recorded `table1-day4`) | sam-table/images/table1.jpeg | TABLE (6) | 3 | 4089 | 4091 | 0 | - | 0.0150 |
| runs/2026-10-30T19-11-41Z | sam-table/images/table1.jpeg | laptop, remote, toy car, knife, cup, object | 3 | 4242 | 4323 | 5 | - | 0.0150 |
| runs/2026-10-30T19-11-55Z (recorded `table1-day4-named`) | sam-table/images/table1.jpeg | laptop, remote, toy car, knife, cup, object | 3 | 4667 | 4740 | 5 | - | 0.0150 |
| offline (fixture `table-01`) | sam-table/table-01.input.jpg | laptop, remote, speaker, toy car, knife | - | 24 / 2 | 97 / 20 | 5 | - | 0 |

- offline replay: `scan --fixtures docs/api-samples --ref table-01 --image docs/api-samples/sam-table/table-01.input.jpg
  --prompts 'laptop,remote,speaker,toy car,knife' --repeat 2` builds 5 objects (laptop-1, remote-1, remote-2, toy car-1,
  knife-1; speaker absent) from the 900x1600 fixture masks (intrinsics follow the masks' grid, as on the phone). The two
  new refs replay too: `table1-day4-named` gives the same 5 objects as its live run, `table1-day4` gives 0.
- recorded fixture ref: `docs/api-samples/sam-table/table1-day4.{fixture.json,input.jpg}` (default TABLE prompts,
  every mask absent: the "nothing found" case) and `table1-day4-named.{fixture.json,input.jpg}` (6 named prompts, 5
  objects, instances kept). Each input jpg is the 720x1280 q90 JPEG SAM saw. Masks only, no headers or bodies; checked
  against the pre-commit token pattern plus Authorization / x-api-key / base64: 0 hits. `table-01.*` untouched.
- run id: runs/2026-10-30T19-10-46Z.json (conc 3), runs/2026-10-30T19-11-02Z.json (conc 6), plus 19-11-28Z, 19-11-41Z,
  19-11-55Z; `metrics summarise runs` lists them (n = 0 characters, no error).
- `samConcurrency` added: `MetaConfig.samConcurrency` (default `MetaApi.MAX_SAM_CONCURRENCY` = 3, must be > 0), read by
  `MetaApi.segment`; test `SegmentEachTest.aConfiguredLimitCapsTheRequestsInFlight`. No other core behaviour changed.
- spent: 7 scans x 6 SAM requests x $0.0025 = **$0.105** (measured by the CostMeter; brief cap $0.60).
- verdict (scan < 6 s): **yes from the laptop, but tight at concurrency 3**: six prompts in two rounds took 4.1-5.3 s
  (median 4.5 s over 5 live scans), geometry adds 0-81 ms. That leaves 0.7-1.9 s for the phone's grab, JPEG and a
  slower mobile link. At concurrency 6 (one round) the same scan took 2.5 s with no failed prompt (2 of 2). Dropping
  one prompt does NOT help at concurrency 3: 4-6 prompts are all two rounds; only <= 3 prompts make one round.
  Recommendation: run the phone at `samConcurrency = 6` (a one-line `MetaConfig(...)` change in AndroidCaptureHost,
  hand-review zone, not done here) and re-measure on the Pixel. If 6 hits 429s there, drop to 3 prompts or accept ~5 s.
- finding: `object` (the generic catch-all) matched NOTHING in 7 of 7 live scans on a table with a laptop, remote,
  toy car, knife and a pastry; `cup`, `book`, `phone`, `bottle`, `hand` were absent because none are in these photos
  (no photo in `sam-table/images/` shows any of them). It costs $0.0025 per scan and finds nothing: first to drop. The
  default TABLE list found 0 objects on the only real table photos we have, so the 5-object gate needs a table set with
  cups/books/phones/bottles, or the list needs the objects children actually have (named prompts found all 5 here).
- cut/deferred: the scanned geometry uses a synthetic camera (0.5 m, 45 deg, 60 deg hfov), so the printed centroids and
  heights are illustrative, not measurements (the portrait photo was not taken from that pose). Centroid accuracy
  (5 objects within 2 cm) is the human's half on the Pixel. `--provider fireworks` exists but fails by design (no SAM
  there). Not tested: `--floor` live, `--force` overwrite path.
