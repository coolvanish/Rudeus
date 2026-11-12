# Duel balance (T48, T57)

## T57: the rules of 2026-11-12 (ai/tasks/T57-duel-balance-2.md)

Three rule changes, each in `DuelParams` with an off position that reproduces the T48 game bit for bit (B9's second
half, the test `theNewRulesSwitchedOffAreThePreviousPhysics`): the JUMP stomp and the ROLL ram share the dash cooldown
(`specialsShareDashCooldown`); SPEED lengthens every dash (`dashReachBase + dashReachPerSpeed * SPEED` x the dash speed,
its duration unchanged); a dash or a stomp hits only a victim whose feet are at most `hitReachUpM` above and
`hitReachDownM` below the attacker's (a jump over a dash is a dodge, a fighter on a book is safe from the table), and
the fighter a missed dash presses into stands firm. And rule 4: FLY's archetype is 4/6/4/6 (was 3/7/3/7). The bots jump
over dashes and stomps (EASY never, NORMAL half the time, HARD whenever it clears them), never attack a fighter out of
reach, push from where they can reach, try the stomp or the ram before a plain dash when both are ready, and stomp only a
fighter that cannot get out of the way. The acceptance table is T57's (B1..B9); B1, the abilities at equal stats
5/5/5/5, is the row new drawings meet.

### Verdicts (n = 200, NORMAL v NORMAL and HARD v HARD, four arenas)

"T48" is T48's committed table under its own criteria (A1..A8: no equal-stats group, archetype field 35..65 %, no small
/ large exceptions in hits and length); "before" is step 2 (the rules on with the brief's starting numbers, the bots
taught, everything else as T48 left it); "final" is the defaults committed by T57.

| # | Criterion | Threshold | T48 | Before (step 2) | Final | Verdict |
|---|---|---|---|---|---|---|
| B1 | each ability against the field, all at 5/5/5/5 | 35..65 % | not in the matrix (by hand, NORMAL, empty / large: 36..64 % but ROLL v JUMP 80.5 / 19.5) | 7.7..85.2 % (27 of 32 out) | 28.0..60.8 % (1 out: small HARD ROLL 28.0 %) | FAIL (1 out) |
| B2 | each ability against the field, archetype stats | 30..70 % | 7.2..87.7 % | 17.0..91.7 % (10 out) | 16.2..75.3 % (9 out) | FAIL |
| B3 | every matchup, each side | 25..75 % | 0..100 % (archetypes, 46 out) | 0..100 % (110 out) | 5.5..94.5 % (38 out: 2 at equal stats, 36 at archetypes) | FAIL |
| B4 | rounds ended by the clock, per pair | <= 25 % (large <= 40 %) | 0..1.9 % | 0..5.6 % | 0..0.2 % | PASS |
| B5 | hits per ring-out, per pair | 2..8 (large ..10) | 2.5..9.4 | 3.4..12.7 (19 out) | 2.8..8.2 (arenas 3.5..7.8) | PASS |
| B6 | match length, per pair | 30..120 s (small 20..) | 15.3..62.4 s | 25.4..64.7 s | 24.6..55.2 s (arenas 27.1..49.7 s) | PASS |
| B7 | a specialist (8/4/4/4) against 5/5/5/5 | 40..65 % | 6.0..86.0 % (45 out) | 8.5..95.5 % (56 out) | 5.5..90.5 % (77 out) | FAIL |
| B8 | 8/8/8/8 against 5/5/5/5 | >= 65 % | 32..100 % (3 out) | 83.5..100 % | 95.5..100 % | PASS |
| B9 | the same matches at 16 ms and 33 ms; the rules off = the T48 game | identical | identical | identical | identical (64 000 replays); off switches: 24 bot matches + 16 fights, and the T48 matrix (n 20), bit for bit | PASS |

