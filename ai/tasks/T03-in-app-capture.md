# T03 — in-app-capture

status: doing
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
