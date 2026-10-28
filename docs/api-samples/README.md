# docs/api-samples — saved Meta API replies and FixtureApi data

`FixtureApi` (`core/src/commonMain/kotlin/kz/rudy/ar/core/meta/FixtureApi.kt`) replays these files so the
pipeline, the tests and the offline app never touch the network. The CLI runs them with

```
./gradlew :cli:run --args="character --fixtures docs/api-samples --out runs --all"
./gradlew :cli:run --args="character --fixtures docs/api-samples --out runs --ref sample-02 --replay-latency"
```

## Layout

```
docs/api-samples/
  <job>/meta.json                 how this folder's samples were produced (endpoint, model, latency, cost)
  <job>/<ref>.fixture.json        normalised reply for input <ref> (schema below) — what FixtureApi reads
  <job>/<ref>.response.json       raw, REDACTED reply saved by scripts/probe-meta.sh (optional, real probes only)
  <job>/<ref>.headers.txt         the reply headers of that probe (auth/cookie lines stripped)
  <job>/request.json.tmpl         the request body scripts/probe-meta.sh sends (header lines: path, content-type, model)
  sam-*/<ref>.<prompt>.response.json  SAM 3.1 takes ONE concept per request, so a probe saves one reply per prompt
  sam-cutout/<ref>.input.png|jpg  the photo given to SAM (also the CLI's input image for <ref>)
  sam-cutout/prompts.json         ["drawing", "toy"]
  sam-table/prompts.json          the prompt list used for table scans
  sam-rig/prompts.json            body parts for the rigging stretch (head, arm, leg, wing, tail, body)
  muse-stylize/refs/*.png         the style reference images (<= 9) sent to Muse Image edit
  muse-stylize/<ref>.out*.png     stylized outputs, one per attempt
  spark-sheet/<ref>.description.txt  the character description the CLI sends for <ref> (optional)
```

Jobs (= folder names, `Jobs` in MetaModels.kt): `sam-cutout`, `sam-table`, `sam-rig`, `muse-stylize`,
`spark-score`, `spark-sheet`, `voice`. A `<ref>` is the input's stable name (`sample-01`, later `cat-01`);
the same `<ref>` across folders describes one character.

## `<ref>.fixture.json` (schema 1)

```json
{
  "schema": 1, "job": "spark-score", "ref": "sample-02", "synthetic": true, "note": "...",
  "latencyMs": 1200,
  "masks":   [ { "label": "drawing", "width": 64, "height": 64, "rle": [1040, 32, 32, ...], "score": 0.93 },
               { "label": "book", "width": 64, "height": 64, "bbox": { "x": 28, "y": 44, "w": 33, "h": 17 } } ],
  "outputs": [ "sample-02.out-1.png", "sample-02.out-2.png" ],
  "scores":  [ 0.55, 0.65, 0.75 ],
  "sheet":   { "name": "...", "personality": "...", "ability": "fly", "lines": { "kk": [...], "ru": [...] } },
  "text":    "секір"
}
```

Only the field for the job is present:

| job | field | semantics |
|---|---|---|
| `sam-cutout` | `masks` | one per prompt (`drawing`, `toy`); the pipeline takes the largest non-empty mask |
| `sam-table` | `masks` | one per prompt from `prompts.json`; an absent object (SAM returned no match) is a mask with `label`, `width`, `height` and no `rle`/`bbox` |
| `muse-stylize` | `outputs` | sibling PNG file names, one per stylize attempt; the last one repeats |
| `spark-score` | `scores` | one gate score per call in order (attempt 1, 2, 3); the last one repeats |
| `spark-sheet` | `sheet` | the model's JSON exactly as returned; `SheetParser` maps unknown abilities to JUMP and pads to 5 lines |
| `voice` | `text` | the transcript |

`rle` is COCO-style uncompressed RLE over the row-major pixel grid: alternating OFF/ON run lengths, starting
with OFF, summing to `width * height`. `latencyMs` (optional) overrides the folder's `meta.json` latency when the
CLI runs with `--replay-latency`.

## `meta.json`

```json
{ "job": "spark-score", "endpoint": "POST /chat/completions", "model": "spark-1.3",
  "requestShape": "{model, messages: [...]}", "latencyMs": 1200, "costUsd": 0.002,
  "probedAt": "2026-10-27T18:40:00Z", "synthetic": false, "notes": "..." }
```

`latencyMs` is the measured wall time of the real call; `costUsd` the per-call estimate that `CostTable`
(core/metrics/MetricsLog.kt) must match. `probedAt` stays empty while the folder is synthetic.

## Cost: measured vs estimated

