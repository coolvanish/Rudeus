# T38 — batch-import

status: review
created: 2026-11-04
day: Day 10 (user request: add several toys from the gallery at once, without playing them)
gate: Day 6 offline mode stays reachable (a batch with Wi-Fi off fails as one message, nothing crashes); the single-photo
path, the bundled row and the saved row are unchanged
cut-if-slips: `AndroidCaptureHost.canPickMany = false` hides the button; everything else is unreachable then

## Goal
On Capture a third button **Add toys** opens the system photo picker for up to 7 photos. Each becomes a character the
same way a single photo does (cutout → stylize → gate → sheet), three at a time, with at most 2 stylize attempts and no
"Like it?" question. The Import screen shows one tile per photo (photo, then the sprite, name, score). A character that
passes the gate joins the Mine shelf as soon as it is done; one below the gate is shown with its score and a Keep toggle,
off by default, so a bad batch does not litter the shelf. **Done** returns to Capture with the new cards first; nothing
goes to Scanning or Play. Back during the run cancels what is still running; what was already saved stays.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/BatchImport.kt, core/metrics/MetricsLog.kt (append only)
- core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/BatchImportTest.kt, core/metrics/MetricsLogTest.kt (one test added)
- shared/src/commonMain/kotlin/kz/rudy/ar/CaptureHost.kt, App.kt, ui/Strings.kt, ui/screens/CaptureScreen.kt,
  ui/screens/ImportScreen.kt
- androidApp/src/main/kotlin/kz/rudy/ar/capture/PhotoIntake.kt, di/AndroidCaptureHost.kt (hand-review)
- DESIGN.md (Capture, Import, copy table)

## Contract
- `BatchImport.MAX_PHOTOS = 7`, `MAX_ATTEMPTS = 2`, `CONCURRENCY = 3`; `take(photos)` keeps the first 7;
  `run(count, concurrency) { index -> T }` runs the items with at most `concurrency` in flight, returns one `Result<T>`
  per index in order, a failed item does not stop the others, cancelling the caller cancels every item.
- `MetricsLog.appendCharacter(c)`: a finished character recorded elsewhere joins this run; `characters` and `scans` are
  atomic lists, so appends from several coroutines never lose one. `startCharacter`/`stage`/`endCharacter` stay
  single-threaded: one pipeline per log.
- `CaptureHost.canPickMany` (false = no button), `pickPhotos(max)`, `photoBatches: Flow<List<ByteArray>>` (an empty
  list = cancelled), `importCharacter(photo, maxAttempts, onEvent)`: no reviewer, `maxRetries = maxAttempts - 1`, a
  failed gate ships the best sprite (`accepted = false`).
- Android: each batch character runs on its own `MetricsLog` (one pipeline per log) and is appended to the session run
  when it ends; its stage costs are therefore the table estimates (`measured = false`), the session's metered totals
  stay exact. Saves are serialised by a mutex (`SavedCharacters` is one call at a time). Refs stay `01`, `02`…
  (atomic counter).
- Locked decisions untouched: scan is a button, one anchor per session, PAUSED freezes.

## Tests required
- `itemsComeBackInOrderWhateverFinishesFirst`, `neverMoreThanConcurrencyInFlight`, `oneFailureLeavesTheOthersAlone`,
  `cancellingTheCallerCancelsEveryItem`, `takeKeepsTheFirstSeven`, `zeroItemsIsAnEmptyList`
- `MetricsLogTest.charactersAppendedFromManyCoroutinesAreAllThere`
Tests not required: Compose screens, the picker.

## Done when
- `scripts/verify.sh` exit 0
- Pixel, build with the key: Add toys → 3 photos → three tiles paint at once (`import_start n=3`), each ends with
  `pipeline_ms … ref=0N`, passed ones show in Mine before Done is tapped (`saved_character=`), `import_done added=`;
  Wi-Fi off → one "Didn't work" line, no crash, Back returns to Capture.

## Result (filled on acceptance)
- measured: JVM green (core, shared host test), assembleDebug green. Device rows UNTESTED (worktree APK has no key).
- review: pending
- cut/deferred: deleting a kept character from the shelf (T26 deferred, still); per-batch measured costs.
