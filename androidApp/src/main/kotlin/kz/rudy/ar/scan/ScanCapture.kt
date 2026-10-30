package kz.rudy.ar.scan

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kz.rudy.ar.capture.AndroidImages
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.pipeline.ScanInput
import kz.rudy.ar.core.pipeline.ScanMasks
import kz.rudy.ar.core.pipeline.SceneBuilder
import java.io.ByteArrayOutputStream
import java.util.Locale

// HAND-REVIEW: T04 — scan = ONE explicit grab of the current AR frame, never per frame. Two halves:
//  (1) grabScanFrame, main thread, inside onSessionUpdated (acquireCameraImage only works on the current frame):
//      YUV_420_888 -> NV21 bytes (one copy, the Image is closed in `finally`), Camera.getPose() (sensor frame, NOT
//      displayOrientedPose: the CPU image and Camera.getImageIntrinsics() are both in sensor orientation), the anchor
//      pose and the anchor plane's polygon (`Plane.getPolygon()` x/z pairs through `centerPose` -> world points).
//  (2) runTableScan, IO: NV21 -> JPEG (YuvImage) -> <= SCAN_MAX_SIDE px -> TableScanner.segment -> :core
//      (ScanMasks.of, ScanInput.fromAnchor, SceneBuilder.build). The intrinsics are rescaled to the pixel grid the
//      masks come back in (SAM answers in the input JPEG's grid: docs/api-samples/sam-table matches its input).
// No ARCore type leaves this file except as :core Pose3/Vec3; nothing here decides what an object means.

private const val TAG = "RudeusGate"

/** Long side of the JPEG SAM gets (the brief: <= 1280). ARCore's CPU image is 640x480 on most devices, so usually no-op. */
internal const val SCAN_MAX_SIDE = 1280

private const val SCAN_JPEG_QUALITY = 90

/** Upper bound on the whole scan (6 prompts, `MetaConfig.samConcurrency` at a time); the overlay would otherwise hold forever on a dead link. */
internal const val SCAN_TIMEOUT_MS = 30_000L

/** Everything taken from one AR frame on the main thread, before anything slow happens. */
internal class ScanGrab(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    /** In the grabbed image's pixel grid. */
    val intrinsics: Intrinsics,
    val cameraPose: Pose3,
    val anchorPose: Pose3,
    val planePolygonWorld: List<Vec3>?,
    val startedMs: Long,
)

internal class ScanOutcome(val level: Level?, val ms: Long, val error: String? = null)

/** One attempt to grab the current frame: [grab], or nothing with [retry] = ARCore had no image for it yet. */
internal class GrabAttempt(val grab: ScanGrab?, val retry: Boolean)

/** How many frames a scan waits for `acquireCameraImage` (NotYetAvailableException right after resume) before failing. */
internal const val GRAB_MAX_TRIES = 10

/**
 * Main thread, on the current [frame] (camera TRACKING). Never throws into the frame loop: any failure (no image
 * for this frame -> [GrabAttempt.retry], odd size, stride surprise, intrinsics or polygon errors) is logged and
 * comes back as `grab = null`.
 */