Live calls are priced from each reply by `core/meta/Pricing.kt` and reported to `MetricsLog` (`MetaApi`'s
`CostMeter`): SAM 3.1 $0.0025 per request (`usage.images_processed` when present), Muse Image $0.01 per generated
image, Spark at the standard-tier $1.25/M input + $4.25/M output tokens from `usage.prompt_tokens` /
`completion_tokens` (an upper bound: the contributor tier is cheaper, its price is unpublished). `usage` is parsed
leniently, so the saved replies here, whose token counts read `"<redacted>"`, still decode; they just carry no Spark
cost. Stages without a measured cost (every `FixtureApi` run, a reply without `usage`) use the `CostTable` estimates:
cutout $0.005 (2 SAM requests), stylize $0.01, score ~$0.009 (~9 images at <= 512 px), sheet ~$0.005; the arithmetic
is in `CostTable`'s KDoc. `runs/*.json` mark each stage `measured: true|false`.

## Pipeline image sizes

`CharacterPipeline` sends SAM the full photo; Muse gets the cutout downscaled to <= 1024 px on the long side; the
Spark score gets the matted sprite and the style refs at <= 512 px (the refs are downscaled once per run); the sheet
call gets the cutout at <= 512 px. Muse's output is matted (`SpriteMatte`: white background -> transparent) before
the gate sees it. The sheet call runs concurrently with stylize/matte/gate.

## Synthetic flag

`sample-01` and `sample-02` are **synthetic** (`"synthetic": true`): 64x64 programmatically generated PNGs and
hand-written replies that exercise the pipeline offline. `sample-01` passes the gate on the first try (score 0.85);
`sample-02` fails twice and passes on the third attempt (0.55, 0.65, 0.75). Keep them: tests depend on them.

