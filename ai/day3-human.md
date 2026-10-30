# Day 3 — what only a human can do (Oct 29, 2026)

Everything below needs the Pixel in hand (it was not attached over adb during the autonomous run). Agents built and
reviewed T12–T14 on `day3/table-gate`; `scripts/verify.sh` is green (core 154, shared 11, APK 60 MB).

## 1. Install and log
```
./gradlew :androidApp:installDebug && scripts/device.sh run
```
Second terminal: `scripts/device.sh log` (tag RudeusGate). Key in `local.properties`, Wi-Fi on.

## 2. Day 3 gate (fill the table into ai/tasks/T02 and T03 "Result")
| Criterion | How | Threshold |
|---|---|---|
| 60 s walking around a real cup without drift | place (10 s settle), tap the same cup-edge point at t=0 and t=60, compare `tap u= v=` in the log; walk around the cup for the full 60 s | Δ < 1 cm; the character stays on its spot by eye |
| capture → character < 60 s | Capture → Photo → Processing → Next → Scanning → place → Play; read `capture_to_play_ms=` | < 60 000 ms |

Run A on Oct 28 showed a 14–25 cm world-frame excursion at 50–80 s (map correction). If it repeats, note at what
second and whether the dino stayed on its real spot — that decides whether the Day 3 gate passes or the anchor
strategy changes (re-anchor on a large correction is the fallback; not implemented, by design).

## 3. New since Oct 28 (one pass, ~15 min) — combined T13/T14 checklists
1. Fresh install: the paper camera-permission screen (not a bare button); Allow → camera.
2. Capture: the bundled cards show their own sprites (passed-gate characters first). Pick one → Scanning.
3. Scanning: chips **Table | Floor** top-centre; "Look at the table" + "Move the phone slowly"; Scan disabled.
   After the plane settles (~10 s): one pill "Tap the table — it stands there". Tap → character stands, greeting
   bubble, chips gone, Scan enabled.
4. Scan → 4 s looking-around → "Table ready!" (no object count) → Play. **Same spot, no camera flicker** (one AR
   session now; this is the T02 fix).
5. Play: "Tap — it walks" pill, walk, pill leaves 5 s after the first walk. Into a cup → "Blocked!"; off the edge →
   "Oops! Fell" → "Back up". Bubbles ≥ 2.5 s apart. Button reads "Rescan".
6. Cover the camera 3 s → full Paused scrim; uncover → scrim leaves, character reappears ≤ 1 s, nothing reset.
   Same during a scan on Scanning → scan cancelled, Scan available again.
7. Play → Metrics → Back: character still there; taps on Metrics do not move it.
8. Metrics after one capture: real numbers; Export → "Saved · Where: Download/Rudeus/<runId>.json"; Files app →
   Downloads → Rudeus has the file, `summary.n` matches. Before any capture: "Nothing yet", Export disabled.
9. Floor / outdoors: restart, pick **Floor**: "finding the floor" pill; character ~24 cm, walks ~20 cm/s; floor taps
   accepted; a tap on a table top from floor mode rejected. Outside: daylight, grass or pavement (plain concrete
   may not give a plane; the banner says so).
10. Gallery → a portrait HEIC: log `heif=true`, Processing preview upright, sprite upright.
11. Camera photo: log `decode_px≈2040x1536`, `photo_px≈4080x3072`.
12. Footprints toggle on → the ARCore Rec button appears at the right edge; off → gone.
13. Language switch KK/RU/EN on Capture only; the Processing "Done!" shows the sprite at 160 dp.

## 3b. Already verified on the Pixel 7 Pro emulator (Oct 29, docs/evidence/ui-2026-10-29-*.png)
- Capture kk/ru/en, bundled cards with real sprites and full ability labels, Metrics empty state, the paper camera
  permission screen, system Back from Metrics returns to Capture, `bundled=16 bundled_ms=465` at cold start. No crashes.
- Not possible on the emulator: anything with ARCore (Scanning/Play), the camera, the pipeline (no key in that build).
- Known, left as is: the language choice is not persisted across a process restart (plain state); Scanning draws its
  chips over the permission screen until the camera is allowed.

## 4. Housekeeping only you can do
- Two stale agent worktrees are clean and safe to delete (blocked for the agent as destructive):
  ```
  git worktree remove .claude/worktrees/agent-a0fa1f632c8a4401e && git worktree remove .claude/worktrees/agent-a27e6be646cdb125c && git branch -D worktree-agent-a0fa1f632c8a4401e worktree-agent-a27e6be646cdb125c
  ```
- Decide after the gate: merge `day3/table-gate` into `main` (the agents only commit on the branch).

## 5. Known limits (by design today)
- Scan is still fake (0 objects) until T04 (Day 4). Floor platforms need `Semantics.kindFor` to take the surface
  (T04 owner; noted in T12's Result).
- A cancelled capture now lands in the run JSON as `aborted: true` (sheet cost in flight is not metered).

## 6. Day 4 lives on branch `day4/table-level` (main checkout; supersedes `day4/scan`) — see ai/day4-human.md
Real scan: camera frame → SAM (6 table prompts, +4 on the floor) → `ScanInput.fromAnchor` → `SceneBuilder` → the
character routes around the cup; footprint overlay under the Footprints toggle. Reviewed, builds, untested on the
Pixel. Order of work for you: (1) test Day 3 on `day3/table-gate` (sections 2–3 above); (2) if the anchor holds,
`git checkout day4/scan` (it already contains day3) and run the T04 checklist in ai/tasks/T04-scan-to-level.md —
its gate is 5 objects within 2 cm and scan < 6 s; the first thing to watch is `scan_ms=` / `segment_ms=` in logcat and
whether the footprints sit on the real objects (a mirrored overlay = flip the v sign in FootprintOverlay.kt).
