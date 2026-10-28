# T03 — in-app-capture

status: review
created: 2026-10-28 (contract revised the same day, after T07/T11 and T02)
day: Day 3
gate: capture -> character < 60 s
cut-if-slips: cut line 6 (fall back to CLI-generated bundled characters); inside the brief cut (f) then (e)

## Goal
The child photographs a drawing or a toy in the app (or picks a photo already in the gallery). `CharacterPipeline`
runs on the phone with `MetaApi`, the Processing screen shows the real stages, and the new character's sprite is the
one that stands on the table in Play (T02 billboard).

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/capture/** (new): photo intake, Bitmap-backed `RasterImage`, Android image helpers
- androidApp/src/main/kotlin/kz/rudy/ar/di/** (new): MetaApi + pipeline wiring from `BuildConfig.META_API_KEY`
- androidApp/src/main/kotlin/kz/rudy/ar/MainActivity.kt, ar/RudeusArView.kt (sprite bytes in), AndroidManifest.xml +
  res/xml/ (FileProvider only), src/main/assets/style-refs/ (copy of docs/style-refs/*.png + cull.json)
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt, ui/screens/CaptureScreen.kt, ProcessingScreen.kt (state wiring only)
Not yours: `:core` pipeline/meta (frozen, tested); `cli/**`.

## Contract
**(a) Photo in — system camera and photo picker, not the AR frame.** Changed from "acquireCameraImage at 1080p": the
ARCore CPU image is 640x480 unless the camera config changes (which can hurt tracking), and the capture screen has no AR
session. Use `ActivityResultContracts.TakePicture` with a FileProvider URI in the app cache (manifest `<provider>` +
`res/xml/file_paths.xml`, `androidx.core.content.FileProvider` is already on the classpath) and
`ActivityResultContracts.PickVisualMedia` (photo picker, no permission). No new dependency, no Gradle edit.

**(b) Android image helpers** (androidApp/capture, the JVM `JvmRaster` counterparts the pipeline needs):
- decode with downsampling to ≤ 2048 px long side (`inSampleSize` + scale), then EXIF upright
  (`android.media.ExifInterface` on a stream; phone photos rely on it);
- `AndroidRaster(bitmap): RasterImage` for the deterministic gate half;
- `cutoutComposer(photo, mask)`: alpha from the SAM `MaskResult` (rle or bbox, same semantics as `JvmRaster.cutout`),
  transparent pixels transparent WHITE (0x00FFFFFF), PNG out;
- `spriteMatter(png)`: decode → `SpriteMatte` (core commonMain) → PNG, null on degenerate (same as `JvmRaster.matte`);
- `imageResizer(bytes, maxSide)`: keep aspect, never upscale, PNG keeps alpha.
Mirror `core/src/jvmMain/.../io/JvmRaster.kt` behaviour; read it first.

**(c) Pipeline on the phone.** Build `CharacterPipeline` like `JvmPipeline.create` does (matte, downscale,
rasterDecoder, cutoutComposer) with `MetaApi(MetaConfig(... BuildConfig.META_API_KEY ...), costMeter = metrics)`.
Style refs from assets in cull.json `kept` order (Spark sees the first 3). Run on `Dispatchers.Default`/`IO`; map
`PipelineEvent` to `ProcessingState` (stage, completed, attempt, gate score shown honestly, GATE_FAILED when it shipped
unpassed, ERROR on failure). `:shared` gets the pipeline as an injected suspend function from MainActivity (no network
code in `:shared`), e.g. `App(arView, makeCharacter: (suspend (photo: ByteArray, onEvent: (PipelineEvent) -> Unit) -> CharacterResult)?)`.
A missing key (blank `BuildConfig.META_API_KEY`) shows the ERROR state, never crashes.

**(d) The new character on the table.** Play shows the captured character's matted sprite and sheet (name, lines):
pass the sprite bytes to `RudeusArView` (T02's `spriteFromBytes`), keep the bundled dino as the default. Picking a
bundled character keeps working.

**(e) Metrics.** Each phone run writes `files/runs/<runId>.json` through `MetricsLog` + `FileMetricsSink`
(`source = "app"`, `device = "pixel"`), so `scripts/device.sh pull-runs` can collect them.

**(f) Offline.** No network / HTTP error / no key: show the error state with "pick a ready character" (existing
Processing actions). The `FixtureApi`-over-bundled-assets fallback moves to T06 (offline mode).

## Tests required
- `pipelineRunsAgainstBundledAssetsFixtureSource` is moved to T06 with (f).
- Pure logic you add outside androidApp (e.g. a `PipelineEvent` → `ProcessingState` mapper in `:shared`) gets a unit test.
Tests not required: camera, picker, Bitmap glue, screens.

## Done when
- `scripts/verify.sh` exit 0
- On the Pixel: photo (or gallery pick) → Processing shows real stages → Play shows that character on the table, and a
  run JSON lands in `files/runs/`. Time from shutter to Play noted (gate < 60 s; live latency today is 40–60 s for a
  first-try toy, so the gate is at risk and that is reported, not hidden).

## Result
Built 2026-10-28 (fable, worktree of day3/table-gate). `scripts/verify.sh` exit 0 (core 132, shared 7 incl. the new
`ProcessingEventsTest` x5, APK 51 MB with the 3.2 MB style refs). Not yet run on the Pixel: the numbers below are to be
filled in by the human.

**What was built**
- `:shared` `CaptureHost` (kz.rudy.ar): `takePhoto`, `pickPhoto`, `suspend makeCharacter(photo, onEvent)`, `playEntered()`.
  `App(arView: (Modifier, spritePng: ByteArray?) -> Unit, capture: CaptureHost? = null)`: the slot now carries the
  sprite Play shows (null = bundled dino); a null host keeps the fake stage loop. Capture gets a "from gallery" ghost
  button when the host is present; "pick a bundled character" resets the sprite to the dino.
- `ui/screens/ProcessingEvents.kt`: pure `ProcessingState.reduce(PipelineEvent)` (matte -> painting row, rescore ->
  checking row, sheet start never steals the active row, retry reopens painting+checking, gate row settles on
  GateScored when passed or last attempt, Finished -> DONE or GATE_FAILED, Failed -> ERROR with the cross on the stage).
  The ERROR sheet's second button now reads "pick a ready one" and goes back to Capture (brief (f)).
- `androidApp/capture/AndroidImages.kt`: `AndroidRaster(bitmap)`, `decode`, `cutout`, `matte`, `downscale`, `upright`,
  `exifOrientation` (android.media.ExifInterface on a stream), `loadPhoto`/`preparePhoto` (inSampleSize + scale to
  <= 2048 px, EXIF upright, JPEG q92). PNGs are written through a non-premultiplied Bitmap so alpha-0 stays WHITE.
- `androidApp/capture/PhotoIntake.kt`: `rememberPhotoIntake()` = TakePicture (FileProvider cache URI, file deleted
  after reading) + PickVisualMedia (ImageOnly). Decode on IO, callback on main. Manifest `<provider>` + `res/xml/file_paths.xml`.
- `androidApp/di/AndroidCaptureHost.kt`: MetaApi from `BuildConfig.META_API_KEY` (blank -> `check` throws -> ERROR
  state), one `MetricsLog(source="app", provider="meta", device="pixel")` per app session, JSON rewritten to
  `files/runs/<runId>.json` after every run (also on failure/cancel), style refs from `assets/style-refs` in cull.json order.
  Logs to tag `RudeusGate`: `photo_ready_ms`, `stage_start/stage_end`, `gate_scored`, `pipeline_ms`, `capture_to_play_ms`.
- `RudeusArView(spritePng)`: `spriteFromBytes` when set, dino otherwise; `GENERATED_SPRITE_FACES_RIGHT = false`.

**Pixel test steps** (key in local.properties, rebuild, Wi-Fi on)
1. `./gradlew :androidApp:installDebug && scripts/device.sh run`; in another terminal `scripts/device.sh log | grep RudeusGate`.
2. Capture -> "Фото" -> system camera -> shoot a toy on the table -> confirm. Expect `photo_ready_ms=` (< 1500) and
   `photo_bytes=` (200-900 KB).
3. Processing: rows tick in order cutout -> painting -> checking (score shown) -> naming; `stage_start=cutout` ...
   `pipeline_ms=`. Note gate score and attempts. Tap "Әрі қарай".
4. Scanning: tap the table (after the settle hint) -> Skip -> Play shows the NEW character, not the dino; name/lines in the
   HUD are the sheet's. Read `capture_to_play_ms=` -> record here: ______ ms (gate < 60 000; live latency today 40-60 s
   for a first-try toy, so the gate is at risk).
5. `scripts/device.sh pull-runs` -> `runs/<runId>.json` has `source: "app"`, one character with stages and costs.
6. Repeat with "Галереядан" (photo picker) on a drawing from the gallery; the picker needs no permission prompt.
7. Back -> pick "Мұрат" (bundled) -> Scanning -> Play shows the dino again.
8. Wi-Fi off -> "Фото" -> ERROR sheet with "Тағы бір рет" / "Дайынын таңда"; the latter returns to Capture.

**Known risks / to check on the Pixel**
- Transparent WHITE through `Bitmap.setPremultiplied(false)` + `compress(PNG)`: believed to write raw RGB for alpha-0
  pixels; if Muse output comes back on black, SpriteMatte fails and the SAM fallback kicks in (visible as
  `stage_end=matte ok=false`). Verify once by inspecting a cutout in the run.
- Facing: generated sprites are assumed to face left (dino convention). A right-facing sprite walks backwards.
- One Filament texture per character per AR screen is created and not destroyed (same lifetime policy as T02).
- The AR slot is recreated between Scanning and Play (T02 known issue); the sprite is decoded twice, nothing else.
- Process death while in the camera app loses the pending callback (no photo; the child retries).
- Memory: a 12 MP photo decodes at full size (48 MB) before scaling to 2048 px; fine on the Pixel, watch on low-RAM devices.
- Review (rudeus-reviewer, draft 1): merge after fixes → done by the orchestrator: the camera permission is asked before
  `TakePicture` (fresh install crashed with SecurityException; no camera app → null, not a crash); phone refs are "01",
  "02"… (a failed sheet fell back to the name "App"); `capture_to_play_ms` logged once per capture; transparent gallery
  PNGs flattened on white before JPEG; more `configChanges` so dark mode / locale / font scale don't recreate the
  activity mid-run. Open: a cancelled run's paid stages are not in the run JSON (no `endCharacter`); HEIC orientation
  ignored; a 12 MP photo is decoded at full size before scaling (fine on the Pixel's memory).

