package kz.rudy.ar.ar

import kz.rudy.ar.core.geometry.Quat
import kotlin.test.Test
import kotlin.test.assertEquals

// T52: the one line where a :core rotation becomes a SceneView node's. kotlin-math's Quaternion takes (x, y, z, w) in
// that order, as :core's Quat does; a swap here would turn every billboard and nothing in :core could see it.
class NodeRotationTest {
    @Test
    fun quatReachesTheNodeWithItsComponentsInOrder() {
        val q = Quat(0.1f, 0.2f, 0.3f, 0.4f).toNode()
        assertEquals(0.1f, q.x)
        assertEquals(0.2f, q.y)
        assertEquals(0.3f, q.z)
        assertEquals(0.4f, q.w)
    }
}
