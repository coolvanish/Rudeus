# T57 — duel-balance-2

status: todo
created: 2026-11-12
phase: v2 wave 4 (ai/build-plan-v2.md, "The game")
gate: G4 — no ability wins more than 65 % against the field at equal budget
cut-if-slips: the FLY archetype change (keep 3/7/3/7 and report); never the three rule changes
depends: T48 merged (`cli duel matrix`, `docs/duel-balance.md`, wobble, swept knockback)
read first: the Results of T42, T45, T55 and T48 (the candidate log and "What a human must decide" in T48)

## Decisions this brief implements (the user's, 2026-11-12)
Wobble stays. Four rule changes are allowed, because numbers alone did not balance the abilities (T48: ROLL takes
67–88 % of the field on three arenas, FLY 7–26 %; SPEED barely matters; the stomp and the ram had to be weakened to
0.85x and 1.1x because they do not spend the dash cooldown):
1. **The stomp and the ram share the dash cooldown.** `Special` for JUMP and ROLL needs the dash ready as well as the
   special, and starts both cooldowns. (FLY's lift-off and HIDE's dodge are defensive and stay as they are.) With that,
   their strength (`stompScale`, `ramDashScale`, `ramKnockScale`) may go back up.
2. **SPEED lengthens the dash.** The dash's speed (so its reach, the duration stays) is multiplied by
   `dashReachBase + dashReachPerSpeed * SPEED`: a fast fighter strikes from farther away. SPEED keeps setting walk speed.
