# T60 — bot-beats-idle

status: doing
created: 2026-11-12
phase: v2 wave 4 (ai/build-plan-v2.md, "The game")
gate: G5 duel on the Pixel (a bot that does nothing on a table with a cup and a book would be the first thing a child sees)
cut-if-slips: the "moving target" half of the acceptance (keep the idle half); never the root cause in the Result
depends: T57 merged (the bots, the matrix, `docs/duel-balance.md`). Independent of T58 / T59 (`core/session`, `shared`)
tier: rudeus-opus-extra (root-causing a behaviour of the bot's decision code, then a numeric check against the matrix)

## What was found (T58's implementer, a throw-away probe; not pinned by any test)
On `DuelArenas.CUP_AND_BOOK` a NORMAL or HARD bot of any ability **never scores against a fighter that does not
move**: the idle side wins 2:0 on two 60 s timeouts, 3 of 3 seeds, from either mark. EASY bots win 3 of 3. On EMPTY
and SMALL every level wins (0:2 in about 22 s and 15 s). T57 measured bots against bots only, so nothing saw it.
A scanned table with a cup and a book is the normal case on the phone, and the first match a child plays is against
NORMAL: a child who stands still must not beat Normal and Hard.

## Goal
A bot at any level beats a fighter that sends no input, on every tuning arena, from either mark, with any ability on
either side; and the same against a target that only shuffles around its mark. The cause is found, written down and
fixed in the bot's decision code with the smallest change. Rules, physics and `DuelParams` do not change.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/DuelBot.kt, BotTuning.kt
- core/src/commonTest/kotlin/kz/rudy/ar/core/duel/DuelIdleTest.kt (new); DuelBotTest.kt and DuelNewRulesTest.kt only
  where a tuning constructor gains a field (mechanical, listed in the Result)
- cli/src/main/kotlin/kz/rudy/ar/cli/DuelCommand.kt and core/.../duel/DuelArenas.kt, DuelSim.kt — only if the matrix
  gains the idle row (B12 below); otherwise untouched
- docs/duel-balance.md (a T60 section: the idle table before / after, the matrix before / after)
- ai/tasks/T60-bot-beats-idle.md (the Result)

Do not touch `DuelPhysics.kt`, `DuelParams.kt`, `DuelRules.kt`, `DuelMatch.kt`, `DuelRunner.kt`, `DuelState.kt`,
`core/session/**` (T58 is in review there), `core/physics/**`, `core/domain/**`, `shared/**`, `androidApp/**`, Gradle.

## Contract
- **Off position.** `theNewRulesSwitchedOffAreThePreviousPhysics` replays bot matches with `PRE_T57_TUNING` against
  fingerprints recorded at 207acc6 and must stay green with its constants untouched. So every new decision path is
  behind a `BotTuning` / `BotSkill` number or flag whose value in `PRE_T57_TUNING` (set there explicitly, the one
  mechanical edit allowed in `DuelNewRulesTest.kt`) reproduces today's decisions bit for bit, and draws nothing from
  the bot's PRNG when off. If the cause turns out to be a plain bug whose fix cannot be gated without keeping the bug
  as a mode, say so and stop before changing the goldens: that is the orchestrator's decision.
- **Determinism.** Decisions stay on the tick grid, independent of the frame length; the matrix's replay at 16 ms
  stays identical (B9).
- **No rule change.** The bot only chooses differently among the inputs it already has (`MoveTo`, Dash, Jump,
  Special). No new knowledge the human does not have (it may not read the opponent's cooldowns if it does not today).
- **Levels keep their order**: `hardBeatsEasy` and `normalIsBetweenEasyAndHard` stay green with their thresholds
  untouched.

## Acceptance
| # | Criterion | Threshold |
|---|---|---|
| I1 | A fighter that sends no input, human side, against a bot: four arenas (EMPTY, CUP_AND_BOOK, SMALL, LARGE) x NORMAL, HARD x the four bot abilities x the idle side's four abilities (sample: at least the diagonal and JUMP against each) x both marks x 3 seeds | the bot wins every match, and by ring-outs, not by the clock |
| I2 | The same against EASY | the bot wins at least 2 of 3 per cell and never loses 0:2 by timeouts on every seed |
| I3 | A target that shuffles: `MoveTo` a point 1 radius from its mark, a new one every 500 ms (seeded) | NORMAL and HARD win at least 8 of 9 per arena |
| I4 | No bot stands off for a whole round: the longest stretch of FIGHT in which a NORMAL or HARD bot neither closes the distance nor attacks, the opponent being reachable on foot | under 10 s, in I1 and in the matrix's bot-v-bot matches (report the maximum) |
| B1..B11 | `duel matrix --n 200` before and after | no row changes its verdict for the worse; every range moves by less than 5 points or the move is explained; B9 identical; B11 still PASS |
If I1 can be met only by making a row of the matrix worse, keep the best candidate that meets I1 and report the row.

## Method (time-box: the root cause first, then at most six fix candidates)
1. Reproduce as a red test: `DuelIdleTest.aBotBeatsAFighterThatStandsStill` (I1's sample through `DuelRunner` with a
   human side that sends nothing). It is red on CUP_AND_BOOK at NORMAL and HARD before the fix: record its
   numbers per arena and level as the "before" table, and commit the test together with the fix (no red commit).
2. Root cause, by a trace of one CUP_AND_BOOK match (per think tick: where the bot stands, the target it chose and
   why, why it did not dash): one paragraph in the Result naming the function and the condition. Check whether the
   same condition can hold against a moving child (for example a child who stands behind the cup or on the book).
3. The fix, gated as the contract says; tests: `aBotBeatsAFighterThatStandsStill`, `aBotBeatsATargetThatShuffles`,
   `easyStillBeatsAnIdleFighter`, `noBotStandsOffForARound`, `theFixSwitchedOffIsTheOldBot` (a sample of the
   goldens' matches through the new code with the gate off equals the recorded prints).
4. `./gradlew :cli:run --args="duel matrix --n 200 --out build/duel" -q` before (at the base) and after; the tables
   in `docs/duel-balance.md` under "T60".
5. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`. Keep the duel test package
   under about 100 s; say how long it takes.

## Done when
- `scripts/verify.sh` exit 0; I1..I4 measured and in the Result; the matrix before / after in the doc
- no file outside "Owns" changed

## Result (filled on acceptance)
- root cause: ...
- measured: ...
- what a human must decide: ...
- cut/deferred: ...
