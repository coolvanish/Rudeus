package kz.rudy.ar.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Decodes PNG/JPEG [bytes] off the main thread (CMP 1.12 `ByteArray.decodeToImageBitmap()`); null while decoding,
 * when [bytes] is null, or when the bytes are not an image. Keyed on the array's identity.
 */
@Composable
fun rememberSpriteBitmap(bytes: ByteArray?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = if (bytes == null || bytes.isEmpty()) null else try {
            withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    return bitmap
}

/** The character's sprite (transparent PNG) fitted into [modifier]; draws nothing until decoded. */
@Composable
fun SpriteImage(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier) {
    Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
}
