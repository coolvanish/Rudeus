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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// HAND-REVIEW: T03 (a) — photo in via the system camera (ActivityResultContracts.TakePicture into a FileProvider URI
// under cacheDir/capture, authority "<applicationId>.fileprovider", res/xml/file_paths.xml) and the photo picker
// (ActivityResultContracts.PickVisualMedia, no storage permission). NOT the AR frame: the ARCore CPU image is
// 640x480 and the Capture screen has no AR session. The result is decoded off the main thread
// (AndroidImages.preparePhoto: <= 2048 px, EXIF upright, JPEG) and handed back on the main thread.
// HAND-REVIEW: review fix — the manifest declares CAMERA, so ACTION_IMAGE_CAPTURE throws SecurityException until the
// permission is granted (fresh install: the AR screens ask for it later). takePhoto asks first; a denial or a missing
// camera app hands back null instead of crashing.

private const val TAG = "RudeusGate"
private const val CAPTURE_DIR = "capture"

/** The two photo sources, created by [rememberPhotoIntake] inside composition (the launchers need it). */
class PhotoIntake internal constructor(
    private val context: Context,
    private val camera: ActivityResultLauncher<Uri>,
    private val picker: ActivityResultLauncher<PickVisualMediaRequest>,
    private val permission: ActivityResultLauncher<String>,
    private val pending: Pending,
) {
    internal class Pending {
        var file: File? = null
        var onPhoto: ((ByteArray?) -> Unit)? = null
        /** `elapsedRealtime` when the last camera/picker result arrived (the child's shutter + confirm). */
        var shutterAtMs: Long = 0L
    }

    /** The last shutter time, once: Play logs `capture_to_play_ms` only for the capture that led to it. */
    fun takeShutterAtMs(): Long = pending.shutterAtMs.also { pending.shutterAtMs = 0L }

    fun takePhoto(onPhoto: (ByteArray?) -> Unit) {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        pending.file = file
        pending.onPhoto = onPhoto
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera(context, camera, pending) else permission.launch(Manifest.permission.CAMERA)
    }

    fun pickPhoto(onPhoto: (ByteArray?) -> Unit) {
        pending.onPhoto = onPhoto
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** Opens the system camera on [Pending.file]; no camera app -> the waiting callback gets null. */
private fun launchCamera(context: Context, camera: ActivityResultLauncher<Uri>, pending: PhotoIntake.Pending) {
    val file = pending.file ?: return
    try {
        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no camera app: ${e.message}")
        pending.file = null
        pending.onPhoto?.also { pending.onPhoto = null }?.invoke(null)
    }
}

@Composable
fun rememberPhotoIntake(): PhotoIntake {
    val context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()
    val pending = remember { PhotoIntake.Pending() }

    /** Runs [load] on IO, then hands the bytes (or null) to the waiting callback on the main thread. */
    fun deliver(load: () -> ByteArray?) {
        val onPhoto = pending.onPhoto ?: return
        pending.onPhoto = null
        pending.shutterAtMs = SystemClock.elapsedRealtime()
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val bytes = withContext(Dispatchers.IO) { runCatching(load).getOrNull() }
            Log.i(TAG, "photo_ready_ms=${SystemClock.elapsedRealtime() - t0} photo_bytes=${bytes?.size ?: 0}")
            onPhoto(bytes)
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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera(context, camera, pending)
        } else {
            pending.file = null
            pending.onPhoto?.also { pending.onPhoto = null }?.invoke(null)
        }
    }
    return remember(camera, picker, permission) { PhotoIntake(context.applicationContext, camera, picker, permission, pending) }
}
