package kz.rudy.ar.capture

import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoIntakeTest {
    @Test
    fun restoredCameraPathAndEarlyResultReachNewConsumerOnce() = runBlocking {
        val photo = File("/cache/capture/photo.jpg")
        val original = PhotoIntake.Pending(photo)
        val saver = PhotoIntake.Pending.saver
        val saved = with(saver) { SaverScope { true }.save(original) }
        val restored = assertNotNull(saver.restore(assertNotNull(saved)))
        assertEquals(photo, restored.file)

        // The restored Activity receives the camera result before App starts collecting.
        val bytes = byteArrayOf(1, 2, 3)
        restored.results.send(bytes)
        assertContentEquals(bytes, withTimeout(1_000) { restored.photos.first() })
        assertTrue(restored.results.tryReceive().isFailure)
        assertTrue(original.results.tryReceive().isFailure)
    }

    @Test
    fun pickerRestoresWithoutCameraFileAndDeliversCancellation() = runBlocking {
        val saver = PhotoIntake.Pending.saver
        val saved = with(saver) { SaverScope { true }.save(PhotoIntake.Pending()) }
        val restored = assertNotNull(saver.restore(assertNotNull(saved)))
        assertNull(restored.file)
        restored.results.send(null)
        assertNull(withTimeout(1_000) { restored.photos.first() })
        assertTrue(restored.results.tryReceive().isFailure)
    }
}
