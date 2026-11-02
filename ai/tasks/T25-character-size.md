# T25 — character-size

status: review
created: 2026-11-03
day: Day 8 (added after the Pixel runs: "sometimes objects are too small")
gate: Day 6 gate must stay reachable; size 1× behaves exactly as before
cut-if-slips: revert the brief; nothing else depends on it

## Goal
On Play the child can make the character bigger or smaller with two round buttons. A bigger character looks bigger,
bumps into the cup sooner and walks faster in proportion. Jump height and the level's rules stay. The default is unchanged.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/Surface.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/physics/Physics.kt, PhysicsParams.kt
- core/src/commonTest/kotlin/kz/rudy/ar/core/physics/PhysicsSurfaceTest.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt, ArSlot.kt, ui/Strings.kt, ui/components/Glyph.kt, ui/screens/PlayScreen.kt
- androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt, CharacterNode.kt, RudeusArView.kt (hand-review)
- DESIGN.md (Play: Size)

## Contract
- `CharacterSize.STEPS` = 0.5, 0.75, 1, 1.5, 2, 3 (`DEFAULT` = 1); `bigger` / `smaller` stop at the ends. 1× = 6 cm on
  the table, so 3–18 cm there; the floor profile multiplies as usual.
- `SurfaceProfile.radiusAt(size)`; `PhysicsParams.forProfile(profile, size)` scales walk speed and arrive epsilon by
  `profile.scale * size`. Gravity, jump velocity, respawn height, `stepUpMaxM` and `platformMaxHeightM` stay the
  profile's, so at any size a jump clears a 2 cm object but not a 9 cm cup. At `DEFAULT` it equals the previous result.
- `Physics.step`: a character standing still (no target, on the ground) is not pushed out of an object when that would
  put it over a cliff. Otherwise a big character spawning beside a cup near the edge falls and respawns forever.
- `ArSlot.characterSize` is state, not a request counter. Kept across Back, like the surface. Not saved to disk.
- The host applies it through `GameLoop.configure` and a node scale (no sprite rebuild). Locked decisions stay: one
  anchor, the surface locked at anchor time, PAUSED freezes and never resets.

## Tests required
- `characterSizeStepsStopAtBothEnds`, `defaultSizeLeavesTheSurfacePhysicsUntouched`, `sizeScalesHorizontalMotionButNoHeight`
- `jumpHeightIsTheSameAtEverySize`, `biggerCharacterStopsFartherFromTheBox`, `everySizeJumpsOverALowObstacle`, `noSizeJumpsOverACup`
- `growingBesideABoxPushesOutWithoutAnyEvent`, `standingStillIsNeverPushedOffTheTable`
Tests not required: Compose screens, AR rendering.

## Done when
- `scripts/verify.sh` exit 0
- Pixel: Plus and Minus change the character on the table with its feet on the surface; logcat shows
  `character_size size= radius=`; at 3× it stops farther from the cup; the buttons go disabled at 3× and 0.5×.

## Result (filled on acceptance)
- measured: JVM green. Pixel 2026-11-03, build without the API key: both buttons show at the left edge, clear of the
  debug Rec button; `character_size` logged 1.0 -> 1.5 -> 2.0 -> 3.0 -> 2.0 with the radius following; no crash. Still
  open on the Pixel: stopping farther from a real cup needs a scan, so a build with the key.
- review: rudeus-reviewer found no blockers. Its two majors (a jump apex scaled with size let a big character snap over a
  cup; an endless fall and respawn at 3× beside a cup near the edge) are fixed by the contract above. A second pass on those
  fixes confirmed the scaling, the standing-still line and the tests that guard them.
- known limits: the Day 5 scenario beats are timed for 1×, so replay it at 1×. Voice "go" (hidden) still steps 10 cm. At
  3× a tap within 9 cm of a cup cannot be reached: it says BLOCKED and keeps walking in place until the next tap (the same
  rule as 1×, with a 3 cm zone). Growing in a gap narrower than the circle can leave the sprite overlapping a cup. From an
  overlapping spot next to the edge, every walk or jump falls and respawns until Smaller is pressed (the standing-still line
  covers only a character with no target on the ground). Objects between the 4 cm platform limit and about 6.5 cm can be
  hopped through a snap-out, and the width grows with size. The scan-angle brief's push-out rule removes the falls, but while
  overlapping, the character can then walk through the cup. With that rule the standing-still line can never run, so that
  brief's `Physics.kt` drops it.
- cut/deferred: pinch to resize (holding the phone leaves one hand, so buttons came first); resizing the table objects.
