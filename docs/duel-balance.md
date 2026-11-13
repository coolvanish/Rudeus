# Duel balance (T48, T57, T60, T61)

## T61: bot impatience (ai/tasks/T61-bot-impatience.md)

**The rule** (`BotSkill.impatientAfterMs`, **on by default: 16 000 ms at NORMAL and HARD**, 0 at EASY and in
`PRE_T57_TUNING`; T60's `wholeDetour` stays off): a NORMAL or HARD bot that has not attacked (a dash, a stomp, a ram)
for that long of FIGHT, counted between its own decisions of a round, attacks from where it stands: at an opponent it can
hit within a dash's height reach it dashes whatever the alignment, walks straight at it when that line touches no
object's body, and keeps out of objects by their bodies only, until it attacks; then it plays its plan again.

**Why 16 s.** Between bots, a stretch without an attack (this rule's clock, not T60's I4, which also restarts on
closing in) of 10 s or more happens in 8.4 % of NORMAL and 4.4 % of HARD matrix-like matches; 12 s 1.8 / 0.5 %; 15 s 0.4 /
0.1 %, and 15 s fires in one of the 24 golden matches (its longest stretch: 15.55 s). At 16 s: 0.34 % / 0.06 % (R6).

**T60's off-position test.** `theFixSwitchedOffIsTheOldBot` took "the default tuning with `wholeDetour` off" as the bot
before T60; with impatience on by default that is no longer that bot, so the test now names it by both switches
(`wholeDetour = false`, `impatientAfterMs = 0` at NORMAL and HARD). Its assertions, prints and numbers are unchanged
(the orchestrator's correction of the T61 brief; commit 9861ec3 gives the reasoning).

| # | Criterion | Before T61 (impatience 0) | Shipped (impatience 16 s) |
|---|---|---|---|
| R1 | T60's I1 sample, 168 per level, the defaults | 126 of 168 per level: cup-and-book 0 of 42, all 0:2 on the clock | 168 of 168 at both levels, all 2:0 by ring-outs (cup-and-book 52 s a match) |
| R2 | 60 random tables, 720 matches, not won <= 7 | 80 not won (15 tables) | 10 not won (1.4 %, 5 tables): not met |
| R3 | behind the cup / on the book / walking back to the middle, won of 8 (NORMAL, HARD) | 8, 8 / 8, 8 / 6, 1 | the same; on the book 4 by ring-outs (was 3) |
| R4 | `duel matrix --n 200` against T57's table | identical | 290 of 320 cells identical; 30 move by at most 1 point (2 of 200); no verdict changes; B7 61 -> 62 out (large NORMAL FLY AGILITY 40.0 -> 39.5 %); B9 identical |
| R5 | existing tests | green | green, thresholds untouched (T60's off-position test names its old bot, above) |
| R6 | bot-v-bot matches in which it fires | - | NORMAL 0.34 %, HARD 0.06 % (3200 each); over the matrix's own seeds about 0.25 / 0.11 % |

R2's last 10 (traced): on tables 5, 9, 17 and 39 every impatient hit slams the fighter into a cup right behind it on
the bot's line (it never moves toward an edge); on table 42 one hit knocks it onto a book's edge, out of a dash's
reach, where the bot keeps its plan. The layouts are in the brief's Result.

### The current matrix (the shipped defaults, `duel matrix --n 200`, 2026-11-13)

`duel matrix --n 200 --seed 1 --level normal,hard --arenas empty,cup-and-book,small,large --groups ability,equal,stat
--step-ms 33 --check-n 200 --check-step-ms 16`, 110 s on 4 threads. Every cell equals T57's table below except the 30
listed after the verdicts; so T57's per-cell tables, read with those 30 rows, are the current ones.

| # | Criterion | Threshold | Measured | Verdict |
|---|---|---|---|---|
| B1 | each ability against the field, all at 5/5/5/5 | 35.0 %..65.0 % | 20.3 %..83.5 % | FAIL (4 out: empty HARD JUMP 24.7, FLY 65.3; small HARD FLY 83.5; large HARD JUMP 20.3: T57's four) |
| B2 | each ability against the field, archetype stats | 30.0 %..70.0 % | 19.5 %..84.3 % | FAIL (6 out, T57's) |
| B3 | every matchup, each side, equal stats and archetypes | 25.0 %..75.0 % | 1.0 %..99.0 % | FAIL (54 out) |
| B4 | rounds ended by the clock, per ability pair | <= 25.0 % (large: <= 40.0 %) | 0.0 %..0.2 % | PASS |
| B5 | hits per ring-out, per ability pair | 2.0..8.0 (large: ..10.0) | per pair 2.5..8.7 (per arena 3.6..7.2) | FAIL (3 out) |
| B6 | mean match length, per ability pair | 30.0..120.0 s (small: 20.0..) | per pair 23.7 s..54.7 s | PASS |
| B7 | a specialist (8/4/4/4) against 5/5/5/5 | 40.0 %..65.0 % | 7.0 %..83.5 % | FAIL (62 out: T57's 61 + large NORMAL FLY AGILITY 39.5 %) |
| B8 | 8/8/8/8 against 5/5/5/5 | >= 65.0 % | 80.0 %..100.0 % | PASS |
| B9 | the same matches at another frame length | identical | identical (64 000 replayed at 16 ms) | PASS |
| B11 | specials per fighter and match (equal stats); no twin pairs | >= 1.0 NORMAL, >= 0.5 HARD; 0 twins | 0.9..3.9 a match; 0 twins | PASS |

Against the field (B1 / B2) seven values move, by at most 0.5 point: equal stats on cup-and-book, NORMAL FLY 51.3 ->
51.5, ROLL 42.2 -> 42.0, HARD FLY 60.5 -> 60.3, ROLL 37.5 -> 37.7; archetypes on the large table, NORMAL JUMP 42.3 ->
42.7, ROLL 84.0 -> 84.2, HIDE 38.8 -> 38.3. The 30 cells that differ from T57's, and in how many of their 200 matches the rule fires (measured as a
stretch of 16 s or more without an attack in T57's match):

| group | arena | level | cell | A's wins of 200, T57 -> now | rule fired (of 200) |
|---|---|---|---|---|---|
| equal | cup-and-book | NORMAL | JUMP v ROLL | 118 -> 118 | 1 |
| equal | cup-and-book | NORMAL | FLY v ROLL | 101 -> 102 | 1 |
| equal | cup-and-book | NORMAL | FLY v HIDE | 86 -> 86 | 1 |
| equal | cup-and-book | HARD | FLY v ROLL | 123 -> 122 | 1 |
| equal | cup-and-book | HARD | FLY v HIDE | 100 -> 100 | 1 |
| equal | large | NORMAL | JUMP v FLY | 42 -> 42 | 2 |
| equal | large | NORMAL | ROLL v HIDE | 91 -> 91 | 3 |
| archetype | cup-and-book | HARD | ROLL v ROLL | 103 -> 103 | 2 |
| archetype | large | NORMAL | JUMP v FLY | 72 -> 74 | 3 |
| archetype | large | NORMAL | FLY v FLY | 102 -> 103 | 2 |
| archetype | large | NORMAL | FLY v ROLL | 22 -> 22 | 1 |
| archetype | large | NORMAL | FLY v HIDE | 59 -> 61 | 3 |
| archetype | large | NORMAL | ROLL v HIDE | 177 -> 178 | 9 |
| archetype | large | NORMAL | HIDE v HIDE | 96 -> 96 | 1 |
| archetype | large | HARD | JUMP v FLY | 22 -> 22 | 1 |
| archetype | large | HARD | FLY v ROLL | 41 -> 41 | 1 |
| stat | large | NORMAL | FLY POWER v 5/5/5/5 | 109 -> 108 | 1 |
| stat | large | NORMAL | FLY SPEED v 5/5/5/5 | 154 -> 155 | 10 |
| stat | large | NORMAL | FLY GUARD v 5/5/5/5 | 123 -> 124 | 13 |
| stat | large | NORMAL | FLY AGILITY v 5/5/5/5 | 80 -> 79 | 3 |
| stat | large | NORMAL | ROLL POWER v 5/5/5/5 | 140 -> 141 | 2 |
| stat | large | NORMAL | HIDE POWER v 5/5/5/5 | 69 -> 69 | 1 |
| stat | large | NORMAL | HIDE SPEED v 5/5/5/5 | 167 -> 167 | 3 |
| stat | large | NORMAL | HIDE GUARD v 5/5/5/5 | 92 -> 93 | 18 |
| stat | large | NORMAL | HIDE AGILITY v 5/5/5/5 | 119 -> 119 | 1 |
| stat | large | NORMAL | HIDE 8/8/8/8 v 5/5/5/5 | 200 -> 200 | 1 |
| stat | large | HARD | FLY SPEED v 5/5/5/5 | 136 -> 135 | 7 |
| stat | large | HARD | FLY GUARD v 5/5/5/5 | 114 -> 114 | 3 |
| stat | large | HARD | HIDE SPEED v 5/5/5/5 | 137 -> 135 | 14 |
| stat | large | HARD | HIDE GUARD v 5/5/5/5 | 84 -> 82 | 6 |

(Cells where A's wins are unchanged still differ in match time, hits or specials. The T57 table below is history: the
state from T57 to T60.)

## T60: a bot must beat a fighter that stands still (ai/tasks/T60-bot-beats-idle.md)

**Found by T58**: on `DuelArenas.CUP_AND_BOOK` a NORMAL or HARD bot never scored against a fighter that sends no
input. **The cause** (`Brain.around`, the detour round the opponent): the push from the mark goes toward the bottom edge,
so the spot to dash from is above the fighter, against the cup's keep-out circle; the shorter way there runs between
the fighter and the cup (13.6 cm apart, 16.6 cm needed), and the side of the detour, picked anew on every decision by
its next waypoint alone, flips: off the ring the waypoint into the gap is refused, on the ring its first step is not.
The bot goes to and fro 1 to 2 cm below-left of the fighter for the whole round, from where a dash would push it 30° or
more off the push, so it never dashes. EASY dashes at any alignment and wins. The full trace is in the brief's Result.

**The fix, behind `BotTuning.wholeDetour` (off by default, a human decides; see below).** When another object that
blocks closes the shorter way round (a hole does not count: its bounding circle is too coarse), the bot takes the other
side if its whole way round is open, else walks straight at its goal if nothing is in the way; otherwise as before. No rule, physics or `DuelParams` change; nothing drawn from
the bot's generator; `PRE_T57_TUNING` sets it false, and with it false the 24 golden match setups at today's rules give
the prints recorded before T60 (`theFixSwitchedOffIsTheOldBot`). On a table without objects it changes nothing.

**Why it is off by default.** On, it breaks `normalIsBetweenEasyAndHard` (not loosened): HARD ROLL beats NORMAL ROLL on
cup-and-book 214 of 400 on the test's seeds, under the 0.55 floor (220); 238 before. Over five seed bases (2000
matches) 63.4 % -> 54.1 %. The fix on NORMAL alone: 50.4 %; on HARD alone: 68.4 %. Both levels play better near the cup
with it; NORMAL, which went to and fro beside a HARD bot standing by the cup too, gains far more. The six
candidates that met I1 all cost that cell, 211..217 of 400 (the brief's candidate log). The orchestrator decided to turn
the fix on and to set that test's per-ability floor to 0.50 with a 0.58 mean; the permission system refused the commit
that lowers the floor (a threshold change needs the user's own approval), so the default stays off until the user
approves it.

### The idle table (I1, I2): a fighter that sends no input on its mark, 5/5/5/5 both

Every bot ability against the same ability and against JUMP, both marks, 3 seeds: 42 matches per cell. "won" = the
bot won; all wins were 2:0 by two ring-outs; "clock" = lost 0:2 on two timeouts; s = mean match length; stand-off = I4's
longest stretch without closing in or attacking.

| arena | level | before (T57 bot): won / clock / s / stand-off | after (`wholeDetour` on) |
|---|---|---|---|
| empty | EASY | 42 / 0 / 33.7 s / 4.2 s | 42 / 0 / 33.7 s / 4.2 s |
| empty | NORMAL | 42 / 0 / 22.6 s / 2.7 s | 42 / 0 / 22.6 s / 2.7 s |
| empty | HARD | 42 / 0 / 22.5 s / 2.6 s | 42 / 0 / 22.5 s / 2.6 s |
| cup-and-book | EASY | 42 / 0 / 32.9 s / 4.2 s | 42 / 0 / 33.1 s / 4.2 s |
| cup-and-book | NORMAL | **0 / 42 / 129.0 s / 59.2 s** | 42 / 0 / 28.4 s / 3.0 s |
| cup-and-book | HARD | **0 / 42 / 129.0 s / 57.6 s** | 42 / 0 / 27.6 s / 2.6 s |
| small | EASY | 42 / 0 / 19.5 s / 3.3 s | 42 / 0 / 19.5 s / 3.3 s |
| small | NORMAL | 42 / 0 / 14.1 s / 2.5 s | 42 / 0 / 14.1 s / 2.5 s |
| small | HARD | 42 / 0 / 14.3 s / 2.6 s | 42 / 0 / 14.3 s / 2.6 s |
| large | EASY | 42 / 0 / 42.3 s / 4.5 s | 42 / 0 / 42.3 s / 4.5 s |
| large | NORMAL | 42 / 0 / 27.7 s / 2.7 s | 42 / 0 / 27.7 s / 2.7 s |
| large | HARD | 42 / 0 / 27.5 s / 2.7 s | 42 / 0 / 27.5 s / 2.7 s |

I3 (a target that walks to a new point one radius from its mark every 500 ms): 9 of 9 per arena and level, NORMAL and
HARD, all 2:0 by ring-outs, before and after (its motion breaks the to and fro). I4 between bots (equal and archetype
cells, four arenas, NORMAL and HARD, seeds 1..10: 3200 bot sides): longest 8.6 s before, 8.0 s after, none over 10 s
(the tests' 640 sides: 7.5 s).

**Arenas with a CLIFF hole** (not tuning arenas; every ability pair at equal stats, seeds 1..6, 96 matches per level;
"sides" = bot sides with a stretch over 10 s without closing in or attacking, of 192):

| arena | level | rounds on the clock, off / on | sides over 10 s, off / on | a still fighter: won (by ring-outs), off / on |
|---|---|---|---|---|
| `Scenarios.day5Level()` (cup, book, 10 x 30 cm gap) | NORMAL | 6 of 235 / 7 of 238 | 27 / 30 | 23 (0) / 22 (0) of 24 |
| `Scenarios.day5Level()` | HARD | 62 of 228 / 61 of 229 | 126 / 127 | 23 (0) / 22 (0) of 24 |
| 50 x 40 cm, a 4 x 30 cm hole strip, a book | NORMAL | 0 / 0 | 0 / 0 | 24 (2) / 24 (2) of 24 |
| 50 x 40 cm, a 4 x 30 cm hole strip, a book | HARD | 1 / 1 | 2 / 2 | 24 (2) / 24 (2) of 24 |

day5 is where the T58 review saw HARD v HARD draw 0:0 after 5 rounds. Its causes are not T60's gap (traced): the mark
(0.08, 0) touches the cup and the push toward the nearest edge drives the fighter into it (wall slams); a bot on that mark
starts inside the cup's keep-out circle with its goal straight away from it and goes to and fro round it (the side flips
with a near-zero cross product); the gap's bounding circle plus margin (21.8 cm) bars walk targets on half the table.
The fix changes none of it. A first version of the fix also counted a hole's coarse bounding circle (21 cm round the
strip) as an object closing the way, and on the strip table NORMAL stalled more often (0 -> 6 sides over 10 s); since
candidate 7 a hole closes a way as the edge does, and the switch changes nothing on that table. Both are in the brief's
"what a human must decide".

### The matrix (`duel matrix --n 200`), before and with the switch on

Before = 920ed48 (identical to the T57 table below); the committed default (switch off) gives the same `matrix.json`
cells. On = `--tuning` `{"wholeDetour": true}`, 91 s on 4 threads, measured with candidate 3; candidate 7 (kept) differs
only near holes, which no tuning arena has (`--n 100`: all 320 cells identical to 3's). Only the 80 cup-and-book cells
change; the other 240 tallies are identical.

| # | Threshold | Before | On | |
|---|---|---|---|---|
| B1 | 35..65 % | 20.3..83.5 % (4 out) | 20.3..83.5 % (5 out: + cup-and-book HARD ROLL 37.5 -> 33.2 %) | FAIL both |
| B2 | 30..70 % | 19.5..84.3 % (6 out) | 19.5..84.3 % (6 out, the same) | FAIL both |
| B3 | 25..75 % | 1.0..99.0 % (54 out) | 1.0..99.0 % (52 out: cup-and-book NORMAL archetype FLY v ROLL back in) | FAIL both |
| B4 | <= 25 % (large 40) | 0..0.2 % | 0..0.2 % | PASS both |
| B5 | 2..8 (large 10) | 2.5..8.7 (3 out) | 2.5..8.5 (1 out: cup-and-book HARD archetype JUMP v HIDE 8.5) | FAIL both |
| B6 | 30..120 s (small 20) | 23.7..54.7 s | 23.7..54.7 s | PASS both |
| B7 | 40..65 % | 7.0..83.5 % (61 out) | 7.0..83.5 % (62 out: 3 back in, 4 SPEED specialists on cup-and-book now out) | FAIL both |
| B8 | >= 65 % | 80.0..100 % | 80.0..100 % | PASS both |
| B9 | identical | 64 000 replays identical | 64 000 replays identical | PASS both |
| B11 | >= 1.0 NORMAL, >= 0.5 HARD | 0.9..3.9, 0 twins | 0.9..3.9, 0 twins | PASS both |

No row changes its verdict and no row's range moves. Against the field on cup-and-book (B1, equal stats, JUMP / FLY /
ROLL / HIDE): NORMAL 53.2 / 51.3 / 42.2 / 53.3 -> 54.0 / 48.2 / 42.5 / 55.3, HARD 43.3 / 60.5 / 37.5 / 58.7 -> 46.0 /
62.5 / 33.2 / 58.3 (every move under 5 points). With archetype stats (B2) HARD ROLL 58.7 -> 48.5 % and HARD HIDE
47.8 -> 53.2 %, both toward 50. 19 single cells move 5 points or more, all on cup-and-book; the largest are the SPEED
specialists (NORMAL ROLL 44 -> 24 %, HIDE 49 -> 36 %, HARD FLY 48 -> 35.5 %, HARD ROLL 41 -> 31.5 %) and ROLL against
HIDE at HARD (archetype 47.5 -> 31.5 %): probably the cells where one side profited most from the other going to and
fro beside the cup (a SPEED 8 line walks round fastest; a ROLL may ram up to 60° off its push where a dash needs 30°, so
the to and fro cost it least). Not traced cell by cell.

The strength tests with the switch on (their own seeds): `hardBeatsEasy` empty 100 %, cup-and-book 94.5 -> 96.5 %;
NORMAL v EASY 100 % and 95.5 -> 97.5 %; HARD v NORMAL empty identical (JUMP 351, FLY 268, ROLL 252, HIDE 358 of 400),
cup-and-book JUMP 247 -> 257, FLY 274 -> 268, **ROLL 238 -> 214** (floor 220), HIDE 286 -> 284.

## T57: the rules of 2026-11-12 (ai/tasks/T57-duel-balance-2.md)

Three rule changes, each in `DuelParams` with an off position that reproduces the T48 game bit for bit (B9's second
half, the test `theNewRulesSwitchedOffAreThePreviousPhysics`): the JUMP stomp and the ROLL ram share the dash cooldown
(`specialsShareDashCooldown`); SPEED lengthens every dash (`dashReachBase + dashReachPerSpeed * SPEED` x the dash speed,
its duration unchanged); a dash or a stomp hits only a victim whose feet are at most `hitReachUpM` (a stomp:
`stompReachUpM`) above and `hitReachDownM` below the attacker's (a jump over a dash is a dodge, a fighter on a book is
safe from a dash from the table, not from a stomp landing beside it: see below), and the fighter a missed dash presses
into stands firm. And rule 4: FLY's archetype is 4/6/4/6
(was 3/7/3/7). The acceptance table is T57's B1..B11; B1, the abilities at equal stats 5/5/5/5, is the row new drawings
meet; B11 (added by the review) checks that the bots use every ability's special, so that B1 measures the abilities and
not only their bodies. Priority when rows conflict: B9, B10 > B11 > B4 > B1 > B5, B6 > B3 > B2 > B7.

**The review (pass 2).** Pass 1's bots used no special at all in the 19 200 matrix matches without a ROLL (FLY and
HIDE lifted off and dodged only within 2 radii of an edge, a JUMP stomped only a fighter that could not jump: a ROLL);
the 12 800 with a ROLL had 1.5 a match (its rams, and stomps at it). So its B1 measured JUMP, FLY and HIDE with their
specials unused. Since the review the bots use them as a player would: a FLY lifts off and a
HIDE dodges a hit that would land, wherever they stand (NORMAL notices a hit coming between its thoughts 30 % of the
time); a JUMP whose stomp is ready jumps a dash as a stomp, and stomps a fighter on the ground that no jump gets out of
(the stomp now reaches 9 cm up, above a plain jump's 8.1 cm apex: only a FLY's float clears it); a ROLL turns to face the
other and rams anywhere on the table; a HARD ROLL also rams an opponent in its path that cannot get out of it. The
numbers were retuned so that each special is worth using: stomp 2.0 x, ram 2.0 x, counter 1.2 x, lift-off 200 ms at
walking pace, dodge 300 ms, and each special its own cooldown (lift-off 2.2 x, dodge 1.6 x the 9 s: a lift-off or a
dodge that works is a free hit back).

**The stomp's reach is a change to rule 3 as the user decided it** (there a stomp reaches as high as a dash, 2.8 cm).
With `stompReachUpM` 0.09 a JUMP on the table stomps a fighter standing on a 3 or 4 cm platform beside it off the
platform (a dash from the same spot misses it: `aStompReachesAFighterOnABookADashDoesNot`), and a plain jump (apex 8.1
cm on 8 ms steps) no longer clears a stomp; only a FLY's float does (above 9 cm from 0.2 to 2.0 s after its jump, apex
27.6 cm: `aPlainJumpDoesNotClearAStompAFloatDoes`). At the dash's 2.8 cm, the rest the final defaults (n 100), a JUMP
took 6.0..44.7 % against the field at equal stats and started 0.36..0.81 specials a match (B1 10 cells out, B11 6).
Kept as the measured default; the user decides (the brief's Result, "What a human must decide").

### Verdicts (n = 200, NORMAL v NORMAL and HARD v HARD, four arenas)

"T48" is T48's committed table under its own criteria (A1..A8: no equal-stats group, archetype field 35..65 %, no small
/ large exceptions in hits and length); "before" is step 2 (the rules on with the brief's starting numbers, the bots
taught, everything else as T48 left it); "pass 1" is the defaults T57 first committed (specials unused between bots);
"final" is the defaults after the review.

| # | Criterion | Threshold | T48 | Before (step 2) | Pass 1 | Final | Verdict |
|---|---|---|---|---|---|---|---|
| B1 | each ability against the field, all at 5/5/5/5 | 35..65 % | not in the matrix | 7.7..85.2 % (27 of 32 out) | 28.0..60.8 % (1 out) | 20.3..83.5 % (4 out, all HARD: JUMP 24.7 empty, 20.3 large; FLY 65.3 empty, 83.5 small) | FAIL (4 out) |
| B2 | each ability against the field, archetype stats | 30..70 % | 7.2..87.7 % | 17.0..91.7 % (10 out) | 16.2..75.3 % (9 out) | 19.5..84.3 % (6 out) | FAIL |
| B3 | every matchup, each side | 25..75 % | 0..100 % (46 out) | 0..100 % (110 out) | 5.5..94.5 % (38 out) | 1.0..99.0 % (54 out) | FAIL |
| B4 | rounds ended by the clock, per pair | <= 25 % (large <= 40 %) | 0..1.9 % | 0..5.6 % | 0..0.2 % | 0..0.2 % | PASS |
| B5 | hits per ring-out, per pair | 2..8 (large ..10) | 2.5..9.4 | 3.4..12.7 (19 out) | 2.8..8.2 | 2.5..8.7 (3 out: JUMP v HIDE on cup-and-book, 8.0..8.7) | FAIL (3 out) |
| B6 | match length, per pair | 30..120 s (small 20..) | 15.3..62.4 s | 25.4..64.7 s | 24.6..55.2 s | 23.7..54.7 s | PASS |
| B7 | a specialist (8/4/4/4) against 5/5/5/5 | 40..65 % | 6.0..86.0 % (45 out) | 8.5..95.5 % (56 out) | 5.5..90.5 % (77 out) | 7.0..83.5 % (61 out) | FAIL |
| B8 | 8/8/8/8 against 5/5/5/5 | >= 65 % | 32..100 % (3 out) | 83.5..100 % | 95.5..100 % | 80.0..100 % | PASS |
| B9 | the same matches at 16 ms and 33 ms; the rules off = the T48 game | identical | identical | identical | identical | identical (64 000 replays); off switches: 24 bot matches + 16 fights recorded on 207acc6, bit for bit | PASS |
| B10 | every core/duel test green | - | - | - | green | green (`scripts/verify.sh`; the Result lists every test touched) | PASS |
| B11 | specials per fighter per match, equal stats, per ability, arena and level; no twin pairs | >= 1.0 NORMAL, >= 0.5 HARD | - | - | not measured: 0 specials in the 19 200 matches without a ROLL (JUMP, FLY and HIDE never used theirs there) | NORMAL 1.15..3.78, HARD 0.88..3.87; no twins | PASS |

Each ability against the field at equal stats (B1, win share %), JUMP / FLY / ROLL / HIDE, and the specials each starts
per fighter per match (B11):

| arena | level | B1: JUMP / FLY / ROLL / HIDE | B11: JUMP / FLY / ROLL / HIDE | dashes and stomps missed by height / match |
|---|---|---|---|---|
| empty | NORMAL | 45.2 / 63.7 / 40.3 / 50.8 | 1.81 / 1.33 / 3.01 / 1.46 | 0.30 |
| empty | HARD | **24.7** / **65.3** / 48.0 / 62.0 | 1.37 / 2.09 / 3.29 / 2.17 | 0.31 |
| cup-and-book | NORMAL | 53.2 / 51.3 / 42.2 / 53.3 | 2.52 / 1.55 / 3.71 / 1.78 | 0.60 |
| cup-and-book | HARD | 43.3 / 60.5 / 37.5 / 58.7 | 2.48 / 2.08 / 3.77 / 2.23 | 0.87 |
| small | NORMAL | 42.8 / 64.8 / 53.0 / 39.3 | 1.41 / 1.15 / 2.41 / 1.45 | 0.32 |
| small | HARD | 38.8 / **83.5** / 37.5 / 40.2 | 0.88 / 1.67 / 1.96 / 1.72 | 0.18 |
| large | NORMAL | 41.2 / 58.5 / 53.5 / 46.8 | 2.28 / 1.33 / 3.78 / 1.54 | 0.36 |
| large | HARD | **20.3** / 59.2 / 61.8 / 58.7 | 1.67 / 2.15 / 3.87 / 2.29 | 0.33 |

What still fails, and why (the counts):
- **B1, four cells, all at HARD: JUMP 20.3..24.7 % on the empty and large tables, FLY 65.3 % (empty) and 83.5 %
  (small).** At NORMAL every ability is in (39.3..64.8 %). The pair behind it is JUMP v FLY: 17.0 / 83.0 % at NORMAL and
  5.5 / 94.5 % at HARD on the empty table (11.5 and 3.5 % on the small one, 21.0 and 1.0 % on the large one). A FLY's
  float (the walking game's 0.3 x gravity) clears the stomp's 9 cm reach, so the JUMP bot does not stomp a FLY (one that
  jumps floats clear of it, and a HARD one always jumps), while the FLY's lift-off takes one of the JUMP's dashes and
  hits back; at HARD every FLY reaction comes in time. JUMP against ROLL and HIDE on the empty table at NORMAL: 46.5 and
  72.0 %. With the specials unused (pass 1) B1 had one cell out (small HARD ROLL 28 %), but that measured bodies, not
  abilities.
- **B3, 54 cells**: at equal stats JUMP v FLY (1.0..39.5 %; 1.0 on the large table at HARD) and, at HARD, ROLL v HIDE
  (14.5 % empty, 19.5 % cup-and-book: the dodge takes a ram and the counter answers it) and ROLL v FLY on the small
  table (11 %); the rest are archetype cells.
- **B5, three pairs on cup-and-book (8.0..8.7 hits per ring-out, JUMP v HIDE)**: the pair with the most hits on that
  table at both levels; a dodged dash and its counter are hits that ring nobody out.
- **B2, six cells**: HARD JUMP (19.5..29.7 %) and HARD FLY on the small table (84 %), as B1; ROLL's 7/6/5/2 on the large
  table (84 %: its pace).
- **B7, 61 of 128**: the stats are not worth the same (POWER 7.0..71.0 %, SPEED 9.0..83.5 %, GUARD 9.5..72.5 %, AGILITY
  23.5..79.5 %); fewer out than pass 1 (77), not fixed by numbers.
- **A jump between bots clears almost no dash.** In whole matches (`botsUseTheirSpecialsInWholeMatches`: every ability
  pair at equal stats, 64 matches per level, 32 on the empty table, where only the air puts a fighter out of reach) the
  bots decided a plain Jump once at NORMAL and 4 times at HARD, and a FLY's or a HIDE's jump got clear of 0 dashes; the
  dashes that missed by height on the empty table (0.25 a match at NORMAL, 0.34 at HARD) all had a JUMP for victim, its
  stomp-jump. Bots dash from 1 to 2 cm, once aligned behind the other, too close to clear. The jump over a dash is a
  tool for the child, who dashes from afar; the bots' `botsJumpOverDashesByLevel` shows they would take it.
- The bot strength ordering holds. HARD's edge over NORMAL in the mirror match is smaller now that NORMAL uses its
  specials: `normalIsBetweenEasyAndHard` measures 59.5..89.5 % per ability on 400 matches each (cup-and-book ROLL the
  lowest; `duel series --n 400` gives that cell 66.5 % from seed 1, 65.8 % from seed 70001, 59.5 % from seed 33000), and
  its floor is 0.55 since the second review (0.60 on 50 or 200 matches before).

### Step 2: the rules on with the starting numbers, the bots taught (the T57 "before"), 2026-11-12

Starting numbers: shared cooldown on; dash reach 0.75 + 0.05 SPEED (SPEED 5 = 1, as before); reach 2 cm up, 4 cm down.
Everything else as T48 left it. `duel matrix --n 200` (320 cells: 80 equal, 80 archetype, 160 stat; 64 000 matches and
the same 64 000 at 16 ms): 144.7 s on 4 threads.

| # | Criterion | Threshold | Measured | Verdict |
|---|---|---|---|---|
| B1 | each ability against the field, all at 5/5/5/5 | 35.0 %..65.0 % | 7.7 %..85.2 % | FAIL (27 out) |
| B2 | each ability against the field, archetype stats | 30.0 %..70.0 % | 17.0 %..91.7 % | FAIL (10 out) |
| B3 | every matchup, each side, equal stats and archetypes | 25.0 %..75.0 % | 0.0 %..100.0 % | FAIL (110 out) |
| B4 | rounds ended by the clock, per ability pair | <= 25.0 % (large: <= 40.0 %) | 0.0 %..5.6 % | PASS (0 out) |
| B5 | hits per ring-out, per ability pair | 2.0..8.0 (large: ..10.0) | per pair 3.4..12.7 (per arena 4.2..9.1) | FAIL (19 out) |
| B6 | mean match length, per ability pair | 30.0..120.0 s (small: 20.0..) | per pair 25.4 s..64.7 s (per arena 30.7 s..53.9 s) | PASS (0 out) |
| B7 | a specialist (8/4/4/4) against 5/5/5/5 | 40.0 %..65.0 % | 8.5 %..95.5 % | FAIL (56 out) |
| B8 | 8/8/8/8 against 5/5/5/5 | >= 65.0 % | 83.5 %..100.0 % | PASS (0 out) |
| B9 | the same matches at another frame length | identical | identical (64 000 matches replayed at 16 ms, 0 different) | PASS |

Each ability against the field (win share, %), JUMP / FLY / ROLL / HIDE:

| arena | level | equal stats 5/5/5/5 (B1) | archetype stats (B2) |
|---|---|---|---|
| empty | NORMAL | 21.2 / 76.5 / 31.5 / 70.8 | 34.8 / 40.0 / 49.7 / 75.5 |
| empty | HARD | 22.7 / 74.5 / 23.2 / 79.7 | 24.5 / 41.3 / 56.3 / 77.8 |
| cup-and-book | NORMAL | 30.2 / 65.0 / 37.8 / 67.0 | 35.5 / 53.0 / 50.5 / 61.0 |
| cup-and-book | HARD | 27.2 / 68.5 / 36.3 / 68.0 | 27.7 / 55.5 / 59.5 / 57.3 |
| small | NORMAL | 16.5 / 74.0 / 31.5 / 78.0 | 33.0 / 39.7 / 35.7 / 91.7 |
| small | HARD | 32.8 / 76.7 / 7.7 / 82.8 | 33.7 / 57.0 / 19.5 / 89.8 |
| large | NORMAL | 15.7 / 75.7 / 36.3 / 72.3 | 17.0 / 57.7 / 65.8 / 59.5 |
| large | HARD | 24.5 / 59.2 / 31.2 / 85.2 | 22.7 / 44.2 / 55.7 / 77.5 |

What it said: the rules end the T48 problem the other way round. ROLL no longer runs the table (its ram now costs a
dash), but the stomp and the ram, still at T48's weakened 0.85 x / 1.1 x, are now worse than the dash they cost; FLY
(a plain jump floats 2.2 s, far out of reach) and HIDE (the dodge, the x2 counter, and a jump too) take 59..85 % at
equal stats. Rounds still end on the table (clock 0..5.6 %).

### T57 pass 1 (specials unused between bots), 2026-11-12

The defaults T57 first committed (0d8042b, candidate 17), before the review: FLY and HIDE lifted off and dodged only within 2 radii of an edge and a JUMP stomped only a fighter that could not jump, so between bots the specials were used only in the cells with a ROLL: 0 in the 19 200 equal-stats and archetype
matches without one, 1.5 a match in the 12 800 with one (the overview's 0.3..1.3 a match is that, averaged over all
pairs). Its acceptance table, as committed; the full pass-1 matrix is docs/duel-balance.md at 7d1a247.

#### Acceptance (pass 1)

| # | Criterion | Threshold | Measured | Verdict |
|---|---|---|---|---|
| B1 | each ability against the field, all at 5/5/5/5 | 35.0 %..65.0 % | 28.0 %..60.8 % | FAIL (1 out) |
| B2 | each ability against the field, archetype stats | 30.0 %..70.0 % | 16.2 %..75.3 % | FAIL (9 out) |
| B3 | every matchup, each side, equal stats and archetypes | 25.0 %..75.0 % | 5.5 %..94.5 % | FAIL (38 out) |
| B4 | rounds ended by the clock, per ability pair | <= 25.0 % (large: <= 40.0 %) | 0.0 %..0.2 % | PASS (0 out) |
| B5 | hits per ring-out, per ability pair | 2.0..8.0 (large: ..10.0) | per pair 2.8..8.2 (per arena 3.5..7.8) | PASS (0 out) |
| B6 | mean match length, per ability pair | 30.0..120.0 s (small: 20.0..) | per pair 24.6 s..55.2 s (per arena 27.1 s..49.7 s) | PASS (0 out) |
| B7 | a specialist (8/4/4/4) against 5/5/5/5 | 40.0 %..65.0 % | 5.5 %..90.5 % | FAIL (77 out) |
| B8 | 8/8/8/8 against 5/5/5/5 | >= 65.0 % | 95.5 %..100.0 % | PASS (0 out) |
| B9 | the same matches at another frame length | identical | identical (64000 matches replayed at 16 ms, 0 different) | PASS (0 out) |
| B10 | every core/duel test green | - | not measured here: `scripts/verify.sh` (B9's other half, the rules switched off, is the test `theNewRulesSwitchedOffAreThePreviousPhysics`) | - |

- B1 out of 35.0 %..65.0 %: small HARD ROLL 28.0 %
- B2 out of 30.0 %..70.0 %: empty NORMAL FLY 29.2 %; empty NORMAL HIDE 71.8 %; empty HARD FLY 24.8 %; small HARD FLY 75.3 %; small HARD ROLL 16.2 %; small HARD HIDE 72.8 %; large NORMAL FLY 23.2 %; large HARD FLY 29.0 %; large HARD ROLL 70.8 %
- B3 out of 25.0 %..75.0 %: small HARD equal ROLL v HIDE 23.5 %; small HARD equal HIDE v ROLL 76.5 %; empty NORMAL archetype JUMP v ROLL 85.5 %; empty NORMAL archetype ROLL v JUMP 14.5 %; empty NORMAL archetype FLY v HIDE 11.0 %; empty NORMAL archetype HIDE v FLY 89.0 %; empty HARD archetype FLY v HIDE 12.5 %; empty HARD archetype HIDE v FLY 87.5 %; cup-and-book HARD archetype FLY v ROLL 19.0 %; cup-and-book HARD archetype ROLL v FLY 81.0 %; cup-and-book HARD archetype ROLL v HIDE 76.0 %; cup-and-book HARD archetype HIDE v ROLL 24.0 %; small NORMAL archetype JUMP v HIDE 11.5 %; small NORMAL archetype HIDE v JUMP 88.5 %; small NORMAL archetype FLY v ROLL 22.0 %; small NORMAL archetype ROLL v FLY 78.0 %; and 22 more
- B7 out of 40.0 %..65.0 %: empty NORMAL JUMP POWER 39.5 %; empty NORMAL JUMP SPEED 17.5 %; empty NORMAL JUMP GUARD 67.0 %; empty NORMAL JUMP AGILITY 82.5 %; empty NORMAL FLY POWER 39.5 %; empty NORMAL FLY SPEED 17.5 %; empty NORMAL FLY GUARD 67.0 %; empty NORMAL FLY AGILITY 82.5 %; empty NORMAL ROLL SPEED 22.0 %; empty NORMAL ROLL AGILITY 79.5 %; empty NORMAL HIDE POWER 39.5 %; empty NORMAL HIDE SPEED 17.5 %; empty NORMAL HIDE GUARD 67.0 %; empty NORMAL HIDE AGILITY 82.5 %; empty HARD JUMP POWER 38.5 %; empty HARD JUMP SPEED 5.5 %; and 61 more

### The T57 defaults (final, after the review), 2026-11-12

Generated by `./gradlew :cli:run --args="duel matrix --n 200 --out build/duel"` (`build/duel/matrix.md`, headings two
levels down): 320 cells (80 equal-stats, 80 archetype, 160 stat), 64 000 matches and the same 64 000 replayed at 16 ms,
126.4 s on 4 threads (1012 matches/s). The candidate log that led here is in the brief's Result.

`duel matrix --n 200 --seed 1 --level normal,hard --arenas empty,cup-and-book,small,large --groups ability,equal,stat --step-ms 33 --check-n 200 --check-step-ms 16`

Numbers: the defaults. 200 matches per cell (seeds 1..200, marks swapped every other match from the first, as duel series does), both sides bots of the same level, 33 ms frames; B9 replays 200 per cell at 16 ms. Clock = share of the rounds ended by the round clock; hits = Hit + Clash events per ring-out (all rounds' hits); s = mean match length. "equal": both sides 5/5/5/5 (B1); "archetype": each ability's stats for a character without stats (B2).

#### Acceptance

| # | Criterion | Threshold | Measured | Verdict |
|---|---|---|---|---|
| B1 | each ability against the field, all at 5/5/5/5 | 35.0 %..65.0 % | 20.3 %..83.5 % | FAIL (4 out) |
| B2 | each ability against the field, archetype stats | 30.0 %..70.0 % | 19.5 %..84.3 % | FAIL (6 out) |
| B3 | every matchup, each side, equal stats and archetypes | 25.0 %..75.0 % | 1.0 %..99.0 % | FAIL (54 out) |
| B4 | rounds ended by the clock, per ability pair | <= 25.0 % (large: <= 40.0 %) | 0.0 %..0.2 % | PASS (0 out) |
| B5 | hits per ring-out, per ability pair | 2.0..8.0 (large: ..10.0) | per pair 2.5..8.7 (per arena 3.6..7.2) | FAIL (3 out) |
| B6 | mean match length, per ability pair | 30.0..120.0 s (small: 20.0..) | per pair 23.7 s..54.7 s (per arena 27.6 s..47.3 s) | PASS (0 out) |
| B7 | a specialist (8/4/4/4) against 5/5/5/5 | 40.0 %..65.0 % | 7.0 %..83.5 % | FAIL (61 out) |
| B8 | 8/8/8/8 against 5/5/5/5 | >= 65.0 % | 80.0 %..100.0 % | PASS (0 out) |
| B9 | the same matches at another frame length | identical | identical (64000 matches replayed at 16 ms, 0 different) | PASS (0 out) |
| B10 | every core/duel test green | - | not measured here: `scripts/verify.sh` (B9's other half, the rules switched off, is the test `theNewRulesSwitchedOffAreThePreviousPhysics`) | - |
| B11 | abilities in play (equal stats): specials per fighter and match; no twin pairs | >= 1.0 NORMAL, >= 0.5 HARD; 0 twins | 0.9..3.9 a match; 0 twin pairs | PASS (0 out) |

Priority when rows conflict (the T57 review): B9, B10 > B11 > B4 > B1 > B5, B6 > B3 > B2 > B7 (B8 always held).

- B1 out of 35.0 %..65.0 %: empty HARD JUMP 24.7 %; empty HARD FLY 65.3 %; small HARD FLY 83.5 %; large HARD JUMP 20.3 %
- B2 out of 30.0 %..70.0 %: empty HARD JUMP 25.3 %; small HARD JUMP 29.7 %; small HARD FLY 84.0 %; large NORMAL ROLL 84.0 %; large HARD JUMP 19.5 %; large HARD ROLL 84.3 %
- B3 out of 25.0 %..75.0 %: empty NORMAL equal JUMP v FLY 17.0 %; empty NORMAL equal FLY v JUMP 83.0 %; empty HARD equal JUMP v FLY 5.5 %; empty HARD equal FLY v JUMP 94.5 %; empty HARD equal JUMP v ROLL 13.5 %; empty HARD equal ROLL v JUMP 86.5 %; empty HARD equal ROLL v HIDE 14.5 %; empty HARD equal HIDE v ROLL 85.5 %; cup-and-book HARD equal ROLL v HIDE 19.5 %; cup-and-book HARD equal HIDE v ROLL 80.5 %; small NORMAL equal JUMP v FLY 11.5 %; small NORMAL equal FLY v JUMP 88.5 %; small HARD equal JUMP v FLY 3.5 %; small HARD equal FLY v JUMP 96.5 %; small HARD equal FLY v ROLL 89.0 %; small HARD equal ROLL v FLY 11.0 %; and 38 more
- B5 out of 2.0..8.0 (large: ..10.0): cup-and-book HARD equal JUMP v HIDE 8.1; cup-and-book NORMAL archetype JUMP v HIDE 8.0; cup-and-book HARD archetype JUMP v HIDE 8.7
- B7 out of 40.0 %..65.0 %: empty NORMAL JUMP POWER 29.0 %; empty NORMAL FLY SPEED 22.5 %; empty NORMAL FLY GUARD 72.5 %; empty NORMAL ROLL SPEED 20.5 %; empty NORMAL HIDE SPEED 33.0 %; empty NORMAL HIDE AGILITY 70.5 %; empty HARD JUMP POWER 35.5 %; empty HARD FLY SPEED 9.0 %; empty HARD FLY AGILITY 34.0 %; empty HARD ROLL SPEED 15.0 %; empty HARD ROLL AGILITY 26.5 %; empty HARD HIDE POWER 19.5 %; empty HARD HIDE SPEED 9.0 %; cup-and-book NORMAL JUMP AGILITY 35.5 %; cup-and-book NORMAL FLY SPEED 35.0 %; cup-and-book NORMAL FLY AGILITY 32.0 %; and 45 more

#### Overview (equal stats, 5/5/5/5)

| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |
|---|---|---|---|---|---|---|---|---|---|---|
| empty | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 38.1 | 35.0 / 40.3 | 5.4 | FLY v ROLL 5.9 | 2.5 | 0.0 | 3.8 |
| empty | HARD | 0.0 % | JUMP v JUMP 0.0 % | 38.1 | 33.5 / 42.7 | 5.7 | ROLL v ROLL 6.4 | 2.4 | 0.0 | 4.5 |
| cup-and-book | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 44.1 | 39.2 / 48.8 | 7.1 | JUMP v HIDE 7.9 | 2.5 | 4.8 | 4.8 |
| cup-and-book | HARD | 0.0 % | JUMP v JUMP 0.0 % | 43.7 | 39.2 / 48.2 | 7.1 | JUMP v HIDE 8.1 | 2.5 | 5.1 | 5.3 |
| small | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 29.2 | 25.9 / 33.1 | 3.8 | FLY v FLY 4.4 | 2.5 | 0.0 | 3.2 |
| small | HARD | 0.0 % | JUMP v JUMP 0.0 % | 29.4 | 26.2 / 33.8 | 3.7 | JUMP v FLY 4.4 | 2.5 | 0.0 | 3.1 |
| large | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 47.3 | 42.5 / 52.5 | 6.9 | FLY v ROLL 7.7 | 2.5 | 0.0 | 4.5 |
| large | HARD | 0.0 % | JUMP v JUMP 0.0 % | 47.1 | 39.9 / 54.4 | 7.2 | FLY v FLY 8.0 | 2.4 | 0.0 | 5.0 |

#### Overview (archetype stats)

| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |
|---|---|---|---|---|---|---|---|---|---|---|
| empty | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 37.0 | 32.6 / 41.0 | 5.1 | JUMP v FLY 5.8 | 2.5 | 0.0 | 3.6 |
| empty | HARD | 0.0 % | JUMP v JUMP 0.0 % | 38.5 | 33.7 / 43.7 | 5.7 | FLY v FLY 6.5 | 2.5 | 0.0 | 4.4 |
| cup-and-book | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 43.0 | 39.1 / 47.1 | 7.0 | JUMP v HIDE 8.0 | 2.5 | 4.4 | 4.6 |
| cup-and-book | HARD | 0.0 % | JUMP v JUMP 0.0 % | 43.8 | 39.7 / 48.8 | 7.1 | JUMP v HIDE 8.7 | 2.5 | 5.1 | 5.1 |
| small | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 27.6 | 25.0 / 30.7 | 3.6 | JUMP v FLY 4.1 | 2.5 | 0.0 | 3.1 |
| small | HARD | 0.0 % | JUMP v JUMP 0.0 % | 27.7 | 23.7 / 32.5 | 3.6 | FLY v FLY 4.3 | 2.4 | 0.0 | 2.8 |
| large | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 45.3 | 40.9 / 53.4 | 6.6 | JUMP v FLY 7.8 | 2.4 | 0.0 | 4.2 |
| large | HARD | 0.0 % | HIDE v HIDE 0.2 % | 47.2 | 39.3 / 54.7 | 7.2 | FLY v FLY 8.4 | 2.4 | 0.0 | 4.9 |

#### Abilities in play (equal stats, B11)

Specials started per fighter and match, by ability (a same-ability cell counts both fighters); misses = dashes and stomps that met their victim out of height reach, per match (information).

| arena | level | JUMP | FLY | ROLL | HIDE | misses / match |
|---|---|---|---|---|---|---|
| empty | NORMAL | 1.8 | 1.3 | 3.0 | 1.5 | 0.3 |
| empty | HARD | 1.4 | 2.1 | 3.3 | 2.2 | 0.3 |
| cup-and-book | NORMAL | 2.5 | 1.6 | 3.7 | 1.8 | 0.6 |
| cup-and-book | HARD | 2.5 | 2.1 | 3.8 | 2.2 | 0.9 |
| small | NORMAL | 1.4 | 1.1 | 2.4 | 1.4 | 0.3 |
| small | HARD | 0.9 | 1.7 | 2.0 | 1.7 | 0.2 |
| large | NORMAL | 2.3 | 1.3 | 3.8 | 1.5 | 0.4 |
| large | HARD | 1.7 | 2.2 | 3.9 | 2.3 | 0.3 |

#### Abilities (equal stats, 5/5/5/5): empty, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 35.1 s / 5.4 | 17.0 % / 0.0 % / 37.8 s / 5.4 | 46.5 % / 0.0 % / 37.2 s / 5.5 | 72.0 % / 0.0 % / 35.0 s / 5.0 | 45.2 % / 0.0 % / 36.7 s / 5.3 |
| **FLY** 5/5/5/5 | 83.0 % / 0.0 % / 37.8 s / 5.4 | 54.5 % / 0.0 % / 40.3 s / 5.7 | 64.5 % / 0.0 % / 40.1 s / 5.9 | 43.5 % / 0.0 % / 39.0 s / 5.2 | 63.7 % / 0.0 % / 39.0 s / 5.5 |
| **ROLL** 5/5/5/5 | 53.5 % / 0.0 % / 37.2 s / 5.5 | 35.5 % / 0.0 % / 40.1 s / 5.9 | 52.5 % / 0.0 % / 40.0 s / 5.7 | 32.0 % / 0.0 % / 39.8 s / 5.7 | 40.3 % / 0.0 % / 39.0 s / 5.7 |
| **HIDE** 5/5/5/5 | 28.0 % / 0.0 % / 35.0 s / 5.0 | 56.5 % / 0.0 % / 39.0 s / 5.2 | 68.0 % / 0.0 % / 39.8 s / 5.7 | 48.5 % / 0.0 % / 36.6 s / 4.8 | 50.8 % / 0.0 % / 37.9 s / 5.3 |

#### Abilities (equal stats, 5/5/5/5): empty, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 36.7 s / 5.9 | 5.5 % / 0.0 % / 34.9 s / 5.4 | 13.5 % / 0.0 % / 35.5 s / 5.7 | 55.0 % / 0.0 % / 33.5 s / 4.9 | 24.7 % / 0.0 % / 34.7 s / 5.3 |
| **FLY** 5/5/5/5 | 94.5 % / 0.0 % / 34.9 s / 5.4 | 54.5 % / 0.0 % / 42.7 s / 6.2 | 57.0 % / 0.0 % / 39.8 s / 6.0 | 44.5 % / 0.0 % / 42.2 s / 5.7 | 65.3 % / 0.0 % / 39.0 s / 5.7 |
| **ROLL** 5/5/5/5 | 86.5 % / 0.0 % / 35.5 s / 5.7 | 43.0 % / 0.0 % / 39.8 s / 6.0 | 52.5 % / 0.0 % / 39.2 s / 6.4 | 14.5 % / 0.0 % / 35.2 s / 5.2 | 48.0 % / 0.0 % / 36.8 s / 5.6 |
| **HIDE** 5/5/5/5 | 45.0 % / 0.0 % / 33.5 s / 4.9 | 55.5 % / 0.0 % / 42.2 s / 5.7 | 85.5 % / 0.0 % / 35.2 s / 5.2 | 46.5 % / 0.0 % / 41.2 s / 5.4 | 62.0 % / 0.0 % / 37.0 s / 5.2 |

#### Abilities (equal stats, 5/5/5/5): cup-and-book, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 39.2 s / 7.0 | 39.5 % / 0.0 % / 46.3 s / 7.2 | 59.0 % / 0.0 % / 40.2 s / 7.1 | 61.0 % / 0.0 % / 43.2 s / 7.9 | 53.2 % / 0.0 % / 43.3 s / 7.4 |
| **FLY** 5/5/5/5 | 60.5 % / 0.0 % / 46.3 s / 7.2 | 51.0 % / 0.0 % / 48.7 s / 7.3 | 50.5 % / 0.0 % / 43.7 s / 6.9 | 43.0 % / 0.0 % / 47.5 s / 7.2 | 51.3 % / 0.0 % / 45.9 s / 7.1 |
| **ROLL** 5/5/5/5 | 41.0 % / 0.0 % / 40.2 s / 7.1 | 49.5 % / 0.0 % / 43.7 s / 6.9 | 54.0 % / 0.0 % / 41.7 s / 6.6 | 36.0 % / 0.0 % / 41.9 s / 6.6 | 42.2 % / 0.0 % / 41.9 s / 6.9 |
| **HIDE** 5/5/5/5 | 39.0 % / 0.0 % / 43.2 s / 7.9 | 57.0 % / 0.0 % / 47.5 s / 7.2 | 64.0 % / 0.0 % / 41.9 s / 6.6 | 56.5 % / 0.0 % / 48.8 s / 7.2 | 53.3 % / 0.0 % / 44.2 s / 7.3 |

#### Abilities (equal stats, 5/5/5/5): cup-and-book, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 46.5 % / 0.0 % / 39.7 s / 7.0 | 30.0 % / 0.0 % / 45.6 s / 7.3 | 45.5 % / 0.0 % / 39.2 s / 7.3 | 54.5 % / 0.0 % / 42.6 s / 8.1 | 43.3 % / 0.0 % / 42.4 s / 7.6 |
| **FLY** 5/5/5/5 | 70.0 % / 0.0 % / 45.6 s / 7.3 | 56.0 % / 0.0 % / 47.8 s / 7.3 | 61.5 % / 0.0 % / 43.7 s / 6.8 | 50.0 % / 0.0 % / 47.8 s / 7.3 | 60.5 % / 0.0 % / 45.7 s / 7.1 |
| **ROLL** 5/5/5/5 | 54.5 % / 0.0 % / 39.2 s / 7.3 | 38.5 % / 0.0 % / 43.7 s / 6.8 | 53.5 % / 0.0 % / 42.0 s / 6.8 | 19.5 % / 0.0 % / 40.8 s / 6.1 | 37.5 % / 0.0 % / 41.2 s / 6.7 |
| **HIDE** 5/5/5/5 | 45.5 % / 0.0 % / 42.6 s / 8.1 | 50.0 % / 0.0 % / 47.8 s / 7.3 | 80.5 % / 0.0 % / 40.8 s / 6.1 | 52.5 % / 0.0 % / 48.2 s / 7.1 | 58.7 % / 0.0 % / 43.7 s / 7.2 |

#### Abilities (equal stats, 5/5/5/5): small, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 50.0 % / 0.0 % / 28.1 s / 4.0 | 11.5 % / 0.0 % / 29.0 s / 4.1 | 58.0 % / 0.0 % / 26.5 s / 3.4 | 59.0 % / 0.0 % / 29.2 s / 4.0 | 42.8 % / 0.0 % / 28.2 s / 3.8 |
| **FLY** 5/5/5/5 | 88.5 % / 0.0 % / 29.0 s / 4.1 | 49.0 % / 0.0 % / 33.1 s / 4.4 | 49.5 % / 0.0 % / 29.0 s / 3.5 | 56.5 % / 0.0 % / 31.9 s / 4.1 | 64.8 % / 0.0 % / 30.0 s / 3.9 |
| **ROLL** 5/5/5/5 | 42.0 % / 0.0 % / 26.5 s / 3.4 | 50.5 % / 0.0 % / 29.0 s / 3.5 | 44.0 % / 0.0 % / 25.9 s / 3.0 | 66.5 % / 0.0 % / 28.1 s / 3.5 | 53.0 % / 0.0 % / 27.9 s / 3.5 |
| **HIDE** 5/5/5/5 | 41.0 % / 0.0 % / 29.2 s / 4.0 | 43.5 % / 0.0 % / 31.9 s / 4.1 | 33.5 % / 0.0 % / 28.1 s / 3.5 | 49.0 % / 0.0 % / 30.9 s / 3.9 | 39.3 % / 0.0 % / 29.7 s / 3.9 |

#### Abilities (equal stats, 5/5/5/5): small, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 42.0 % / 0.0 % / 30.2 s / 3.8 | 3.5 % / 0.0 % / 28.5 s / 4.4 | 68.5 % / 0.0 % / 27.9 s / 3.3 | 44.5 % / 0.0 % / 28.0 s / 3.3 | 38.8 % / 0.0 % / 28.1 s / 3.7 |
| **FLY** 5/5/5/5 | 96.5 % / 0.0 % / 28.5 s / 4.4 | 53.5 % / 0.0 % / 33.8 s / 4.4 | 89.0 % / 0.0 % / 31.0 s / 3.9 | 65.0 % / 0.0 % / 30.9 s / 3.7 | 83.5 % / 0.0 % / 30.1 s / 4.0 |
| **ROLL** 5/5/5/5 | 31.5 % / 0.0 % / 27.9 s / 3.3 | 11.0 % / 0.0 % / 31.0 s / 3.9 | 56.0 % / 0.0 % / 29.3 s / 3.5 | 70.0 % / 0.0 % / 26.2 s / 3.2 | 37.5 % / 0.0 % / 28.4 s / 3.4 |
| **HIDE** 5/5/5/5 | 55.5 % / 0.0 % / 28.0 s / 3.3 | 35.0 % / 0.0 % / 30.9 s / 3.7 | 30.0 % / 0.0 % / 26.2 s / 3.2 | 53.0 % / 0.0 % / 28.4 s / 3.3 | 40.2 % / 0.0 % / 28.4 s / 3.4 |

#### Abilities (equal stats, 5/5/5/5): large, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 50.0 % / 0.0 % / 44.0 s / 6.8 | 21.0 % / 0.0 % / 47.5 s / 7.1 | 37.5 % / 0.0 % / 44.4 s / 6.5 | 65.0 % / 0.0 % / 44.6 s / 6.4 | 41.2 % / 0.0 % / 45.5 s / 6.7 |
| **FLY** 5/5/5/5 | 79.0 % / 0.0 % / 47.5 s / 7.1 | 48.0 % / 0.0 % / 50.8 s / 7.4 | 47.5 % / 0.0 % / 52.5 s / 7.7 | 49.0 % / 0.0 % / 48.7 s / 6.8 | 58.5 % / 0.0 % / 49.6 s / 7.2 |
| **ROLL** 5/5/5/5 | 62.5 % / 0.0 % / 44.4 s / 6.5 | 52.5 % / 0.0 % / 52.5 s / 7.7 | 49.5 % / 0.0 % / 42.5 s / 6.6 | 45.5 % / 0.0 % / 51.2 s / 7.5 | 53.5 % / 0.0 % / 49.3 s / 7.2 |
| **HIDE** 5/5/5/5 | 35.0 % / 0.0 % / 44.6 s / 6.4 | 51.0 % / 0.0 % / 48.7 s / 6.8 | 54.5 % / 0.0 % / 51.2 s / 7.5 | 47.5 % / 0.0 % / 46.7 s / 6.4 | 46.8 % / 0.0 % / 48.2 s / 6.9 |

#### Abilities (equal stats, 5/5/5/5): large, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 44.5 % / 0.0 % / 49.8 s / 7.8 | 1.0 % / 0.0 % / 41.3 s / 7.2 | 9.0 % / 0.0 % / 39.9 s / 6.6 | 51.0 % / 0.0 % / 44.1 s / 6.5 | 20.3 % / 0.0 % / 41.8 s / 6.7 |
| **FLY** 5/5/5/5 | 99.0 % / 0.0 % / 41.3 s / 7.2 | 45.5 % / 0.0 % / 54.4 s / 8.0 | 36.0 % / 0.0 % / 50.6 s / 7.9 | 42.5 % / 0.0 % / 50.7 s / 7.2 | 59.2 % / 0.0 % / 47.5 s / 7.5 |
| **ROLL** 5/5/5/5 | 91.0 % / 0.0 % / 39.9 s / 6.6 | 64.0 % / 0.0 % / 50.6 s / 7.9 | 55.5 % / 0.0 % / 41.4 s / 6.7 | 30.5 % / 0.0 % / 47.3 s / 6.9 | 61.8 % / 0.0 % / 45.9 s / 7.1 |
| **HIDE** 5/5/5/5 | 49.0 % / 0.0 % / 44.1 s / 6.5 | 57.5 % / 0.0 % / 50.7 s / 7.2 | 69.5 % / 0.0 % / 47.3 s / 6.9 | 44.5 % / 0.0 % / 51.0 s / 7.1 | 58.7 % / 0.0 % / 47.4 s / 6.9 |

#### Abilities (archetype stats): empty, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 35.1 s / 5.4 | 42.5 % / 0.0 % / 40.8 s / 5.8 | 47.5 % / 0.0 % / 38.0 s / 5.5 | 65.5 % / 0.0 % / 32.6 s / 4.9 | 51.8 % / 0.0 % / 37.1 s / 5.4 |
| **FLY** 4/6/4/6 | 57.5 % / 0.0 % / 40.8 s / 5.8 | 52.5 % / 0.0 % / 41.0 s / 5.4 | 15.0 % / 0.0 % / 34.9 s / 4.6 | 30.0 % / 0.0 % / 37.0 s / 5.2 | 34.2 % / 0.0 % / 37.5 s / 5.2 |
| **ROLL** 7/6/5/2 | 52.5 % / 0.0 % / 38.0 s / 5.5 | 85.0 % / 0.0 % / 34.9 s / 4.6 | 49.0 % / 0.0 % / 39.6 s / 5.1 | 67.0 % / 0.0 % / 35.9 s / 4.8 | 68.2 % / 0.0 % / 36.2 s / 5.0 |
| **HIDE** 4/5/4/7 | 34.5 % / 0.0 % / 32.6 s / 4.9 | 70.0 % / 0.0 % / 37.0 s / 5.2 | 33.0 % / 0.0 % / 35.9 s / 4.8 | 57.0 % / 0.0 % / 35.1 s / 4.7 | 45.8 % / 0.0 % / 35.2 s / 4.9 |

#### Abilities (archetype stats): empty, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 36.7 s / 5.9 | 19.5 % / 0.0 % / 37.8 s / 5.6 | 13.5 % / 0.0 % / 37.8 s / 6.0 | 43.0 % / 0.0 % / 33.7 s / 4.9 | 25.3 % / 0.0 % / 36.5 s / 5.5 |
| **FLY** 4/6/4/6 | 80.5 % / 0.0 % / 37.8 s / 5.6 | 55.0 % / 0.0 % / 43.7 s / 6.5 | 49.0 % / 0.0 % / 40.3 s / 5.7 | 31.5 % / 0.0 % / 39.2 s / 5.7 | 53.7 % / 0.0 % / 39.1 s / 5.7 |
| **ROLL** 7/6/5/2 | 86.5 % / 0.0 % / 37.8 s / 6.0 | 51.0 % / 0.0 % / 40.3 s / 5.7 | 49.5 % / 0.0 % / 40.8 s / 6.0 | 18.0 % / 0.0 % / 38.2 s / 5.3 | 51.8 % / 0.0 % / 38.8 s / 5.7 |
| **HIDE** 4/5/4/7 | 57.0 % / 0.0 % / 33.7 s / 4.9 | 68.5 % / 0.0 % / 39.2 s / 5.7 | 82.0 % / 0.0 % / 38.2 s / 5.3 | 47.0 % / 0.0 % / 36.9 s / 5.1 | 69.2 % / 0.0 % / 37.0 s / 5.3 |

#### Abilities (archetype stats): cup-and-book, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 39.2 s / 7.0 | 48.0 % / 0.0 % / 45.9 s / 7.3 | 47.5 % / 0.0 % / 39.1 s / 6.7 | 59.0 % / 0.0 % / 42.8 s / 8.0 | 51.5 % / 0.0 % / 42.6 s / 7.4 |
| **FLY** 4/6/4/6 | 52.0 % / 0.0 % / 45.9 s / 7.3 | 48.0 % / 0.0 % / 46.3 s / 7.3 | 23.0 % / 0.0 % / 40.9 s / 6.4 | 44.0 % / 0.0 % / 47.1 s / 7.3 | 39.7 % / 0.0 % / 44.6 s / 7.0 |
| **ROLL** 7/6/5/2 | 52.5 % / 0.0 % / 39.1 s / 6.7 | 77.0 % / 0.0 % / 40.9 s / 6.4 | 60.0 % / 0.0 % / 42.0 s / 6.3 | 70.5 % / 0.0 % / 40.8 s / 6.2 | 66.7 % / 0.0 % / 40.3 s / 6.4 |
| **HIDE** 4/5/4/7 | 41.0 % / 0.0 % / 42.8 s / 8.0 | 56.0 % / 0.0 % / 47.1 s / 7.3 | 29.5 % / 0.0 % / 40.8 s / 6.2 | 52.5 % / 0.0 % / 46.2 s / 7.0 | 42.2 % / 0.0 % / 43.6 s / 7.2 |

#### Abilities (archetype stats): cup-and-book, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 46.5 % / 0.0 % / 39.7 s / 7.0 | 46.0 % / 0.0 % / 45.9 s / 7.7 | 38.5 % / 0.0 % / 41.9 s / 7.2 | 56.5 % / 0.0 % / 44.7 s / 8.7 | 47.0 % / 0.0 % / 44.1 s / 7.9 |
| **FLY** 4/6/4/6 | 54.0 % / 0.0 % / 45.9 s / 7.7 | 48.0 % / 0.0 % / 48.8 s / 7.4 | 33.0 % / 0.0 % / 43.5 s / 6.7 | 52.5 % / 0.0 % / 47.1 s / 7.1 | 46.5 % / 0.0 % / 45.5 s / 7.2 |
| **ROLL** 7/6/5/2 | 61.5 % / 0.0 % / 41.9 s / 7.2 | 67.0 % / 0.0 % / 43.5 s / 6.7 | 51.5 % / 0.0 % / 40.4 s / 6.2 | 47.5 % / 0.0 % / 40.7 s / 6.3 | 58.7 % / 0.0 % / 42.0 s / 6.7 |
| **HIDE** 4/5/4/7 | 43.5 % / 0.0 % / 44.7 s / 8.7 | 47.5 % / 0.0 % / 47.1 s / 7.1 | 52.5 % / 0.0 % / 40.7 s / 6.3 | 49.5 % / 0.0 % / 45.0 s / 6.9 | 47.8 % / 0.0 % / 44.2 s / 7.4 |

#### Abilities (archetype stats): small, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 50.0 % / 0.0 % / 28.1 s / 4.0 | 26.5 % / 0.0 % / 30.1 s / 4.1 | 54.5 % / 0.0 % / 25.0 s / 3.1 | 40.0 % / 0.0 % / 27.8 s / 4.0 | 40.3 % / 0.0 % / 27.6 s / 3.7 |
| **FLY** 4/6/4/6 | 73.5 % / 0.0 % / 30.1 s / 4.1 | 47.0 % / 0.0 % / 30.7 s / 4.0 | 24.0 % / 0.0 % / 25.6 s / 3.1 | 43.0 % / 0.0 % / 30.2 s / 3.9 | 46.8 % / 0.0 % / 28.7 s / 3.7 |
| **ROLL** 7/6/5/2 | 45.5 % / 0.0 % / 25.0 s / 3.1 | 76.0 % / 0.0 % / 25.6 s / 3.1 | 54.0 % / 0.0 % / 25.1 s / 2.8 | 73.0 % / 0.0 % / 25.1 s / 3.0 | 64.8 % / 0.0 % / 25.3 s / 3.1 |
| **HIDE** 4/5/4/7 | 60.0 % / 0.0 % / 27.8 s / 4.0 | 57.0 % / 0.0 % / 30.2 s / 3.9 | 27.0 % / 0.0 % / 25.1 s / 3.0 | 43.0 % / 0.0 % / 28.3 s / 3.7 | 48.0 % / 0.0 % / 27.7 s / 3.6 |

#### Abilities (archetype stats): small, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 42.0 % / 0.0 % / 30.2 s / 3.8 | 1.5 % / 0.0 % / 25.7 s / 4.1 | 86.0 % / 0.0 % / 28.9 s / 3.7 | 1.5 % / 0.0 % / 24.2 s / 3.4 | 29.7 % / 0.0 % / 26.3 s / 3.7 |
| **FLY** 4/6/4/6 | 98.5 % / 0.0 % / 25.7 s / 4.1 | 53.0 % / 0.0 % / 32.5 s / 4.3 | 73.0 % / 0.0 % / 30.9 s / 3.9 | 80.5 % / 0.0 % / 29.0 s / 3.9 | 84.0 % / 0.0 % / 28.5 s / 3.9 |
| **ROLL** 7/6/5/2 | 14.0 % / 0.0 % / 28.9 s / 3.7 | 27.0 % / 0.0 % / 30.9 s / 3.9 | 50.5 % / 0.0 % / 25.2 s / 2.5 | 50.5 % / 0.0 % / 23.7 s / 2.9 | 30.5 % / 0.0 % / 27.8 s / 3.5 |
| **HIDE** 4/5/4/7 | 98.5 % / 0.0 % / 24.2 s / 3.4 | 19.5 % / 0.0 % / 29.0 s / 3.9 | 49.5 % / 0.0 % / 23.7 s / 2.9 | 48.5 % / 0.0 % / 26.4 s / 3.3 | 55.8 % / 0.0 % / 25.6 s / 3.4 |

#### Abilities (archetype stats): large, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 50.0 % / 0.0 % / 44.0 s / 6.8 | 36.0 % / 0.0 % / 53.4 s / 7.8 | 25.5 % / 0.0 % / 43.6 s / 6.5 | 65.5 % / 0.0 % / 42.6 s / 6.5 | 42.3 % / 0.0 % / 46.5 s / 6.9 |
| **FLY** 4/6/4/6 | 64.0 % / 0.0 % / 53.4 s / 7.8 | 51.0 % / 0.0 % / 48.5 s / 7.1 | 11.0 % / 0.0 % / 40.9 s / 5.9 | 29.5 % / 0.0 % / 48.0 s / 6.9 | 34.8 % / 0.0 % / 47.4 s / 6.9 |
| **ROLL** 7/6/5/2 | 74.5 % / 0.0 % / 43.6 s / 6.5 | 89.0 % / 0.0 % / 40.9 s / 5.9 | 46.5 % / 0.0 % / 43.7 s / 6.2 | 88.5 % / 0.0 % / 46.2 s / 6.5 | 84.0 % / 0.0 % / 43.6 s / 6.3 |
| **HIDE** 4/5/4/7 | 34.5 % / 0.0 % / 42.6 s / 6.5 | 70.5 % / 0.0 % / 48.0 s / 6.9 | 11.5 % / 0.0 % / 46.2 s / 6.5 | 48.0 % / 0.0 % / 42.0 s / 6.0 | 38.8 % / 0.0 % / 45.6 s / 6.6 |

#### Abilities (archetype stats): large, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 44.5 % / 0.0 % / 49.8 s / 7.8 | 11.0 % / 0.0 % / 45.3 s / 7.2 | 3.5 % / 0.0 % / 39.3 s / 6.7 | 44.0 % / 0.0 % / 42.3 s / 6.4 | 19.5 % / 0.0 % / 42.3 s / 6.8 |
| **FLY** 4/6/4/6 | 89.0 % / 0.0 % / 45.3 s / 7.2 | 45.0 % / 0.0 % / 54.7 s / 8.4 | 20.5 % / 0.0 % / 45.7 s / 7.1 | 35.0 % / 0.0 % / 49.6 s / 7.5 | 48.2 % / 0.0 % / 46.9 s / 7.3 |
| **ROLL** 7/6/5/2 | 96.5 % / 0.0 % / 39.3 s / 6.7 | 79.5 % / 0.0 % / 45.7 s / 7.1 | 50.0 % / 0.0 % / 44.2 s / 6.7 | 77.0 % / 0.0 % / 54.1 s / 7.7 | 84.3 % / 0.0 % / 46.4 s / 7.2 |
| **HIDE** 4/5/4/7 | 56.0 % / 0.0 % / 42.3 s / 6.4 | 65.0 % / 0.0 % / 49.6 s / 7.5 | 23.0 % / 0.0 % / 54.1 s / 7.7 | 45.5 % / 0.2 % / 47.3 s / 6.8 | 48.0 % / 0.0 % / 48.7 s / 7.2 |

#### Stats: empty, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 29.0 % / 0.0 % | 46.0 % / 0.0 % | 49.5 % / 0.0 % | 46.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 61.0 % / 0.0 % | 22.5 % / 0.0 % | 72.5 % / 0.0 % | 51.5 % / 0.0 % | 99.5 % / 0.0 % |
| ROLL | 64.0 % / 0.0 % | 20.5 % / 0.0 % | 63.0 % / 0.0 % | 59.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 45.5 % / 0.0 % | 33.0 % / 0.0 % | 57.5 % / 0.0 % | 70.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: empty, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 35.5 % / 0.0 % | 51.0 % / 0.0 % | 40.0 % / 0.0 % | 43.5 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 55.0 % / 0.0 % | 9.0 % / 0.0 % | 57.5 % / 0.0 % | 34.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 61.0 % / 0.0 % | 15.0 % / 0.0 % | 43.5 % / 0.0 % | 26.5 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 19.5 % / 0.0 % | 9.0 % / 0.0 % | 47.0 % / 0.0 % | 45.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: cup-and-book, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 45.0 % / 0.0 % | 53.5 % / 0.0 % | 53.5 % / 0.0 % | 35.5 % / 0.0 % | 98.0 % / 0.0 % |
| FLY | 56.0 % / 0.0 % | 35.0 % / 0.0 % | 62.0 % / 0.0 % | 32.0 % / 0.0 % | 95.5 % / 0.0 % |
| ROLL | 36.0 % / 0.0 % | 44.0 % / 0.0 % | 49.5 % / 0.0 % | 30.0 % / 0.0 % | 96.5 % / 0.0 % |
| HIDE | 49.0 % / 0.0 % | 49.0 % / 0.0 % | 55.5 % / 0.0 % | 33.0 % / 0.0 % | 96.5 % / 0.0 % |

#### Stats: cup-and-book, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 50.5 % / 0.0 % | 57.0 % / 0.0 % | 48.0 % / 0.0 % | 38.5 % / 0.0 % | 98.0 % / 0.0 % |
| FLY | 65.5 % / 0.0 % | 48.0 % / 0.0 % | 67.0 % / 0.0 % | 34.0 % / 0.0 % | 96.0 % / 0.7 % |
| ROLL | 41.5 % / 0.0 % | 41.0 % / 0.0 % | 53.0 % / 0.0 % | 23.5 % / 0.0 % | 98.0 % / 0.0 % |
| HIDE | 50.5 % / 0.0 % | 41.5 % / 0.0 % | 55.5 % / 0.0 % | 34.0 % / 0.0 % | 92.5 % / 0.0 % |

#### Stats: small, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 43.0 % / 0.0 % | 24.5 % / 0.0 % | 51.5 % / 0.0 % | 58.5 % / 0.0 % | 99.5 % / 0.0 % |
| FLY | 67.0 % / 0.0 % | 17.0 % / 0.0 % | 67.5 % / 0.0 % | 59.5 % / 0.0 % | 92.0 % / 0.0 % |
| ROLL | 13.5 % / 0.0 % | 48.5 % / 0.0 % | 21.5 % / 0.0 % | 71.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 52.0 % / 0.0 % | 40.5 % / 0.0 % | 62.0 % / 0.0 % | 75.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: small, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 18.0 % / 0.0 % | 37.5 % / 0.0 % | 22.0 % / 0.0 % | 56.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 41.5 % / 0.0 % | 19.5 % / 0.0 % | 57.0 % / 0.0 % | 33.0 % / 0.0 % | 80.0 % / 0.0 % |
| ROLL | 30.5 % / 0.0 % | 27.0 % / 0.0 % | 41.0 % / 0.0 % | 77.0 % / 0.0 % | 99.5 % / 0.0 % |
| HIDE | 7.0 % / 0.0 % | 27.0 % / 0.0 % | 9.5 % / 0.0 % | 79.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: large, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 36.5 % / 0.0 % | 69.5 % / 0.0 % | 43.0 % / 0.0 % | 50.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 54.5 % / 0.0 % | 77.0 % / 0.0 % | 61.5 % / 0.0 % | 40.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 70.0 % / 0.0 % | 63.0 % / 0.0 % | 52.0 % / 0.0 % | 39.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 34.5 % / 0.0 % | 83.5 % / 0.6 % | 46.0 % / 0.0 % | 59.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: large, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 29.5 % / 0.0 % | 57.5 % / 0.0 % | 34.0 % / 0.0 % | 37.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 47.0 % / 0.0 % | 68.0 % / 0.0 % | 57.0 % / 0.0 % | 33.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 71.0 % / 0.0 % | 41.5 % / 0.0 % | 41.5 % / 0.0 % | 37.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 27.0 % / 0.2 % | 68.5 % / 0.6 % | 42.0 % / 0.0 % | 46.5 % / 0.0 % | 100.0 % / 0.0 % |

## T48

The duel's balance by measurement (ai/tasks/T48-cli-duel-sim-balance.md, gate G4). Every table below is generated by
`./gradlew :cli:run --args="duel matrix --n 200 --bundle docs/bundled --out build/duel"` (`build/duel/matrix.md`, copied
here with its headings one level down): seeded bot-vs-bot matches of `:core` (`DuelMatrix.play` = `DuelSim`; a cell is
exactly `duel series --seed 1 --n 200` of its two fighters), both sides the same bot level, n = 200 per cell, every
match replayed at 16 ms frames for A8. Same arguments, same table. Nothing calls a model or reads a key.

**Rule change: wobble is on.** Every hit a fighter takes in a round makes the next one push it farther: + 1 wobble per
hit taken (a clash counts for both), the knockback x (1 + 0.7 x wobble), wobble at most 4, back to 0 when a round
starts. At 5 v 5 the hits of a round slide a fighter 4.5, 7.7, 10.8, 14.0, then 17.1 cm. Nobody loses by wobble; it only
brings the edge closer. It was needed because numbers alone could not end rounds on the 120 x 60 cm table without a
one-hit ring-out on the 30 x 30 cm one: with no wobble, at every knock speed up to 0.37 m/s (6.7 cm, the most an even
hit may slide from the small table's mark, 7 cm from its edge) and every dash cooldown from 0.7 to 1.5 s, two HARD bots
on the large table needed 12.4 to 33.9 hits per ring-out (A4 asks for at most 8). Since the review the knockback and
dash moves are swept (pieces of at most half a radius), so a hit at the wobble cap (up to 4.9 m/s, 3.8 cm in one 8 ms
sub-step) can no longer carry a fighter through a thin object; `1 + wobbleKnockScale * wobbleMax` is capped at 10.

## Verdicts (n = 200, NORMAL v NORMAL and HARD v HARD)

A4 and A5 are judged on every ability pair (the arena averages, in brackets, would hide failing pairs). "Before" is the
T42 / T45 / T55 numbers run through today's code (`--params` / `--tuning` files: wobble 0, where the sweep changes
nothing, bit for bit).

| # | Criterion | Threshold | Before | After | Verdict |
|---|---|---|---|---|---|
| A1 | each ability against the field | 35..65 % | 2.7..95.5 % (20 of 32 out) | 7.2..87.7 % (17 out) | FAIL |
| A2 | every matchup, each side | 25..75 % | 0..100 % (76 of 96 out) | 0..100 % (46 out) | FAIL |
| A3 | rounds ended by the clock, per pair | <= 25 % (large <= 40 %) | 0..97.8 % (24 of 80 out) | 0..1.9 % | PASS |
| A4 | hits per ring-out, per pair | 2..8 | 3.1..2826 (66 out; arenas 12.4..57.3) | 2.5..9.4 (7 out; arenas 3.5..6.7) | FAIL |
| A5 | mean match length, per pair | 30..120 s | 18.5..161.3 s (24 out; arenas 43.2..97.7) | 15.3..62.4 s (28 out; arenas 23.7..44.3) | FAIL |
| A6 | specialist 8/4/4/4 v 5/5/5/5 | 40..65 % | 0..99.5 % (89 of 128 out) | 6.0..86.0 % (45 out) | FAIL |
| A7 | 8/8/8/8 v 5/5/5/5 | >= 65 % | 40..100 % (3 of 32 out) | 32..100 % (3 out, all on cup-and-book) | FAIL |
| A8 | 16 ms and 33 ms frames | identical | 57 600 of 57 600 | 57 600 of 57 600 | PASS |
| A9 | every core/duel test green | - | 90 | 102 (16 updated, see the brief) | PASS |

What still fails, and why (the measurements behind it are in the brief's candidate log):
- **A4: seven pairs need a little more than 8 hits.** Six on the large table, FLY in each: JUMP v FLY 9.4 (NORMAL) / 8.7
  (HARD), FLY v FLY 8.4 / 8.3, FLY v HIDE 8.3 / 8.4; and the HARD JUMP mirror on cup-and-book, 8.1. Every arena's
  average is 3.5..6.7; before T48 every arena's was 12.4..57.3 and 66 pairs were out.
- **A5: short matches where a ring-out is quick.** 28 pairs under 30 s: 16 on the small table (all its pairs but three;
  the shortest JUMP v ROLL, 15.3 s), 8 on the empty table (every pair with ROLL), 3 on the large one, 1 on cup-and-book.
- **A1 / A2: FLY's stat line and ROLL.** ROLL takes 66.5..87.7 % of the field on the empty, small and large tables
  (29.8 % on cup-and-book at NORMAL), FLY 7.2..26.8 % on the empty, small and large ones. Separated with `duel series`
  (n = 200, NORMAL): at equal stats 5/5/5/5 the abilities are within 36..64 % of each other on the empty and the large
  table (JUMP v FLY 55 / 45 and 41.5 / 58.5, HIDE v JUMP 64 / 36 on both, FLY v HIDE 46 / 54 and 47 / 53), except ROLL
  v JUMP on the empty table, 80.5 / 19.5: there the ram (a hit that does not wait for the dash cooldown) wins it; on the
  large table ROLL is even at equal stats (47.5 / 52.5), so its 73.7..87.7 % there come from its 7/6/5/2 line. FLY's
  archetype 3/7/3/7 (domain `FighterStats.defaultFor`, not T48's) on a JUMP wins 23.5 % (empty) and 36.5 % (large)
  against 5/5/5/5: SPEED and AGILITY buy a bot less than POWER and GUARD cost.
- **A6: SPEED.** A SPEED 8 specialist is 6.0..50.5 % on the empty, cup-and-book and small tables and 40.5..86 % on the
  large one: walking fast barely decides who dashes first (both reach range at once) except on a big table. GUARD 8
  spans 35..75.5 %, AGILITY 8 22..75 %, POWER 8 38.5..66 %.
- **A7: cup-and-book.** 8/8/8/8 loses or barely wins there with ROLL at NORMAL (32.0 %) and with HIDE (59.5 and
  57.0 %); its other cells there are 66..88.5 %, and on the other three tables it wins 88..100 %. Not isolated.
- **The bot levels per ability** (the strength tests' seeds, n = 50, HARD v NORMAL, empty / cup-and-book): JUMP 78 / 66,
  FLY 66 / 76, ROLL 90 / 70, HIDE 86 / 80 (`normalIsBetweenEasyAndHard` asserts each >= 60 %).
- **A counter can be a one-hit ring-out on the small table.** At `counterKnockScale` 2.0 an even HIDE counter slides
  about 9 cm, more than the 7 cm from the small table's mark to its edge (at the old 1.5 x: 6.75 cm).

## After: the T48 defaults (wobble on, review fixes in), 2026-11-11

80.2 s on 4 threads (57 600 matches + the same 57 600 replayed at 16 ms, 1437 matches/s).

`duel matrix --n 200 --seed 1 --level normal,hard --arenas empty,cup-and-book,small,large --groups ability,stat --bundle docs/bundled --step-ms 33 --check-n 200 --check-step-ms 16`

Numbers: the defaults. 200 matches per cell (seeds 1..200, marks swapped every other match from the first, as duel series does), both sides bots of the same level, 33 ms frames; A8 replays 200 per cell at 16 ms. Clock = share of the rounds ended by the round clock; hits = Hit + Clash events per ring-out (all rounds' hits); s = mean match length.

### Acceptance

| # | Criterion | Threshold | Measured | Verdict |
|---|---|---|---|---|
| A1 | each ability against the field | 35.0 %..65.0 % | 7.2 %..87.7 % | FAIL (17 out) |
| A2 | every matchup, each side | 25.0 %..75.0 % | 0.0 %..100.0 % | FAIL (46 out) |
| A3 | rounds ended by the clock, per ability pair | <= 25.0 % (large: <= 40.0 %) | 0.0 %..1.9 % | PASS (0 out) |
| A4 | hits per ring-out, per ability pair | 2.0..8.0 | per pair 2.5..9.4 (per arena 3.5..6.7) | FAIL (7 out) |
| A5 | mean match length, per ability pair | 30.0..120.0 s | per pair 15.3 s..62.4 s (per arena 23.7 s..44.3 s) | FAIL (28 out) |
| A6 | a specialist (8/4/4/4) against 5/5/5/5 | 40.0 %..65.0 % | 6.0 %..86.0 % | FAIL (45 out) |
| A7 | 8/8/8/8 against 5/5/5/5 | >= 65.0 % | 32.0 %..100.0 % | FAIL (3 out) |
| A8 | the same matches at another frame length | identical | identical (57600 matches replayed at 16 ms, 0 different) | PASS (0 out) |
| A9 | every core/duel test green | - | not measured here: `scripts/verify.sh` | - |

- A1 out of 35.0 %..65.0 %: empty NORMAL FLY 15.0 %; empty NORMAL ROLL 87.0 %; empty HARD FLY 7.2 %; empty HARD ROLL 87.3 %; cup-and-book NORMAL ROLL 29.8 %; cup-and-book HARD JUMP 72.0 %; small NORMAL JUMP 33.0 %; small NORMAL FLY 12.2 %; small NORMAL ROLL 87.0 %; small NORMAL HIDE 67.8 %; small HARD FLY 26.8 %; small HARD ROLL 66.5 %; small HARD HIDE 67.0 %; large NORMAL ROLL 73.7 %; large HARD JUMP 34.5 %; large HARD FLY 23.2 %; and 1 more
- A2 out of 25.0 %..75.0 %: empty NORMAL JUMP v ROLL 22.0 %; empty NORMAL ROLL v JUMP 78.0 %; empty NORMAL FLY v ROLL 2.0 %; empty NORMAL ROLL v FLY 98.0 %; empty NORMAL FLY v HIDE 16.5 %; empty NORMAL HIDE v FLY 83.5 %; empty NORMAL ROLL v HIDE 85.0 %; empty NORMAL HIDE v ROLL 15.0 %; empty HARD JUMP v FLY 87.5 %; empty HARD FLY v JUMP 12.5 %; empty HARD FLY v ROLL 0.0 %; empty HARD ROLL v FLY 100.0 %; empty HARD FLY v HIDE 9.0 %; empty HARD HIDE v FLY 91.0 %; empty HARD ROLL v HIDE 89.5 %; empty HARD HIDE v ROLL 10.5 %; and 30 more
- A4 out of 2.0..8.0: cup-and-book HARD JUMP v JUMP 8.1; large NORMAL JUMP v FLY 9.4; large NORMAL FLY v FLY 8.4; large NORMAL FLY v HIDE 8.3; large HARD JUMP v FLY 8.7; large HARD FLY v FLY 8.3; large HARD FLY v HIDE 8.4
- A5 out of 30.0..120.0 s: empty NORMAL JUMP v ROLL 25.1 s; empty NORMAL FLY v ROLL 25.6 s; empty NORMAL ROLL v ROLL 26.8 s; empty NORMAL ROLL v HIDE 24.5 s; empty HARD JUMP v ROLL 25.0 s; empty HARD FLY v ROLL 22.6 s; empty HARD ROLL v ROLL 26.7 s; empty HARD ROLL v HIDE 22.5 s; cup-and-book NORMAL ROLL v ROLL 24.8 s; small NORMAL JUMP v JUMP 23.3 s; small NORMAL JUMP v FLY 29.0 s; small NORMAL JUMP v ROLL 15.3 s; small NORMAL JUMP v HIDE 24.4 s; small NORMAL FLY v ROLL 18.3 s; small NORMAL FLY v HIDE 26.7 s; small NORMAL ROLL v ROLL 18.5 s; and 12 more
- A6 out of 40.0 %..65.0 %: empty NORMAL JUMP SPEED 26.5 %; empty NORMAL FLY POWER 65.5 %; empty NORMAL FLY SPEED 23.5 %; empty NORMAL FLY GUARD 74.0 %; empty NORMAL ROLL SPEED 39.5 %; empty NORMAL ROLL AGILITY 68.0 %; empty NORMAL HIDE SPEED 36.5 %; empty NORMAL HIDE GUARD 67.0 %; empty HARD JUMP SPEED 36.0 %; empty HARD FLY SPEED 26.0 %; empty HARD FLY AGILITY 38.5 %; empty HARD ROLL SPEED 28.0 %; empty HARD HIDE SPEED 29.5 %; empty HARD HIDE AGILITY 37.0 %; cup-and-book NORMAL ROLL SPEED 22.5 %; cup-and-book NORMAL ROLL AGILITY 37.0 %; and 29 more
- A7 out of >= 65.0 %: cup-and-book NORMAL ROLL 8/8/8/8 32.0 %; cup-and-book NORMAL HIDE 8/8/8/8 59.5 %; cup-and-book HARD HIDE 8/8/8/8 57.0 %

### Overview (ability cells)

| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |
|---|---|---|---|---|---|---|---|---|---|---|
| empty | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 32.5 | 24.5 / 42.1 | 4.9 | FLY v FLY 6.3 | 2.4 | 0.0 | 3.5 |
| empty | HARD | 0.0 % | JUMP v JUMP 0.0 % | 30.5 | 22.5 / 44.8 | 4.8 | FLY v FLY 6.9 | 2.4 | 0.0 | 3.3 |
| cup-and-book | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 35.4 | 24.8 / 45.7 | 6.1 | JUMP v JUMP 6.9 | 2.4 | 5.1 | 4.2 |
| cup-and-book | HARD | 0.0 % | JUMP v JUMP 0.0 % | 37.6 | 30.3 / 46.5 | 6.5 | JUMP v JUMP 8.1 | 2.5 | 5.6 | 4.8 |
| small | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 23.7 | 15.3 / 35.1 | 3.5 | FLY v FLY 5.0 | 2.4 | 0.0 | 3.3 |
| small | HARD | 0.0 % | JUMP v JUMP 0.0 % | 26.0 | 19.4 / 38.2 | 3.5 | FLY v FLY 5.5 | 2.5 | 0.0 | 3.4 |
| large | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 44.3 | 29.1 / 62.4 | 6.7 | JUMP v FLY 9.4 | 2.5 | 0.0 | 4.1 |
| large | HARD | 0.3 % | ROLL v ROLL 1.9 % | 42.2 | 29.8 / 58.2 | 6.7 | JUMP v FLY 8.7 | 2.4 | 0.0 | 3.4 |

### Abilities: empty, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 52.0 % / 0.0 % / 31.4 s / 5.1 | 73.5 % / 0.0 % / 41.7 s / 6.1 | 22.0 % / 0.0 % / 25.1 s / 3.9 | 40.0 % / 0.0 % / 34.6 s / 5.1 | 45.2 % / 0.0 % / 33.8 s / 5.0 |
| **FLY** 3/7/3/7 | 26.5 % / 0.0 % / 41.7 s / 6.1 | 45.0 % / 0.0 % / 42.1 s / 6.3 | 2.0 % / 0.0 % / 25.6 s / 4.2 | 16.5 % / 0.0 % / 38.1 s / 5.8 | 15.0 % / 0.0 % / 35.1 s / 5.4 |
| **ROLL** 7/6/5/2 | 78.0 % / 0.0 % / 25.1 s / 3.9 | 98.0 % / 0.0 % / 25.6 s / 4.2 | 51.5 % / 0.0 % / 26.8 s / 3.6 | 85.0 % / 0.0 % / 24.5 s / 3.6 | 87.0 % / 0.0 % / 25.1 s / 3.9 |
| **HIDE** 4/5/4/7 | 60.0 % / 0.0 % / 34.6 s / 5.1 | 83.5 % / 0.0 % / 38.1 s / 5.8 | 15.0 % / 0.0 % / 24.5 s / 3.6 | 51.0 % / 0.0 % / 35.0 s / 5.1 | 52.8 % / 0.0 % / 32.4 s / 4.8 |

### Abilities: empty, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 51.5 % / 0.0 % / 32.0 s / 5.3 | 87.5 % / 0.0 % / 34.8 s / 5.7 | 27.5 % / 0.0 % / 25.0 s / 3.8 | 40.5 % / 0.0 % / 32.0 s / 5.1 | 51.8 % / 0.0 % / 30.6 s / 4.8 |
| **FLY** 3/7/3/7 | 12.5 % / 0.0 % / 34.8 s / 5.7 | 49.5 % / 0.0 % / 44.8 s / 6.9 | 0.0 % / 0.0 % / 22.6 s / 4.1 | 9.0 % / 0.0 % / 33.1 s / 5.2 | 7.2 % / 0.0 % / 30.2 s / 5.0 |
| **ROLL** 7/6/5/2 | 72.5 % / 0.0 % / 25.0 s / 3.8 | 100.0 % / 0.0 % / 22.6 s / 4.1 | 58.5 % / 0.0 % / 26.7 s / 3.8 | 89.5 % / 0.0 % / 22.5 s / 3.6 | 87.3 % / 0.0 % / 23.3 s / 3.8 |
| **HIDE** 4/5/4/7 | 59.5 % / 0.0 % / 32.0 s / 5.1 | 91.0 % / 0.0 % / 33.1 s / 5.2 | 10.5 % / 0.0 % / 22.5 s / 3.6 | 53.0 % / 0.0 % / 31.5 s / 4.5 | 53.7 % / 0.0 % / 29.2 s / 4.6 |

### Abilities: cup-and-book, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 51.0 % / 0.0 % / 35.6 s / 6.9 | 42.5 % / 0.0 % / 45.7 s / 6.9 | 96.5 % / 0.0 % / 30.1 s / 6.9 | 56.0 % / 0.0 % / 35.5 s / 6.4 | 65.0 % / 0.0 % / 37.1 s / 6.7 |
| **FLY** 3/7/3/7 | 57.5 % / 0.0 % / 45.7 s / 6.9 | 51.0 % / 0.0 % / 40.4 s / 6.4 | 43.0 % / 0.0 % / 33.0 s / 5.8 | 49.0 % / 0.0 % / 40.5 s / 6.3 | 49.8 % / 0.0 % / 39.7 s / 6.3 |
| **ROLL** 7/6/5/2 | 3.5 % / 0.0 % / 30.1 s / 6.9 | 57.0 % / 0.0 % / 33.0 s / 5.8 | 48.0 % / 0.0 % / 24.8 s / 4.1 | 29.0 % / 0.0 % / 31.4 s / 5.7 | 29.8 % / 0.0 % / 31.5 s / 6.1 |
| **HIDE** 4/5/4/7 | 44.0 % / 0.0 % / 35.5 s / 6.4 | 51.0 % / 0.0 % / 40.5 s / 6.3 | 71.0 % / 0.0 % / 31.4 s / 5.7 | 49.5 % / 0.0 % / 37.5 s / 6.1 | 55.3 % / 0.0 % / 35.8 s / 6.2 |

### Abilities: cup-and-book, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.0 % / 0.0 % / 38.8 s / 8.1 | 64.0 % / 0.0 % / 46.5 s / 7.5 | 91.0 % / 0.0 % / 32.0 s / 7.0 | 61.0 % / 0.0 % / 33.2 s / 6.3 | 72.0 % / 0.0 % / 37.2 s / 6.9 |
| **FLY** 3/7/3/7 | 36.0 % / 0.0 % / 46.5 s / 7.5 | 50.0 % / 0.0 % / 43.7 s / 6.7 | 35.0 % / 0.0 % / 39.3 s / 6.8 | 52.0 % / 0.0 % / 40.6 s / 6.2 | 41.0 % / 0.0 % / 42.2 s / 6.8 |
| **ROLL** 7/6/5/2 | 9.0 % / 0.0 % / 32.0 s / 7.0 | 65.0 % / 0.0 % / 39.3 s / 6.8 | 52.5 % / 0.0 % / 30.3 s / 4.8 | 31.0 % / 0.0 % / 33.5 s / 5.7 | 35.0 % / 0.0 % / 35.0 s / 6.5 |
| **HIDE** 4/5/4/7 | 39.0 % / 0.0 % / 33.2 s / 6.3 | 48.0 % / 0.0 % / 40.6 s / 6.2 | 69.0 % / 0.0 % / 33.5 s / 5.7 | 52.5 % / 0.0 % / 37.5 s / 6.0 | 52.0 % / 0.0 % / 35.8 s / 6.1 |

### Abilities: small, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 23.3 s / 3.7 | 78.0 % / 0.0 % / 29.0 s / 4.1 | 0.5 % / 0.0 % / 15.3 s / 2.7 | 20.5 % / 0.0 % / 24.4 s / 3.4 | 33.0 % / 0.0 % / 22.9 s / 3.4 |
| **FLY** 3/7/3/7 | 22.0 % / 0.0 % / 29.0 s / 4.1 | 52.5 % / 0.0 % / 35.1 s / 5.0 | 5.5 % / 0.0 % / 18.3 s / 2.8 | 9.0 % / 0.0 % / 26.7 s / 3.9 | 12.2 % / 0.0 % / 24.7 s / 3.7 |
| **ROLL** 7/6/5/2 | 99.5 % / 0.0 % / 15.3 s / 2.7 | 94.5 % / 0.0 % / 18.3 s / 2.8 | 57.0 % / 0.0 % / 18.5 s / 2.7 | 67.0 % / 0.0 % / 19.1 s / 2.5 | 87.0 % / 0.0 % / 17.6 s / 2.7 |
| **HIDE** 4/5/4/7 | 79.5 % / 0.0 % / 24.4 s / 3.4 | 91.0 % / 0.0 % / 26.7 s / 3.9 | 33.0 % / 0.0 % / 19.1 s / 2.5 | 52.0 % / 0.0 % / 27.4 s / 3.7 | 67.8 % / 0.0 % / 23.4 s / 3.3 |

### Abilities: small, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 25.5 s / 3.3 | 59.0 % / 0.0 % / 31.9 s / 4.4 | 36.0 % / 0.0 % / 19.4 s / 2.5 | 24.0 % / 0.0 % / 24.3 s / 3.1 | 39.7 % / 0.0 % / 25.2 s / 3.4 |
| **FLY** 3/7/3/7 | 41.0 % / 0.0 % / 31.9 s / 4.4 | 50.0 % / 0.0 % / 38.2 s / 5.5 | 7.0 % / 0.0 % / 22.7 s / 3.4 | 32.5 % / 0.0 % / 30.2 s / 4.0 | 26.8 % / 0.0 % / 28.3 s / 4.0 |
| **ROLL** 7/6/5/2 | 64.0 % / 0.0 % / 19.4 s / 2.5 | 93.0 % / 0.0 % / 22.7 s / 3.4 | 52.0 % / 0.0 % / 20.0 s / 2.5 | 42.5 % / 0.0 % / 20.8 s / 2.7 | 66.5 % / 0.0 % / 21.0 s / 2.9 |
| **HIDE** 4/5/4/7 | 76.0 % / 0.0 % / 24.3 s / 3.1 | 67.5 % / 0.0 % / 30.2 s / 4.0 | 57.5 % / 0.0 % / 20.8 s / 2.7 | 53.5 % / 0.0 % / 26.9 s / 3.5 | 67.0 % / 0.0 % / 25.1 s / 3.3 |

### Abilities: large, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 54.0 % / 0.0 % / 40.3 s / 6.6 | 16.0 % / 0.0 % / 62.4 s / 9.4 | 45.0 % / 0.0 % / 35.1 s / 5.4 | 44.5 % / 0.0 % / 44.0 s / 6.7 | 35.2 % / 0.0 % / 47.2 s / 7.1 |
| **FLY** 3/7/3/7 | 84.0 % / 0.0 % / 62.4 s / 9.4 | 45.0 % / 0.0 % / 53.8 s / 8.4 | 12.5 % / 0.0 % / 40.1 s / 6.1 | 55.5 % / 0.0 % / 57.1 s / 8.3 | 50.7 % / 0.0 % / 53.2 s / 8.0 |
| **ROLL** 7/6/5/2 | 55.0 % / 0.0 % / 35.1 s / 5.4 | 87.5 % / 0.0 % / 40.1 s / 6.1 | 48.5 % / 0.0 % / 29.1 s / 4.2 | 78.5 % / 0.0 % / 35.4 s / 5.4 | 73.7 % / 0.0 % / 36.9 s / 5.7 |
| **HIDE** 4/5/4/7 | 55.5 % / 0.0 % / 44.0 s / 6.7 | 44.5 % / 0.0 % / 57.1 s / 8.3 | 21.5 % / 0.0 % / 35.4 s / 5.4 | 48.0 % / 0.0 % / 45.3 s / 6.8 | 40.5 % / 0.0 % / 45.5 s / 6.8 |

### Abilities: large, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 55.5 % / 0.6 % / 42.0 s / 7.0 | 52.0 % / 0.0 % / 58.2 s / 8.7 | 26.5 % / 0.0 % / 32.3 s / 5.2 | 25.0 % / 0.2 % / 42.2 s / 6.9 | 34.5 % / 0.1 % / 44.2 s / 7.0 |
| **FLY** 3/7/3/7 | 48.0 % / 0.0 % / 58.2 s / 8.7 | 49.0 % / 0.0 % / 50.2 s / 8.3 | 1.5 % / 0.0 % / 29.9 s / 5.4 | 20.0 % / 0.0 % / 54.3 s / 8.4 | 23.2 % / 0.0 % / 47.4 s / 7.6 |
| **ROLL** 7/6/5/2 | 73.5 % / 0.0 % / 32.3 s / 5.2 | 98.5 % / 0.0 % / 29.9 s / 5.4 | 49.0 % / 1.9 % / 42.4 s / 6.1 | 91.0 % / 0.0 % / 29.8 s / 4.8 | 87.7 % / 0.0 % / 30.7 s / 5.1 |
| **HIDE** 4/5/4/7 | 75.0 % / 0.2 % / 42.2 s / 6.9 | 80.0 % / 0.0 % / 54.3 s / 8.4 | 9.0 % / 0.0 % / 29.8 s / 4.8 | 52.0 % / 0.0 % / 40.4 s / 5.9 | 54.7 % / 0.1 % / 42.1 s / 6.7 |

### Stats: empty, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 57.5 % / 0.0 % | 26.5 % / 0.0 % | 63.0 % / 0.0 % | 44.0 % / 0.0 % | 93.5 % / 0.0 % |
| FLY | 65.5 % / 0.0 % | 23.5 % / 0.0 % | 74.0 % / 0.0 % | 46.5 % / 0.0 % | 99.5 % / 0.0 % |
| ROLL | 43.5 % / 0.0 % | 39.5 % / 0.0 % | 44.5 % / 0.0 % | 68.0 % / 0.0 % | 95.5 % / 0.0 % |
| HIDE | 61.0 % / 0.0 % | 36.5 % / 0.0 % | 67.0 % / 0.0 % | 50.5 % / 0.0 % | 98.0 % / 0.0 % |

### Stats: empty, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 46.5 % / 0.0 % | 36.0 % / 0.0 % | 48.0 % / 0.0 % | 43.0 % / 0.0 % | 96.5 % / 0.0 % |
| FLY | 52.0 % / 0.0 % | 26.0 % / 0.0 % | 61.5 % / 0.0 % | 38.5 % / 0.0 % | 99.5 % / 0.0 % |
| ROLL | 49.5 % / 0.0 % | 28.0 % / 0.0 % | 45.0 % / 0.0 % | 58.5 % / 0.0 % | 95.5 % / 0.0 % |
| HIDE | 45.5 % / 0.0 % | 29.5 % / 0.0 % | 46.0 % / 0.0 % | 37.0 % / 0.0 % | 99.0 % / 0.0 % |

### Stats: cup-and-book, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 43.5 % / 0.0 % | 46.5 % / 0.0 % | 47.5 % / 0.0 % | 41.5 % / 0.0 % | 68.0 % / 0.0 % |
| FLY | 56.0 % / 0.0 % | 40.5 % / 0.0 % | 58.0 % / 0.0 % | 41.5 % / 0.0 % | 86.5 % / 0.0 % |
| ROLL | 44.0 % / 0.0 % | 22.5 % / 0.0 % | 40.0 % / 0.0 % | 37.0 % / 0.0 % | 32.0 % / 0.0 % |
| HIDE | 49.5 % / 0.0 % | 24.0 % / 0.0 % | 41.5 % / 0.0 % | 51.5 % / 0.0 % | 59.5 % / 0.0 % |

### Stats: cup-and-book, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 63.0 % / 0.0 % | 50.5 % / 0.0 % | 60.0 % / 0.0 % | 62.0 % / 0.0 % | 71.5 % / 0.0 % |
| FLY | 59.5 % / 0.0 % | 44.5 % / 0.0 % | 62.5 % / 0.0 % | 42.0 % / 0.0 % | 88.5 % / 0.0 % |
| ROLL | 60.5 % / 0.0 % | 34.5 % / 0.0 % | 65.5 % / 0.0 % | 63.0 % / 0.0 % | 66.0 % / 0.0 % |
| HIDE | 54.5 % / 0.0 % | 29.0 % / 0.0 % | 63.0 % / 0.0 % | 60.5 % / 0.0 % | 57.0 % / 0.0 % |

### Stats: small, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 66.0 % / 0.0 % | 35.0 % / 0.0 % | 55.0 % / 0.0 % | 50.0 % / 0.0 % | 88.0 % / 0.0 % |
| FLY | 61.0 % / 0.0 % | 9.5 % / 0.0 % | 75.5 % / 0.0 % | 50.0 % / 0.0 % | 95.0 % / 0.0 % |
| ROLL | 61.5 % / 0.0 % | 29.5 % / 0.0 % | 49.0 % / 0.0 % | 22.0 % / 0.0 % | 95.5 % / 0.0 % |
| HIDE | 54.5 % / 0.0 % | 29.0 % / 0.0 % | 68.0 % / 0.0 % | 58.5 % / 0.0 % | 93.5 % / 0.0 % |

### Stats: small, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 38.5 % / 0.0 % | 35.0 % / 0.0 % | 54.0 % / 0.0 % | 75.0 % / 0.0 % | 95.0 % / 0.0 % |
| FLY | 52.0 % / 0.0 % | 6.0 % / 0.0 % | 57.5 % / 0.0 % | 51.0 % / 0.0 % | 99.0 % / 0.0 % |
| ROLL | 58.5 % / 0.0 % | 32.5 % / 0.0 % | 51.5 % / 0.0 % | 69.0 % / 0.0 % | 99.5 % / 0.0 % |
| HIDE | 38.5 % / 0.0 % | 32.0 % / 0.0 % | 55.0 % / 0.0 % | 62.5 % / 0.0 % | 100.0 % / 0.0 % |

### Stats: large, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 59.0 % / 0.0 % | 40.5 % / 0.0 % | 62.5 % / 0.0 % | 39.0 % / 0.0 % | 99.5 % / 0.0 % |
| FLY | 60.5 % / 0.0 % | 86.0 % / 0.2 % | 56.0 % / 0.2 % | 42.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 56.5 % / 0.0 % | 53.0 % / 0.0 % | 65.0 % / 0.0 % | 73.5 % / 0.0 % | 99.5 % / 0.0 % |
| HIDE | 60.5 % / 0.0 % | 79.5 % / 0.0 % | 56.0 % / 0.2 % | 45.5 % / 0.0 % | 100.0 % / 0.0 % |

### Stats: large, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 49.5 % / 0.2 % | 57.0 % / 0.0 % | 47.0 % / 0.0 % | 38.0 % / 0.0 % | 94.0 % / 0.0 % |
| FLY | 55.0 % / 0.0 % | 77.5 % / 0.0 % | 56.0 % / 0.0 % | 47.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 50.0 % / 1.2 % | 78.0 % / 0.0 % | 47.5 % / 0.0 % | 61.0 % / 0.0 % | 99.5 % / 0.0 % |
| HIDE | 43.5 % / 0.0 % | 79.5 % / 0.0 % | 35.0 % / 0.0 % | 40.5 % / 0.0 % | 100.0 % / 0.0 % |

### Bundled round robin: empty, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 86.7 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 52.8 % | 0.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 45.0 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 45.0 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 45.0 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 45.0 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 45.0 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 45.0 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 15.5 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 15.5 % | 0.0 % |

### Bundled round robin: empty, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 87.3 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 53.7 % | 0.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 51.5 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 51.5 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 51.5 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 51.5 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 51.5 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 51.5 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 7.5 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 7.5 % | 0.0 % |

### Bundled round robin: cup-and-book, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_cowcow | JUMP 5/5/5/5 | 64.7 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 64.7 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 64.7 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 64.7 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 64.7 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 64.7 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 55.3 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 45.8 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 45.8 % | 0.0 % |
| drawing_car | ROLL 7/6/5/2 | 34.2 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 34.2 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 34.2 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 34.2 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 34.2 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 34.2 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 34.2 % | 0.0 % |

### Bundled round robin: cup-and-book, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_cowcow | JUMP 5/5/5/5 | 71.3 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 71.3 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 71.3 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 71.3 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 71.3 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 71.3 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 52.0 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 39.2 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 39.2 % | 0.0 % |
| drawing_car | ROLL 7/6/5/2 | 37.5 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 37.5 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 37.5 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 37.5 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 37.5 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 37.5 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 37.5 % | 0.0 % |

### Bundled round robin: small, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 86.0 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 67.8 % | 0.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 32.8 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 32.8 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 32.8 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 32.8 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 32.8 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 32.8 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 13.3 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 13.3 % | 0.0 % |

### Bundled round robin: small, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 67.0 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 67.0 % | 0.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 39.7 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 39.7 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 39.7 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 39.7 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 39.7 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 39.7 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 26.3 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 26.3 % | 0.0 % |

### Bundled round robin: large, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 74.8 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 50.3 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 50.3 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 40.5 % | 0.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 34.3 % | 0.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 34.3 % | 0.0 % |
| horse | JUMP 5/5/5/5 | 34.3 % | 0.0 % |
| toy_dino | JUMP 5/5/5/5 | 34.3 % | 0.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 34.3 % | 0.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 34.3 % | 0.0 % |

### Bundled round robin: large, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 85.5 % | 0.0 % |
| toy_fox | HIDE 4/5/4/7 | 54.7 % | 0.1 % |
| drawing_cowcow | JUMP 5/5/5/5 | 37.0 % | 0.1 % |
| drawing_penguini | JUMP 5/5/5/5 | 37.0 % | 0.1 % |
| horse | JUMP 5/5/5/5 | 37.0 % | 0.1 % |
| toy_dino | JUMP 5/5/5/5 | 37.0 % | 0.1 % |
| toy_edinorog | JUMP 5/5/5/5 | 37.0 % | 0.1 % |
| toy_one_dino | JUMP 5/5/5/5 | 37.0 % | 0.1 % |
| drawing_god_cow | FLY 3/7/3/7 | 22.8 % | 0.0 % |
| drawing_pink | FLY 3/7/3/7 | 22.8 % | 0.0 % |


## Before: the T42, T45 and T55 numbers (regenerated through the same code, 2026-11-11)

265.2 s on 4 threads (57 600 matches + the same 57 600 replayed at 16 ms, 434 matches/s). The same command with
`--params before-params.json --tuning before-tuning.json`: every number T48 changed, back to its value before T48
(wobble 0: the sweep moves nothing then, a matrix of these numbers is byte-identical with and without it; the tuning
file gives no `sidestepEdgeRadii`, which then is `nearEdgeRadii`, as before):

```json
{"dashCooldownMs": 1500, "cooldownBase": 1.30, "cooldownPerAgility": 0.06, "speedBase": 0.70, "speedPerPoint": 0.06,
 "powerBase": 0.55, "powerPerPoint": 0.09, "guardBase": 0.55, "guardPerPoint": 0.09, "specialCooldownMs": 6000,
 "stompScale": 1.2, "ramDashScale": 2.2, "ramKnockScale": 1.5, "dodgeMs": 500, "counterKnockScale": 1.5,
 "wobblePerHit": 0.0, "wobbleMax": 0.0}
```

```json
{"ramNearEdgeRadii": 5.0,
 "normal": {"thinkMs": 144, "thinkJitterTicks": 2, "dashRangeShare": 0.8, "alignCos": 0.8660254, "aimErrorShare": 0.1,
  "wrongWayChance": 0.0, "wanderChance": 0.0, "wanderMs": 0, "leadsAim": false, "weighsPush": false, "usesSpecial": true,
  "nearEdgeRadii": 4.0, "sidesteps": false, "counters": false, "safeRamsOnly": false},
 "hard": {"thinkMs": 48, "thinkJitterTicks": 1, "dashRangeShare": 0.85, "alignCos": 0.8660254, "aimErrorShare": 0.0,
  "wrongWayChance": 0.0, "wanderChance": 0.0, "wanderMs": 0, "leadsAim": true, "weighsPush": true, "usesSpecial": true,
  "nearEdgeRadii": 4.0, "sidesteps": true, "counters": true, "safeRamsOnly": true}}
```

`duel matrix --n 200 --seed 1 --level normal,hard --arenas empty,cup-and-book,small,large --groups ability,stat --bundle docs/bundled --step-ms 33 --check-n 200 --check-step-ms 16`

Numbers: params before-params.json, tuning before-tuning.json. 200 matches per cell (seeds 1..200, marks swapped every other match from the first, as duel series does), both sides bots of the same level, 33 ms frames; A8 replays 200 per cell at 16 ms. Clock = share of the rounds ended by the round clock; hits = Hit + Clash events per ring-out (all rounds' hits); s = mean match length.

### Acceptance

| # | Criterion | Threshold | Measured | Verdict |
|---|---|---|---|---|
| A1 | each ability against the field | 35.0 %..65.0 % | 2.7 %..95.5 % | FAIL (20 out) |
| A2 | every matchup, each side | 25.0 %..75.0 % | 0.0 %..100.0 % | FAIL (76 out) |
| A3 | rounds ended by the clock, per ability pair | <= 25.0 % (large: <= 40.0 %) | 0.0 %..97.8 % | FAIL (24 out) |
| A4 | hits per ring-out, per ability pair | 2.0..8.0 | per pair 3.1..2826.4 (per arena 12.4..57.3) | FAIL (66 out) |
| A5 | mean match length, per ability pair | 30.0..120.0 s | per pair 18.5 s..161.3 s (per arena 43.2 s..97.7 s) | FAIL (24 out) |
| A6 | a specialist (8/4/4/4) against 5/5/5/5 | 40.0 %..65.0 % | 0.0 %..99.5 % | FAIL (89 out) |
| A7 | 8/8/8/8 against 5/5/5/5 | >= 65.0 % | 40.0 %..100.0 % | FAIL (3 out) |
| A8 | the same matches at another frame length | identical | identical (57600 matches replayed at 16 ms, 0 different) | PASS (0 out) |
| A9 | every core/duel test green | - | not measured here: `scripts/verify.sh` | - |

- A1 out of 35.0 %..65.0 %: empty NORMAL FLY 14.7 %; empty NORMAL ROLL 87.5 %; empty NORMAL HIDE 34.5 %; empty HARD JUMP 67.2 %; empty HARD FLY 2.7 %; empty HARD ROLL 95.5 %; empty HARD HIDE 34.7 %; cup-and-book NORMAL JUMP 84.2 %; cup-and-book NORMAL FLY 18.8 %; cup-and-book HARD JUMP 88.7 %; cup-and-book HARD FLY 17.8 %; small NORMAL FLY 15.5 %; small NORMAL ROLL 81.7 %; small HARD FLY 23.2 %; large NORMAL JUMP 90.2 %; large NORMAL FLY 9.2 %; and 4 more
- A2 out of 25.0 %..75.0 %: empty NORMAL JUMP v ROLL 22.0 %; empty NORMAL ROLL v JUMP 78.0 %; empty NORMAL JUMP v HIDE 97.5 %; empty NORMAL HIDE v JUMP 2.5 %; empty NORMAL FLY v ROLL 14.0 %; empty NORMAL ROLL v FLY 86.0 %; empty NORMAL FLY v HIDE 0.5 %; empty NORMAL HIDE v FLY 99.5 %; empty NORMAL ROLL v HIDE 98.5 %; empty NORMAL HIDE v ROLL 1.5 %; empty HARD JUMP v FLY 95.5 %; empty HARD FLY v JUMP 4.5 %; empty HARD JUMP v ROLL 6.0 %; empty HARD ROLL v JUMP 94.0 %; empty HARD JUMP v HIDE 100.0 %; empty HARD HIDE v JUMP 0.0 %; and 60 more
- A3 out of <= 25.0 % (large: <= 40.0 %): empty NORMAL JUMP v FLY 30.5 %; empty NORMAL FLY v FLY 74.0 %; empty NORMAL HIDE v HIDE 77.9 %; empty HARD FLY v FLY 66.5 %; cup-and-book NORMAL JUMP v FLY 63.0 %; cup-and-book NORMAL FLY v FLY 84.3 %; cup-and-book NORMAL FLY v ROLL 40.5 %; cup-and-book NORMAL FLY v HIDE 31.0 %; cup-and-book NORMAL ROLL v HIDE 55.7 %; cup-and-book NORMAL HIDE v HIDE 88.8 %; cup-and-book HARD JUMP v JUMP 29.3 %; cup-and-book HARD JUMP v FLY 33.8 %; cup-and-book HARD JUMP v HIDE 26.7 %; cup-and-book HARD FLY v FLY 97.8 %; cup-and-book HARD FLY v ROLL 36.7 %; cup-and-book HARD FLY v HIDE 49.0 %; and 8 more
- A4 out of 2.0..8.0: empty NORMAL JUMP v JUMP 15.3; empty NORMAL JUMP v FLY 38.2; empty NORMAL JUMP v HIDE 13.5; empty NORMAL FLY v FLY 198.6; empty NORMAL FLY v HIDE 12.9; empty NORMAL HIDE v HIDE 240.9; empty HARD JUMP v JUMP 13.5; empty HARD JUMP v FLY 16.2; empty HARD JUMP v ROLL 12.4; empty HARD JUMP v HIDE 10.6; empty HARD FLY v FLY 154.6; empty HARD FLY v HIDE 13.3; empty HARD ROLL v ROLL 11.6; empty HARD ROLL v HIDE 10.1; empty HARD HIDE v HIDE 17.6; cup-and-book NORMAL JUMP v JUMP 36.8; and 50 more
- A5 out of 30.0..120.0 s: empty NORMAL FLY v FLY 133.4 s; empty NORMAL FLY v ROLL 27.8 s; empty NORMAL ROLL v HIDE 24.2 s; empty NORMAL HIDE v HIDE 138.2 s; empty HARD FLY v FLY 130.7 s; empty HARD FLY v ROLL 28.5 s; cup-and-book NORMAL JUMP v FLY 134.9 s; cup-and-book NORMAL FLY v FLY 149.9 s; cup-and-book NORMAL ROLL v ROLL 28.8 s; cup-and-book NORMAL HIDE v HIDE 152.9 s; cup-and-book HARD FLY v FLY 161.3 s; cup-and-book HARD FLY v HIDE 120.8 s; cup-and-book HARD HIDE v HIDE 121.5 s; small NORMAL JUMP v ROLL 20.5 s; small NORMAL ROLL v ROLL 23.0 s; small NORMAL ROLL v HIDE 25.7 s; and 8 more
- A6 out of 40.0 %..65.0 %: empty NORMAL JUMP SPEED 26.5 %; empty NORMAL JUMP GUARD 68.0 %; empty NORMAL JUMP AGILITY 39.5 %; empty NORMAL FLY POWER 92.0 %; empty NORMAL FLY SPEED 2.0 %; empty NORMAL FLY GUARD 99.5 %; empty NORMAL FLY AGILITY 9.0 %; empty NORMAL ROLL SPEED 9.5 %; empty NORMAL ROLL GUARD 72.5 %; empty NORMAL HIDE POWER 92.5 %; empty NORMAL HIDE SPEED 4.0 %; empty NORMAL HIDE GUARD 98.5 %; empty NORMAL HIDE AGILITY 6.0 %; empty HARD JUMP POWER 35.0 %; empty HARD JUMP SPEED 6.0 %; empty HARD JUMP GUARD 31.5 %; and 73 more
- A7 out of >= 65.0 %: cup-and-book NORMAL ROLL 8/8/8/8 54.5 %; cup-and-book HARD FLY 8/8/8/8 40.0 %; large HARD FLY 8/8/8/8 57.0 %

### Overview (ability cells)

| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |
|---|---|---|---|---|---|---|---|---|---|---|
| empty | NORMAL | 19.5 % | HIDE v HIDE 77.9 % | 64.2 | 24.2 / 138.2 | 25.6 | HIDE v HIDE 240.9 | 1.9 | 0.0 | 5.1 |
| empty | HARD | 9.0 % | FLY v FLY 66.5 % | 56.6 | 28.5 / 130.7 | 18.3 | FLY v FLY 154.6 | 2.1 | 0.0 | 6.3 |
| cup-and-book | NORMAL | 41.2 % | HIDE v HIDE 88.8 % | 97.7 | 28.8 / 152.9 | 57.3 | HIDE v HIDE 511.5 | 1.4 | 23.9 | 7.3 |
| cup-and-book | HARD | 34.0 % | FLY v FLY 97.8 % | 97.2 | 60.1 / 161.3 | 48.4 | FLY v FLY 2826.4 | 1.6 | 31.9 | 12.7 |
| small | NORMAL | 7.2 % | FLY v FLY 32.9 % | 43.2 | 20.5 / 79.7 | 12.4 | FLY v FLY 39.6 | 2.2 | 0.0 | 5.1 |
| small | HARD | 10.8 % | FLY v FLY 96.1 % | 51.9 | 18.5 / 158.9 | 16.8 | FLY v FLY 1709.4 | 2.2 | 0.0 | 5.1 |
| large | NORMAL | 33.0 % | ROLL v ROLL 79.4 % | 95.9 | 44.5 / 143.0 | 47.8 | ROLL v ROLL 238.3 | 1.6 | 0.0 | 5.0 |
| large | HARD | 13.4 % | FLY v FLY 66.1 % | 72.3 | 41.4 / 136.2 | 24.0 | FLY v FLY 156.7 | 2.0 | 0.0 | 7.9 |

### Abilities: empty, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 48.0 % / 0.0 % / 54.7 s / 15.3 | 70.5 % / 30.5 % / 106.1 s / 38.2 | 22.0 % / 0.0 % / 33.9 s / 7.9 | 97.5 % / 0.0 % / 44.6 s / 13.5 | 63.3 % / 10.7 % / 61.5 s / 18.1 |
| **FLY** 3/7/3/7 | 29.5 % / 30.5 % / 106.1 s / 38.2 | 51.0 % / 74.0 % / 133.4 s / 198.6 | 14.0 % / 0.0 % / 27.8 s / 5.7 | 0.5 % / 0.0 % / 43.4 s / 12.9 | 14.7 % / 10.8 % / 59.1 s / 17.2 |
| **ROLL** 7/6/5/2 | 78.0 % / 0.0 % / 33.9 s / 7.9 | 86.0 % / 0.0 % / 27.8 s / 5.7 | 44.0 % / 0.0 % / 35.5 s / 7.1 | 98.5 % / 0.0 % / 24.2 s / 5.2 | 87.5 % / 0.0 % / 28.6 s / 6.3 |
| **HIDE** 4/5/4/7 | 2.5 % / 0.0 % / 44.6 s / 13.5 | 99.5 % / 0.0 % / 43.4 s / 12.9 | 1.5 % / 0.0 % / 24.2 s / 5.2 | 47.5 % / 77.9 % / 138.2 s / 240.9 | 34.5 % / 0.0 % / 37.4 s / 10.5 |

### Abilities: empty, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 45.5 % / 0.2 % / 49.5 s / 13.5 | 95.5 % / 8.2 % / 54.7 s / 16.2 | 6.0 % / 1.1 % / 50.0 s / 12.4 | 100.0 % / 0.0 % / 34.4 s / 10.6 | 67.2 % / 3.2 % / 46.4 s / 13.1 |
| **FLY** 3/7/3/7 | 4.5 % / 8.2 % / 54.7 s / 16.2 | 48.5 % / 66.5 % / 130.7 s / 154.6 | 0.0 % / 0.7 % / 28.5 s / 7.0 | 3.5 % / 1.3 % / 47.3 s / 13.3 | 2.7 % / 3.5 % / 43.5 s / 12.2 |
| **ROLL** 7/6/5/2 | 94.0 % / 1.1 % / 50.0 s / 12.4 | 100.0 % / 0.7 % / 28.5 s / 7.0 | 52.5 % / 0.4 % / 53.9 s / 11.6 | 92.5 % / 0.0 % / 46.7 s / 10.1 | 95.5 % / 0.6 % / 41.7 s / 9.9 |
| **HIDE** 4/5/4/7 | 0.0 % / 0.0 % / 34.4 s / 10.6 | 96.5 % / 1.3 % / 47.3 s / 13.3 | 7.5 % / 0.0 % / 46.7 s / 10.1 | 40.0 % / 4.4 % / 70.0 s / 17.6 | 34.7 % / 0.5 % / 42.8 s / 11.3 |

### Abilities: cup-and-book, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 15.4 % / 96.4 s / 36.8 | 67.5 % / 63.0 % / 134.9 s / 109.1 | 88.5 % / 5.4 % / 66.3 s / 21.9 | 96.5 % / 22.4 % / 74.2 s / 35.4 | 84.2 % / 31.1 % / 91.8 s / 43.2 |
| **FLY** 3/7/3/7 | 32.5 % / 63.0 % / 134.9 s / 109.1 | 47.5 % / 84.3 % / 149.9 s / 342.8 | 14.0 % / 40.5 % / 75.9 s / 47.4 | 10.0 % / 31.0 % / 92.8 s / 46.5 | 18.8 % / 45.2 % / 101.2 s / 61.4 |
| **ROLL** 7/6/5/2 | 11.5 % / 5.4 % / 66.3 s / 21.9 | 86.0 % / 40.5 % / 75.9 s / 47.4 | 54.0 % / 0.0 % / 28.8 s / 5.3 | 72.5 % / 55.7 % / 105.0 s / 84.6 | 56.7 % / 34.5 % / 82.4 s / 44.3 |
| **HIDE** 4/5/4/7 | 3.5 % / 22.4 % / 74.2 s / 35.4 | 90.0 % / 31.0 % / 92.8 s / 46.5 | 27.5 % / 55.7 % / 105.0 s / 84.6 | 43.0 % / 88.8 % / 152.9 s / 511.5 | 40.3 % / 37.1 % / 90.7 s / 51.8 |

### Abilities: cup-and-book, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.0 % / 29.3 % / 113.3 s / 53.7 | 89.5 % / 33.8 % / 96.3 s / 53.4 | 83.5 % / 3.1 % / 60.1 s / 18.2 | 93.0 % / 26.7 % / 69.0 s / 35.2 | 88.7 % / 20.8 % / 75.2 s / 33.0 |
| **FLY** 3/7/3/7 | 10.5 % / 33.8 % / 96.3 s / 53.4 | 49.5 % / 97.8 % / 161.3 s / 2826.4 | 20.0 % / 36.7 % / 82.0 s / 45.9 | 23.0 % / 49.0 % / 120.8 s / 74.8 | 17.8 % / 39.9 % / 99.7 s / 56.8 |
| **ROLL** 7/6/5/2 | 16.5 % / 3.1 % / 60.1 s / 18.2 | 80.0 % / 36.7 % / 82.0 s / 45.9 | 49.0 % / 1.4 % / 68.1 s / 15.6 | 52.0 % / 23.2 % / 79.6 s / 28.1 | 49.5 % / 21.1 % / 73.9 s / 28.9 |
| **HIDE** 4/5/4/7 | 7.0 % / 26.7 % / 69.0 s / 35.2 | 77.0 % / 49.0 % / 120.8 s / 74.8 | 48.0 % / 23.2 % / 79.6 s / 28.1 | 55.5 % / 37.4 % / 121.5 s / 57.3 | 44.0 % / 33.0 % / 89.8 s / 42.5 |

### Abilities: small, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 46.0 % / 0.8 % / 45.3 s / 12.2 | 96.5 % / 12.5 % / 67.8 s / 19.3 | 6.5 % / 0.0 % / 20.5 s / 4.4 | 88.5 % / 0.0 % / 36.3 s / 9.1 | 63.8 % / 4.0 % / 41.5 s / 10.4 |
| **FLY** 3/7/3/7 | 3.5 % / 12.5 % / 67.8 s / 19.3 | 44.5 % / 32.9 % / 79.7 s / 39.6 | 37.5 % / 0.2 % / 32.7 s / 6.3 | 5.5 % / 0.0 % / 34.2 s / 8.3 | 15.5 % / 4.0 % / 44.9 s / 10.7 |
| **ROLL** 7/6/5/2 | 93.5 % / 0.0 % / 20.5 s / 4.4 | 62.5 % / 0.2 % / 32.7 s / 6.3 | 49.0 % / 0.0 % / 23.0 s / 4.4 | 89.0 % / 0.0 % / 25.7 s / 5.1 | 81.7 % / 0.1 % / 26.3 s / 5.3 |
| **HIDE** 4/5/4/7 | 11.5 % / 0.0 % / 36.3 s / 9.1 | 94.5 % / 0.0 % / 34.2 s / 8.3 | 11.0 % / 0.0 % / 25.7 s / 5.1 | 50.0 % / 24.0 % / 66.8 s / 27.9 | 39.0 % / 0.0 % / 32.0 s / 7.5 |

### Abilities: small, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 46.0 % / 0.0 % / 29.9 s / 5.6 | 79.0 % / 6.3 % / 62.2 s / 17.2 | 33.0 % / 0.0 % / 18.5 s / 3.1 | 61.5 % / 1.4 % / 46.4 s / 11.5 | 57.8 % / 2.5 % / 42.4 s / 10.4 |
| **FLY** 3/7/3/7 | 21.0 % / 6.3 % / 62.2 s / 17.2 | 53.5 % / 96.1 % / 158.9 s / 1709.4 | 34.5 % / 0.4 % / 53.0 s / 11.9 | 14.0 % / 1.1 % / 44.3 s / 11.9 | 23.2 % / 2.6 % / 53.2 s / 13.6 |
| **ROLL** 7/6/5/2 | 67.0 % / 0.0 % / 18.5 s / 3.1 | 65.5 % / 0.4 % / 53.0 s / 11.9 | 50.0 % / 0.0 % / 30.1 s / 4.8 | 60.0 % / 0.0 % / 35.4 s / 6.9 | 64.2 % / 0.1 % / 35.6 s / 7.2 |
| **HIDE** 4/5/4/7 | 38.5 % / 1.4 % / 46.4 s / 11.5 | 86.0 % / 1.1 % / 44.3 s / 11.9 | 40.0 % / 0.0 % / 35.4 s / 6.9 | 47.0 % / 0.0 % / 40.8 s / 9.3 | 54.8 % / 0.8 % / 42.0 s / 10.0 |

### Abilities: large, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 56.5 % / 0.2 % / 75.8 s / 20.7 | 74.0 % / 43.8 % / 125.5 s / 56.3 | 98.5 % / 0.2 % / 70.5 s / 22.7 | 98.0 % / 0.2 % / 58.3 s / 17.8 | 90.2 % / 16.1 % / 84.8 s / 29.1 |
| **FLY** 3/7/3/7 | 26.0 % / 43.8 % / 125.5 s / 56.3 | 48.5 % / 75.3 % / 139.6 s / 218.3 | 0.5 % / 0.0 % / 44.5 s / 11.9 | 1.0 % / 0.0 % / 56.9 s / 17.3 | 9.2 % / 16.3 % / 75.6 s / 25.1 |
| **ROLL** 7/6/5/2 | 1.5 % / 0.2 % / 70.5 s / 22.7 | 99.5 % / 0.0 % / 44.5 s / 11.9 | 51.5 % / 79.4 % / 143.0 s / 238.3 | 98.0 % / 31.8 % / 102.9 s / 46.7 | 66.3 % / 11.1 % / 72.6 s / 25.2 |
| **HIDE** 4/5/4/7 | 2.0 % / 0.2 % / 58.3 s / 17.8 | 99.0 % / 0.0 % / 56.9 s / 17.3 | 2.0 % / 31.8 % / 102.9 s / 46.7 | 47.5 % / 77.1 % / 141.5 s / 235.9 | 34.3 % / 10.9 % / 72.7 s / 25.2 |

### Abilities: large, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 53.0 % / 0.0 % / 57.2 s / 15.4 | 79.5 % / 13.1 % / 61.7 s / 18.4 | 7.5 % / 2.8 % / 69.3 s / 17.8 | 100.0 % / 0.7 % / 43.0 s / 13.8 | 62.3 % / 5.9 % / 58.0 s / 16.7 |
| **FLY** 3/7/3/7 | 20.5 % / 13.1 % / 61.7 s / 18.4 | 49.0 % / 66.1 % / 136.2 s / 156.7 | 0.5 % / 4.2 % / 41.4 s / 10.3 | 6.0 % / 6.1 % / 58.3 s / 18.1 | 9.0 % / 8.0 % / 53.8 s / 15.6 |
| **ROLL** 7/6/5/2 | 92.5 % / 2.8 % / 69.3 s / 17.8 | 99.5 % / 4.2 % / 41.4 s / 10.3 | 48.5 % / 4.4 % / 81.6 s / 19.2 | 82.0 % / 1.3 % / 66.5 s / 15.7 | 91.3 % / 2.7 % / 59.0 s / 14.7 |
| **HIDE** 4/5/4/7 | 0.0 % / 0.7 % / 43.0 s / 13.8 | 94.0 % / 6.1 % / 58.3 s / 18.1 | 18.0 % / 1.3 % / 66.5 s / 15.7 | 50.5 % / 27.9 % / 108.1 s / 37.3 | 37.3 % / 2.7 % / 55.9 s / 15.9 |

### Stats: empty, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 60.0 % / 0.0 % | 26.5 % / 2.6 % | 68.0 % / 15.1 % | 39.5 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 92.0 % / 18.2 % | 2.0 % / 11.7 % | 99.5 % / 98.9 % | 9.0 % / 17.5 % | 100.0 % / 0.0 % |
| ROLL | 47.0 % / 0.0 % | 9.5 % / 0.0 % | 72.5 % / 0.0 % | 49.5 % / 0.0 % | 97.5 % / 0.7 % |
| HIDE | 92.5 % / 18.1 % | 4.0 % / 10.6 % | 98.5 % / 98.2 % | 6.0 % / 19.2 % | 100.0 % / 0.0 % |

### Stats: empty, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 35.0 % / 0.0 % | 6.0 % / 0.0 % | 31.5 % / 0.8 % | 50.5 % / 0.0 % | 96.5 % / 0.0 % |
| FLY | 82.0 % / 98.8 % | 3.0 % / 79.6 % | 99.0 % / 100.0 % | 13.0 % / 99.8 % | 99.0 % / 8.3 % |
| ROLL | 79.5 % / 0.0 % | 4.0 % / 13.0 % | 76.5 % / 100.0 % | 78.5 % / 0.0 % | 100.0 % / 0.7 % |
| HIDE | 17.0 % / 0.8 % | 0.0 % / 5.3 % | 50.5 % / 100.0 % | 70.5 % / 0.8 % | 100.0 % / 0.0 % |

### Stats: cup-and-book, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 51.0 % / 1.6 % | 35.0 % / 29.6 % | 58.0 % / 78.5 % | 45.5 % / 3.5 % | 95.5 % / 22.3 % |
| FLY | 64.5 % / 58.4 % | 23.0 % / 71.5 % | 57.0 % / 100.0 % | 46.5 % / 62.0 % | 66.5 % / 67.3 % |
| ROLL | 46.0 % / 0.0 % | 20.5 % / 0.0 % | 42.0 % / 0.0 % | 43.5 % / 0.0 % | 54.5 % / 11.9 % |
| HIDE | 73.5 % / 63.4 % | 16.0 % / 61.7 % | 58.5 % / 100.0 % | 37.0 % / 58.4 % | 67.0 % / 67.1 % |

### Stats: cup-and-book, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 44.0 % / 1.4 % | 8.5 % / 27.9 % | 34.5 % / 61.1 % | 47.0 % / 3.4 % | 92.5 % / 40.0 % |
| FLY | 68.0 % / 99.6 % | 31.5 % / 98.2 % | 49.0 % / 100.0 % | 42.5 % / 93.5 % | 40.0 % / 88.2 % |
| ROLL | 53.0 % / 1.2 % | 5.0 % / 28.7 % | 81.0 % / 99.6 % | 54.5 % / 1.8 % | 92.5 % / 49.3 % |
| HIDE | 31.5 % / 30.5 % | 14.5 % / 53.8 % | 71.0 % / 100.0 % | 92.0 % / 25.8 % | 98.5 % / 29.8 % |

### Stats: small, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 59.0 % / 0.0 % | 8.5 % / 0.7 % | 57.5 % / 17.0 % | 56.5 % / 0.0 % | 99.5 % / 0.0 % |
| FLY | 63.0 % / 0.0 % | 1.0 % / 9.0 % | 88.5 % / 98.3 % | 52.0 % / 0.2 % | 100.0 % / 0.0 % |
| ROLL | 49.0 % / 0.0 % | 11.5 % / 0.0 % | 63.0 % / 0.0 % | 41.0 % / 0.0 % | 95.5 % / 0.2 % |
| HIDE | 61.0 % / 0.0 % | 6.0 % / 17.6 % | 62.0 % / 95.5 % | 47.5 % / 0.0 % | 100.0 % / 0.2 % |

### Stats: small, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 16.5 % / 0.0 % | 6.0 % / 0.0 % | 16.0 % / 1.3 % | 83.5 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 70.0 % / 97.1 % | 18.0 % / 88.1 % | 79.5 % / 100.0 % | 57.0 % / 66.3 % | 100.0 % / 0.2 % |
| ROLL | 84.5 % / 0.0 % | 10.5 % / 1.3 % | 78.0 % / 47.2 % | 50.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 18.0 % / 0.0 % | 1.5 % / 4.1 % | 27.0 % / 100.0 % | 76.5 % / 0.0 % | 100.0 % / 0.0 % |

### Stats: large, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 57.5 % / 0.0 % | 26.5 % / 4.2 % | 79.0 % / 26.1 % | 45.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 88.5 % / 46.6 % | 0.0 % / 32.3 % | 99.0 % / 99.8 % | 12.0 % / 51.2 % | 100.0 % / 0.0 % |
| ROLL | 87.5 % / 41.6 % | 0.0 % / 10.1 % | 99.5 % / 98.8 % | 10.0 % / 43.8 % | 100.0 % / 0.0 % |
| HIDE | 87.5 % / 46.9 % | 1.0 % / 32.3 % | 99.0 % / 99.8 % | 11.0 % / 52.2 % | 100.0 % / 0.0 % |

### Stats: large, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 34.0 % / 0.0 % | 5.0 % / 0.0 % | 35.0 % / 3.5 % | 55.5 % / 0.0 % | 99.5 % / 0.0 % |
| FLY | 80.5 % / 99.4 % | 0.0 % / 74.1 % | 97.5 % / 100.0 % | 15.5 % / 99.8 % | 57.0 % / 51.9 % |
| ROLL | 79.5 % / 1.2 % | 10.0 % / 20.8 % | 76.0 % / 100.0 % | 86.5 % / 0.0 % | 99.5 % / 2.7 % |
| HIDE | 18.0 % / 4.6 % | 2.5 % / 10.9 % | 44.5 % / 100.0 % | 56.5 % / 13.0 % | 100.0 % / 0.0 % |

### Bundled round robin: empty, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| toy_car2 | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| toy_duck | ROLL 7/6/5/2 | 90.7 % | 0.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 61.8 % | 10.7 % |
| drawing_penguini | JUMP 5/5/5/5 | 61.8 % | 10.7 % |
| horse | JUMP 5/5/5/5 | 61.8 % | 10.7 % |
| toy_dino | JUMP 5/5/5/5 | 61.8 % | 10.7 % |
| toy_edinorog | JUMP 5/5/5/5 | 61.8 % | 10.7 % |
| toy_one_dino | JUMP 5/5/5/5 | 61.8 % | 10.7 % |
| toy_fox | HIDE 4/5/4/7 | 34.5 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 13.0 % | 10.9 % |
| drawing_pink | FLY 3/7/3/7 | 13.0 % | 10.9 % |

### Bundled round robin: empty, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| toy_car2 | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| toy_duck | ROLL 7/6/5/2 | 95.3 % | 1.0 % |
| drawing_cowcow | JUMP 5/5/5/5 | 67.2 % | 3.4 % |
| drawing_penguini | JUMP 5/5/5/5 | 67.2 % | 3.4 % |
| horse | JUMP 5/5/5/5 | 67.2 % | 3.4 % |
| toy_dino | JUMP 5/5/5/5 | 67.2 % | 3.4 % |
| toy_edinorog | JUMP 5/5/5/5 | 67.2 % | 3.4 % |
| toy_one_dino | JUMP 5/5/5/5 | 67.2 % | 3.4 % |
| toy_fox | HIDE 4/5/4/7 | 34.7 % | 0.5 % |
| drawing_god_cow | FLY 3/7/3/7 | 2.8 % | 3.7 % |
| drawing_pink | FLY 3/7/3/7 | 2.8 % | 3.7 % |

### Bundled round robin: cup-and-book, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_cowcow | JUMP 5/5/5/5 | 84.7 % | 30.8 % |
| drawing_penguini | JUMP 5/5/5/5 | 84.7 % | 30.8 % |
| horse | JUMP 5/5/5/5 | 84.7 % | 30.8 % |
| toy_dino | JUMP 5/5/5/5 | 84.7 % | 30.8 % |
| toy_edinorog | JUMP 5/5/5/5 | 84.7 % | 30.8 % |
| toy_one_dino | JUMP 5/5/5/5 | 84.7 % | 30.8 % |
| drawing_car | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| drawing_car_2 | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| drawing_watermelon | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| toy-car-01 | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| toy-car-02 | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| toy_car2 | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| toy_duck | ROLL 7/6/5/2 | 55.3 % | 33.5 % |
| toy_fox | HIDE 4/5/4/7 | 40.3 % | 37.1 % |
| drawing_god_cow | FLY 3/7/3/7 | 19.7 % | 44.6 % |
| drawing_pink | FLY 3/7/3/7 | 19.7 % | 44.6 % |

### Bundled round robin: cup-and-book, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_cowcow | JUMP 5/5/5/5 | 86.3 % | 20.5 % |
| drawing_penguini | JUMP 5/5/5/5 | 86.3 % | 20.5 % |
| horse | JUMP 5/5/5/5 | 86.3 % | 20.5 % |
| toy_dino | JUMP 5/5/5/5 | 86.3 % | 20.5 % |
| toy_edinorog | JUMP 5/5/5/5 | 86.3 % | 20.5 % |
| toy_one_dino | JUMP 5/5/5/5 | 86.3 % | 20.5 % |
| drawing_car | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| drawing_car_2 | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| drawing_watermelon | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| toy-car-01 | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| toy-car-02 | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| toy_car2 | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| toy_duck | ROLL 7/6/5/2 | 53.0 % | 19.9 % |
| toy_fox | HIDE 4/5/4/7 | 44.0 % | 33.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 16.7 % | 39.2 % |
| drawing_pink | FLY 3/7/3/7 | 16.7 % | 39.2 % |

### Bundled round robin: small, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| drawing_car_2 | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| drawing_watermelon | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| toy-car-01 | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| toy-car-02 | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| toy_car2 | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| toy_duck | ROLL 7/6/5/2 | 79.2 % | 0.2 % |
| drawing_cowcow | JUMP 5/5/5/5 | 63.8 % | 4.0 % |
| drawing_penguini | JUMP 5/5/5/5 | 63.8 % | 4.0 % |
| horse | JUMP 5/5/5/5 | 63.8 % | 4.0 % |
| toy_dino | JUMP 5/5/5/5 | 63.8 % | 4.0 % |
| toy_edinorog | JUMP 5/5/5/5 | 63.8 % | 4.0 % |
| toy_one_dino | JUMP 5/5/5/5 | 63.8 % | 4.0 % |
| toy_fox | HIDE 4/5/4/7 | 39.0 % | 0.0 % |
| drawing_god_cow | FLY 3/7/3/7 | 18.0 % | 4.1 % |
| drawing_pink | FLY 3/7/3/7 | 18.0 % | 4.1 % |

### Bundled round robin: small, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| drawing_car_2 | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| drawing_watermelon | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| toy-car-01 | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| toy-car-02 | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| toy_car2 | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| toy_duck | ROLL 7/6/5/2 | 65.2 % | 0.2 % |
| drawing_cowcow | JUMP 5/5/5/5 | 57.5 % | 2.5 % |
| drawing_penguini | JUMP 5/5/5/5 | 57.5 % | 2.5 % |
| horse | JUMP 5/5/5/5 | 57.5 % | 2.5 % |
| toy_dino | JUMP 5/5/5/5 | 57.5 % | 2.5 % |
| toy_edinorog | JUMP 5/5/5/5 | 57.5 % | 2.5 % |
| toy_one_dino | JUMP 5/5/5/5 | 57.5 % | 2.5 % |
| toy_fox | HIDE 4/5/4/7 | 54.8 % | 0.8 % |
| drawing_god_cow | FLY 3/7/3/7 | 22.5 % | 2.7 % |
| drawing_pink | FLY 3/7/3/7 | 22.5 % | 2.7 % |

### Bundled round robin: large, NORMAL v NORMAL

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_cowcow | JUMP 5/5/5/5 | 89.7 % | 16.2 % |
| drawing_penguini | JUMP 5/5/5/5 | 89.7 % | 16.2 % |
| horse | JUMP 5/5/5/5 | 89.7 % | 16.2 % |
| toy_dino | JUMP 5/5/5/5 | 89.7 % | 16.2 % |
| toy_edinorog | JUMP 5/5/5/5 | 89.7 % | 16.2 % |
| toy_one_dino | JUMP 5/5/5/5 | 89.7 % | 16.2 % |
| drawing_car | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| drawing_car_2 | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| drawing_watermelon | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| toy-car-01 | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| toy-car-02 | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| toy_car2 | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| toy_duck | ROLL 7/6/5/2 | 67.0 % | 11.0 % |
| toy_fox | HIDE 4/5/4/7 | 34.3 % | 10.9 % |
| drawing_god_cow | FLY 3/7/3/7 | 9.0 % | 16.2 % |
| drawing_pink | FLY 3/7/3/7 | 9.0 % | 16.2 % |

### Bundled round robin: large, HARD v HARD

16 characters, 4 distinct fighters; each one's win share against the others.

| character | fighter | v the others | clock |
|---|---|---|---|
| drawing_car | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| drawing_car_2 | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| drawing_watermelon | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| toy-car-01 | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| toy-car-02 | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| toy_car2 | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| toy_duck | ROLL 7/6/5/2 | 90.0 % | 3.9 % |
| drawing_cowcow | JUMP 5/5/5/5 | 62.8 % | 6.7 % |
| drawing_penguini | JUMP 5/5/5/5 | 62.8 % | 6.7 % |
| horse | JUMP 5/5/5/5 | 62.8 % | 6.7 % |
| toy_dino | JUMP 5/5/5/5 | 62.8 % | 6.7 % |
| toy_edinorog | JUMP 5/5/5/5 | 62.8 % | 6.7 % |
| toy_one_dino | JUMP 5/5/5/5 | 62.8 % | 6.7 % |
| toy_fox | HIDE 4/5/4/7 | 37.3 % | 2.7 % |
| drawing_god_cow | FLY 3/7/3/7 | 9.8 % | 8.5 % |
| drawing_pink | FLY 3/7/3/7 | 9.8 % | 8.5 % |

