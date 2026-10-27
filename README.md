# Rudeus — your drawing is the character, your table is the level

An Android AR game for kids in Kazakh and Russian. Photograph a child's drawing (or a toy): it becomes the game
character. Press **Scan**: the cup on the table becomes an obstacle, the book a platform, the table edge a cliff.
Then the character walks, jumps and flies around the real objects.

> Status: prerequisites and the testable core are in place; AR host, capture and scan are being built day by day
> against the plan in [`ai/build-plan.md`](ai/build-plan.md). All work in this repository is new since Oct 26, 2026.

## How it works

| Stage | Model | Role |
|---|---|---|
| Cutout | SAM 3.1 | cut the drawing/toy out of the photo |
| Stylize | Muse Image edit | restyle the cutout with ≤ 9 reference images |
| Gate | Spark 1.3 vision + deterministic palette/outline check | score ≥ 0.7 or retry (≤ 2) |
| Sheet | Spark 1.3 | name, personality, ability (jump / fly / roll / hide), 5 lines in kk and ru |
| Table scan | SAM 3.1 | one mask per prompt → footprints on the table plane via ray–plane |
| Voice (optional) | Voice Transcribe | push-to-talk commands in Kazakh |

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
the CLI reads the `META_API_KEY` environment variable. A pre-commit hook and CI refuse any commit that contains a
key-shaped string. Without a key the app and CLI run offline against the redacted fixtures in `docs/api-samples/`.

## Metrics
Every number reported here comes from a file in `runs/` produced by `MetricsLog` (never hand-edited).
Table to be generated on Day 7 with `metrics summarise`.

## Platform statement
Android only, by choice: ARCore is the AR runtime. The Kotlin Multiplatform split (`:core` on android + jvm) is what
lets the same pipeline run on the Mac for development and metrics. iOS is out of scope; the untouched template
files are parked in `attic/`.

## Licenses
MIT (see `LICENSE`). Third-party: SceneView (Apache-2.0), ARCore SDK (Apache-2.0), Ktor (Apache-2.0),
kotlinx libraries (Apache-2.0), Compose Multiplatform (Apache-2.0). Sounds: CC0 (list in `docs/third-party.md` on Day 6).
Drawings and toys in the demo are the author's own.
Exception: the SAM mask codec port (`core/.../meta/SamMaskCodec.kt`) and its test vectors (`SamMaskCodecTest.kt`) are derived from Meta's `@meta-sam/parser` and licensed under the SAM License, not MIT: see [`LICENSES/SAM-LICENSE.txt`](LICENSES/SAM-LICENSE.txt).
