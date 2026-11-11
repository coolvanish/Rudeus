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
- The table matters: an OBSTACLE stops knockback (wall-slam = short stun), a PLATFORM is high ground (+25 % knockback
  from above), the boundary is the win condition. Between rounds the child may move real objects; one Scan rebuilds
  the arena. Never a scan mid-round.
- No HP bars, no hit animations: sprites cannot carry them and the cliff already exists in physics.
- Kid-safe: fighters are pushed, never hurt; the loser respawns and says a line.

### Stats (draw-to-power)
`POWER` (dash impulse) · `SPEED` (walk speed) · `GUARD` (knockback resistance) · `AGILITY` (cooldowns, stun recovery).
Each 1..10, **sum always 20** (`FighterStats.BUDGET`). Spark returns raw 1–10 scores from what is visible (claws, wings,
wheels, shell, size) plus a ≤ 4-word reason per stat in kk/ru/en; `StatBudget.normalise` in `:core` turns them into the
budget. A sheet without stats (every v1.0 character) gets the archetype of its ability. Same budget for everyone means
a "weak" drawing does not exist: the child trades one stat for another by what they draw, and re-drawing is the
upgrade path.

## 2.5D views
`SpriteView { FRONT, SIDE, BACK }`. The sheet says which view the drawing is (`baseView`); Muse draws the other two
(`MetaModels.drawView`, job `muse-view`, + 2 edits per character, in parallel, never fails the character).
`ViewPicker.pick(facing, cameraForward, cameraRight, previous, views)` is pure `:core` math with 10° hysteresis; the
host only swaps the texture and the mirror flag. Unknown quality until a live spike: `cli views --live --ref <ref>`
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
| 1 | T39 ios-targets-core | core, Gradle (hand-review) | opus-high | — |
| 1 | T40 sheet-fighter-stats | core/meta, core/domain | opus-high | contracts |
| 1 | T41 sprite-views | core/emote, core/meta, core/pipeline, cli | opus-high | contracts |
| 1 | T42 duel-physics | core/duel | opus-extra | contracts |
| 1 | T43 thin-host-map (design, no code) | reads androidApp | fable-high | — |
| 2 | T44 fighter-card-ui | shared | sonnet-high | T40 |
| 2 | T45 duel-rules-bot (match, specials, bot, headless sim) | core/duel | opus-extra | T42 |
| 2 | T46 ios-targets-shared | shared, Gradle (hand-review) | opus-high | T39 |
| 2 | T47 thin-host-step-1 (per T43's map) | core + androidApp (hand-review) | fable-high draft | T43 |
| 3 | T48 cli-duel-sim-balance | cli, core tuning | opus-extra | T45 |
| 3 | T49 duel-ui (picker, VS, HUD, result) | shared | sonnet-high | T44, T45 |
| 3 | T50 ar-host-views-duel | androidApp (hand-review) | fable-high draft | T41, T45, T47, T49 |
| 4 | T51 ios-app-shell (Xcode, Swift RealityKit host) | iosApp | on the Mac | T46, T47 |

After every implementing brief: `rudeus-reviewer` on the diff, fixes in the same worktree, merge, status commit.

## Gates (measurable)
- **G1 foundation**: `:core:compileKotlinIosSimulatorArm64` green (Linux or the Mac); `scripts/verify.sh` green.
- **G2 stats**: 100 % of `docs/bundled` sheets yield stats with sum 20; legacy sheets parse unchanged.
- **G3 views**: live spike on ≥ 5 bundled characters, human verdict ≥ 4/5 usable BACK and FRONT; else cut to SIDE + BACK only, else cut views.
- **G4 duel core**: `cli duel --all`: no archetype wins > 65 % against the field at equal budget; every match ends
  (no timeouts without a winner); 30 Hz and 60 Hz stepping agree on the winner in ≥ 95 % of seeded matches.
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
