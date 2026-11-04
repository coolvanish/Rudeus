# T33 — pose-frames

status: review
created: 2026-11-04
day: post-deadline (follow-up after v1.0; the T30 pose spike's GO, see docs/api-samples/muse-pose/README.md)
gate: a bundled character with frames waves with its drawn arm (HELLO) and roars with its open mouth (ROAR); a character without frames plays the v1.0 squash-stretch choreographies unchanged; the run JSON shows the `frames` stage and its cost
cut-if-slips: `POSE_FRAMES = false` in AndroidCaptureHost (no stage, no Muse cost) and drop `frames/` from the bundle; the choreographies keep playing

## Goal
When a character has model-drawn pose frames, an emote shows them on the quad at the keys the choreography marks and
goes back to the base sprite at the end; the squash-stretch, tilt, hop and yaw of the choreography stay on top. Frames
come from Muse Image edit (the T30 prompts), two for HELLO and two for ROAR, for a photo character (pipeline stage
`frames`) and for the bundled ones (`cli choreo --frames`). Without frames nothing changes.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/emote/PoseFrames.kt (new), Emote.kt, EmotePlayer.kt, Choreographies.kt, ChoreographyParser.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaModels.kt, MetaApi.kt, MetaDtos.kt (Prompts.pose), FixtureApi.kt, FakeModels.kt, RecordingModels.kt, OfflineGuard.kt, FixtureConverter.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/CharacterPipeline.kt, core/src/jvmMain/.../JvmPipeline.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/bundle/Bundle.kt, SavedCharacters.kt; core/src/{commonMain,jvmMain,androidMain}/.../io/*CharacterFolder.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/MetricsLog.kt (CostTable.poseUsd)
- core/src/commonTest/** (PoseFramesTest, PoseFrameKeysTest, ChoreographyFrameJsonTest, PoseFramesStageTest, SavedFramesTest, BundleFramesTest, PoseFixtureTest)
- cli/src/main/kotlin/kz/rudy/ar/cli/ChoreoCommand.kt, Main.kt, Record.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/ArSlot.kt, App.kt, ui/components/CastUi.kt, ui/screens/CaptureScreen.kt (BundledCharacter.frames)
- androidApp (hand-review): ar/CharacterNode.kt, ar/GameLoop.kt, ar/RudeusArView.kt, di/AndroidCaptureHost.kt
- docs/api-samples/muse-pose/{toy_dino.fixture.json,meta.json}, docs/bundled/toy_dino/{frames/,choreo.json}, docs/bundled/index.json, the assets mirror

## Contract
- `EmoteKey.frame: Int?` — the drawn frame (index into `PoseFrames` of that emote) shown from this key until the next
  key; null = base sprite. `ChoreographyRules.sanitize` keeps 0..`MAX_FRAME` (1), clears the rest, never on the key at 0
  it inserts or on the last key. `EmotePlayer.frameAt(c, tMs)`: the latest key's frame at or before tMs (hold), null
  outside [0, duration). The pose sample is untouched by frames.
- `PoseFrames` — per-emote PNG lists, bounded `MAX_PER_EMOTE = 2`, `MAX_PER_CHARACTER = 4`, `MAX_SIDE = 768`; files
  `<emote>-<n>.png` (1-based); `fromFiles` keeps an emote's frames from index 0 while contiguous. `PoseSpec.DRAWN` =
  hello-1, hello-2, roar-1, roar-2.
- `Choreographies.withFrames(c, n)` / `EmoteSet.withFrames(counts)`: the interior keys point at frames 0..n-1 in time
  order over [first interior key, last key); the first and last key never carry one; a two-key choreography gets a
  REST key at durationMs/2 for frame 0; n = 0 strips. Idempotent, sanitize-stable.
- `MetaModels.drawPose(ref, sprite, refs, spec)` (job `muse-pose`): one Muse edit with `Prompts.pose(spec)` (the T30
  prompts + the white-background suffix). MetaApi meters it under `Jobs.MUSE_POSE`; FixtureApi picks the output by
  frame name from `muse-pose/<ref>.fixture.json`; RecordingModels writes `muse-pose/<ref>.<emote>-<n>.png`.
- `CharacterPipeline(poseFrames = true, frameConcurrency = 2)`: after the stylize loop, the four edits from the matted
  sprite (<= 1024 px) with the Muse refs, each matted by `spriteMatter` (no SAM fallback) and bounded to 768; stage
  `frames` after `choreo` (ms = batch wall time, cost metered else `poseUsd` x frames returned, ok = 4/4);
  `CharacterResult.frames`, `emotes` keyed with `withFrames`. Failures never fail the character.
- Bundle: `BundleEntry.frames: Map<Emote, List<String>>?` (`<ref>/frames/<emote>-<n>.png`); `CharacterFolder.write(..,
  frames)` / `readFrames`; `SavedCharacter.frames`, `SavedCharacters.save(.., emotes, frames)`.
- CLI: `character --frames`; `choreo --frames` (after the Spark call) / `--frames-only` (no Spark call, keys the existing
  choreo.json), `--refs <dir>`; budget worst case + $0.04/ref; `--record` writes muse-pose fixtures (preflight refuses
  synthetic ones, real ones need `--force`).
- Shared: `BundledCharacter.frames`, `ArCharacter.frames` (defaults EMPTY), through `CastUi.member` and `App.useResult`.
- Host (hand-review): `CharacterSprite.frames` (one Texture + image MaterialInstance per frame), `CharacterPoser.apply(..,
  emote, emoteKind, frame)` multiplies scale, adds yaw, tilts about the quad's Z (quaternions, kotlin-math degrees),
  lifts by hop x height x size, swaps the material with `setMaterialInstanceAt(0, ..)` and shows a frame at the quad's
  height with its own aspect; `GameLoop.playEmote` / `emoteTimeMs` on the simulated clock (PAUSED freezes); the emote
  row's `slot.command` (Emote only) drives it; bundled and saved characters load choreo.json + frames;
  `DeferredModels` forwards `choreograph` and `drawPose`. `POSE_FRAMES = true` is the switch.

## Tests required
- PoseFramesTest: file names, bounds, contiguity, content equality
- PoseFrameKeysTest: sanitize keeps/clears frames, frameAt hold semantics, withFrames order / idempotence / bare choreography, EmoteSet.withFrames
- ChoreographyFrameJsonTest: `frame` round-trips and is written only when set; odd values ignored
- PoseFramesStageTest: 4 frames + keyed emotes + stage/cost; a missing frame; total failure costs nothing; off = no stage; two in flight
- SavedFramesTest, BundleFramesTest: round trips; old data without frames decodes
- PoseFixtureTest: fixture by name whatever the order; the committed toy_dino spike replays; prompts cover DRAWN only
Tests not required: Compose, AR rendering, the CLI parser.

## Done when
- `./gradlew :core:jvmTest` green; `scripts/verify.sh` exit 0 (needs the Android SDK; not available in the cloud session that wrote this)
- `docs/bundled/toy_dino/frames/` (4 PNGs, 768 px) + keyed choreo.json + index `frames`, mirrored into androidApp assets
- On the Pixel: toy_dino waves with the drawn arm and roars with the open mouth; another bundled character plays as before; a photo character's run JSON has a `frames` stage

## Result (filled on acceptance)
- measured (cloud session 2026-11-04, no Android SDK: core tests via a scratch Maven build, CLI compiled and run the same way):
  core 332 tests green (310 before + 22 new); `cli choreo --bundle docs/bundled --ref toy_dino --frames-only` offline from the
  spike fixture: frames=hello:2,roar:2, 1395 ms, $0.0400 (estimate; fixtures meter nothing), run `runs/2026-11-04T12-58-37Z.json`
  with one `frames` stage (ok, 0.04). Frame keys: hello 350-900 ms -> hello-1, 900-1200 -> hello-2; roar 250-750 -> roar-1, 750-1100 -> roar-2.
- APIs relied on (SceneView 4.31.0 sources from Maven Central): `RenderableComponent.setMaterialInstanceAt` (RenderableComponent.kt:266),
  `Node.quaternion` (Node.kt:370; `Node.rotation` is `Quaternion.fromEuler` of the Euler triple, Node.kt:414), `MaterialLoader.createImageInstance`
  (MaterialLoader.kt:377), the composable `PlaneNode` (SceneScope.kt:906). `Quaternion.fromAxisAngle` / `*` are kotlin-math (degrees).
- not done: `:androidApp:assembleDebug` and `:shared:testAndroidHostTest` (no SDK here; the host diff is uncompiled, hand-review);
  the rest of T32 (cast as several quads, finger grab, emote sounds); live frames for the other 15 bundled characters
  (`cli choreo --bundle docs/bundled --all --frames-only --live --record docs/api-samples --budget-usd 0.80`, ~$0.64, then the mirror).
- cut/deferred: a background frames pass after entering Play (the plan's A3 shape) instead of +~26 s on the processing screen is a one-line
  move if the Pixel run finds the wait too long.
