// HAND-REVIEW: T23 — SoundPool wrapper; never throws into the frame loop, no-op until loaded.
package kz.rudy.ar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import kz.rudy.ar.core.domain.CharacterEvent

/**
 * Plays the five generated sounds (the WAVs in `assets/sounds`, see scripts/gen-sounds.py) on character events.
 * A sample that has not finished loading is skipped, the same event plays at most once per [MIN_GAP_MS], and every
 * SoundPool call is guarded: a missing asset or a released pool only means silence.
 */
class Sounds(context: Context) {
    private val pool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }.onFailure { Log.w(TAG, "SoundPool unavailable", it) }.getOrNull()

    /** Sample ids that finished loading successfully (written by the load listener, read by [play]). */
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val ids = HashMap<String, Int>()
    private val lastPlayed = LongArray(CharacterEvent.entries.size) { Long.MIN_VALUE / 2 }

    @Volatile
    private var released = false

    init {
        val p = pool
        if (p != null) {
            p.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
            for (name in NAMES) {
                runCatching {
                    // load() dups the descriptor, so closing the AssetFileDescriptor right after is safe.
                    context.assets.openFd("sounds/$name.wav").use { afd -> ids[name] = p.load(afd, 1) }
                }.onFailure { Log.w(TAG, "sound $name not loaded", it) }
            }
        }
    }

    fun play(event: CharacterEvent) {
        val (name, volume) = when (event) {
            CharacterEvent.PLACED -> "place" to 0.8f
            CharacterEvent.JUMPED -> "jump" to 0.8f
            CharacterEvent.LANDED -> "land" to 0.8f
            CharacterEvent.FELL -> "fall" to 0.6f
            CharacterEvent.BLOCKED -> "blocked" to 0.6f
            CharacterEvent.RESPAWNED -> "place" to 0.4f
            else -> return
        }
        if (released) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayed[event.ordinal] < MIN_GAP_MS) return
        val id = ids[name] ?: return
        if (id !in loaded) return
        lastPlayed[event.ordinal] = now
        runCatching { pool?.play(id, volume, volume, 1, 0, 1f) }
    }

    fun release() {
        if (released) return
        released = true
        loaded.clear()
        runCatching { pool?.release() }
    }

    private companion object {
        const val TAG = "RudeusSounds"
        const val MIN_GAP_MS = 150L
        val NAMES = listOf("place", "jump", "land", "fall", "blocked")
    }
}
