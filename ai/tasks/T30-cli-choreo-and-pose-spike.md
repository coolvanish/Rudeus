# T30 — cli-choreo-and-pose-spike

status: review
created: 2026-11-04
day: Day 9 (Nov 4, v1.1 minimal cut; see ai/plan-cast-emotes.md). Starts after T29 is merged.
gate: v1.1 gate "bundled characters play Spark-made emotes offline"; the pose-frame spike answers go/no-go for cut line 1
cut-if-slips: the pose spike first (cut line 1); then the live run (bundled characters ship with `source=default`)

## Goal
Every bundled character gets its five choreographies from Spark, saved as `choreo.json` in `docs/bundled/<ref>/`
and mirrored into the app's assets, with the real replies recorded as fixtures. Separately, a CLI spike asks Muse
Image to draw two pose frames for one character (waving, roaring) so a human can decide whether model-drawn frames
keep the character's identity. No app code changes.

## Owns (exact paths)
- cli/src/main/kotlin/kz/rudy/ar/cli/Main.kt (dispatch lines + usage text only), ChoreoCommand.kt (new), PoseSpike.kt (new)
- docs/api-samples/spark-choreo/** (live fixtures + meta.json; keep T29's sample-01 and request.json.tmpl)
- docs/api-samples/muse-pose/** (new)
- docs/bundled/*/choreo.json, docs/bundled/index.json (the `choreo` field only)
- androidApp/src/main/assets/bundled/*/choreo.json, androidApp/src/main/assets/bundled/index.json (a mirror of docs/bundled; assets are not hand-review files)
Must NOT touch: `core/`, `shared/`, any `.kt` under androidApp/src/main, Gradle files.

## Contract
`./gradlew :cli:run --args="choreo --bundle docs/bundled [--ref <ref> | --all] [--live] [--record docs/api-samples] [--budget-usd 0.20]"`
- For each bundled entry (index.json): sprite + sheet -> `models.choreograph(ref, sheet, sprite fitted to 512 px, as the pipeline does)` -> `<ref>/choreo.json` (`ChoreographyParser.toJson`), and the index entry's `choreo` path set via `BundleIndex.upsert`.
- Offline (no `--live`): `FixtureApi` replays `docs/api-samples/spark-choreo/<ref>.fixture.json`; a missing fixture is reported per ref and the ref is skipped (exit 0 if at least one succeeded).
- `--live`: `MetaApi` with `KeySource.read()` (never print the key), `CostMeter`/`MetricsLog` as the `character` command does, `--budget-usd` guard (default 0.20; Spark ≈ $0.003/call), `--record` wraps it in `RecordingModels` so each reply lands as `spark-choreo/<ref>.fixture.json` (+ `meta.json` written once with endpoint, model, median latency, cost, probedAt, synthetic=false).
- Prints one line per ref: `ref source=spark|default latency_ms= cost_usd= durations=hello:1200,bye:...`. Writes `runs/<ts>.json` through MetricsLog like the other commands (never by hand).
- After the live run: copy every `docs/bundled/<ref>/choreo.json` and `docs/bundled/index.json` into `androidApp/src/main/assets/bundled/` (same layout; do not touch sprites or sheets there).

`./gradlew :cli:run --args="pose-spike --bundle docs/bundled --ref toy_dino --out docs/api-samples/muse-pose --live [--budget-usd 0.10]"`
- Loads the sprite and the style refs (`docs/api-samples/muse-stylize/refs`, through `StyleRefs` / the pipeline's prepare path if accessible, else the raw ref PNGs). Four calls to the now-public `MetaApi.stylize(ref, sprite, refs, prompt)`: HELLO ×2 ("the very same character, identical colours, outline and proportions, waving one arm high", "... both arms up, happy"), ROAR ×2 ("... mouth wide open, roaring, leaning forward", "... eyes squeezed, roaring"). Each result -> the pipeline's sprite matte (SAM cutout + alpha, `JvmPipeline` has the matter) -> `<ref>.hello-1.png`, `<ref>.hello-2.png`, `<ref>.roar-1.png`, `<ref>.roar-2.png`.
- Writes `contact.png`: the original sprite and the four frames side by side at 256 px height (java.awt BufferedImage, as `ImageConvert` does), and `README.md` with per-frame latency and cost, the total, and the three questions a human answers: same character? outline and colours kept? usable as a frame (pose readable at 6 cm)? Leave the answers blank.
- Budget guard; Muse ≈ $0.01 per image + SAM ≈ $0.0025 per matte.

## Tests required
None (CLAUDE.md: the CLI parser is not tested). `./gradlew :cli:compileKotlin` and `scripts/verify.sh` must pass.
Tests not required: everything.

## Steps
1. Read `Main.kt` (`character`, `scan`, flags, `KeySource`, `Record`), `RecordingModels.kt`, `FixtureApi.kt`, T29's `ChoreographyParser` and `MetaApi.choreograph`, `Bundle.kt`, the `meta-fixtures` skill.
2. `choreo` offline against `sample-01` first (smoke), then live `--all --record`, then the asset mirror.
3. `pose-spike` live on `toy_dino` (if its sprite is unsuitable, `drawing_penguini`).
4. `scripts/verify.sh`.

## Done when
- `docs/bundled/<ref>/choreo.json` exists for every bundled ref, at least 12 of 15 with `"source": "spark"`; index.json carries `choreo`; the assets mirror matches (`diff -r` on those files).
- `docs/api-samples/spark-choreo/` holds one redacted fixture per ref + meta.json (pre-commit scan clean).
- `docs/api-samples/muse-pose/contact.png` and `README.md` exist; the README's numbers are measured.
- Commits: `cli: choreo command, bundled choreo.json + spark-choreo fixtures (T30)` and `cli: muse pose-frame spike (T30)`.

## Result (filled on acceptance)
- measured:
- pose spike verdict (human):
- cut/deferred:
- Phase 1 result (2026-11-04, worktree task/T30-cli-choreo, offline only, no key, no API call): `choreo` (ChoreoCommand.kt)
  and `pose-spike` (PoseSpike.kt) added and dispatched from Main.kt (+ usage text); `Jobs.SPARK_CHOREO` in
  `Record.RECORDED_JOBS` (so `character --record` preflight/`--force` also covers `spark-choreo/<ref>.fixture.json`);
  `scripts/probe-meta.sh` lists the `spark-choreo` job (the template engine already rendered it; a `sprite.<ext>` input
  is now saved under its folder name, `<ref>.response.json`; key handling untouched); docs/api-samples/README.md has the
  layout entry, the `text` field row, the endpoints row and a probe example. Offline smoke: docs/bundled copied to
  build/t30-smoke/bundle, sample-01.fixture.json copied as build/t30-smoke/fixtures/spark-choreo/toy_dino.fixture.json,
  `choreo --bundle build/t30-smoke/bundle --fixtures build/t30-smoke/fixtures --ref toy_dino --out build/t30-smoke/runs`
  -> `toy_dino source=spark latency_ms=13 cost_usd=0.0030 durations=hello:1000,bye:1500,dance:2000,spin:900,roar:1200`,
  exit 0, toy_dino/choreo.json written with `"source": "spark"`, the index entry gained `"choreo": "toy_dino/choreo.json"`,
  runs/<id>.json holds one `choreo` stage; `--all` replays toy_dino and skips the 15 refs without a fixture (exit 0).
  docs/bundled untouched. From the T29 review: `character --bundle` now also writes `<ref>/choreo.json`
  (`Bundle.encodeEmotes(result.emotes)`) so the entry's `choreo` path never dangles (offline smoke on sample-01: choreo.json
  with source spark, index `choreo` set). Additions to the contract: `--fixtures` (offline root, default docs/api-samples), `--out`
  (runs dir, default runs), `--force` (replace a real spark-choreo recording); pose-spike `--refs`, `--runs`, and a
  prompt suffix "Keep the drawing style of the first image. Plain white background, nothing else in the picture." (the
  matte floods a white background). Re-encoding index.json writes `"choreo": null` on entries not yet choreographed.
  Phase 2, from the MAIN checkout on day9/cast-emotes after the merge (the key comes from local.properties there):
  1. `./gradlew :cli:run --args="choreo --bundle docs/bundled --all --live --record docs/api-samples --budget-usd 0.20"`
     (16 refs x ~$0.003 measured estimate, guard worst case $0.01/ref -> 16 refs fit in $0.20; spends ~$0.05).
  2. Mirror: `for d in docs/bundled/*/; do r=$(basename "$d"); cp "docs/bundled/$r/choreo.json" "androidApp/src/main/assets/bundled/$r/choreo.json"; done; cp docs/bundled/index.json androidApp/src/main/assets/bundled/index.json`,
     then check with `for d in docs/bundled/*/; do r=$(basename "$d"); diff "docs/bundled/$r/choreo.json" "androidApp/src/main/assets/bundled/$r/choreo.json"; done; diff docs/bundled/index.json androidApp/src/main/assets/bundled/index.json`.
  3. `./gradlew :cli:run --args="pose-spike --bundle docs/bundled --ref toy_dino --out docs/api-samples/muse-pose --live --budget-usd 0.10"`
     (4 x ($0.01 Muse + $0.0025 SAM fallback) = $0.05 worst case; fall back to `--ref drawing_penguini`).
  4. Optional raw sample: `scripts/probe-meta.sh spark-choreo docs/bundled/toy_dino/sprite.png` (one Spark call, ~$0.003).
  5. `scripts/verify.sh`, the pre-commit scan, then the two commits named under "Done when".

- Phase 2 result (2026-11-04, main checkout, day9/cast-emotes, uncommitted):
  - choreo live (`choreo --bundle docs/bundled --all --live --record docs/api-samples --budget-usd 0.20`, run
    `runs/2026-11-04T12-10-54Z.json`): 14 of 16 refs choreographed, all 14 `source=spark` (0 default, 0 failed);
    latency p50 22.2 s (min 15.2 s, max 25.7 s); measured cost $0.1816 total, $0.0130 per call (4x the $0.003
    estimate; CostTable.choreoUsd should move to ~0.013). The budget guard ($0.02/ref worst case) stopped before
    toy_fox and toy_one_dino (spent $0.1816 of $0.20): they have no choreo.json and `"choreo": null` in the index (the
    app plays the defaults). To finish: `./gradlew :cli:run --args="choreo --bundle docs/bundled --ref toy_fox --live
    --record docs/api-samples --budget-usd 0.05"`, the same for toy_one_dino, then re-mirror (~$0.026).
    Durations seen: hello 1100-1200, bye 1400-1600, dance 2000-2400, spin 1200-1600, roar 1000-1500 ms.
  - spark-choreo/: 14 real fixtures (synthetic=false) + sample-01 (synthetic) + meta.json (synthetic=false,
    latencyMs 22224, costUsd 0.01298). The fixtures' `note` reads "recorded by cli character --live" (RecordingModels
    hard-codes it; core follow-up). Token-shape scan of the new files: clean.
  - Asset mirror: 14 choreo.json + index.json copied into androidApp/src/main/assets/bundled/; cmp: 0 mismatches.
    index.json diffs only add the `choreo` field.
  - pose-spike live (`pose-spike --bundle docs/bundled --ref toy_dino --out docs/api-samples/muse-pose --live
    --budget-usd 0.10`, run `runs/2026-11-04T12-16-28Z.json`): 4/4 frames, every matte by SpriteMatte (no SAM
    fallback), Muse latency 11.6-13.7 s per frame, $0.0100 each, $0.0400 total. Files: toy_dino.hello-1.png,
    toy_dino.hello-2.png, toy_dino.roar-1.png, toy_dino.roar-2.png (0.76-0.99 MB each at 1024 px), contact.png,
    README.md (human answers blank).
  - pose spike verdict (human): 

## Addendum after T29 (2026-11-04)
- Also owned: `cli/src/main/kotlin/kz/rudy/ar/cli/Record.kt` (add `Jobs.SPARK_CHOREO` to `RECORDED_JOBS`), `scripts/probe-meta.sh` (a `spark-choreo` job), `docs/api-samples/README.md` (the endpoints table row and the fixture list).
- T29's `RecordingModels` writes the sanitised `ChoreographyParser.toJson(set)` as the fixture `text` (the raw reply stays inside MetaApi); a `scripts/probe-meta.sh spark-choreo` run may save one raw redacted `.response.json` beside it for the README's "saved real API responses".
- Two phases: (1) the code, tested offline against `sample-01`, in the T30 worktree (no key there: `KeySource` reads the environment or the key file in the working dir, and `:cli:run` sets the working dir to the checkout root); (2) the live run (`choreo --all --live --record`, the asset mirror, `pose-spike`) from the MAIN checkout on `day9/cast-emotes` after the code is merged, outputs committed there.
