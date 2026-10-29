package kz.rudy.ar.di

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
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
import kz.rudy.ar.core.meta.SegmentResult
import kz.rudy.ar.core.meta.SheetParser
import kz.rudy.ar.core.metrics.MetricsLog
import kz.rudy.ar.core.metrics.MetricsRun
import kz.rudy.ar.core.pipeline.CharacterPipeline
import kz.rudy.ar.core.pipeline.CharacterResult
import kz.rudy.ar.core.pipeline.PipelineEvent
import kz.rudy.ar.core.pipeline.StyleGate
import kz.rudy.ar.scan.TableScanner
import kz.rudy.ar.ui.screens.BundledCharacter
import org.json.JSONObject
import java.io.File

// HAND-REVIEW: T03 (c)(e)(f) — the only place that reads BuildConfig.META_API_KEY. The key goes straight into
// MetaConfig (whose toString hides it) and is never logged. One MetricsLog per app session: every character appends
// to files/runs/<runId>.json (rewritten after each run, also on failure or cancel), source "app", device "pixel",
// so scripts/device.sh pull-runs collects it. A blank key throws before any call -> App shows the ERROR state with
// "pick a ready one" (the FixtureApi fallback is T06). Style refs come from assets/style-refs in cull.json order.
// HAND-REVIEW: T14 (c)(d) — bundled characters come from assets/bundled (a copy of docs/bundled, 7.6 MB: 16 sprites +
// sheets + index.json), parsed once on first `bundled()` call; `exportRun` writes the session run through
// MediaStore.Downloads (API >= 29, no permission) or the app's external files dir below that.
// HAND-REVIEW: T04 — also the AR view's TableScanner: the scan's SAM call goes through the same MetaApi (metered
// under Jobs.SAM_TABLE) and a successful scan lands in the same run file (`scans[]`), so a bundled-character session
// with only scans now has a run to show/export (`latestRun` counts scans too).

private const val TAG = "RudeusGate"
private const val STYLE_REFS_DIR = "style-refs"
private const val RUNS_DIR = "runs"
private const val BUNDLED_DIR = "bundled"

/** Where the exported run lands on API >= 29 (relative to the primary external volume), as the player sees it in Files. */
private const val EXPORT_RELATIVE_DIR = "Download/Rudeus"

