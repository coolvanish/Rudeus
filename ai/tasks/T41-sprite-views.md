# T41 — sprite-views

status: todo
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

data class ViewChoice(val view: SpriteView, val mirrored: Boolean)

object ViewPicker {
    const val HYSTERESIS_DEG = 10f
    /**
     * Which picture to show. All vectors are in the plane (u, v) and need not be unit length:
     * [facing] = where the character looks (CharacterState.facing), [cameraForward] = the camera's view direction
     * projected onto the plane, [cameraRight] = the camera's right axis projected onto the plane.
     *   facing along cameraForward (within 45 deg)  -> BACK   (it walks away from us)
     *   facing against cameraForward (within 45 deg) -> FRONT (it walks towards us)
     *   otherwise SIDE, looking screen-right when facing . cameraRight > 0
     * [previous] holds until the angle is HYSTERESIS_DEG past a boundary, so a character walking along a boundary
     * does not flicker. A wanted view that [views] lacks falls back to SIDE, then to the base sprite.
     * mirrored = the host flips the quad horizontally. A real FRONT or BACK picture is never mirrored. A SIDE
     * picture, and the base sprite whenever it stands in for a view the character lacks, is mirrored when the
     * direction it was drawn looking (drawn SIDE views always look right; the base uses baseFacesRight) differs
     * from the screen direction (facing . cameraRight > 0 = screen-right). That is today's flip rule.
     * Degenerate input (zero facing or zero camera vectors: the camera is straight above) returns [previous], or the
     * base sprite unmirrored when there is none.
     */
    fun pick(facing: Vec2, cameraForward: Vec2, cameraRight: Vec2, previous: ViewChoice?, views: SpriteViews): ViewChoice
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
- `CharacterPipeline(spriteViews: Boolean = false, ...)`: off = no call, no stage, `CharacterResult.views == SpriteViews.NONE`.
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
- measured: ...
- cut/deferred: G3 (the live spike and the human verdict) is the user's, on the Mac
