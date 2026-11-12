# Rudeus v2 — "Duel on the table: draw your fighter" (from Nov 11, 2026)

The hackathon build (`v1.0`, `ai/build-plan.md`) stays as the record of what shipped. This file is the plan after it.
Decisions below were taken with the user on 2026-11-11; where they contradict `ai/build-plan.md`, this file wins.

## What changed on Nov 11
| Topic | v1.0 | v2 decision |
|---|---|---|
| Product | sandbox: a drawing walks on the table | **Duel**: 1v1 against a bot, push the other fighter off the real table edge |
| Stats | one `Ability` | **draw-to-power**: Spark reads the picture, `:core` normalises to a fixed budget, reasons shown to the child |
| Character look | one billboard sprite, flipped by facing | **2.5D**: FRONT / SIDE / BACK sprites picked by the camera's bearing (Muse draws the missing views) |
| Opponent | none | bot in `:core`, one phone; bot-vs-bot "whose drawing is stronger" comes for free |
| Platforms | Android only, "no expect/actual" | Android first, **iOS-ready**: iOS targets in `:core` and `:shared`, a thin AR host, `expect/actual` for leaf services |
| Deadline | Nov 4 | none; phases with gates instead of days |

## The game (rules v0 — tune with `cli duel`, not by feel)
- Two fighters on the scanned table. Round ends when one leaves the level boundary (ring-out, the existing cliff) or
  the 60 s clock runs out (closest to the boundary loses). Best of three.
- Verbs: tap = walk (exists), **Dash** = a short charge along `facing` that knocks the other back, **Jump** (exists),
  **Special** = one per `Ability` with a cooldown (JUMP stomp shockwave, FLY brief ring-out immunity, ROLL long ram,
  HIDE dodge + counter).
- Since T57 (the user's decisions of 2026-11-12, each with an off switch in `DuelParams`): the stomp and the ram need
  and spend the dash cooldown as well as their own; SPEED lengthens every dash; **height matters for hits**: a dash
  reaches 2.8 cm up and 4 cm down, so a jump over a dash is a dodge and a fighter on a book is safe from a dash until
  the other climbs (and is not shoved by it either: "stands firm"). The stomp's shockwave reaches 9 cm up (a fighter
  on a book and a low jumper are hit, a floating FLY is not): the implementer's number, waiting for the user's yes.
  Wobble (T48): each hit taken in a round makes the next knockback larger; cleared every round.
- The table matters: an OBSTACLE stops knockback (wall-slam = short stun), a PLATFORM is high ground (+25 % knockback
  from above), the boundary is the win condition. Between rounds the child may move real objects; one Scan rebuilds
  the arena. Never a scan mid-round.
- No HP bars, no hit animations: sprites cannot carry them and the cliff already exists in physics.
- Kid-safe: fighters are pushed, never hurt; the loser respawns and says a line.

