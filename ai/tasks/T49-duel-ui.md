# T49 — duel-ui

status: review
created: 2026-11-11
phase: v2 wave 3 (ai/build-plan-v2.md, "The game")
gate: G5 — three matches in a row on the Pixel (this brief is the UI half; the wiring is T50)
cut-if-slips: the opponent picker (the opponent is the next character on the shelf); never the HUD
depends: T44 (`FighterStatsCard`), T45 (`DuelMatchState`, `MatchPhase`, `PointResult`, `DuelPhysics` helpers) — both merged

## Goal
Everything the duel shows that is not AR: picking an opponent and a difficulty, the VS card with both fighters' stats,
the HUD during a match (score, round, clock, the countdown and point banners, Dash / Jump / Special with their
cooldowns) and the result. Built as stateless composables over one pure model, so the Android host and later the iOS
host only feed it a `DuelMatchState`. Nothing is wired into the app here: no navigation, no `ArSlot`, no `App.kt`.

## Owns (exact paths)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/duel/DuelHudModel.kt (new: the pure model)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/duel/DuelHud.kt, VersusCard.kt, OpponentPicker.kt, DuelResult.kt (new)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt — new ids only
- shared/src/commonTest/kotlin/kz/rudy/ar/DuelHudModelTest.kt (new)
- DESIGN.md — the copy rows for the new ids and one section "Duel"

Do not touch `App.kt`, `ArSlot.kt`, `CaptureHost.kt`, `PlayScreen.kt`, `CaptureScreen.kt` (read `BundledCharacter`
from it, do not edit it), anything in `:core`, `androidApp/**`, Gradle. T53 is about to move types out of `ArSlot.kt`;
the wiring of these screens is T50.

