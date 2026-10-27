package kz.rudy.ar.ar

import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.recording.ARRecorder
import kz.rudy.ar.core.domain.Vec2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// HAND-REVIEW: T01 Stream C — Day 1 gate probes. Logcat-only instrumentation (tag RudeusGate) plus a Rec/Stop
// button over SceneView's ARRecorder (ARCore Recording API). No game logic here. Delete this file and the few
// probe.* / recorder.* hooks in RudeusArView once the gate table in ai/tasks/T01-day1-gate-probes.md is filled.
// Read with: scripts/device.sh log   (filter includes RudeusGate:I)

internal const val GATE_TAG = "RudeusGate"
private const val ANCHOR_LOG_PERIOD_MS = 10_000L

private fun nowMs(): Long = SystemClock.elapsedRealtime()

private fun Pose.fmt(): String = "(%.4f,%.4f,%.4f)".format(Locale.US, tx(), ty(), tz())

/**
 * Plain mutable holder — written from the AR frame callback and the tap handler, never Compose state,
 * so nothing here can trigger a recomposition per frame.
 */
internal class GateProbe {
    private var sessionStartMs = 0L
    private var planeLogged = false
    private var depthLogged = false
    private var anchorPlacedMs = 0L
    private var lastAnchorLogMs = 0L

    /** Mirror of RudeusArView's `anchor` state so the frame callback never reads a Compose snapshot. */
    private var anchor: Anchor? = null

    /** Criterion "plane < 10 s": the clock starts at the first session resume (or first frame, whichever is first). */
    fun onSessionResumed() {
        if (sessionStartMs == 0L) {
            sessionStartMs = nowMs()
            Log.i(GATE_TAG, "session_resumed")
        }
    }

    /** Criterion "depth occlusion acceptable": logged once from sessionConfiguration. */
    fun onDepthConfigured(supported: Boolean, chosen: Config.DepthMode) {
        if (depthLogged) return
        depthLogged = true
        Log.i(GATE_TAG, "depth_supported=$supported depth_mode=$chosen")
    }

    /**
     * Per frame. Work is trivial: a plane scan only until the first TRACKING horizontal plane is seen
     * (bounded by the < 10 s gate), then one `anchor.pose` read every 10 s.
     */
    fun onFrame(session: Session) {
        val now = nowMs()
        if (sessionStartMs == 0L) sessionStartMs = now
        if (!planeLogged) {
            for (p in session.getAllTrackables(Plane::class.java)) {
                if (p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState == TrackingState.TRACKING) {
                    planeLogged = true
                    Log.i(GATE_TAG, "plane_detected_ms=${now - sessionStartMs}")
                    break
                }
            }
        }
        val a = anchor ?: return
        if (now - lastAnchorLogMs >= ANCHOR_LOG_PERIOD_MS && a.trackingState == TrackingState.TRACKING) {
            lastAnchorLogMs = now
            Log.i(GATE_TAG, "anchor t_ms=${now - anchorPlacedMs} pos=${a.pose.fmt()}")
        }
    }

    /** Criterion "drift < 1 cm / 60 s": first `anchor` line is t_ms=0; compare pos with the line near t_ms=60000. */
    fun onAnchorPlaced(a: Anchor) {
        anchor = a
        anchorPlacedMs = nowMs()
        lastAnchorLogMs = anchorPlacedMs
        Log.i(GATE_TAG, "anchor t_ms=0 pos=${a.pose.fmt()}")
    }

    /** Criterion "cup footprint 1–2 cm": tap the same cup edge at t=0 and t=60; u/v are metres in the anchor frame. */
    fun onTap(a: Anchor, hitPose: Pose, uv: Vec2) {
        Log.i(
            GATE_TAG,
            "tap u=%.4f v=%.4f world=%s anchorTracking=%s t_ms=%d".format(
                Locale.US, uv.u, uv.v, hitPose.fmt(), a.trackingState, nowMs() - anchorPlacedMs,
            ),
        )
    }
}

/**
 * Rec/Stop toggle for the ARCore Recording API via SceneView 4.31 `ARRecorder`
 * (docs/docs/ar-recording.md). The recorder learns the live Session from `recorder.recordFrame(session)` in
 * onSessionUpdated; `start(file)` calls `Session.startRecording(RecordingConfig(session)
 * .setMp4DatasetFilePath(path).setAutoStopOnPause(true))` on the running session — no pause/resume needed,
 * and with `recordingResolution = null` it does NOT swap the camera config, so the session anchor survives.
 * Output: getExternalFilesDir(null)/recordings/<yyyyMMdd-HHmmss>.mp4  -> scripts/device.sh pull-recordings
 */
@Composable
internal fun GateRecordButton(recorder: ARRecorder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recording = recorder.state == ARRecorder.State.RECORDING
    Button(
        modifier = modifier,
        onClick = {
            if (recording) {
                val file = recorder.stop()
                Log.i(GATE_TAG, "recording=${file?.absolutePath} stopped state=${recorder.state}")
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(File(context.getExternalFilesDir(null), "recordings"), "$stamp.mp4")
                if (recorder.start(file)) {
                    Log.i(GATE_TAG, "recording=${file.absolutePath} started")
                } else {
                    Log.w(GATE_TAG, "recording_failed=${recorder.errorMessage}")
                }
            }
        },
    ) {
        Text(if (recording) "Stop" else "Rec")
    }
}
