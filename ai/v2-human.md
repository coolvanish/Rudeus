# v2 waves 1–3 — what only a human can do (2026-11-11)

`claude/practical-dijkstra-2uveq7` (from `main` at 4f58c28): the first three waves of `ai/build-plan-v2.md`. Verify in
the cloud container after T48: core 687, shared 41, androidApp 3 tests, 0 failed; APK 63 MB; `:core` and `:shared`
compile for `iosSimulatorArm64`. Every device row is UNTESTED: the container has no Pixel, no API key and no Mac.

## 1. What the agents did (read the diffs: `git log --oneline 4f58c28..HEAD`)
| Brief | What | Status |
|---|---|---|
| wave 0 | `iosArm64` + `iosSimulatorArm64` klib targets in `:core` and `:shared` (Gradle), `ktor-client-darwin`, `Platform.ios.kt`, identity hash that compiles for iOS; `scripts/verify.sh` gates the iOS compile; contract types `FighterStats`, `Stat`, `SpriteView` | done; **Gradle diffs need your approval** |
| T39 ios-core-services | `IoCharacterFolder` / `IoFixtureSource` / `IoMetricsSink` on kotlinx-io (crash-safe replace and delete, recovery on first use), the first leaf `expect/actual` (`renameAtomically`: `File.renameTo` on Android for API 24, `atomicMove` on JVM/iOS), `ios-core` CI job | done; Android actual and iOS execution UNTESTED; **Gradle diff needs your approval** |
| T40 sheet-fighter-stats | stats, reasons, view and faces in the Spark sheet (prompt, strict schema, parser), `StatBudget.normalise` (sum always 20), archetypes for v1.0 sheets | done; needs a live re-record on the Mac |
| T41 sprite-views | `SpriteViews`, `ViewPicker.pick(facing, toCamera, …)`, `MetaModels.drawView`, pipeline stage `views` (off by default), bundle and saved storage, `cli views` with a contact sheet | done; **G3 needs your Mac** |
| T42 duel-physics | dash, stat knockback, wall slam, ring-out at the edge for everyone, safe separation, timed events, fixed 8 ms sub-steps | done |
| T43 thin-host-map | per-file map of `:androidApp` (2 121 code lines: 995 platform-free), the `TableEngine` seam, the step order | done |
| T44 fighter-card-ui | `FighterStatsCard` on Processing "Done"; **Done now waits for a tap on Next** (the 2 s auto-advance is gone) | done; needs your Pixel look |
| T45 duel-match-specials | COUNTDOWN / FIGHT / POINT / RESULT, first to two, marks, `rearrange` for a rescan between rounds, one special per ability | done |
| T47 thin-host S1 | `GameLoop` moved verbatim to `core.session`, 22 tests; host diff 7 lines | needs your Pixel run |
| T49 duel-ui | pure `duelHud` model, HUD, opponent picker, VS card, result; not wired into the app | done; look UNTESTED until T50 |
| T52 thin-host S2 | billboard maths moved to `core.session.BillboardPoser` -> `NodePose`, 25 tests; `CharacterNode.kt` 252 -> 162 lines | needs your Pixel run |
| T48 cli-duel-sim-balance | `cli duel match / series / matrix`, a 120 x 60 cm arena, tuned defaults, **wobble** (each hit taken in a round makes the next knockback larger; cleared every round; `wobblePerHit = 0` is the old physics), swept knockback (no tunnelling at any speed) | done; five acceptance rows still FAIL, see §3 G4 and §4 |
| T53 thin-host S3a | brief only | waits for the two Pixel runs above |
| T55 duel-bot-sim | seeded bot (EASY / NORMAL / HARD), `DuelRunner` (bots decide on a tick grid: identical matches at any frame length), `DuelSim`, three tuning arenas. Measured: plain dashes almost never ring anyone out (FLY / HIDE mirrors end 92–99 % of rounds by the clock): the numbers are not a game yet, T48 tunes them | done |

