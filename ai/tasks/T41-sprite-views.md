# T41 — sprite-views

status: done
created: 2026-11-11
phase: v2 wave 1 (ai/build-plan-v2.md)
gate: G3 — live spike on >= 5 bundled characters, a human judges the contact sheets
cut-if-slips: cut FRONT (keep BACK), then cut views entirely (the v1.0 billboard stays)

## Goal
A character can carry up to three pictures of itself (front, side, back). `:core` knows how to ask Muse for the
missing ones, how to keep them with the character on disk and in a bundle, and which one to show for a given camera.
The CLI can run the whole thing on one character and lay the results side by side for a human to judge. No host
changes: the Android host keeps showing the base sprite until T50.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/emote/SpriteViews.kt (new: `SpriteViews`, `ViewChoice`, `ViewPicker`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/ViewPrompts.kt (new; do NOT add to `Prompts` in MetaDtos.kt, T40 owns it)
- core/src/commonMain/kotlin/kz/rudy/ar/core/meta/MetaModels.kt, MetaApi.kt, FixtureApi.kt, FakeModels.kt, RecordingModels.kt, OfflineGuard.kt, Pricing.kt, FixtureConverter.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/MetricsLog.kt (cost table / job list only)
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/CharacterPipeline.kt, PipelineEvent.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/bundle/Bundle.kt, SavedCharacters.kt
- core/src/jvmMain/kotlin/kz/rudy/ar/core/pipeline/JvmPipeline.kt
- cli/src/main/kotlin/kz/rudy/ar/cli/ViewsCommand.kt (new), Main.kt (register the command)
- core/src/commonTest/kotlin/kz/rudy/ar/core/emote/SpriteViewsTest.kt, ViewPickerTest.kt (new); meta/ViewFixtureTest.kt (new); the existing pipeline / bundle / saved-characters tests where a views case is added
- docs/api-samples/muse-view/ (new: meta.json, request.json.tmpl, one synthetic `sample-01` fixture with 2 tiny PNGs), docs/api-samples/README.md (layout + job rows)

Do not touch: `SheetParser.kt`, `MetaDtos.kt`, `domain/*` (T40), `physics/*`, `duel/*` (T42), `io/*` and the
`File*` classes in androidMain/jvmMain (T39), any Gradle file, `androidApp/**`, `shared/**`.

## Contract
```kotlin
/** Up to three pictures of one character. [base] is the pipeline's sprite and is not stored here. */
class SpriteViews private constructor(
    val base: SpriteView,                    // which side the sprite itself shows
    val baseFacesRight: Boolean,             // a SIDE base sprite looks to the picture's right
    val drawn: Map<SpriteView, ByteArray>,   // matted PNGs of the other views; never contains [base]
) {
    val available: Set<SpriteView>           // drawn.keys + base
    fun files(): Map<String, ByteArray>      // "view-front.png" / "view-side.png" / "view-back.png"
    companion object {
        val NONE: SpriteViews                // base SIDE, faces left, nothing drawn = today's behaviour
        const val MAX_SIDE = 768
        fun of(base: SpriteView, baseFacesRight: Boolean, drawn: Map<SpriteView, ByteArray>): SpriteViews
        fun fromFiles(base: SpriteView, baseFacesRight: Boolean, files: Map<String, ByteArray>): SpriteViews  // other names ignored
        fun fileName(view: SpriteView): String
        /** The views to ask Muse for when the sprite shows [base]: the other two, BACK first. */
        fun missing(base: SpriteView): List<SpriteView>
    }
}
// content equality + hashCode like PoseFrames, so a saved character round-trips in tests

// Changed after review (2026-11-11): one input for the camera (no separate forward/right to get the hand wrong) and
// the hysteresis carried on the bearing sector, not on the picture shown.
data class ViewChoice(val view: SpriteView, val mirrored: Boolean, val sector: SpriteView)

object ViewPicker {
    const val HYSTERESIS_DEG = 10f
    /**
     * [facing] = where the character looks (CharacterState.facing); [toCamera] = camera position minus character
     * position in the plane. F = -toCamera normalised, screen-right R = (-F.v, F.u) (right-handed plane frame: u =
     * anchor X, v = anchor Z; a camera looking along +v has screen-right -u, the host's `(cos y, -sin y)`).
     *   |θ| <= 45 deg BACK, >= 135 deg FRONT, else SIDE, θ = atan2(f.R, f.F); previous.sector widened by HYSTERESIS_DEG.
     *   Picture: the sector's view, else SIDE, else the base. Mirror: as before (real FRONT/BACK never; SIDE or a
     *   standing-in base by drawn direction vs screen direction, 0.15 dead band).
     * Degenerate (toCamera < 1 cm, zero or NaN facing): previous, else the base unmirrored.
     */
    fun pick(facing: Vec2, toCamera: Vec2, previous: ViewChoice?, views: SpriteViews): ViewChoice
}
```
`MetaModels`:
```kotlin
/** Muse Image edit: the matted [sprite] redrawn as seen from [view], with the style [refs]; PNG on Muse's opaque
 *  white background, matted by the caller. Metered under Jobs.MUSE_VIEW. Default body throws MetaError.Provider like
 *  drawPose, so implementations outside :core keep compiling. Every implementation and decorator in core/meta forwards it. */
suspend fun drawView(ref: String, sprite: ByteArray, refs: List<ByteArray>, view: SpriteView): ByteArray
```
- `Jobs.MUSE_VIEW = "muse-view"` (also the fixture folder; fixture `outputs` named `<ref>.view-<view>.png`), in `Jobs.ALL`, priced like `MUSE_POSE`.
- `ViewPrompts.view(view)`: same character, identical colours, outline and proportions, seen from the front / from
  behind / from the side looking right; keep the drawing style of the first image; plain white background. Reuse the
  request builder `drawPose` uses (same endpoint, same shape).
- `CharacterPipeline(spriteViews: Boolean = false, ...)`: off = no call, no stage, nothing drawn; `CharacterResult.views` is
  `SpriteViews.of(sheet)` (base and direction from the sheet, changed after review: was `NONE`).
  On: after the stylize loop (and after the sheet is known: `sheet.baseView ?: SIDE`, `sheet.facesRight ?: false`),
  stage `views`: one `drawView` per `SpriteViews.missing(base)`, concurrently with the `frames` stage and bounded by
  the same concurrency limit; each result matted exactly as a pose frame is; a failed or unmattable view is simply
  absent. The stage is recorded ok=true when at least one view came back. A character never fails because of views.
- `CharacterResult.views: SpriteViews = SpriteViews.NONE`.
- Storage without touching `CharacterFolder`: the view files travel in the existing `frames` map (`PoseFrames.fromFiles`
  already ignores other names). `SavedCharacters` writes `frames.files() + views.files()` and reads both back.
- Bundle: `BundleEntry.views: Map<SpriteView, String>? = null` (relative paths `<ref>/views/<view>.png`), written only
  when non-empty so existing `index.json` files stay byte-identical; `Bundle.viewPath(ref, view)`.
- CLI: `views --ref <ref> [--fixtures dir | --live] [--record] [--out dir]` draws the missing views for one bundled or
  fixture character and writes `<out>/<ref>/view-*.png` plus `contact.png` (base + drawn views side by side, labelled),
  the way `PoseSpike` does for frames. `--live` uses the key exactly as the other commands do (never print it).

## Tests required
- `pickFrontSideBackByBearing` — the three sectors, both screen directions for SIDE
- `pickHoldsPreviousInsideHysteresis` / `pickSwitchesPastHysteresis`
- `pickFallsBackWhenAViewIsMissing` — BACK wanted, only base SIDE -> base, mirrored by the SIDE rule
- `pickMirrorsSideByDrawnDirection` — drawn SIDE (right) vs base SIDE facing left
- `pickDegenerateInputKeepsPrevious`
- `spriteViewsNeverStoreTheBase`, `spriteViewsFilesRoundTrip`, `fromFilesIgnoresPoseFrames`
- `pipelineDrawsMissingViewsWhenOn` / `pipelineOffMakesNoViewCall` / `failedViewNeverFailsTheCharacter` (FakeModels)
- `savedCharacterKeepsViewsNextToFrames` — write + read through `MemoryCharacterFolder`
- `bundleIndexWithoutViewsIsUnchanged` — encode today's entry: no `views` key
- `fixtureApiReplaysViewsByName`, `recordingModelsForwardDrawView`, `offlineGuardForwardsDrawView`
Tests not required: the CLI argument parser, contact-sheet pixels, live calls.

## Done when
- `./gradlew :core:jvmTest :cli:compileKotlin` green
- `./gradlew :cli:run --args="views --ref sample-01 --fixtures docs/api-samples --out build/t41-views" -q` writes the files
- `./gradlew :cli:run --args="character --fixtures docs/api-samples --out build/t41-runs --all" -q` unchanged
- No file outside "Owns" changed

## Result (filled on acceptance)
- after review (merged with 9efa471, T39/T40/T43/T44): `:core:jvmTest` 538 green (532 at the merge + 6: ViewPickerTest
  8 -> 11, SpriteViewsStageTest 5 -> 6, RecordingModelsTest +2); `scripts/verify.sh` OK (shared 30, android 2);
  `cli views --ref sample-01`: back + side, exit 0; `cli character --all`: sprites, stages and costs identical to the
  merge commit 641579f.
- measured (cloud session 2026-11-11, no key, no device): `:core:jvmTest` 413 green (388 + 25 new: SpriteViewsTest 4,
  ViewPickerTest 8, ViewFixtureTest 5, SpriteViewsStageTest 5, BundleViewsTest 2, SavedViewsTest 1);
  `scripts/verify.sh` OK (includes `:core:compileKotlinIosSimulatorArm64` + its test klib, `:cli:compileKotlin`,
  `:androidApp:assembleDebug`; shared 26, android 2). `cli views --ref sample-01 --fixtures docs/api-samples --out
  build/t41-views`: 2/2 views (back, front) matted, contact.png + README.md, exit 0. `cli character --all` (fixtures):
  sprites byte-identical and every stage / cost identical to the same run at 1b52cbb (only timings differ).
- ViewPicker as built after review (the first version keyed hysteresis on the picture shown, so a base standing in for
  a missing view flickered its mirror at a boundary, and took separate forward / right vectors whose hand the tests got
  wrong): `pick(facing, toCamera, previous, views)`. F = -toCamera normalised (the line of sight to THIS character),
  screen-right R = (-F.v, F.u), the host's `(cos y, -sin y)`; θ = atan2(f·R, f·F); sector BACK |θ| <= 45°, FRONT >= 135°,
  else SIDE, the previous `ViewChoice.sector` widened by 10° (inclusive), independent of which pictures exist. Picture:
  the sector's view, else SIDE, else the base. Mirror: real FRONT/BACK never; SIDE or a standing-in base when
  (f·R > 0) != drawn direction (drawn SIDE = right, base = baseFacesRight); |f̂·R| <= 0.15 keeps the previous flag of the
  same picture, else unmirrored. toCamera < 1 cm or a zero/NaN facing -> previous, else the base unmirrored. 18 shared
  `ViewChoice`s, no allocation. Tests: the host formula (CharacterNode.kt:211-219) as a frame-by-frame oracle over a
  2000-frame walk for both base directions; 0..360..0 sweeps and +-1° wobbles at every boundary for six view sets
  (FRONT{BACK}, FRONT{}, BACK{FRONT}, SIDE{}, SIDE-right{BACK}, full): at most one change per crossing, none closer
  than 10°; a steady bearing gives one choice.
- Changed from the contract after review: `CharacterResult.views` is `SpriteViews.of(sheet)` (base and direction from
  the sheet) even with views off, the same rule `SavedCharacters.load` uses, so a T40 sheet `{view: front, faces: right}`
  picks the same before and after a restart; `NONE` only means "no sheet information" (a v1.0 sheet still gives NONE).
  `RecordingModels.sheetJson` now writes `stats`, `why`, `view`, `faces` in the model-reply shape when the sheet has
  them (a sheet without them records byte for byte what it did). The muse-view sample gained `sample-01.view-side.png`
  because sample-01's T40 sheet is a FRONT base looking right (the CLI now draws back + side for it).
- For T50 (host): the app's `DeferredModels` (androidApp/.../di/AndroidCaptureHost.kt:517-530) forwards neither
  `drawView` nor `drawPose`; it must before views or frames are turned on in the app, or every call is a failed stage.
  A character can now carry up to 7 pictures (sprite + 4 frames + 2 views), each at most 768 px: about 7 x 2.36 MB =
  16.5 MB of RGBA texture per character. The host passes `toCamera = cameraLocal - state.pos` and hands the last
  `ViewChoice` back as `previous`; an iOS host must map ARKit's anchor-local X / Z to u / v exactly as PlaneCoords.kt does.
- Choices the brief left open: bundle view files are `<ref>/views/back.png` (view name, not `view-back.png`); the CLI also
  writes `<out>/<ref>/README.md` (the human-answer table, like PoseSpike) and keeps an unmattable view as
  `view-<view>.raw.png` so the human can still judge Muse; `--record` takes an optional dir (default `--fixtures`) and
  rewrites `muse-view/meta.json` from the measured calls; the CLI draws one view at a time, the pipeline two at a time
  sharing the frames' permits. `CostTable.viewUsd = 0.010`.
- cut/deferred: G3 (the live spike and the human verdict) is the user's, on the Mac:
  `./gradlew :cli:run --args="views --live --ref <ref> --record --out build/views"` for >= 5 bundled refs (~$0.02 each,
  `--budget-usd` default 0.05), then fill each `build/views/<ref>/README.md`. No host change (T50).
