# T57 — duel-balance-2

status: review
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

**Problems first.** B1 holds on 31 of 32 cells: ROLL at equal stats on the small table at HARD takes 28.0 % (35 % is
the floor). B2 (9 of 32 out), B3 (38 of 192 out, 36 of them archetype cells) and B7 (77 of 128 out) fail; B4, B5, B6,
B8, B9 pass; B10 (tests) passes. 19 candidates, the time-box; the final defaults are candidate 17.

### The rules as implemented (`DuelParams`, all validated by name; off positions reproduce T48 bit for bit)
1. **The stomp and the ram share the dash cooldown** (`specialsShareDashCooldown`, default true; off: false).
   `DuelParams.sharesDashCooldown(ability)` (JUMP or ROLL with the flag) is the one place it is read:
   `DuelPhysics.canSpecial` refuses the stomp / ram while `dashCooldownMs > 0`; the special starts the dash cooldown
   (`cooldownMsFor`) as well as its own; the HUD's Special button is enabled by `canSpecial` and its ring shows
   `DuelPhysics.specialWaitLeft` (the longer of the two waits for a sharing special); the bot reads `canSpecial` and
   `sharesDashCooldown`. FLY's lift-off and HIDE's dodge never touch the dash.
2. **SPEED lengthens the dash**: every dash (plain, counter, ram) moves at `dashSpeedMps * (dashReachBase +
   dashReachPerSpeed * SPEED)` (`dashSpeedMpsFor`), its duration unchanged. Default 0.75 + 0.05 SPEED (SPEED 5 = 1, as
   before; SPEED 1 .. 10 = 0.80 .. 1.25 x); off: 1 + 0. Validated as a stat factor (> 0 at SPEED 1 and 10). SPEED still
   sets the walk speed.
3. **Height matters for hits**: a dash or a stomp hits only a victim with `-hitReachDownM <= victimFeet - attackerFeet
   <= hitReachUpM` (`DuelParams.inReach`), both feet read after step 3 of the sub-step the hit is judged in (the same
   instant: frame-rate independent). Defaults 0.028 m up (the brief's starting point 0.02, tuned; a 3 cm book stays out
   of reach of the table) and 0.04 m down; scaled with `profile.scale` like `fromAboveMinM`, not with the character's
   size. Off: `DuelParams.NO_REACH_LIMIT` (`Float.MAX_VALUE`: survives JSON, kept as is by `scaled`). A miss is nothing:
   no event, knockback, wobble or stun, no counter window for a HIDE whose dodge "let it through"; the dash carries on.
   `fromAbove` keeps its x 1.25 inside the reach; a clash needs each in the other's reach (else it is one plain hit); a
   fighter in the air above the edge is still out. Added (a gap in the brief): the fighter a missed dash presses into
   from out of reach **stands firm in separation** (the dasher goes round it, as round a dodging HIDE), so a dash from
   the table cannot shove a fighter across a book by separation.
4. **FLY's archetype** (`FighterStats.defaultFor(FLY)`): 3/7/3/7 -> **4/6/4/6** (after rules 1..3 the old line took
   2.8..44 % against the field; 4/6/4/6 is the most even line that keeps SPEED and AGILITY its high stats). It changes
   only characters without stats.

