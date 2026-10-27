---
name: task-brief
description: The ai/tasks workflow for Rudeus - how to write a Txx brief, dispatch it to an agent or worktree, and accept it. Load when planning a day, splitting work across agents, or when asked to "make a task for" something.
---

# Task briefs (`ai/tasks/Txx-slug.md`)

One brief = one agent = one branch (`task/Txx-slug`) = one PR-sized change. Briefs are the unit of parallelism:
agents in `:core`/`:cli`/`:shared` run side by side; anything in the hand-review zone is a single serial brief.

## Create
```
scripts/new-task.sh T07 occluder-boxes
```
Fill the sections of the template (`ai/tasks/TEMPLATE.md`). Every brief has:
- **Day / gate** it serves (from `ai/build-plan.md`) and what to cut if it slips.
- **Files it owns** (exact paths). Two parallel briefs never own the same file.
- **Contract**: public functions/types with signatures, units, edge cases.
- **Tests required** (names) and **tests explicitly not required**.
- **Done when**: `scripts/verify.sh` green + the gate check, stated as a command or a measurable.

## Dispatch
- Pure Kotlin work (`core/`, `cli/`): fresh agent, `general-purpose`, with the brief path + CLAUDE.md as context.
- UI (`shared/`): same, add `DESIGN.md`.
- AR host (`androidApp/`): human-driven. An agent may draft; a human runs it on the Pixel before it merges.
- Parallel briefs: separate git worktrees (`git worktree add .claude/worktrees/Txx task/Txx-slug`).

## Accept
1. Read the diff, not the summary. 2. `scripts/verify.sh`. 3. Gate check from the brief. 4. Merge, delete branch.
Record measured numbers in the brief's "Result" section; Day 7 README pulls from there and from `runs/`.

## Status line
Top of each brief: `status: todo | doing | review | done | cut`. `ls ai/tasks` is the board.
