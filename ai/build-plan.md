# Rudeus — AR game build plan (Oct 27 → Nov 4, 2026)

Full editable version: Claude Doc "Rudeus AR Game Build Plan". This copy is the reference for other chats in this Project.

**Pitch:** whatever is on your table becomes the character; the table becomes the level. Android-only ARCore game: a child's drawing (or toy) → character; real table objects → obstacles, platforms, cliffs. Deadline Wed Nov 4, 9:00 PM ET (02:00 UTC Nov 5).

## Model roles (runtime)
| Model | Job |
|---|---|
| SAM 3.1 job 1 | cut drawing/toy out of the photo (no paper/shadows) |
| SAM 3.1 job 2 | rig stylized sprite into parts (head/legs/arms/wings) — stretch |
| SAM 3.1 job 3 | segment table objects (cup, book, phone, hand…) into level geometry + occluders |
| Muse Image edit | stylize cutout with ≤9 style reference images |
| Spark 1.3 vision | style gate: score vs refs (threshold 0.7, ≤2 retries) |
| Spark 1.3 | character sheet JSON: name, personality, ability ∈ {jump, fly, roll, hide}, 5 lines kk/ru |
| Voice Transcribe | optional; only if Kazakh ASR passes Day 1 |

## Locked decisions
- Android only; KMP split is the multiplatform story; README says iOS out of scope by choice.
- ARCore classic via SceneView 4.31.0 (`io.github.sceneview:arsceneview`), no Jetpack XR.
- No backend. Ktor from phone; key from `local.properties` → `BuildConfig`; JVM CLI runs the same pipeline from the Mac.
- Character = billboard quad (Y-only) with sprite texture.
- Level in plane coords (u, v) + height h. Footprints via ray–plane using camera pose + intrinsics saved at scan time. No Depth API dependency for geometry.
- Occlusion: Depth API flag first; occluder boxes (`depthWrite=true, colorWrite=false`) as plan B.
- Scan = explicit button, 3–5 s "looking around" animation. Never per-frame.

## Repo today
Untouched CMP template: Kotlin 2.4.21, AGP 9.1.1, CMP 1.12.2, compileSdk 37, minSdk 24, package `kz.rudy.ar`, modules `:androidApp` + `:shared`. No git, no AR deps, no Meta client.

## Target module layout
```
:core        KMP (android + jvm), no Compose — domain, physics, meta/ (MetaModels iface, MetaApi Ktor 3, FixtureApi), pipeline/ (CharacterPipeline, SceneBuilder), metrics/
:shared      CMP UI, depends on :core — Capture, Scanning, Play, Metrics screens; HUD; 2D Canvas fallback
:androidApp  AR host — ARSceneView, frame grab, (u,v,h)→Pose, occluders, BuildConfig.META_API_KEY
:cli         JVM main — runs pipeline against the real API from the Mac
```
Rules: AR view injected into `App()` as a slot composable from androidApp (no expect/actual, no stub actuals). `:core` has no Compose. AR/Filament/Gradle code hand-written or hand-reviewed; agents work on `:core`/`:cli` with tests + saved real API responses (`docs/api-samples/`).

## Day by day
**Day 1 — Tue Oct 27: setup + go/no-go gate.** git init + public repo (README: new work after Oct 26); Pixel ARCore services updated; key → BuildConfig (standard tier); `claude mcp add sceneview`; curl probes saved to `docs/api-samples/` (SAM on table photo w/ 5–6 objects, SAM on pencil drawing, Muse Image edit w/ refs, Spark vision score, Voice Kazakh clip, Baseten slug); arsceneview + manifest + CAMERA; record one ARCore session of the table (Recording API).
Gate: plane < 10 s & drift < 1 cm/60 s · quad walks to tap · cup footprint via ray–plane within 1–2 cm · depth occlusion acceptable or decide occluders · SAM clean masks on 5–6 objects (latency, cost) · Muse Image style stable over 3 gens · Kazakh ASR usable?
Rule: first three pass → AR is the base. Plane fails → phone-on-stand 2D fallback (CameraX + Canvas), only `:androidApp` changes.

**Day 2 — Wed Oct 28: pipeline as CLI.** `:core` + `:cli`; 8 culled style refs; CharacterPipeline (cutout → stylize → gate → sheet); deterministic gate half (palette ΔE Lab, outline thickness); MetricsLog → `runs/<ts>.json`; run 10 drawings/toys → fixtures + bundled demo characters; unit tests on FixtureApi.
Gate: ≥7/10 pass gate first try; p50 < 40 s; ≤ $0.05/character.