## 2. Install and log
Check the branch out in the MAIN checkout on the Mac (a worktree build has no key), then:
```
./gradlew :androidApp:installDebug && scripts/device.sh run
```
Second terminal first: `scripts/device.sh log`. Unlock the phone. A cup on the textured cloth is enough.

## 3. Gates
### G1 foundation — PASS in the container
`scripts/verify.sh` compiles `:core` and `:shared` for iOS. Nothing iOS has been linked or run.

### Thin host on the Pixel (T47, T52) — UNTESTED
| Criterion | Status | Evidence |
|---|---|---|
| **"Hand over the lens" (T47):** two characters, tap so one walks across, cover the lens ~3 s, uncover: the Paused scrim while covered; both reappear where they stood; the walker finishes its walk to the tapped spot; nobody jumps, slides or returns to the middle | UNTESTED (JVM PASS: `GameLoopTest`, 22) | no `cast_add` in the log after the blip; `docs/evidence/v2-t47-lens.png` |
| **"Walk, bow, jump" (T52):** tap right then left of one character: it turns once each way and bobs; Bye after each walk: it bows toward the side it looks at, stays facing you, feet on the table; Jump: stretch going up, squash on landing | UNTESTED (JVM PASS: `BillboardPoserTest`, 25; 200 000-frame equivalence run, 0 differing bits) | `docs/evidence/v2-t52-bow.png` |
If T47 fails: `git revert 6102cbc`. If T52 fails: `git revert 8c97f42`; if still wrong, also `git revert f881b35`.

### G2 stats — JVM PASS, live UNTESTED
| Criterion | Status | Evidence |
|---|---|---|
| Every bundled sheet fights with a balanced 20 | PASS (`legacySheetHasNoStatsAndFightsWithItsArchetype`) | core tests |
| Spark returns stats, reasons, view, faces under the strict schema | UNTESTED | `./gradlew :cli:run --args="character --live --record --ref toy_dino --out runs"`; if the call is 400 on the nested `why`, drop `strict` (see the TODO at `CHARACTER_SHEET_FORMAT`) |
| The card on "Done" reads well in kk / ru / en and fits a short screen | UNTESTED | `docs/evidence/v2-t44-card-{kk,ru,en}.png` |

### G3 views — UNTESTED (Mac, about $0.02 per character)
```
./gradlew :cli:run --args="views --live --ref toy_dino --record --out build/views"
```
Repeat for at least five bundled refs, open each `build/views/<ref>/contact.png`, fill the table in its `README.md`.
Verdict: at least 4 of 5 usable BACK and FRONT; else cut FRONT, then cut views (plan, "Risks and cuts" 1).

### G4 duel core — partly PASS (`docs/duel-balance.md`; reproduce: `./gradlew :cli:run --args="duel matrix --n 200 --out build/duel" -q`, about 80 s)
| Criterion | Status | Evidence |
|---|---|---|
| Every match ends; any frame split gives the same match; sides are fair | PASS | `core/duel` tests; 57 600 of 57 600 matches identical at 16 and 33 ms |
| Rounds are decided on the table: share ended by the clock | PASS: 0–1.9 % (was up to 97.8 %) | A3 |
| Hits per ring-out 2..8 per pair | FAIL: 2.5–9.4, seven pairs above 8 (six on the 120 cm table, all with FLY) | A4 |
| No ability above 65 % of the field | FAIL: ROLL 67–88 % on three arenas, FLY 7–26 % | A1 |
| Every specialist viable (one stat at 8) | FAIL: 6–86 %, 45 cells out; SPEED barely matters | A6 |
| Match length 30..120 s per pair | FAIL: 15–62 s; the 30 x 30 cm table plays in about 24 s | A5 |

### G5 duel on the Pixel, G6 iOS — not started
G5 needs T53, T56, T54, T50. G6 needs T46 and T51 on the Mac.

