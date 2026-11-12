# T53 — thin-host-step-3

status: review
created: 2026-11-11
phase: v2 wave 3 (ai/build-plan-v2.md, "iOS readiness": thin host). Step S3a of the map in ai/tasks/T43-thin-host-map.md
gate: G1 foundation (`scripts/verify.sh` green, `:core` and `:shared` still compile for iOS); a precondition of S3b, T54 and T50
cut-if-slips: never cut. Two revert points, see "Done when": commit C alone, then commit A
depends: T47 and T52 merged **and their Pixel checks passed by the user** ("hand over the lens", "walk, bow, jump").
  T53 builds on both and is not dispatched before that. Not while another brief is `doing` on `shared/.../ArSlot.kt`
  or `App.kt` (T49 adds actions to `CharacterAction`: whichever lands second rebases onto the type's new home)
tier: rudeus-opus-extra (the plan table's tier: the split is specified line by line and proved by script, as T52 was;
  the review and the Pixel check are the safety net). Dispatched 2026-11-12 at d79c7e5+: `ar/RudeusArView.kt`, `ArSlot.kt`
  and `core/session` are unchanged since 23a9ea2, so every `file:line` below still holds; T45, T49 and T57 are merged

`file:line` below is the line at 23a9ea2. Paths without a module are under `androidApp/src/main/kotlin/kz/rudy/ar/`.

## Goal
A `TableEngine` in `core.session` owns the game from the moment the anchor exists: it holds the `GameLoop`, locks the
surface, keeps the physics cast in step with the characters the UI wants, knows whose buttons they are, and applies
each command (Jump, WalkForward, an emote) exactly once. A small composable in `:shared`, `rememberTableEngine`, turns
`ArSlot` edges into engine calls. The Android host keeps what only it can do (the session, the anchor, the frame, taps,
scans) and reads the loop through the engine. Today those 90-odd lines are closures inside `RudeusArView`
(`ar/RudeusArView.kt:235-241, 330-344, 414-451, 574-588, 599-606`) and have never run in a test. After this brief an
iOS host does not write them, and "one anchor per session" and "a command is applied once" are enforced and tested in
common code. The player sees no difference.

## Decisions taken here (not open)
1. **S3 is two briefs.** T53 is S3a: everything that reacts to a slot edge or to the anchor. S3b is the frame step and
   is outlined at the end, with its own Pixel check. Reasons: the two halves fail differently on the phone (a button
   that acts twice or not at all, against a character that slides, freezes or vanishes), so one check cannot tell them
   apart; S3a adds no per-frame code and no seam type the frame needs, so its host diff is reviewable alone; and the
   engine's one temporary exposure, `val loop`, is needed by the host's tap and scan code until S4 and S5 anyway, so
   the split costs no throw-away API. One change against the map: **the scenario start moves in S3b, not here.** Its
   player is created by the request and consumed by the frame (`ar/RudeusArView.kt:453-465, 783-802`); moving one
   without the other would make the engine lend its player to the host for one brief.
2. **Seam types.** T53 introduces `Member`, `TableSlot`, `TableEngine` and moves `CharacterAction` / `CharacterCommand`
   to `:core`; every field is filled and tested here. It introduces **no `FrameInput`, no `CameraFrame`, no `ArPort`**:
   nothing S3a moves reads a frame or asks the AR session a question. The one AR value it needs, camera-right for
   the spawn, is asked for lazily (`ar/RudeusArView.kt:583`: only when a member joins), so it comes in as
   `() -> Vec2` at the two call sites. S3b introduces `FrameInput(nowMs, tracking, camera)` and
   `CameraFrame(pos, heightM, right)`, exact shapes in the outline: that is everything `BillboardPoser.pose` reads,
   and `ViewPicker.pick(facing, toCamera, ...)` needs only `camera.pos - state.pos`, so T50 changes no host line for
   it. `ArPort` arrives in T54 with its first two questions (`hitTable`, `depthGapsOnWay`). Later fields are appended
   with a default that means "as today", as `NodePose` was built.
3. **Edge triggers: Compose keeps deciding when, the engine decides what, and the engine also refuses a repeat.**
   The four `LaunchedEffect`s keep their keys, moved as they are into `rememberTableEngine`
   (`ar/RudeusArView.kt:341, 415, 417, 421`), so on Android nothing about "once per change" changes. The engine's
   `command` additionally remembers the last serial it was offered and ignores the same one twice, so a caller
   without Compose keys (a test, a future host) gets the rule too; on Android that guard can never fire, because a
   keyed effect only runs when the key differs from the last. Pinned by `theSameSerialOfferedTwiceIsAppliedOnce` and
   `aSerialOfferedAgainAfterNullIsApplied`. The other counters: `jumpRequest`, `walkForwardRequest` and `ability` are
   ignored by this host today (`ar/RudeusArView.kt:139-140`) and the engine never reads them; `scenarioRequest` moves
   in S3b and `scanRequest` in S5, each the same way (a keyed effect plus a last-seen guard, 0 = nothing asked).
4. **Threading and lifetime.** The engine is confined to the main thread, has no lock and starts no coroutine. Three
   kinds of caller, all on main, as today: composition (`offerSurface`, a plain field write, exactly what lines
   331-336 do now), effects (`configure`, `activeOffered`, `abilities`, `command`), and the AR frame callback and tap
   handler (`syncCast`, `anchorPlaced`, `activeId`, `loop`). Callbacks run on the caller's stack and must not call
   back into the engine (today's only write Compose state). It lives exactly as long as the AR view's composition:
   `remember`ed without keys inside `rememberTableEngine`, so a new `ArSlot` never recreates it, it survives
   Scanning -> Play (App composes the slot at one call site, `shared/.../App.kt:610`), and it dies when the session
   ends. Nothing is carried to the next session: App resets the request counters to 0 and `command` to null and never
   lowers the serial (`shared/.../App.kt:566-582`, `shared/.../ui/components/CastUi.kt:44-49`), and a new engine has
   been offered nothing.
5. **Proof that it is a move.** The code cannot be pinned where it is: it lives in closures of a composable around an
   `ARSceneView`. So, as in T52, commit A makes the split inside the host (two host-local files with the names the
   common ones will have), a script proves each method body is the old lines after a fixed list of 16 substitutions,
   and JVM tests then pin the host-local engine. Commit B adds the same files to `:core` and `:shared` with the same
   tests; a comment-stripped diff may show only package, visibility and import lines. Commit C deletes the host
   copies and changes imports. What a human approves: about 75 lines out of and 25 into `RudeusArView.kt` in A, nine
   named edits; 3 lines in C; 1 import line in P.
6. **Pixel check**: one two-minute sequence, under "Done when". It covers what T53 moved (anchor, cast, commands)
   and what T53 only rewired (a scan, a tracking loss, the scenario replay), because those now read the engine's loop.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/Commands.kt (new: `CharacterAction`, `CharacterCommand`, moved from `:shared`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt (new: `Member`, `TableSlot`, `TableEngine`)
- core/src/commonTest/kotlin/kz/rudy/ar/core/session/TableEngineTest.kt (new)
- shared/src/commonMain/kotlin/kz/rudy/ar/TableEngineHost.kt (new: `rememberTableEngine`, `ArSlot.toTableSlot`)
- shared/src/commonTest/kotlin/kz/rudy/ar/TableSlotTest.kt (new)
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt (the two types leave, lines 46-54), App.kt, ui/components/CastUi.kt, shared/src/commonTest/kotlin/kz/rudy/ar/CastUiTest.kt (import lines only)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt, TableEngineHost.kt (HAND-REVIEW: new in commit A, deleted in commit C)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt (HAND-REVIEW: commits P, A, C, only the edits under "Host diff")
- androidApp/src/test/kotlin/kz/rudy/ar/ar/TableEnginePinTest.kt (new in commit A, deleted in commit C)
- ai/tasks/T53-thin-host-step-3.md (the Result section)

Does not move and must not touch: the frame callback (`ar/RudeusArView.kt:711-830`), the scenario (453-465), every
scan line (390-412, 487-553, 751-779), taps and the finger grab (472-480, 558-564, 610-680, 836-892), the pre-anchor
gate (723-743, 843-856), `ar/CharacterNode.kt`, `ar/CastTouch.kt`, `core/session/GameLoop.kt` and `BillboardPoser.kt`,
`core/duel/**`, `core/physics/**`, `core/emote/**`, every Gradle file. No `Stage` interface, no
duel code, no `FrameInput`.

## Contract
```kotlin
package kz.rudy.ar.core.session

// Commands.kt: moved from shared/.../ArSlot.kt:46-54, unchanged but for the package
sealed interface CharacterAction { data object Jump; data object WalkForward; data class Emote(val emote: Emote) }
data class CharacterCommand(val serial: Int, val id: String, val action: CharacterAction)

/** One character the engine keeps on the table: what physics and the emotes need. No pictures. */
class Member(val id: String, val ability: Ability, val emotes: EmoteSet)

/** The engine's view of one `ArSlot`: what the UI wants and where it listens. One immutable value per slot. */
class TableSlot(
    val members: List<Member>,                             // in cast order; the first is the one Play started with
    val activeId: String?,
    val command: CharacterCommand?,
    val soundsOn: Boolean,
    val onAnchorPlaced: () -> Unit,
    val onCastEvent: (String, CharacterEvent) -> Unit,
)

class TableEngine(
    private val current: () -> TableSlot,                  // the latest slot, read on every use (`currentSlot` today)
    surface: Surface,                                      // the first slot's; followed until the anchor
    private val playEmoteSound: (EmoteSound) -> Unit,      // the engine gates it on `soundsOn`
    private val onMemberRemoved: (String) -> Unit,         // the host's finger bookkeeping, until T54
    private val log: (String) -> Unit,                     // one `RudeusGate` line, byte for byte as today
    separate: Boolean = true,                              // the host's SEPARATION_ENABLED switch
) {
    val loop: GameLoop                 // read by the host's frame, tap and scan code until S3b, T54 and S5 take them
    val surface: Surface               // private set; locked at the anchor
    val profile: SurfaceProfile        // SurfaceProfile.of(surface)
    val anchored: Boolean              // private set
    fun offerSurface(wanted: Surface)                      // composition time            <- lines 331-336
    fun configure(size: Float)                             // effect (surface, size)      <- 342-343
    fun activeOffered(id: String?)                         // effect (activeId): one log  <- 415
    fun abilities(members: List<Member>)                   // effect (members)            <- 417
    fun command(from: TableSlot)                           // effect (command serial)     <- 422-450
    fun activeId(): String?                                // the slot's when on the table, else the first <- 414
    fun syncCast(cameraRight: () -> Vec2)                  // every anchored frame        <- 575-587
    fun anchorPlaced(cameraRight: () -> Vec2)              // the session's ONE anchor    <- 599, 603-605
}
```
```kotlin
package kz.rudy.ar   // shared/src/commonMain/kotlin/kz/rudy/ar/TableEngineHost.kt

/** [members] with the slot's command, active id, sounds flag and its two callbacks, as the engine reads them. */
fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot

/**
 * The engine of one AR session and the four effects that feed it. Call it once, before anything reads the surface.
 * [members] = who the host renders (`slot.characters`; the Android host still has its v1.0 legacy member).
 */
@Composable
fun rememberTableEngine(
    slot: ArSlot, members: List<ArCharacter>,
    playEmoteSound: (EmoteSound) -> Unit, onMemberRemoved: (String) -> Unit, log: (String) -> Unit,
    separate: Boolean = true,
): TableEngine
```
`rememberTableEngine` is: `remember(slot, members) { slot.toTableSlot(members) }` held in `rememberUpdatedState`; the
three lambdas held the same way; `remember { TableEngine(...) }`; `offerSurface(slot.surface)`; then
`LaunchedEffect(game.surface, slot.characterSize) { configure }`, `LaunchedEffect(slot.activeId) { activeOffered }`,
`LaunchedEffect(members) { abilities }`, `LaunchedEffect(slot.command?.serial) { command(view) }`: the keys of
`ar/RudeusArView.kt:341, 415, 417, 421`. `Member` has the property names of `ArCharacter` (`id`, `ability`, `emotes`)
on purpose, so the moved bodies read the same.

**What must stay exactly as it is** (the rules the tests pin):
- Surface (331-336): follows the slot until the anchor; after it a different wish is ignored and logged
  `surface_change_ignored anchored=.. requested=..` once per value.
- Anchor (599-606): `anchored`, then `onAnchorPlaced()`, then the cast sync (one `PLACED` per member, in slot order).
  The host still calls `onTracking(true)` after it, so the HUD sees the same order.
- Cast sync (575-587): a member gone from the slot leaves the loop, its finger is released, `cast_remove id=..`; a
  member not yet in the loop joins at `loop.spawnFor(active member's position or the origin, camera-right)` with its
  ability, `cast_add id=.. n=.. u=.. v=..`, then `onCastEvent(id, PLACED)`. Camera-right is asked for only when
  someone joins. `slot` and `active` are read afresh on every call.
- Commands (422-450): before the anchor or for an id not on the table, `command_ignored serial=.. id=.. anchored=..`.
  Jump: `loop.jump`; `jump id=..` or `jump_ignored id=.. mode=.. ability=..` with the mode from before the call.
  WalkForward: 10 cm x `profile.scale` along `facing`; a target outside the boundary is skipped,
  `voice_walk_outside_boundary`. Emote: the choreography of that member **in the slot that sent the command**, the
  sound only when that slot has `soundsOn`, `emote id=.. emote=.. source=.. ms=..`.
- New, and unreachable from the Android host: a second `anchorPlaced` is refused and logged (`anchor_refused`), and a
  serial offered twice in a row is applied once.

**How the earlier findings bind this step.** Commands arrive between frames. A Jump while the cast is paused is
ignored by physics, not queued (`jump_ignored .. mode=PAUSED`). A WalkForward while paused sets the target and keeps
the pause (`core/.../physics/Physics.kt:172-178`), so the walk starts after the resume, and by T47's finding 1 its
STARTED_WALKING is folded into RESUMED. T47 inferred that; T53 pins it, as found, with no fix.

**One thing that is not identical, and why it is harmless.** The effects of lines 415, 417 and 421 are now declared
before those of 378, 386 and 399 (they sit in `rememberTableEngine`, which must run before the surface is read). When
a recomposition changes two keys at once, their bodies run in the new order. They share no state (the hint and the
scan flags on one side, the loop on the other); only the order of two log lines can differ. `configure` still comes
first and the command still comes before the scenario start (453).

### Host diff, commit P (the type move)
`CharacterAction` and `CharacterCommand` go to `core/session/Commands.kt`; `:shared` gets import lines
(`ArSlot.kt`, `App.kt`, `ui/components/CastUi.kt`, `CastUiTest.kt`); `ar/RudeusArView.kt:53` becomes
`import kz.rudy.ar.core.session.CharacterAction`. This is the one commit that touches the zone together with `:core`
and `:shared`: a type cannot move in two commits without a broken build between them. The zone part is that one line.

### Host diff, commit A (the split; HAND-REVIEW)
New `ar/TableEngine.kt` (package `kz.rudy.ar.ar`, `internal`, no Android, AR or Compose import) and
`ar/TableEngineHost.kt` (`rememberTableEngine`, `toTableSlot`), each opening with one `// HAND-REVIEW: T53 — ...`
note. Then in `ar/RudeusArView.kt`, nine edits and nothing else:
1. Imports: `CharacterAction` and `GameLoop` go (unused now).
2. Header: `// HAND-REVIEW: T53 — the surface lock, the cast sync, the active member and the commands are a pure`
   `// TableEngine fed by rememberTableEngine; one anchor per session and PAUSED never resets are untouched.`
3. `SlotState` (235-241) keeps `surfaceReady` and `tracking`, takes the engine, and answers `surface` and `profile`
   from it, so the nine places that read `slotState.profile` / `slotState.surface` in scan, tap and scenario code
   (459, 464, 503, 519, 559, 602, 655, 666, 878) do not change.
4. Line 313 becomes
   `val game = rememberTableEngine(slot, members, playEmoteSound = { sounds?.play(it) }, onMemberRemoved = touch::unbindMember, log = { Log.i(GATE_TAG, it) }, separate = SEPARATION_ENABLED)`
   and `val loop = game.loop`, so every remaining `loop.` reads as before. `val members = slot.members()` (348) moves
   up above it.
5. Lines 330-338 become `val slotState = remember { SlotState(game) }`, `val surface = slotState.surface`,
   `val profile = slotState.profile`.
6. The size effect (341-344) goes; its note (339-340) stays, pointing at `rememberTableEngine`.
7. Lines 413-451 become `fun activeId(): String? = game.activeId()` (the tap code at 864 and 867 keeps calling it).
8. `syncCast` (574-588) becomes `fun syncCast(a: Anchor, frame: Frame) = game.syncCast { cameraRight(a, frame) }`
   (its call at 782 does not change).
9. `placeAnchor`: line 599 goes; 604-605 become `game.anchorPlaced { cameraRight(created, frame) }`; `val s = currentSlot`
   (603) and `s.onTracking(true)` (606) stay around it. The lock is now set three lines later than today, after the
   probe and the `anchor_plane_age_ms` log (601-602); neither reads it.
No SceneView or ARCore call is added, removed or made differently.

### Host diff, commit C (the switch; HAND-REVIEW; the whole of it)
Delete `ar/TableEngine.kt`, `ar/TableEngineHost.kt` and `TableEnginePinTest.kt`; in `ar/RudeusArView.kt` add
`import kz.rudy.ar.core.session.TableEngine` and `import kz.rudy.ar.rememberTableEngine`, and the T53 note gains
"(now `core.session.TableEngine` and `:shared` `rememberTableEngine`, pinned by JVM tests)".

## Tests required
One file, written in commit A as `TableEnginePinTest` (package `kz.rudy.ar.ar`; it runs under
`:androidApp:testDebugUnitTest` because the engine touches no `android.*`), and in commit B, with only the package
line and the class name changed, as `TableEngineTest` in `:core`. `kotlin.test` only, camelCase names, no explicit
import of `TableEngine`, `TableSlot` or `Member`. The test drives frames itself with `engine.loop.onFrame`, as the
host does. Log lines with floats are asserted by their key and integer fields only (float text may differ on iOS).
Surface:
- `surfaceFollowsTheSlotUntilTheAnchor`
- `surfaceIsLockedAtTheAnchorAndAnIgnoredChangeIsLoggedOnce`
- `configureSizesTheLoopForTheLockedSurfaceAndLogsIt`
Anchor and cast:
- `nobodyIsOnTheTableBeforeTheAnchor`
- `anchorPlacedReportsTheAnchorThenPlacedPerMemberInSlotOrder`
- `aSecondAnchorIsRefused`
- `firstMemberStandsAtTheOriginLaterOnesNearTheActiveTowardCameraRight` — equal to `loop.spawnFor(..)`
- `cameraRightIsAskedOnlyWhenSomeoneJoins`
- `aMemberAddedToTheSlotJoinsOnTheNextSyncWithItsAbility`
- `aMemberGoneFromTheSlotLeavesTheLoopAndItsFingerIsReleased`
- `syncCastWithNothingNewDoesNothing` — no event, no log, the same `Cast` instance
- `activeIsTheSlotsWhenOnTheTableElseTheFirst`
- `abilitiesFollowTheSlotAndUnknownIdsAreIgnored`
Commands:
- `commandBeforeTheAnchorIsIgnoredAndLogged`, `commandForAMemberNotOnTheTableIsIgnoredAndLogged`
- `jumpJumpsThatMemberOnly`
- `jumpThatPhysicsIgnoresIsLoggedWithTheModeBeforeIt` — airborne and ROLL
- `walkForwardIsTenCentimetresTimesTheSurfaceScaleAlongFacing` — table and floor
- `walkForwardOutsideTheBoundaryIsSkippedAndLogged`
- `emoteStartsTheChoreographyAndPlaysItsSoundOnlyWhenSoundsAreOn`
- `emoteUsesTheMembersOfTheSlotThatSentIt` — missing from that slot: nothing happens, whatever the latest slot holds
- `aNullCommandDoesNothing`
- `theSameSerialOfferedTwiceIsAppliedOnce`, `aSerialOfferedAgainAfterNullIsApplied`
Across a tracking loss:
- `jumpDuringATrackingLossIsIgnoredNotQueued`
- `walkForwardDuringATrackingLossWalksAfterResumeWithoutItsStartEvent`
Evidence:
- `logLinesKeepTheirKeys` — the exact text of every float-free line above, the key and field names of the others
That is 27. In `:shared` (commit B only): `TableSlotTest.tableSlotCarriesTheSlotsFieldsAndCallbacks`.

These are characterization tests: if one is not green against the host-local engine, the test is wrong or it found
something. Assert what the code does and report it in the Result; do not change the moved bodies to fit a test.

Tests not required: `rememberTableEngine` (Compose; its proof is the diff), `RudeusArView`, anything on a device.

## Steps
1. Read `ar/RudeusArView.kt` whole, `shared/.../ArSlot.kt`, `App.kt:532-610`, `core/session/GameLoop.kt`, the Results
   of T47 and T52, `.claude/skills/hand-review/SKILL.md`.
2. **Commit P** `core: CharacterCommand and CharacterAction move to core.session (T53; hand-review)`.
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :core:jvmTest :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug --console=plain -q`
3. **Commit A** `androidApp: split the table engine out of the AR view and pin it (T53; hand-review)`. Prove the split
   is a move. Save the old file first (the worktree guard refuses `git` inside `<(...)`):
   `git show HEAD:androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt > $S/old.kt` before editing, and write
   `$S/t53.sed` with exactly these 16 lines:
   ```
   s/Log\.i(GATE_TAG, /log(/
   s/return@LaunchedEffect/return/
   s/slotState\.//g
   s/anchorRef\[0\] == null/!anchored/
   s/anchorRef\[0\] != null/anchored/
   s/currentSlot\.members()/current().members/
   s/currentSlot\./current()./g
   s/slot\.command/from.command/
   s/members\.firstOrNull/from.members.firstOrNull/
   s/slot\.soundsOn/from.soundsOn/
   s/sounds?\.play(/playEmoteSound(/
   s/cameraRight(a, frame)/cameraRight()/
   s/touch\.unbindMember(/onMemberRemoved(/
   s/slot\.characterSize/size/g
   s/slot\.surface/wanted/g
   s/slot\.activeId/id/g
   ```
   For each row, `sed -n '<old lines>p' $S/old.kt | sed -f $S/t53.sed | diff -w - <(sed -n '<body lines>p' androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt)`:

   | Engine method | Old lines | May differ |
   |---|---|---|
   | `offerSurface` | 331-336 | nothing |
   | `configure` | 342-343 | nothing |
   | `activeId` | 414 | nothing |
   | `command` | 422-450 | three added lines at the top: the last-offered-serial guard |
   | `syncCast` | 575-587 | nothing |
   | `anchorPlaced` | 599, 603-605 | by eye, four lines: the refusal guard, and `syncCast(cameraRight)` for `syncCast(created, frame)` |
   | `activeOffered`, `abilities` | 415, 417 | by eye, one line each (the effect wrapper is gone) |

   and the four effect keys in `ar/TableEngineHost.kt` are the expressions of lines 341, 415, 417, 421. Then
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain -q`
4. **Commit B** `core, shared: TableEngine and rememberTableEngine with the same tests (T53)`. Prove the move is a move:
   `diff <(grep -vE '^\s*(//|/\*|\*)' androidApp/src/main/kotlin/kz/rudy/ar/ar/TableEngine.kt) <(grep -vE '^\s*(//|/\*|\*)' core/src/commonMain/kotlin/kz/rudy/ar/core/session/TableEngine.kt)`
   and the same for `TableEngineHost.kt` against the `:shared` file may show only the package line, `internal`
   dropped, and import lines; the two test files only the package line and the class name. Then
   `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :core:jvmTest :core:compileKotlinIosSimulatorArm64 :core:compileTestKotlinIosSimulatorArm64 :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:testDebugUnitTest --console=plain -q`
   The same 27 assertions are green against both engines at this commit.
5. **Commit C** `androidApp: the table engine comes from :core and :shared (T53; hand-review)`: host diff C only.
6. `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh`. Without `/opt/android-sdk`
   commits P and A cannot be compiled: say so and stop before P.
7. Fill the Result in its own commit, set `status: review`. The final message names the four commits and the host
   files, and says "needs your Pixel run" with the check below.

## Done when
- `scripts/verify.sh` exit 0; core tests up by at least 27, shared by at least 1, android back to its old count
- the diffs of steps 3 and 4 were as described (paste their output into the Result)
- `git diff <P>^..<C> --stat -- androidApp/src/main`: no `ar/TableEngine*.kt` left; `RudeusArView.kt` about 30 lines in, 75 out
- `grep -rn "HAND-REVIEW" core/src shared/src` prints nothing new; no file outside "Owns" changed
- **The one Pixel check (human, about two minutes), "place, scan, two friends, lens, replay":** a cup on the table,
  footprints on.
  1. Tap the table: the character appears where you tapped, once, with its sound.
  2. Press Scan: "found" appears and the character cannot walk through the cup.
  3. Add a second character: it appears beside the first, toward the right of the screen, not on top of it.
  4. Press Jump: the active one jumps once. Pick the other chip, press Hello: only that one waves, one sound.
  5. Cover the lens, press Jump while it is covered, uncover after 3 seconds: both reappear where they stood and
     nobody jumps.
  6. Press Scenario: the first character plays the script to the end.
  In `scripts/device.sh log`: one `cast_add` per character with `n=1` then `n=2`, one `jump id=`, one `emote id=`,
  one `jump_ignored .. mode=PAUSED`, `scenario_done ms=` just over 15000, no `anchor_refused`. If anything is off,
  revert commit C and look again: if it is now right, the fault is in what differs between the host files and the
  common ones (imports and visibility only, so report it); if it is still wrong, it is commit A's split: revert A
  too and report which step failed.

## How a duel becomes the engine's stage (not built here)
After S3b the host talks to the engine only through `anchorPlaced`, `onFrame(FrameInput): List<NodePose>` and, after
T54, taps; none of them names `GameLoop`. Inside, the frame is two parts: a stage that turns `(nowMs, tracking)`
into "who is on the table and in what state", and the poser that turns that into `NodePose`s. Today the stage is
`GameLoop`. A duel is a second stage over `DuelMatch.step(state, arena, dtMs, inputs, tracking, rules, params,
physics)`: it clamps `dt` and forgets the frame clock on a tracking loss as `GameLoop` does, turns a table tap into
`DuelInput.MoveTo` and a command into Dash / Jump / Special, hands each `DuelFighter.body` to the same poser, and keeps
its own walk and landing clocks from state, not events (T47's finding). The slot says which stage runs through a
field appended to `TableSlot`; the fighters are members like any other, so the host's rigs need nothing new. The
interface between the two is extracted when the second implementation exists, in the duel-stage brief, as a
`:core`-only change pinned by S3b's tests. **For that to hold, the host must stop reading `engine.loop` before a
second stage exists**: T54 removes the tap uses, S5 the scan uses. T50 is planned before S5, so either S5 lands
first or S3b puts the scan's reads of the level and of where the members stand, and its one write of the level
(`ar/RudeusArView.kt:518, 521, 531-534`), behind `engine.level` and `engine.standing()`. That is a plan decision for
the orchestrator.

## Outline of S3b (the frame step; its own brief, before T54)
- **Moves**: the tracking edge (`ar/RudeusArView.kt:745-750` and `slotState.tracking`, 600, 606), the scenario start
  and feed (453-465, 783-802), `loop.onFrame` (803), the cast-state report (806-809), the reappear timer (810, 815),
  the pose loop (812-827) with the per-member `BillboardPoser` (`ar/CharacterNode.kt:139-140`). The host builds a
  `FrameInput`, calls `engine.onFrame`, and writes each pose into its node: three setters and `isVisible`.
- **Shapes**, only what it fills:
  ```kotlin
  class FrameInput(val nowMs: Long, val tracking: Boolean, val camera: CameraFrame)
  class CameraFrame(
      val pos: Vec2,       // camera position in plane coordinates (`planeCoords`, line 813)
      val heightM: Float,  // above the plane (`planeHeight`, 814)
      val right: Vec2,     // screen-right in the plane (`cameraRight`, 566-569), any length
  )
  fun TableEngine.onFrame(input: FrameInput): List<NodePose>   // empty while not tracking, as nothing is written today
  ```
  The camera is read every frame and trusted only while tracking (camera-right is used for a member that joins
  during a loss, as today). Appended, with as-today defaults: `NodePose.visible = true`; `TableSlot.characterSize`,
  `debug`, `scenarioRequest`, `onTracking`, `onCastState`; `Member.facesRight = false`. The engine constructor gains
  `feetDepthBias` and `reappearAfterTrackingMs`, no defaults: both are Pixel numbers and stay in the host
  (`ar/CharacterNode.kt:52`, `ar/RudeusArView.kt:211-216`). `CAST_STATE_PERIOD_MS` moves. S4 appends `planeToClip` and
  the view size to `CameraFrame`; nothing here changes for it.
- **Pins**: `trackingEdgeIsReportedOnce`, `anchorThenTrackingKeepTodaysOrder`, `hiddenFor800MsAfterTrackingReturns`,
  `noPoseWhileNotTracking`, `posesFollowCastOrderAndSkipNobody`, `castStateAtMostEvery100MsAndOnlyInDebug`,
  `scenarioRequestBeforeTheAnchorIsRejected`, `aNewRequestRestartsTheReplayFromSpawn`,
  `scenarioFeedMatchesScenarioRunner`, `aBeatDueOnTheResumeFrameIsAppliedButItsEventIsLost` (T47's finding where it
  bites), `aRebuiltSpriteStartsUnflippedAgain` (today a new sprite builds a new poser, `ar/RudeusArView.kt:350-354`),
  `engineAndHostPoserAgreeBitForBit` (the same frames through the engine and through a bare `BillboardPoser`).
  T52's findings stay pinned by its own tests; the engine must feed the poser the host clock for `sinceLandedMs`
  (822), not simulated time.
- **Proof**: as here: host-local `onFrame` first, a substitution script over 745-750 and 782-827, then the common copy.
- **Pixel check, "lens and replay"**: one character walking, cover the lens 3 s: it freezes, comes back about 0.8 s
  after tracking with no slide, and finishes its walk; then Scenario plays to the end with `scenario_done ms=` just
  over 15000 and, with footprints on, the mini-map dot follows it.

## Result (filled 2026-11-12, cloud container with /opt/android-sdk, no device)
- commits (on 33b6a13): P `d6bc58e` (the type move), A `e0cc901` (host split + `TableEnginePinTest`), B `4ecf53d`
  (`:core` / `:shared` copies + the same tests), C `a98a74f` (the host switches over). This Result is a fifth,
  docs-only commit, so reverting C alone touches only the host.
- measured (test XML; every Gradle call under `flock /tmp/rudeus-gradle.lock`):
  - baseline 33b6a13, `scripts/verify.sh` exit 0: core 710, shared 42, android 3.
  - P: the step-2 command green; core 710, shared 42 (the moved types are only re-imported).
  - A: `:androidApp:testDebugUnitTest` 30 = 3 + 27 `TableEnginePinTest`, 0 failed; `:androidApp:assembleDebug` green.
  - B: core 737 = 710 + 27 `TableEngineTest`, shared 43 = 42 + `TableSlotTest`, android 30, all 0 failed;
    `:core` and `:shared` main and test klibs compile for iosSimulatorArm64. The same 27 assertions are green against
    both engines at this commit.
  - C: `scripts/verify.sh` exit 0: core 737, shared 43, android 3 (back to the baseline), apk built; a forced
    `:core:jvmTest --rerun` after it: 737, 0 failed.
  - Mutation check at B, on the `:core` engine, one at a time, the file restored byte for byte after each (`cmp`):
    no serial guard -> `theSameSerialOfferedTwiceIsAppliedOnce`; no anchor refusal -> `aSecondAnchorIsRefused`,
    `logLinesKeepTheirKeys`; the latest slot's members or `soundsOn` for an emote -> `emoteUsesTheMembersOfTheSlotThatSentIt`;
    PLACED before `onAnchorPlaced` -> `anchorPlacedReportsTheAnchorThenPlacedPerMemberInSlotOrder`,
    `commandBeforeTheAnchorIsIgnoredAndLogged`; spawn near the origin -> `firstMemberStandsAtTheOrigin...`; the surface
    never locks -> 4 tests (lock, configure, walk, log keys); the walk without `profile.scale` -> `walkForward...AlongFacing`;
    camera-right asked on every sync -> 3 tests; the finger not released -> `aMemberGoneFromTheSlot...`. 10 of 10 caught.
  - No compiler warning in any T53 file (non-incremental forced recompiles of `:core` JVM and iOS main and test,
    `:androidApp` main; a deliberate probe line showed the `:shared` test compiles do surface warnings, then was removed).
  - host diff (`git diff 33b6a13..a98a74f --stat -- androidApp/src/main`): `RudeusArView.kt` 17 insertions, 78
    deletions (936 -> 875 lines); no `ar/TableEngine*.kt` left. Per commit: P 1/1 (line 53), A 14/78 (the nine edits),
    C 4/1 (two imports, the note re-wrapped to take the words). No SceneView or ARCore call added, removed or made
    differently: `cameraRight(a, frame)` is still asked only when a member joins, now through a lambda.
  - `grep -rn "HAND-REVIEW" core/src shared/src`: empty. Files changed (`git diff --stat 33b6a13..HEAD`): only those
    under "Owns".
- step-3 diff, run right after committing A (the old file saved with `git show d6bc58e:...` to a scratch dir; it differs
  from 23a9ea2 only at line 53, so every brief line number holds; engine bodies at `TableEngine.kt` 81-86, 91-92, 148,
  113-144, 156-168, 173-180, 97, 102):
  ```
  ### offerSurface: old 331,336 vs body 81,86
  (no difference)
  ### configure: old 342,343 vs body 91,92
  (no difference)
  ### activeId: old 414,414 vs body 148,148
  (no difference)
  ### command: old 422,450 vs body 113,144
  0a1,3
  >         val serial = from.command?.serial
  >         if (serial != null && serial == lastOffered) return
  >         lastOffered = serial
  ### syncCast: old 575,587 vs body 156,168
  (no difference)
  ### anchorPlaced: old 599 + 603-605 vs body 173-180 (by eye)
  0a1,4
  >         if (anchored) {
  >             log("anchor_refused")
  >             return
  >         }
  2c6
  <         val s = currentSlot
  ---
  >         val s = current()
  4c8
  <         syncCast(created, frame) // T32: every waiting member is placed now, each with onCastEvent(id, PLACED)
  ---
  >         syncCast(cameraRight) // T32: every waiting member is placed now, each with onCastEvent(id, PLACED)
  ### activeOffered: old 415 vs body 97 (by eye)
  1c1
  <     LaunchedEffect(id) { id?.let { log("active id=$it") } }
  ---
  >         id?.let { log("active id=$it") }
  ### abilities: old 417 vs body 102 (by eye)
  1c1
  <     LaunchedEffect(members) { for (c in members) loop.setAbility(c.id, c.ability) }
  ---
  >         for (c in members) loop.setAbility(c.id, c.ability)
  ```
  The effect keys in `ar/TableEngineHost.kt`: `(game.surface, slot.characterSize)`, `(slot.activeId)`, `(members)`,
  `(slot.command?.serial)`, the expressions of lines 341 (`surface` there was `slotState.surface`, the engine's now),
  415, 417, 421.
- step-4 diff at B (comment lines stripped by the brief's `grep -vE`):
  ```
  ### androidApp/.../ar/TableEngine.kt vs core/.../core/session/TableEngine.kt
  1c1
  < package kz.rudy.ar.ar
  ---
  > package kz.rudy.ar.core.session
  10,12d9
  < import kz.rudy.ar.core.session.CharacterAction
  < import kz.rudy.ar.core.session.CharacterCommand
  < import kz.rudy.ar.core.session.GameLoop
  15c12
  < internal class Member(val id: String, val ability: Ability, val emotes: EmoteSet)
  ---
  > class Member(val id: String, val ability: Ability, val emotes: EmoteSet)
  17c14
  < internal class TableSlot(
  ---
  > class TableSlot(
  26c23
  < internal class TableEngine(
  ---
  > class TableEngine(
  ### androidApp/.../ar/TableEngineHost.kt vs shared/.../kz/rudy/ar/TableEngineHost.kt
  1c1
  < package kz.rudy.ar.ar
  ---
  > package kz.rudy.ar
  8,9d7
  < import kz.rudy.ar.ArCharacter
  < import kz.rudy.ar.ArSlot
  10a9,11
  > import kz.rudy.ar.core.session.Member
  > import kz.rudy.ar.core.session.TableEngine
  > import kz.rudy.ar.core.session.TableSlot
  13c14
  < internal fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot = TableSlot(
  ---
  > fun ArSlot.toTableSlot(members: List<ArCharacter>): TableSlot = TableSlot(
  23c24
  < internal fun rememberTableEngine(
  ---
  > fun rememberTableEngine(
  ### tests (plain diff)
  1c1
  < package kz.rudy.ar.ar
  ---
  > package kz.rudy.ar.core.session
  116c116
  < class TableEnginePinTest {
  ---
  > class TableEngineTest {
  ```
- found by the characterization tests: **all 27 were green against the host-local engine on the first run**; no moved
  body was changed. What they pin beyond the brief's wording (behaviour as it was, no fix proposed):
  1. T47's inference is now pinned: a WalkForward under a tracking loss sets the target and keeps PAUSED; the first
     frame back reports RESUMED only (no STARTED_WALKING), the member walks from there and ARRIVED comes as usual. A
     Jump under the loss logs `jump_ignored id=.. mode=PAUSED ..` and nothing jumps after the resume.
  2. An emote for a member that is on the table but missing from the slot that sent the command is dropped with **no
     log line at all**: besides the new serial guard, the only ignored-command path without a line (the on-table check
     reads the loop, the member lookup reads the sender). It needs a member removed between the press and the effect; not seen on a device.
  3. With sounds on, a silent choreography (BYE, SPIN) hands `EmoteSound.NONE` to `playEmoteSound`; the Android
     `Sounds.play(EmoteSound)` returns early on it (`audio/Sounds.kt:69`). An iOS host must do the same.
  4. Camera-right is asked for the first member too (the argument is evaluated though `spawnFor` ignores it for an
     empty cast): two members placed at the anchor = two asks. Removals and quiet syncs ask nothing.
  5. `activeOffered` logs any id it is given, on the table or not (`active id=zz`); null logs nothing.
  6. At add time a member's ability is the latest slot's; `abilities` before the anchor is a no-op.
  7. New guards, as specified: the serial guard keys on the serial alone (another id or action under the same serial is
     dropped too), and a serial ignored before the anchor is not re-applied after it (Compose would not re-run that
     effect either). Both unreachable on Android: serials only rise and `command` is reset to null only with the session.
  Found by reading, not pinned (it is what the code does; S3b decides): `syncCast` has no anchored check. The Android
  host calls it only from the anchor and from anchored frames; an engine caller that syncs before `anchorPlaced` would
  place members. S3b moves the frame (and this call) into the engine, where the check is natural.
- deviations from the letter of the brief (none changes behaviour):
  1. `anchorPlaced`'s by-eye diff has three parts, not two: the 4-line refusal guard, `val s = current()` for
     `val s = currentSlot` (the 16-line sed has no rule for a bare `currentSlot`), and `syncCast(cameraRight)`.
  2. Edit 3 also rewrites one line of `SlotState`'s KDoc ("`anchored` freezes it" -> "the engine's, locked at the
     anchor"), since `anchored` left the class. Edit 6 keeps the T25 note and changes its second line to point at
     `rememberTableEngine`. The KDoc and comments of the removed closures (old 413, 416, 418-420) moved into the
     engine as KDoc; edit 7 replaced 413-451 with the one line, as written.
  3. `RudeusArView.kt` is 17 in / 78 out (the brief estimated about 30 / 75); commit C is 4 / 1, not 3 lines, because
     the note's new words wrap onto a second line (as T52's did).
  4. `TableSlotTest`'s one test also anchors a `TableEngine` fed by the converted slot (the anchor, then PLACED per
     member in the given order) besides checking the fields, callbacks and defaults.
  5. `rememberTableEngine` holds the three lambdas in `rememberUpdatedState`, as specified, so the engine calls the
     latest `sounds`; the old command effect used the `sounds` of its launch. `sounds` is MainActivity's one instance
     and `touch` is remembered, so nothing observable changes.
  6. `configure` reads `profile` when the effect runs, the old effect the composition's `profile` at launch: the same
     value, because the effect is keyed on the surface and a surface change cancels an effect that has not run yet.
- Pixel check ("place, scan, two friends, lens, replay"): UNTESTED (no device in the cloud container). **Needs the
  user's Pixel run**, the six steps under "Done when": tap to place (once, with its sound); Scan (found, cannot walk
  through the cup); add a second character (beside the first, toward screen-right); Jump (the active one, once), pick
  the other chip and Hello (only that one, one sound); cover the lens, Jump while covered, uncover after 3 s (both
  reappear where they stood, nobody jumps); Scenario (the first one plays to the end). Log: one `cast_add` per
  character with `n=1` then `n=2`, one `jump id=`, one `emote id=`, one `jump_ignored .. mode=PAUSED`, `scenario_done
  ms=` just over 15000, no `anchor_refused`. If anything is off, revert C first, then A, as "Done when" says.
- cut/deferred: nothing cut. `engine.loop` stays exposed (the host's frame, taps and scans read it until S3b, T54, S5);
  `SlotState.surface` / `profile` now delegate to the engine and go when S3b moves the frame.
