# T29 — emotes-choreography

status: review
created: 2026-11-04
day: Day 9 (Nov 4, v1.0 minimal cut; see ai/plan-cast-emotes.md)
gate: v1.0 gate "every emote plays on the active character and says a line; a new character arrives with its five choreographies (logcat `choreo source=spark`), offline it shows `source=default`"
cut-if-slips: cut line 6 — Spark choreography off (`MetaModels.choreograph` never called by the pipeline; `EmoteSet.defaults()` ships). The emote vocabulary, player and defaults are never cut.

## Goal
A character can say hi, say bye, dance, spin and roar. Each emote is a short choreography of the billboard quad
(scale, yaw, tilt, hop, a sound, a line from the sheet) that `:core` can sample at any millisecond. Spark writes the
five choreographies for a new character in ONE structured-output call, looking at the sprite and the personality;
`:core` sanitises the reply within hard bounds and falls back to hand-authored defaults offline or on a bad reply.
The set is saved next to the sprite (saved characters and the bundle) so Play never calls a model.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/emote/Emote.kt, EmotePlayer.kt, Choreographies.kt, ChoreographyParser.kt (all new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaModels.kt, MetaApi.kt, MetaDtos.kt, FixtureApi.kt, FakeModels.kt, OfflineGuard.kt, RecordingModels.kt, CostMeter.kt, Pricing.kt (only what `choreograph` needs)
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/CharacterPipeline.kt (STAGE_CHOREO, `CharacterResult.emotes`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/MetricsLog.kt — ONLY to add `costs.choreoUsd` (a new field with a default; the JSON stays readable by the Metrics screen)
- core/src/commonMain/kotlin/kz/rudy/ar/core/io/CharacterFolder.kt, core/src/jvmMain/kotlin/kz/rudy/ar/core/io/FileCharacterFolder.kt, core/src/androidMain/kotlin/kz/rudy/ar/core/io/FileCharacterFolder.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/bundle/Bundle.kt, SavedCharacters.kt
- core/src/commonTest/kotlin/kz/rudy/ar/core/emote/** (new), and the existing meta / pipeline / bundle / io tests that the contract changes (FixtureApiTest, RequestShapeTest, RecordingModelsTest, OfflineGuardTest, CharacterPipelineTest, SavedCharactersTest, BundleTest, FileCharacterFolderTest)
- docs/api-samples/spark-choreo/meta.json, request.json.tmpl, sample-01.fixture.json (synthetic, hand-written)
Must NOT touch: `physics/`, `domain/Model.kt`, `shared/`, `androidApp/`, `cli/` (T30 adds the CLI command after this merges; it only needs `MetaApi.stylize(ref, cutout, refs, prompt)` to be public — do that, one word).

## Contract
```kotlin
package kz.rudy.ar.core.emote

/** The Play HUD's five buttons, in this order. */
@Serializable enum class Emote { HELLO, BYE, DANCE, SPIN, ROAR }
@Serializable enum class EmoteSound { NONE, HELLO, ROAR, DANCE }

/**
 * The quad's pose at one instant, relative to the physics pose: the host multiplies scaleX/scaleY into the node scale,
 * adds yawDeg to the billboard yaw (a full turn = a spin), tilts the quad by tiltDeg about its own Z (a bow, a wave),
 * and raises it by hop * character height (hop is in character heights, so one choreography reads the same on the
 * table and the floor). Physics never sees any of it.
 */
@Serializable data class EmotePose(val scaleX: Float = 1f, val scaleY: Float = 1f, val yawDeg: Float = 0f, val tiltDeg: Float = 0f, val hop: Float = 0f) {
    companion object { val REST = EmotePose() }
}
@Serializable data class EmoteKey(val atMs: Long, val pose: EmotePose = EmotePose.REST)
/** [line] = index (0..4) into CharacterSheet.lines[lang]: the line said when the emote starts. */
@Serializable data class Choreography(val emote: Emote, val durationMs: Long, val keys: List<EmoteKey>, val line: Int = 0, val sound: EmoteSound = EmoteSound.NONE)
/** The five choreographies of one character. [source] = SOURCE_SPARK or SOURCE_DEFAULT (what the logcat key shows). */
@Serializable data class EmoteSet(val source: String, val choreographies: Map<Emote, Choreography>) {
    operator fun get(emote: Emote): Choreography          // never null: Choreographies.DEFAULT[emote] when missing
    companion object { const val SOURCE_SPARK = "spark"; const val SOURCE_DEFAULT = "default"; fun defaults(): EmoteSet }
}

object ChoreographyRules {
    val DURATION_MS = 300L..4000L; const val MAX_KEYS = 16; val SCALE = 0.6f..1.5f; const val MAX_TILT_DEG = 35f
    const val MAX_YAW_STEP_DEG = 360f   // between two consecutive keys
    val HOP = 0f..0.5f; const val MAX_LINE = 4
    /**
     * null when structurally unusable (no keys, durationMs outside DURATION_MS, more than MAX_KEYS keys after dedupe);
     * else: keys sorted by atMs (equal times keep the last), atMs clamped to 0..durationMs, every field clamped to
     * its range, yaw steps clamped to MAX_YAW_STEP_DEG, a REST key inserted at 0 when none is there, and the LAST key
     * forced to durationMs with pose REST except yaw, which is rounded to the nearest multiple of 360 (the quad faces
     * the camera again). line clamped to 0..MAX_LINE. sanitize(sanitize(x)) == sanitize(x).
     */
    fun sanitize(c: Choreography): Choreography?
}

object EmotePlayer {
    /** REST before 0 and from durationMs on; between two keys every field blends with smoothstep(t) (yaw linearly in degrees, so 0 -> 360 turns the quad once, never the short way round). */
    fun sample(c: Choreography, tMs: Long): EmotePose
    fun finished(c: Choreography, tMs: Long): Boolean = tMs >= c.durationMs
}

/** Hand-authored defaults; every one satisfies sanitize(x) == x. */
object Choreographies {
    val DEFAULT: Map<Emote, Choreography>
    // HELLO 1200 ms: tilt +15 / -15 / +15 / 0 (a wave of the whole body), hop 0.08 on the first beat, line 0, sound HELLO
    // BYE   1400 ms: a bow — tilt 25 reached at 300 ms, held to 700 ms, back to 0 at 1000, two small ±8 tilts, line 1, NONE
    // DANCE 2400 ms: four hops of 0.15 (every 400 ms) with tilt alternating ±12, scale (1.15, 0.85) on each landing, yaw 0 -> 360 over the last 800 ms, line 3, sound DANCE
    // SPIN  1200 ms: yaw 0 -> 720, scaleX 0.9 at the middle, line 4, NONE
    // ROAR  1300 ms: scale (1.3, 1.15) reached in 150 ms, tilt ±6 at ~10 Hz for 600 ms (keys every 50 ms, within MAX_KEYS), back to REST, line 2, sound ROAR
}

object ChoreographyParser {
    /**
     * Spark's reply, fences and prose tolerated like SheetParser: {"hello": C, "bye": C, "dance": C, "spin": C, "roar": C},
     * C = {"durationMs": n, "keys": [{"atMs": n, "scaleX": f, "scaleY": f, "yawDeg": f, "tiltDeg": f, "hop": f}], "line": n, "sound": "none|hello|roar|dance"}
     * (every field optional, unknown keys ignored). An emote missing or failing sanitize -> Choreographies.DEFAULT[emote].
     * source = SOURCE_SPARK when at least one emote came from the reply, else SOURCE_DEFAULT. Throws MetaError.Parse only when the text holds no JSON object.
     */
    fun parse(raw: String): EmoteSet
    /** choreo.json: the same shape plus "source". fromJson re-sanitises every choreography; unreadable text -> defaults (never throws). */
    fun toJson(set: EmoteSet): String
    fun fromJson(text: String): EmoteSet
}
```
`MetaModels` (abstract, every implementation and decorator in core/meta forwards it):
```kotlin
/** Spark: the five choreographies for a character, from its sheet and (when given) the cutout/sprite image. */
suspend fun choreograph(ref: String, sheet: CharacterSheet, image: ByteArray? = null): EmoteSet
```
- `Jobs.SPARK_CHOREO = "spark-choreo"`, in `Jobs.ALL`; fixture folder `docs/api-samples/spark-choreo/`, `Fixture.text` holds the raw reply content.
- `MetaApi.choreograph`: `POST chatPath` on `config.sparkModel`, one text part (`Prompts.choreograph(sheet)`: the personality, the names, the move vocabulary with the exact field names and bounds above, "a shy character waves small, a wild one roars big", JSON only) + one image_url part when image != null (fit to 512 like the sheet), `response_format` = `Prompts.CHOREOGRAPHY_FORMAT` (json_schema, strict, additionalProperties false; keys as arrays of objects of numbers), `max_completion_tokens` 4096, `reasoning_effort` "low". Cost metered under `Jobs.SPARK_CHOREO` via `Pricing.sparkUsd`. Reply -> `ChoreographyParser.parse`.
- `FixtureApi.choreograph`: `load(SPARK_CHOREO, ref).text` -> parse; missing fixture -> `MetaError.FixtureMissing` as the other jobs do.
- `FakeModels`: a scripted `EmoteSet` or defaults, with the same pause/failure hooks as `characterSheet`.
- `RecordingModels`: records `spark-choreo/<ref>.fixture.json` with `text` (raw content) and latency, like the sheet.
- `OfflineGuard`: forwards; its offline rule applies as for the sheet.
- `MetaApi.stylize(ref, cutout, refs, prompt)` becomes public (T30's pose spike calls it; no other change).
- Pipeline: in the `sheet` coroutine, right after the sheet resolves: `models.choreograph(ref, sheet, sheetImage)`; any failure or a cancellation while the caller is still active -> `EmoteSet.defaults()`. Recorded as stage `STAGE_CHOREO = "choreo"` (attempt 1, ok, ms, cost = measured cost since a `costMark(Jobs.SPARK_CHOREO)` taken before the call, else `costs.choreoUsd` when ok). `CharacterResult.emotes: EmoteSet = EmoteSet.defaults()` (default value: every caller compiles). Emit `PipelineEvent.StageStarted(STAGE_CHOREO, 1)` ONLY if `shared/.../ProcessingEvents.kt` tolerates an unknown stage name (read it and its test); otherwise emit nothing for this stage and say so in the Result.
- Storage: `CharacterFolder.write(id, sprite, sheet, choreo: String? = null)` + `readChoreo(id): String?`; file impls write `choreo.json` beside `sheet.json` in the same atomic step, Memory impl keeps a map. `SavedCharacter.emotes: EmoteSet` (defaults when the file is missing or unreadable); `SavedCharacters.save(sprite, sheet, emotes: EmoteSet = EmoteSet.defaults())`. `Bundle.choreoPath(ref) = "$ref/choreo.json"`, `BundleEntry.choreo: String? = null` (old index files still decode), `Bundle.entry(...)` fills it, `Bundle.encodeEmotes/decodeEmotes` via ChoreographyParser.
- `MetricsLog.costs.choreoUsd` default 0.003 (Spark, the sheet's order of magnitude). The metrics JSON gains that one field under `costs`; nothing else moves.

## Tests required
- emote/EmoteTest.kt: `defaultsAreAlreadySanitized`, `sampleIsRestOutsideTheChoreography`, `sampleBlendsBetweenKeysWithSmoothstep` (midpoint of a 0 -> 20 tilt pair is 10), `aSpinTurnsTheQuadBackToTheCamera` (after sanitize the last yaw % 360 == 0 and sample(c, durationMs) == REST), `sanitizeClampsOrdersAndCapsKeys`, `sanitizeRejectsEmptyOrOutOfRangeDuration`, `sanitizeIsIdempotent`
- emote/ChoreographyParserTest.kt: `parsesAFullReply`, `missingEmoteFallsBackToDefault`, `brokenEmoteFallsBackAndSourceStaysSpark`, `noJsonObjectThrowsParse`, `jsonRoundTripKeepsEverySet`, `unreadableJsonYieldsDefaults`
- meta/pipeline/bundle/io: `fixtureApiReplaysTheChoreoFixture` (sample-01), `requestShapeChoreographHasStrictSchemaAndImage`, `recordingModelsWritesTheChoreoFixture`, `offlineGuardForwardsChoreograph`, `pipelineShipsDefaultsWhenChoreographFails`, `pipelineRecordsTheChoreoStageAfterTheSheet`, `savedCharacterRoundTripsItsEmotes`, `aSavedCharacterWithoutChoreoLoadsDefaults`, `fileFolderWritesChoreoBesideTheSheet`, `bundleEntryCarriesTheChoreoPath`, `anOldIndexWithoutChoreoStillDecodes`
Tests not required: Compose, AR, the CLI.

## Steps
1. Read `SheetParser.kt`, `MetaModels.kt`, `MetaApi.kt` (`characterSheet`, `score`), `MetaDtos.kt` (`ChatRequest.characterSheet`, `Prompts`, `CHARACTER_SHEET_FORMAT`), `FixtureApi.kt`, `RecordingModels.kt`, `OfflineGuard.kt`, `CharacterPipeline.kt` (`sheet`, `record`, `costMark`), `SavedCharacters.kt`, `Bundle.kt`, the `meta-fixtures` and `metrics` skills.
2. Emote.kt / EmotePlayer.kt / Choreographies.kt / ChoreographyParser.kt with their tests first (pure, fast).
3. Then the model job end to end (interface, MetaApi, fixture, fake, recording, guard), the pipeline step, storage.
4. `./gradlew :core:jvmTest --console=plain -q` while iterating; `scripts/verify.sh` once at the end (shared and the Android build must stay green: `CharacterResult` and `SavedCharacters` keep source compatibility through default values).

## Done when
- `scripts/verify.sh` exit 0; every test above green; the diff touches only the owned paths.
- `docs/api-samples/spark-choreo/sample-01.fixture.json` replays through `FixtureApi` into a set with `source == "spark"`.
- Commit: `core: emotes — Choreography, EmotePlayer, defaults, Spark choreograph job, choreo.json (T29)`.

## Result (filled on acceptance)
- measured: `scripts/verify.sh` exit 0 (run with `ANDROID_HOME` set, the worktree has no SDK file): core 292 tests, shared 16,
  android 2, 0 failed; APK built. All 24 tests above green (emote 13, meta 4, pipeline 2, bundle 4, io 1).
  `spark-choreo/sample-01.fixture.json` (hand-written, `synthetic: true`) replays through FixtureApi with `source == "spark"`.
  Not probed live: the prompt, the strict schema, 4096 tokens, latency 8000 ms and $0.003 in meta.json are estimates (T30 probes).
- stage event: ProcessingEvents ignores unknown stage names (`stageOf` -> null, `StageFinished` else-branch), so the
  pipeline emits `StageStarted("choreo", 1)` + `StageFinished("choreo", ...)`; both back to back right after `Finished(sheet)`,
  because the call runs inside the sheet coroutine and events stay on the calling coroutine.
- contract notes: `MetaModels.choreograph` has a default body (`EmoteSet.defaults()`): androidApp's `DeferredModels`
  implements the interface explicitly and would not compile otherwise. Every core implementation overrides it, but the
  phone ships defaults until T32 adds the forwarding line (HAND-REVIEW zone):
  `override suspend fun choreograph(ref: String, sheet: CharacterSheet, image: ByteArray?): EmoteSet = delegate.choreograph(ref, sheet, image)`.
  `sanitize` also rejects when the REST key it inserts at 0 would make MAX_KEYS + 1 (keeps the output within MAX_KEYS
  and idempotent); a lone key at 0 gets the final key appended rather than moved. EmotePlayer blends yaw linearly in
  time (even spin), the other fields with smoothstep. `costs.choreoUsd` lives in `CostTable`, which is not in the runs
  JSON; the JSON only gains the `choreo` stage (last, after `sheet`). RecordingModels writes `toJson(set)` as `text`
  (the raw reply never leaves MetaApi). MetricsLogTest updated for the new trailing stage.
- follow-ups (not owned here): T30 — cli `Record.RECORDED_JOBS` / probe-meta.sh / docs/api-samples/README.md for
  spark-choreo, and writing `<ref>/choreo.json` into the bundle (`Bundle.entry` now fills `choreo`, so new index files point
  at that file); the metrics skill's stage list should gain `choreo`. A failed character whose sheet coroutine is still
  in the choreography call records `sheet` ok=false and no `choreo` stage.
- cut/deferred: nothing cut.
- Review fixes (FIX FIRST review of 42f1aa6): (1) the `MetaModels.choreograph` default body now throws
  `MetaError.Provider("choreograph not forwarded by …")`, so a model without the override (the app's DeferredModels
  until T32) ships the defaults as `choreo ok=false, $0`, not as a fake Spark reply; (2) `FixtureConverter` converts
  spark-choreo replies (raw content kept in `text`, validated by `ChoreographyParser.parse`); (3) the sheet and the
  choreography have their own Deferreds: a failing character records a sheet that came back with its own outcome/ms/cost,
  and a choreography still in flight is cancelled and NOT recorded (one that came back is recorded after the sheet);
  (4) RecordingModels writes `text = "{}"` for a default-source set, so it replays as `source=default`; (5) the final
  yaw is the multiple of 360 nearest the clamped yaw within MAX_YAW_STEP_DEG of the previous key (0/300/610 -> 360, not
  720); (6) `fromJson` falls back to `source=default` when "source" is missing or unknown; (7) the choreograph-failure
  test scripts a Spark set. verify.sh: core 297, shared 16, android 2, 0 failed.
