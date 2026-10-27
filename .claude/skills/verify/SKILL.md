---
name: verify
description: How to build and test Rudeus (Gradle KMP, Android, CLI) and what "passing" means. Use before declaring any change done, when a build fails, or when deciding which tests to run.
---

# Verify

The definition of done is `scripts/verify.sh` exit 0. It runs, in order:

| Step | Command | Typical warm time |
|---|---|---|
| core tests (JVM) | `./gradlew :core:jvmTest` | 15–30 s |
| shared host test | `./gradlew :shared:testAndroidHostTest` | 20–40 s |
| cli compiles | `./gradlew :cli:compileKotlin` | 5 s |
| Android debug build | `./gradlew :androidApp:assembleDebug` | 40–120 s |

First run downloads dependencies (several minutes). Run long builds in the background and keep working.

## Which subset to run while iterating
- Touched only `core/`: `./gradlew :core:jvmTest`, then `scripts/verify.sh --mark` only if you ran the full script earlier in the session and nothing outside core changed. Otherwise the full script.
- Touched `shared/` UI: `./gradlew :shared:compileAndroidMain` catches most issues in 20 s.
- Touched `androidApp/`: `./gradlew :androidApp:assembleDebug` is mandatory; Compose preview is not a build.

## Reading failures
- `Unresolved reference` in a `.gradle.kts`: the version catalog alias is missing in `gradle/libs.versions.toml` (check the trailing newline, the file once ended without one).
- `:core` failing with an Android/Compose import: a boundary violation, move the code to `:shared` or `:androidApp`.
- Test XML lives in `core/build/test-results/jvmTest/*.xml`; grep for `failures="` to count without scrolling.
- Manifest merger errors after adding a dependency usually mean a `minSdk` bump; it is a hand-review change.

## Running on the Pixel
```
./gradlew :androidApp:installDebug
scripts/device.sh run        # launches the activity
scripts/device.sh log        # ARCore + app logcat, filtered
scripts/device.sh shot out.png
```
ARCore needs a real device; there is no emulator path for the AR screens. Only `:core` is testable headless, by design.

## Never
- Never mark verify as passed without running it. The Stop hook reads `.gradle/.dirty-since-verify`; `scripts/verify.sh` clears it only on success.
- Never run `./gradlew clean` to "fix" a failure before reading the error; it costs minutes and rarely helps.
