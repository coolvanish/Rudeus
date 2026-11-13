# T61 — bot-impatience

status: doing
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
- measured: ...
- what a human must decide: ...
- cut/deferred: ...
