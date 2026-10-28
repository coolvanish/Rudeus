package kz.rudy.ar.di

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.rudy.ar.BuildConfig
import kz.rudy.ar.CaptureHost
import kz.rudy.ar.capture.AndroidImages
import kz.rudy.ar.capture.PhotoIntake
import kz.rudy.ar.core.io.FileMetricsSink
import kz.rudy.ar.core.meta.MetaApi
import kz.rudy.ar.core.meta.MetaConfig
import kz.rudy.ar.core.meta.Provider
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.pipeline.CharacterPipeline
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.core.pipeline.StyleGate
import org.json.JSONObject
import java.io.File

// HAND-REVIEW: T03 (c)(e)(f) — the only place that reads BuildConfig.META_API_KEY. The key goes straight into
// MetaConfig (whose toString hides it) and is never logged. One MetricsLog per app session: every character appends
// to files/runs/<runId>.json (rewritten after each run, also on failure or cancel), source "app", device "pixel",
// so scripts/device.sh pull-runs collects it. A blank key throws before any call -> App shows the ERROR state with
// "pick a ready one" (the FixtureApi fallback is T06). Style refs come from assets/style-refs in cull.json order.

private const val TAG = "RudeusGate"
private const val STYLE_REFS_DIR = "style-refs"
private const val RUNS_DIR = "runs"

class AndroidCaptureHost(context: Context, private val intake: PhotoIntake) : CaptureHost {
    private val app = context.applicationContext
    private val metrics = MetricsLog(source = "app", provider = Provider.META.metricsName, device = "pixel")
    private val sink = FileMetricsSink(File(app.filesDir, RUNS_DIR))
    private var characters = 0

    private val styleRefs: List<ByteArray> by lazy {
        loadStyleRefs(app.assets).also { Log.i(TAG, "style_refs=${it.size}") }
    }

    /** Built on first use; `check` fails fast (and loudly, without the key) when the build had no key. */
    private val api: MetaApi by lazy {
        val key = BuildConfig.META_API_KEY
        check(key.isNotBlank()) { "META_API_KEY missing: put it in local.properties and rebuild" }
        MetaApi(MetaConfig(apiKey = key), costMeter = metrics)
    }

    override fun takePhoto(onPhoto: (ByteArray?) -> Unit) = intake.takePhoto(onPhoto)

    override fun pickPhoto(onPhoto: (ByteArray?) -> Unit) = intake.pickPhoto(onPhoto)

    override suspend fun makeCharacter(photo: ByteArray, onEvent: (PipelineEvent) -> Unit): CharacterResult =
        withContext(Dispatchers.IO) {
            val ref = "app/${metrics.runId}/${++characters}"
            val t0 = SystemClock.elapsedRealtime()
            val pipeline = CharacterPipeline(
                models = api,
                gate = StyleGate(),
                metrics = metrics,
                rasterDecoder = AndroidImages::decode,
                cutoutComposer = AndroidImages::cutout,
                spriteMatter = AndroidImages::matte,
                imageResizer = AndroidImages::downscale,
                onEvent = { event -> logEvent(event, t0); onEvent(event) },
            )
            try {
                pipeline.run(ref, photo, styleRefs, description = "").also {
                    Log.i(
                        TAG,
                        "pipeline_ms=${SystemClock.elapsedRealtime() - t0} gate=${it.gateScore} passed=${it.gatePassed} " +
                            "attempts=${it.attempts} sprite_bytes=${it.sprite.size} name=${it.sheet.name} run=${metrics.runId}",
                    )
                }
            } finally {
                try {
                    sink.write("${metrics.runId}.json", metrics.toJson())
                } catch (e: Exception) {
                    Log.w(TAG, "metrics write failed: ${e.message}")
                }
            }
        }

    override fun playEntered() {
        Log.i(TAG, "capture_to_play_ms=${SystemClock.elapsedRealtime() - intake.shutterAtMs}")
    }

    private fun logEvent(event: PipelineEvent, t0: Long) {
        val at = SystemClock.elapsedRealtime() - t0
        when (event) {
            is PipelineEvent.StageStarted -> Log.i(TAG, "stage_start=${event.stage} attempt=${event.attempt} at_ms=$at")
            is PipelineEvent.StageFinished -> Log.i(TAG, "stage_end=${event.stage} attempt=${event.attempt} ok=${event.ok} ms=${event.ms} at_ms=$at")
            is PipelineEvent.GateScored -> Log.i(TAG, "gate_scored attempt=${event.attempt} score=${event.score} passed=${event.passed} at_ms=$at")
            is PipelineEvent.Finished -> Unit // summarised by the caller
            is PipelineEvent.Failed -> Log.w(TAG, "pipeline_failed at_ms=$at: ${event.message}")
        }
    }
}

/** Every png/jpg under assets/style-refs, best first: cull.json `kept` order, then the rest by name (as the CLI). */
private fun loadStyleRefs(assets: AssetManager): List<ByteArray> {
    val names = assets.list(STYLE_REFS_DIR).orEmpty().filter {
        it.endsWith(".png", ignoreCase = true) || it.endsWith(".jpg", ignoreCase = true)
    }
    val kept: List<String> = try {
        val arr = JSONObject(assets.open("$STYLE_REFS_DIR/cull.json").bufferedReader().use { it.readText() }).getJSONArray("kept")
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
    return names
        .sortedWith(compareBy({ kept.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
        .map { name -> assets.open("$STYLE_REFS_DIR/$name").use { it.readBytes() } }
}
