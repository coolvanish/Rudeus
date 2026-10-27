# {{ID}} — {{SLUG}}

status: todo
created: {{DATE}}
day: <Day N from ai/build-plan.md>
gate: <the gate line this serves>
cut-if-slips: <which cut line applies, or "never cut">

## Goal
One paragraph. What exists when this is done, from the player's or the pipeline's point of view.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/<pkg>/...
- core/src/commonTest/kotlin/kz/rudy/ar/core/<pkg>/...

## Contract
Public types/functions with signatures, units (metres, ms), edge cases, and what must stay unchanged.

## Tests required
- `testName` — what it proves
Tests not required: <list, so nobody writes them>

## Steps
1. ...
2. ...

## Done when
- `scripts/verify.sh` exit 0
- <gate check as a command or a measurable with its threshold>

## Result (filled on acceptance)
- measured: ...
- run id: runs/<...>.json
- cut/deferred: ...
