# T40 — sheet-fighter-stats

status: done
created: 2026-11-11
phase: v2 wave 1 (ai/build-plan-v2.md)
gate: G2 — every bundled sheet yields stats with sum 20; legacy sheets parse unchanged
cut-if-slips: never cut (the duel reads these numbers)

## Goal
The Spark character sheet carries duel stats read from the picture, the reason for each in kk/ru/en, and which side of
the character the sprite shows. `:core` normalises the model's raw scores to the fixed budget, so every fighter has
20 points however generous the model was. A sheet written before this brief still loads and gets the archetype of
its ability.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/Fighter.kt (the data shape is fixed by the contracts commit; add to it)
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/Model.kt — `CharacterSheet` only
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/SheetParser.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaDtos.kt — only `Prompts.SHEET_INSTRUCTIONS`, `CHARACTER_SHEET_SCHEMA`, `SHEET_MAX_COMPLETION_TOKENS`
- core/src/commonTest/kotlin/kz/rudy/ar/core/domain/FighterStatsTest.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/meta/SheetStatsTest.kt (new)
- docs/api-samples/spark-sheet/sample-01.fixture.json, sample-02.fixture.json (synthetic: add the new fields), request.json.tmpl
- docs/api-samples/README.md (the `spark-sheet` row), .claude/skills/meta-fixtures/SKILL.md (the "Sheet" contract line)

Do not touch: `MetaModels.kt`, `MetaApi.kt`, `FixtureApi.kt`, `CharacterPipeline.kt`, `Bundle.kt` (T41 owns them this
wave), anything under `physics/` or `duel/` (T42), any Gradle file (T39).

## Contract
Already in the tree (contracts commit, do not change the shapes): `Stat`, `FighterStats(power, speed, guard, agility)`
with `MIN = 1`, `MAX = 10`, `BUDGET = 20`, `isBalanced`; `SpriteView { FRONT, SIDE, BACK }`; `CharacterSheet.stats`,
`.statReasons`, `.baseView`, `.facesRight`.

Add:
```kotlin
object StatBudget {
    /**
     * Raw model scores (any finite, non-negative numbers; missing stat = 0) -> a balanced FighterStats:
     * every stat in MIN..MAX, total == BUDGET. Proportional to the raw scores, largest remainder for the
     * rounding, ties broken by Stat ordinal, then clamped and re-spread so the bounds hold.
     * All zeros (or NaN / negative everywhere) -> null: the caller falls back to the archetype.
     */
    fun normalise(raw: Map<Stat, Float>): FighterStats?
}

/** The stats of a character whose sheet has none: one fixed, balanced archetype per ability. */
fun FighterStats.Companion.defaultFor(ability: Ability): FighterStats
// JUMP 5/5/5/5 · FLY 3/7/3/7 · ROLL 7/6/5/2 · HIDE 4/5/4/7  (power/speed/guard/agility)

/** What the duel reads: [stats] when present and balanced, else the archetype of [ability]. */
val CharacterSheet.fighter: FighterStats
```
Model JSON (all four new keys optional for the parser, required in the strict schema sent to Spark):
```json
"stats": {"power": 7, "speed": 3, "guard": 8, "agility": 2},
"why":   {"kk": {"power": "...", "speed": "...", "guard": "...", "agility": "..."}, "ru": {...}, "en": {...}},
"view":  "front" | "side" | "back",
"faces": "left" | "right"
```
- `SheetParser.fromJson`: `stats` values may be numbers or numeric strings; out-of-range and missing values go through
  `StatBudget.normalise`; no usable `stats` object -> `CharacterSheet.stats == null`. `why` -> `statReasons`, each reason
  trimmed, blank ones dropped, cut at 40 characters, unknown stat keys and languages ignored. `view` -> `baseView`
  (unknown/missing -> null). `faces` -> `facesRight` (unknown/missing -> null).
- `SheetParser.fallback(ref)` keeps `stats == null`.
- Prompt: Spark judges from what is visible in the picture, not from the name: claws, horns, big body -> power; wings,
  wheels, long legs -> speed; shell, armour, thick body -> guard; small, light, tail, many legs -> agility. Raw 1–10
  each. A reason is at most 4 words, for a child, in each of kk/ru/en. Say the scores will be rebalanced, so spreading
  them matters more than their size. `view` = which side of the character the picture shows; `faces` only matters for
  a side view.
