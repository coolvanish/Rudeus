---
name: metrics
description: The runs/<timestamp>.json metrics schema written by MetricsLog, how to produce and summarise runs from the CLI, and how README numbers are derived from them. Load when touching core/metrics, the Metrics screen, Export JSON, or when writing README figures.
---

# Metrics (`runs/*.json`)

Rule: **every number in the README comes from a file in `runs/`**. No hand-typed figures. `runs/*.json` are never
hand-edited (a hook blocks it). Day 2 gate reads directly from them: >= 7/10 characters pass the gate first try,
p50 < 40 s, <= $0.05 per character.

## Schema (`MetricsRun`, serialised by kotlinx.serialization)
```json
{
  "schema": 1,
  "runId": "2026-10-28T14-03-22Z",
  "source": "cli | app",
  "provider": "meta | fixture",
  "device": "Pixel 8 | mac",
  "characters": [
    {
      "input": "drawings/cat-01.jpg",
      "stages": [ { "name": "cutout|stylize|gate|sheet", "ms": 1234, "costUsd": 0.004, "attempt": 1, "ok": true } ],
      "gateScore": 0.82, "gatePassed": true, "retries": 0, "totalMs": 31200, "totalCostUsd": 0.031
    }
  ],
  "scans": [ { "objects": 5, "ms": 4800, "withinCm": [1.2, 0.8, 2.1, 1.5, 0.9] } ],
  "summary": { "n": 10, "gatePassRateFirstTry": 0.8, "p50Ms": 29800, "p90Ms": 41000, "costPerCharacterUsd": 0.034 }
}
```
`summary` is computed by `MetricsLog.summarise()`; never written by hand.

## Produce
```
./gradlew :cli:run --args="character --fixtures docs/api-samples --out runs"      # offline, deterministic
META_API_KEY=... ./gradlew :cli:run --args="character --live --drawings docs/drawings --out runs"
./gradlew :cli:run --args="metrics summarise runs"   # prints the README table
```
On the phone: Metrics screen -> Export JSON writes the same schema to app files; pull with `scripts/device.sh pull-runs`.

## README table
Produced by `metrics summarise`; paste verbatim and cite the run ids. Keep the latest three runs in git, prune older.
