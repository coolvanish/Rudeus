# Day 10 — what only a human can do (2026-11-04)

`claude/table-scan-merge-results-a44a04` (from `main` at 2175622): the table is scanned from several sides without a
button, and a rescan merges into the level instead of replacing it. Verify in this worktree: core 373, shared 26,
androidApp 2 tests, 0 failed; APK 63 MB. Device rows are UNTESTED: a worktree APK has no key, and only you can walk
around the table.

## 1. What the agents did (read the diffs: `git log --oneline 2175622..HEAD`)
| Brief | What | Status |
|---|---|---|
| T34 scan-merge | `TableScan.build(..., standing)`: previous objects out of frame or covered by any mask are carried; an in-frame, uncovered, empty place is forgotten only on the 2nd scan in a row (`LevelObject.unseen`); a matched object's footprint is replaced only by a better `ViewQuality` (peak at 55°, falls to 0 at 20° and 75°, halves by 1.5 m; stored as `LevelObject.quality`); the object under a standing character never changes; a new object at a known object's place (relabelled or nudged past 5 cm) adopts its id instead of leaving a ghost; a shift > 2 cm beats view quality; a blank scan forgets nothing; DYNAMIC (hand) re-placed every scan as before. 25 new tests | done |
| T35 auto-scan policy | `AutoScanPolicy` / `ScanSectors` in `:core`: 4 sectors around the anchor (10° hysteresis), camera still 0.5 s (≤ 3 cm, ≤ 0.1 rad), not steeper than 75°, tracking, no scan running, ≥ 8 s since the last scan, ≤ 4 per session, a button scan marks its sector; `scans[].background` + `summary.backgroundScans` in the run file (README figures stay over manual scans). 10 new tests | done |
| T36 toggle + docs | `ArSlot.autoScan` (default on), HUD chip «Өзі сканерлейді / Авто-скан / Auto scan» under Footprints; CLAUDE.md, build-plan (locked decision amended), README «Background scans» + metrics note, architecture.md | done |
| T37 AR host | quiet path in `RudeusArView.kt` behind `slot.autoScan`: same grab → SAM → merge → swap as the button, no `onScanned` (no overlay, lines, toast), ref `auto-NN`, `recordScan(background = true)`, `scan_merge` log line; first scan stays on the button | needs your Pixel run |
| T38 batch import (`claude/multi-character-gallery-import-5182bb`, from main at 507816d) | Capture's third button **Add toys** → system picker (≤ 7) → Import screen: `BatchImport` in `:core` (7 / 3 in flight / 2 attempts, 6 tests), `MetricsLog` lists atomic + `appendCharacter` (1 test), `CaptureHost.pickPhotos` / `photoBatches` / `importCharacter`, `ImportScreen` tiles with Keep chips; `PhotoIntake` multi-picker and `AndroidCaptureHost.importCharacter` (own MetricsLog per batch character, locks on saves and the run file) | needs your Pixel run |

Cost: a scan is 11 SAM prompts ≈ $0.03; four quiet scans ≈ $0.11 per session on top of the button scans.

## 2. Install and log
Merge the branch into `main` (or check it out in the MAIN checkout; worktree builds have no key), then:
```
./gradlew :androidApp:installDebug && scripts/device.sh run
```
Second terminal first: `scripts/device.sh log` (or `adb logcat -s RudeusGate`). Unlock the phone. A cup, a book and a
third thing (bottle, toy) spread over the table on the textured cloth, so that no single view holds all three.

## 3. Gates
### Merge + quiet scans (T34–T37)
| Criterion | Status | Evidence |
|---|---|---|
| After the button scan, walking to a new side fires exactly one quiet scan: `auto_scan sector=N fired=1`, then `scan_ms … ref=auto-02`, no overlay, no line, no toast | UNTESTED | logcat; `docs/evidence/day10-autoscan-quiet.png` (Play with the character, no overlay, while `auto-02` runs) |
| Objects on the far side stay in the level after a scan that cannot see them: their ids appear in `scan_merge … carried=[…]`, never in `removed` | UNTESTED (JVM PASS: `ScanMergeTest`) | logcat `scan_merge`; the character still routes around the unseen cup |
| The object under the character never changes: its id in `kept`, never in `replaced`/`removed`; the character does not fall off or get stuck | UNTESTED (JVM PASS) | logcat; `docs/evidence/day10-autoscan-standing.png` |
| A cup taken off the table disappears after two scans that see its empty place, not before | UNTESTED (JVM PASS) | `scan_merge removed=[cup-1]` on the second such scan |
| At most four quiet scans a session; none within 8 s of the last scan; none while the phone is steeper than 75° or moving | UNTESTED (JVM PASS: `AutoScanTest`) | no `auto_scan` lines beyond `fired=4`; none with `scan_too_steep where=camera quiet=true` right after |
| HUD chip off → no `auto_scan` lines at all; button scans behave as before (`ref=scan-NN`) | UNTESTED | logcat |
| `scripts/verify.sh` green | PASS | worktree: core 373 / shared 26 / android 2 |