## 4. Decisions only you can make
1. **Done waits for a tap** (T44). To get the 2 s auto-advance back: `git revert ed89103`.
2. **Copy.** `stats_title` «Кейіпкердің күші / Сила героя» repeats the stat «Күш / Сила»; `stats_from_drawing`; the duel
   strings the implementer was least sure of in Kazakh: `special_stomp` «Дүрс ет», `special_ram` «Зымыра», `duel_dash`
   «Ұмтыл», `duel_go` «Кеттік», `duel_title` «Сайыс». All in `shared/.../ui/Strings.kt` and the DESIGN.md copy table.
3. **Duel rules** (each is a decision about the game, measured in `docs/duel-balance.md`).
   **Decided 2026-11-12 (you, in chat):** a. wobble stays; b. yes; c. yes, SPEED lengthens the dash, and FLY's
   archetype may also change if still needed; e. height matters for hits (a jump over a dash is a dodge). T57
   (`ai/tasks/T57-duel-balance-2.md`) implements them. d. and f. were not decided: T57 measures them again.
   a. **Wobble stays?** It is on. Without it a ring-out on a 120 cm table took 12–34 hits; with it 3.5–6.7.
      Off switch: `wobblePerHit = 0f` in `DuelParams`.
   b. **Stomp and ram spend the dash cooldown?** Today they do not, so they had to be weakened (0.85x, 1.1x) and
      ROLL still dominates. Recommended: yes.
   c. **SPEED does more than walking?** Today it only sets walk speed and barely matters to a fight, which is why
      FLY (3/7/3/7) is the weakest. Recommended: SPEED also lengthens the dash. Or change FLY's archetype.
   d. **A HIDE counter can be a one-hit ring-out on a 30 cm table** (2.0x slides about 9 cm, the mark is 7 cm from
      the edge). Lower `counterKnockScale` (HIDE gets weaker) or accept it as the reward for a read.
   e. **Hits ignore height** (T42): a jump never dodges a dash, a fighter on a book can be pushed from the table.
   f. **The 30 x 30 cm table plays in about 24 s.** Accept, or lengthen by rule (marks farther from the edge).
4. **Hooks.** `.claude/settings.json` is not in the repo checkout the cloud gets: copy `docs/claude-settings.json` to
   `.claude/settings.json` if you want the guards active there. `session-start.sh` still counts days to Nov 4.
5. **CI.** The `ios-core` job (macOS, `:core:iosSimulatorArm64Test`) is manual: run it once from the Actions tab to see
   the common tests execute on iOS for the first time.

## 5. Housekeeping only you can do
- Approve the hand-review diffs: `git diff 4f58c28..HEAD -- '*.gradle.kts' gradle/libs.versions.toml androidApp/src/main`.
- Merge to `main` (or open a PR) when the Pixel rows pass.
- The cloud session left agent worktrees under `.claude/worktrees/agent-*` with branches `worktree-agent-*`; they
  exist only in the cloud container and vanish with it.
- The cloud environment `rudeus` (hosts: `api.foojay.io`, `cdn.azul.com`, `dl.google.com`, `mcp.context7.com`; a setup
  script that installs the Android SDK) was created for this repo; its setup script has not run on a fresh container yet.

## 6. Known limits (by design today)
- The duel exists only in `:core` and as unwired UI: nothing on the phone plays it yet.
- T33 pose frames never reach the screen (the host never reads `ArCharacter.frames`); T50 builds the picture swap.
- An action issued on the first frame back from a tracking loss loses its event (no jump sound); v1.0 behaviour, pinned.
- Android still saves characters through `FileCharacterFolder`, which cannot rewrite an existing id (latent: ids only grow).
- `IoCharacterFolder` is not safe against power loss (no fsync), like `File*`.
- A fighter that comes down into a gap narrower than its own 6 cm between two blocking objects stays overlapping one of them (found in T48; T42 physics; real scans can produce such gaps).
- No key ships in a store build: a public release needs a key proxy (plan, "Not now").

## 7. Device results
(none yet)
