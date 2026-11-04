# T32 — ar-host-cast-touch-emotes

status: review
created: 2026-11-04
day: Day 9 (Nov 4, v1.0 minimal cut; see ai/plan-cast-emotes.md). Starts after T28, T29, T31 are merged. HAND-REVIEW draft: an agent writes it, the user approves every file and runs it on the Pixel.
gate: v1.0 gate — two characters, two fingers, each walks to its own finger at the same time, never through each other, never off the edge; every emote plays on the active character within a frame and ends in the idle pose; the Day 7 single-character flow and the Day 5 scenario are unchanged
cut-if-slips: cut line 5 (multi-touch grab -> tap-select only: a tap on a character makes it active, a tap on the table walks the active), then cut line 4 (separation off), then the cast (`CAST_ENABLED = false`: one member)

## Goal
The AR host renders every member of the cast as its own billboard, lets a finger grab and lead its own character
(a second finger leads a second one), plays the active character's choreography on its quad, plays the emote sounds,
and reads choreographies for bundled and saved characters. Physics stays in `:core`; the host measures dt, maps
pointers to members and writes node transforms.

## Owns (exact paths; all hand-review)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt, CharacterNode.kt, RudeusArView.kt, CastTouch.kt (new)
- androidApp/src/main/kotlin/kz/rudy/ar/audio/Sounds.kt
- androidApp/src/main/kotlin/kz/rudy/ar/di/AndroidCaptureHost.kt (bundled + saved emotes, and the `DeferredModels.choreograph` forwarding line of the addendum)
- androidApp/src/main/kotlin/kz/rudy/ar/MainActivity.kt (T31 review: wrap `onCastEvent` for the event sounds as `onCharacterEvent` is; hand `Sounds` to RudeusArView for the emote sounds)
- scripts/gen-sounds.py, androidApp/src/main/assets/sounds/hello.wav, roar.wav, dance.wav
Must NOT touch: `core/`, `shared/`, `cli/`, Gradle files, the manifest.

## Contract
- `GameLoop` holds a `Cast` (T28) instead of one body. `onFrame` steps it with `CastPhysics.step` (same dt clamp, pause / resume on tracking) and reports `CastEvents.between(lastReported, cast)` per member through `slot.onCastEvent(id, e)`; the legacy `onCharacterEvent` is never called by this host. Per member: `walkPhaseMs`, `landedAtMs`, the running emote (choreography + start on `simMs`). `walkTo(id, target)`, `jump(id)`, `walkForward(id)`, `emote(id, choreography)`, `setAbility(id, ability)`, `configure(profile, size)` for every member. `restartAtSpawn` and the scenario act on the FIRST member only.
- Members come from `slot.characters` (keyed by id, identity of `spritePng` for rebuilds). A new id: sprite built as today (bytes, else the demo asset), node under the AnchorNode (`key(id, sprite)`), body spawned at `CastPhysics.spawnNear(level, cast, radius, near = active member pos (ZERO when none), preferred = camera-right in plane coords, params)` once the anchor exists; before the anchor every member waits and is placed on the frame the anchor appears (first member at ZERO, the others via spawnNear). `slot.onCastEvent(id, PLACED)` per member when placed. A removed id: node detached, then `CharacterSprite.destroy` in T14's order. `CAST_ENABLED = true` is the cut switch (false: only the first character).
- Touch (CastTouch.kt): `ARSceneView(onTouchEvent = ...)` (ARSceneView.kt:758 in the shipped 4.31.0 sources; returning true keeps the event from the gesture detector). Binding `pointerId -> memberId`. ACTION_DOWN / ACTION_POINTER_DOWN: hit test at that pointer's x/y (`frame.hitTest(x, y)`, plane hit within `profile.tapHeightToleranceM` of the anchor plane as `tableTap` does) -> uv; the nearest ground member within `max(profile.radiusAt(size) * 1.5f, GRAB_MIN_M * profile.scale)` (`GRAB_MIN_M = 0.03f`) is grabbed: bind, `slot.onActiveChanged(id)`, log `grab pointer= id=`, return true. No member near: return false (the gesture detector's single tap keeps placing the anchor and walking the ACTIVE member, as Day 7). ACTION_MOVE: for each bound pointer, at most every `GRAB_MOVE_MS = 60` ms or when the uv moved ≥ 5 mm: hit test -> `level.nearestInside(uv)` -> `confirmSurface` -> `loop.walkTo(id, target)`; no depth-gap sampling per move (document it; the boundary clamp keeps the walk on the scanned surface). ACTION_POINTER_UP / ACTION_UP / ACTION_CANCEL: unbind (the last target stays), log `release pointer= id=`. Return true only for events that touch a bound pointer.
- Emotes: `slot.command` edge-triggered on `serial` (a `LaunchedEffect(slot.command?.serial)`): `Jump` -> `loop.jump(id)` (`jump_ignored` as today), `WalkForward` -> 10 cm × scale along that member's facing, `Emote(e)` -> `loop.emote(id, member.emotes[e])`, log `emote id= emote= source= ms=`. The running emote is sampled with `EmotePlayer.sample(c, simMs - startSimMs)` (simulated clock: PAUSED freezes it, as the scenario) and handed to `CharacterPoser.apply(..., emotePose)`: scale multiplies (`sx * pose.scaleX`, `sy * pose.scaleY`), `pose.yawDeg` adds to the billboard yaw, `pose.tiltDeg` is a rotation about the quad's local Z (`Rotation(z = ...)`, applied with the yaw as Euler degrees), `pose.hop * sprite.heightM * size` adds to the position's height; the walk bob is suppressed while an emote runs; the landing squash still wins. A new emote on the same member replaces the running one. Physics never sees an emote (locked: PAUSED freezes and never resets; one anchor per session; scan is a button).
- Sounds: extend `scripts/gen-sounds.py` with `hello` (two rising chirps), `roar` (low noisy growl, ≤ 0.5 s), `dance` (three-note jingle); `Sounds.play(sound: EmoteSound)` with the same gap guard; `soundsOn` respected.
- `AndroidCaptureHost`: `bundled()` reads `choreo.json` when the index entry has `choreo` (`Bundle.decodeEmotes`, defaults when missing; off the main thread like the sprites); `savedCharacters()` / `saveCharacter()` carry `emotes` through T29's `SavedCharacters`.
- Logs (RudeusGate): `cast_add id= n= u= v=`, `cast_remove id=`, `grab pointer= id=`, `release pointer= id=`, `emote id= emote= source= ms=`, `active id=`. Never a transcript or a photo path.
- Every decision carries a `// HAND-REVIEW: T32 — ...` line (load `hand-review`); confirm every SceneView API in the Gradle cache sources (`ARSceneView.kt` `onTouchEvent`, `Rotation`, `PlaneNode`) and record file:line in the Result.

