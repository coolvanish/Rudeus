# T39 — ios-core-services

status: review
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
- measured: `scripts/verify.sh` exit 0 — core 492 tests (388 before + 104 new), shared 26, android 2, 0 failed /
  0 skipped; APK built. `:core:compileAndroidMain`, `:core:compileKotlinJvm`, `:core:compileKotlinIosArm64`,
  `:core:compileKotlinIosSimulatorArm64`, `:core:compileIosMainKotlinMetadata` and
  `:core:compileTestKotlinIosSimulatorArm64` green on Linux. New suites (jvm XML): Io: CharacterFolder 27,
  FixtureSource 11, MetricsSink 8; File: 21 / 10 / 7; Memory/Map: 10 / 6 / 4.
- proven on the JVM (crash rule): `aProcessDeathAtAnyStepOfAWriteLeavesTheOldEntryOrTheNewOne` copies the root to a
  snapshot before step n of a write, for every n (> 10 asserted), fresh and replacing; a fresh `IoCharacterFolder` on
  the snapshot recovers to exactly the old or the new entry (fresh write: absent or new; replacing: never absent),
  a bystander id untouched, no scratch name left. Same for `delete` (whole entry or none). Exceptions at every step
  (`everyFailingCallLeavesTheOldEntryOrTheNewOne`) leave the old entry or the new one. Removing the move-back from
  recovery turns 2 tests red. Not proven: power loss — nothing is fsync'ed, so a rename can reach the disk before
  the file data (same as `File*` today).
- dependency: `org.jetbrains.kotlinx:kotlinx-io-core:0.9.1` in `commonMain` — one tested file implementation for
  android, jvm and ios instead of a third copy; Ktor 3.6.1 already resolves 0.9.1 (`:core` jvm and `:androidApp`
  debugRuntimeClasspath both show it before and after), so the APK gains no artifact.
- API 24: kotlinx-io-core-jvm 0.9.1's `SystemFileSystem.atomicMove` looks up `java.nio.file.Files` with
  `Class.forName` and, on `ClassNotFoundException`, throws `UnsupportedOperationException("Atomic move not supported")`
  (checked in the jar's bytecode); Android has `java.nio.file` only from API 26 and minSdk is 24. Renames go through
  `internal expect fun renameAtomically` (`io/RenameAtomically*.kt`): jvm and ios call `atomicMove`, android calls
  `java.io.File.renameTo` (the call `FileCharacterFolder` makes) and throws `IOException` on false. **UNTESTED**: the
  android actual compiles but cannot run here (`:core` has no android host tests); it needs the Pixel / an API 24 device.
- deviation: kotlinx-io 0.9.1's `FileSystem` is a **sealed** interface, so the brief's failing `FileSystem` wrapper
  cannot be written. `IoCharacterFolder` has an `internal` constructor with a step hook (`(String) -> Unit`, called
  before every file-system step and rename); the public constructor is the brief's
  `IoCharacterFolder(root, fs = SystemFileSystem)`. The tests inject failures and simulated deaths there.
- finding F1: `FileCharacterFolder` (both copies) cannot rewrite an existing id: `File.renameTo` onto a non-empty
  folder fails, `write` throws "cannot publish" and leaves `.writing` behind (old entry intact). Latent today
  (`SavedCharacters` ids strictly rise). `IoCharacterFolder` replaces (old entry renamed to `.replaced-<id>`, new one
  published, aside deleted; restored on an exception, recovered after a death). Pinned by `replacesAnExistingId = false`.
- finding F2 (pinned by `checksNamesFirst = false`, one named test each): `IoCharacterFolder` rejects an empty / `.` /
  `..` id, its scratch names (`.writing*`, `.replaced-*`, `.deleting-*`) and bad frame names before any I/O, never
  lists scratch names, reads of them are empty, and a delete of a non-id is a no-op. The File copy fails an empty or
  dot id only at the rename (after filling `.writing`), accepts and lists `.replaced-x` / `.writing`, checks frame names
  mid-write, and `delete("")` removes the whole characters root (latent: nothing passes an empty id today).
- other differences: Io's `delete` renames the entry to `.deleting-<id>` before deleting it and swallows I/O errors
  like `deleteRecursively`; `Map`/`Memory` doubles differ from both on-disk ones by design (no directories:
  `exists("dir")` false, `list` skips subfolders, any metrics name accepted).
- cut/deferred: switching Android and the CLI to the `Io*` classes (needs the Pixel, incl. an API 24/25 run of the
  rename actual); image codec for iOS (after T43's map); running the common tests on iOS (CI job `ios-core`,
  `workflow_dispatch` only, not run yet).