3. **Height matters for hits.** A dash or a stomp hits only a victim whose feet are within `hitReachUpM` above the
   attacker's feet (starting point: 0.02 m at table scale, scaled like other distances) and within `hitReachDownM`
   below them (starting point: the platform height, 0.04 m): a jump over a dash is a dodge, a floating FLY is out of
   reach of the ground, and a fighter standing clearly higher is safe from below until the attacker climbs. A miss does
   not use the dash up: it carries on, as it does past a lifted FLY. `fromAbove` keeps its bonus inside the reach.
   Wobble is added only for hits that land. A fighter in the air above the edge is still out (T42's rule is untouched).
4. **FLY's archetype may change** (`FighterStats.defaultFor(FLY)`, today 3/7/3/7), only if FLY is still outside the
   band at its archetype after 1–3, and only to another balanced line (each stat 1..10, sum 20) that still reads as
   "fast and nimble". It affects only characters whose sheet has no stats (every v1.0 character).
The bots must play the new rules or the measurement means nothing: a body that can jump (not ROLL) jumps over an
incoming dash when that is the better answer (NORMAL sometimes, HARD reliably, EASY never), a bot does not dash at a
victim that is out of reach in height, and a JUMP or ROLL bot does not waste the shared cooldown.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/*.kt (all of the package)
- core/src/commonTest/kotlin/kz/rudy/ar/core/duel/*.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/Fighter.kt — only `defaultFor(Ability.FLY)` and its KDoc line;
  core/src/commonTest/kotlin/kz/rudy/ar/core/domain/FighterStatsTest.kt and shared/src/commonTest/kotlin/kz/rudy/ar/FighterStatsUiTest.kt
  only where they pin that line
- cli/src/main/kotlin/kz/rudy/ar/cli/DuelCommand.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/duel/DuelHudModel.kt and shared/src/commonTest/kotlin/kz/rudy/ar/DuelHudModelTest.kt —
  only if a button's enabling changes (the Special of JUMP and ROLL now also waits for the dash)
- docs/duel-balance.md

Do not touch `physics/*`, `session/*`, the rest of `domain/*`, `meta/*`, `androidApp/**`, Gradle.

## Acceptance (by `duel matrix`, fixed seeds, n >= 200 per cell; NORMAL vs NORMAL and HARD vs HARD; four arenas)
| # | Criterion | Threshold |
|---|---|---|
| B1 | **Equal stats (5/5/5/5): no ability dominates.** Win share of each ability against the other three, per arena. This is the row that matters for new drawings, whose stats come from the picture, not the ability | 35 %..65 % |
| B2 | Archetype stats: the same, for characters without stats | 30 %..70 % |
| B3 | No matchup is hopeless, at equal stats and at archetypes | every cell 25 %..75 % |
| B4 | Rounds end on the table: share ended by the clock, per pair | <= 25 % (LARGE <= 40 %) |
| B5 | Hits per ring-out, per pair | 2..8 (LARGE 2..10) |
| B6 | Match length, per pair | 30..120 s (SMALL 20..120 s) |
| B7 | Every specialist viable: one stat at 8, the others at 4, against 5/5/5/5, same ability, per stat | 40 %..65 % |
| B8 | Stats do something: 8/8/8/8 against 5/5/5/5, same ability | >= 65 % |
| B9 | The same table at 16 ms and 33 ms steps; `wobblePerHit = 0` plus the pre-T57 numbers and the new rule switches off reproduce the pre-T57 physics bit for bit | identical |
| B10 | All `core/duel` tests green; every updated test listed with why | — |
The matrix gains the equal-stats group (B1) next to the archetype group, and prints both.

## Contract details
- Every new rule has its numbers in `DuelParams` with validation, and an off position that is exactly today's
  behaviour: `specialsShareDashCooldown: Boolean`, `dashReachPerSpeed = 0` with `dashReachBase = 1`,
  `hitReachUpM` / `hitReachDownM` = +infinity (or a documented "no limit" value that survives JSON). B9's second half
  is checked the way T48's review did it: the same seeded matches before and after, equal results and final states.
- The height test uses the feet heights at the sub-step the hit is judged in (the same instant for both fighters),
  so the rule is frame-rate independent like everything else.
- `DuelPhysics.canSpecial`, the HUD model's button enabling and the bot all read the shared cooldown from one place.
- The invariants of T42 / T45 / T48 hold with every rule on (the fuzz presses Jump and Special at random for every
  ability pair): nobody inside or across a blocking footprint, every boundary exit has exactly one ring-out, grounded
  fighters never overlap, no NaN, every match ends.
- Bot strength ordering survives: `hardBeatsEasy`, `normalIsBetweenEasyAndHard` with its per-ability floor.

## Tests required
- `aJumpOverADashIsADodgeAndTheDashCarriesOn` / `aLateJumpIsStillHit`
- `aFighterClearlyHigherIsOutOfReachFromBelow` / `anAttackerOnTheSameBookHits` / `fromAboveStillHitsDownWithinReach`
- `theStompMissesAFighterInTheAir`
- `aMissAddsNoWobble`
- `stompAndRamNeedTheDashAndSpendIt` / `liftAndDodgeDoNotTouchTheDashCooldown`
- `speedLengthensTheDashNotItsDuration` — monotonic over SPEED 1..10
- `theNewRulesSwitchedOffAreThePreviousPhysics` — the B9 check on a sample, as a test
- `botsJumpOverDashesByLevel` — EASY never, NORMAL sometimes, HARD when it is the better answer; a ROLL bot never tries
- `botsDoNotDashAtAVictimOutOfReach`
- `hudDisablesTheSpecialWhileTheSharedDashRecharges` (if the HUD model changes)
- the fuzz with every rule on; `acceptanceHoldsOnASample` re-pointed at B1 and B4
Keep the duel test package under about 90 s; say how long it takes.

## Method
1. Implement the three rules behind their off switches, with their tests, and commit: the table must be unchanged
   with the switches off (B9).
2. Turn them on with the starting numbers, teach the bots, commit the matrix as the new "before".
3. Tune numbers (and, if needed and allowed, FLY's archetype). Keep the candidate log in the Result as you go and
   commit every candidate that improves the table, with the table. Time-box: about twenty candidates. If a row still
   fails then, stop, keep the best candidate that breaks nothing (B9, B10 always hold), and report which rows fail
   and by how much.
Anything else that would change a rule (what the clock awards, marks, the dodge and counter, wobble's shape) is not
yours: measure, describe under "What a human must decide", leave the code alone.

## Done when
- `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh` exit 0
- `./gradlew :cli:run --args="duel matrix --n 200 --out build/duel" -q` prints the table; `docs/duel-balance.md` holds
  the T48 table, the T57 table and the verdicts
- No file outside "Owns" changed

## Result (filled on acceptance)
(in progress; kept current after every step and candidate so an interruption loses nothing)

### Step 1 — the three rules behind their off switches (done)
- `DuelParams`: `specialsShareDashCooldown` (off: false), `dashReachBase` / `dashReachPerSpeed` (off: 1 / 0),
  `hitReachUpM` / `hitReachDownM` (off: `DuelParams.NO_REACH_LIMIT` = `Float.MAX_VALUE`, survives JSON, kept by `scaled`).
  All validated by name. Helpers: `sharesDashCooldown(ability)` (the one place the shared cooldown is read),
  `dashSpeedMpsFor` / `dashReachMFor` / `ramReachMFor`, `inReach(attackerH, victimH)`, `reachLimited`.
- `DuelPhysics`: `canSpecial` refuses a stomp / ram while the dash recharges; `startSpecial` starts the dash cooldown for
  them; every dash kind moves at the fighter's `dashSpeedMpsFor`; `hits` judges dash and stomp contacts only within the
  height reach (feet after step 3, both at the same instant), a miss is nothing (no event, wobble, stun, counter window;
  the dash carries on); a fighter the other's dash pressed into from out of reach stands firm in separation (as a dodge
  does) so a missed dash from the table does not shove a fighter across a book. New `specialWaitLeft` (the HUD ring).
- B9 with the switches off: the T48 matrix (`--n 20 --seed 2`, 240 cells, `--check-n 0`) gives byte-identical `cells`
  and `acceptance` in `matrix.json` before (207acc6) and after; `theNewRulesSwitchedOffAreThePreviousPhysics` replays
  24 seeded bot matches and 16 random fights (pressing Jump on every incoming dash) against fingerprints recorded on
  207acc6 (it caught a first draft that let a FLY on its lift's last sub-step be pushed in separation).
- Tests: 12 new in `DuelNewRulesTest`; `scripts/verify.sh` OK (core 699, shared 41, android 3).
