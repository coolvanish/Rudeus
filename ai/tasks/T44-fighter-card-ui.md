# T44 — fighter-card-ui

status: review
created: 2026-11-11
phase: v2 wave 2 (ai/build-plan-v2.md)
gate: G2 — a child sees why their drawing got its stats
cut-if-slips: show the four bars without reasons

## Goal
When a character is done, the child sees its four duel stats as bars and, under each, the few words Spark gave for
it ("big wings"). The same block is a reusable component: the duel's VS screen (T49) shows two of them side by side.
A v1.0 character (no stats in its sheet) shows the archetype of its ability and no reasons.

## Owns (exact paths)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/components/FighterStatsCard.kt (new: the model + the composable)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/screens/ProcessingScreen.kt — the "done" state only (next to `AbilityTag`)
- shared/src/commonMain/kotlin/kz/rudy/ar/ui/Strings.kt — new ids only
- shared/src/commonTest/kotlin/kz/rudy/ar/FighterStatsUiTest.kt (new)
- DESIGN.md — the copy table rows for the new ids and one paragraph for the component

Do not touch: `:core` (the contract is `CharacterSheet.fighter`, `.stats`, `.statReasons` from T40), `App.kt`,
`CaptureScreen.kt`, `PlayScreen.kt`, `ArSlot.kt`, `androidApp/**`, Gradle.

## Contract
```kotlin
/** One row of the card: pure data, built without Compose so it can be tested. */
data class StatRow(val stat: Stat, val label: StringId, val value: Int, val reason: String?)

/** The four rows in Stat order for [sheet] in [lang]: values from `sheet.fighter`, reasons from
 *  `sheet.statReasons[lang.code]`, falling back to "en", then none. A sheet without stats has no reasons. */
fun fighterRows(sheet: CharacterSheet, lang: Lang): List<StatRow>

/** True when the numbers came from the picture (sheet.stats != null), false when they are the ability's archetype. */
fun hasDrawnStats(sheet: CharacterSheet): Boolean

@Composable
fun FighterStatsCard(sheet: CharacterSheet, modifier: Modifier = Modifier, compact: Boolean = false)
```
- A row: label, a 10-segment bar filled to `value`, the number, and (not in `compact`) the reason on a second line.
- Strings, kk first, then ru, en; short, for a child: `stat_power` Күш / Сила / Power · `stat_speed` Жылдамдық /
  Скорость / Speed · `stat_guard` Қорғаныс / Защита / Guard · `stat_agility` Ептілік / Ловкость / Agility ·
  `stats_title` (the card's heading) · `stats_from_drawing` (one line shown when `hasDrawnStats`: the numbers come
  from the drawing; draw wings or spikes to change them). Check the Kazakh with the existing strings' register.
- Colours, type, radii, spacing only from `ui/theme/Tokens.kt`; follow DESIGN.md for cards and tags. One bar colour
  per stat is fine if the tokens have four that read on the card background; otherwise one colour.
- Commonmain only, no JVM-only API: `:shared:compileKotlinIosSimulatorArm64` must stay green.

## Tests required
- `rowsFollowStatOrderAndUseFighterValues`
- `reasonsFallBackToEnglishThenNone`
- `sheetWithoutStatsShowsItsArchetypeAndNoReasons`
- `everyStatHasALabelInThreeLanguages`
Tests not required: the composable itself, layout, screenshots.

## Done when
- `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk scripts/verify.sh` exit 0
- No file outside "Owns" changed

## Result (filled on acceptance)
- measured: `scripts/verify.sh` exit 0 under the lock: core 403, shared 30 (26 + 4 new in `FighterStatsUiTest`), android 2;
  `:shared:compileKotlinIosSimulatorArm64` green.
- built: `FighterStatsCard.kt` (`StatRow`, `statLabel`, `fighterRows`, `hasDrawnStats`, the composable); the card sits in
  `ProcessingScreen`'s `ResultLines` under the name + ability tag, in the DONE state only. Six new ids (`stats_title`,
  `stat_power|speed|guard|agility`, `stats_from_drawing`) and their DESIGN.md rows. One bar colour (Sky), tracks CreamDeep,
  card Paper: Tomato/Hazard/Sun are taken by status, cliff and the one primary, and only Sky/Leaf/Grape are left.
- cut/deferred: how it looks on the Pixel is the user's check (not seen here: no device). Open: App.kt moves on 2 s after
  Done (`DONE_AUTO_ADVANCE_MS`); that is too short to read four reasons. App.kt is not in this brief's Owns.