## Tests required
None in JVM (AR rendering is not tested). `./gradlew :androidApp:assembleDebug` and `scripts/verify.sh` must pass.
Device (the user, Pixel 9 Pro XL, the build from the main checkout with the key): two characters added from the grid stand on the table; two fingers lead them at once; they stop touching and nobody falls off; each of the five emotes plays on the active one and ends in the idle pose; a single character still walks, jumps and runs the Day 5 scenario 3× as in Day 7.

## Steps
1. Load `hand-review` and `ar-sceneview`; read T28 `Cast.kt`, T29 `Emote.kt` / `EmotePlayer.kt`, T31 `ArSlot.kt`, then `GameLoop.kt`, `CharacterNode.kt`, `RudeusArView.kt` in full.
2. GameLoop first (cast + emotes, no UI), then CharacterNode (emote pose), then RudeusArView (members, touch, commands), then Sounds / gen-sounds, then AndroidCaptureHost.
3. `./gradlew :androidApp:assembleDebug --console=plain -q` after each file; `scripts/verify.sh` at the end.

## Done when
- `scripts/verify.sh` exit 0; the diff is readable in one screen per intent; `HAND-REVIEW` lines on every decision.
- Commit: `androidApp: cast rigs, finger grab, emote poser, emote sounds (T32; hand-review)`. Status stays `review` until the Pixel run.

## Result (draft 2026-11-04 evening, hand-review; the Pixel run is still owed)
- measured: `scripts/verify.sh` OK — core 305 tests, shared 20, androidApp 2, all green; debug APK 61 MB. Nothing measured on
  the Pixel yet. The five Day 7 WAVs regenerated byte-identical (gen-sounds.py is deterministic); hello 260 ms, roar 450 ms,
  dance 390 ms, all under the 0.5 s cap.
