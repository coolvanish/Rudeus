# Day 9 — what only a human can do (2026-11-04)

`day9/cast-emotes` (from `main` at 93ce667): v1.1 "a cast on the table, and emotes the model choreographs". Verify in the
main checkout at fdf4aac: core 310, shared 22, androidApp 2 tests, 0 failed; APK 60 MB. Device rows are UNTESTED: the
Pixel was not on adb during the run and you said you will build the APK and check the device yourself.

## 1. What the agents did (read the diffs: `git log --oneline 93ce667..HEAD`)
| Brief | What | Status |
|---|---|---|
| T28 cast physics | `Cast`, `CastPhysics.step/separate/spawnNear`, `CastEvents`, `Level.nearestInside`; a one-member cast equals `Physics.step` frame by frame | done |
| T29 emotes | `Emote`, `Choreography`, `EmotePlayer`, five hand-authored defaults, `MetaModels.choreograph` (Spark structured output), `choreo.json` in saved characters and the bundle; review fixes (unforwarded `choreograph` fails honestly, sheet/choreo deferreds split) | done |
| T30 cli | `choreo` and `pose-spike` commands; live run: 16/16 bundled characters `source=spark`, fixtures in `docs/api-samples/spark-choreo/`, assets mirrored; Muse pose spike GO (your verdict) | done |
| T31 shared HUD | cast chips, `+` Add sheet, emote row, name-first bubble, `ArSlot` cast + command contract (legacy fields kept); review fixes (photo dedupe, `+` on its own line, sheet closes on tracking loss, Fell hold) | review (Pixel checks in §3) |
| T32 AR host | one rig per member, finger grab (`onTouchEvent`), emote poser (quaternion yaw·tilt, hop), emote sounds, `DeferredModels.choreograph` forward, `MainActivity` sound wrapper for `onCastEvent` | needs your Pixel run |

Measured on the Mac (runs `2026-11-04T12-10-54Z`, `12-16-28Z`, `12-18-24Z`, `12-18-48Z`): Spark choreography p50 22.2 s
(15.2–25.7 s), $0.013 per call (`CostTable.choreoUsd` set to that); Muse pose frame 11.6–13.7 s, $0.01 each, matte without
SAM; the day's model spend ≈ $0.27.

## 2. Install and log
From the MAIN checkout on `day9/cast-emotes` (worktree builds have no key):
```
./gradlew :androidApp:installDebug && scripts/device.sh run
```
Second terminal first: `scripts/device.sh log` (or `adb logcat -s RudeusGate`). Unlock the phone. A cup and a book on a
textured cloth, as on Day 5.

## 3. Gates
### v1.1 gate (ai/plan-cast-emotes.md)
| Criterion | Status | Evidence |
|---|---|---|
| Two characters added from the grid stand on the table, each walks to its own finger at the same time | UNTESTED | `docs/evidence/day9-cast-two-fingers.png` + logcat `grab pointer=0 id=`, `grab pointer=1 id=` |
| They do not pass through each other and neither is pushed off the edge | UNTESTED (JVM PASS: `CastTest`) | same screenshot; no `FELL` bubble while they meet |
| Every emote plays on the active character within a frame and ends in the idle pose, says a line | UNTESTED | `docs/evidence/day9-emote-{hello,bye,dance,spin,roar}.png`, logcat `emote id= emote= source= ms=` |
| A new photo character arrives with Spark choreographies (`choreo source=spark`); offline `source=default` | UNTESTED (JVM PASS: pipeline tests; CLI PASS 16/16) | logcat `emote ... source=spark` on a fresh photo; `source=default` with Wi-Fi off |
| The v1.0 single-character flow and the Day 5 scenario are unchanged | UNTESTED (JVM PASS: `castOfOneStepsExactlyLikeASingleBody`) | `scenario_done ms≈15000` 3× |
| `scripts/verify.sh` green | PASS | fdf4aac: core 310 / shared 22 / android 2 |

Pixel steps, in order (keys on `RudeusGate`):
1. v1.0 flow: pick a bundled card → Scanning → tap: `anchor_plane_age_ms` then `cast_add id=<card> n=1 u=0.0 v=0.0`; tap-to-walk; Jump logs `jump id=`; footprints on → Scenario 3×: `scenario_done ms=`; feet on the table, no slide.
2. `+` chip: a second and a third character: `cast_add id= n=2 u= v=` standing to the right of the active one, not overlapping. (Chips also work before the anchor: they are placed on the frame the anchor appears.)
3. Two fingers: finger on character A → `grab pointer=0 id=` and `active id=`, the chip rings; drag, A follows within ~60 ms; second finger on B while the first is down → `grab pointer=1 id=`; both follow at once, stop touching, never leave the boundary; lift → `release pointer= id=`, each finishes its walk. Screenshot `day9-cast-two-fingers.png`.
4. Mixed stream: finger A on the TABLE first, B grabs a character, both lift: the NEXT single tap must still walk the active member (the touch path returns true for the whole stream once a pointer is bound).
5. Five emotes on the active one: `emote id= emote=HELLO source=spark|default ms=`. HELLO: wave + hop + chirp. BYE: a bow leaning in the quad's plane while still facing the camera (if it looks like the quad turns about the table's axis, the quaternion order in `CharacterNode.kt` needs a look). DANCE: hops + a turn + jingle. SPIN: two turns. ROAR: puff + shake + growl. All end in idle within a frame; a second emote replaces the first; the walk bob stops during one; Settings → Sounds off mutes. Screenshots `day9-emote-<name>.png`.
6. Cover the camera mid-DANCE: the pose freezes under the Paused scrim and resumes where it was (simulated clock; nothing resets).
7. Tap (no drag) on a character: `active id=`, no walk. Tapping the single character's own spot no longer walks it a few cm (T32 deviation 3).
8. T31 looks: with a two-line bubble and footprints on, does the bottom stack reach the Plus/Minus buttons or the Rec button? With the system font at 1.3×, are "Сау бол" / "Крутись" cut under the emote buttons? With four members, is the Offline badge intact?
9. Fresh photo with Wi-Fi on: Processing shows the usual stages; on Play `emote ... source=spark`. Then Wi-Fi off, another photo: `source=default`, no crash.

