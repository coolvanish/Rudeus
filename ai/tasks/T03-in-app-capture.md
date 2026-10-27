# T03 — in-app-capture

status: todo
created: 2026-10-28
day: Day 3
gate: capture -> character < 60 s
cut-if-slips: cut line 6 (fall back to CLI-generated bundled characters)

## Goal
Take a photo of the drawing/toy inside the app and run `CharacterPipeline` on the phone with `MetaApi`.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/capture/** (frame grab, YUV -> JPEG, Bitmap-backed RasterImage)
- androidApp/src/main/kotlin/kz/rudy/ar/di/** (MetaApi + pipeline wiring from BuildConfig.META_API_KEY)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/screens/CaptureScreen.kt, ProcessingScreen.kt (state wiring only)

## Contract
- `frame.acquireCameraImage()` at 1080p -> JPEG bytes; close the image in `finally`; never per-frame.
- `AndroidRaster(bitmap)` implements `kz.rudy.ar.core.io.RasterImage` for the deterministic gate half.
- Pipeline runs on `Dispatchers.IO`; progress reported per stage to ProcessingScreen; gate score shown honestly.
- Metrics written through `MetricsLog` to app files `files/runs/<runId>.json` (`scripts/device.sh pull-runs`).
- Offline (no network or HTTP error): fall back to `FixtureApi` over bundled assets and show the offline badge.

## Tests required
- `pipelineRunsAgainstBundledAssetsFixtureSource` (core, MapFixtureSource mirroring the bundled set).
Tests not required: camera code, screens.

## Done when
- `scripts/verify.sh` exit 0
- Fresh install -> photo -> character on the table in < 60 s, run id in runs/.

## Result
