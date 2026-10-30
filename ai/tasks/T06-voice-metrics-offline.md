# T06 — voice-metrics-offline

status: doing (2026-10-31: bubbles + Metrics/Export shipped in T13/T14; offline -> T22 + T20; sounds -> T23; voice open, cut line 1)
created: 2026-10-28
day: Day 6
gate: fresh install -> character -> full scenario with Wi-Fi off halfway, no crash
cut-if-slips: Voice is cut line 1; metrics JSON and offline mode are never cut

## Goal
Speech bubbles from the sheet lines, the Metrics screen with Export JSON, bundled characters + scene for offline,
and (only if T01 said Kazakh ASR is usable) voice commands.

## Owns
- shared/.../ui/screens/MetricsScreen.kt, components/SpeechBubble.kt (wiring)
- androidApp/src/main/kotlin/kz/rudy/ar/voice/** (AudioRecord PCM 16 kHz mono -> `MetaModels.transcribe`)
- androidApp/src/main/assets/bundled/** (3 CLI-generated characters + one saved scene + 5 CC0 sounds)

## Contract
- Bubble triggers: placed, blocked, landed, fell, ability used; one line per event, random among the 5, lang = LocalLang.
- Metrics screen renders `MetricsRun.summary` + per-character rows; Export writes the same JSON the CLI writes.
- Offline: any `MetaError.Network` -> switch `MetaModels` to `FixtureApi(AssetFixtureSource)` for the session; badge on.
- Voice: push-to-talk; commands "секір/прыгай" -> jump, "жүр/иди" -> walk forward; nothing else.

## Tests required
- `metricsExportMatchesCliSchema` (core: serialise a run from the app path and parse with the CLI's reader).
Tests not required: audio, assets.

## Done when
- `scripts/verify.sh` exit 0
- Wi-Fi-off run completes; `runs/` has a run with `source: "app"`.

## Result

### Status (orchestrator, 2026-10-31)
- Speech bubbles: done in T13 (`HudSpeech`, event-triggered, sheet lines, kk/ru/en).
- Metrics screen + Export JSON: done in T13/T14 (`MetricsScreen`, `AndroidCaptureHost.exportRun` via MediaStore Downloads).
- Offline: T22 (`OfflineGuard` in `:core`) + T20 (badge, Capture note, honest Scan copy). Decision: no fixture fallback for live capture/scan (see T22 Goal); bundled characters are the offline content.
- Sounds: T23 (five generated CC0 WAVs, SoundPool wrapper, Activity-level wiring).
- Voice (cut line 1): not started; T01 proved English ASR on a 16 kHz clip. Candidate brief T24 after the Day 5 host draft; manifest already declares RECORD_AUDIO.
- `metricsExportMatchesCliSchema`: the app writes `MetricsLog.toJson()` and the CLI reads `MetricsLog.fromJson`, both in `:core` (`MetricsLogTest` covers the round trip); no separate test added.
