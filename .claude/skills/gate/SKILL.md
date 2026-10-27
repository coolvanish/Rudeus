---
name: gate
description: Run a go/no-go check against the day gates and cut lines in ai/build-plan.md for Rudeus. Load at the start and end of each build day, or whenever a gate looks at risk, to decide what to cut instead of what to add.
---

# Day gate check

1. Read the day's **Gate** line in `ai/build-plan.md` and the **Cut lines** list.
2. For each gate criterion, state one of: `PASS (evidence)`, `FAIL (evidence)`, `UNTESTED (why)`.
   Evidence is a run id in `runs/`, a test name, or a device measurement with the command used.
3. If any criterion is FAIL after 6 PM local, apply cut lines **in order** (1 Voice, 2 SAM rigging, 3 hand,
   4 depth occlusion, 5 affordances to jump-only, 6 in-app capture). Never cut: Day 1 gate, metrics JSON,
   Day 6 offline mode, Day 7 shoot.
4. Write the verdict into the day's task brief `Result` section and in the session's final message as a table:

| Criterion | Status | Evidence |
|---|---|---|

Keep it to the plan's wording. Do not add new criteria; do not relax thresholds (0.7 gate, 2 cm footprint,
60 s drift, 40 s p50) without the user saying so in chat.