### Every default that changed (old -> new)
`DuelParams`: `specialsShareDashCooldown` (new) true; `dashReachBase` / `dashReachPerSpeed` (new) 0.75 / 0.05;
`hitReachUpM` / `hitReachDownM` (new) 0.028 / 0.04; `stompScale` 0.85 -> 1.5; `ramKnockScale` 1.1 -> 2.0 (`ramDashScale`
1.3 kept); `counterKnockScale` 2.0 -> 1.5; `dodgeMs` 900 -> 700; `liftMs` 1200 -> 600; `wobbleKnockScale` 0.7 -> 0.8
(hit n of a round knocks 1, 1.8, 2.6, 3.4, 4.2 x); `rollSpeedScale` 1.0 -> 1.1. `BotTuning`: `jumpMarginMs` (new) 16.
`BotSkill`: new `jumpChance` (EASY 0, NORMAL 0.5, HARD 1), `reflexes` (HARD), `closesInOnJumpers` (HARD),
`dodgesAnywhere` (default = `sidesteps`; HARD false); NORMAL `nearEdgeRadii` 20 -> 2, HARD `nearEdgeRadii` 8 -> 2.
`FighterStats.defaultFor(FLY)` 3/7/3/7 -> 4/6/4/6. Unchanged: knock speed, dash 250 ms at 0.30 m/s, the cooldowns, stun,
the stat factors, the 9 s special cooldown, the round rules.

### Acceptance (`duel matrix --n 200`, NORMAL v NORMAL and HARD v HARD, four arenas; docs/duel-balance.md)
| # | Threshold | Before (step 2) | Final | |
|---|---|---|---|---|
| B1 | 35..65 % | 7.7..85.2 % (27 of 32 out) | 28.0..60.8 % (1 out: small HARD ROLL 28.0 %) | FAIL, by 7 points on one cell |
| B2 | 30..70 % | 17.0..91.7 % (10 out) | 16.2..75.3 % (9 out: FLY 23.2..29.2 on empty / large, 75.3 small HARD; HIDE 71.8 / 72.8; ROLL 16.2 small HARD, 70.8 large HARD) | FAIL |
| B3 | 25..75 % | 0..100 % (110 out) | 5.5..94.5 % (38 out; at equal stats 2: small HARD ROLL v HIDE 23.5 / 76.5) | FAIL |
| B4 | <= 25 % (large <= 40 %) | 0..5.6 % | 0..0.2 % | PASS |
| B5 | 2..8 (large ..10) | 3.4..12.7 (19 out) | 2.8..8.2 (arenas 3.5..7.8) | PASS |
| B6 | 30..120 s (small 20..) | 25.4..64.7 s | 24.6..55.2 s | PASS |
| B7 | 40..65 % | 8.5..95.5 % (56 out) | 5.5..90.5 % (77 out: SPEED 5.5..73.0, AGILITY 25.0..90.5, POWER 13.5..65.5, GUARD 25.0..72.0) | FAIL |
| B8 | >= 65 % | 83.5..100 % | 95.5..100 % | PASS |
| B9 | identical | 64 000 replays identical | 64 000 replays at 16 ms identical; off switches: the T48 matrix (n 20, 240 cells) byte-identical `cells` / `acceptance`, and `theNewRulesSwitchedOffAreThePreviousPhysics` (24 bot matches + 16 fights vs 207acc6) | PASS |
| B10 | tests green | - | `scripts/verify.sh` OK in 63 s: core 701 (687 + 14), shared 42 (41 + 1), android 3; duel 116 (102 + 14) | PASS |

### Step 1 — the rules behind their off switches (6e19321)
The `DuelParams` numbers and helpers as above, off by default; `DuelPhysics` as above; the HUD ring =
`specialWaitLeft`. With the switches off the T48 matrix (`--n 20 --seed 2`, 240 cells) gave byte-identical `cells` and
`acceptance`; the goldens test caught a first draft that let a FLY on its lift's last sub-step be pushed in separation.

### Step 2 — the rules on with the starting numbers, the bots taught (bad5495; the "before" in docs/duel-balance.md)
B4, B6, B8, B9 PASS; B1 7.7..85.2 % (27 of 32 out); at equal stats FLY / HIDE 59..85 %, JUMP 16..33 %, ROLL 8..38 %. The
matrix gained the equal-stats group (`CellGroup.EQUAL`, `DuelMatrix.equalCells`, `--groups ability,equal,stat`) and
T57's B1..B9 (`Acceptance`, `DuelMatrix.acceptance`); 144.7 s.

