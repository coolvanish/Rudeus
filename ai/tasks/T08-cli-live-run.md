# T08 — cli-live-run

status: doing
created: 2026-10-28
day: Day 2
gate: run 10 drawings/toys → fixtures + bundled demo characters; ≥7/10 first try · p50 < 40 s · ≤ $0.05/character
cut-if-slips: cut (f) `refs generate` first, then (h) `metrics gate`; never (a)–(e)

## Goal
One command turns a folder of real drawing/toy photos into: a `runs/<ts>.json` with real numbers, recorded fixtures
that replay offline, and a bundle of demo characters the app can ship. It is safe to run unattended: it finds the key
without anyone seeing it, never spends more than a stated budget, and never clobbers synthetic test fixtures.

## Owns (exact paths)
- cli/src/main/kotlin/** (Main.kt and any new files, e.g. RefsCommand.kt, KeySource.kt)
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/RecordingModels.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/bundle/** (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/GateCheck.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/meta/RecordingModelsTest.kt, .../bundle/BundleTest.kt, .../metrics/GateCheckTest.kt (new)
- docs/drawings/README.md (new)

Not yours: every other file under core/ (T07 owns pipeline/meta/metrics/io, T09 owns RefCuller.kt). If you need a core
change outside your files, stop and say so in your report instead of making it.

## Frozen contracts you build on (already on the branch)
- `MetaModels.characterSheet(ref, description, image: ByteArray? = null)` (T07 makes MetaApi send the image).
- `MetaApi(config, client = defaultClient(config), costMeter: CostMeter? = null)`; `MetricsLog : CostMeter` (T07 fills it in).
- `JvmPipeline.create(models, metrics, gate = StyleGate())` (T07 may add defaulted parameters).
- `RefCuller.cull(candidates: List<RefCandidate>, keep: Int = 8): CullResult` (T09 replaces the stub body).

## Contract
**(a) Key without seeing it.** `--live` takes `META_API_KEY` from the environment, else reads the repo-root
`local.properties` *in process* (`META_API_KEY=...`, surrounding quotes stripped, as androidApp does since f2531b4).
Never print, log or include the value in an exception message; the error names the file and the variable only. You must
NOT run shell commands that touch that file (a hook blocks them) — write the code and trust the compile.

**(b) Inputs.** `--refs <dir>` (default `<fixtures>/muse-stylize/refs`). `--drawings <dir>` as today. Without a
`spark-sheet/<ref>.description.txt` the description is now blank (Spark sees the image; no more "a child's drawing of a
character" placeholder).

**(c) Budget.** `--budget-usd <x>` (default 1.00 with `--live`, unused offline). Before each character: if
`spent + worstCase > budget` stop starting characters (worstCase = cutout + 3 × (stylize + score) + sheet from
`metrics.costs`), print which refs were skipped, still write the run JSON, exit 1. `spent` comes from the run's recorded
stage costs (measured when T07 lands, estimates until then).

**(d) Record fixtures.** `--record <fixturesDir>` wraps the live `MetaApi` in `RecordingModels(inner, writer, clock)`
(commonMain, no java.io; `fun interface FixtureWriter { fun write(path: String, bytes: ByteArray) }`). Per ref it writes
schema-1 fixtures FixtureApi replays unchanged: `sam-cutout/<ref>.fixture.json` (masks of the first segment call per ref;
later segment calls for that ref — the matte fallback — are not recorded, say so in KDoc), `muse-stylize/<ref>.out-<n>.png`
+ fixture `outputs` (raw Muse bytes, one per attempt), `spark-score/<ref>.fixture.json` `scores` in call order,
`spark-sheet/<ref>.fixture.json` `sheet` as the model JSON shape (`name, personality, ability` lowercase, `lines`).
`synthetic:false`, `note: "recorded by cli character --live on <date>"`, `latencyMs` = the measured wall time of the first
call of that job. The CLI also copies the input photo to `sam-cutout/<ref>.input.<ext>` so
`character --fixtures <dir> --ref <ref>` replays it. Refuse to overwrite a synthetic fixture (existing
`refuseSyntheticOverwrite`), and refuse to overwrite an existing real fixture unless `--force`; check all targets before
the first API call so a refusal costs nothing.

**(e) Bundle.** `--bundle <dir>` writes `<dir>/<ref>/sprite.png`, `<dir>/<ref>/sheet.json` (`CharacterSheet` JSON) and
upserts `<dir>/index.json`: `@Serializable BundleIndex(schema = 1, characters: List<BundleEntry>)`,
`BundleEntry(ref, name, ability, gateScore, gatePassed, attempts, sprite, sheet, provider, runId)` with relative paths,
sorted by ref, upsert replaces the same ref. This is the format Day 6 offline mode loads (T06), so keep it boring.

**(f) Style-ref candidates.** `refs generate --prompt <text | @file> --n <1..10> --out <dir> [--size WxH] [--budget-usd x]`
calls `POST /v1/images/generations` (verified 2026-10-28 on https://dev.meta.ai/docs/image-generation: body
`{model:"muse-image-1.0", prompt, n, size?, response_format:"b64_json", output_format:"png"}`, reply `data[].b64_json`,
`usage` tokens, $0.01 per image, n 1..10, unknown top-level fields → 400, so never send `background`; 503 = retry with
backoff, at most 2 retries). Implement it in :cli with `MetaApi.defaultClient(config)` and `MetaConfig` (no core change).
Writes `gen-<nn>.png` plus `prompt.txt`. Refuses when `n × 0.01 > budget`.

**(g) Cull.** `refs cull --from <dir> --keep 8 --out <dir>`: decode every png/jpg/jpeg in `--from` with `JvmRaster.decode`
(undecodable → dropped with reason; ImageIO has no webp, which is why `refs generate` asks for png), call `RefCuller.cull`, copy kept files to `--out` keeping their names, write
`<out>/cull.json` (kept, dropped + reasons). Refuse to write into `docs/api-samples/muse-stylize/refs` (tests rely on the
synthetic refs there) unless `--force`.

**(h) Gate check.** `metrics gate <run.json>` prints the Day 2 gate table from one run: first-try pass rate ≥ 0.7 over
n ≥ 10 (n < 10 → `UNTESTED (n=…)` plus the observed rate), `p50Ms < 40000`, `costPerCharacterUsd ≤ 0.05`, and refuses to
call a `provider: "fixture"` run a measurement. Logic in `core/metrics/GateCheck.kt` (pure, thresholds as named
constants copied from ai/build-plan.md — do not relax them), the CLI only prints.

**(i)** `character` builds its pipeline with `JvmPipeline.create(models, metrics)` and passes `costMeter = metrics` to
`MetaApi`. Update the usage text and the header comment. Write `docs/drawings/README.md`: where photos go, kebab-case
`<ref>` naming (`cat-01.jpg`, `toy-car-01.jpg`), own drawings/generic toys only, the exact live command with
`--refs docs/style-refs --record docs/api-samples --bundle docs/bundled --budget-usd 1`.

## Tests required
- `recordingRoundTripsThroughFixtureApi` — record a 2-attempt character via `RecordingModels(FakeModels)` into an in-memory
  writer, replay with `FixtureApi` over an in-memory `FixtureSource`: same masks, scores in order, outputs bytes, sheet.
- `recordingSkipsSecondSegmentForSameRef`.
- `bundleIndexUpsertReplacesSameRefAndSorts`, `bundleEntryJsonRoundTrip`.
- `gateCheckPassFailUntested` — n<10 → UNTESTED, thresholds exact at the boundary (39 999 ms pass, 40 000 fail; 0.05 pass), fixture provider refused.
Tests not required: the CLI argument parser, the HTTP generation call, key reading.

## Steps
1. Read CLAUDE.md, this brief, `.claude/skills/meta-fixtures/SKILL.md`, `.claude/skills/metrics/SKILL.md`, `.claude/skills/verify/SKILL.md`.
2. Core pieces with tests first (RecordingModels, bundle, GateCheck), then the CLI. Commit per piece (`core: ...`, `cli: ...`).
3. `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :cli:compileKotlin --console=plain -q` as you go;
   `ANDROID_HOME=$HOME/Library/Android/sdk scripts/verify.sh` once at the end.
4. Offline smoke (scratch dirs, never runs/ or docs/api-samples): `character --fixtures docs/api-samples --out <tmp> --all --bundle <tmp>/bundle`
   and `--ref sample-01 --record <tmp>/rec` must refuse (record is live-only) or be a clear no-op — your choice, documented.

Rules: no live API calls (the orchestrator runs the live pass after merge, under a budget), never read local.properties,
no new dependencies, no Gradle edits.

## Done when
- `scripts/verify.sh` exit 0
- offline smoke produces `<tmp>/bundle/index.json` with sample-01 and sample-02
- `metrics gate` on a fixture run prints UNTESTED/refused, not PASS

## Result (filled on acceptance)
- measured: ...
- run id: runs/<...>.json
- cut/deferred: ...
