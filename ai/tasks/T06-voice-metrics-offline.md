# T06 — voice-metrics-offline

status: todo
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