## Contract
```kotlin
package kz.rudy.ar.ui.duel

/** The UI's own difficulty (T55's bot levels are mapped to it in T50). */
enum class DuelDifficulty { EASY, NORMAL, HARD }

data class SideModel(val id: String, val name: String, val score: Int, val immune: Boolean, val out: Boolean)

sealed interface DuelBanner {
    data class Count(val n: Int) : DuelBanner                 // COUNTDOWN: 3, 2, 1
    data object Go : DuelBanner                               // the first GO_MS of FIGHT
    data class Point(val mine: Boolean?) : DuelBanner         // POINT: null = nobody scored (replay)
    data class Result(val won: Boolean?) : DuelBanner         // RESULT: null = a draw
}

data class ActionButton(val label: StringId, val enabled: Boolean, val cooldown: Float)   // cooldown 0..1, 0 = ready

data class DuelHudModel(
    val phase: MatchPhase, val round: Int, val pointsToWin: Int,
    val me: SideModel, val foe: SideModel,
    val clockSeconds: Int?,                                   // FIGHT only: the round's seconds left, rounded up
    val banner: DuelBanner?,
    val dash: ActionButton, val jump: ActionButton, val special: ActionButton?,   // special null = no ability
)

const val GO_MS = 800L

/** Pure: everything the HUD draws for the player [myId], from the state alone (no events, no clock). */
fun duelHud(state: DuelMatchState, myId: String, arena: Level, nameOf: (String) -> String,
            rules: DuelRules = DuelRules(), params: DuelParams = DuelParams.DEFAULT): DuelHudModel

@Composable fun DuelHud(model: DuelHudModel, onDash: () -> Unit, onJump: () -> Unit, onSpecial: () -> Unit, modifier: Modifier = Modifier)
@Composable fun VersusCard(mine: BundledCharacter, foe: BundledCharacter, modifier: Modifier = Modifier)
@Composable fun OpponentPicker(characters: List<BundledCharacter>, mineId: String, selectedId: String?, difficulty: DuelDifficulty,
                               onPick: (String) -> Unit, onDifficulty: (DuelDifficulty) -> Unit, onStart: () -> Unit, onWatch: () -> Unit,
                               modifier: Modifier = Modifier)
@Composable fun DuelResult(model: DuelHudModel, onRematch: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier)
```
- `duelHud` rules: buttons are enabled only in FIGHT and only when the physics would take the input
  (`DuelPhysics.canDash` / `canSpecial`, and for Jump the fighter accepts input and its ability can jump: ROLL cannot);
  cooldown fractions from `DuelPhysics.dashCooldownLeft` / `specialCooldownLeft`; `immune` from `DuelPhysics.isImmune`,
  `out` from `DuelPhysics.isOut`; `Count(n)` = the countdown's seconds left rounded up, clamped to 1..3; `Go` while
  FIGHT has run less than `GO_MS`; `Point` from `state.lastPoint`; `Result` from `state.winner`. Always pass `params`
  to the helpers (their defaults are the table's and wrong on the floor). An unknown `myId` is a programming error:
  `require`.
- The special's label follows the fighter's ability: `special_stomp` (JUMP), `special_lift` (FLY), `special_ram`
  (ROLL), `special_dodge` (HIDE).
- `VersusCard`: the two sprites facing each other, names, ability tags, and two `FighterStatsCard(compact = true)`.
- `OpponentPicker`: the shelf's cards minus the player's own character, three difficulty chips, a primary "Fight"
  button (enabled with a selection) and a secondary "Watch" (bot against bot).
- `DuelResult`: who won (or a draw), the score, "Again" and "Done".
- HUD layout: score and round pips on top with the clock between the names; the banner large in the middle and
  never over the buttons; Dash, Jump, Special along the bottom for the thumbs, the cooldown as a ring or a fill
  on the button. A child plays with one hand holding the phone: targets at least 56 dp.
- Strings (kk first, then ru, en; short, for a child), at least: `duel_title`, `duel_pick_opponent`, `duel_fight`,
  `duel_watch`, `duel_vs`, `duel_round`, `duel_go`, `duel_point_mine`, `duel_point_foe`, `duel_replay`, `duel_won`,
  `duel_lost`, `duel_draw`, `duel_again`, `duel_done`, `duel_dash`, `special_stomp`, `special_lift`, `special_ram`,
  `special_dodge`, `difficulty_easy`, `difficulty_normal`, `difficulty_hard`. Reuse existing ids where one already says
  it (Jump). No fighting vocabulary that reads as hurting: the fighters push, nobody is beaten up.
- Colours, type, radii, spacing only from `ui/theme/Tokens.kt`; components from `ui/components` (buttons, chips,
  cards, `SpriteImage`); follow DESIGN.md.
- commonMain only, no JVM-only API: `:shared:compileKotlinIosSimulatorArm64` must stay green.

## Tests required
- `countdownShowsThreeTwoOne`
- `goShowsForTheFirstMomentOfTheFight`
- `clockIsTheRoundsSecondsLeftRoundedUpAndOnlyInFight`
- `buttonsAreEnabledOnlyInFightAndOnlyWhenPhysicsWouldAct` — on cooldown, stunned, out, ROLL's Jump
- `cooldownFractionsFollowTheFighter`
- `specialLabelFollowsTheAbilityAndIsAbsentWithoutOne`
- `pointBannerSaysWhoScoredOrReplay`
- `resultBannerSaysWonLostOrDraw`
- `theModelIsSeenFromMySide` — the same state for the other id swaps `me` and `foe`
- `everyDuelStringHasThreeLanguages`
Tests not required: the composables, layout, screenshots.

## Done when
- `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh` exit 0
- No file outside "Owns" changed

## Result (filled on acceptance)
- measured (2026-11-11, cloud container): `scripts/verify.sh` exit 0: core 650, shared 40 (30 + 10 new `DuelHudModelTest`),
  android 3, debug APK built; `:shared:compileKotlinIosSimulatorArm64` green. Nothing outside "Owns" changed.
- built: `ui/duel/DuelHudModel.kt` (pure `duelHud`, `specialLabel`, `GO_MS`), `DuelHud.kt`, `VersusCard.kt`,
  `OpponentPicker.kt`, `DuelResult.kt`; 23 new `StringId`s (kk/ru/en) and their rows + a "Duel" section in DESIGN.md.
- decisions: Jump is also disabled in the air (`Physics.jump` ignores it there, like ROLL); the Jump label is always
  `play_jump` (FLY's lift is the Special, "Float"); "Fight" is worded "Play" (no word that reads as hurting); Watch needs a
  pick like Play; the banner sits near the top of the band between the top row and the buttons, not the exact middle
  (DESIGN principle 1: the middle is where the fighters stand); Dash glyph is the right chevron, Stomp's the down chevron
  (no new glyphs: `Glyph.kt` is not in Owns).
- cut/deferred: how it looks and plays on the Pixel is the user's check, after T50 wires it. No composable was rendered or
  screenshotted here. Kazakh copy least certain: `special_stomp` "Дүрс ет", `special_ram` "Зымыра", `duel_dash` "Ұмтыл",
  `duel_go` "Кеттік", `duel_title` "Сайыс".