- APIs relied on (file:line, shipped sources in the Gradle cache):
  - `arsceneview-4.31.0-sources.jar` ARSceneView.kt:758 `onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)?`;
    :1401 `if (onTouchEvent?.invoke(event, hitResult) != true)` — true skips the node path AND the gesture detector;
    :1386 + :1515/1525 the `touchDispatcher` closure is attached once, at surface-view creation, so the lambda passed must
    be stable (it is `remember`ed and reads plain holders). ARSceneScope.kt:1546 composes `content` in the same composition
    (no subcomposition), so the applier detaches a removed PlaneNode before any `DisposableEffect.onDispose` runs.
  - `sceneview-4.31.0-sources.jar` Node.kt:370 `open var quaternion: Quaternion`; Node.kt:414 `rotation` setter =
    `Quaternion.fromEuler(value)` with kotlin-math's default order ZYX (kotlin-math-jvm 1.8.0 Quaternion.kt:88-91, 103-108,
    RotationsOrder Matrix.kt:37-46): `Rotation(y, z)` composes `Q_z * Q_y`, a tilt about the ANCHOR's Z. The poser therefore
    sets `node.quaternion = Q_y(yaw) * Q_z(tilt)` (`fromAxisAngle` Quaternion.kt:68, degrees), the product written out from
    Quaternion.kt:432-437 because `times` is `inline` built for JVM 21 and the app compiles for JVM 11 (compiler error
    INLINE_FROM_HIGHER_PLATFORM). With tilt 0 it equals the Day 7 `Rotation(y = yaw)` pose. PlaneNode.kt:65-67 / SceneScope.kt:906
    unchanged use (size, center, normal, materialInstance).
  - ARCore (Context7 `/websites/developers_google_ar`): `Frame.hitTest(float xPx, float yPx)` (same pixel space as the
    `hitTest(MotionEvent)` the taps use; nearest first), `Camera.getDisplayOrientedPose()` (+X = right in the display's
    orientation, so `preferred` = its X axis in anchor coords: `Vec2(x[0], x[2])`), `Pose.getXAxis()`.
