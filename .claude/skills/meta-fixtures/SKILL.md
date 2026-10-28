---
name: meta-fixtures
description: How Rudeus talks to the Meta model APIs (SAM 3.1 segmentation, Muse Image edit, Spark 1.3 vision/text, Voice Transcribe), how to probe them safely with curl, and how saved responses in docs/api-samples become FixtureApi test data. Load when writing anything in core/meta, core/pipeline, or when a probe is needed.
---

# Meta API fixtures

`:core/meta` has one interface, `MetaModels`, and two implementations:
- `MetaApi` — Ktor 3 client, real calls, key from `BuildConfig.META_API_KEY` (phone) or `META_API_KEY` env (CLI).
- `FixtureApi` — replays saved JSON from `docs/api-samples/`. Tests and offline mode use this one. Never flaky.

## Jobs and fixture folders
| Job | Folder | Input | Output file |
|---|---|---|---|
| SAM cutout (drawing/toy) | `docs/api-samples/sam-cutout/` | `<name>.jpg` | `<name>.response.json` (+ `<name>.mask.png`) |
| SAM table objects | `docs/api-samples/sam-table/` | `<name>.jpg` + `prompts.json` | `<name>.response.json` |
| SAM rigging (stretch) | `docs/api-samples/sam-rig/` | sprite png | `<name>.response.json` |
| Muse Image stylize | `docs/api-samples/muse-stylize/` | cutout png + `refs/` | `<name>.response.json` + `<name>.out.png` |
| Spark vision gate score | `docs/api-samples/spark-score/` | stylized png | `<name>.response.json` |
| Spark character sheet | `docs/api-samples/spark-sheet/` | sheet prompt | `<name>.response.json` |
| Voice transcribe (kk) | `docs/api-samples/voice/` | `<name>.wav` | `<name>.response.json` |

Every folder has a `meta.json` with: endpoint path used, model id, request shape, measured latency ms, cost estimate,
date probed. `FixtureApi` reads `meta.json` to replay realistic latency in the CLI (`--realistic-latency`).

## Probing (Day 1 item, re-run when an endpoint changes)
```
scripts/probe-meta.sh --dry-run sam-table docs/api-samples/sam-table/table-01.jpg   # render only: no key, no network
export META_BASE_URL=https://api.meta.ai                                            # no trailing slash, no /v1
scripts/probe-meta.sh sam-table docs/api-samples/sam-table/table-01.jpg             # one request per prompt
```
The request shape per job lives in `docs/api-samples/<job>/request.json.tmpl` (verified endpoints and the
`# unverified:` caveats are tabulated in docs/api-samples/README.md, "Endpoints (Day 1 probe)"). The script reads
the key from `local.properties` itself; you never see or print it. It writes the redacted response
next to the input (`<ref>.<prompt>.response.json` for the SAM jobs, since SAM 3.1 takes one concept per request). **Do not** write your own curl with the key inline, and do not paste responses into chat
if they are larger than a screen: summarise shape and sizes instead.

## Redaction rules (pre-commit hook enforces the token shape)
- Strip `Authorization`, `x-api-key`, request ids that embed account ids.
- Base64 image payloads over 2 KB: replace with `"<base64 N bytes>"` and save the decoded image as a sibling file.

## Contract the pipeline relies on
- Gate: `score in [0,1]`, threshold **0.7**, at most **2 retries**, then the character ships with `gatePassed=false`.
  Every attempt also gets a colour-free look in parallel (`scoreTechnique`, `Prompts.STYLE_SCORE_NO_COLOUR`; user
  decision 2026-10-28): recorded as `techniqueScores` next to `scores` in `spark-score/<ref>.fixture.json`. Fixtures
  recorded before that have no or fewer `techniqueScores`; a missing list makes that look fail (`rescore` ok=false),
  a short one repeats its last value. Spark gets the first 3 style refs (`CharacterPipeline.SPARK_MAX_REFS`).
- Sheet: `ability` must parse into `Ability {JUMP, FLY, ROLL, HIDE}`; unknown -> `JUMP`. Lines: 5 in `kk`, 5 in `ru`.
- Cutout: a single mask; multiple masks -> take the largest connected component.
- Table: one mask per prompt; empty mask -> object absent, not an error.

If the real API deviates from a fixture, update the fixture *and* the parser in the same change, with a test.
Fallback provider (Baseten slug 404 -> Fireworks) is a `MetaModels` implementation swap, nothing else changes.