### Step 3 — the candidate log (n 60 per cell, no replays, equal + archetype groups, about 13 s each unless said)
Measured with `duel matrix --groups equal,ability --check-n 0 --params <file> --tuning <file>` (files under
build/duel/cand, not committed); a candidate is "from the previous" unless it says where it starts.

| # | what changed (from the previous unless said) | B1 | B2 | B3 | B4 | B5 | B6 | B7 | note |
|---|---|---|---|---|---|---|---|---|---|
| 0 | step 2 (n 200) | 7.7..85.2 (27 out) | 17.0..91.7 (10) | 110 | PASS | 19 out | PASS | 56 out | FLY / HIDE 59..85 %, JUMP / ROLL 8..38 % at equal stats |
| 1 | stomp 1.5, ram 1.6 x long / 1.6 x knock, counter 1.5, dodge 700, lift 900 | 11.1..81.1 (23) | 20.0..82.2 (8) | 102 | PASS | 19 | PASS | - | numbers barely move it |
| 2 | stomp 2.0, ram knock 1.8; lift-off / dodge within 6 (NORMAL) / 4 (HARD) radii of an edge | 10.0..80.6 (16) | 27.2..83.9 (7) | 112 | PASS | 25 | 1 | - | a NORMAL JUMP stomped a FLY 90 times for 3 hits (40 matches) |
| 3 | bot: a stomp only when the other cannot get out of its way (code) | 10.0..82.8 (12) | 2.8..83.9 (10) | 82 | PASS | 22 | 1 | - | JUMP 31..66 % at equal; FLY's 3/7/3/7 drops to 3..44 % |
| 4 | ram 1.3 x long / 2.0 x knock; a HARD HIDE dodges near an edge only (`dodgesAnywhere`) | 17.2..78.3 (7) | 3.3..75.0 (8) | 60 | PASS | 25 | 1 | - | B5's misses all on cup-and-book |
| 5 | FLY archetype 4/6/4/6; wobble 0.8; ROLL walks 1.25 x; NORMAL near an edge 4 radii | 21.7..84.4 (9) | 14.4..90.6 (8) | 62 | PASS | 1 | PASS | - | ROLL 81..91 % on the large table (pace) |
| 6 | ROLL 1.1 x; lift 600; reach up 2.8 cm | 33.9..81.7 (3) | 19.4..85.6 (6) | 44 | PASS | PASS | PASS | - | left: FLY on the small table 70 / 82 % |
| 7 | lift 400 | 34.4..82.2 (3) | 19.4..86.1 (6) | 44 | PASS | PASS | PASS | - | the lift's length is not it: on the small table every spot is "near an edge" |
| 8 | lift-off / dodge within 2 radii of an edge (NORMAL, HARD) | 26.1..63.9 (1) | 16.7..75.6 (7) | 42 | PASS | PASS | PASS | 72 (5.0..90.0) | committed (8819ff0); n 200: B1 26.7..60.0 (1 out: small HARD ROLL), B2 15.8..75.3 (9), B3 42, B7 77 |
| 9 | walk 0.70 + 0.06 SPEED, dash reach 0.50 + 0.10 SPEED; ram 1.0 x long | 23.3..64.4 (1) | 14.4..75.0 (9) | 44 | PASS | 1 | PASS | 68 (3.3..91.7) | SPEED now bad everywhere (3..45 %): its reach rarely matters to a bot, its pace did on the large table; not kept |
| 10 | (from 8) bot: a HARD ROLL closes in for a ram as for a dash | 10.6..67.8 (2) | 6.7..79.4 (6) | 46 | PASS | 1 | PASS | - | small HARD ROLL 26 -> 11 %: worse, reverted |
| 11 | bot: a HARD ROLL also rams a fighter in its path that cannot get out | 26.1..63.9 (1) | 16.7..75.6 (7) | 42 | PASS | PASS | PASS | - | identical to 8: never fires (the ram goes along `facing`); reverted |
| 12 | + a ROLL turns to face before ramming | 12.2..64.4 (4) | 22.8..75.6 (5) | 48 | PASS | PASS | PASS | - | ROLL 12..39 %: turning costs the tempo; reverted |
| 13 | HARD `safeRamsOnly` false | 26.1..63.9 (1) | 16.7..75.6 (6) | 42 | PASS | PASS | PASS | - | no change on the small table; not kept |
| 14 | POWER / GUARD 0.70 + 0.06, AGILITY cooldown 1.25 - 0.05 | 26.1..63.9 (1) | 5.0..88.3 (10) | 42 | PASS | 1 | PASS | 84 (1.7..90.0) | worse; not kept |
| 15 | bot: a fighter whose special shares the dash sidesteps on foot | 24.4..63.9 (1) | 20.0..72.2 (7) | 40 | PASS | PASS | PASS | - | within noise; reverted |
| 16 | FLY archetype 5/6/4/5 (archetype group only) | - | 13.3..73.3 (8) | 30 (archetypes) | PASS | PASS | PASS | - | FLY small NORMAL 18.9 %: not better than 4/6/4/6; reverted |
| 17 | (from 8) stomp 2.0 -> 1.5 | 30.0..64.4 (1) | 18.3..75.6 (8) | 40 | PASS | PASS | PASS | - | JUMP v ROLL was up to 92 / 8; n 200: B1 28.0..60.8 (1), B2 9, B3 38: **the final defaults** (0d8042b) |
| 18 | stomp 1.2 | 28.3..63.9 (1) | 17.2..75.6 (10) | 38 | PASS | 1 | PASS | - | not better than 17 |
| 19 | (17) + `dashEndMarginRadii` 0.5 | 27.8..64.4 (1) | 17.8..75.6 (8) | 40 | PASS | PASS | PASS | - | no effect; time-box reached, stopped |

