# T01 — day1-gate-probes

status: todo
created: 2026-10-28
day: Day 1 (carried over)
gate: plane < 10 s · drift < 1 cm/60 s · cup footprint via ray–plane within 1–2 cm · SAM clean masks on 5–6 objects · Muse style stable over 3 gens · Kazakh ASR usable?
cut-if-slips: never cut (this is the go/no-go for AR vs 2D stand mode)

## Goal
Real evidence for the Day 1 gate: saved API responses for every job, one ARCore recording of the table, and the
ARCore numbers measured on the Pixel. Everything downstream (fixtures, FixtureApi, SceneBuilder parsing) is
unblocked by this brief. Human-driven: it needs the key, the phone and the table.

## Owns
- docs/api-samples/**/request.json.tmpl, *.response.json, meta.json (raw probes; redacted by scripts/probe-meta.sh)
- docs/recordings/ (local only; MP4 not committed)
- local.properties: `META_API_KEY=...` (you, by hand)

## Contract
- One probe per job via `META_BASE_URL=... scripts/probe-meta.sh <job> <input>`; inputs: a table photo with 5–6
  objects (cup, book, phone, bottle, hand), a pencil drawing, a toy photo, 8 culled style refs, a 5 s Kazakh clip.
- Each `meta.json` ends with `latencyMs`, HTTP status, and a hand-written `costUsd` estimate per call.
- After each probe, convert the raw response into `<name>.fixture.json` (normalized schema in docs/api-samples/README.md)
  and update the parser in `core/meta/MetaDtos.kt` + a test if the shape differs from the synthetic fixtures.
- ARCore: `./gradlew :androidApp:installDebug`, place the anchor, time plane detection, leave the phone still 60 s
  and measure anchor drift with `scripts/device.sh log` (hit-test the same cup edge at t=0 and t=60).

## Tests required
- `fixtureApiLoadsRealSamTableResponse` — the converted real fixture parses into >= 5 masks.
Tests not required: the probe script, the recording.

## Steps
1. Put the key in local.properties; run the 7 probes; commit the redacted responses.
2. Record one ARCore session of the table (SceneView `ARRecorder`), keep it in docs/recordings/ (gitignored).
3. Fill the Result table below with the gate criteria using the `gate` skill.

## Done when
- `scripts/verify.sh` exit 0
- Gate table filled; decision recorded: AR base (default) or 2D stand fallback.

## Result
| Criterion | Status | Evidence |
|---|---|---|
| plane < 10 s | | |
| drift < 1 cm / 60 s | | |
| quad walks to tap | | |
| cup footprint 1–2 cm | | |
| depth occlusion acceptable | | |
| SAM clean masks 5–6 objects | | |
| Muse style stable x3 | | |
| Kazakh ASR usable | | |
