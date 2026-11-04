# T32 — ar-host-cast-touch-emotes

status: todo
created: 2026-11-04
day: Day 9 (Nov 4, v1.1 minimal cut; see ai/plan-cast-emotes.md). Starts after T28, T29, T31 are merged. HAND-REVIEW draft: an agent writes it, the user approves every file and runs it on the Pixel.
gate: v1.1 gate — two characters, two fingers, each walks to its own finger at the same time, never through each other, never off the edge; every emote plays on the active character within a frame and ends in the idle pose; the v1.0 single-character flow and the Day 5 scenario are unchanged
cut-if-slips: cut line 5 (multi-touch grab -> tap-select only: a tap on a character makes it active, a tap on the table walks the active), then cut line 4 (separation off), then the cast (`CAST_ENABLED = false`: one member)

## Goal
The AR host renders every member of the cast as its own billboard, lets a finger grab and lead its own character
(a second finger leads a second one), plays the active character's choreography on its quad, plays the emote sounds,
and reads choreographies for bundled and saved characters. Physics stays in `:core`; the host measures dt, maps
pointers to members and writes node transforms.

## Owns (exact paths; all hand-review)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt, CharacterNode.kt, RudeusArView.kt, CastTouch.kt (new)
- androidApp/src/main/kotlin/kz/rudy/ar/audio/Sounds.kt
- androidApp/src/main/kotlin/kz/rudy/ar/di/AndroidCaptureHost.kt (bundled + saved emotes only)
- scripts/gen-sounds.py, androidApp/src/main/assets/sounds/hello.wav, roar.wav, dance.wav
Must NOT touch: `core/`, `shared/`, `cli/`, Gradle files, the manifest.

## Contract
- `GameLoop` holds a `Cast` (T28) instead of one body. `onFrame` steps it with `CastPhysics.step` (same dt clamp, pause / resume on tracking) and reports `CastEvents.between(lastReported, cast)` per member through `slot.onCastEvent(id, e)`; the legacy `onCharacterEvent` is never called by this host. Per member: `walkPhaseMs`, `landedAtMs`, the running emote (choreography + start on `simMs`). `walkTo(id, target)`, `jump(id)`, `walkForward(id)`, `emote(id, choreography)`, `setAbility(id, ability)`, `configure(profile, size)` for every member. `restartAtSpawn` and the scenario act on the FIRST member only.
- Members come from `slot.characters` (keyed by id, identity of `spritePng` for rebuilds). A new id: sprite built as today (bytes, else the demo asset), node under the AnchorNode (`key(id, sprite)`), body spawned at `CastPhysics.spawnNear(level, cast, radius, near = active member pos (ZERO when none), preferred = camera-right in plane coords, params)` once the anchor exists; before the anchor every member waits and is placed on the frame the anchor appears (first member at ZERO, the others via spawnNear). `slot.onCastEvent(id, PLACED)` per member when placed. A removed id: node detached, then `CharacterSprite.destroy` in T14's order. `CAST_ENABLED = true` is the cut switch (false: only the first character).
- Touch (CastTouch.kt): `ARSceneView(onTouchEvent = ...)` (ARSceneView.kt:758 in the shipped 4.31.0 sources; returning true keeps the event from the gesture detector). Binding `pointerId -> memberId`. ACTION_DOWN / ACTION_POINTER_DOWN: hit test at that pointer's x/y (`frame.hitTest(x, y)`, plane hit within `profile.tapHeightToleranceM` of the anchor plane as `tableTap` does) -> uv; the nearest ground member within `max(profile.radiusAt(size) * 1.5f, GRAB_MIN_M * profile.scale)` (`GRAB_MIN_M = 0.03f`) is grabbed: bind, `slot.onActiveChanged(id)`, log `grab pointer= id=`, return true. No member near: return false (the gesture detector's single tap keeps placing the anchor and walking the ACTIVE member, as v1.0). ACTION_MOVE: for each bound pointer, at most every `GRAB_MOVE_MS = 60` ms or when the uv moved ≥ 5 mm: hit test -> `level.nearestInside(uv)` -> `confirmSurface` -> `loop.walkTo(id, target)`; no depth-gap sampling per move (document it; the boundary clamp keeps the walk on the scanned surface). ACTION_POINTER_UP / ACTION_UP / ACTION_CANCEL: unbind (the last target stays), log `release pointer= id=`. Return true only for events that touch a bound pointer.
- Emotes: `slot.command` edge-triggered on `serial` (a `LaunchedEffect(slot.command?.serial)`): `Jump` -> `loop.jump(id)` (`jump_ignored` as today), `WalkForward` -> 10 cm × scale along that member's facing, `Emote(e)` -> `loop.emote(id, member.emotes[e])`, log `emote id= emote= source= ms=`. The running emote is sampled with `EmotePlayer.sample(c, simMs - startSimMs)` (simulated clock: PAUSED freezes it, as the scenario) and handed to `CharacterPoser.apply(..., emotePose)`: scale multiplies (`sx * pose.scaleX`, `sy * pose.scaleY`), `pose.yawDeg` adds to the billboard yaw, `pose.tiltDeg` is a rotation about the quad's local Z (`Rotation(z = ...)`, applied with the yaw as Euler degrees), `pose.hop * sprite.heightM * size` adds to the position's height; the walk bob is suppressed while an emote runs; the landing squash still wins. A new emote on the same member replaces the running one. Physics never sees an emote (locked: PAUSED freezes and never resets; one anchor per session; scan is a button).
- Sounds: extend `scripts/gen-sounds.py` with `hello` (two rising chirps), `roar` (low noisy growl, ≤ 0.5 s), `dance` (three-note jingle); `Sounds.play(sound: EmoteSound)` with the same gap guard; `soundsOn` respected.
- `AndroidCaptureHost`: `bundled()` reads `choreo.json` when the index entry has `choreo` (`Bundle.decodeEmotes`, defaults when missing; off the main thread like the sprites); `savedCharacters()` / `saveCharacter()` carry `emotes` through T29's `SavedCharacters`.
- Logs (RudeusGate): `cast_add id= n= u= v=`, `cast_remove id=`, `grab pointer= id=`, `release pointer= id=`, `emote id= emote= source= ms=`, `active id=`. Never a transcript or a photo path.
- Every decision carries a `// HAND-REVIEW: T32 — ...` line (load `hand-review`); confirm every SceneView API in the Gradle cache sources (`ARSceneView.kt` `onTouchEvent`, `Rotation`, `PlaneNode`) and record file:line in the Result.