### Stats (draw-to-power)
`POWER` (dash impulse) · `SPEED` (walk speed and, since T57, the dash's reach) · `GUARD` (knockback resistance) · `AGILITY` (cooldowns, stun recovery).
Each 1..10, **sum always 20** (`FighterStats.BUDGET`). Spark returns raw 1–10 scores from what is visible (claws, wings,
wheels, shell, size) plus a ≤ 4-word reason per stat in kk/ru/en; `StatBudget.normalise` in `:core` turns them into the
budget. A sheet without stats (every v1.0 character) gets the archetype of its ability. Same budget for everyone means
a "weak" drawing does not exist: the child trades one stat for another by what they draw, and re-drawing is the
upgrade path.

## 2.5D views
`SpriteView { FRONT, SIDE, BACK }`. The sheet says which view the drawing is (`baseView`); Muse draws the other two
(`MetaModels.drawView`, job `muse-view`, + 2 edits per character, in parallel, never fails the character).
`ViewPicker.pick(facing, toCamera, previous, views)` is pure `:core` math with 10° hysteresis on the bearing sector
(`toCamera` = camera minus character in the plane; the picker derives the screen basis itself, so no host can get the
handedness wrong); the host only swaps the texture and the mirror flag. Unknown quality until a live spike: `cli views --live --ref <ref>`
writes a contact sheet; a human says yes/no (like the T30 pose spike).

## iOS readiness
- `:core` and `:shared` get `iosArm64` + `iosSimulatorArm64`. Everything that compiles for iOS is common code first;
  `expect/actual` only for leaf services with no common library: HTTP engine, image decode/encode, file system roots,
  identity hash, clock/locale, audio, haptics.
- **The AR view stays a slot**, on both platforms. Android: SceneView in `:androidApp`. iOS: Swift + RealityKit in
  `iosApp`, handed to `MainViewController(arView:)`. Not an `actual`: RealityKit is not reachable from Kotlin, SceneKit
  is deprecated, and neither could be compiled or reviewed here.
- **Thin host**: game loop, cast touch picking, scan orchestration, capture/pipeline orchestration move out of
  `:androidApp` into common code behind plain-data ports (camera pose, hit result, node pose). The Android host
  becomes an adapter; the iOS host is the same adapter written once in Swift. The duel is born in `:core`, so it never
  has to be ported.
- No stub actuals: an `actual` exists only when it works. What iOS lacks is absent from the iOS host, not faked.

## Phases, briefs, order
One brief = one agent = one worktree. Same-wave briefs own disjoint files. Hand-review zone briefs are serial.

| Wave | Brief | Module | Tier | Depends on |
|---|---|---|---|---|
| 0 | contracts commit: `FighterStats`, `Stat`, `SpriteView`, sheet fields | core | orchestrator | — |
| 1 | T39 ios-core-services (common file services on kotlinx-io) — **done** | core, Gradle (hand-review) | opus-high | — |
| 1 | T40 sheet-fighter-stats — **done** | core/meta, core/domain | opus-high | contracts |
| 1 | T41 sprite-views — **done** (G3, the live Muse spike, is the user's) | core/emote, core/meta, core/pipeline, cli | opus-high | contracts |
| 1 | T42 duel-physics — **done** | core/duel | opus-extra | contracts |
| 1 | T43 thin-host-map (design, no code) — **done** | reads androidApp | fable-high | — |
| 2 | T44 fighter-card-ui — **done** | shared | sonnet-high | T40 |
| 2 | T45 duel-match-specials (rounds, points, marks, one special per ability) — **done** | core/duel | opus-extra | T42 |
| 3 | T55 duel-bot-sim (seeded bot, three levels; headless match simulator) — **done** | core/duel | opus-extra | T45 |
| 0 | `:shared` iOS klib targets (done in wave 0: 2 lines of common code + `Platform.ios.kt`) | shared, Gradle (hand-review) | orchestrator | — |
| 4 | T46 ios-entry (`MainViewController`, the `UIKitView` bridge for the AR slot) — moved next to T51: it cannot be run without the Mac | shared/iosMain | opus-high | T43 |
| 2 | T47 thin-host S1: `GameLoop` to `core.session`, verbatim, pinned by tests — **done** (Pixel "hand over the lens": fine by the user's report, 2026-11-12) | core + androidApp (hand-review) | opus-high | T43 |
| 3 | T52 thin-host S2: billboard maths to `BillboardPoser` -> `NodePose` — **done** (Pixel "walk, bow, jump": fine by the user's report, 2026-11-12) | core + androidApp (hand-review) | opus-extra | T47 |
| 3 | T53 thin-host S3a: `TableEngine` (surface lock, cast sync, active member, commands) — **done** (Pixel "place, scan, two friends, lens, replay": fine by the user's report, 2026-11-12) | core + shared + androidApp (hand-review) | opus-extra | T52, Pixel |
| 3 | T56 thin-host S3b — **merged 2026-11-12, `review` until the Pixel check** ("walk, cover, grow, replay"): the frame step and the scenario feed behind `FrameInput` / `CameraFrame`; also puts the host's scan reads behind `engine.level` / `engine.standing()` so the duel stage (T50) does not wait for S5 | core + shared + androidApp (hand-review) | opus-extra (brief by fable-high) | T53 |
| 3 | T54 thin-host S4 — **dispatched 2026-11-12** (`ai/tasks/T54-thin-host-step-4.md`; built in a worktree, merged after T56's Pixel check): taps and finger grab behind `ArPort` | core + androidApp (hand-review) | opus-extra (brief by fable-high) | T56 |
| 3 | T48 cli-duel-sim-balance — **done**: rounds end on the table (clock share 0–1.9 %, was up to 97.8 %); wobble on; ability balance, specialists and per-pair hit counts still FAIL (`docs/duel-balance.md`) | cli, core tuning | opus-extra | T55 |
| 4 | T57 duel-balance-2 — **done** (reviewed twice): the three rules behind off switches (bit-exact with them off), FLY's archetype 4/6/4/6, the bots use every special (new row B11), a second tuning pass. At equal stats NORMAL is in band on all four arenas (39–65 %); HARD has 4 of 32 cells out (JUMP v FLY); archetypes, hopeless pairs and specialists still FAIL (`docs/duel-balance.md`, decisions in `ai/v2-human.md` §4.3) | core/duel, domain (one line), cli | opus-extra | T48 |
| 3 | T49 duel-ui — **done** (not wired; the look is the user's check after T50) (picker, VS, HUD, result): components and pure models only; no `ArSlot.kt` / `App.kt` edits (T53 moves types out of `ArSlot.kt`; the wiring is T50) | shared | sonnet-high | T44, T45 |
| 4 | T50 ar-host-views-duel (also builds the per-frame picture swap: T33 pose frames never reached the screen) | androidApp (hand-review) | fable-high draft | T41, T45, T49, T52–T54, T56 |
| 4 | T51 ios-app-shell (Xcode, Swift RealityKit host) | iosApp | on the Mac | T46; a real AR host also needs S5 (scan) and S6 (capture) of the map |

After every implementing brief: `rudeus-reviewer` on the diff, fixes in the same worktree, merge, status commit.

### What T43's map changed (2026-11-11, `ai/tasks/T43-thin-host-map.md`)
- `:androidApp` is 3 596 lines, 2 121 of them code: 995 platform-free, about 619 AR glue, about 507 Android leaf
  services. A Swift host that gets the 995 for free still writes about 540 lines of AR glue.
- The seam: a `TableEngine` in `core.session` fed `FrameInput` + touches, answering `NodePose`s, asking the host four
  questions through `ArPort` (`hitTable`, `depthGapsOnWay`, `pictureAspect`, `grabScanFrame`). `ArSlot` keeps its shape.
- One step does not make the host thin: S2–S4 (T52–T54) come before the duel is wired into the host (T50), or the
  duel wiring is written in `RudeusArView` and moved twice.
- **T33 pose frames never reach the screen**: the host never reads `ArCharacter.frames` (the T33 host draft was lost
  at merge b367873). The picture swap T41 assumed does not exist; T50 builds it.
- `ViewPicker` must be fed the camera-to-character bearing, not the optical axis (they differ by up to half the field
  of view at the screen edges, against a 10° hysteresis). The duel's 60 s round clock runs on simulated time, so a
  tracking loss does not run the round down.
- Of the `expect/actual` candidates only the image codec deserves one, and not before T51.
- Found by T47's characterization tests (behaviour of v1.0, not changed): an action issued on the first frame back
  from a tracking loss loses its event. A jump from `beforeStep` on that frame reports only RESUMED, never JUMPED (no
  jump sound, no HUD reaction); by the same rule a `walkTo` there loses STARTED_WALKING. The duel must not rely on
  per-action events on the resume frame (T45 reads state, not events); fixing the loop is a separate, small brief.

## Gates (measurable)
- **G1 foundation**: `:core` and `:shared` compile for `iosSimulatorArm64` + `iosArm64` (klibs; Linux or the Mac), gated by `scripts/verify.sh`. **Reached 2026-11-11** for the code that exists; every later brief must keep it.
- **G2 stats**: 100 % of `docs/bundled` sheets yield stats with sum 20; legacy sheets parse unchanged.
- **G3 views**: live spike on ≥ 5 bundled characters, human verdict ≥ 4/5 usable BACK and FRONT; else cut to SIDE + BACK only, else cut views.
- **G4 duel core**: `cli duel --all`: no archetype wins > 65 % against the field at equal budget; every match ends
  (no timeouts without a winner); 30 Hz and 60 Hz stepping agree on the winner in ≥ 95 % of seeded matches.
  **State 2026-11-12 (after T57):** matches end, rounds end on the table (clock share 0–0.2 %), 16 and 33 ms steps
  agree exactly (64 000 of 64 000 replays), every special is in use between bots (B11). The balance half: at equal
  5/5/5/5 stats every ability is inside 35–65 % at NORMAL on all four arenas; at HARD 4 of 32 cells are out (JUMP 20–25 %
  on the empty and large tables, FLY 65–84 % on the empty and small ones: a floating FLY is out of the stomp's reach).
  Archetypes (6 cells out), hopeless matchups (54) and one-stat specialists (61 of 128) still fail and need rule
  decisions, not numbers: `./gradlew :cli:run --args="duel matrix --n 200"`. The gate as written ("no archetype above
  65 % of the field") is not reached.
- **G5 duel on the Pixel**: three matches in a row on a real table, ring-outs at the real edge within 2 cm, no tracking reset.
- **G6 iOS**: `iosApp` builds on the Mac and shows the Compose UI with a camera-less placeholder slot.

## What only the human can do
- **Live model calls** (the key is in `local.properties` on the Mac, never in the cloud): re-record `spark-sheet`
  fixtures with stats, run the `muse-view` spike, judge the contact sheets.
- **Pixel**: every `androidApp` change is a draft until it runs on the table (G5).
- **Mac**: anything that links for iOS (G6); the cloud container can at best compile klibs.
- Approve hand-review diffs (`androidApp/**`, Gradle).

## Risks and cuts (in order)
1. Muse cannot hold a character across views → cut FRONT, keep BACK; then cut views (billboard as in v1.0).
2. Kotlin/Native iOS compilation unavailable in the cloud → iOS source sets are written but compiled only on the Mac / a macOS CI job.
3. Thin-host refactor regresses AR on the Pixel → it lands in steps, each behaviour-preserving with JVM tests; a step that fails on the Pixel is reverted alone.
4. Duel is not fun with tap-to-walk → fallback mode is bot-vs-bot with the child choosing fighters and arena (the "whose drawing is stronger" show).
5. Specials unbalance the duel → ship Dash + Jump only.

## Not now (written down so nobody starts them)
Two-phone multiplayer · bond/XP layer and evolve-by-redraw persistence (after G5) · true 3D meshes · public store
release (needs a key proxy: the key cannot ship in an APK) · voice in the duel.
