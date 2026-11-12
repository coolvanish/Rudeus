# T59 — duel-app-flow

status: review
created: 2026-11-12
phase: v2 wave 4 (ai/build-plan-v2.md, "The game"). With T58 it takes the duel half of the plan's T50 row
gate: G5 duel on the Pixel: three matches in a row on a real table, ring-outs at the real edge within 2 cm, no
  tracking reset. This brief is the `:shared` half; its Pixel check is the first time anyone plays the duel
cut-if-slips: step 5 (the Rescan button between rounds); never the flow picker -> VS -> fight -> result
depends: commit K of `ai/tasks/T58-duel-stage.md` (the contract; made after T54 merges, which also frees
  `TableEngineHost.kt` and `TableSlotTest.kt`) to build; T58 merged for anything to move on the table. T49 done
tier: rudeus-opus-high (UI wiring in CLAUDE.md's table: a state machine in the 965-line `App.kt` with serials, the
  back rule and the session end; more than a screen from DESIGN.md, no physics)

Line numbers are those of f3a8d88 (`shared/.../App.kt` 965 lines, `ArSlot.kt` 140, `ui/screens/PlayScreen.kt` 338);
T54 does not touch these three. Paths without a module are under `shared/src/commonMain/kotlin/kz/rudy/ar/`.

## Goal
On the Play screen a child presses Duel, picks an opponent and a difficulty, sees the VS card, and plays: taps walk
the fighter, Dash / Jump / Special are thumb buttons with their cooldowns, the HUD shows the score, the round and the
clock, a point banner follows every ring-out, and the result card offers Again or Done. Done (or Back) returns to
free play on the same table with the same anchor. T49's composables and `duelHud(...)` are used as they are; the
match itself runs in `core.session.TableEngine` (T58) and reaches this module as a `DuelReport` through the slot.
No file under `androidApp/` or `core/` changes.

## Decisions taken by the orchestrator (not open)
- The flow: an entry on the play screen -> `OpponentPicker` -> `VersusCard` -> the fight with `DuelHud`, fed by
  `duelHud(...)` from the state the engine reports through the slot -> `DuelResult` -> rematch or back to free play.
- The child's fighter is the active member; the opponent is a saved or bundled character, played by `DuelBot` at the
  picked difficulty; stats come from each character's sheet (`CharacterSheet.fighter`).
- Pure models are tested; Compose screens are not. No new dependency, no `expect/actual`, no ARCore or SceneView
  type; `:shared` keeps compiling for `iosSimulatorArm64`.

## Decisions taken here (not open; where DESIGN.md and T49 are silent these are this brief's choices)
1. **The duel is a state over Play, not a `Screen`.** `arOn` (`App.kt:564`) keeps the AR slot composed only for
   SCANNING, PLAY and Metrics-over-Play; a new screen would have to join that rule and the back rule. `duelUi:
   DuelUi?` (null = free play) lives beside `addOpen`, and the session-end effect clears it.
2. **Entry**: a `SecondaryButton` with `duel_title` in Play's bottom row, right after Scan (both layouts of
   `PlayScreen.kt:242-260`). Enabled only with a scanned boundary (`DuelFlow.canDuel(level)`: T58 refuses a level
   without one, so no floor duel yet), the anchor and tracking. No new copy for the disabled state.
3. **Picker**: `DuelFlow.opponents`: saved then bundled, each id once, without mine and without the saved card of my
   photo (`photoSavedId`, as the Add sheet does at `App.kt:867-870`). NORMAL is preselected (`ai/v2-human.md` §4.3:
   "the level a child meets first"). `DuelDifficulty` maps to the `BotLevel` of the same name.
4. **The opponent joins the cast when Play or Watch is pressed**, so the host builds its picture under the VS card
   and `syncCast` has placed it before the match starts. A full cast (`Cast.MAX` = 4) gives it the last seat that is
   not mine. The cast is not restored afterwards: the opponent stays on the table as a friend, and a member that
   lost its seat can be added again from `+`.