(n 60: about ±7 points of noise on one ability's share against the field; the B7 column only where the stat group was
played.)

### What the bots now do differently
- **Jump over a dash or a stomp** coming at them when a jump gets the feet above the reach before the hit can land and
  keeps them there until it has passed (16 ms margins): EASY never, NORMAL half the time when it thinks in time, HARD
  always, watching on every tick (it reacts between its thoughts). A ROLL cannot. A HIDE near an edge dodges first (the
  counter); a FLY jumps before it lifts off (its float costs nothing).
- **Never attack out of reach**: no dash, counter, ram or stomp at a fighter whose feet (predicted along its jump's arc
  at the first contact, half way and the dash's end; at a stomp's landing) are out of reach. When the spot behind the
  other is out of its reach (the other on a book), it pushes from the first spot round it that is in reach (and aligns
  its dash with that), else walks at it, up onto the book.
- **HARD closes in on jumpers**: it dashes at a fighter that could jump only from close enough that, reacting on the
  next tick, it could not clear the dash.
- **The shared cooldown**: with both ready and its situation there, the stomp / ram goes before a plain dash; a stomp
  only at a fighter that cannot get out of its way (no lift-off or dodge ready and no jump in time: in practice a ROLL,
  or a fighter stunned through the landing; a NORMAL JUMP had stomped a FLY 90 times for 3 hits).
- **Lift-off and dodge only within 2 radii of an edge** (NORMAL 20 -> 2, HARD 8 -> 2, a HARD HIDE no longer anywhere).
- Every reach is the bot's own (SPEED); the walk is planned at the ground it will come down on (a FLY floating over the
  cup sent walk targets inside it, caught by `botsNeverEmitNonFiniteOrOutsideTargets`).
- Observed: between bots almost no dash is jumped (they dash once aligned behind the other, from 1 to 2 cm, too close to
  clear); the jump answers long dashes and stomps, i.e. mostly a child's.

### How long
`duel matrix --n 200` (320 cells, 64 000 matches + 64 000 replays at 16 ms): 119.5 s on 4 threads (1070 matches/s).
The duel test package: 116 tests, 31..42 s summed (`everyMatchFinishes` 9 s, `acceptanceHoldsOnASample` 8.6 s,
`normalIsBetweenEasyAndHard` 3.9 s); `simIsFastEnough` 256 matches/s on one thread.

