# T22 — core-offline-guard

status: todo
created: 2026-10-31
day: Day 6
gate: fresh install -> character -> full scenario with Wi-Fi off halfway, no crash
cut-if-slips: never cut (offline mode is a never-cut item)

## Goal
`:core` knows when the phone is offline so the UI can say so honestly. A `MetaModels` decorator watches every call:
a `MetaError.Network` marks the session offline, the next success marks it online again; the flag is a `StateFlow`
the app host exposes through `CaptureHost.offline` (T20 adds that member; the orchestrator wires `AndroidCaptureHost`
after both merge). Decision recorded here (deviation from T06's "switch to FixtureApi(AssetFixtureSource)"): there is
NO fixture fallback for a live capture or a live scan — a replayed level from another table would put cliffs and cups
in the wrong places, and an in-app photo has no fixture. Offline = bundled characters (already in the APK) + the
level you already scanned + an honest badge. Nothing throws differently than today; nothing retries.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/OfflineGuard.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/meta/OfflineGuardTest.kt (new)
- this brief

Do NOT touch `MetaApi.kt`, `FixtureApi.kt`, `FakeModels.kt`, anything outside `core/meta/` + its test.

## Contract (package `kz.rudy.ar.core.meta`)
```kotlin
/**
 * Decorates a MetaModels: forwards every call unchanged and tracks connectivity from the outcomes.
 * - A call that throws MetaError.Network -> offline = true (the error is rethrown as is).
 * - A call that returns normally -> offline = false.
 * - Any other failure (Http, Parse, Provider, FixtureMissing, CancellationException) leaves the flag unchanged and
 *   rethrows as is.
 * Thread-safe (calls arrive from IO and the pipeline's second coroutine); kotlinx StateFlow, no other dependency.
 */
class OfflineGuard(private val delegate: MetaModels) : MetaModels {
    val offline: StateFlow<Boolean>          // starts false
    val transitions: Int                     // how many times the flag changed (for logging / tests)
    fun reset()                              // back to online (e.g. the player tapped Retry)
    // all six MetaModels functions: suspend overrides that wrap `delegate.x(...)` in one private `track { }` helper
}
```
`offline` must be a `MutableStateFlow(false).asStateFlow()`; a value that does not change must not emit (StateFlow
semantics, so `transitions` counts real changes only).

## Tests required (`OfflineGuardTest`, `kotlinx.coroutines.test.runTest`, `FakeModels` as the delegate)
- `networkErrorMarksOfflineAndRethrows` — `segmentError = MetaError.Network("x")`: `segment` throws the SAME instance,
  `offline.value == true`, `transitions == 1`.
- `successMarksOnlineAgain` — after the above, clear `segmentError`; `segment` succeeds -> `offline.value == false`, `transitions == 2`.
- `httpErrorDoesNotChangeTheFlag` — `segmentError = MetaError.Http(500, "boom")` while online: rethrown, flag stays false, `transitions == 0`.
- `allSixCallsAreForwarded` — one call per `MetaModels` function reaches the delegate (`FakeModels.calls` has six entries, in order).
- `cancellationLeavesTheFlagAlone` — `sheetError = CancellationException()`: rethrown, flag unchanged.
Tests not required: the Android host wiring, UI.

## Steps
1. Read `MetaModels.kt`, `Types.kt` (`MetaError`), `FakeModels.kt`, `SegmentEachTest.kt` (runTest idiom).
2. Implement; `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest --console=plain -q` from your worktree;
   read the XML. Fill the Result. No git.

## Done when
- `:core:jvmTest` green with the five tests.
- The Result states the wiring the orchestrator must do in `AndroidCaptureHost` (3 lines: wrap `api`, route the
  scanner's `segment` through the guard, `override val offline get() = guard.offline`).

## Result (filled on acceptance)
- done: ...
- host wiring (for the orchestrator): ...