5. **VS card**: a Paper screen with `VersusCard`, for `DuelFlow.VERSUS_MS` (2500) or until a tap; then `duelSerial`
   rises, `duelSeed` is the app clock (`nowMs()`), and the step is FIGHT. Mine is drawn from
   `DuelFlow.card(member, sheet)`: a photo member has no shelf card until its save lands.
6. **Reports never recompose `App`.** `duelReport` is a `MutableState<DuelReport?>` that `App`'s body never reads;
   `DuelScreen` reads it. T58 reports about ten times a second; `App` is about 750 lines of composition.
7. **Buttons are commands for the active member**: `act(CharacterAction.Dash)`, `act(CharacterAction.Jump)`,
   `act(CharacterAction.Special)` (`App.kt:261`), not `jump()`, whose legacy counter is not the duel's.
8. **Result**: `DuelResult` when the reported phase is RESULT. Again = a new serial and seed, straight to the
   countdown, same opponent and difficulty. Done = `duelUi = null`. Back at any step leaves the duel for free play
   (never for Capture) with no question asked.
9. **A refused duel** (`DuelReport.Refused`, any reason) closes the flow and says `duel_no_room` in the bubble: the
   one new string. With decision 2 the only reason a child can meet is a table too small or too cluttered.
10. **Watch** is the same match with `mineBot` at the picked level; `DuelFlow.hud` then disables the three buttons.
11. **No bubble during a fight** (the duel HUD has none): `onCastEvent` returns before `HudSpeech.next` while the
    step is FIGHT. The host has already played the event's sound.
12. **Scans.** `ArSlot.autoScan` is false during a fight (the plan: never a scan mid-round). Between rounds
    (COUNTDOWN and POINT) a `rescan` button asks for one scan through the existing `scanRequest`; while it runs
    (`hud.scanning`) the setup's `hold` is true, so the countdown waits for the answer (T58 decision 9). No hold
    flag of its own: `hud.scanning` already ends on every answer (`App.kt:524`) and on the shell's timer (820).
13. **Not offered in a fight**: chips, `+`, emotes, voice, Bigger / Smaller, Footprints, Metrics. `PlayScreen` is
    not composed while the step is FIGHT; the Paused scrim is `DuelScreen`'s own `PausedOverlay`.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt, TableEngineHost.kt (`toTableSlot` only), App.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/components/CastUi.kt (`member`), ui/screens/PlayScreen.kt (the entry)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/duel/DuelFlow.kt, DuelScreen.kt (new)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt (one id), DESIGN.md (the "Duel" section and one copy row)
- shared/src/commonTest/kotlin/kz/rudy/ar/DuelFlowTest.kt (new), TableSlotTest.kt, CastUiTest.kt
  (`DuelHudModelTest.everyDuelStringHasThreeLanguages` already covers every `duel_` id by its prefix: not touched)
- ai/tasks/T59-duel-app-flow.md (the Result section)

