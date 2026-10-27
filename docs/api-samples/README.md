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
  sam-cutout/<ref>.input.png|jpg  the photo given to SAM (also the CLI's input image for <ref>)
  sam-table/prompts.json          the prompt list used for table scans
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
| `sam-table` | `masks` | one per prompt from `prompts.json`; an absent object is an empty mask (`"rle": [width*height]`) |
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

## Synthetic flag

Everything in this folder today is **synthetic** (`"synthetic": true` in every fixture and meta.json): 64x64
programmatically generated PNGs and hand-written replies that exercise the pipeline offline. `sample-01` passes the
gate on the first try (score 0.85); `sample-02` fails twice and passes on the third attempt (0.55, 0.65, 0.75).
When the Day 1 probe lands, replace them with real (redacted) replies, set `synthetic: false` and fill `probedAt`.
Never present a synthetic number as a measurement: `runs/*.json` produced with `provider: "fixture"` are
smoke-test runs, not README figures.

## Naming

- `<ref>` is kebab-case, no spaces: `cat-01`, `robot-toy-02`. It appears in `runs/*.json` as `characters[].input`.
- Raw probe replies: `<ref>.response.json`; decoded binary payloads next to them: `<ref>.mask.png`, `<ref>.out.png`.
- Per-attempt outputs: `<ref>.out-<n>.png`.

## Redaction (pre-commit hook enforces the token shape)

- Strip `Authorization`, `x-api-key`, cookies and any request id that embeds an account id.
- Base64 payloads over 2 KB: replace the string with `"<base64 N bytes>"` and save the decoded file as a sibling.
- Never paste a raw reply into chat if it is larger than a screen; describe its shape and sizes instead.
- `scripts/probe-meta.sh <job> <input>` does all of this and reads the key from `local.properties` itself.

If the real API deviates from a fixture, update the fixture *and* the DTO/parser in `core/meta/MetaDtos.kt` in
the same change, with a test (`core/src/commonTest/.../meta/FixtureApiTest.kt`).