### Tests updated because they pinned pre-T57 behaviour or numbers (each with the old value in a comment)
- `DuelPhysicsTest`: `hitFromAPlatformIsStronger` (a victim on the book is hit from the table only with the reach
  unlimited; since T57 that is a miss, asserted), `flyKnockedPastTheEdgeIsOutEvenWhileAirborne` (b jumps at 128 ms, not
  0: a jump that early is now out of reach), `walkSpeedFollowsTheSpeedStat` (ROLL walks x `rollSpeedScale`, 1.1).
- `DuelSpecialsTest`: `stompKnocksEveryoneNearTheLandingAway` (0.2125 -> 0.375 m/s; the FLY lifts at 160 ms so the
  0.6 s lift covers the landing), `ramHitsHarderAndCanRollOffTheEdge` (0.275 -> 0.5 m/s; no dash right after the ram,
  the old behaviour asserted with the rule off; reaches by formula), `liftedFighterCannotBeHitAndTheDashCarriesOn` (the
  lift's end on the 16 ms frame grid; the walk-off part with a 1.2 s lift), `liftedFighterClimbsNoHigherThanAWalker`
  (a 1.2 s lift).
- `DuelWobbleTest.wobbleGrowsKnockbackAndIsCapped`: factors 1, 1.8, 2.6, 3.4, 4.2 (were 1 .. 3.8), by `wobbleFactor`.
- `DuelParamsTest.duelParamsScaleWithTheSurface`: the reaches scale as heights.
- `DuelMatchTest`: `theEarlierRingOutLosesAtAnyFrameRateAndInEitherSlot` (marks -0.0436 / 0.0438: SPEED 1's shorter dash
  put the ring-outs across a 33 ms frame boundary), `aMatchMidFightRoundTripsThroughJson` (red's dash after its stomp's
  dash cooldown, was at 1000 ms).
- `DuelRunnerTest.humanSideWithABotIsExactlyDuelMatch`: the sleeping mind is NORMAL (a HARD one now acts on reflex).
- `DuelBotTest`: `aDashComingAtItNearAnEdge` (HARD JUMP jumps, HARD ROLL dashes across; NORMAL FLY jumps or lifts; in
  mid-table nobody dodges and HARD jumps; the dodge with T48's numbers), `theRamAndTheStomp` (the dash ready, not
  cooled; the stomp at a ROLL or a stunned fighter, not at a jumper; no special with the dash recharging),
  `aRamNeverCrossesAHole` (the dash ready), `aSidestepNeverDashesAcrossAHole` and `aWalkTargetNeverLandsInAHoleAtTheRim`
  (a ROLL bot: a JUMP one jumps now).
- `DuelBalanceTest`: `matrixArithmeticOnHandMadeResults` (B1..B9 ids, group names in failures, the equal group, the large
  / small bounds), `acceptanceHoldsOnASample` (re-pointed at B1 12..88 %, B4 strict, B5 ..12 / 14).
- `FighterStatsTest`: FLY's archetype. `DuelHudModelTest`: `buttonsAreEnabledOnlyInFightAndOnlyWhenPhysicsWouldAct` (a
  JUMP's Special greys while its dash recharges), `cooldownFractionsFollowTheFighter` (the stomp's ring shows the dash's
  wait).
- New (14 core + 1 shared): the 12 of `DuelNewRulesTest` (every required name, the fuzz with every rule on included),
  `botsJumpOverDashesByLevel`, `botsDoNotDashAtAVictimOutOfReach`, `hudDisablesTheSpecialWhileTheSharedDashRecharges`.

### Deviations
1. The out-of-reach "stands firm" separation (rule 3 above): not in the brief's text; without it a missed dash from the
   table shoves a fighter across a book by separation, against "safe from below".
