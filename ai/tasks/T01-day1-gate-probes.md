# T01 — day1-gate-probes

status: doing
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
| plane < 10 s | PASS (textured surface only) | `plane_detected_ms=1387` on textured cloth; afternoon of Oct 28 on paper next to the cup: 5503 ms, 4682 ms (one re-entered screen took 47140 ms). Bare grey desk: no plane at all (`tracking_failure=INSUFFICIENT_FEATURES`); dim light: `INSUFFICIENT_LIGHT`. `docs/evidence/t01-gate-2026-10-28.log` |
| drift < 1 cm / 60 s | PASS (anchor placed after the map settles) | run C: 0.72 cm from placement to t=60 s, of which 0.66 cm in the first 10 s, then 0.07 cm (0.14 cm by t=90 s). Run A, anchor placed 3.7 s after the plane: 4.7 cm jump in the first 10 s, then up to 1.6 cm. Same log. T02 should wait a few seconds of plane tracking before it accepts the anchor tap |
| quad walks to tap | UNTESTED | T02 (billboard quad not in the build yet) |
| cup footprint 1–2 cm | UNTESTED | run C has 11 taps but no record of which edge each one was or the cup's real size; repeat: left and right bottom edge twice, plus the bottom diameter with a ruler |
| depth occlusion acceptable | SUPPORTED | `depth_supported=true depth_mode=AUTOMATIC`; visual quality judged on Day 5 |
| SAM clean masks 5–6 objects | PARTIAL | `sam-table/table-01.fixture.json`: laptop, toy car, knife clean (c ≥ 0.92); "remote" merged remote+speaker into one mask; "speaker" absent. Latency 1.5–3.2 s per prompt, $0.0025/request. `store:false` required for full-size photos |
| Muse style stable x3 | UNTESTED | endpoint works (`muse-stylize/drawing-01.out-1.png`, 12–19 s, $0.01, opaque background); stability x3 needs the 8 real style refs (not available yet) |
| Kazakh ASR usable | PASS (rescoped to English) | decision Oct 28: demo is spoken in English (Russian second); Kazakh is not an ASR language and that is accepted. `voice/jump-01`: "jump over the cup now fly to the book" transcribed exactly, 2.9 s, from a 16 kHz synthesized clip |

Real samples: `sam-table/table-01.*` (5 prompts) and a complete real character `drawing-01` across sam-cutout, muse-stylize,
spark-score and spark-sheet (all `synthetic:false`); `runs/2026-10-27T22-58-25Z.json` is the offline pipeline run on it
(smoke run, provider=fixture, old CostTable pricing — not a README figure). Spark runs on `muse-spark-1.3-contributor` (user decision).
Findings: SAM needs `store:false` for full-size photos; Spark reasoning eats small budgets (sheet 2048, score 512);
the score is 0.00 against the synthetic 64x64 refs, so the 0.7 threshold cannot be judged until real refs exist;
Muse returns an opaque background, so the pipeline needs a second cutout (design item for Day 2).
The dashboard issues ids as `LLM_<id>_<suffix>` and the API accepts that form. Voice: probed in English with a synthesized clip (demo language).
