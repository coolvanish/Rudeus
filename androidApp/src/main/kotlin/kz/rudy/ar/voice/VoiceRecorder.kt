package kz.rudy.ar.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream

// HAND-REVIEW: T24 — push-to-talk capture. Raw 16 kHz mono PCM16 from AudioRecord (MetaApi.transcribe wraps it in the
// WAV header the endpoint wants), read on one background thread into a buffer capped at MAX_BYTES (5 s). At the cap
// the thread stops the AudioRecord itself, so a held button never keeps the mic open. Nothing here throws out: every
// failure is a log line (tag RudeusGate) and false / null. Callers check RECORD_AUDIO before start() (the host does).

private const val TAG = "RudeusGate"

/** One push-to-talk recording at a time; [start] and [stop] are synchronized (main thread and IO). */
internal class VoiceRecorder {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var out: ByteArrayOutputStream? = null
    @Volatile private var running = false

    /** Starts recording. False when already recording, the mic is busy or AudioRecord could not be created. */
    fun start(): Boolean {
        synchronized(lock) { return startLocked() }
    }

    @SuppressLint("MissingPermission") // the host checks RECORD_AUDIO before calling; a SecurityException is caught
    private fun startLocked(): Boolean {
        if (record != null) return false
        try {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (min <= 0) {
                Log.w(TAG, "voice_record_unsupported min_buffer=$min")
                return false
            }
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(min, CHUNK_BYTES * 2))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Log.w(TAG, "voice_record_init_failed")
                return false
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                Log.w(TAG, "voice_record_busy")
                return false
            }
            val buffer = ByteArrayOutputStream(MAX_BYTES)
            record = rec
            out = buffer
            running = true
            thread = Thread({ readLoop(rec, buffer) }, "rudeus-voice").also { it.start() }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "voice_record_start_failed ${e::class.simpleName}")
            releaseLocked()
            return false
        }
    }

    /** Stops and releases the recorder; the PCM bytes recorded (<= [MAX_BYTES], even length), or null when idle. */
    fun stop(): ByteArray? {
        synchronized(lock) { return stopLocked() }
    }

    private fun stopLocked(): ByteArray? {
        val rec = record ?: return null
        running = false
        try {
            rec.stop() // unblocks a pending read
        } catch (e: Exception) {
            Log.w(TAG, "voice_record_stop_failed ${e::class.simpleName}")
        }
        try {
            thread?.join(JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val bytes = out?.toByteArray()
        releaseLocked()
        return bytes?.let { if (it.size % 2 == 0) it else it.copyOf(it.size - 1) }
    }

    private fun readLoop(rec: AudioRecord, buffer: ByteArrayOutputStream) {
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            while (running && buffer.size() < MAX_BYTES) {
                val n = rec.read(chunk, 0, minOf(chunk.size, MAX_BYTES - buffer.size()))
                if (n < 0) {
                    if (running) Log.w(TAG, "voice_record_read_error code=$n")
                    break
                }
                buffer.write(chunk, 0, n)
            }
            if (running && buffer.size() >= MAX_BYTES) {
                Log.i(TAG, "voice_record_capped bytes=${buffer.size()}")
                rec.stop() // the cap frees the mic; stop() still collects the bytes and releases
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice_record_read_failed ${e::class.simpleName}")
        }
    }

    private fun releaseLocked() {
        running = false
        try {
            record?.release()
        } catch (e: Exception) {
            Log.w(TAG, "voice_record_release_failed ${e::class.simpleName}")
        }
        record = null
        thread = null
        out = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** 5 s of 16 kHz mono 16-bit PCM. */
        const val MAX_BYTES = SAMPLE_RATE * 2 * 5
        /** 100 ms per read. */
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10
        private const val JOIN_MS = 500L
    }
}
