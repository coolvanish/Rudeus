# T20 — shared-jump-offline-hud

status: done
created: 2026-10-31
day: Day 5 (jump / affordances) + Day 6 (offline badge, honest offline copy)
gate: Day 5 — scenario runs 3x (the HUD must offer Jump and a debug "Scenario" replay); Day 6 — Wi-Fi off halfway, no crash
cut-if-slips: cut line 5 (affordances -> jump only: then the button always says "Jump"); never cut the offline badge

## Goal
The child can make the character jump (or fly) from the Play HUD; a rolling character has no button and rolls fast
(physics already does that). The AR host learns the character's ability and receives jump requests through `ArSlot`,
the same way it receives scan requests. A debug "Scenario" button (footprints on) asks the host to replay the Day 5
script. When the app host reports it is offline, the Play HUD shows the Leaf "Offline" badge, Capture shows the
"No internet — ready characters" note with Photo disabled (that part already exists in `CaptureScreen`), and the Scan
button says why instead of pretending there is no table.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/CaptureHost.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/screens/PlayScreen.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/screens/HudSpeech.kt (only if a new event line is needed)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt (new ids only)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/components/Buttons.kt (only if a round action button is needed; prefer reuse)
- shared/src/commonTest/kotlin/kz/rudy/ar/HudSpeechTest.kt (only if HudSpeech changes)
- DESIGN.md — the Play section only (document the Jump button and the offline Scan copy)
- this brief

Do NOT touch `androidApp/**`, `core/**`, `ScanningScreen.kt`, `CaptureScreen.kt` (it already handles `offline`).

## Contract
### ArSlot (additive; existing fields unchanged)
```kotlin
/** The character's ability from its sheet: the host sets `CharacterState.ability` before the anchor and on change. */
val ability: Ability = Ability.JUMP,
/** Each increment = one `Physics.jump` on the host (ignored by physics while airborne / for ROLL). 0 = none yet. */
val jumpRequest: Int = 0,
/** Debug (footprints on): each increment replays `Scenarios.DAY5` from the character's current position. */
val scenarioRequest: Int = 0,
```
The slot stays `remember`ed on `(spritePng, surface, debug, scanRequest, jumpRequest, scenarioRequest, sheet.ability)`.
Like `scanRequest`, the counters reset to 0 when the AR session ends (`arOn` false).

### CaptureHost (additive, default so the demo shell compiles)
```kotlin
/** True while the host's last model call failed on the network (and until one succeeds). The HUD shows the badge. */
val offline: StateFlow<Boolean> get() = MutableStateFlow(false)   // kotlinx.coroutines.flow, already on the classpath
```
App collects it (`collectAsState()`): `HudState.offline`, `CaptureScreen(offline = …)`.

### PlayScreen
- New params: `onJump: () -> Unit`, `onScenario: (() -> Unit)? = null`.
- Jump button: shown for `JUMP`, `FLY` and `HIDE` (HIDE behaves as a jump in physics), hidden for `ROLL`. Label
  `play_jump` for JUMP/HIDE, `play_fly` for FLY, glyph = the ability's glyph. 64 dp tall, Paper fill with Sky outline
  and Sky text (a secondary action; Scan stays the one Sun button), to the RIGHT of Scan in the bottom row; Scan keeps
  `weight(1f)`. Hit area >= 56 dp. It is enabled while `!hud.trackingLost`.
- Debug "Scenario" ghost button (`play_scenario`) only when `hud.showFootprints && onScenario != null`, in the top-right
  column under Metrics.
- Offline: when `hud.offline`, the Scan/Rescan button is disabled and the tap hint row shows `ScrimPill(t(StringId.capture_offline_note))`
  instead of "Tap — it walks" (so the child sees why). Nothing else changes.

### App
- `onJump = { jumpRequest++ }`, `onScenario = { scenarioRequest++ }` (only passed when `hud.showFootprints`).
- Offline Scan from Play: with `offline` true the Scan button is disabled (above), so no request is sent. On the
  Scanning screen, when offline, `onScan` shows the speech line `capture_offline_note` and does not bump
  `scanRequest` (status stays IDLE). Skip / Play anyway keep working.
- `character = CharacterState(ability = sheet.ability)` stays (the HUD does not need the live mode).

### Strings (kk first, ru, en; short)
- `play_jump`: "Секір" / "Прыгай" / "Jump"
- `play_fly`: "Ұш" / "Лети" / "Fly"
- `play_scenario`: "Сценарий" / "Сценарий" / "Scenario"

## Tests required
- None new (Compose screens are not tested). Keep `HudSpeechTest` green if `HudSpeech` changes.
Tests not required: button layout, the badge.

## Steps
1. Read `App.kt`, `PlayScreen.kt`, `ArSlot.kt`, `CaptureHost.kt`, `Buttons.kt`, `Chips.kt`, `Strings.kt`, DESIGN.md
   "Play" and the "Buttons" rules (one Sun button per screen; tap targets >= 56 dp).
2. Implement; build with `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :shared:compileAndroidMain :shared:testAndroidHostTest --console=plain -q`
   from your worktree (`:androidApp:assembleDebug` will fail in your worktree until T21 wires the new slot fields —
   that is expected; the new fields have defaults so the host still compiles against them).
3. Update DESIGN.md Play section (Jump button, Scenario debug button, offline Scan copy). Fill the Result. No git.

## Done when
- `:shared:compileAndroidMain` and `:shared:testAndroidHostTest` green in the worktree.
- Every new string id has kk, ru, en. The slot's new fields default so `androidApp` compiles unchanged.

## Result (filled on acceptance)
- done: `ArSlot` + `ability` / `jumpRequest` / `scenarioRequest` (defaults; documented like `scanRequest`).
  `CaptureHost.offline` defaults to one shared never-offline `StateFlow` (stable to collect; kotlinx-coroutines-core
  is already `implementation` in :shared, no new dependency). `PlayScreen(onJump, onScenario = null)`: Jump / Fly
  button right of Scan (GhostButton with a new optional `accent` param = Sky, height 64 dp, enabled while
  `!trackingLost`, hidden for ROLL); debug Scenario ghost under Metrics; offline disables Scan and shows the
  `capture_offline_note` pill in the hint row. `App`: counters (reset with `scanRequest` when `arOn` goes false),
  `collectAsState` of `capture?.offline` into `HudState.offline` and `CaptureScreen(offline)`, slot remembered on
  `(spritePng, surface, debug, scanRequest, jumpRequest, scenarioRequest, sheet.ability)`, offline Scan on Scanning
  says `capture_offline_note` and stays IDLE. Strings `play_jump`, `play_fly`, `play_scenario` (kk/ru/en).
  DESIGN.md Play section updated. HudSpeech unchanged (uses `HudSpeech.say`).
- verified: `:shared:compileAndroidMain` + `:shared:testAndroidHostTest` green (HudSpeechTest 5, ProcessingEventsTest 5,
  SharedCommonTest 1, SharedLogicAndroidHostTest 1; 0 failures); `:androidApp:compileDebugKotlin` green unchanged.
- cut/deferred: the host side (T21) must read `ability`, `jumpRequest`, `scenarioRequest` and implement
  `CaptureHost.offline`. The DESIGN.md copy table (section e) does not yet list the three new ids (outside this
  brief's Play-section scope).
