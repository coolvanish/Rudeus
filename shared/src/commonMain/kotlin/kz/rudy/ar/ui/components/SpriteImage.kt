package kz.rudy.ar.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.roundToInt

/**
 * Decodes PNG/JPEG [bytes] off the main thread (CMP 1.12 `ByteArray.decodeToImageBitmap()`); null while decoding,
 * when [bytes] is null, or when the bytes are not an image. Keyed on the array's identity and the bounds. What is kept
 * fits [maxWidthPx] x [maxHeightPx], so a place that shows the image small does not hold all its pixels.
 */
@Composable
fun rememberSpriteBitmap(bytes: ByteArray?, maxWidthPx: Int = Int.MAX_VALUE, maxHeightPx: Int = Int.MAX_VALUE): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes, maxWidthPx, maxHeightPx) {
        value = if (bytes == null || bytes.isEmpty()) null else try {
            withContext(Dispatchers.Default) { bytes.decodeToImageBitmap().fittedInto(maxWidthPx, maxHeightPx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    return bitmap
}

/** A filtered copy that fits the box (aspect kept), or this bitmap when it already does. Never upscales. */
private fun ImageBitmap.fittedInto(maxWidth: Int, maxHeight: Int): ImageBitmap {
    if (width <= maxWidth && height <= maxHeight) return this
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    val w = maxOf(1, (width * scale).roundToInt())
    val h = maxOf(1, (height * scale).roundToInt())
    val out = ImageBitmap(w, h)
    Canvas(out).drawImageRect(this, dstSize = IntSize(w, h), paint = Paint().apply { filterQuality = FilterQuality.Low })
    return out
}

/** The character's sprite (transparent PNG) fitted into [modifier]; draws nothing until decoded. */
@Composable
fun SpriteImage(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier) {
    Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
}