- decisions (each carries a `HAND-REVIEW: T32` line):
  1. Touch stream: once a pointer of a stream is bound, EVERY later event of that stream returns true until ACTION_UP /
     CANCEL (not only the bound pointers' events). Reason: with "bound pointers only", DOWN(table) .. grab by finger 2 ..
     UP(table) reaches the detector as a clean single tap and walks the newly active member to finger 1 (a ghost tap).
     The detector may be left with a dangling DOWN (its long-press fires into no-op listeners; the next DOWN resets it).
  2. Move throttle: a hit test at most every GRAB_MOVE_MS (60 ms) per bound pointer, and a `walkTo` only when the table
     point moved ≥ GRAB_MOVE_MIN_M (5 mm) — none for a resting finger; POINTER_UP / UP lead once more from the lifting
     finger (review fix: a flick no longer stops up to 60 ms short). The target is `level.nearestInside(uv)`.
     `confirmSurface` is NOT called on a lead: `SurfaceBoundary.grown` squares every standing point by STAND_MARGIN_M
     (2 cm), so a finger held at the hull's edge would ratchet the hull outward 2 cm per lead, up to 16 times a second.
     Review fix (b): every lead is edge-checked like a tap (`depthGap` at the target + `depthGapsOnWay` with
     LEAD_DEPTH_SAMPLES = 2 from the member's feet, `SurfaceBoundary.crossesEdge`); a refused lead logs
     `lead_past_edge id= u= v= way=` and the member keeps its last target. Before a scan `boundary` is null, so this check
     is the only thing that keeps a drag on the real table then.
  2b. Review fix (a): the grab is a SCREEN-space test. Each ground member's feet and head (feet + sprite.heightM * size)
     are projected with ARCore `Camera.getViewMatrix` / `getProjectionMatrix` (near/far do not touch x, y) into the view's
     pixels; the quad's width at the feet depth is `P00 * viewW / 2 / depth` px per metre times `sprite.widthM * size`; the
     rectangle grows by GRAB_MARGIN_DP (16 dp) on every side; nearest centre wins. A finger on the head or body now grabs
     (before, its table point lay 4-10 cm behind the feet and the active member walked there). Fallback before the view is
     laid out (`viewSize` = 0): the brief's table-point radius.
  3. Tap-select lives in `tableTap` always: a tap on a member's quad makes it active and does not walk. With
     CAST_TOUCH_ENABLED the grab consumes that tap first, so this is exactly the cut-line-5 behaviour when flipped.
     Review fix (c): with exactly ONE member on the table `grabbable` is always null — no grab, no tap-select — so the Day 7
     small steps next to the character still walk it and the Day 5 scenario sees the same taps as before.
  3b. Detector edge case (reviewer): when a stream is consumed after the detector saw its DOWN, Android's GestureDetector
     keeps that DOWN as `mCurrentDownEvent`; the NEXT tap, if it comes within the double-tap timeout, is classified as a
     double tap and arrives through `onDoubleTapEvent`, not `onSingleTapUp`. Both listeners call `tableTap` (Pixel
     2026-11-03 fix), so the tap still walks; the double-tap flag only changes which callback carries it. Pixel check 4.
  3c. Review fix (d): a member's PlaneNode is created with `isVisible = false`; the poser's first placed frame shows it,
     so a fresh member is never drawn unposed at the anchor origin for a frame.
  3d. Review fix (bow): the emote tilt is multiplied by the facing sign (`flip`, and `-flip` for a sprite drawn facing
     right), so BYE bows forward whichever way the sprite looks.
  4. A slot with an empty `characters` (a Day 7 slot) renders one member `legacy` from `spritePng` / `ability`; App never
     sends that, it only keeps previews and a Day 7 caller alive.
  5. Scan swap: `SurfaceBoundary.afterScan` is folded over every member's state (it only ever grows the hull it is given).
  6. Emote finish: `GameLoop.emotePose` drops a finished choreography (simMs - start >= durationMs); the last key is REST
     by `ChoreographyRules.sanitize`, so the quad ends in the idle pose. A `MIN_GAP_MS` of 150 ms applies per emote sound.
  7. MainActivity (owned path added after the T31 review): `onCastEvent` wrapped for the event sounds exactly like
     `onCharacterEvent` (kept), `Sounds` handed to `RudeusArView(sounds = ...)` for the emote sounds.
  8. Logs on RudeusGate: `cast_add id= n= u= v=`, `cast_remove id=`, `grab pointer= id=`, `release pointer= id=`,
     `emote id= emote= source= ms=`, `active id=` (on every slot.activeId change: chip or finger), plus
     `command_ignored serial= id= anchored=`, `jump id= …` / `jump_ignored id= …`.
- cut/deferred: nothing cut. Switches: `CAST_ENABLED` (RudeusArView.kt), `CAST_TOUCH_ENABLED` (cut line 5),
  `SEPARATION_ENABLED` (GameLoop.kt, cut line 4). The bundled index.json still has no `choreo` entries, so bundled
  characters play the defaults (the read path is there; the saved/photo ones carry Spark's set).
- Pixel checks (build from the main checkout with the key; `adb logcat -s RudeusGate`):
  1. Day 7 flow: pick a card -> Scanning -> tap the table: `anchor_plane_age_ms`, then `cast_add id=<card> n=1 u=0.0 v=0.0`;
     tap-to-walk, Jump (`jump id=`), Scenario 3x (`scenario_done ms=`) as before; feet on the table, no slide.
  2. `+` chip -> add a second (and third) character: `cast_add id=… n=2 u= v=` with the new one standing to the right of
     the active one, not overlapping; `cast_remove id=` when a chip is removed (if the HUD offers it) and the quad is gone.
  3. Two fingers: finger A on character 1's HEAD (not its feet) -> `grab pointer=0 id=…` and the chip turns active
     (`active id=`); drag -> it follows (lag <= 60 ms); finger B on character 2 while A is down -> `grab pointer=1 id=…`,
     both follow at once; they stop touching (separation) and never leave the scanned boundary; lift -> `release
     pointer= id=`, each finishes its walk at the finger's last point (no 60 ms short stop on a flick). A finger landing
     next to a character (outside its quad + 16 dp) must NOT grab: no `grab` line, and the active member walks there.
  3a. Drag a character toward and past the table edge, before any scan and after one: `lead_past_edge id= u= v= way=`
     appears as the finger crosses the edge and the character stops at its last good target; it never walks into the air.
     On the table the line must not appear during an ordinary drag (if it does, LEAD_DEPTH_SAMPLES / EDGE_DROP_M need a look).
  4. After a mixed stream (finger A on the TABLE first, finger B grabs, both lift) the next single tap must still walk the
     active member (the detector's dangling DOWN is harmless).
  5. Each of the five emote buttons on the active character: `emote id= emote=HELLO source=default|spark ms=…`; HELLO
     wave + hop with the hello chirp, BYE bow (a lean in the quad's own plane, still facing the camera), DANCE hops + a
     turn + jingle, SPIN two turns, ROAR puff + shake + growl; every one ends in the idle pose within a frame; a second
     emote mid-way replaces the first; the walk bob stops while one runs; Sounds off in Settings mutes them.
  6. Cover the camera during DANCE: the pose freezes with the Paused scrim and resumes where it was (simulated clock).
  7. Tap a character (no drag): it becomes active (`active id=`) and does not walk.

## Addendum after T29 (2026-11-04)
- `MetaModels.choreograph` has a default body returning `EmoteSet.defaults()` (so `DeferredModels` in androidApp kept compiling). T32 MUST add the forwarding override in `DeferredModels` (hand-review zone, one line): `override suspend fun choreograph(ref: String, sheet: CharacterSheet, image: ByteArray?): EmoteSet = delegate.choreograph(ref, sheet, image)` — without it a photo character on the phone always ships `source=default` and the gate line `choreo source=spark` can never show. Add the file that holds `DeferredModels` to the owned paths.