Must not touch: `ui/duel/DuelHudModel.kt`, `DuelHud.kt`, `VersusCard.kt`, `OpponentPicker.kt`, `DuelResult.kt` (T49's,
as they are), `rememberTableEngine` (no new effect: the engine reads `TableSlot.duel` on every frame), `core/**`
(T58's), `androidApp/**`, every Gradle file.

## Contract
From commit K, in `kz.rudy.ar.core.session` (read T58's contract block; do not redefine):
`CharacterAction.Dash`, `CharacterAction.Special`; `DuelSetup(serial, mineId, foeId, foeLevel, seed, mineBot = null,
hold = false)`; `DuelReport.Running(serial, state, arena, rules, params)` / `DuelReport.Refused(serial, reason)`;
`Member(id, ability, emotes, stats = archetype)`; `TableSlot.duel`, `TableSlot.onDuel`.
```kotlin
// ArSlot.kt — appended, each default meaning "as today"
data class ArCharacter(/* ... */ val frames: PoseFrames = PoseFrames.EMPTY,
    val stats: FighterStats? = null)          // what a duel reads (`CharacterSheet.fighter`); null = the ability's archetype
data class ArSlot(/* ... */
    val duel: DuelSetup? = null,              // the duel wanted on the table; null = free play. One match per new serial
    val onDuel: (DuelReport) -> Unit = {},    // main thread, from the AR frame loop: a state write, nothing else
)
// TableEngineHost.kt — toTableSlot: Member(it.id, it.ability, it.emotes, it.stats ?: FighterStats.defaultFor(it.ability)),
//   duel = duel, onDuel = onDuel
// CastUi.member: stats = c.sheet.fighter.  App.useResult (360-367): stats = sheet.fighter

// ui/duel/DuelFlow.kt — pure, no Compose
enum class DuelStep { PICK, VERSUS, FIGHT }
/** The duel flow; `null` in App = free play. RESULT is FIGHT whose report says `MatchPhase.RESULT`. */
data class DuelUi(val step: DuelStep = DuelStep.PICK, val foeId: String? = null,
                  val difficulty: DuelDifficulty = DuelDifficulty.NORMAL, val watch: Boolean = false)
object DuelFlow {
    const val VERSUS_MS = 2500L
    fun botLevel(d: DuelDifficulty): BotLevel
    /** A duel needs a scanned edge: a level with a boundary. */
    fun canDuel(level: Level?): Boolean
    /** [saved] then [bundled], each id once, without [mineId] and [alsoMine]. */
    fun opponents(saved: List<BundledCharacter>, bundled: List<BundledCharacter>, mineId: String,
                  alsoMine: Set<String> = emptySet()): List<BundledCharacter>
    /** A cast member as a shelf card, for the VS card: its id, [sheet], sprite, emotes, frames. */
    fun card(member: ArCharacter, sheet: CharacterSheet): BundledCharacter
    /** [cast] with [foe] on it: unchanged (the same instance) when it is; appended when there is room; else in the
     *  place of the last member that is not [mineId]. */
    fun castFor(cast: List<ArCharacter>, mineId: String, foe: ArCharacter): List<ArCharacter>
    /** What the slot carries: null unless [ui] is at FIGHT with a foe and [mineId] is known. Watch = `mineBot` at
     *  the same level. */
    fun setup(ui: DuelUi?, serial: Int, mineId: String?, seed: Long, hold: Boolean = false): DuelSetup?
    /** `duelHud` of a running report with its own arena, rules and params; null for no report, a refusal, or a
     *  report [mineId] is not in. [watch]: Dash, Jump and Special are not enabled. */
    fun hud(report: DuelReport?, mineId: String, watch: Boolean, nameOf: (String) -> String): DuelHudModel?
}

// ui/duel/DuelScreen.kt — stateless over DuelUi and the report; composes T49's four composables
@Composable fun DuelScreen(ui: DuelUi, report: State<DuelReport?>, mine: BundledCharacter, mineId: String,
    opponents: List<BundledCharacter>, foe: BundledCharacter?, nameOf: (String) -> String,
    paused: Boolean, pausedHint: StringId, scanning: Boolean, scanProgress: Float,
    onPick: (String) -> Unit, onDifficulty: (DuelDifficulty) -> Unit, onStart: (watch: Boolean) -> Unit,
    onVersusDone: () -> Unit, onDash: () -> Unit, onJump: () -> Unit, onSpecial: () -> Unit,
    onRescan: (() -> Unit)?, onRematch: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier)
// PlayScreen: two parameters appended: onDuel: (() -> Unit)? = null (null = no button), duelEnabled: Boolean = false
```
`DuelScreen` by step. PICK: `OpponentPicker` (it draws its own Paper). VERSUS: a Paper screen, safe-drawing and
screen-edge padding, `VersusCard(mine, foe)` centred; `LaunchedEffect(Unit) { delay(VERSUS_MS); onVersusDone() }` and
a tap anywhere call `onVersusDone`. Both take every touch (the pattern of `DuelResult.kt`'s scrim), so nothing reaches
the AR view under them. FIGHT: `DuelFlow.hud(report.value, mineId, ui.watch, nameOf)`; null draws nothing; else
`DuelHud(model, onDash, onJump, onSpecial)`, whose empty areas let a tap through to the table; `ScanOverlay(scanning,
scanProgress)`; when `onRescan != null`, the phase is COUNTDOWN or POINT and no scan runs, a `SecondaryButton` with
`rescan` centred just above the HUD's button row, never over the banner; `PausedOverlay(paused, hint = pausedHint)`;
and `DuelResult(model, onRematch, onDone)` when the phase is RESULT.

`App.kt`, the whole of the wiring:
1. State beside `addOpen` (224): `duelUi: DuelUi?`, `duelSerial: Int` (never lowered, like `commandSerial`),
   `duelSeed: Long`, and `val duelReport = remember { mutableStateOf<DuelReport?>(null) }` (decision 6).
2. The slot (534-563): `val duel = remember(duelUi, duelSerial, duelSeed, active?.id, hud.scanning) {
   DuelFlow.setup(duelUi, duelSerial, active?.id, duelSeed, hold = hud.scanning) }` joins the slot's keys and
   fields; `autoScan = autoScan && duelUi?.step != DuelStep.FIGHT`; `onDuel` goes through `rememberUpdatedState`
   like its neighbours: a `Running` with `serial == duelSerial` is written to `duelReport`; a `Refused` with that
   serial closes the duel and says `duel_no_room` (`HudSpeech.say`); any other serial is dropped.
3. `closeDuel()`: `duelUi = null; duelReport.value = null`. Called by Done, by `back()` as its third early return
   (after `settingsOpen` and `addOpen`, 378-379), and by the session-end effect (568-586).
4. `Screen.PLAY` (807-875): the two effects stay first. `PlayScreen(...)` is composed unless the step is FIGHT, with
   `onDuel = { duelUi = DuelUi() }` and `duelEnabled = active != null && DuelFlow.canDuel(level) && scan.anchored &&
   !hud.trackingLost`. Then `duelUi?.let { DuelScreen(...) }` with mine = `DuelFlow.card(active, activeSheet())`,
   `opponents` of decision 3, foe = the picked card.
5. `onStart(watch)`: the picked card becomes `CastUi.member(card, lang)`; `cast = DuelFlow.castFor(cast, mine.id,
   it)`; `castSheets` gains its sheet and loses the sheets of ids no longer in the cast; step VERSUS with `watch`.
   `onVersusDone`: `duelSerial++`, `duelSeed = nowMs()`, step FIGHT (ignored unless the step is VERSUS: the timer and
   a tap can both fire). `onRematch`: `duelSerial++`, `duelSeed = nowMs()`, `duelReport.value = null`.
6. `onRescan` (step 5 of this brief): `{ hud = hud.copy(scanning = true, scanProgress = 0f); scanRequest++ }`; the
   arc effect at 812-825 and `onScanned` (507-525) already end it.
7. `onCastEvent` (477-483): decision 11's early return, after the `walkedOnce` line.

**What must stay exactly as it is**: free play for a slot without a duel (same slot fields, same keys plus one that
is null); one AR call site and `arOn`; `commandSerial` never lowered; the back rule for every screen; `go()`; T49's
composables and `duelHud`; kk first, then ru, en; colours, type and spacing from `ui/theme/Tokens.kt` only.

## Tests required
`DuelFlowTest`:
- `difficultyMapsToTheBotLevelOfTheSameName`
- `aDuelNeedsALevelWithABoundary` — null, a level without one, one with
- `opponentsAreSavedThenBundledOnceEachWithoutMine` — and without my photo's saved card
- `aCardOfAMemberCarriesItsIdSheetSpriteEmotesAndFrames`
- `theFoeJoinsTheCastWhenThereIsRoom`, `aFoeAlreadyOnTheTableChangesNothing` (the same list instance)
- `inAFullCastTheFoeTakesTheLastSeatThatIsNotMine` — also when mine is the last
- `aSetupExistsOnlyInFightWithAFoeAndAFighter`
- `aSetupCarriesSerialSeedLevelHoldAndWatch` — Watch gives mine the same bot level
- `hudIsNullWithoutARunningReportOfMine` — no report, a refusal, a match mine is not in
- `hudIsDuelHudWithTheReportsArenaRulesAndParams` — a report with `DuelRules(pointsToWin = 3)` and
  `DuelParams.DEFAULT.copy(dashCooldownMs = 5000)` gives three pips and that cooldown's fraction: no default was used
- `aWatchedHudDisablesTheThreeButtonsAndKeepsTheRest`
`CastUiTest`: `memberCarriesTheSheetsFighterStats` — a sheet with stats, and a v1.0 sheet (its archetype).
`TableSlotTest`: its one test also checks that `stats` reach `Member` (null = the archetype), that `duel` is the same
instance and `onDuel` is forwarded, and the defaults (null, a no-op).
Build reports for the tests with `DuelMatch.start` and `DuelMatch.step` on `DuelArenas.EMPTY`; nothing here needs T58.
Tests not required: `DuelScreen`, `PlayScreen`, `App`, layout, screenshots, anything on a device.

## Steps
Every Gradle call: `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew <tasks> --console=plain
-q`; the tasks of steps 2-5: `:shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64
:shared:compileTestKotlinIosSimulatorArm64 :androidApp:assembleDebug`.
1. `grep -n "class DuelSetup" core/src/commonMain/kotlin/kz/rudy/ar/core/session/DuelContract.kt`: without commit K
   stop and say so. Read `App.kt` whole, `ArSlot.kt`, `TableEngineHost.kt`, `ui/duel/*.kt`, `ui/screens/PlayScreen.kt`,
   `ui/components/CastUi.kt`, DESIGN.md ("Play", "Duel", the principles), T49's Result, T58's contract block and its
   decisions 2, 5, 8, 9, 10. Record the baseline counts.
2. **Commit 1** `shared: fighter stats and the duel setup reach the table engine (T59)`: the `ArSlot.kt` fields,
   `toTableSlot`, `CastUi.member`, `useResult`, `TableSlotTest`, `CastUiTest`.
3. **Commit 2** `shared: DuelFlow, the pure rules of the duel flow (T59)`: `DuelFlow.kt`, `DuelFlowTest`.
4. **Commit 3** `shared: the duel on the play screen (T59)`: `DuelScreen.kt` without the Rescan button, the entry in
   `PlayScreen.kt`, the wiring 1-5 and 7, `duel_no_room` ("Орын аз" / "Мало места" / "Not enough room"; the Kazakh
   is the user's to check) with its DESIGN.md copy row, and in DESIGN.md's "Duel" section the entry, the VS
   screen's 2.5 s and the "not wired" sentence gone.
5. **Commit 4** (the cut line) `shared: rescan between rounds (T59)`: wiring 6, the button, `hold`, one DESIGN line.
6. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`.
7. Fill the Result in its own commit, set `status: review`. The final message says "no host file changed" and
   "needs your Pixel run after T58 is merged" with the check below. Until T58 is merged the fight shows nothing (no
   report arrives): do not fake a report in `App`.

## Done when
- `scripts/verify.sh` exit 0; shared tests up by at least 13, core and android unchanged
- `git diff --stat <base>..HEAD` shows only files under "Owns"; nothing under `androidApp/`, `core/`, no Gradle
  file, none of T49's five files
- `grep -rnE "import (com\.google\.ar|io\.github\.sceneview|android\.)" shared/src/commonMain` prints nothing new
- **The Pixel check (human, T58 merged, about fifteen minutes): "the first duel".** A table at least 60 x 40 cm on
  the textured cloth, a cup and a book about 3 cm thick; saved or bundled characters with JUMP, FLY, ROLL, HIDE at
  hand; `scripts/device.sh log` in a second terminal.
  1. Play a JUMP character, place it. Before Scan the Duel button is grey; after Scan it lights. Footprints on:
     write down the largest gap between the loop and the real table edge, in cm (G5 wants 2 or less). Footprints off.
  2. Duel: the picker lists every character but yours. Pick a FLY one, Normal, Play: the VS card with both stat
     cards, gone after 2.5 s or a tap.
  3. Both stand on the table facing each other, yours on your side; a third character on the table is out of
     sight. 3, 2, 1, Go, and the clock counts down from 1:00.
  4. Tap the table: yours walks there. Tap past the edge: it walks to the edge, not off it. Dash: a short charge
     the way it faces, and the button refills from the left in about 2.5 s. Jump, then Stomp.
  5. Push the other over the edge, or be pushed: it falls at the edge, "Point for you" / "Point for them", a pip
     fills, both are back on their marks and the next countdown starts.
  6. Cover the lens 5 s in a round: Paused. Uncover: the clock shows what it showed, both stand where they stood,
     the score is kept, the round goes on.
  7. Between rounds move the cup and press Rescan while 3, 2, 1 shows: the countdown waits, then both stand clear
     of the cup's new place.
  8. Play to the end: "You won" or "Good try" with the score. Again: 0 : 0, round 1, no VS card. Done: free play,
     every character back in sight, a tap walks yours, no "tap to place".
  9. Duel again with Watch: the two play by themselves, your three buttons stay grey. System Back in the fight:
     free play at once.
  10. Rest a thumb on Dash and tap the table with the other hand at the same moment, five times: do both count?
  Three whole matches without placing the character again are G5's "three in a row".
  In the log: per match one `duel_start serial= mine= foe= level= seed= marks_ms=` (write `marks_ms` down), a
  `duel_round n=` per round, a `duel_point round= winner= reason=` per point, one `duel_over winner= at_ms=`; step 7
  a `duel_arena marks=`; step 8 a `duel_end`; one `anchor t_ms=0` in all, no `anchor_refused`, no `cast_add` after
  the cover. What a fault means: Duel stays grey after a scan = `onLevel` / `canDuel`; no HUD after the VS card =
  no report: a `duel_refused reason=` line names why, none at all is `toTableSlot` or the slot's keys; fighters
  that never leave their old places, or a missing character after Done = T58's stage switch; a button that does
  nothing = the command (`duel_command_ignored` names the id); a tap that does nothing = `duel_tap_past_edge` (the
  depth), else the phase; a camera hitch at Go = `marks_ms`; a HUD that stutters = the report rate (T58 decision 10).
- **The feel answers `ai/v2-human.md` §4.3 waits for**, from the same session; write each into the Result:

  | Question (§4.3) | Try | Write down |
  |---|---|---|
  | The jump over a dash (§3 G4, rule 3) | JUMP or HIDE against Normal: when it runs at you, press Jump. Ten tries | passed under (`duel_fight Missed(` with you as victim) / hit (`Hit(`); can a child time it: yes, hard, no |
  | FLY's 200 ms lift-off (c) | play a FLY: press Float as the other dashes, ten tries; Dodge with a HIDE if at hand | how many it saved; does 200 ms read as anything |
  | The stomp's reach, 9 cm (a) | play a JUMP, the other on the book: Stomp from the table, then Dash from the table; then you on the book | pushed off by the stomp (expected yes) and by the dash (expected no); a shockwave or unfair |
  | Match length (i, B6) | three matches on this table | the table in cm; each `duel_over .. at_ms=`; rounds with `reason=TIMEOUT`; too short, right, too long |
  | JUMP v FLY (b), ROLL without a jump (f) | one match each at Normal | winnable or not; what was missing |
  | Easy / Normal / Hard (h) | one match each | the scores; which a child should meet first |
  | Dash goes where the fighter last walked | twenty dashes | how many went where you meant; would "at the other fighter" be better |
  | A tap during 3, 2, 1 does nothing | watch your own taps | did you expect a walk at Go |
  | HUD | one hand on the phone | the fill jerky or fine; the banner in the way; a thumb reaches all three buttons |

## Result (filled on acceptance)
- commits, measured counts (2026-11-12, cloud container, base 847d116 = commit K): 75bfa47 (stats + setup reach the
  engine), 00dd5a5 (`DuelFlow`), 4baa847 (the duel on the play screen), 7efb4a7 (rescan between rounds, the cut line).
  `scripts/verify.sh` exit 0: core 814 (unchanged), shared 56 (was 43: +12 `DuelFlowTest`, +1
  `CastUiTest.memberCarriesTheSheetsFighterStats`; `TableSlotTest`'s one test extended), android 4 (unchanged), debug
  APK built; `:shared:compileKotlinIosSimulatorArm64` and `compileTestKotlinIosSimulatorArm64` green. `git diff --stat
  847d116..HEAD`: only files under "Owns" (nothing in `androidApp/`, `core/`, Gradle, or T49's five files). No host
  file changed: the host's `slot.copy(...)` compiles as it is. No new `com.google.ar` / `sceneview` / `android.` import.
- deviations from the letter of the brief:
  1. **The Duel entry is not in the bottom row beside Scan for JUMP / FLY / HIDE**: it stands right above Scan /
     Rescan at the bottom-left. Measured on paper (Nunito ExtraBold 16 sp, 20 dp side padding, 12 dp gaps): "Скан
     снова" with its glyph is about 160 dp, "Дуэль" about 90 dp, Jump at least 156 dp, three gaps 36 dp: about 440 dp
     against 380 dp inside a 412 dp phone's 16 dp margins. In one row the Row would squeeze Jump, free play's primary,
     to about 90 dp. Stacked, free play's bottom row is exactly what it was. For ROLL (the Scan primary and no Jump) the
     button is in line after Scan as the brief says. Not rendered here: the look is the user's to check.
  2. `hold = hud.scanning` came in commit 3 with the setup, not in commit 4. Reverting commit 4 only removes the button;
     the hold that stays is harmless (it only waits for a scan started before the duel).
  3. `onDuel` also drops a report unless the step is FIGHT (the brief only says "that serial"): a report that arrives
     after Done with the old serial would otherwise sit in `duelReport` and show the old RESULT card for a moment at
     the next duel. `onVersusDone` clears the report too, for the same reason.
  4. `DuelFlow.setup` also returns null when `mineId == foeId` (not reachable from the picker, which leaves mine out).
  5. The VS step's tap uses `detectTapGestures` on its full-screen Paper box (taking every touch the way
     `DuelResult`'s scrim does: the top sibling that is hit stops the hit test); the picker gets the scrim's
     `awaitPointerEvent` loop through its `modifier`. T49's files are untouched.
  6. Opponents leave out `photoSavedId` only when *mine* is the photo member ("the saved card of my photo"); the Add
     sheet leaves it out when any photo member is on the table.
  7. `PausedOverlay` is drawn under `DuelResult`, as the brief lists them, so Done works on a paused result card.
- what K does not give: nothing was missing. Note for T58: `TableSlotTest` builds an engine with a `DuelSetup` in its
  slot (mine "b", foe "a") but only calls `anchorPlaced`, no frame; if T58 starts a match there, that test needs a look.
- copy the implementer is least sure of: `duel_no_room` "Орын аз" (kk) is the user's to check; "Мало места" / "Not
  enough room".
- Pixel check ("the first duel"), G5's three matches, the gap at the edge, `marks_ms`: not run (no Pixel, and T58 is
  not merged: until it is, the fight shows nothing because no report arrives; App fakes none). The user's, after T58.
- the feel answers: the user's, from the same session (the table above).
- cut/deferred: nothing cut. Untested by design (CLAUDE.md): `DuelScreen`, the Play entry, App's wiring. A scan
  answer during a fight writes its "Table is empty" line into the bubble state; it shows only if free play returns
  before the bubble times out.
  During PICK and VERSUS Play stays composed under the Paper screens (its bubble may still change under them).
