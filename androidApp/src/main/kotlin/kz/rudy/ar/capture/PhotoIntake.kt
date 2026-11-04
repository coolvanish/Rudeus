package kz.rudy.ar.capture

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kz.rudy.ar.core.pipeline.BatchImport
import java.io.File

// HAND-REVIEW: T03 (a) — photo in via the system camera (ActivityResultContracts.TakePicture into a FileProvider URI
// under cacheDir/capture, authority "<applicationId>.fileprovider", res/xml/file_paths.xml) and the photo picker
// (ActivityResultContracts.PickVisualMedia, no storage permission). NOT the AR frame: the ARCore CPU image is
// 640x480 and the Capture screen has no AR session. The result is decoded off the main thread
// (AndroidImages.preparePhoto: <= 2048 px, EXIF upright, JPEG) and handed back on the main thread.
// HAND-REVIEW: review fix — the manifest declares CAMERA, so ACTION_IMAGE_CAPTURE throws SecurityException until the
// permission is granted (fresh install: the AR screens ask for it later). takePhoto asks first; a denial or a missing
// camera app hands back null instead of crashing.
// HAND-REVIEW: T38 — a third launcher, ActivityResultContracts.PickMultipleVisualMedia(BatchImport.MAX_PHOTOS): the system
// picker itself caps the choice at 7 (the contract needs a fixed maxItems >= 2, so `pickPhotos(max)` cannot widen it). The
// URIs are decoded in parallel on IO through the same loadPhoto (<= 2048 px, upright) and arrive as one list on
// `photoBatches`, unreadable ones left out; a cancelled picker sends an empty list. No shutter time: a batch is never played.

private const val TAG = "RudeusGate"
private const val CAPTURE_DIR = "capture"

/** The two photo sources, created by [rememberPhotoIntake] inside composition (the launchers need it). */
class PhotoIntake internal constructor(
    private val context: Context,
    private val camera: ActivityResultLauncher<Uri>,
    private val picker: ActivityResultLauncher<PickVisualMediaRequest>,
    private val manyPicker: ActivityResultLauncher<PickVisualMediaRequest>,
    private val permission: ActivityResultLauncher<String>,
    private val pending: Pending,
) {
    internal class Pending(var file: File? = null) {
        // Results can arrive before App starts collecting after Activity restoration.
        val results = Channel<ByteArray?>(Channel.BUFFERED)
        val photos = results.receiveAsFlow()
        val batches = Channel<List<ByteArray>>(Channel.BUFFERED)
        val photoBatches = batches.receiveAsFlow()

        companion object {
            val saver = Saver<Pending, String>(
                save = { it.file?.absolutePath.orEmpty() },
                restore = { Pending(it.takeIf(String::isNotEmpty)?.let(::File)) },
            )
        }

        /** `elapsedRealtime` when the last camera/picker result arrived (the child's shutter + confirm). */
        var shutterAtMs: Long = 0L
    }

    val photos get() = pending.photos
    val photoBatches get() = pending.photoBatches

    /** The last shutter time, once: Play logs `capture_to_play_ms` only for the capture that led to it. */
    fun takeShutterAtMs(): Long = pending.shutterAtMs.also { pending.shutterAtMs = 0L }

    fun takePhoto() {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        pending.file = file
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera(context, camera, pending) else permission.launch(Manifest.permission.CAMERA)
    }

    fun pickPhoto() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Up to `min(max, BatchImport.MAX_PHOTOS)` photos; the launcher's cap is fixed, a larger [max] only logs. */
    fun pickPhotos(max: Int) {
        if (max > BatchImport.MAX_PHOTOS) Log.w(TAG, "pick_photos max=$max capped=${BatchImport.MAX_PHOTOS}")
        manyPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** Opens the system camera; a missing camera app delivers a cancelled result. */
private fun launchCamera(context: Context, camera: ActivityResultLauncher<Uri>, pending: PhotoIntake.Pending) {
    val file = pending.file ?: return
    try {
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no camera app: ${e.message}")
        pending.file = null
        pending.results.trySend(null)
    }
}

@Composable
fun rememberPhotoIntake(): PhotoIntake {
    val context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()
    val pending = rememberSaveable(saver = PhotoIntake.Pending.saver) { PhotoIntake.Pending() }

    /** Runs [load] on IO and queues the result for the current App, including after process recreation. */
    fun deliver(load: () -> ByteArray?) {
        pending.shutterAtMs = SystemClock.elapsedRealtime()
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val bytes = withContext(Dispatchers.IO) { runCatching(load).getOrNull() }
            Log.i(TAG, "photo_ready_ms=${SystemClock.elapsedRealtime() - t0} photo_bytes=${bytes?.size ?: 0}")
            pending.results.send(bytes)
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pending.file
        pending.file = null
        deliver {
            try {
                if (saved && file != null && file.isFile) AndroidImages.preparePhoto(file.readBytes()) else null
            } finally {
                file?.delete()
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        deliver { uri?.let { AndroidImages.loadPhoto(context.contentResolver, it) } }
    }
    val manyPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(BatchImport.MAX_PHOTOS)) { uris ->
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val photos = withContext(Dispatchers.IO) {
                // T38 review fix: the same bound as the pipelines; seven full-size decodes at once peaked near 300 MB native.
                val decodes = Semaphore(BatchImport.CONCURRENCY)
                uris.take(BatchImport.MAX_PHOTOS)
                    .map { uri -> async { decodes.withPermit { runCatching { AndroidImages.loadPhoto(context.contentResolver, uri) }.getOrNull() } } }
                    .awaitAll()
                    .filterNotNull()
            }
            Log.i(TAG, "photo_batch_ms=${SystemClock.elapsedRealtime() - t0} picked=${uris.size} photos=${photos.size}")
            pending.batches.send(photos)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera(context, camera, pending)
        } else {
            pending.file = null
            pending.results.trySend(null)
        }
    }
    return remember(camera, picker, manyPicker, permission) { PhotoIntake(context.applicationContext, camera, picker, manyPicker, permission, pending) }
}
