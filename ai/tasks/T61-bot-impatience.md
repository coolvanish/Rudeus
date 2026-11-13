# T61 — bot-impatience

status: review
created: 2026-11-13
phase: v2 wave 4 (ai/build-plan-v2.md, "The game")
gate: G5 duel on the Pixel (a real table is a random table: the bot must not stand off on it)
cut-if-slips: the random-table row R2 (keep R1 on the tuning arenas); never "no existing threshold changes"
depends: T60 merged (7b5a0a8): its root cause, `DuelIdleTest`, the idle table
tier: rudeus-opus-extra (the same agent that did T60: the bot's decision code and its measurements)

## Why
T60 fixed one way a NORMAL or HARD bot stands off (the detour dithering in a closed gap) and its reviewer measured
what is left: on 60 random 60 x 40 cm tables with 1–3 cups or books, against a fighter that sends no input, the bots
fail to win 113 of 720 matches with T60's switch off and 78 of 720 with it on (tables where a mark or the spot to
push from lies inside a cup's keep-out circle: 0 attacks, 59 s stand-offs). T60's switch cannot be turned on
without changing a test threshold, which is the user's decision and not taken. EASY never stands off: it dashes at
any alignment (`alignCos = -1`) and wins 42 of 42.

## Goal
A rule that ends every stand-off without knowing its cause: **a NORMAL or HARD bot that has not attacked for
`impatientAfterMs` of FIGHT while the opponent is reachable attacks from where it stands** (it walks at the opponent
and dashes at any alignment, as EASY does, until an attack lands or the opponent has moved away), then goes back to
its normal play. In matches between bots the rule must almost never fire (T60 measured the longest stretch without
an attack in the matrix at 8.0–8.6 s), so that the balance matrix and the level-order tests do not move and **no
existing test threshold changes**. It is on by default; `wholeDetour` stays off.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/DuelBot.kt, BotTuning.kt
- core/src/commonTest/kotlin/kz/rudy/ar/core/duel/DuelIdleTest.kt (new tests; its T60 tests stay as they are),
  DuelNewRulesTest.kt (one line: the new field's off value in `PRE_T57_TUNING`)
- docs/duel-balance.md (a T61 section), ai/tasks/T61-bot-impatience.md (the Result)

Do not touch `DuelBotTest.kt`, `DuelBalanceTest.kt`, `DuelPhysics.kt`, `DuelParams.kt`, `DuelRules.kt`,
`DuelMatch.kt`, `DuelRunner.kt`, `DuelState.kt`, `DuelArenas.kt`, `core/session/**`, `shared/**`, `androidApp/**`,
Gradle. No threshold of any existing test changes; if one goes red, stop and report.

## Contract
- `BotSkill.impatientAfterMs: Long` (0 = never; EASY 0, since it already dashes at any alignment; NORMAL and HARD
  10 000 unless the measurements below say otherwise; `PRE_T57_TUNING` sets 0 explicitly). With 0 every decision is
  today's, bit for bit, and nothing is drawn from the bot's PRNG; the goldens stay untouched and green.
- The bot's own clock for the rule runs on FIGHT time it has seen in its decisions (tick grid, simulated time; a
  tracking loss or another phase does not advance it), restarts when the bot attacks (a dash, a ram, a stomp
  started) and at every round start. "Reachable": what the bot can already tell (a walk line or a route exists; the
  opponent is within a dash's height reach, or the bot can stomp). An opponent on a platform out of a dash's reach is
  not attacked by impatient dashes that can only miss: the bot keeps its normal play there (report what happens).
- While impatient the bot still refuses what it refuses today for its own safety (no dash that carries itself over
  an edge or into a hole: `safeRamsOnly`, the dash-end margin), and still uses no knowledge a human lacks.
- Determinism: decisions on the tick grid, independent of the frame length; the matrix's 16 ms replay identical.

## Acceptance
| # | Criterion | Threshold |
|---|---|---|
| R1 | T60's I1 sample (a fighter that sends no input; four tuning arenas, NORMAL and HARD, both marks, 3 seeds) with the defaults (`wholeDetour` off, impatience on) | the bot wins every match; rounds decided by ring-outs, not the clock |
| R2 | 60 seeded random 60 x 40 cm tables with 1–3 cups (radius 3–5 cm) or books (8–14 x 6–10 cm, 3 cm) placed at random clear of the marks' search, NORMAL and HARD, bot abilities JUMP, ROLL, FLY against an idle JUMP, both marks: 720 matches (the reviewer's probe; build the generator in the test, seeded, and print the "before" with impatience 0: expect about 113 not won) | not won <= 1 % (7 of 720); every table that still fails listed with its layout and the reason |
| R3 | The three spots of T60's Result on cup-and-book (behind the cup, on the book, walking back to the middle), 8 matches each, NORMAL and HARD | reported before / after; no spot gets worse |
| R4 | `duel matrix --n 200` with the defaults against T57's table (the shipped state) | every cell identical, or the cells that move listed with how often the rule fired in them; no row's verdict changes; B9 identical |
| R5 | Every existing test green with its thresholds untouched (`hardBeatsEasy`, `normalIsBetweenEasyAndHard`, `botsUseTheirSpecialsInWholeMatches`, `acceptanceHoldsOnASample`, the goldens, T60's five) | — |
| R6 | How often the rule fires between bots: share of bot-v-bot matrix matches in which it fires at least once, per level | reported; if above 2 %, raise `impatientAfterMs` (try 12 000, 15 000) before anything else |

## Method (time-box: four candidates)
1. The generator and the "before" numbers (R1..R3 with impatience 0) as tests / a printed table; commit.
2. The rule behind `impatientAfterMs`, tests `anImpatientBotAttacksFromWhereItStands`,
   `impatienceRestartsWithEveryAttackAndEveryRound`, `impatienceOffIsTodaysBot` (goldens' sample),
   `aBotBeatsAnIdleFighterOnRandomTables` (R2 on a smaller seeded sample that runs in a few seconds, the full 720 as
   a printed measurement), `impatienceDoesNotCarryTheBotOverAnEdge`; commit.
3. R4 / R6 with the matrix; adjust the one number if R6 says so; commit each candidate that is better.
4. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`; the duel test package under
   about 110 s (say the time); Result filled; `status: review`.

## Done when
- `scripts/verify.sh` exit 0; R1..R6 in the Result and in `docs/duel-balance.md`; no file outside "Owns" changed

## Result (filled on acceptance)
**Problems first.** (1) **R2 is not met: 10 of 720 not won (1.4 %), bar 7** (80 before). 9 of the 10 are an impatient bot
dashing the fighter into a cup right behind it on its line, again and again (wall slams, no ring-out); the tenth knocks
it onto a book's edge, out of a dash's reach. (2) **A T60 test was corrected, not loosened** (the orchestrator's
decision, for the user to judge; commit 9861ec3): R1 and R5 contradicted each other as the brief stood, because T60's
`theFixSwitchedOffIsTheOldBot` took "the default tuning with `wholeDetour` off" as the bot before T60 and checked that
this bot loses to a fighter standing on its cup-and-book mark with 0 attacks, while R1 asks the default bot to win that
match. The test proves two things: T60's switch off reproduces the pre-T60 bot (the 24 golden prints), and that old bot
loses there, so the difference is the fix. With impatience a second behaviour on by default, "the default with
`wholeDetour` off" is no longer the old bot, so the test now names it by both switches (`wholeDetour = false`,
`impatientAfterMs = 0` at NORMAL and HARD). Its assertions, prints, sample and numbers are unchanged; nothing it checked
stops being checked; R1's test asserts the opposite for the shipped default; no threshold changes anywhere. (3) The
coordinator's premise "8.0..8.6 s between bots" was my T60 I4 metric, which also restarts when a bot closes in. Without
an attack, as this rule counts, bot-v-bot stretches of 10 s or more occur in 8.4 % (NORMAL) and 4.4 % (HARD) of the
matrix's matches, so 10 s fails R6 and 15 s fires in a golden match: the number is 16 000.

**Shipped**: impatience on by default, 16 000 ms at NORMAL and HARD (EASY 0, `PRE_T57_TUNING` 0); T60's `wholeDetour`
off. With these defaults the duel package's 137 tests are green, every existing threshold untouched.

- **The rule** (`DuelBot.kt`, `BotTuning.kt`): `BotSkill.impatientAfterMs` (0 = off: today's bot bit for bit, no PRNG
  draw, the mind keeps no clock; `PRE_T57_TUNING` sets 0 for NORMAL and HARD, two lines of `DuelNewRulesTest.kt`).
  `BotMind.calmMs / calmAtMs / calmRound`: FIGHT time since the bot's last attack (a dash, a stomp or a ram it sent, on a
  thinking tick or a reflex tick), counted only between its own decisions of one round (a countdown, a point pause, a
  tracking loss add nothing; every round restarts it). On a thinking tick with `calmMs >= impatientAfterMs`, at an
  opponent it can hit within a dash's height reach (`urge`), the bot (a) dashes whatever the alignment (step 3), (b)
  walks straight at the opponent when that line touches no object's body, else routes at it, and (c) keeps out of
  objects by their bodies only (radius + its own, no clearance), so a gap its body fits through is a way. Every safety
  check stands (dash end margin, holes, `safeRamsOnly`, walk-target margins); against a fighter out of a dash's reach (on
  a book) it keeps its plan. Mutable state stays on the tick grid: the 16 ms replay (B9) is identical.
- **R1** (T60's I1 sample, 168 matches per level, T60's switch off): impatience 0: cup-and-book 0 of 42 at NORMAL and at
  HARD (all 0:2 on the clock), the other arenas 42 of 42; impatience 16 s: **168 of 168 at both levels, every one 2:0 by
  ring-outs** (cup-and-book mean 52.4 s NORMAL, 52.1 s HARD; empty 22.6 / 22.5, small 14.1 / 14.3, large 27.7 / 27.5 s,
  unchanged). PASS with impatience on.
- **R2** (60 seeded random 60 x 40 cm tables, 1..3 cups r 3..5 cm or books 8..14 x 6..10 cm; NORMAL and HARD, JUMP / ROLL
  / FLY against an idle JUMP, both marks: 720): before (impatience 0) **80 not won** (79 lost 0:2 on the clock, almost all
  with 0 attacks), 565 won 2:0 by ring-outs, 15 tables; with impatience 16 s **10 not won** (6 lost 0:2, 4 lost 1:2),
  684 won 2:0, 5 tables. FAIL (bar 7). The reviewer's probe (another generator) had 113 before. Every table that still
  fails, with why (traced: one match each):
  1. table 5 [cup r 4.5 cm at (-0.242, 0.142); book 13.2 x 8.8 cm at (0.098, 0.118); cup r 3.5 cm at (-0.082, 0.034)],
     NORMAL JUMP 0:2 and HARD JUMP 1:2, second mark: the idle fighter's mark (-0.09, -0.03) is just below the small cup;
     every impatient stomp or dash pushes it into that cup (a wall slam, 4 to 6 a match), it never moves.
  2. table 9 [cup r 3.1 cm at (-0.212, 0.130); cup r 4.5 cm at (-0.088, -0.056)], NORMAL JUMP 0:2, second mark: the same
     into the 4.5 cm cup (3 stomps a round, slams).
  3. table 17 [cup r 4.6 cm at (0.087, 0.050); cup r 4.7 cm at (-0.189, -0.008); cup r 3.4 cm at (-0.070, 0.083)], HARD
     JUMP 0:2, ROLL 1:2, FLY 0:2, second mark: the idle fighter sits between the small cup above it and the bot below;
     every hit is a slam into the small cup (7 to 12 a match).
  4. table 39 [cup r 3.8 cm at (0.175, -0.108); cup r 3.8 cm at (0.104, 0.020)], NORMAL JUMP 0:2, HARD JUMP 1:2, HARD ROLL
     0:2, first mark: every hit slams it into the cup at (0.104, 0.020) (7 to 9 a match).
  5. table 42 [book 13.4 x 9.9 cm at (-0.107, -0.105); cup r 4.3 cm at (-0.106, 0.072); book 11.8 x 7.9 cm at (0.147,
     -0.069)], NORMAL FLY 1:2, second mark: one impatient dash a round knocks it onto the first book's edge, out of a
     dash's height reach; then no impatient dash (it keeps its plan there, as the contract says) and the plan stands off
     (T60's "on the book").
  The common cause of 1..4: an impatient dash goes along the line from the bot to the opponent, and here a cup stands
  right behind the opponent on that line (day5's cause 1 in the T60 Result); attacking from where it stands cannot push
  it anywhere.
- **R3** (T60's three spots, 8 matches each): before / with impatience. Behind the cup: NORMAL and HARD won 8 of 8 on the
  clock both ways (longest stretch without closing in or attacking 58.9 -> 42.5 s); on the book: 8 of 8 both ways, by
  ring-outs 3 -> 4 at both levels (59.1 / 59.2 s both ways: the fighter is out of a dash's reach there); between the cup
  and the book: NORMAL 6 of 8, HARD 1 of 8 both ways (the bots attack there all the time; the rule never fires). No spot
  gets worse (asserted).
- **R4** (`duel matrix --n 200` with the shipped defaults, 110 s; the same 320 cells as the earlier run with impatience
  16 s via `--tuning`; against T57's table, the state up to 59c8522): **290 of 320 cells identical; 30 move, every one by
  at most 1 point** (6 on cup-and-book, 24 on large: 7 equal-stats,
  9 archetype, 14 stat cells). No row changes its verdict and no range's ends move: B1 4 out, B2 6, B3 54, B5 3, B4 / B6
  / B8 / B11 PASS, B9 64 000 replays identical; B7 61 -> 62 out (large NORMAL FLY AGILITY 40.0 -> 39.5 %). How often the
  rule fired in the moved cells (of 200): equal cup-and-book NORMAL JUMP v ROLL 1, FLY v ROLL 1, FLY v HIDE 1, HARD FLY v
  ROLL 1, FLY v HIDE 1; equal large NORMAL JUMP v FLY 2, ROLL v HIDE 3; archetype cup-and-book HARD ROLL v ROLL 2;
  archetype large NORMAL JUMP v FLY 3, FLY v FLY 2, FLY v ROLL 1, FLY v HIDE 3, ROLL v HIDE 9, HIDE v HIDE 1, HARD JUMP v
  FLY 1, FLY v ROLL 1; stat large NORMAL FLY POWER 1, SPEED 10, GUARD 13, AGILITY 3, ROLL POWER 2, HIDE POWER 1, SPEED 3,
  GUARD 18, AGILITY 1, 8/8/8/8 1; HARD FLY SPEED 7, GUARD 3, HIDE SPEED 14, GUARD 6: 116 of the 6000 matches of those
  cells.
- **R5** (the shipped defaults): every existing test green, thresholds untouched: `hardBeatsEasy` (100 %, 94.5 %),
  `normalIsBetweenEasyAndHard` (NORMAL v EASY 100 %, 95.5 %; HARD v NORMAL 351 / 268 / 252 / 358 and 247 / 274 / 238 /
  286 of 400: identical to T57's), `botsUseTheirSpecialsInWholeMatches`, `acceptanceHoldsOnASample`, the goldens and
  T60's five (its off-position test with the old bot named by both switches, problem 2).
- **R6** (bot-v-bot, the matches in which the rule fires at least once; measured as the matches with a stretch of the
  threshold or more without an attack with the rule off, where it first fires): equal, archetype and stat cells, four
  arenas, seeds 1..20 (3200 matches per level): 10 s NORMAL 8.4 %, HARD 4.4 %; 12 s 1.8 % / 0.5 %; 15 s 0.4 % / 0.1 %; **16 s
  0.34 % (11) / 0.06 % (2)**; over the matrix's own seeds, about 80 of 32 000 NORMAL and 36 of 32 000 HARD matches
  (0.25 % / 0.11 %). PASS at 16 s. The 24 golden matches' longest stretches: 15.55 s (NORMAL, golden 15), 11.1 s (HARD).
- **candidate log** (R2 not won of 720; R1; R6 NORMAL / HARD; goldens):

  | # | what | R1 | R2 | R6 | goldens (T60's prints) | kept? |
  |---|---|---|---|---|---|---|
  | 0 | before (impatience 0) | 0 of 42 on cup-and-book | 80 | - | = | |
  | 1 | 10 s; impatient: dash at any alignment, walk (routed) at the opponent | 168 / 168 | 53 | 8.4 / 4.4 % | match 11 differs | no: R6 |
  | 2 | 15 s; + walk straight at it when the line touches no object's body | 168 / 168 | 23 | 0.4 / 0.1 % | match 15 differs | no |
  | 3 | 16 s; + keep out of objects by their bodies only while impatient | 168 / 168 | **10** | 0.34 / 0.06 % | identical | **kept; on by default since 50907c3** |

  The fourth candidate was not used (the orchestrator's decision): R2 stays not met, as measured.
- **Tests** (`DuelIdleTest.kt`, class `DuelImpatienceTest`, 7 new; T60's class untouched): `anImpatientBotAttacksFromWhereItStands`,
  `impatienceRestartsWithEveryAttackAndEveryRound` (and a 5 s tracking loss changes no attack's match time),
  `impatienceOffIsTodaysBot` (the 24 golden setups at today's rules with impatience 0 against the prints recorded on
  920ed48; PRE_T57_TUNING at 0), `aBotBeatsAnIdleFighterOnRandomTables` (R2 on tables 1..15: 28 of 180 not won without
  impatience, asserted; 3 with it, asserted as "no more than 3": R2's 1 % is not met, see above),
  `impatienceDoesNotCarryTheBotOverAnEdge` (bots impatient from their second decision, a still fighter in corners and by
  edges, 4 tuning arenas and 10 random tables, 108 matches of 30 s: it went off 369 times, the bot never; every walk
  target 2 radii inside), `theDefaultBotBeatsAFighterThatStandsStill` (R1 on `BotTuning()`, and that it is `IMPATIENT`),
  `theRandomTablesAndTheStandOffSpots` (the generator; R2's 720 and R3 printed before / after; R3's "no spot gets
  worse" asserted). `IMPATIENT` is 16 s at NORMAL and HARD written out, equal to the default. T60's class: one line,
  the old bot's tuning in `theFixSwitchedOffIsTheOldBot` (problem 2). Duel package 137 tests (130 + 7), green, 74 to 82 s
  summed on this container (the T61 class about 22 s). `scripts/verify.sh` with the shipped defaults (f715184): OK in
  99 s, core 876, shared 65, android 4 tests, 0 failed; iOS klibs; debug APK.
- **what a human must decide**:
  1. **The correction of T60's off-position test** (problem 2): the orchestrator's reasoning is above and in commit
     9861ec3; the user may overrule it (then impatience goes back to 0 by default and R1 fails with the defaults).
  2. **R2's last 10** (an impatient bot pushes along its line to the opponent, and on tables 5, 9, 17, 39 a cup stands
     right behind the opponent on that line). The next step: an impatient bot goes round to a push that no object
     blocks (choosePush's "blocked" test: an object within a body's width of the push's line before the edge), and dashes
     from there. Its cost in the matrix, estimated (not measured): it acts only while impatient, which happens in 116 of
     the 64 000 matrix matches, and only where an object stops the push; the large table, where 24 of today's 30 moved
     cells are, has no object, and on cup-and-book the rule fires in 7 of the 1200 matches of its 6 moved cells. So
     at most those cup-and-book matches change again: the same 6 cells or fewer, by 1 point or less, no verdict. On the
     random tables it would aim at the 9 slam cases, likely leaving table 42 (out of reach on a book).
  3. The threshold: 16 s is set by the goldens (15.55 s) and R6; a child waits 16 s for the first attack on a stuck
     table (rounds about 52 s on cup-and-book against 22 on the empty table).
  4. Still standing off (T60's Result, unchanged): behind the cup (the bot wins on the clock, 42 s without an attack now,
     59 before), on the book (out of a dash's reach: no impatient dash; 59 s), and day5's three causes (T60's Result 6).
- **cut/deferred**: R2's bar (cut-if-slips: R1 holds on the tuning arenas, and on 98.6 % of the random tables'
  matches); the fourth candidate (not used, by decision).
