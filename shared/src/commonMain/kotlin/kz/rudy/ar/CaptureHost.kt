package kz.rudy.ar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.ui.screens.BundledCharacter

/**
 * What :androidApp injects into [App] for T03 in-app capture. :shared never touches the camera, the network or
 * `BuildConfig`; it only drives these hooks from the Capture and Processing screens. Null host = demo shell.
 *
 * Photo callbacks deliver upright JPEG/PNG bytes with the long side <= 2048 px, or null when the child cancelled
 * (or the image could not be read). They may arrive on any thread.
 */
interface CaptureHost {
    /** Opens the system camera. */
    fun takePhoto(onPhoto: (ByteArray?) -> Unit)

    /** Opens the photo picker (no permission needed). */
    fun pickPhoto(onPhoto: (ByteArray?) -> Unit)

    /**
     * Runs `CharacterPipeline` on [photo] off the main thread. [onEvent] is called in order on the pipeline's
     * thread; it must not throw. Throws (after writing metrics) when the character fails, including a missing key.
     */
    suspend fun makeCharacter(photo: ByteArray, onEvent: (PipelineEvent) -> Unit): CharacterResult

    /** Play is on screen with the captured character: the host logs shutter -> Play for the Day 3 gate. */
    fun playEntered()

    /**
     * Characters shipped in the APK (CLI-generated, `assets/bundled/index.json`), best first. Each carries its
     * sprite PNG and sheet so picking one works offline. Empty = none shipped (the screen shows "Nothing yet").
     */
    fun bundled(): List<BundledCharacter> = emptyList()

    /** The app session's metrics run so far (characters made on this phone), or null before the first one. */
    fun latestRun(): MetricsRun? = null

    /**
     * Writes [latestRun] as `<runId>.json` somewhere the child/parent can find it (Downloads). Returns the
     * user-visible location, or null on failure. Never throws.
     */
    suspend fun exportRun(): String? = null

    /**
     * T20: true while the host's last model call failed on the network (and until one succeeds). App collects it:
     * the Play HUD shows the Leaf "Offline" badge and disables Scan, Capture disables Photo and shows the note.
     * The default (never offline) is one shared flow, so collecting it is stable across recompositions.
     */
    val offline: StateFlow<Boolean> get() = NeverOffline
}

/** The default [CaptureHost.offline] for hosts that do not track the network (and for the demo shell). */
private val NeverOffline: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
