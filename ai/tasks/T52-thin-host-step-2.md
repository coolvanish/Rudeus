# T52 — thin-host-step-2

status: todo
created: 2026-11-11
phase: v2 wave 3 (ai/build-plan-v2.md, "iOS readiness": thin host). Step S2 of the map in ai/tasks/T43-thin-host-map.md
gate: G1 foundation (`scripts/verify.sh` green, `:core` still compiles for iOS); a precondition of T53 and T50
cut-if-slips: never cut. Two revert points, see "Done when": commit C alone, then commit A
depends: T47 merged (`core.session` exists). Disjoint from T45 / T55 (`core/duel`), T49 (`shared`). T53 builds on this
tier: rudeus-opus-extra, as planned (float-exact extraction, quaternion tests)

`file:line` below is the line at 5b2f618. Paths without a module are under `androidApp/src/main/kotlin/kz/rudy/ar/`.

## Goal
The maths that turns a character's physics state and the camera position into a billboard pose (where the quad
stands, how it is turned, squashed, stretched and mirrored) lives in `:core` as `BillboardPoser`, returns a plain
`NodePose`, and is tested on the JVM. The Android host keeps only the three lines that write a pose into its
SceneView node. Today all of it is one method that writes into a `PlaneNode` (`ar/CharacterNode.kt:178-251`), so none
of it has ever run in a test. After this brief an iOS host gets the motion for free, the duel's fighters are posed by
the same code, and the two things already fixed once on the Pixel (the quaternion order and the bow's sign,
`ar/CharacterNode.kt:82-84, 221-224`) can no longer regress without a red test. The player sees no difference.

## Decisions taken here (not open)
1. **The flip stays the host's own computation, verbatim** (`ar/CharacterNode.kt:211-220`); `ViewPicker` is not called
   by `BillboardPoser` in this step. Reasons: (a) the proof that this is a move is a diff of the method body, and it
   only works if the body is the same tokens; (b) `ViewPicker` is pinned equal only for a SIDE base sprite with the
   camera at least 10 cm away in the plane (`core/src/commonTest/.../emote/ViewPickerTest.kt:155-213`). It differs
   when the camera is within 1 cm of straight above (it holds the previous choice, `core/.../emote/SpriteViews.kt:185`;
   the host flips by `facing.u`) and for a FRONT or BACK base (never mirrored in its own sector, `SpriteViews.kt:199`),
   and the bow's sign is defined from the flip (`ar/CharacterNode.kt:224`), which has no meaning for an unmirrored
   FRONT picture. Those are product decisions about views, not a move. **The switch lands in T50**, when
   `ArCharacter` carries `SpriteViews` and the host can show another picture; it is then a `:core`-only change. To make
   it safe, this brief pins the two against each other from the poser's side (`flipMatchesViewPickerForASideBaseSprite`,
   `flipDeadbandIsTheViewPickersMirrorDeadband`) and pins the one known difference (`cameraStraightAboveFlipsByFacingU`).
2. **`NodePose` is `id`, `pos`, `h`, `rotation`, `scaleX`, `scaleY`, `mirrored`**: exactly what this step fills and
   tests. Later fields are appended with a default that means "as today", so no caller or test written here changes:
   `visible: Boolean = true` (T53, the reappear timer of `ar/RudeusArView.kt:814, 825`) and `picture: String = BASE`
   (T50). Two changes against the sketch in T43: `pos: Vec2` + `h` instead of `u, v, h` (it mirrors
   `CharacterState.pos / h`, and the host keeps writing it through `localPosition(pos, h)`, the one place that owns
   the node frame, `ar/PlaneCoords.kt:56`), and `rotation: Quat` instead of `yawDeg` / `tiltDeg` (decision 4).
3. **Constants.** `FEET_DEPTH_BIAS` stays in the host (`ar/CharacterNode.kt:67-73`): it is dated "Pixel 2026-11-03",
   it corrects ARCore's depth-from-motion on that phone, "0f turns it off" is a switch the user flips there, and an
   iOS host will need its own number. `:core` takes it as a constructor parameter with no default, so the number has
   one home. The motion numbers move with the maths: `WALK_BOB_*`, `JUMP_*` (`ar/CharacterNode.kt:52-65`; they are the
   game's motion spec, `DESIGN.md:135`, and must be the same on both platforms) and `FLIP_DEADBAND` (76; it already
   has a twin in `:core`, `ViewPicker.MIRROR_DEADBAND`, `SpriteViews.kt:160-161`). The rule: a constant with a
   "Pixel <date>" note stays where the user greps for it; a design number moves.