**Real** (`"synthetic": false`, probed 2026-10-28, each folder's `meta.json` carries latency, cost and notes):
`sam-table/table-01` (5 prompts on a real table photo), the complete character `drawing-01` (sam-cutout, muse-stylize,
spark-score, spark-sheet) and `voice/jump-01` (English). `drawing-01`'s score is 0.00 because the only style refs on
disk are the synthetic squares; it becomes meaningful once the real refs land in `muse-stylize/refs/`.
Never present a synthetic number as a measurement: `runs/*.json` produced with `provider: "fixture"` are
smoke-test runs, not README figures.

## Naming

- `<ref>` is kebab-case, no spaces: `cat-01`, `robot-toy-02`. It appears in `runs/*.json` as `characters[].input`.
- Raw probe replies: `<ref>.response.json`; decoded binary payloads next to them: `<ref>.mask.png`, `<ref>.out.png`.
- Per-attempt outputs: `<ref>.out-<n>.png`.

## Endpoints (Day 1 probe)

Everything is served by the **Meta Model API** (`https://api.meta.ai/v1`, `Authorization: Bearer <key>`, docs at
https://dev.meta.ai/docs). Verified on 2026-10-28 against the official docs (the links in the last column); nothing
has been exercised with a key yet, so the first real probe may still correct a detail. Each `request.json.tmpl`
header lists its own `# unverified:` lines.

| job | base URL (`META_BASE_URL`) | path | content type | model id | verified? |
|---|---|---|---|---|---|
| `sam-cutout` | `https://api.meta.ai` | `/v1/responses` (one request per prompt) | json (base64 data URL) | `sam-3.1` | yes — https://dev.meta.ai/docs/sam/segmenting ; reply wrapper for `stream:false` unverified |
| `sam-table` | `https://api.meta.ai` | `/v1/responses` (one request per prompt) | json (base64 data URL) | `sam-3.1` | yes — https://dev.meta.ai/docs/sam/reading-segmentation ; same caveat |
| `sam-rig` | `https://api.meta.ai` | `/v1/responses` (one request per prompt) | json (base64 data URL) | `sam-3.1` | yes (call shape) — https://dev.meta.ai/docs/media-segmentation ; quality on cartoon parts unverified |
| `muse-stylize` | `https://api.meta.ai` | `/v1/images/edits` | json (`images[]` of data URLs, <= 10) | `muse-image-1.0` | yes — https://dev.meta.ai/docs/image-generation ; `background` is always `opaque` (alpha not kept) |
| `spark-score` | `https://api.meta.ai` | `/v1/chat/completions` | json (`image_url` parts, data URLs) | `muse-spark-1.3-contributor` | yes — https://dev.meta.ai/docs/image-understanding |
| `spark-sheet` | `https://api.meta.ai` | `/v1/chat/completions` | json (`response_format: json_schema`) | `muse-spark-1.3-contributor` | yes — https://dev.meta.ai/docs/features/structured-output ; Kazakh quality unverified |
| `voice` | `https://api.meta.ai` | `/v1/asr/transcribe` | multipart (`request` JSON part + `audio` WAV) | `muse-voice-transcribe-1.0` | yes — https://dev.meta.ai/docs/api-reference/voice/transcribe ; Kazakh is NOT in the 25 supported languages |

What the docs say that the code did not expect:

- **SAM 3.1** is not a `/segment` endpoint. It rides the Responses API, accepts exactly one concept prompt per request
  (comma lists return zero matches) and returns special-token text, one line per frame:
  `<0f>0<|box;x1=..;y1=..;x2=..;y2=..;w=W;h=H;c=0.9|><|mask;x=0;y=0;data=H,W,~<base85>|>`. The mask payload is Meta's
  own base85 encoding (`~` lossless, `!` one_bit), not COCO RLE; Meta ships `@meta-sam/parser` (TS) and
  `meta_sam_parser` (Python) with `decode_mask_to_rle`. Zero matches = empty `output_text`, not an error.
  `metadata` values are strings: `mask_encoding`, `include_confidence`, `propagation_direction`.
- **Muse Image edit** wants `images: [{"image_url": "data:..."}, ...]` (1..10, the cutout first, then the refs) in a
  JSON body, or multipart `image` via the OpenAI SDK. Reply `data[0].b64_json`, `output_format` (default webp — we
  ask for png), `background: "opaque"`. $0.01 per image, 150 RPM.
- **Muse Spark 1.3** is OpenAI-compatible Chat Completions. Use `max_completion_tokens` (`max_tokens` is a deprecated
  alias), leave `temperature` at the default 1.0 (reasoning model; `stop`, `logprobs`, `n>1` -> 400),
  `reasoning_effort` in minimal..xhigh. Standard tier $1.25/M in, $4.25/M out, 3,000 RPM; `-contributor` is cheaper
  but Meta may train on it.
- **Muse Voice Transcribe** file endpoint is multipart only: `request` part
  `{"model","audioEncoding":"WAV","mode":"PUSH_TO_TALK","languageBias":[...],"keywords":[...]}` plus `audio` =
  RIFF WAV, mono 16-bit PCM at 16 or 24 kHz (raw PCM -> 400). Reply `{"sessionId","transcript","audioDurationMs","turns"}`
  (no `text` field). $0.18 per audio hour. Supported languages (25) do not include Kazakh or Russian; `languageBias`
  is a hint by language *name*.
- **Fallback providers.** No evidence (2026-10-28) that Baseten lists SAM 3.1 or that Fireworks serves SAM 3,
  Muse Image or Voice Transcribe. Fireworks does serve the open-weight `accounts/fireworks/models/muse-spark-open`
  (OpenAI-compatible, `https://api.fireworks.ai/inference/v1`, limited preview) — a chat-only fallback for the
  Spark jobs. The "Baseten slug" in ai/build-plan.md would be a self-deployed SAM 3.1 from the Hugging Face
  checkpoint, with its own request shape.

## Probing

```
scripts/probe-meta.sh --dry-run sam-table photos/table-01.jpg     # renders the bodies, no key, no network
export META_BASE_URL=https://api.meta.ai                           # no trailing slash, no /v1 (the template path has it)
scripts/probe-meta.sh sam-table photos/table-01.jpg                # 6 requests, one per prompt in prompts.json
scripts/probe-meta.sh muse-stylize docs/api-samples/sam-cutout/cat-01.input.png   # + muse-stylize/refs/*.png
scripts/probe-meta.sh spark-sheet docs/api-samples/spark-sheet/cat-01.description.txt
scripts/probe-meta.sh voice clips/jump-kk.wav                      # mono 16-bit PCM WAV, 16 or 24 kHz
```

The key is read from `local.properties` (`META_API_KEY=...`) or the environment and never printed. `--dry-run`
prints the path, content type, body size and the first 300 chars of each rendered body with base64 collapsed, and
exits 0 without `META_BASE_URL` or a key. Each real probe writes `<ref>[.<prompt>].response.json` (redacted),
`<ref>[.<prompt>].headers.txt` and appends `{when, input, httpStatus, latencyMs}` to `meta.json`; fill in
`costUsd`, `model`, `requestShape` and `probedAt` by hand afterwards.

## Redaction (pre-commit hook enforces the token shape)

- Strip `Authorization`, `x-api-key`, cookies and any request id that embeds an account id.
- Base64 payloads over 2 KB: replace the string with `"<base64 N bytes>"` and save the decoded file as a sibling.
- Never paste a raw reply into chat if it is larger than a screen; describe its shape and sizes instead.
- `scripts/probe-meta.sh <job> <input>` does all of this and reads the key from `local.properties` itself.

If the real API deviates from a fixture, update the fixture *and* the DTO/parser in `core/meta/MetaDtos.kt` in
the same change, with a test (`core/src/commonTest/.../meta/FixtureApiTest.kt`).
