---
name: human-checklist
description: The ai/dayN-human.md handoff - what only the user can do on the Pixel after an autonomous run, in the fixed seven-section shape used for Days 3, 4 and 5. Load at the end of a build day, before merging to main, or whenever device criteria are UNTESTED and the session is about to end.
---

# `ai/dayN-human.md` — what only a human can do

Written once per day at the end of the autonomous run; appended, never rewritten, when the user reports device
results. The reader is the user on the Pixel with one terminal open, so every step is a command or a tap and every
expectation is a logcat key or something visible.

## Shape (keep the numbering; Days 3–5 use exactly this)
```
# Day N — what only a human can do (<date>)
<branch>, what it contains, verify counts (core N, shared M, APK X MB), why device rows are UNTESTED.

## 1. What the agents did (read the diffs: `git log --oneline <base>..HEAD`)
| Brief | What | Status |          <- one row per brief, status: done / needs your Pixel run / needs your ears
Measured numbers from the Mac go here (CLI timings, costs).

## 2. Install and log
./gradlew :androidApp:installDebug && scripts/device.sh run
Second terminal first: scripts/device.sh log. Unlock the phone. Table layout in plane coords when a scenario needs it.

## 3. Gates                     <- one subsection per gate the day serves (Day 5 and Day 6 shared one file)
| Criterion | Status | Evidence |
Then the Pixel steps in order and the exact logcat keys to expect (`scenario_start`, `scenario_step idx=0..4`,
`scenario_done ms≈15000`, `occlusion=depth depth_supported=true`, `scan_ms=`, `jump_ignored mode=`).

## 4. Decisions only you can make      <- numbered; each names the file and the one-line switch
## 5. Housekeeping only you can do     <- worktrees to remove, branches to delete, merges to main
## 6. Known limits (by design today)
## 7. Device results (<date>, device, who)   <- appended after the user's run: PASS / PARTIAL / FAIL / Open
```

## Rules learned writing three of these
- **Logcat first, then launch.** The gate keys are logged once at startup; the Day 5 scenario count was lost because
  the logcat buffer rotated. Say "second terminal first" in §2 and say which numbers matter for the README.
- **Status words in §1 are precise:** `done` (verified on the JVM and merged), `needs your Pixel run` (hand-review
  code), `needs your ears` (sounds), `review` (brief waits on the device).
- **Every decision in §4 has a one-line switch** (`AndroidCaptureHost.voice -> null`, `FORCE_OCCLUDER_BOXES = true`,
  `samConcurrency` back to 3, flip the `v` sign in `FootprintOverlay.kt`). If the switch is more than one line,
  the code is not ready for a human decision.
- **Evidence names are fixed before the run:** `docs/evidence/dayN-<topic>-<variant>.png` (e.g.
  `day5-occlusion-{depth,boxes}.png`) so the user does not invent names and the README can link them.
- **§7 is the user's voice.** Record what they said (PASS/PARTIAL/FAIL/Open) with the commit that fixed anything found
  (`footprints on the table after commit 3cf8ac0`). Do not reinterpret a FAIL into a PARTIAL.
- Also update the brief: `status: review (Pixel <date>: <one line>)` and the gate table in the brief's Result.

## Commit
`ai: dayN-human checklist, Day N gate verdicts (device UNTESTED, JVM PASS)`; after the user's run:
`ai: Pixel results <date> — Day N device evidence, <what failed>, <what was fixed>`.
