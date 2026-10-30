package kz.rudy.ar

/**
 * T24 push-to-talk, implemented by :androidApp (AudioRecord + `MetaModels.transcribe` through the host's
 * `OfflineGuard`). :shared never touches the microphone or the network; the Play HUD only drives these four calls and
 * parses the transcript with `VoiceCommands.parse`. Null `CaptureHost.voice` = no mic button.
 */
interface VoiceHost {
    /** RECORD_AUDIO is granted right now (checked on every press; the system dialog answers asynchronously). */
    fun hasPermission(): Boolean

    /** Shows the system permission dialog; the result arrives through [hasPermission] later. */
    fun requestPermission()

    /** Starts recording. True = recording; false = no permission or the mic is busy. Never throws. */
    fun startListening(): Boolean

    /**
     * Stops recording and transcribes what was heard (at most 5 s of audio) in [lang] (`kk` / `ru` / `en`).
     * The transcript, or null on any failure (nothing recorded, offline, timeout, no key). Never throws.
     */
    suspend fun stopAndTranscribe(lang: String): String?
}