internal fun grabScanFrame(frame: Frame, anchor: Anchor, plane: Plane?, nowMs: Long = SystemClock.elapsedRealtime()): GrabAttempt {
    try {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return GrabAttempt(null, retry = true)
        }
        val width: Int
        val height: Int
        val nv21: ByteArray
        try {
            width = image.width
            height = image.height
            // NV21 has no row padding: its chroma rows are exactly `width` bytes, so an odd width cannot be expressed.
            if (image.format != ImageFormat.YUV_420_888 || width % 2 != 0 || height < 2) {
                Log.w(TAG, "scan_grab_failed: format=${image.format} size=${width}x$height")
                return GrabAttempt(null, retry = false)
            }
            nv21 = yuv420ToNv21(image)
        } finally {
            image.close()
        }
        val camera = frame.camera
        val ci = camera.imageIntrinsics
        val f = ci.focalLength
        val c = ci.principalPoint
        val d = ci.imageDimensions
        // The intrinsics describe d[0] x d[1]; the image should be exactly that. Scale if a device disagrees.
        val sx = width / d[0].toFloat()
        val sy = height / d[1].toFloat()
        val intrinsics = Intrinsics(f[0] * sx, f[1] * sy, c[0] * sx, c[1] * sy, width, height)
        val grab = ScanGrab(
            nv21 = nv21,
            width = width,
            height = height,
            intrinsics = intrinsics,
            cameraPose = camera.pose.toPose3(),
            anchorPose = anchor.pose.toPose3(),
            planePolygonWorld = plane?.let(::planePolygonWorld),
            startedMs = nowMs,
        )
        return GrabAttempt(grab, retry = false)
    } catch (e: Exception) {
        Log.w(TAG, "scan_grab_failed: ${e.javaClass.simpleName} ${e.message}")
        return GrabAttempt(null, retry = false)
    }
}

/**
 * IO. JPEG -> SAM -> level. Never throws: a failure (network, missing key, timeout, degenerate masks) comes back as
 * [ScanOutcome.error] with `level = null`. [previous] keeps ids stable across re-scans (SceneBuilder, <= 5 cm).
 */
internal suspend fun runTableScan(
    grab: ScanGrab,
    scanner: TableScanner,
    prompts: List<String>,
    previous: Level?,
    profile: SurfaceProfile,
    ref: String,
): ScanOutcome = withContext(Dispatchers.IO) {
    try {
        val jpeg = toJpeg(grab)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val jpegW = bounds.outWidth.takeIf { it > 0 } ?: grab.width
        val jpegH = bounds.outHeight.takeIf { it > 0 } ?: grab.height
        val t1 = SystemClock.elapsedRealtime()
        // withTimeoutOrNull, not withTimeout: its TimeoutCancellationException is a CancellationException and would
        // escape the catch below, killing the caller's coroutine without an answer (review fix, blocker 1).
        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) { scanner.segment(ref, jpeg, prompts) }
            ?: throw IllegalStateException("scan timeout after $SCAN_TIMEOUT_MS ms")
        val segmentMs = SystemClock.elapsedRealtime() - t1
        val masks = ScanMasks.of(result)
        // Masks are full-frame in SAM's grid (= the JPEG's); the intrinsics follow whichever grid came back.
        val grid = masks.firstOrNull()?.mask
        val intrinsics = grab.intrinsics.scaledTo(grid?.width ?: jpegW, grid?.height ?: jpegH)
        val input = ScanInput.fromAnchor(grab.anchorPose, grab.cameraPose, intrinsics, masks, grab.planePolygonWorld)
        val built = SceneBuilder().build(input, previous, profile)
        // A prompt whose request failed (429, timeout) must not make its objects vanish: the previous scan's objects
        // of that label are carried over unchanged. DYNAMIC ones (a hand) are not: they are re-placed every scan.
        val carried = if (previous == null || result.failedPrompts.isEmpty()) emptyList() else {
            val failed = result.failedPrompts.toSet()
            val taken = built.objects.mapTo(HashSet()) { it.id }
            previous.objects.filter { it.label in failed && it.kind != ObjectKind.DYNAMIC && it.id !in taken }
        }
        val level = if (carried.isEmpty()) built else built.copy(objects = built.objects + carried)
        val ms = SystemClock.elapsedRealtime() - grab.startedMs
        Log.i(
            TAG,
            "scan_ms=$ms segment_ms=$segmentMs objects=${level.objects.size} masks=${masks.size} prompts=${prompts.size} " +
                "failed=${result.failedPrompts.size} carried=${carried.size} jpeg=${jpegW}x$jpegH bytes=${jpeg.size} " +
                "boundary=${input.boundary?.size ?: 0} ref=$ref",
        )
        if (carried.isNotEmpty()) Log.w(TAG, "scan_carried failed_prompts=${result.failedPrompts} ids=${carried.map { it.id }}")
        for (o in level.objects) {
            val c = o.centroid
            Log.i(TAG, "scan_object id=${o.id} kind=${o.kind} c=(%.3f,%.3f) h=%.3f n=${o.footprint.size}".format(Locale.US, c.u, c.v, o.heightM))
        }
        ScanOutcome(level, ms)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val ms = SystemClock.elapsedRealtime() - grab.startedMs
        Log.w(TAG, "scan_failed ms=$ms ref=$ref: ${e.javaClass.simpleName} ${e.message}")
        ScanOutcome(null, ms, e.message ?: e.javaClass.simpleName)
    }
}