### Add toys (T38)
| Criterion | Status | Evidence |
|---|---|---|
| **Add toys** → pick 3 photos → the Import screen shows 3 tiles, all three painting at once: `photo_batch_ms … photos=3`, three `stage_start=cutout` before any `pipeline_ms`, each ending `pipeline_ms … batch=true ref=0N` | UNTESTED (JVM PASS: `BatchImportTest`) | logcat; `docs/evidence/day10-import-running.png` |
| A photo that passes shows on the Mine shelf before Done is tapped (`saved_character=` while the others still run); after Done the new cards are first, nothing went to Scanning or Play | UNTESTED | `docs/evidence/day10-import-done.png`, then Capture |
| A photo below the gate: Tomato tag with its score, Keep chip off; Done without Keep leaves it off the shelf, with Keep it is saved (`saved_character=… passed=false`) | UNTESTED | logcat |
| At most 2 attempts per photo: no `stage_start=stylize attempt=3` in a batch | UNTESTED (JVM PASS: reducer `maxAttempts = 2`) | logcat |
| Back during the run: the pipelines stop (no further `stage_start` for those refs), Capture shows what was already saved; the run file holds them as `aborted: true` | UNTESTED | logcat, Metrics → Export |
| Wi-Fi off → every tile "Didn't work", title "None came out" + "Check the internet", one Done, no crash | UNTESTED | `docs/evidence/day10-import-offline.png` |
| Metrics screen after a batch: the characters are in the run (`n` counts them); their stage costs are estimates (`measured: false`), by design | UNTESTED | Export JSON |
| `scripts/verify.sh` green | PASS | worktree: core 380 / shared 26 / android 2 |

Pixel steps for T38 (after the merge steps, or on their own):
1. Capture → **Add toys** → pick 3 drawings from the gallery (one of them deliberately bad, e.g. a photo of the table). Expect `photo_batch_ms … picked=3 photos=3` and three tiles. Screenshot `day10-import-running.png` while two tiles still paint and one is done.
2. Wait for Done. Expect the good ones Passed (Leaf), the bad one Not passed (Tomato) with a Keep chip. Tap Keep on it, then **Done**: `saved_character=… passed=false`, Capture shows the three new cards first. Screenshot `day10-import-done.png` before tapping Done.
3. Repeat with 7 photos: the picker stops at 7; three paint at once, the rest say "Waiting"; the whole batch takes about two or three single runs, not seven.
4. Start a batch, press Back after the first `stage_start=stylize`: Capture; no new `stage_start` lines for those refs follow.
5. Wi-Fi off → **Add toys** → 2 photos: both tiles "Didn't work", one **Done**, no crash. Screenshot `day10-import-offline.png`.

Pixel steps, in order (keys on `RudeusGate`):
1. Pick a bundled card → Scanning → tap → **Scan**: `scan_ms … ref=scan-01`, `scan_merge removed=[] replaced=[] kept=[] carried=[] ref=scan-01`, one `scan_object id=` per object. No `auto_scan` before this.
2. Walk to the next side of the table (about a quarter turn), hold the phone still ~0.5 s at a normal tilt: exactly one `auto_scan sector=N fired=1`, then `scan_ms … ref=auto-02` and `scan_merge … ref=auto-02`. Nothing appears on the screen; the character keeps walking. The objects this view cannot see are listed in `carried=`. Screenshot `day10-autoscan-quiet.png`.
3. Stay on that side: no second `auto_scan`. Go to the third side within 8 s: nothing until the cooldown passes, then one more. Around the table: `fired=2`, `3`, `4` at most; a fifth side never fires.
4. Walk the character onto the book, then scan from a side where the book looks different: `scan_merge … kept=[book-1]`; the character stays on the book. Screenshot `day10-autoscan-standing.png`.
5. Take the cup off the table, press **Rescan** twice from the same spot (≥ 8 s apart or just the button): first `carried=[cup-1]`, second `removed=[cup-1]`; the character now walks through where the cup was.
6. Press **Rescan** while a quiet scan is running: one overlay, one answer when it ends, no extra `scan-NN` ref until the next request.
7. Cover the camera (PAUSED), uncover: nothing resets; the next `auto_scan` needs a fresh still half-second.
8. HUD chip off: walk around the table again, no `auto_scan` lines; **Rescan** works as before.
9. Metrics screen → Export: `scans[]` entries of `auto-NN` carry `background: true`; the summary has `backgroundScans`.

## 4. Decisions only you can make
1. Quiet scans misfire, cost too much or confuse the child → `ArSlot.autoScan` default `false` in `shared/.../ArSlot.kt` (the chip stays, off by default; the host code stays behind it).
2. They fire too often / too rarely → `AutoScanParams` defaults in `core/.../pipeline/AutoScan.kt` (`dwellMs`, `cooldownMs`, `maxScans`, `stillDistM`).
3. The merge keeps ghosts or drops real objects → there is no one-line switch; revert the `core:` merge commit (T34) and the level is replaced per scan as at 2175622. The `scan_merge` line tells which rule misjudged (`carried` = out of frame / covered / first miss, `removed` = second miss, `kept` / `replaced` = view quality).

## 5. Housekeeping only you can do
- Merge `claude/table-scan-merge-results-a44a04` into `main` once the Pixel run passes (or with the chip off by default if only T37 fails); remove the worktree.
- If the submission is not sent yet, send the current `main` first; this branch goes in only after your run.

## 6. Known limits (by design today)
- Straight-down views give no scan at all (camera steeper than 75° is refused before the request): four sides, not five points.
- A cup moved 5–8 cm may get a new id while its old middle hides behind the new mask: the old one is carried as "covered" until a view from another side sees its empty place twice.
- The first scan of a session is always the button; quiet scans only add to it.
- The chip resets to on with each app start (not per AR session). Switching it off stops new quiet frames; a quiet scan already in flight finishes.
- A quiet scan that fails on the network flips the Offline badge like a button scan would (same `OfflineGuard`); there is no toast.
- The Metrics screen's scan count includes quiet scans; only the exported JSON tells them apart (`background`).

## 7. Device results
(to be filled after your run)