What still fails, and why:
- **B1, one cell: ROLL on the small table at HARD, 28.0 %** (NORMAL there 60.8 %; ROLL 40.7..60.3 % everywhere else).
  A ROLL cannot jump (the walking game's rule), so on a 30 x 30 cm table, where every spot is near an edge and one
  dodged exchange is a ring-out, the HARD bots that jump, lift off or dodge out of its rams and dashes win; it loses to
  HIDE 23.5 / 76.5 there (the two equal-stats B3 failures). Eight candidates aimed at it moved it within noise (24..30 %).
- **B2, nine cells: the archetypes, not the abilities.** FLY's new 4/6/4/6 takes 23..39 % on the empty, cup-and-book
  and large tables but 75.3 % on the small one at HARD; HIDE's 4/5/4/7 71.8 % (empty, NORMAL) and 72.8 % (small, HARD);
  ROLL's 7/6/5/2 16.2 % (small, HARD) and 70.8 % (large, HARD). The same abilities at 5/5/5/5 are 41..61 % on those
  tables. That is B7's problem showing through: a point of SPEED or AGILITY is not worth a point of POWER or GUARD.
- **B7, 77 of 128: the stats are not worth the same, and how much depends on the table.** SPEED 8 takes 5.5..73.0 %
  (it walks faster, which wins the large table and nothing on the small one; its longer dash rarely matters, since the
  bots dash once they stand behind the other, from 1 to 2 cm), AGILITY 8 25.0..90.5 % (NORMAL 58..91, HARD 25..60),
  POWER 8 13.5..65.5 %, GUARD 8 25.0..72.0 %. Two candidates on the stat factors (SPEED's weight moved to the dash's
  reach; flatter POWER / GUARD, steeper AGILITY) made it worse (68 and 84 out at n 60). 8/8/8/8 wins 95.5..100 % (B8).
- **B3**: 36 of its 38 failures are archetype cells (above).
- The bot strength ordering holds, with no margin on one cell: HARD ROLL over NORMAL ROLL on the empty table is 30 of 50,
  exactly the 60 % floor of `normalIsBetweenEasyAndHard` (every other ability 62..86 %).

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

### The T57 defaults (final), 2026-11-12

Generated by `./gradlew :cli:run --args="duel matrix --n 200 --out build/duel"` (`build/duel/matrix.md`, headings two
levels down): 320 cells (80 equal-stats, 80 archetype, 160 stat), 64 000 matches and the same 64 000 replayed at 16 ms,
119.5 s on 4 threads (1070 matches/s). The candidate log that led here is in the brief's Result.

`duel matrix --n 200 --seed 1 --level normal,hard --arenas empty,cup-and-book,small,large --groups ability,equal,stat --step-ms 33 --check-n 200 --check-step-ms 16`

Numbers: the defaults. 200 matches per cell (seeds 1..200, marks swapped every other match from the first, as duel series does), both sides bots of the same level, 33 ms frames; B9 replays 200 per cell at 16 ms. Clock = share of the rounds ended by the round clock; hits = Hit + Clash events per ring-out (all rounds' hits); s = mean match length. "equal": both sides 5/5/5/5 (B1); "archetype": each ability's stats for a character without stats (B2).

#### Acceptance

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

#### Overview (equal stats, 5/5/5/5)

| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |
|---|---|---|---|---|---|---|---|---|---|---|
| empty | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 37.9 | 36.1 / 38.8 | 5.2 | JUMP v ROLL 5.7 | 2.6 | 0.0 | 0.4 |
| empty | HARD | 0.0 % | JUMP v JUMP 0.0 % | 38.8 | 37.6 / 39.1 | 5.8 | JUMP v ROLL 6.1 | 2.5 | 0.0 | 0.4 |
| cup-and-book | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 45.4 | 41.8 / 46.3 | 7.4 | JUMP v ROLL 7.7 | 2.5 | 5.9 | 0.5 |
| cup-and-book | HARD | 0.0 % | JUMP v JUMP 0.0 % | 43.8 | 40.6 / 45.8 | 7.4 | JUMP v ROLL 8.0 | 2.5 | 6.6 | 0.5 |
| small | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 28.4 | 26.0 / 29.0 | 3.7 | FLY v HIDE 3.9 | 2.5 | 0.0 | 1.3 |
| small | HARD | 0.0 % | JUMP v JUMP 0.0 % | 30.3 | 28.7 / 33.2 | 4.0 | ROLL v HIDE 4.4 | 2.5 | 0.0 | 1.0 |
| large | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 48.2 | 47.0 / 52.4 | 7.1 | JUMP v ROLL 7.8 | 2.5 | 0.0 | 0.4 |
| large | HARD | 0.0 % | JUMP v JUMP 0.0 % | 49.7 | 47.3 / 51.2 | 7.8 | JUMP v ROLL 8.0 | 2.5 | 0.0 | 0.5 |

#### Overview (archetype stats)

| arena | level | clock | worst pair clock | s | shortest / longest pair s | hits / ring-out | most hits, pair | ring-outs / match | wall slams / match | specials / match |
|---|---|---|---|---|---|---|---|---|---|---|
| empty | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 37.0 | 33.5 / 38.8 | 5.3 | JUMP v ROLL 5.8 | 2.5 | 0.0 | 0.3 |
| empty | HARD | 0.0 % | JUMP v JUMP 0.0 % | 37.8 | 32.9 / 39.5 | 5.8 | JUMP v FLY 6.2 | 2.5 | 0.0 | 0.3 |
| cup-and-book | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 43.3 | 40.4 / 46.3 | 7.2 | JUMP v HIDE 7.5 | 2.5 | 5.2 | 0.4 |
| cup-and-book | HARD | 0.0 % | JUMP v JUMP 0.0 % | 42.7 | 40.1 / 44.9 | 7.2 | JUMP v HIDE 7.7 | 2.5 | 5.9 | 0.5 |
| small | NORMAL | 0.0 % | JUMP v JUMP 0.0 % | 27.1 | 24.6 / 29.5 | 3.5 | JUMP v HIDE 3.9 | 2.5 | 0.0 | 1.3 |
| small | HARD | 0.0 % | JUMP v JUMP 0.0 % | 30.0 | 27.9 / 33.6 | 4.2 | FLY v ROLL 4.8 | 2.4 | 0.0 | 0.8 |
| large | NORMAL | 0.0 % | HIDE v HIDE 0.2 % | 47.8 | 44.0 / 55.2 | 7.0 | JUMP v ROLL 7.7 | 2.5 | 0.0 | 0.4 |
| large | HARD | 0.0 % | JUMP v JUMP 0.0 % | 49.0 | 45.9 / 51.7 | 7.8 | JUMP v FLY 8.2 | 2.5 | 0.0 | 0.4 |

#### Abilities (equal stats, 5/5/5/5): empty, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 72.0 % / 0.0 % / 36.5 s / 5.7 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 56.3 % / 0.0 % / 38.0 s / 5.3 |
| **FLY** 5/5/5/5 | 51.5 % / 0.0 % / 38.8 s / 5.2 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 51.5 % / 0.0 % / 36.9 s / 5.1 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 50.5 % / 0.0 % / 38.1 s / 5.1 |
| **ROLL** 5/5/5/5 | 28.0 % / 0.0 % / 36.5 s / 5.7 | 48.5 % / 0.0 % / 36.9 s / 5.1 | 47.5 % / 0.0 % / 36.1 s / 5.0 | 45.5 % / 0.0 % / 37.4 s / 5.1 | 40.7 % / 0.0 % / 36.9 s / 5.3 |
| **HIDE** 5/5/5/5 | 51.5 % / 0.0 % / 38.8 s / 5.2 | 51.5 % / 0.0 % / 38.8 s / 5.2 | 54.5 % / 0.0 % / 37.4 s / 5.1 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 52.5 % / 0.0 % / 38.3 s / 5.2 |

#### Abilities (equal stats, 5/5/5/5): empty, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 44.0 % / 0.0 % / 39.1 s / 6.1 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 44.3 % / 0.0 % / 39.1 s / 6.0 |
| **FLY** 5/5/5/5 | 55.5 % / 0.0 % / 39.1 s / 5.9 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 31.5 % / 0.0 % / 38.3 s / 5.7 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 43.8 % / 0.0 % / 38.8 s / 5.8 |
| **ROLL** 5/5/5/5 | 56.0 % / 0.0 % / 39.1 s / 6.1 | 68.5 % / 0.0 % / 38.3 s / 5.7 | 53.0 % / 0.0 % / 38.1 s / 5.7 | 56.5 % / 0.0 % / 37.6 s / 5.7 | 60.3 % / 0.0 % / 38.3 s / 5.9 |
| **HIDE** 5/5/5/5 | 55.5 % / 0.0 % / 39.1 s / 5.9 | 55.5 % / 0.0 % / 39.1 s / 5.9 | 43.5 % / 0.0 % / 37.6 s / 5.7 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 51.5 % / 0.0 % / 38.6 s / 5.8 |

#### Abilities (equal stats, 5/5/5/5): cup-and-book, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 51.5 % / 0.0 % / 46.3 s / 7.4 | 51.0 % / 0.0 % / 46.2 s / 7.5 | 73.5 % / 0.0 % / 41.8 s / 7.7 | 51.5 % / 0.0 % / 46.3 s / 7.4 | 58.7 % / 0.0 % / 44.7 s / 7.5 |
| **FLY** 5/5/5/5 | 49.0 % / 0.0 % / 46.2 s / 7.5 | 54.0 % / 0.0 % / 44.2 s / 7.4 | 52.5 % / 0.0 % / 45.4 s / 7.3 | 50.0 % / 0.0 % / 45.3 s / 7.3 | 50.5 % / 0.0 % / 45.7 s / 7.4 |
| **ROLL** 5/5/5/5 | 26.5 % / 0.0 % / 41.8 s / 7.7 | 47.5 % / 0.0 % / 45.4 s / 7.3 | 49.5 % / 0.0 % / 46.1 s / 7.3 | 51.5 % / 0.0 % / 45.7 s / 7.4 | 41.8 % / 0.0 % / 44.3 s / 7.5 |
| **HIDE** 5/5/5/5 | 48.5 % / 0.0 % / 46.3 s / 7.4 | 50.0 % / 0.0 % / 45.3 s / 7.3 | 48.5 % / 0.0 % / 45.7 s / 7.4 | 51.5 % / 0.0 % / 46.3 s / 7.4 | 49.0 % / 0.0 % / 45.8 s / 7.4 |

#### Abilities (equal stats, 5/5/5/5): cup-and-book, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 43.9 s / 7.3 | 49.0 % / 0.0 % / 44.3 s / 7.3 | 55.0 % / 0.0 % / 40.6 s / 8.0 | 49.5 % / 0.0 % / 43.9 s / 7.3 | 51.2 % / 0.0 % / 42.9 s / 7.5 |
| **FLY** 5/5/5/5 | 51.0 % / 0.0 % / 44.3 s / 7.3 | 46.5 % / 0.0 % / 44.3 s / 7.2 | 53.0 % / 0.0 % / 45.8 s / 7.2 | 48.5 % / 0.0 % / 43.7 s / 7.2 | 50.8 % / 0.0 % / 44.6 s / 7.3 |
| **ROLL** 5/5/5/5 | 45.0 % / 0.0 % / 40.6 s / 8.0 | 47.0 % / 0.0 % / 45.8 s / 7.2 | 46.5 % / 0.0 % / 44.3 s / 7.4 | 43.0 % / 0.0 % / 43.8 s / 7.3 | 45.0 % / 0.0 % / 43.4 s / 7.5 |
| **HIDE** 5/5/5/5 | 50.5 % / 0.0 % / 43.9 s / 7.3 | 51.5 % / 0.0 % / 43.7 s / 7.2 | 57.0 % / 0.0 % / 43.8 s / 7.3 | 49.5 % / 0.0 % / 43.9 s / 7.3 | 53.0 % / 0.0 % / 43.8 s / 7.3 |

#### Abilities (equal stats, 5/5/5/5): small, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 28.7 s / 3.9 | 48.0 % / 0.0 % / 28.6 s / 3.9 | 44.5 % / 0.0 % / 28.0 s / 3.7 | 47.5 % / 0.0 % / 28.7 s / 3.9 | 46.7 % / 0.0 % / 28.5 s / 3.8 |
| **FLY** 5/5/5/5 | 52.0 % / 0.0 % / 28.6 s / 3.9 | 47.0 % / 0.0 % / 28.8 s / 3.9 | 38.0 % / 0.0 % / 28.9 s / 3.6 | 46.5 % / 0.0 % / 28.9 s / 3.9 | 45.5 % / 0.0 % / 28.8 s / 3.8 |
| **ROLL** 5/5/5/5 | 55.5 % / 0.0 % / 28.0 s / 3.7 | 62.0 % / 0.0 % / 28.9 s / 3.6 | 45.0 % / 0.0 % / 26.0 s / 3.0 | 65.0 % / 0.0 % / 29.0 s / 3.6 | 60.8 % / 0.0 % / 28.7 s / 3.6 |
| **HIDE** 5/5/5/5 | 52.5 % / 0.0 % / 28.7 s / 3.9 | 53.5 % / 0.0 % / 28.9 s / 3.9 | 35.0 % / 0.0 % / 29.0 s / 3.6 | 47.5 % / 0.0 % / 28.7 s / 3.9 | 47.0 % / 0.0 % / 28.9 s / 3.8 |

#### Abilities (equal stats, 5/5/5/5): small, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 69.0 % / 0.0 % / 30.5 s / 3.9 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 58.7 % / 0.0 % / 30.1 s / 4.0 |
| **FLY** 5/5/5/5 | 46.5 % / 0.0 % / 30.0 s / 4.1 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 70.5 % / 0.0 % / 33.2 s / 4.3 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 56.8 % / 0.0 % / 31.1 s / 4.1 |
| **ROLL** 5/5/5/5 | 31.0 % / 0.0 % / 30.5 s / 3.9 | 29.5 % / 0.0 % / 33.2 s / 4.3 | 56.0 % / 0.0 % / 28.7 s / 3.5 | 23.5 % / 0.0 % / 31.3 s / 4.4 | 28.0 % / 0.0 % / 31.6 s / 4.2 |
| **HIDE** 5/5/5/5 | 46.5 % / 0.0 % / 30.0 s / 4.1 | 46.5 % / 0.0 % / 30.0 s / 4.1 | 76.5 % / 0.0 % / 31.3 s / 4.4 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 56.5 % / 0.0 % / 30.4 s / 4.2 |

#### Abilities (equal stats, 5/5/5/5): large, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 47.0 s / 6.8 | 49.5 % / 0.0 % / 47.0 s / 6.8 | 42.5 % / 0.0 % / 49.1 s / 7.8 | 49.5 % / 0.0 % / 47.0 s / 6.8 | 47.2 % / 0.0 % / 47.7 s / 7.1 |
| **FLY** 5/5/5/5 | 50.5 % / 0.0 % / 47.0 s / 6.8 | 50.0 % / 0.0 % / 47.1 s / 6.8 | 45.5 % / 0.0 % / 50.8 s / 7.4 | 50.0 % / 0.0 % / 47.1 s / 6.8 | 48.7 % / 0.0 % / 48.3 s / 7.0 |
| **ROLL** 5/5/5/5 | 57.5 % / 0.0 % / 49.1 s / 7.8 | 54.5 % / 0.0 % / 50.8 s / 7.4 | 45.5 % / 0.0 % / 47.5 s / 7.0 | 47.5 % / 0.0 % / 52.4 s / 7.3 | 53.2 % / 0.0 % / 50.8 s / 7.5 |
| **HIDE** 5/5/5/5 | 50.5 % / 0.0 % / 47.0 s / 6.8 | 50.0 % / 0.0 % / 47.1 s / 6.8 | 52.5 % / 0.0 % / 52.4 s / 7.3 | 49.5 % / 0.0 % / 47.0 s / 6.8 | 51.0 % / 0.0 % / 48.8 s / 7.0 |

#### Abilities (equal stats, 5/5/5/5): large, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 44.5 % / 0.0 % / 50.3 s / 8.0 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 48.2 % / 0.0 % / 50.0 s / 7.9 |
| **FLY** 5/5/5/5 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 42.0 % / 0.0 % / 49.3 s / 7.7 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 47.3 % / 0.0 % / 49.6 s / 7.8 |
| **ROLL** 5/5/5/5 | 55.5 % / 0.0 % / 50.3 s / 8.0 | 58.0 % / 0.0 % / 49.3 s / 7.7 | 43.5 % / 0.0 % / 47.3 s / 7.4 | 52.5 % / 0.0 % / 51.2 s / 7.8 | 55.3 % / 0.0 % / 50.3 s / 7.8 |
| **HIDE** 5/5/5/5 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 47.5 % / 0.0 % / 51.2 s / 7.8 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 49.2 % / 0.0 % / 50.3 s / 7.8 |

#### Abilities (archetype stats): empty, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 48.5 % / 0.0 % / 38.8 s / 5.2 | 62.0 % / 0.0 % / 38.0 s / 5.5 | 85.5 % / 0.0 % / 37.1 s / 5.8 | 39.0 % / 0.0 % / 37.7 s / 5.6 | 62.2 % / 0.0 % / 37.6 s / 5.6 |
| **FLY** 4/6/4/6 | 38.0 % / 0.0 % / 38.0 s / 5.5 | 46.5 % / 0.0 % / 36.6 s / 5.0 | 38.5 % / 0.0 % / 38.2 s / 5.5 | 11.0 % / 0.0 % / 33.5 s / 5.0 | 29.2 % / 0.0 % / 36.6 s / 5.4 |
| **ROLL** 7/6/5/2 | 14.5 % / 0.0 % / 37.1 s / 5.8 | 61.5 % / 0.0 % / 38.2 s / 5.5 | 51.0 % / 0.0 % / 35.4 s / 4.6 | 34.5 % / 0.0 % / 38.7 s / 5.5 | 36.8 % / 0.0 % / 38.0 s / 5.6 |
| **HIDE** 4/5/4/7 | 61.0 % / 0.0 % / 37.7 s / 5.6 | 89.0 % / 0.0 % / 33.5 s / 5.0 | 65.5 % / 0.0 % / 38.7 s / 5.5 | 48.5 % / 0.0 % / 35.5 s / 5.0 | 71.8 % / 0.0 % / 36.7 s / 5.4 |

#### Abilities (archetype stats): empty, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 44.5 % / 0.0 % / 39.1 s / 5.9 | 63.5 % / 0.0 % / 39.5 s / 6.2 | 68.0 % / 0.0 % / 38.9 s / 6.2 | 54.5 % / 0.0 % / 38.7 s / 6.1 | 62.0 % / 0.0 % / 39.1 s / 6.2 |
| **FLY** 4/6/4/6 | 36.5 % / 0.0 % / 39.5 s / 6.2 | 49.5 % / 0.0 % / 39.0 s / 5.9 | 25.5 % / 0.0 % / 39.0 s / 6.1 | 12.5 % / 0.0 % / 32.9 s / 5.4 | 24.8 % / 0.0 % / 37.2 s / 5.9 |
| **ROLL** 7/6/5/2 | 32.0 % / 0.0 % / 38.9 s / 6.2 | 74.5 % / 0.0 % / 39.0 s / 6.1 | 53.0 % / 0.0 % / 38.4 s / 5.4 | 72.5 % / 0.0 % / 37.7 s / 5.8 | 59.7 % / 0.0 % / 38.5 s / 6.0 |
| **HIDE** 4/5/4/7 | 45.5 % / 0.0 % / 38.7 s / 6.1 | 87.5 % / 0.0 % / 32.9 s / 5.4 | 27.5 % / 0.0 % / 37.7 s / 5.8 | 49.0 % / 0.0 % / 34.8 s / 5.4 | 53.5 % / 0.0 % / 36.4 s / 5.8 |

#### Abilities (archetype stats): cup-and-book, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 51.5 % / 0.0 % / 46.3 s / 7.4 | 64.0 % / 0.0 % / 43.8 s / 7.4 | 64.0 % / 0.0 % / 40.4 s / 7.3 | 58.0 % / 0.0 % / 43.9 s / 7.5 | 62.0 % / 0.0 % / 42.7 s / 7.4 |
| **FLY** 4/6/4/6 | 36.0 % / 0.0 % / 43.8 s / 7.4 | 44.0 % / 0.0 % / 43.5 s / 7.2 | 27.5 % / 0.0 % / 41.5 s / 6.9 | 54.0 % / 0.0 % / 44.3 s / 7.4 | 39.2 % / 0.0 % / 43.2 s / 7.2 |
| **ROLL** 7/6/5/2 | 36.0 % / 0.0 % / 40.4 s / 7.3 | 72.5 % / 0.0 % / 41.5 s / 6.9 | 50.0 % / 0.0 % / 43.3 s / 6.5 | 75.0 % / 0.0 % / 42.0 s / 6.9 | 61.2 % / 0.0 % / 41.3 s / 7.0 |
| **HIDE** 4/5/4/7 | 42.0 % / 0.0 % / 43.9 s / 7.5 | 46.0 % / 0.0 % / 44.3 s / 7.4 | 25.0 % / 0.0 % / 42.0 s / 6.9 | 50.0 % / 0.0 % / 43.7 s / 7.3 | 37.7 % / 0.0 % / 43.4 s / 7.3 |

#### Abilities (archetype stats): cup-and-book, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 43.9 s / 7.3 | 61.0 % / 0.0 % / 44.1 s / 7.7 | 54.5 % / 0.0 % / 40.8 s / 7.5 | 66.5 % / 0.0 % / 44.7 s / 7.7 | 60.7 % / 0.0 % / 43.2 s / 7.6 |
| **FLY** 4/6/4/6 | 39.0 % / 0.0 % / 44.1 s / 7.7 | 49.5 % / 0.0 % / 42.8 s / 7.1 | 19.0 % / 0.0 % / 40.1 s / 6.9 | 56.5 % / 0.0 % / 44.9 s / 7.4 | 38.2 % / 0.0 % / 43.0 s / 7.3 |
| **ROLL** 7/6/5/2 | 45.5 % / 0.0 % / 40.8 s / 7.5 | 81.0 % / 0.0 % / 40.1 s / 6.9 | 52.0 % / 0.0 % / 41.5 s / 6.4 | 76.0 % / 0.0 % / 41.2 s / 7.2 | 67.5 % / 0.0 % / 40.7 s / 7.2 |
| **HIDE** 4/5/4/7 | 33.5 % / 0.0 % / 44.7 s / 7.7 | 43.5 % / 0.0 % / 44.9 s / 7.4 | 24.0 % / 0.0 % / 41.2 s / 7.2 | 51.0 % / 0.0 % / 43.1 s / 7.3 | 33.7 % / 0.0 % / 43.6 s / 7.4 |

#### Abilities (archetype stats): small, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 47.5 % / 0.0 % / 28.7 s / 3.9 | 34.0 % / 0.0 % / 28.9 s / 3.8 | 51.5 % / 0.0 % / 27.3 s / 3.6 | 11.5 % / 0.0 % / 27.9 s / 3.9 | 32.3 % / 0.0 % / 28.0 s / 3.8 |
| **FLY** 4/6/4/6 | 66.0 % / 0.0 % / 28.9 s / 3.8 | 46.0 % / 0.0 % / 29.5 s / 3.8 | 22.0 % / 0.0 % / 26.0 s / 3.3 | 13.0 % / 0.0 % / 26.3 s / 3.7 | 33.7 % / 0.0 % / 27.1 s / 3.6 |
| **ROLL** 7/6/5/2 | 48.5 % / 0.0 % / 27.3 s / 3.6 | 78.0 % / 0.0 % / 26.0 s / 3.3 | 53.0 % / 0.0 % / 25.4 s / 2.8 | 71.5 % / 0.0 % / 24.6 s / 3.0 | 66.0 % / 0.0 % / 26.0 s / 3.3 |
| **HIDE** 4/5/4/7 | 88.5 % / 0.0 % / 27.9 s / 3.9 | 87.0 % / 0.0 % / 26.3 s / 3.7 | 28.5 % / 0.0 % / 24.6 s / 3.0 | 54.0 % / 0.0 % / 26.7 s / 3.6 | 68.0 % / 0.0 % / 26.3 s / 3.5 |

#### Abilities (archetype stats): small, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 53.5 % / 0.0 % / 30.0 s / 4.1 | 5.5 % / 0.0 % / 27.9 s / 4.4 | 88.5 % / 0.0 % / 30.8 s / 4.1 | 13.0 % / 0.0 % / 28.7 s / 4.4 | 35.7 % / 0.0 % / 29.1 s / 4.3 |
| **FLY** 4/6/4/6 | 94.5 % / 0.0 % / 27.9 s / 4.4 | 51.0 % / 0.0 % / 29.8 s / 4.0 | 83.5 % / 0.0 % / 33.6 s / 4.8 | 48.0 % / 0.0 % / 31.0 s / 4.5 | 75.3 % / 0.0 % / 30.9 s / 4.6 |
| **ROLL** 7/6/5/2 | 11.5 % / 0.0 % / 30.8 s / 4.1 | 16.5 % / 0.0 % / 33.6 s / 4.8 | 50.5 % / 0.0 % / 29.2 s / 3.2 | 20.5 % / 0.0 % / 31.1 s / 4.4 | 16.2 % / 0.0 % / 31.8 s / 4.4 |
| **HIDE** 4/5/4/7 | 87.0 % / 0.0 % / 28.7 s / 4.4 | 52.0 % / 0.0 % / 31.0 s / 4.5 | 79.5 % / 0.0 % / 31.1 s / 4.4 | 49.5 % / 0.0 % / 27.9 s / 3.8 | 72.8 % / 0.0 % / 30.3 s / 4.4 |

#### Abilities (archetype stats): large, NORMAL v NORMAL

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 49.5 % / 0.0 % / 47.0 s / 6.8 | 59.0 % / 0.0 % / 50.2 s / 7.4 | 58.0 % / 0.0 % / 52.0 s / 7.7 | 58.5 % / 0.0 % / 46.8 s / 7.0 | 58.5 % / 0.0 % / 49.6 s / 7.4 |
| **FLY** 4/6/4/6 | 41.0 % / 0.0 % / 50.2 s / 7.4 | 50.5 % / 0.0 % / 45.4 s / 6.7 | 16.5 % / 0.0 % / 46.1 s / 7.1 | 12.0 % / 0.0 % / 44.0 s / 7.0 | 23.2 % / 0.0 % / 46.8 s / 7.2 |
| **ROLL** 7/6/5/2 | 42.0 % / 0.0 % / 52.0 s / 7.7 | 83.5 % / 0.0 % / 46.1 s / 7.1 | 50.0 % / 0.0 % / 47.1 s / 6.4 | 62.5 % / 0.0 % / 55.2 s / 7.6 | 62.7 % / 0.0 % / 51.1 s / 7.5 |
| **HIDE** 4/5/4/7 | 41.5 % / 0.0 % / 46.8 s / 7.0 | 88.0 % / 0.0 % / 44.0 s / 7.0 | 37.5 % / 0.0 % / 55.2 s / 7.6 | 57.0 % / 0.2 % / 44.0 s / 6.6 | 55.7 % / 0.0 % / 48.7 s / 7.2 |

#### Abilities (archetype stats): large, HARD v HARD

Row's win share against the column, then clock / s / hits per ring-out of the pair.

| row v col | JUMP | FLY | ROLL | HIDE | v field |
|---|---|---|---|---|---|
| **JUMP** 5/5/5/5 | 50.0 % / 0.0 % / 49.8 s / 7.9 | 52.5 % / 0.0 % / 50.5 s / 8.2 | 43.0 % / 0.0 % / 51.7 s / 8.1 | 57.5 % / 0.0 % / 48.7 s / 8.0 | 51.0 % / 0.0 % / 50.3 s / 8.1 |
| **FLY** 4/6/4/6 | 47.5 % / 0.0 % / 50.5 s / 8.2 | 43.0 % / 0.0 % / 48.7 s / 8.1 | 23.0 % / 0.0 % / 48.4 s / 7.7 | 16.5 % / 0.0 % / 45.9 s / 7.8 | 29.0 % / 0.0 % / 48.3 s / 7.9 |
| **ROLL** 7/6/5/2 | 57.0 % / 0.0 % / 51.7 s / 8.1 | 77.0 % / 0.0 % / 48.4 s / 7.7 | 56.0 % / 0.0 % / 48.3 s / 7.0 | 78.5 % / 0.0 % / 50.1 s / 7.6 | 70.8 % / 0.0 % / 50.1 s / 7.8 |
| **HIDE** 4/5/4/7 | 42.5 % / 0.0 % / 48.7 s / 8.0 | 83.5 % / 0.0 % / 45.9 s / 7.8 | 21.5 % / 0.0 % / 50.1 s / 7.6 | 52.5 % / 0.0 % / 47.6 s / 7.6 | 49.2 % / 0.0 % / 48.2 s / 7.8 |

#### Stats: empty, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 39.5 % / 0.0 % | 17.5 % / 0.0 % | 67.0 % / 0.0 % | 82.5 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 39.5 % / 0.0 % | 17.5 % / 0.0 % | 67.0 % / 0.0 % | 82.5 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 44.0 % / 0.0 % | 22.0 % / 0.0 % | 57.5 % / 0.0 % | 79.5 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 39.5 % / 0.0 % | 17.5 % / 0.0 % | 67.0 % / 0.0 % | 82.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: empty, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 38.5 % / 0.0 % | 5.5 % / 0.0 % | 42.5 % / 0.0 % | 46.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 38.5 % / 0.0 % | 5.5 % / 0.0 % | 42.5 % / 0.0 % | 46.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 44.0 % / 0.0 % | 13.5 % / 0.0 % | 52.5 % / 0.0 % | 64.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 38.5 % / 0.0 % | 5.5 % / 0.0 % | 42.5 % / 0.0 % | 46.0 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: cup-and-book, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 57.0 % / 0.0 % | 38.0 % / 0.0 % | 64.0 % / 0.0 % | 32.5 % / 0.0 % | 99.5 % / 0.0 % |
| FLY | 57.5 % / 0.0 % | 33.5 % / 0.0 % | 64.5 % / 0.0 % | 33.0 % / 0.0 % | 97.5 % / 0.0 % |
| ROLL | 50.5 % / 0.0 % | 33.0 % / 0.0 % | 67.0 % / 0.0 % | 36.0 % / 0.0 % | 96.5 % / 0.0 % |
| HIDE | 57.0 % / 0.0 % | 38.0 % / 0.0 % | 64.0 % / 0.0 % | 32.5 % / 0.0 % | 99.5 % / 0.0 % |

#### Stats: cup-and-book, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 65.5 % / 0.0 % | 51.5 % / 0.0 % | 72.0 % / 1.8 % | 31.5 % / 0.0 % | 96.0 % / 0.5 % |
| FLY | 58.0 % / 0.0 % | 47.5 % / 0.0 % | 68.0 % / 0.0 % | 27.5 % / 0.0 % | 95.5 % / 1.3 % |
| ROLL | 58.5 % / 0.0 % | 28.0 % / 0.0 % | 65.0 % / 3.0 % | 31.5 % / 0.0 % | 99.0 % / 0.0 % |
| HIDE | 65.5 % / 0.0 % | 51.5 % / 0.0 % | 72.0 % / 1.8 % | 31.5 % / 0.0 % | 96.0 % / 0.5 % |

#### Stats: small, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 42.0 % / 0.0 % | 22.5 % / 0.0 % | 49.0 % / 0.0 % | 90.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 41.5 % / 0.0 % | 23.5 % / 0.0 % | 47.0 % / 0.0 % | 90.5 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 13.5 % / 0.0 % | 46.0 % / 0.0 % | 25.0 % / 0.0 % | 70.5 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 42.0 % / 0.0 % | 22.5 % / 0.0 % | 49.0 % / 0.0 % | 90.0 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: small, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 20.0 % / 0.0 % | 8.0 % / 0.0 % | 26.5 % / 0.0 % | 58.5 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 20.0 % / 0.0 % | 8.0 % / 0.0 % | 26.5 % / 0.0 % | 58.5 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 21.5 % / 0.0 % | 15.5 % / 0.0 % | 38.0 % / 0.0 % | 82.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 20.0 % / 0.0 % | 8.0 % / 0.0 % | 26.5 % / 0.0 % | 58.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: large, NORMAL v NORMAL

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 39.0 % / 0.0 % | 72.5 % / 0.6 % | 46.5 % / 0.0 % | 70.5 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 39.0 % / 0.0 % | 73.0 % / 0.2 % | 45.5 % / 0.0 % | 70.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 42.5 % / 0.0 % | 71.5 % / 0.0 % | 46.0 % / 0.2 % | 62.0 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 39.0 % / 0.0 % | 72.5 % / 0.6 % | 46.5 % / 0.0 % | 70.5 % / 0.0 % | 100.0 % / 0.0 % |

#### Stats: large, HARD v HARD

The specialist's (or 8/8/8/8's) win share against 5/5/5/5 with the same ability, then the clock.

| ability | POWER 8 | SPEED 8 | GUARD 8 | AGILITY 8 | 8/8/8/8 |
|---|---|---|---|---|---|
| JUMP | 45.0 % / 0.0 % | 65.5 % / 0.0 % | 47.5 % / 0.0 % | 25.0 % / 0.0 % | 100.0 % / 0.0 % |
| FLY | 44.5 % / 0.0 % | 65.0 % / 0.0 % | 48.5 % / 0.0 % | 25.0 % / 0.0 % | 100.0 % / 0.0 % |
| ROLL | 51.5 % / 0.0 % | 52.0 % / 0.0 % | 51.0 % / 0.0 % | 50.5 % / 0.0 % | 100.0 % / 0.0 % |
| HIDE | 45.0 % / 0.0 % | 65.5 % / 0.0 % | 47.5 % / 0.0 % | 25.0 % / 0.0 % | 100.0 % / 0.0 % |



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

