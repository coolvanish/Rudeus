---
name: cut-line
description: How to actually apply a cut line from ai/build-plan.md once the gate skill says cut - the one-line switch behind a host interface, what to keep in the tree, the brief status wording, the human-checklist note and the commit. Load when a feature fails on the Pixel after 6 PM, when the user says "cut it" or "hide it before the shoot", or when deciding whether a feature is cuttable at all.
---

# Applying a cut line

`gate` decides *whether* to cut (order: 1 Voice, 2 SAM rigging, 3 hand, 4 depth occlusion -> boxes -> none,
5 affordances -> jump only, 6 in-app capture -> bundled characters; never the Day 1 gate, metrics JSON, Day 6 offline
mode, Day 7 shoot). This skill is *how*, so the cut is one commit, reversible, and visible.

## Build features cuttable from day one
A feature is cuttable when the host exposes it as a nullable or a constant and nothing else depends on it:
- `CaptureHost.voice: VoiceHost?` -> `null` hides the mic (cut line 1, applied 2026-11-03 in `AndroidCaptureHost`).
- `FORCE_OCCLUDER_BOXES` / `OCCLUDER_BOXES_WHEN_NO_DEPTH` in `Occluders.kt` -> depth, boxes, or none (cut line 4).
- Ability set in the character sheet parser / `GameLoop.setAbility` -> jump only (cut line 5).
- Capture button behind `CaptureHost` -> bundled characters only (cut line 6).
If a brief adds a feature on a cut line, its Contract must name the switch; the reviewer checks for it.

## The cut, step by step
1. **Evidence first:** the gate table row that fails, with the device note (`FAIL: voice on the Pixel (not
   investigated by decision)`). A cut without a FAIL row is scope reduction and needs the user's word in chat.
2. **Flip the switch, keep the code.** `:core` logic and its tests stay (VoiceCommandsTest 5/5 still runs); only the
   host stops offering the feature. Deleting code before the shoot is more risk, not less.
3. **Comment at the switch:** `// HAND-REVIEW: cut line N — <what fails where>; flip back by <one line>`.
4. **Copy and HUD:** remove the button, badge or hint the player would otherwise see (kk/ru/en); an invisible feature
   with a visible button is a demo bug.
5. **Verify** (`assembleDebug` at least; `scripts/verify.sh` before stopping).
6. **Commit** `androidApp: hide the mic (cut line 1 — voice does not work on the Pixel; hand-review)`: the subject
   names the cut line and the reason.
7. **Bookkeeping:** brief `status: cut (<date>: <reason>; switch: <file>)`; the README's model table and the
   human checklist §4/§7 say the feature is cut and why; `ai/build-plan.md` itself is not edited (it is the plan of
   record, the brief records the deviation).

## Do not
- Do not cut below the line order without the user; if occlusion is the problem, boxes come before none.
- Do not fix the failing feature "quickly" after 6 PM instead of cutting; that is what the cut lines exist for.
- Do not remove tests or fixtures for the cut feature.