## Tests required
None in JVM (AR rendering is not tested). `./gradlew :androidApp:assembleDebug` and `scripts/verify.sh` must pass.
Device (the user, Pixel 9 Pro XL, the build from the main checkout with the key): two characters added from the grid stand on the table; two fingers lead them at once; they stop touching and nobody falls off; each of the five emotes plays on the active one and ends in the idle pose; a single character still walks, jumps and runs the Day 5 scenario 3× as in v1.0.

## Steps
1. Load `hand-review` and `ar-sceneview`; read T28 `Cast.kt`, T29 `Emote.kt` / `EmotePlayer.kt`, T31 `ArSlot.kt`, then `GameLoop.kt`, `CharacterNode.kt`, `RudeusArView.kt` in full.
2. GameLoop first (cast + emotes, no UI), then CharacterNode (emote pose), then RudeusArView (members, touch, commands), then Sounds / gen-sounds, then AndroidCaptureHost.
3. `./gradlew :androidApp:assembleDebug --console=plain -q` after each file; `scripts/verify.sh` at the end.

## Done when
- `scripts/verify.sh` exit 0; the diff is readable in one screen per intent; `HAND-REVIEW` lines on every decision.
- Commit: `androidApp: cast rigs, finger grab, emote poser, emote sounds (T32; hand-review)`. Status stays `review` until the Pixel run.

## Result (filled on acceptance)
- measured:
- APIs relied on (file:line):
- cut/deferred:

## Addendum after T29 (2026-11-04)
- `MetaModels.choreograph` has a default body returning `EmoteSet.defaults()` (so `DeferredModels` in androidApp kept compiling). T32 MUST add the forwarding override in `DeferredModels` (hand-review zone, one line): `override suspend fun choreograph(ref: String, sheet: CharacterSheet, image: ByteArray?): EmoteSet = delegate.choreograph(ref, sheet, image)` — without it a photo character on the phone always ships `source=default` and the gate line `choreo source=spark` can never show. Add the file that holds `DeferredModels` to the owned paths.
