package kz.rudy.ar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.ui.screens.BundledCharacter

/**
 * What :androidApp injects into [App] for T03 in-app capture. :shared never touches the camera, the network or
 * `BuildConfig`; it only drives these hooks from the Capture and Processing screens. Null host = demo shell.
 *
 * Photo results deliver upright JPEG/PNG bytes with the long side <= 2048 px, or null when the child cancelled
 * (or the image could not be read). The active App collects results independently of the launch, so restored
 * Activity results have a consumer.
 */
interface CaptureHost {
    /** One-shot results, buffered by the host until the active App collects them. */
    val photos: Flow<ByteArray?>

    /** Opens the system camera. */
    fun takePhoto()

    /** Opens the photo picker (no permission needed). */
    fun pickPhoto()

    /**
     * Runs `CharacterPipeline` on [photo] off the main thread. [onEvent] is called in order on the pipeline's
     * thread; it must not throw. Throws (after writing metrics) when the character fails, including a missing key.
     * [review] is the pipeline's reviewer: asked after a below-threshold attempt with attempts left, while that sprite is
     * on screen; true ships it, false starts the next attempt.
     */
    suspend fun makeCharacter(
        photo: ByteArray,
        review: suspend (attempt: Int, score: Float) -> Boolean,
        onEvent: (PipelineEvent) -> Unit,
    ): CharacterResult

    /** Play is on screen with the captured character: the host logs shutter -> Play for the Day 3 gate. */
    fun playEntered()

    /**
     * Characters shipped in the APK (CLI-generated, `assets/bundled/index.json`), best first. Each carries its
     * sprite PNG and sheet so picking one works offline. Empty = none shipped (the screen shows "Nothing yet").
     */
    fun bundled(): List<BundledCharacter> = emptyList()

    /** T26: characters made on this phone in earlier runs and kept on disk, newest first. Never throws. */
    suspend fun savedCharacters(): List<BundledCharacter> = emptyList()

    /** T26: keeps [result] on disk for later sessions; its card, or null when it could not be kept. Never throws. */
    suspend fun saveCharacter(result: CharacterResult): BundledCharacter? = null

    /** The app session's metrics run so far (characters made on this phone), or null before the first one. */
    fun latestRun(): MetricsRun? = null

    /**
     * Writes [latestRun] as `<runId>.json` somewhere the child/parent can find it (Downloads). Returns the
     * user-visible location, or null on failure. Never throws.
     */
    suspend fun exportRun(): String? = null

    /**
     * T20: true while the host's last model call failed on the network (and until one succeeds). App collects it:
     * the Play HUD shows the "Offline" badge and Capture shows a note. Photo and Scan remain enabled for retry.
     * The default (never offline) is one shared flow, so collecting it is stable across recompositions.
     */
    val offline: StateFlow<Boolean> get() = NeverOffline

    /** T24: push-to-talk on Play (hold the mic, say "jump" / "go"). Null = no mic button (demo shell, tests). */
    val voice: VoiceHost? get() = null

    /** The table/floor choice from the last run, read once when App starts. Null = never chosen. */
    fun lastSurface(): Surface? = null

    /** The child picked [surface]: the host keeps it for [lastSurface]. */
    fun rememberSurface(surface: Surface) {}
}

/** The default [CaptureHost.offline] for hosts that do not track the network (and for the demo shell). */
private val NeverOffline: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
