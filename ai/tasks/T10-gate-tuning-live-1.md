# T10 — gate-tuning-live-1

status: done
created: 2026-10-28
day: Day 2
gate: ≥7/10 pass gate first try · p50 < 40 s · ≤ $0.05/character
cut-if-slips: keep (b) and (c) (robustness); drop (a) and report the prompt idea instead

## Goal
Act on the first live run (`runs/2026-10-27T23-51-04Z.json`, 3 real inputs, provisional refs in `docs/style-refs/`):
0/3 passed the gate, every character used all 3 attempts (p50 78.6 s), and one character died because a Spark score
call ran out of tokens. Make the stylizer copy the refs' look harder and stop a flaky score call from killing a
character. **Do not** change the 0.7 threshold, the 0.6 model weight, the retry count or the score prompt's rubric.

## Evidence (live run 1)
- Muse outputs (`docs/api-samples/muse-stylize/{toy-car-01,toy-car-02,watermelon-01}.out-*.png`, untracked in the
  main checkout) keep the character but with thin sketchy brown/black lines and crayon hatching; the refs have a bold
  even dark-brown outline, flat crayon fills, simple friendly faces. Model scores: toy-car-01 0.62, 0.32, 0.68;
  watermelon-01 0.25, 0.30, 0.32. Deterministic half ≈ 0.65–0.68.
- toy-car-02: `scoreStyle` → `MetaError.Parse("empty content, finish_reason=length")` at
  `max_completion_tokens = 512` with 9 images; the whole character failed.
- Measured costs (standard-tier upper bound): gate ≈ $0.0055/call, sheet ≈ $0.004, stylize $0.01, cutout $0.005.
- One score call took 53 s (watermelon-01 attempt 1); others 6–9 s.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaDtos.kt (`Prompts` only), docs/api-samples/muse-stylize/request.json.tmpl,
  docs/api-samples/spark-score/request.json.tmpl
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/CharacterPipeline.kt
- tests: core/src/commonTest/kotlin/kz/rudy/ar/core/pipeline/CharacterPipelineTest.kt, core/src/commonTest/kotlin/kz/rudy/ar/core/meta/RequestShapeTest.kt

## Contract
**(a) Stylize prompt.** Rewrite `Prompts.STYLIZE` so Muse transfers the refs' look explicitly: the first image is the
child's character; redraw it as a clean sticker illustration in exactly the style of the reference images — copy their
bold, even outline (weight and colour), flat crayon colour fills with light crayon texture, simple friendly shapes;
keep the character's identity (silhouette, pose, colours, features) so the child recognises it; one character, plain pure
white background, wide margins, no text, no shadow. Keep it one paragraph, no subject names. Keep `request.json.tmpl`
identical to the code (the existing shape test enforces it).

**(b) Score budget.** `SCORE_MAX_COMPLETION_TOKENS` 512 → 1024 (output tokens are billed as used; the cap only stops
truncation). Update the template and the KDoc line that explains the budget.

**(c) A failed score call is a failed attempt, not a dead character.** If `scoreStyle` (or the deterministic half)
throws a non-cancellation exception: record the `gate` stage `ok=false` with what was metered, treat the attempt as
`score = 0, passed = false`, emit `GateScored(attempt, 0f, false)`, and continue to the next attempt (retries unchanged).
After the last attempt the character ships with `gatePassed = false` as usual. Stylize and cutout errors still fail the
character. Document this in the class KDoc.

## Tests required
- `scoreErrorCountsAsFailedAttempt` — FakeModels score throws on attempt 1, returns 0.85 on attempt 2 → passes with
  attempts = 2, gate stage 1 `ok=false`, cost only what was metered.
- `scoreErrorOnEveryAttemptShipsUnpassed` — character completes, `gatePassed=false`, 3 gate stages.
- `stylizePromptAsksForRefOutlineAndIdentity` (RequestShapeTest) — the prompt mentions outline, flat colour, white
  background and keeping the character recognisable; the template matches.
- Existing tests green; update any that asserted "gate error fails the character", and say which.

## Steps
1. Read CLAUDE.md, this brief, `core/.../pipeline/CharacterPipeline.kt`, `core/.../meta/MetaDtos.kt` (`Prompts`).
2. Implement (c) first (robustness), then (b), then (a). Commit `core: ...` per item.
3. `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest --console=plain -q`; `scripts/verify.sh` at the end.
Rules: no live calls, never read local.properties, no new deps, no Gradle edits.

## Done when
- `scripts/verify.sh` exit 0; the orchestrator's live run 2 on the same 3 inputs completes all 3 characters.

## Result (filled on acceptance)
- (a) stylize prompt names the refs' look (bold even outline, flat crayon fills, keep identity); (b) score budget
  1024; (c) a throwing score call is a failed attempt. Review: merge as is → follow-up `428e08f` narrows the try to the
  scoring only (a throwing listener no longer double-records a stage) and treats a stray cancellation as a failed attempt.
- measured: live run 2 `runs/2026-10-28T00-01-24Z.json` vs run 1 on the same 3 inputs: model scores 0.62/0.32/0.68 →
  0.88 (toy-car-01), crash → 0.95 (toy-car-02), 0.25/0.30/0.32 → 0.60/0.65 (watermelon-01); first-try pass 0/3 → 2/3;
  p50 78.6 s → 33.0 s; $0.0455 → $0.0294 per character.
- cut/deferred: threshold, weights, retries and the score rubric unchanged (user decisions). Spark's score is noisy
  (0.62 vs 0.32 on near-identical outputs in run 1); averaging two calls or a rubric with anchors would steady it —
  not done, it changes the measurement.
