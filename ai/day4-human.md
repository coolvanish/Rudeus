# Day 4 — what only a human can do (Oct 31, 2026)

Everything is on branch **`day4/table-level`** in the main checkout (it contains `day3/table-gate`, the Day 4 scan draft
from `day4/scan`, and today's T16–T18). `scripts/verify.sh` is green: core 169, shared 12, APK 60 MB. The Pixel was offline
during the autonomous run, so every device criterion below is UNTESTED.

## 1. What the agents did today (read the diffs, not this list: `git log --oneline 038cfd6..HEAD`)
| Brief | What | Status |
|---|---|---|
| T16 `cli-scan-bench` | `./gradlew :cli:run --args="scan ..."` runs the phone's scan path from the Mac; `MetaConfig.samConcurrency`; fixtures `table1-day4*`; 5 runs in `runs/` | done (reviewed; see §5) |
| T17 `scan-empty-copy` | `ScanStatus.EMPTY`: "Table is empty — put a cup or a book on it", "Play anyway"; a failed scan still says "No table found" | done |
| T18 `day4-scenario-test` | `Day4ScenarioTest`: real SAM fixture → Level; cup blocks / book step-up / cliff respawn / PAUSED; rescan ids | done |
| hand-review | `AndroidCaptureHost`: `samConcurrency = 6` (one SAM round instead of two) — `// HAND-REVIEW:` comment explains | needs your Pixel run |

Measured from the Mac (six table prompts, 720×1280 JPEG): **4.1–5.3 s at concurrency 3, 2.5 s at 6**, $0.015 per scan.

## 2. Install and log
```
./gradlew :androidApp:installDebug && scripts/device.sh run
```
Second terminal: `scripts/device.sh log`. Key in the usual place, Wi-Fi on. Put **five real objects** on the table that match the
prompt list: cup, bottle, book, phone, and your hand (or a toy). The saved photos had none of these, which is why the default
prompts found 0 objects there — that is a prompt/table mismatch, not a bug.

## 3. Day 4 gate (fill the ruler table in ai/tasks/T04-scan-to-level.md "Result")
Follow the **Pixel checklist** in `ai/tasks/T04-scan-to-level.md` (steps 1–9). The three gate numbers:
| Criterion | How | Threshold |
|---|---|---|
| scan < 6 s | logcat `scan_ms=` per Scan tap (also `segment_ms=`) | < 6000 ms; watch for 429s in `segment[...]` lines — if they appear, set `samConcurrency` back to 3 (one line, `AndroidCaptureHost.kt`) |
| 5 objects within 2 cm | Footprints toggle on; ruler from each overlay centroid to the object's real base centre | ≤ 2 cm each; mirrored overlay = flip the v sign in `FootprintOverlay.kt` (one character) |
| character routes around | tap past the cup → "Blocked!"; tap onto the book → steps up; tap off the table edge → "Oops! Fell" → "Back up" at the anchor | by eye |

Also new since Oct 29: an empty table (found, nothing on it) shows **"Table is empty"** with *Scan again* / *Play anyway*
instead of "No table found".

## 4. Decisions only you can make
1. **Concurrency 6 on the phone** — keep if no 429s and `scan_ms` < 6 s; otherwise back to 3 and accept ~5 s.
2. **Prompt list** — `object` never matched anything in 7/7 live scans. Options: keep it (free in time, $0.0025/scan), drop it,
   or replace it with `toy` (a child's toy on the table is the likeliest unlisted thing). One line in `TableScanner.kt`
   (`ScanPrompts.TABLE`) and the mirrored list in `cli/.../ScanCommand.kt`.
3. **Merge** `day4/table-level` → `main` after the gate (agents only commit on branches).

## 5. Housekeeping only you can do
- Worktrees that are now merged or superseded and safe to remove (blocked for the agent as destructive):
  ```
  git worktree remove .claude/worktrees/T17 && git worktree remove .claude/worktrees/T18 && git worktree remove .claude/worktrees/T04
  git branch -d task/T17-scan-empty-copy task/T18-day4-scenario-test
  git worktree remove .claude/worktrees/agent-a0fa1f632c8a4401e && git worktree remove .claude/worktrees/agent-a27e6be646cdb125c && git branch -D worktree-agent-a0fa1f632c8a4401e worktree-agent-a27e6be646cdb125c
  ```
  `day4/scan` is superseded by `day4/table-level`; delete it when you are happy (`git branch -d day4/scan` after removing its worktree).
- **Reviewer verdict (rudeus-reviewer, Oct 31): "merge after the should-fixes"; no blockers.** Fixed here: budget stop meters the real SAM job; CLI FOV spans the long side like the T18 test; `--record` skips scans with failed prompts and never touches `table-01`/`sample-0x`; "Play anyway" marks the HUD scanned. Left as noted: the concurrency-6 line needs your Pixel sign-off (above); `MetaApi` → `samConcurrency` wiring has no direct test (would need a mock HTTP engine = new dependency); the DYNAMIC carry-over rule lives in `ScanCapture.kt` (host) and is untestable on the JVM — moving it to `:core` is a Day 5/6 candidate; the two `table1-day4*.input.jpg` are byte-identical (180 KB twice).

## 6. Known limits (by design today)
- Scan accuracy numbers in `runs/*.json` are `withinCm = []` until you measure; the T16 runs were made with a synthetic camera,
  so their centroids are illustrative only.
- Day 5 (occlusion, jump, affordances — T05) is not started; its brief is `ai/tasks/T05-occlusion-jump-affordances.md`.
