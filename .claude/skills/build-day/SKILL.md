---
name: build-day
description: How a Rudeus build day is run end to end - day branch, briefs in waves across worktrees, reviewer wave, merge, status commits, human checklist at the end. Load when the user says "what's next by the plan", "let's do Day N", or asks to run several briefs at once.
---

# A build day (the pattern from Days 3, 4 and 5)

One day = one integration branch `dayN/<slug>` (`day3/table-gate`, `day4/table-level`, `day5/play`) cut from the
previous day's branch in the **main checkout**. Nothing lands on `main` until the user has run the day's gate on the
Pixel and said so.

## 1. Plan (first 15 minutes, no code)
1. Load `gate`; read the day's **Gate** line and the Cut lines in `ai/build-plan.md`.
2. Split the gate into briefs with `scripts/new-task.sh Txx slug` (load `task-brief`). Rule of thumb from the record:
   one `:core` brief, one `:shared` brief, one `:cli`/fixtures brief run in parallel; the `androidApp` brief is
   serial and comes last because it depends on the `:core` contract (T19 -> T20 -> T21; T12 -> T13 -> T14).
3. Commit the briefs alone: `ai: Day N briefs Txx (...), Tyy (...)`. That commit is the day's table of contents.

## 2. Wave 1 — implement (parallel, disjoint paths)
- One worktree per brief: `git worktree add .claude/worktrees/Txx task/Txx-slug` cut from the day branch.
- Dispatch by tier (CLAUDE.md agents table): `rudeus-opus-high` by default, `rudeus-opus-extra` for geometry/physics,
  `rudeus-sonnet-high` for UI from DESIGN.md, copy, fixtures. The agent gets the brief path + CLAUDE.md, nothing else.
- Agents in the same wave never own the same file. If two briefs need one file, the second brief waits for the first.
- The `androidApp` draft is also written by an agent (`rudeus-fable-high` or opus-high), but it is a **draft for
  hand-review** (load `hand-review`): it must not be merged on the agent's word.

## 3. Wave 2 — review before merge
- `rudeus-reviewer` gets the diff (`git diff dayN/slug...task/Txx-slug`), the brief, and nothing from the implementer.
- Findings come back ordered; anything labelled gate-relevant / "FIX FIRST" is fixed in the same worktree by the
  implementing tier and committed as `area: Txx review fixes — a, b, c` (one commit, findings listed in the subject).
- Two review examples worth remembering: the offline flag that locked Photo/Scan forever (fixed: a tap is the retry),
  and the FLY replay that skipped the cup beat (fixed: every run restarts at spawn on simulated time).

## 4. Merge and bookkeeping
```
git merge --no-ff task/Txx-slug            # into dayN/slug, in the main checkout
```
- After each merge set the brief's `status:` line (`done`, or `review` when only the Pixel can finish it) and commit
  the status change with `ai: Txx done` or `ai: T19/T20/T22/T23 done; T21/T24 stay review for the Pixel`.
- `scripts/verify.sh` on the day branch after the last merge; note the counts (core N, shared M, APK size) for the
  human checklist.
- Agents cannot remove worktrees or delete branches (load `harness-limits`). List them in the checklist instead.

## 5. End of day
1. Load `gate`: verdict table per criterion, `PASS` / `FAIL` / `UNTESTED (Pixel offline/locked)`; JVM evidence names
   the test, device evidence names the logcat key or the screenshot.
2. Write `ai/dayN-human.md` (load `human-checklist`) and commit it: `ai: dayN-human checklist, Day N gate verdicts`.
3. The final message to the user is the gate table plus the three things only they can do, not a diff summary.

## What slips the day (seen, not guessed)
- A hand-review draft written against Context7 docs for a newer SceneView than the shipped 4.31.0; confirm APIs in
  the Gradle cache sources (load `hand-review`).
- Waiting on the Pixel: when it is offline or locked, mark UNTESTED and move on; use the emulator for UI evidence
  (load `device-evidence`). Do not burn the afternoon retrying `adb devices`.
- Scope creep after 6 PM: cut, never add (`gate` step 3, `cut-line`).