/** NV21 -> JPEG at [SCAN_JPEG_QUALITY], then down to [SCAN_MAX_SIDE] on the long side (JPEG stays JPEG). */
private fun toJpeg(grab: ScanGrab): ByteArray {
    val out = ByteArrayOutputStream(grab.width * grab.height / 4)
    YuvImage(grab.nv21, ImageFormat.NV21, grab.width, grab.height, null)
        .compressToJpeg(Rect(0, 0, grab.width, grab.height), SCAN_JPEG_QUALITY, out)
    return AndroidImages.downscale(out.toByteArray(), SCAN_MAX_SIDE)
}

/** Same camera, another pixel grid: focal lengths and principal point scale with the image. */
private fun Intrinsics.scaledTo(w: Int, h: Int): Intrinsics {
    if (w == width && h == height) return this
    val sx = w / width.toFloat()
    val sy = h / height.toFloat()
    return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, w, h)
}

/**
 * YUV_420_888 (3 planes, any row/pixel stride) -> NV21 (Y, then interleaved V/U). Odd sizes drop the last column
 * / row of chroma, as NV21 itself does.
 */
private fun yuv420ToNv21(image: Image): ByteArray {
    val w = image.width
    val h = image.height
    val out = ByteArray(w * h + 2 * (w / 2) * (h / 2))
    val yPlane = image.planes[0]
    val yBuf = yPlane.buffer
    val yRow = yPlane.rowStride
    val yPix = yPlane.pixelStride
    var pos = 0
    for (r in 0 until h) {
        val base = r * yRow
        if (yPix == 1) {
            yBuf.position(base)
            yBuf.get(out, pos, w)
            pos += w
        } else {
            for (c in 0 until w) out[pos++] = yBuf.get(base + c * yPix)
        }
    }
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val uRow = uPlane.rowStride
    val uPix = uPlane.pixelStride
    val vRow = vPlane.rowStride
    val vPix = vPlane.pixelStride
    for (r in 0 until h / 2) {
        val ub = r * uRow
        val vb = r * vRow
        for (c in 0 until w / 2) {
            out[pos++] = vBuf.get(vb + c * vPix)
            out[pos++] = uBuf.get(ub + c * uPix)
        }
    }
    return out
}

/** ARCore pose -> :core pose (same convention: quaternion x, y, z, w; translation in metres). */
internal fun Pose.toPose3(): Pose3 = Pose3(Vec3(tx(), ty(), tz()), Quat(qx(), qy(), qz(), qw()))

/**
 * The plane's convex polygon as world points: `getPolygon()` is `[x1, z1, x2, z2, ...]` in the plane's local frame
 * (y = 0), mapped through `centerPose`. A plane that ARCore merged into a bigger one is followed to its survivor.
 */
internal fun planePolygonWorld(plane: Plane): List<Vec3>? {
    var p = plane
    var hops = 0
    while (hops++ < 8) p = p.subsumedBy ?: break
    val buf = p.polygon
    val n = buf.limit() / 2
    if (n < 3) return null
    val pose = p.centerPose
    val out = ArrayList<Vec3>(n)
    val local = FloatArray(3)
    for (i in 0 until n) {
        local[0] = buf.get(2 * i)
        local[1] = 0f
        local[2] = buf.get(2 * i + 1)
        val w = pose.transformPoint(local)
        out.add(Vec3(w[0], w[1], w[2]))
    }
    return out
}