- JSON written for a sheet without the new fields must stay byte-identical to today's (bundled `sheet.json` files and
  their tests): encode the four new fields only when set (`@EncodeDefault(NEVER)` as `MaskResult.instances` does, or
  the equivalent that works with `Bundle.json`'s `encodeDefaults = true`).
- `SHEET_MAX_COMPLETION_TOKENS`: raise if the reply grows past the current cap (state the estimate in Result).

## Tests required
- `normaliseAlwaysSumsToBudgetWithinBounds` — a few hundred seeded random raw maps, plus extremes (one huge, three tiny; all equal; all 10; one stat missing)
- `normaliseKeepsTheOrderOfRawScores` — raw a >= raw b implies out a >= out b
- `normaliseIsDeterministicOnTies` — equal raws: the Stat-ordinal rule decides, same output every time
- `normaliseOfNothingIsNull` — empty, zeros, NaN, negatives
- `everyAbilityArchetypeIsBalanced`
- `sheetWithStatsParses` — the new sample fixture: stats balanced, reasons in three languages, view, faces
- `legacySheetHasNoStatsAndFightsWithItsArchetype` — every `docs/api-samples/spark-sheet/*.fixture.json` recorded before this brief
- `sheetJsonWithoutNewFieldsIsUnchanged` — encode a legacy sheet with `Bundle.json`: no `stats`, `statReasons`, `baseView`, `facesRight` keys
- `sheetWithStatsRoundTrips` — encode/decode equality
- `sheetSchemaListsEveryRequiredKey` — the strict schema names stats/why/view/faces (extend `RequestShapeTest` if that is where the schema is pinned)
Tests not required: live calls, prompt wording, Compose.

## Steps
1. Tests for `StatBudget` first, then the implementation.
2. Parser + schema + prompt; mirror the prompt in `request.json.tmpl`.
3. Synthetic fixtures; legacy fixtures stay untouched (they are the back-compat proof).
4. Docs lines.

## Done when
- `./gradlew :core:jvmTest` green (cloud container: no `ANDROID_HOME` needed for this task)
- `./gradlew :cli:run --args="character --fixtures docs/api-samples --out build/t40-runs --all" -q` still ships every fixture character
- No file outside "Owns" changed

## Result (filled on acceptance)
- measured: `:core:jvmTest` 403 tests, 0 failures (baseline 388; +7 `FighterStatsTest`, +7 `SheetStatsTest`, +1
  `RequestShapeTest.sheetSchemaListsEveryRequiredKey`; review fixes included). `:core:compileKotlinIosSimulatorArm64` green.
  `cli character --fixtures docs/api-samples --out build/t40-runs --all`: 20/20 fixture characters shipped.
  G2: every bundled sheet (`androidApp/src/main/assets/bundled`, `docs/bundled`) fights with a balanced 20 (the archetype
  of its ability: none has stats yet), read through `SheetParser.parse` and `Bundle.decodeSheet`; their JSON is
  byte-identical to the pre-T40 shape (pinned against a mirror of the old class).
- normaliser: `clamp(k * raw, MIN, MAX)` with one scale `k` found by geometric bisection (raws 1e-30..3e38 converge),
  floors + largest remainder. Tie-break as implemented is stricter than the contract's wording: equal fractions go to
  the larger raw score first, then to the lower Stat ordinal (so a strictly larger raw never gets fewer points, even
  when float rounding makes two shares equal). One positive score alone: it gets MAX, the other three split 10
  (4/3/3). A balanced input comes back unchanged.
- review fixes: a score too large for a Float (`1e39`, `"1e39"`, `1e400`) is clamped to `Float.MAX_VALUE` in
  `SheetParser.parseStats`, so it is the top score (10/4/3/3), not a missing one. `CharacterSheet.fighter` is the one
  rule on every load path: balanced `stats`; else those stats rebalanced as raw scores (a hand-edited 10/10/10/10 fights
  as 5/5/5/5 through `Bundle.decodeSheet` and `SheetParser.parse` alike); else the archetype. The legacy test pins the
  exact count (18 recorded fixtures); a re-record with stats must update it.
- `facesRight` is meaningful only when `baseView == SIDE`; the parser keeps whatever the model said for front/back
  views (the strict schema makes it answer), and consumers must ignore it there.
- token cap: reply ~250-300 -> ~450-500 visible tokens (new fields ~380 chars, Cyrillic-heavy); with the extra judging
  in reasoning, `SHEET_MAX_COMPLETION_TOKENS` 2048 -> 3072 (billed as used). Unprobed.
- also: `SheetParser` reads the saved `sheet.json` keys (`statReasons`, `baseView`, `facesRight`) too, because the
  Android host loads bundled sheets through `SheetParser.parse` (AndroidCaptureHost); otherwise a regenerated bundle
  would lose reasons and view on the phone.
- outside "Owns": `core/src/commonTest/.../meta/RequestShapeTest.kt` (the brief allows extending it; its pins on the
  required list and the 2048 cap had to change).
- cut/deferred: real `spark-sheet` fixtures with stats need a live re-record on the Mac (human). Whether Spark's strict
  mode accepts the nested `why` object is unverified (same caveat as `lines`). `RecordingModels.sheetJson` (T41 owns it)
  does not write stats/why/view/faces yet, so `--record` would drop them from a re-recorded fixture until it does.