4. **The rotation crosses the seam as a quaternion, not as two angles.** With angles, the composition
   `Q_y(yaw) * Q_z(tilt)` would live in every host, untested (certainly in Swift), and the Euler-order bug the Pixel
   already found once could come back on iOS unseen. With `rotation: Quat` there is one composition, in `:core`, and
   a JVM test states what it must do in geometry, not in formulas: the quad's front still points at the camera while
   it bows (the wrong order tips it 30 degrees up), the top leans the way the sprite looks (the wrong sign leans the
   other way), and one golden value distinguishes `Q_y * Q_z` from `Q_z * Q_y` by the sign of `x`. What is left in a
   host is copying four floats, and the Android copy gets its own one-line test.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/BillboardPoser.kt (new: `NodePose`, `BillboardPoser`, the motion constants, `billboardRotation`)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/BillboardPoserTest.kt (new)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/BillboardPoser.kt (HAND-REVIEW: new in commit A, deleted in commit C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/CharacterNode.kt (HAND-REVIEW: commits A and C, only the edits under "Host diff")
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt (HAND-REVIEW: commit A, line 244 and one header line)
- androidApp/src/test/kotlin/kz/rudy/ar/ar/BillboardPoserPinTest.kt (new in commit A, deleted in commit C)
- androidApp/src/test/kotlin/kz/rudy/ar/ar/NodeRotationTest.kt (new in commit A, stays)
- ai/tasks/T52-thin-host-step-2.md (the Result section)

Does not move and must not touch: the sprite and texture code (`ar/CharacterNode.kt:96-172`), `FEET_DEPTH_BIAS`, the
frame loop and the visibility timer in `RudeusArView.kt` (808-827), `ar/PlaneCoords.kt`, `core/session/GameLoop.kt`,
`core/emote/**` (read `ViewPicker` from a test only), `core/duel/**` (T45 is working there), `core/physics/**`,
`core/domain/**`, `core/geometry/**`, `shared/**`, every Gradle file. No `TableEngine`, `FrameInput`, `visible` or
`picture`: each arrives with the step that first fills it.

## Contract
```kotlin
package kz.rudy.ar.core.session

/** One billboard for one frame, in the anchor frame (x = u, y = h, z = v). The host writes it into its node, nothing else. */
@Serializable
data class NodePose(
    val id: String,        // the member this pose belongs to
    val pos: Vec2,         // the quad's origin (the feet), plane coordinates, metres; the feet depth bias is in it
    val h: Float,          // its height above the plane, metres; the emote's hop and the bias are in it
    val rotation: Quat,    // yaw about +h so the front faces the camera, then tilt about the quad's own front axis
    val scaleX: Float,     // magnitudes on the quad the host built (height = the profile's, width = height x aspect);
    val scaleY: Float,     //   never negative: the flip is [mirrored]
    val mirrored: Boolean, // show the picture flipped horizontally (Android: a negative X scale, as today)
)

class BillboardPoser(
    val id: String,
    private val heightM: Float,        // the quad's height at size 1 (`SurfaceProfile.characterHeightM`)
    private val facesRight: Boolean,   // the picture is drawn looking to its own right
    private val feetDepthBias: Float,  // share of the height the quad is pulled toward the camera; 0f = off. No default.
) {
    /** Same parameters, order and defaults as `CharacterPoser.apply` minus `node` (ar/CharacterNode.kt:190-200). */
    fun pose(
        state: CharacterState,
        cameraLocal: Vec2,             // camera position in plane coordinates
        cameraHeightM: Float,          // its height above the plane
        walkPhaseMs: Long,             // GameLoop.walkPhaseMs(id)
        verticalVelocity: Float = 0f,  // PhysicsBody.verticalVelocity, m/s
        sinceLandedMs: Long = Long.MAX_VALUE,  // host clock minus GameLoop.landedAtMs(id)
        size: Float = 1f,              // ArSlot.characterSize
        emotePose: EmotePose? = null,  // GameLoop.emotePose(id)
    ): NodePose
}

/** Q_y(yawDeg) * Q_z(tiltDeg), degrees, right-handed; with tilt 0 exactly a yaw. */
internal fun billboardRotation(yawDeg: Float, tiltDeg: Float): Quat

// internal const val, names and values as in the host today:
// WALK_BOB_PERIOD_MS = 400L, WALK_BOB_STRETCH = 0.08f, WALK_BOB_SQUASH = 0.05f,
// JUMP_RISE_SX = 0.85f, JUMP_RISE_SY = 1.15f, JUMP_LAND_SX = 1.25f, JUMP_LAND_SY = 0.75f, JUMP_LAND_MS = 120L,
// FLIP_DEADBAND = 0.15f
```
The poser keeps one thing between frames, the flip sign, starting unflipped (`ar/CharacterNode.kt:179`). It takes no
events: every input is state or a clock the caller owns.

**What must stay exactly as it is** (the rules the tests pin), all from `ar/CharacterNode.kt`:
- Position (201-207): `pull = min(heightM * feetDepthBias / camDist, 0.5)`, 0 when the camera is within 1 mm;
  `pos = state.pos + toCam * pull`; `h = state.h + hop * heightM * size + (cameraHeightM - state.h) * pull`.
- Yaw (211-212): `atan2(toCam.u, toCam.v)`, plus the emote's `yawDeg`. The flip never sees the emote's yaw.
- Flip (215-220): `right = (cos yaw, -sin yaw)`, `along = facing . right`; only when `|along| > FLIP_DEADBAND` does
  the flip change, to "unflipped when the walking direction is the drawn direction".
- Tilt (224-225): the emote's `tiltDeg` times `lean`, `lean = -flip` for a right-drawn picture, `flip` otherwise.
- Scale (229-250): landing squash for `sinceLandedMs in 0 until 120` on the ground (IDLE or WALKING), ease-out cubic
  from (1.25, 0.75); else rising stretch (0.85, 1.15) while JUMPING with `verticalVelocity > 0`; else the walk bob
  while WALKING with no emote; then the emote's scales multiply; then `* (1 - pull) * size` on both axes.

**How T47's finding binds this step.** An action issued on the first frame back from a tracking loss loses its
event (T47 Result, finding 1). The rising stretch reads state (`mode`, `verticalVelocity`), so a jump whose JUMPED was
lost still stretches; that is pinned here. The landing squash reads `sinceLandedMs`, which the loop stamps from the
LANDED event (`core/.../session/GameLoop.kt:240`), so a landing that coincides with a resume frame shows no squash.
That is v1.0 behaviour and is not changed here; if it is fixed, it is fixed in the loop, and `sinceLandedMs` stays
an input so the duel (which reads state, not events) can feed its own clock.

### Host diff, commit A (the split; HAND-REVIEW)
Nothing can pin `CharacterPoser.apply` before it is split: it writes into a SceneView `PlaneNode`, which a JVM unit
test cannot build. So commit A makes the split inside the host, exact by construction, and the tests then pin it.
1. New `ar/BillboardPoser.kt`, package `kz.rudy.ar.ar`, everything `internal`: the constants of
   `ar/CharacterNode.kt:52-54, 61-65, 76` with their notes (56-60, 75), `Y_AXIS`, `Z_AXIS`, `hamilton` with their note
   (78-94), a `NodePose` with the fields above (no `@Serializable`: the app module has no serialization plugin), and
   `class BillboardPoser(id, heightM, facesRight, feetDepthBias)` whose `pose(...)` is lines 201-250 with exactly
   these changes:
   - `sprite.heightM` -> `heightM` (205, 206), `sprite.facesRight` -> `facesRight` (219, 224), `FEET_DEPTH_BIAS` -> `feetDepthBias` (205);
   - line 207 becomes `val pos = state.pos + toCam * pull` and `val h = state.h + hopM + upToCam * pull`;
   - line 225 becomes `val rotation = billboardRotation(yawDeg, (emotePose?.tiltDeg ?: 0f) * lean)`;
   - line 250 becomes `return NodePose(id, pos, h, rotation, scaleX = sx * (1f - pull) * size, scaleY = sy * (1f - pull) * size, mirrored = flip < 0f)`.
   And `internal fun billboardRotation(yawDeg: Float, tiltDeg: Float): Quat`: the old expression
   `hamilton(Quaternion.fromAxisAngle(Y_AXIS, yawDeg), Quaternion.fromAxisAngle(Z_AXIS, tiltDeg))`, its four
   components copied into a `:core` `Quat(x, y, z, w)`. The KDoc of `apply`'s parameters (181-189) moves to `pose`.
   The file opens with one `// HAND-REVIEW: T52 — ...` note saying it is the pure half of the poser on its way to `:core`.
2. `ar/CharacterNode.kt`: the moved lines go; `FEET_DEPTH_BIAS` and its note stay; `CharacterPoser` becomes
   ```kotlin
   internal class CharacterPoser(id: String, sprite: CharacterSprite) {
       private val poser = BillboardPoser(id, sprite.heightM, sprite.facesRight, FEET_DEPTH_BIAS)
       fun apply(node: PlaneNode, state: CharacterState, cameraLocal: Vec2, cameraHeightM: Float, walkPhaseMs: Long,
                 verticalVelocity: Float = 0f, sinceLandedMs: Long = Long.MAX_VALUE, size: Float = 1f, emotePose: EmotePose? = null) {
           val p = poser.pose(state, cameraLocal, cameraHeightM, walkPhaseMs, verticalVelocity, sinceLandedMs, size, emotePose)
           node.position = localPosition(p.pos, p.h)
           node.quaternion = p.rotation.toNode()
           node.scale = Scale(x = if (p.mirrored) -p.scaleX else p.scaleX, y = p.scaleY, z = 1f)
       }
   }
   /** The one place a :core rotation becomes a node's. */
   internal fun Quat.toNode(): Quaternion = Quaternion(x, y, z, w)
   ```
   with one `// HAND-REVIEW: T52 — ...` note at the class saying: the maths is in `BillboardPoser`, these three writes
   are the whole host side; the squash / stretch numbers and `FLIP_DEADBAND` went with it; `FEET_DEPTH_BIAS` stays
   here. Imports the move leaves unused are removed. `apply` keeps its signature, so its call site (818-824) is untouched.
3. `ar/RudeusArView.kt:244`: `CharacterPoser(sprite)` -> `CharacterPoser(id, sprite)`, and one header line
   `// HAND-REVIEW: T52 — the poser's maths is a pure BillboardPoser returning a NodePose; nothing else changed here.`

Why this is exact, not approximate: the position expressions are the same expressions held in two `val`s; the four
quaternion components are copied into a `Quat` and back into a `Quaternion(x, y, z, w)`, the constructor `hamilton`
itself ends with (89-94); and `flip * sx * (1 - pull) * size` with `flip` = 1 or -1 is, bit for bit, plus or minus
`sx * (1 - pull) * size`. No SceneView or ARCore call is added, removed or made differently: the same three setters
(`position`, `quaternion`, `scale`) get the same values.

### Core file, commit B: the only differences from the host file, comments aside
1. `package kz.rudy.ar.ar` -> `package kz.rudy.ar.core.session`.
2. `internal` dropped from `NodePose` and `BillboardPoser` (the constants and `billboardRotation` stay `internal`);
   `@Serializable` on `NodePose` and its import.
3. `billboardRotation`, the axes and their imports: no kotlin-math. `Y_AXIS` / `Z_AXIS` are `Vec3`, `hamilton` is
   gone (`Quat.times` is the same product, `core/.../geometry/Math3.kt:53-58` against `ar/CharacterNode.kt:89-94`),
   and the body is `Quat.fromAxisAngle(Y_AXIS, yawDeg * DEG_TO_RAD) * Quat.fromAxisAngle(Z_AXIS, tiltDeg * DEG_TO_RAD)`
   with `private const val DEG_TO_RAD = 0.017453292f`, the factor kotlin-math 1.8.0 multiplies by.
4. Comments: no `HAND-REVIEW` marker in `:core`; keep what each note says.
`FLIP_DEADBAND` stays the literal `0.15f` (the diff must be clean); a test ties it to `ViewPicker.MIRROR_DEADBAND`.

### Host diff, commit C (the switch; HAND-REVIEW; the whole of it)
1. Delete `ar/BillboardPoser.kt` and `androidApp/src/test/kotlin/kz/rudy/ar/ar/BillboardPoserPinTest.kt`.
2. `ar/CharacterNode.kt`: add `import kz.rudy.ar.core.session.BillboardPoser`, and the T52 note gains
   "(now `core.session.BillboardPoser`, pinned by JVM tests)". `CharacterPoser` does not change: the names are the same.
Nothing else in `androidApp/src/main`.

## Tests required
One file, written in commit A as `BillboardPoserPinTest` (package `kz.rudy.ar.ar`, so it binds to the host's
`internal` classes; it runs under `:androidApp:testDebugUnitTest` because nothing in it touches `android.*` and
kotlin-math is a plain JVM jar). In commit B the same file, with only the package line and the class name changed,
is `BillboardPoserTest` in `:core`. `kotlin.test` only, camelCase names, no explicit import of `BillboardPoser`,
`NodePose` or the constants (same-package resolution is what lets one file serve both); `GameLoop`, `ViewPicker`,
`SpriteViews`, `Quat`, `Vec3` are imported explicitly in both. Floats within 1e-5.
Rotation, asserted on what the quaternion does to the quad's axes (`rotation.rotate(Vec3)`), not on angles:
- `quadFrontPointsAtTheCameraInThePlane` — front (0, 0, 1) becomes `toCam` normalised with y = 0, for cameras all round
- `rotationIsYawThenTiltAboutTheQuadsOwnAxis` — a left-drawn sprite at the origin facing (1, 0), camera at (1, 0) (yaw 90, no flip), emote tilt 30: the front is still (1, 0, 0) and the quaternion is (0.18301, 0.68301, 0.18301, 0.68301); the other order has x = -0.18301 and tips the front up
- `zeroTiltIsAPureYaw` — x = z = 0 for any camera
- `bowLeansTheWayTheSpriteLooks` — positive emote tilt, both `facesRight`, walking screen-left and screen-right: the quad's up leans toward the viewer's right (`(cos yaw, 0, -sin yaw)`) exactly when the character walks screen-right
- `emoteYawSpinsTheQuadAndLeavesTheFlipAlone`
Flip:
- `flipFollowsFacingAcrossTheLineOfSight`, `flipUsesTheSpritesDrawnFacing`
- `flipHoldsInsideTheDeadbandAndStartsUnmirrored`
- `flipMatchesViewPickerForASideBaseSprite` — 2 000 seeded frames, character and camera both moving, camera never within 10 cm in the plane, both `facesRight`: `pose.mirrored == ViewPicker.pick(facing, toCam, previous, SpriteViews.of(SIDE, facesRight, emptyMap())).mirrored` on every frame
- `flipDeadbandIsTheViewPickersMirrorDeadband`
- `cameraStraightAboveFlipsByFacingU` — `toCam` = 0: no NaN anywhere, and the flip follows `facing.u` (where `ViewPicker` would hold; T50 decides)
Position:
- `feetStandWherePhysicsPutsThemWithoutBias`
- `feetDepthBiasKeepsTheLineOfSightAndShrinksTheQuadByTheSameFactor` — bias 0.6: camera, posed feet and true feet are collinear, the distance and both scales shrink by `1 - pull`
- `feetDepthBiasIsCappedAtHalfWayAndOffAtTheLens`
- `hopRaisesByHopTimesHeightTimesSize`
Scale:
- `idleFallingAndPausedAreUnitScale`
- `walkBobFollowsTheWalkPhase` — 100 ms: (0.95, 1.08); 300 ms: (1.05, 0.92); 0 and 200 ms: (1, 1)
- `risingJumpStretches`
- `landingSquashEasesOutOver120Ms` — 0 ms: (1.25, 0.75); 60 ms: (1.03125, 0.96875); 120 ms and a negative clock: none
- `landingSquashWinsOverTheBobAndNeedsTheGround`
- `emoteScalesMultiplyAndSwitchTheBobOff`
- `sizeScalesBothAxes`, `scaleMagnitudesNeverCarryTheFlip`
Identity and the loop:
- `poseCarriesThePosersId`
- `jumpOnTheResumeFrameStillStretchesThoughItsEventIsLost` — a `GameLoop`, a tracking loss, a jump from `beforeStep` on the first frame back (events: RESUMED only), the poser fed from the loop's state: (0.85, 1.15) while it rises
That is 25. One more, Android only, written in commit A and kept: `NodeRotationTest.quatReachesTheNodeWithItsComponentsInOrder`
(`Quat(0.1f, 0.2f, 0.3f, 0.4f).toNode()` has x, y, z, w = 0.1, 0.2, 0.3, 0.4).

These are characterization tests. The numbers above are derived from the code by hand; if one is not green against
the host class, the test is wrong or it found something: assert what the code does and report it in the Result.
Do not change the maths to fit a test.

Tests not required: Compose, AR rendering, `CharacterPoser.apply`, the sprite and texture code, anything on a device.

## Steps
1. Read `ar/CharacterNode.kt`, `ar/PlaneCoords.kt`, `.claude/skills/hand-review/SKILL.md`, the Result of T47, section 2
   of T43, `core/.../emote/SpriteViews.kt` (`ViewPicker`) and its test's oracle, `core/.../geometry/Math3.kt`.
2. **Commit A** `androidApp: split the poser's maths from its node writes and pin it (T52; hand-review)`: host diff A
   plus the two test files. Prove the split is a move:
   `diff <(git show HEAD^:androidApp/src/main/kotlin/kz/rudy/ar/ar/CharacterNode.kt | sed -n '201,250p' | sed -e 's/sprite\.heightM/heightM/g' -e 's/sprite\.facesRight/facesRight/g' -e 's/FEET_DEPTH_BIAS/feetDepthBias/g') <(sed -n '<first>,<last>p' androidApp/src/main/kotlin/kz/rudy/ar/ar/BillboardPoser.kt)`
   over the body of `pose` (first statement to `return`; run it right after committing A) may show three hunks only:
   old 207, 225, 250 against the four lines named above. The comments inside the body stay as they are in this
   commit, so they do not show. Then
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain -q`
3. **Commit B** `core: BillboardPoser and NodePose in core.session with the same tests (T52)`. Both classes exist now,
   in different packages; the host still uses its own. Prove the move is a move:
   `diff <(grep -vE '^\s*(//|/\*|\*)' androidApp/src/main/kotlin/kz/rudy/ar/ar/BillboardPoser.kt) <(grep -vE '^\s*(//|/\*|\*)' core/src/commonMain/kotlin/kz/rudy/ar/core/session/BillboardPoser.kt)`
   may show only differences 1-3 of "Core file", and `diff` of the two test files only the package line and the
   class name. Then
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :core:jvmTest :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64 :androidApp:testDebugUnitTest --console=plain -q`
   The same 25 assertions are green against both classes at this commit: that is the proof of equivalence,
   including the one piece that is re-expressed and not moved, the rotation.
4. **Commit C** `androidApp: the poser's maths comes from :core (T52; hand-review)`: host diff C, nothing else.
5. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`. Without `/opt/android-sdk`
   commit A cannot be compiled: say so and stop before it; do not land an uncompiled host change.
6. Fill the Result, set `status: review`. The final message names the three commits and the host files, and says
   "needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; the core test count is up by at least 25; the android count is its old value plus 1
- both `diff`s of steps 2 and 3 were as described (paste their output into the Result)
- `git diff <A>^..<C> --stat -- androidApp/src/main`: no `ar/BillboardPoser.kt` left, `RudeusArView.kt` at most 3 changed lines, `CharacterNode.kt` shorter
- `grep -rn "HAND-REVIEW" core/src` prints nothing; no file outside "Owns" changed
- **The one Pixel check (human, about a minute), "walk, bow, jump":** one character on the table. Tap to its right,
  then to its left, so it walks across your view both ways: it turns to face the way it walks, once each way, and
  bobs as it goes. After each walk press Bye: it bows toward the side it is looking at, stays facing you (it does
  not tip toward or away from the phone), and its feet stay on the table. Press Jump once: it stretches going up
  and squashes on landing. If anything is off, revert commit C and look again: if it is now right, the fault is the
  rotation re-expressed in `:core` (the only part that is not a move); if it is still wrong, it is commit A's split,
  revert that too and report what was seen.

## Result (filled on acceptance)
- measured: ...
- step-2 and step-3 diffs: ...
- found by the characterization tests: ...
- Pixel check: ...
- cut/deferred: ...
