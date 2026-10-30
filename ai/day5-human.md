# Day 5 + Day 6 — what only a human can do (Oct 31, 2026)

Everything is on branch **`day5/play`** in the main checkout (= `day4/table-level` + T19–T24). `scripts/verify.sh` is green
(core 184, shared 12, APK 60 MB). The Pixel was online but **locked** during the autonomous run, so the device QA
agent could only install and launch (no crash at start); every on-device criterion below is UNTESTED.

## 1. What the agents did (read the diffs: `git log --oneline 454553e..HEAD`)
| Brief | What | Status |
|---|---|---|
| T19 `core-scenario` | `Scenario` / `ScenarioRunner` / `ScenarioPlayer` in `:core`; `Scenarios.DAY5` beat sheet; 3x-identical test; FELL pinned over the cliff | done |
| T20 `shared-jump-offline-hud` | Jump / Fly button (none for ROLL), `ArSlot.ability/jumpRequest/scenarioRequest`, debug Scenario button, offline badge + note | done |
| T21 `ar-host-occlusion-jump-scenario` | depth occlusion (Plan A) with occluder cylinders/boxes behind a constant (Plan B), `GameLoop.jump`, scenario replay on simulated time from spawn, jump squash-stretch | needs your Pixel run (hand-review) |
| T22 `core-offline-guard` | `OfflineGuard` decorator: Network error -> offline, next success -> online; wired around every model call in `AndroidCaptureHost` | done |
| T23 `sounds` | five generated CC0 WAVs (44 KB) on place / jump / land / fall / blocked via SoundPool, wired in `MainActivity` | needs your ears |
| T24 `voice-commands` | push-to-talk mic on Play: "jump" / "go" (en, ru, kk keywords) -> jump / one step; English is the demo language; `VoiceCommandsTest` 5/5 | needs your Pixel run (hand-review); see §4 |

Review: wave 1 reviewer said FIX FIRST on two gate-relevant findings, both fixed: (1) the offline flag used to lock
Photo / Scan forever because the guard only clears on a successful call — now no button is disabled by offline, a tap
is the retry and the NO_PLANE card names the network; (2) a FLY scenario replay from the end position skipped the cup
beat — the host now restarts at spawn and drives the script on simulated physics time (a tracking stall pauses the
script instead of bunching taps). The T21 host review (second reviewer) found Plan B drawing the camera after the
occluders (black cup shapes) and every rectangle counted as a cup; both fixed before you see the side-by-side.

## 2. Install and log
```
./gradlew :androidApp:installDebug && scripts/device.sh run
```
Second terminal: `scripts/device.sh log` (grep `RudeusGate`). Unlock the phone first — the QA agent cannot.
Table layout for the scenario (plane coords from the anchor, u = right, v = toward you): **cup at (0.15, 0)**,
**book (≤ 2 cm) at (0, 0.15)**, **a 10 cm gap at (-0.20, 0)** — on a real table "gap" = the table edge or a cliff you
scan; without one the JUMP variant just lands and walks on.

## 3. Gates
### Day 5 — scripted 30 s scenario runs 3x in a row without code changes
| Criterion | Status | Evidence |
|---|---|---|
| scenario runs 3x without code changes | UNTESTED on device; PASS on the JVM | `ScenarioTest.day5RunsThreeTimesIdentically` (3 identical traces), `day5JumpVariantHitsEveryBeat` (BLOCKED 1.6 s, STEPPED_UP 6.3 s, JUMPED 10.4 s, FELL over the cliff 11.5 s, RESPAWNED 12.2 s), `day5FlyVariantCrossesTheGap` (lands at u = -0.264) |
Pixel: Footprints on -> **Scenario** button x3 (pick a FLY bundled character for the gap; JUMP falls and respawns).
Expect per run: `scenario_start`, five `scenario_step idx=0..4 at_ms=0/4000/9000/10400/11400`, `scenario_done ms≈25000`.
Also: `occlusion=depth depth_supported=true` at start; character hidden behind the real cup; Jump button ->
squash-stretch, `jump_ignored mode=JUMPING` on a double tap, `jump_ignored ability=ROLL` for a roller.
Plan B side-by-side: set `FORCE_OCCLUDER_BOXES = true` in `Occluders.kt`, rebuild, same scene, screenshot both into
`docs/evidence/day5-occlusion-{depth,boxes}.png`; pick one (cut line 4 order: depth -> boxes -> none).

### Day 6 — fresh install -> character -> full scenario with Wi-Fi off halfway, no crash
| Criterion | Status | Evidence |
|---|---|---|
| Wi-Fi off halfway, no crash | UNTESTED on device; JVM: `OfflineGuardTest` 5/5 | offline badge + note appear after the first failed call; Photo / Scan stay tappable (retry); sounds and voice fail silently |
| metrics JSON with `source: "app"` | PASS (device, Oct 28) | `runs/2026-10-28T12-31-11Z.json` pulled from the Pixel today; `latestRun` / Export unchanged |
Pixel: uninstall, install, capture a character (Wi-Fi on), Scanning -> Scan -> Play, turn Wi-Fi off, play the
scenario, Rescan (expect "No internet" + offline badge, no crash; the mic is disabled), Metrics -> Export JSON.
Voice (Wi-Fi on, language EN): first mic press -> permission dialog; hold, say "jump", release -> JUMPED within ~3 s;
"go" -> one step; logcat shows `voice_ms=` / `text_len=` and never the transcript.

## 4. Decisions only you can make
1. **Occlusion**: depth (default on the Pixel) vs boxes, from the two screenshots.
2. **Voice (cut line 1)**: T24 ships behind `CaptureHost.voice`; if it misbehaves on the Pixel, cut it by returning
   `null` from `AndroidCaptureHost.voice` (one line) — nothing else depends on it.
3. **Sound levels** (0.8 / 0.6 in `Sounds.kt`) and whether the respawn chime is wanted.
4. **Merge** `day5/play` -> `main` after the gates; the T04 (Day 4) ruler table is still yours too (`ai/day4-human.md`).

## 5. Housekeeping only you can do
Worktrees merged today: `.claude/worktrees/T19 T20 T22 T23 T24` (+ T04/T17/T18 from Day 4, and the two stale
`agent-*` ones). Remove with `git worktree remove <path>` and `git branch -d task/T19-core-scenario task/T20-shared-jump-offline-hud task/T22-core-offline-guard task/T23-sounds task/T24-voice-commands`.
Still open from earlier days: T02/T03/T04/T13/T14 are `review` pending your Pixel pass; T11 waits for your decision on
which drawings may be committed.

## 6. Known limits (by design today)
- No fixture fallback offline for a live capture or scan (T22 decision): offline = bundled characters + the level
  you already scanned. The T06 "switch to FixtureApi(AssetFixtureSource)" line is superseded.
- The scenario script assumes the Day 5 table layout; on an arbitrary table it is just a timed tap sequence.
- Hand = stretch (SAM rigging) not attempted; Day 7 video and README not started.
