package kz.rudy.ar

import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent

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
}
