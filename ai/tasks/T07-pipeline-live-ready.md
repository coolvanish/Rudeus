# T07 — pipeline-live-ready

status: doing
created: 2026-10-28
day: Day 2
gate: ≥7/10 pass gate first try · p50 < 40 s · ≤ $0.05/character
cut-if-slips: never cut (CharacterPipeline is the Day 2 deliverable); inside the brief, cut (e) then (f) before (a)–(d)

## Goal
`CharacterPipeline` is ready for real Meta replies: the stylized sprite gets a real alpha matte (Muse returns an
opaque white background), the sheet is written by Spark *looking at the character* (no hand-written description),
the sheet call no longer adds to wall-clock time, every stage records its **measured** cost, oversized images are
downscaled before Muse/Spark, and the UI can follow progress stage by stage.

## Owns (exact paths; no other brief touches these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/CharacterPipeline.kt, StyleGate.kt, SpriteMatte.kt (new), PipelineEvent.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaApi.kt, MetaDtos.kt, MetaModels.kt, FixtureApi.kt, FakeModels.kt, SheetParser.kt, CostMeter.kt, Pricing.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/MetricsLog.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/io/RasterImage.kt
- core/src/jvmMain/kotlin/kz/rudy/ar/core/io/JvmRaster.kt, core/src/jvmMain/kotlin/kz/rudy/ar/core/pipeline/JvmPipeline.kt
- core/src/androidMain/** only if a new expect-free helper is needed (prefer not)
- tests: core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/CharacterPipelineTest.kt, SpriteMatteTest.kt (new),
  core/src/commonTest/kotlin/kz/rudy/ar/core/meta/RequestShapeTest.kt, FixtureApiTest.kt, UsageCostTest.kt (new),
  core/src/commonTest/kotlin/kz/rudy/ar/core/metrics/MetricsLogTest.kt, core/src/jvmTest/** (new, for JvmRaster)
- docs/api-samples/spark-sheet/request.json.tmpl, docs/api-samples/README.md (pipeline/cost paragraphs only),
  .claude/skills/metrics/SKILL.md (only if the schema gains fields)

Not yours: `core/.../pipeline/RefCuller.kt` (T09), `core/.../meta/RecordingModels.kt` and `core/.../bundle/**` (T08),
`cli/**` (T08). Do not change the public signatures of `StyleGate.palette`, `paletteScore`, `outlineFraction`,
`outlineScore`, `Lab` (T09 calls them).

## Frozen contracts (already on the branch — build on them, do not rename)
- `MetaModels.characterSheet(ref: String, description: String, image: ByteArray? = null)`.
- `fun interface CostMeter { fun record(job: String, ref: String, usd: Double) }`; `MetaApi(config, client, costMeter)`;
  `MetricsLog : CostMeter` (currently a no-op stub — you implement it).
- `JvmPipeline.create(models, metrics, gate = StyleGate())` — you may add parameters **with defaults** only.

## Contract
**(a) Sprite matte.** Muse Image always answers `background: "opaque"`; the prompt asks for a plain white background
(see `docs/api-samples/muse-stylize/drawing-01.out-1.png`: white background, thick black outline, an enclosed pale-pink
face). New `SpriteMatte` (commonMain, pure, works on `RasterImage` → `ArrayRaster`): flood-fill from every border pixel
through "background-like" pixels (near-white: high L*, low chroma in Lab; thresholds as named constants), set them
transparent, keep enclosed light regions opaque, optionally feather 1 px, crop to the opaque bounds. Report the opaque
fraction. Degenerate result (opaque fraction < 5 % or > 97 %, constants) = matte failed.
`JvmRaster.matte(png: ByteArray): ByteArray?` encodes the result (null = failed/undecodable). Pipeline parameter
`spriteMatter: ((ByteArray) -> ByteArray?)? = null`; when set it runs after **every** stylize attempt and the gate
scores the matted sprite; `CharacterResult.sprite` is the matted PNG. On matte failure: fall back to one SAM pass on the
stylized image (`models.segment(ref, sprite, Jobs.CUTOUT_PROMPTS)` + `cutoutComposer`); if that also fails, ship the
unmatted sprite. Metrics stage name `"matte"` (cost 0 for the deterministic path; the SAM fallback's measured/estimated
cost when used). Stage order in metrics: cutout, then per attempt stylize, matte, gate, then sheet.

**(b) Sheet sees the character.** `MetaApi.characterSheet` sends a multimodal chat message when `image != null`
(text part + one `image_url` part, like `scoreStyle`), keeping `response_format` json_schema, `max_completion_tokens`
2048, `reasoning_effort` low. `Prompts.characterSheet(description)` keeps working for blank descriptions
(no "A child drew a character: ." artefacts). Update `docs/api-samples/spark-sheet/request.json.tmpl` to the new shape.
The pipeline passes the cutout PNG (downscaled, see (e)) and the description (blank when none).
`FixtureApi`/`FakeModels` ignore the image.

**(c) Sheet in parallel.** The sheet call starts right after the cutout and runs concurrently with the
stylize→matte→gate loop (`coroutineScope { async { ... } }`). Only the calling coroutine touches `MetricsLog`: the sheet
coroutine returns its result + duration + ok flag, and the stage is recorded after the loop, so stage order in the JSON
stays deterministic. Sheet failure no longer kills the character: record the stage with `ok=false`, ship a fallback sheet
(`SheetParser.fallback(ref)`: name derived from the ref, ability JUMP, `FALLBACK_LINES`). Cutout/stylize/gate failures
still fail the character as today. Cancellation still propagates.

**(d) Measured cost.** Parse `usage` leniently on every reply: chat `{prompt_tokens, completion_tokens}`, image
`{input_tokens, output_tokens}`, SAM (count requests; `usage.images_processed` when present). Values may be strings
like `"<redacted>"` in saved fixtures — treat non-numbers as absent, never fail a call on usage. New `Pricing` object with
the published prices as named constants and their source in KDoc: SAM 3.1 **$0.0025 per request**, Muse Image
**$0.01 per generated image**, Spark priced at the **standard-tier** rates **$1.25/M input, $4.25/M output** as an upper
bound (the contributor tier is cheaper but unpublished — say so in KDoc). `MetaApi` calls `costMeter?.record(job, ref, usd)`
once per HTTP call. `MetricsLog.record` accumulates per job, thread-safe without new dependencies
(`kotlin.concurrent.atomics` with `@OptIn(ExperimentalAtomicApi::class)` or a lock-free alternative). The pipeline takes
the accumulated measured cost for a stage when present, else the `CostTable` estimate (the sheet runs concurrently, so
accumulate by job, not "since last stage": jobs are disjoint between the two coroutines; SAM calls in the matte fallback
are sequential with the cutout). Recalibrate the `CostTable` defaults to the measured per-call prices
(cutout = 2 SAM requests = 0.005, stylize 0.01, score and sheet: your estimate from the Spark pricing with ~9 images
at ≤512 px; document the arithmetic). Schema stays `schema: 1`; if you add a field (e.g. a per-stage `measured: Boolean`)
it must have a default, be documented in `.claude/skills/metrics/SKILL.md`, and old runs/*.json must still parse.

**(e) Downscale.** Pipeline parameter `imageResizer: ((bytes: ByteArray, maxSide: Int) -> ByteArray)? = null`
(`JvmRaster.downscale`, keeps aspect, never upscales, PNG keeps alpha, JPEG input may stay JPEG). Sizes as named
constants: Muse input cutout ≤ 1024 px long side; Spark score sprite + refs ≤ 512; sheet image ≤ 512. The style refs
are downscaled once per run (not per attempt). SAM keeps the full photo.

**(f) Progress events.** `sealed interface PipelineEvent`: `StageStarted(stage, attempt)`, `StageFinished(stage, attempt,
ok, ms)`, `GateScored(attempt, score, passed)`, `Finished(result)`/`Failed(message)` — pick names that read well; stage
names are the existing `STAGE_*` constants plus `"matte"`. Pipeline parameter `onEvent: ((PipelineEvent) -> Unit)? = null`,
invoked on the calling coroutine only. This is what T03 will use to drive ProcessingScreen; keep it free of UI types.

`JvmPipeline.create` wires decode, cutout, matte, downscale (parameters with defaults so callers can turn pieces off).

## Tests required
- `matteRemovesWhiteBackgroundKeepsEnclosedWhite` — synthetic sprite: white bg, black ring outline, white/pale interior: background transparent, interior opaque.
- `matteDegenerateReportsFailure` — all-white and no-white images both fail the bounds.
- `pipelineScoresMattedSprite` — the gate receives the matted bytes; `CharacterResult.sprite` is matted.
- `matteFailureFallsBackToSam` — matter returns null → one extra `segment` call, stage `matte` recorded.
- `sheetRunsConcurrentlyAndIsRecordedLast` — with a delaying fake, total time < sum of stage times (virtual time via `runTest`), sheet stage last in metrics.
- `sheetFailureShipsFallbackSheet` — character completes, sheet stage `ok=false`, gate fields intact.
- `sheetRequestCarriesImagePart` (RequestShapeTest) — json_schema kept, one image_url part when an image is given, plain string when not.
- `usageParsesNumbersAndIgnoresRedacted` — chat, image and SAM usage → USD via `Pricing`; `"<redacted>"` → absent, no throw.
- `measuredCostOverridesEstimate` — `MetricsLog` uses recorded cost per stage, falls back to `CostTable` when none.
- `costMeterIsThreadSafe` (jvmTest or commonTest with `Dispatchers.Default`) — concurrent records sum exactly.
- `downscaleKeepsAspectAndNeverUpscales` (jvmTest, JvmRaster).
- `eventsFollowStageOrder` — one first-try and one retry run produce the expected event sequence.
- Every existing test stays green (update expectations only where the contract above changes behaviour, and say which).
Tests not required: Compose, the CLI, live network calls.

## Steps
1. Read CLAUDE.md, this brief, `.claude/skills/meta-fixtures/SKILL.md`, `.claude/skills/metrics/SKILL.md`, `.claude/skills/verify/SKILL.md`.
2. Implement (a)→(f) in that order; commit after each with `core: ...` messages so partial work is mergeable.
3. `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :cli:compileKotlin --console=plain -q` after each step;
   `ANDROID_HOME=$HOME/Library/Android/sdk scripts/verify.sh` once at the end.
4. Offline smoke: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :cli:run --args="character --fixtures docs/api-samples --out <scratch dir> --all"` must still finish
   (write to a scratch dir, not runs/).

Rules: no live API calls, never read local.properties, no new dependencies, no Gradle edits.

## Done when
- `scripts/verify.sh` exit 0
- offline smoke run finishes with stage order cutout, stylize, matte, gate, …, sheet and non-zero costs

## Result (filled on acceptance)
- measured: ...
- run id: runs/<...>.json
- cut/deferred: ...
