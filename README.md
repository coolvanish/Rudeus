# Rudeus — your drawing is the character, your table is the level

An Android AR game for kids in Kazakh and Russian. Photograph a child's drawing (or a toy): it becomes the game
character. Press **Scan**: the cup on the table becomes an obstacle, the book a platform, the table edge a cliff.
Then the character walks, jumps and flies around the real objects.

> Status: Android prototype with capture, character generation, table scan and AR play implemented.
> Voice controls are currently disabled in the Android app. Remaining work is tracked in [`ai/build-plan.md`](ai/build-plan.md).

## How it works

| Stage | Model | Role |
|---|---|---|
| Cutout | SAM 3.1 | cut the drawing/toy out of the photo |
| Stylize | Muse Image edit | restyle the cutout with ≤ 9 reference images |
| Gate | Spark 1.3 vision + deterministic palette/outline check | score ≥ 0.7 or retry (≤ 2) |
| Sheet | Spark 1.3 | name, personality, ability (jump / fly / roll / hide), 5 lines in kk and ru |
| Table scan | SAM 3.1 | one mask per prompt → footprints on the table plane via ray–plane |
| Voice (disabled in Android) | Voice Transcribe | implementation retained; mic hidden pending device fixes |

The whole pipeline is pure Kotlin in `:core` and runs identically on the phone and from the Mac (`:cli`).
Geometry needs no depth sensor: the scan saves the camera pose and intrinsics, and footprints come from
intersecting silhouette rays with the detected plane. See [`docs/architecture.md`](docs/architecture.md).

## Modules

| Module | What |
|---|---|
| `:core` | domain, physics, geometry, model API clients, pipeline, metrics. Tested on the JVM. |
| `:shared` | Compose UI: Capture, Processing, Scanning, Play HUD, Metrics. Design in [`DESIGN.md`](DESIGN.md). |
| `:androidApp` | AR host (ARCore via SceneView), frame grab, pose conversion, API key wiring. |
| `:cli` | runs the pipeline against saved fixtures or the live API; writes `runs/*.json`. |

## Run

```
./gradlew :core:jvmTest                                   # domain tests
./gradlew :cli:run --args="character --fixtures docs/api-samples --out runs --all"   # offline pipeline
./gradlew :cli:run --args="metrics summarise runs"       # metrics table
./gradlew :androidApp:installDebug && scripts/device.sh run    # on an ARCore phone
scripts/verify.sh                                         # everything (definition of done)
```

Requirements: JDK 21 (Gradle provisions it), Android SDK with platform 37, an ARCore-capable phone for the AR screens.

## API key handling
Put `META_API_KEY=...` in `local.properties` (gitignored). Gradle injects it into `BuildConfig.META_API_KEY`;
the CLI reads the `META_API_KEY` environment variable. The pre-commit hook and manually triggered CI scan for
key-shaped assignments; CI does not run automatically on push or pull requests.

## Offline behavior
The CLI's fixture mode (`character --fixtures docs/api-samples ...`, without `--live`) uses redacted API responses
and needs no key. The Android app does not switch to fixtures: without a key, generation and scanning fail;
ready-made characters remain available from bundled assets.

On a network failure, Android shows an offline badge and keeps the existing scanned level for the current AR
session. Photo and Scan remain enabled for retry; a successful model call clears the badge. No automatic fixture
fallback or network retry is performed. Voice controls are currently hidden (`AndroidCaptureHost.voice = null`).

## Metrics
Every number reported here comes from a file in `runs/` produced by `MetricsLog` (never hand-edited).
Generated with `metrics summarise` over the runs listed below (the five empty Oct 30 runs with n = 0 are left out).
`fixture` rows replay saved API responses; `meta` rows are live calls; `app` is a run from the Pixel.

| run | source | provider | device | n | gate pass 1st try | p50 ms | p90 ms | $/character |
|---|---|---|---|---:|---:|---:|---:|---:|
| 2026-10-27T20-00-31Z | cli | fixture | mac | 2 | 50.0% | 24 | 186 | $0.0500 |
| 2026-10-27T22-58-25Z | cli | fixture | mac | 1 | 0.0% | 71582 | 71582 | $0.0720 |
| 2026-10-27T23-51-04Z | cli | meta | mac | 3 | 0.0% | 78625 | 143148 | $0.0455 |
| 2026-10-28T00-01-24Z | cli | meta | mac | 3 | 66.7% | 33015 | 56134 | $0.0294 |
| 2026-10-28T00-07-29Z | cli | meta | mac | 3 | 100.0% | 28953 | 30661 | $0.0257 |
| 2026-10-28T07-46-08Z | cli | meta | mac | 6 | 33.3% | 106959 | 211041 | $0.0460 |
| 2026-10-28T09-11-21Z | cli | meta | mac | 15 | 40.0% | 116586 | 216926 | $0.0515 |
| 2026-10-28T12-31-11Z | app | meta | pixel | 2 | 100.0% | 51202 | 63575 | $0.0279 |

Day 2 targets were ≥ 7/10 passing the gate first try, p50 < 40 s and ≤ $0.05 per character. The largest live
run (15 drawings, 2026-10-28T09-11-21Z) missed the pass-rate and latency targets; the cost target held.

## Platform statement
Android only, by choice: ARCore is the AR runtime. The Kotlin Multiplatform split (`:core` on android + jvm) is what
lets the same pipeline run on the Mac for development and metrics. iOS is out of scope; the untouched template
files are parked in `attic/`.

## Licenses
MIT (see `LICENSE`). Third-party: SceneView (Apache-2.0), ARCore SDK (Apache-2.0), Ktor (Apache-2.0),
kotlinx libraries (Apache-2.0), Compose Multiplatform (Apache-2.0). Sounds: CC0 (list in `docs/third-party.md` on Day 6).
Drawings and toys in the demo are the author's own.
Exception: the SAM mask codec port (`core/.../meta/SamMaskCodec.kt`) and its test vectors (`SamMaskCodecTest.kt`) are derived from Meta's `@meta-sam/parser` and licensed under the SAM License, not MIT: see [`LICENSES/SAM-LICENSE.txt`](LICENSES/SAM-LICENSE.txt).
