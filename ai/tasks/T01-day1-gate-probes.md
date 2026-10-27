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
| plane < 10 s | PASS | `RudeusGate plane_detected_ms=1387` on Pixel 9 Pro XL, ARCore 1.57, textured cloth (2026-10-28) |
| drift < 1 cm / 60 s | UNTESTED | needs anchor placed + 60 s still; read `anchor t_ms=0` vs `t_ms≈60000` |
| quad walks to tap | UNTESTED | T02 (billboard quad not in the build yet) |
| cup footprint 1–2 cm | UNTESTED | needs four `tap u= v=` lines on the same object edge |
| depth occlusion acceptable | SUPPORTED | `depth_supported=true depth_mode=AUTOMATIC`; visual quality judged on Day 5 |
| SAM clean masks 5–6 objects | PARTIAL | `sam-table/table-01.fixture.json`: laptop, toy car, knife clean (c ≥ 0.92); "remote" merged remote+speaker into one mask; "speaker" absent. Latency 1.5–3.2 s per prompt, $0.0025/request. `store:false` required for full-size photos |
| Muse style stable x3 | UNTESTED | endpoint works (`muse-stylize/drawing-01.out-1.png`, 12–19 s, $0.01, opaque background); stability x3 needs the 8 real style refs (not available yet) |
| Kazakh ASR usable | UNTESTED | needs a 5 s Kazakh clip; docs list neither Kazakh nor Russian among supported languages — expect cut line 1 |

Real samples: `sam-table/table-01.*` (5 prompts) and a complete real character `drawing-01` across sam-cutout, muse-stylize,
spark-score and spark-sheet (all `synthetic:false`); `runs/2026-10-27T22-58-25Z.json` is the offline pipeline run on it
(smoke run, provider=fixture, old CostTable pricing — not a README figure). Spark runs on `muse-spark-1.3-contributor` (user decision).
Findings: SAM needs `store:false` for full-size photos; Spark reasoning eats small budgets (sheet 2048, score 512);
the score is 0.00 against the synthetic 64x64 refs, so the 0.7 threshold cannot be judged until real refs exist;
Muse returns an opaque background, so the pipeline needs a second cutout (design item for Day 2).
The dashboard issues ids as `LLM_<id>_<suffix>` and the API accepts that form. Voice: not probed (no Kazakh clip).