### Added after the T32 review (merged as 83cf561; verify at HEAD: core 310 / shared 22 / android 2, APK 60 MB)
The reviewer found nothing to fix first; the two "look at on the Pixel" majors were fixed before your build:
10. Grab by the head: with TWO characters, put a finger on character 1's HEAD, not its feet → `grab pointer=0 id=` and `active id=`; drag, it follows within ~60 ms. A finger landing beside a character (outside its quad + 16 dp) must NOT grab: no `grab` line, the active member walks there as a tap. Lift → `release pointer=0 id=` and the character finishes at the finger's last point (a flick does not stop short). With ONE character there are no `grab` lines at all; small taps right next to it still walk it (v1.0) and the Scenario runs 3× unchanged.
11. Edge while dragging: drag a character toward and past the real table edge, once BEFORE any scan and once after → `lead_past_edge id= u= v= way=` as the finger crosses the edge; the character stops at its last good target and never walks into the air. During an ordinary drag on the table the line must not appear (if it does, `LEAD_DEPTH_SAMPLES` / the tap's edge drop threshold need a look, same as `tap_past_edge`).
12. BYE on a right-facing (flipped) sprite bows forward; a freshly added member never flashes at the anchor origin on its first frame.
Notes from the review: an emote sound plays at once even under the Paused scrim while the pose waits for tracking (expected); if a drag starts within 300 ms of a previous tap and is then grabbed, the gesture detector's double-tap flag stays set and the next table drag may walk the active member to where it lifts (rare, documented in the T32 brief).

## 4. Decisions only you can make
1. Finger grab misbehaves → `CAST_TOUCH_ENABLED = false` in `RudeusArView.kt` (cut line 5: tap-select + tap-to-walk only).
2. Characters jitter or get pushed off a book by a friend → `SEPARATION_ENABLED = false` in `GameLoop.kt` (cut line 4: they overlap).
3. The cast itself fails → `CAST_ENABLED = false` in `RudeusArView.kt` (one member; the HUD still shows chips).
4. Spark choreographies look worse than the defaults → in `AndroidCaptureHost.kt` drop the `DeferredModels.choreograph` override (cut line 6: `source=default` everywhere, the row stays).
5. Merge to `main` and tag `v1.1`, rebuild `release/` (APK from the main checkout, the four runs JSON), README v1.1 section (drafted, see `git log`): after your PASS, not before.

## 5. Housekeeping only you can do
- Worktrees to remove: `.claude/worktrees/T28`, `T29`, `T30`, `T31`, `T32` (`git worktree remove <path>`), branches `task/T28-cast-physics`, `task/T29-emotes-choreography`, `task/T30-cli-choreo`, `task/T31-shared-cast-emote-hud`, `task/T32-ar-host-cast` (`git branch -d`).
- `git merge --no-ff day9/cast-emotes` into `main` after the Pixel PASS; `git tag v1.1`.
- Fork leftovers (the archived session's briefs and seed) sit in the session scratchpad, not in the repo; nothing to do.

## 6. Known limits (by design today)
- Separation is one pass per frame: three or more jammed members keep a µm overlap for a frame; two walkers exactly head-on with swapped targets press as WALKING forever (1 cm off the line they slide past); a friend can nudge a member off a book (FELL / LANDED bubble); the cup guard checks the centre, so a member pressed against a cup jitters ~0.4 mm (T28 review).
- A drag does not sample depth gaps per move (only the boundary clamp); a tap still does (T32).
- Emotes are squash-stretch choreographies on the one quad; the Muse pose frames (GO) are a post-deadline brief (card proposed).
- Spark choreography costs $0.013 and ~22 s per character, hidden behind the stylize loop in the pipeline; the recorded fixtures carry the sanitised set, not the raw reply, and their `note` text still says "cli character --live" (cosmetic, `RecordingModels`).
- Voice emote words and the long-press wheel are cut (user decision 16:30).
- `ArSlot` keeps the v1.0 fields (`spritePng`, `ability`, `jumpRequest`, `walkForwardRequest`, `onCharacterEvent`) mirrored for compatibility; removing them is post-deadline cleanup.

## 7. Device results (<date>, device, who)
(appended after your run)
