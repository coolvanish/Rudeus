# T39 — ios-core-services

status: todo
created: 2026-11-11
phase: v2 wave 1 (ai/build-plan-v2.md, "iOS readiness")
gate: G1 — `:core` compiles for iOS and its file services exist there
cut-if-slips: keep the targets (already in), defer the common file implementation

## Goal
`:core` already has `iosArm64` + `iosSimulatorArm64` (wave 0: klibs compile on Linux and macOS, `platform.Foundation`
and `platform.UIKit` resolve, `commonTest` compiles for iOS). What iOS still lacks is the three file-backed services
that exist twice today, as copies in `androidMain` and `jvmMain`. This brief gives them one common implementation,
proven on the JVM by the same contract tests as the copies, available to iOS, and leaves Android and the CLI on their
current classes until a human has seen the common one run on the Pixel.

## Owns (exact paths)
- gradle/libs.versions.toml, core/build.gradle.kts (HAND-REVIEW zone: minimal diff, `// HAND-REVIEW:` comment, say so in the final message)
- core/src/commonMain/kotlin/kz/rudy/ar/core/io/IoCharacterFolder.kt, IoFixtureSource.kt, IoMetricsSink.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/io/*.kt (new: the contract tests)
- core/src/jvmTest/kotlin/** (only to run the same contract against the existing `File*` classes)
- .github/workflows/ci.yml (one new job, see below)

Do not touch: the existing `File*` classes in androidMain / jvmMain (they keep serving Android and the CLI),
`CharacterFolder.kt` / `FixtureSource.kt` / `MetricsSink.kt` (the interfaces are the contract), `meta/*`, `pipeline/*`,
`bundle/*` (T41), `domain/*` (T40), `duel/*` (T42), `scripts/verify.sh` (already gates the iOS klib compile).

## Contract
- One new dependency: `org.jetbrains.kotlinx:kotlinx-io-core` in `commonMain`, at the version Ktor 3.6.1 already
  resolves (`./gradlew :core:dependencies --configuration jvmRuntimeClasspath | grep kotlinx-io`). Reason for the task
  note: one tested file implementation for android, jvm and ios instead of a third copy; it is already on the
  classpath through Ktor, so the APK gains no artifact.
- `IoCharacterFolder(root: kotlinx.io.files.Path, fs: FileSystem = SystemFileSystem) : CharacterFolder` with exactly
  the semantics of `FileCharacterFolder`, including the crash rule in `CharacterFolder.write` ("after a crash [id] is
  either complete or absent"): write into a sibling temp directory, then `atomicMove`. Read the JVM copy first and
  mirror its edge cases (existing id replaced, `choreo == null` removes the file, empty `frames` removes the folder,
  names that are not ids in `ids()`).
- `IoFixtureSource`, `IoMetricsSink`: the same for the other two.
- `ids()`, reads and `delete` never throw on a missing root; `write` throws on an I/O failure (as the interface says).
- No `expect/actual` here: this is common code. iOS gets its root directory from the host later (T51), the same way
  Android passes `filesDir`.

### Contract tests
One abstract test per interface in `commonTest`, taking a factory, run against: the `Memory*` class, the new `Io*`
class on a temp directory (`SystemTemporaryDirectory`), and (in `jvmTest`) the existing `File*` class. Same assertions
for all three; a difference between `File*` and `Io*` is a finding, not something to paper over.

### CI
A job `ios-core` on `macos-latest` in `.github/workflows/ci.yml` (still `workflow_dispatch` only):
`./gradlew :core:iosSimulatorArm64Test --console=plain`, test results uploaded like the build job's. It is the only
place the common tests actually run on iOS; the cloud container and `scripts/verify.sh` only compile them.

## Tests required
- `writeThenReadRoundTrips` (sprite, sheet, choreo, frames)
- `writeReplacesAnExistingId`, `writeWithoutChoreoRemovesIt`, `writeWithoutFramesRemovesTheFolder`
- `idsOfAMissingRootIsEmpty`, `readsOfAMissingIdAreNull`, `deleteOfAMissingIdIsANoOp`
- `aFailedWriteLeavesTheOldEntryIntact` — make the temp step fail (a `FileSystem` wrapper that throws on the n-th call) and read the old entry back
- the equivalents for `FixtureSource` and `MetricsSink`
Tests not required: iOS execution in the cloud (impossible), Android instrumented tests.

## Done when
- `ANDROID_HOME=/opt/android-sdk scripts/verify.sh` exit 0 (it includes `:core:compileKotlinIosSimulatorArm64` and the iOS test compile)
- `./gradlew :core:compileKotlinIosArm64` green
- The Gradle diff is the dependency line(s) only

## Result (filled on acceptance)
- measured: ...
- cut/deferred: switching Android and the CLI to the `Io*` classes (needs the Pixel); image codec for iOS (after T43's map)