**Day 3 — Thu Oct 29: character on the table.** Anchor placement (one anchor/session); billboard quad; tap-to-walk via hit test → core FSM → Pose; walk anim (sprite-sheet or squash-stretch, pick one); in-app capture (acquireCameraImage 1080p → pipeline); PAUSED freezes physics, never resets.
Gate: 60 s walking around a real cup without drift; capture → character < 60 s.

**Day 4 — Fri Oct 30: table becomes level.** Scan saves pose/intrinsics/plane id → SAM prompts cup, book, phone, bottle, hand, object; SceneBuilder: mask → contour → Douglas–Peucker → bottom-edge rays → plane → footprint (u,v); height from top-edge ray. Semantics: cup/bottle obstacle; book/phone platform (h ≤ 4 cm); hand dynamic; plane boundary cliff. Physics: circle-vs-polygon push-out, step-up, cliff fall/respawn. Debug footprint overlay (toggle). Re-scan diffs by centroid, stable ids.
Gate: 5 objects within 2 cm, character routes around; scan < 6 s.

**Day 5 — Sat Oct 31: occlusion, jump, affordances.** depthMode on, else occluder boxes (decide by noon); jump parabola in (u,v,h); affordances ≤3: jump (default), fly (wings: crosses gaps), roll (wheels: 2× speed, no jump); hand = stretch; scripted 30 s scenario: place → walk → blocked by cup → jump on book → fly over gap.
Gate: scenario runs 3× in a row without code changes.

**Day 6 — Sun Nov 1: voice, metrics, polish.** Speech bubbles from sheet lines (event-triggered); Voice only if Day 1 passed (AudioRecord PCM 16 kHz mono); SAM rigging stretch; Metrics screen + Export JSON; offline mode (bundled characters + scene); 5 CC0 sounds.
Gate: fresh install → character → full scenario with Wi-Fi off halfway, no crash.

**Day 7 — Mon Nov 2: video + README.** ≤3 min: 0:00–0:15 hook ("your drawing, your table"), 0:15–1:15 pipeline + gate score, 1:15–2:15 gameplay, 2:15–3:00 architecture + metrics. Tripod second camera + scrcpy, textured cloth, daylight, own drawings/generic toys only. README: pitch, model table, diagram, metrics from runs/, cost, new-work statement, iOS out of scope, key handling, licenses. Tag v1.0, code freeze.

**Day 8 — Tue Nov 3: buffer + submit.** Fix only what the shoot exposed; rerun Day 6 gate; `release/` with APK, video, metrics JSON; submit evening of Nov 3.

## Cut lines (in order; cut at 6 PM if a gate still fails)
1. Voice Transcribe · 2. SAM rigging → squash-stretch · 3. Hand interaction · 4. Depth occlusion → occluder boxes → none · 5. Affordances → jump only · 6. In-app capture → CLI-generated bundled characters.
Never cut: Day 1 gate, metrics JSON, Day 6 offline mode, Day 7 shoot.

## Risks → fallbacks
No plane on plain/glossy table → textured cloth, side light, else 2D stand mode · SAM latency → scan on demand + cover animation, cache masks · style drift → fixed refs, gate+retry, template body · tracking loss → one anchor, freeze on PAUSED · API outage → FixtureApi + bundled characters · Baseten slug 404 → provider interface, Fireworks fallback · branded toys → own drawings only · agent AR code drift → hand-review AR/Gradle · depth jitter (no ToF) → occluder boxes · contributor tier → standard tier key.

## Rubric mapping
Impact 30: kids' game in Kazakh/Russian from own drawings (Build for Your World). Tooling 25: SAM ×3 jobs, Muse Image edit, Spark gate + sheet, Voice. Demo 25: real table, real cup, 3-min video. Depth 20: measured style gate, ray–plane geometry, metrics JSON, KMP split, Recording/Playback dev loop.
First 15 s of the video: the pet is your child's drawing, and the level is your table.

## Submission checklist
Public repo w/ first commit after Oct 26 · video ≤ 3 min · README metrics match runs/*.json · no API key in git history · LICENSE + third-party list · release/ with APK, video, metrics · iOS statement + key handling note · form submitted Nov 3 evening.