2. Rule 2 applies to every dash kind, the ram and the counter too (they are dashes).
3. `hitReachUpM` tuned to 0.028 (the brief: "starting point 0.02"), kept below a 3 cm book.
4. The HUD's Special ring shows `specialWaitLeft` (the longer of the special's and the dash's wait), beyond the enabling
   the brief asked for, so a greyed button never shows a ready ring.
5. `BotSkill.dodgesAnywhere` (default `sidesteps`) separates a HARD HIDE's dodge-anywhere from its sidestep.
6. The matrix's criteria are T57's B1..B9 (the A ids are gone from the code; T48's table stays in the doc); B3..B6 are
   judged on both pair groups and name the group.
7. `theNewRulesSwitchedOffAreThePreviousPhysics` compares fingerprints recorded on 207acc6 (results, every event and
   decision, the final state), with T48's numbers and archetypes written out in the test.
8. An airborne bot plans its walk at the ground under it (`planH`). With every rule off this changes nothing on the
   tuning arenas (the goldens hold); on a level with an obstacle lower than a stomp's arc, a pre-T57 stomping bot's walk
   targets could differ.

### Found in the earlier briefs' code while measuring
1. (T55) An airborne bot built its keep-out circles at its feet height, so a jumping bot could aim a walk inside a low
   obstacle; only stomping JUMP bots were airborne before T57 and the tuning arenas have no such obstacle. Fixed
   (deviation 8) because T57's floating FLY hit it over the cup.
2. (T55) A ram goes along `facing`, and a bot that walked round to the spot behind the other faces along its last step,
   so `ramReady` is rarely true for a well-placed ROLL. Turning first costs more than it gains (candidate 12); left.
3. (T45) On the lift's last sub-step a FLY is grounded and still immune, so it stands firm in separation; a first draft
   keyed on "dodging" alone changed that, and the goldens caught it.

### What a human must decide
1. **ROLL cannot jump** (the walking game's rule, kept by T45). Under rule 3 every other body dodges a long dash by
   jumping, a ROLL cannot: the one B1 failure (small table, HARD, 28 %) and most of ROLL's B3 cells. Keep it (ROLL is
   the heavy one), give ROLL a duel-only hop, or accept 28 % there.
2. **The stats are not worth the same (B7, 77 of 128 out).** SPEED buys walking pace, which decides the 120 cm table and
   nothing on the 30 cm one; its longer dash (rule 2) rarely matters to a bot, which dashes from 1 to 2 cm. AGILITY's
   worth depends on the level (NORMAL 58..91 %, HARD 25..60 %). Numbers did not fix it (candidates 9, 14); a rule might
   (the dash's speed counting in the hit, say), and that is the user's.
3. **FLY's float**: a FLY's plain jump (the walking game's 0.3 x gravity) stays out of reach for about 2 s. The bots
   rarely meet it, a child dashing from afar will; the user asked for "a floating FLY is out of reach of the ground" and
   should see it on the Pixel.
4. **The archetypes (B2)**: with B7 unsolved the lines carry it: FLY 4/6/4/6 23..39 % on three tables and 75 % on the
   small one at HARD; HIDE's 4/5/4/7 72 % on two cells; ROLL's 7/6/5/2 16 % (small HARD) and 71 % (large HARD). Only
   FLY's line was T57's to change.
5. **The stomp became a ROLL-buster**: a stomp the other can jump out of costs the dash too, so bots stomp only what
   cannot (a ROLL, a stunned fighter). A child will stomp anyone; it lands when the other does not jump.
6. **The HARD-over-NORMAL floor** holds with no margin on one cell (ROLL on the empty table, 30 of 50 = 60 %).
7. The "stands firm" behaviour of an out-of-reach fighter (deviation 1) is a rule detail the user may want otherwise.

- cut/deferred: nothing cut (FLY's archetype change was needed and made). Not done: B1's last cell, B2, B3, B7 (above).