class AndroidCaptureHost(context: Context, private val intake: PhotoIntake) : CaptureHost, TableScanner {
    private val app = context.applicationContext
    private val metrics = MetricsLog(source = "app", provider = Provider.META.metricsName, device = "pixel")
    private val sink = FileMetricsSink(File(app.filesDir, RUNS_DIR))
    @Volatile private var characters = 0 // written on IO (makeCharacter), read on main (latestRun)
    @Volatile private var scans = 0 // written on IO (recordScan), read on main (latestRun)

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
            // HAND-REVIEW: review fix — a plain "01", "02"… ref: SheetParser.fallback names a character from its ref
            // ("app/<run>/1" became "App"; "01" gives the default "Dos"), and the CLI only allows refs without '/'.
            val ref = (++characters).toString().padStart(2, '0')
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
        // Once per capture: coming back to Play from Metrics must not log a second, inflated number.
        val shutterAt = intake.takeShutterAtMs()
        if (shutterAt > 0L) Log.i(TAG, "capture_to_play_ms=${SystemClock.elapsedRealtime() - shutterAt}")
    }

    /** Process-level cache, preloaded by MainActivity.onCreate; the first call blocks only for what is left. */
    override fun bundled(): List<BundledCharacter> = BundledAssets.get(app)

    /** The run so far once a character was started or a scan recorded this session, else null. */
    override fun latestRun(): MetricsRun? = if (characters > 0 || scans > 0) metrics.toRun() else null

    // ---- TableScanner (T04): the AR view's scan goes through the same MetaApi and MetricsLog. ----

    override suspend fun segment(ref: String, jpeg: ByteArray, prompts: List<String>): SegmentResult =
        api.segment(ref, jpeg, prompts)

    /**
     * Day 4 gate row: `withinCm` stays empty here; the human fills the ruler numbers into the brief.
     * HAND-REVIEW: review fix — the append and the JSON snapshot run on Main, where `latestRun()` reads the same
     * lists (MetricsLog's non-cost methods are single-threaded); only the file write goes to IO.
     */
    override suspend fun recordScan(objects: Int, ms: Long) {
        val json = withContext(Dispatchers.Main.immediate) {
            metrics.recordScan(objects, ms, withinCm = emptyList())
            scans++
            metrics.toJson()
        }
        withContext(Dispatchers.IO) {
            try {
                sink.write("${metrics.runId}.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "metrics write failed: ${e.message}")
            }
        }
    }

    /** Never throws (CaptureHost contract): everything, the JSON encoding included, is inside the try. */
    override suspend fun exportRun(): String? = withContext(Dispatchers.IO) {
        try {
            val run = latestRun() ?: return@withContext null
            val name = "${run.runId}.json"
            val json = metrics.toJson() // same run as latestRun(); :core owns the JSON (no serialization dep here)
            val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportViaMediaStore(name, json)
            else exportToExternalFiles(name, json)
            where?.also { Log.i(TAG, "export=$it bytes=${json.length}") }
        } catch (e: Exception) {
            Log.w(TAG, "export failed: ${e.message}")
            null
        }
    }

    /**
     * API >= 29: a pending row in MediaStore.Downloads under Download/Rudeus, written through the resolver, then
     * published. No storage permission is needed for files the app creates there. A second export of the same run
     * gets a MediaStore-unique name ("<runId> (1).json"), so the returned path carries the DISPLAY_NAME queried back.
     * HAND-REVIEW: review fix — a missing stream or a failed publish throws into the catch, which deletes the pending
     * row; before, a null stream returned null and left an invisible pending row behind.
     */
    private fun exportViaMediaStore(name: String, json: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, EXPORT_RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val os = resolver.openOutputStream(uri) ?: error("no output stream for $uri")
            os.use { it.write(json.toByteArray()) }
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            check(published == 1) { "publish updated $published rows" }
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no half-written pending row left behind
            throw e
        }
        val actualName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        return "$EXPORT_RELATIVE_DIR/$actualName"
    }

    /** API 24–28: the app's own external files dir (no permission); the returned path is the absolute file path. */
    private fun exportToExternalFiles(name: String, json: String): String? {
        val dir = File(app.getExternalFilesDir(null) ?: return null, RUNS_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return null
        val file = File(dir, name)
        file.writeText(json)
        return file.absolutePath
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

/**
 * Process-level cache of the bundled characters. [preload] starts the parse on a background thread (MainActivity
 * .onCreate, before setContent); [get] from composition then blocks only for what is still left, since `lazy` is
 * synchronized. One instance per process: the assets never change.
 */
object BundledAssets {
    @Volatile private var cache: Lazy<List<BundledCharacter>>? = null

    private fun lazyFor(app: Context): Lazy<List<BundledCharacter>> =
        cache ?: synchronized(this) {
            cache ?: lazy {
                val t0 = SystemClock.elapsedRealtime()
                loadBundled(app.assets).also { Log.i(TAG, "bundled=${it.size} bundled_ms=${SystemClock.elapsedRealtime() - t0}") }
            }.also { cache = it }
        }

    fun preload(context: Context) {
        val l = lazyFor(context.applicationContext)
        if (!l.isInitialized()) Thread({ l.value }, "rudeus-bundled").start()
    }

    fun get(context: Context): List<BundledCharacter> = lazyFor(context.applicationContext).value
}

/**
 * assets/bundled/index.json (`characters[]`: ref, sprite, sheet, gatePassed) -> [BundledCharacter]s, gate-passed
 * ones first, then the rest, each group in index order. A character whose sprite or sheet is missing or unreadable
 * is skipped with a log line; a missing or broken index yields an empty list. The sheet is parsed by :core's
 * SheetParser (same normalisation as the pipeline: 5 lines kk/ru, unknown ability -> JUMP).
 */
private fun loadBundled(assets: AssetManager): List<BundledCharacter> {
    val entries = try {
        val arr = JSONObject(assets.open("$BUNDLED_DIR/index.json").bufferedReader().use { it.readText() }).getJSONArray("characters")
        List(arr.length()) { arr.getJSONObject(it) }
    } catch (e: Exception) {
        Log.w(TAG, "bundled index unreadable: ${e.message}")
        return emptyList()
    }
    val loaded = entries.mapNotNull { o ->
        val ref = o.optString("ref")
        try {
            val spritePath = o.optString("sprite", "$ref/sprite.png")
            val sheetPath = o.optString("sheet", "$ref/sheet.json")
            val sprite = assets.open("$BUNDLED_DIR/$spritePath").use { it.readBytes() }
            // A sprite BitmapFactory cannot size is skipped here, not discovered as a decode failure on the table.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(sprite, 0, sprite.size, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "sprite undecodable: $spritePath" }
            val sheet = SheetParser.parse(assets.open("$BUNDLED_DIR/$sheetPath").bufferedReader().use { it.readText() })
            Pair(o.optBoolean("gatePassed", false), BundledCharacter(id = ref, sheet = sheet, thumbnail = null, spritePng = sprite))
        } catch (e: Exception) {
            Log.w(TAG, "bundled skipped ref=$ref: ${e.message}")
            null
        }
    }
    return loaded.filter { it.first }.map { it.second } + loaded.filter { !it.first }.map { it.second }
}
