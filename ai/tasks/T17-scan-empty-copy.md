# T17 — scan-empty-copy

status: doing
created: 2026-10-31
day: Day 4
gate: 5 objects within 2 cm, character routes around; scan < 6 s (this brief keeps the HUD honest around that gate)
cut-if-slips: never cut (small); if it slips, ship only the Scanning-screen half

## Goal
A real scan that finds the table but no objects on it (`onScanned(0)`) no longer shows "No table found / use a
patterned cloth" (T04 risk 5). The child sees "Table is empty — put a cup or a book on it", can scan again or play
anyway on the empty table (the host has already swapped in a boundary-only `Level` through `onLevel`). A failed scan
(`onScanned(null)`) keeps today's NO_PLANE card and Skip-after-two rule. The Play rescan bubble makes the same
distinction.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt — only the `onScanned` block and the Scanning `onSkip`/new
  "play anyway" wiring
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/screens/ScanningScreen.kt — `ScanStatus`, `ScanState` KDoc, the card
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt — new ids only
- shared/src/androidHostTest/** and shared/src/commonTest/** — only if an existing test enumerates `ScanStatus`
  or string ids and must be extended
- this brief

Do NOT touch `ArSlot.kt`, `CaptureHost.kt`, anything in `androidApp/` or `core/`.

## Contract
- `enum class ScanStatus { IDLE, SCANNING, NO_PLANE, EMPTY, DONE, PAUSED }` — `EMPTY` is new.
- `onScanned` on the Scanning screen while `status == SCANNING`:
  - `null`  → `NO_PLANE`, `failures + 1` (unchanged).
  - `0`     → `EMPTY`, `objectsFound = 0`, `failures` unchanged (an empty table is not a failure; Skip logic untouched).
  - `n > 0` → `DONE` (unchanged).
- EMPTY card (ScanningScreen): title + hint + two buttons. Primary = the existing scan button label (scan again);
  secondary ghost = `scan_play_anyway` → same action as today's `onSkip` (go to Play; the level stays whatever the
  host built). Use the existing card/button components; match the NO_PLANE card's layout and DESIGN.md tokens.
- Strings (kk first, ru, en; short — the player is a child):
  - `scan_empty_title`: "Үстел бос" / "Стол пустой" / "Table is empty"
  - `scan_empty_hint`: "Кесе не кітап қой" / "Поставь кружку или книгу" / "Put a cup or a book on it"
  - `scan_empty_title_floor`: "Еден бос" / "Пол пустой" / "Floor is empty"
  - `scan_empty_hint_floor`: "Қорап не аяқ киім қой" / "Поставь коробку или ботинок" / "Put a box or a shoe on it"
  - `scan_play_anyway`: "Бәрібір ойнау" / "Играть так" / "Play anyway"
- Play (Rescan) bubble in `onScanned`'s `else` branch: `0` → `scan_empty_title` (floor variant on FLOOR);
  `null` → the existing `scan_no_plane_title` (floor variant on FLOOR). `hud.scanned` semantics unchanged.
- The host-less shell (no AR view) still ends a scan with an honest `0` after the default duration → it now lands on
  EMPTY, not NO_PLANE. Keep that path compiling and sensible.

## Tests required
- None new (Compose screens are not tested). If `SharedLogicAndroidHostTest` or a common test enumerates string ids
  or `ScanStatus`, extend it so it stays green.
Tests not required: Scanning screen layout, the bubble text timing.

## Steps
1. Read `ai/tasks/T04-scan-to-level.md` "Result" (how the host answers), `App.kt` lines ~196–232 and ~330–380,
   `ScanningScreen.kt`, `Strings.kt`, DESIGN.md (card + button tokens).
2. Implement; build with `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :shared:testAndroidHostTest
   :androidApp:assembleDebug --console=plain -q` from your worktree.
3. Commit on your branch: `shared: empty-table scan state and copy (T17)`.

## Done when
- `:shared:testAndroidHostTest` and `:androidApp:assembleDebug` green in the worktree (the orchestrator runs the full
  `scripts/verify.sh` after merging).
- Every new string id has kk, ru and en.

## Result (filled on acceptance)
- measured: n/a (copy + state)
- cut/deferred: ...
