package kz.rudy.ar.core.session

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

// T54: ScreenView.of is the one matrix product of picking. The host hands over ARCore's camera view matrix and the
// anchor's model matrix (Pose.toMatrix), both column-major; the engine projects plane points through their product.

/** Column-major [m] applied to (x, y, z, w). */
private fun apply(m: FloatArray, p: FloatArray): FloatArray =
    FloatArray(4) { r -> m[r] * p[0] + m[4 + r] * p[1] + m[8 + r] * p[2] + m[12 + r] * p[3] }

/** A rigid transform, column-major: rotation [angle] about the unit axis (ax, ay, az), then translation (tx, ty, tz). */
private fun rigid(angle: Float, ax: Float, ay: Float, az: Float, tx: Float, ty: Float, tz: Float): FloatArray {
    val c = cos(angle)
    val s = sin(angle)
    val k = 1f - c
    return floatArrayOf(
        c + ax * ax * k, ay * ax * k + az * s, az * ax * k - ay * s, 0f,
        ax * ay * k - az * s, c + ay * ay * k, az * ay * k + ax * s, 0f,
        ax * az * k + ay * s, ay * az * k - ax * s, c + az * az * k, 0f,
        tx, ty, tz, 1f,
    )
}

class ScreenViewTest {

    @Test
    fun ofMultipliesViewByModelColumnMajor() {
        // By hand: the model moves the plane origin to (1, 2, 3); the view turns the world 90 degrees about +Y,
        // which takes (x, y, z) to (z, y, -x). The plane origin lands at (3, 2, -1) in eye space.
        val model = rigid(0f, 0f, 1f, 0f, 1f, 2f, 3f)
        val view = rigid((PI / 2).toFloat(), 0f, 1f, 0f, 0f, 0f, 0f)
        val proj = FloatArray(16) { it * 0.5f }
        val sv = ScreenView.of(view, model, proj, widthPx = 1080, heightPx = 2400, pickMarginPx = 42f)
        val origin = apply(sv.planeToEye, floatArrayOf(0f, 0f, 0f, 1f))
        for ((want, got) in floatArrayOf(3f, 2f, -1f, 1f).zip(origin)) assertTrue(abs(want - got) < 1e-6f, "${origin.toList()}")
        // The other order (model x view) would put it at (1, 2, 3).
        assertContentEquals(proj, sv.eyeToClip)
        assertEquals(1080, sv.widthPx)
        assertEquals(2400, sv.heightPx)
        assertEquals(42f, sv.pickMarginPx)

        // Any pair: the product sends a plane point where the model, then the view, send it.
        val rnd = Random(54)
        repeat(1_000) {
            fun unit(): FloatArray {
                val a = FloatArray(3) { rnd.nextFloat() * 2f - 1f }
                val n = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-3f)
                return floatArrayOf(a[0] / n, a[1] / n, a[2] / n)
            }
            val ma = unit()
            val va = unit()
            val m = rigid(rnd.nextFloat() * 6.3f, ma[0], ma[1], ma[2], rnd.nextFloat() * 4f - 2f, rnd.nextFloat() - 0.5f, rnd.nextFloat() * 4f - 2f)
            val v = rigid(rnd.nextFloat() * 6.3f, va[0], va[1], va[2], rnd.nextFloat() * 4f - 2f, rnd.nextFloat() * 4f - 2f, rnd.nextFloat() * 4f - 2f)
            val s = ScreenView.of(v, m, proj, 1, 1, 0f)
            val p = floatArrayOf(rnd.nextFloat() - 0.5f, rnd.nextFloat() * 0.2f, rnd.nextFloat() - 0.5f, 1f)
            val want = apply(v, apply(m, p))
            val got = apply(s.planeToEye, p)
            for (i in 0 until 4) assertTrue(abs(want[i] - got[i]) < 1e-5f, "case $it: ${want.toList()} vs ${got.toList()}")
        }
    }

    @Test
    fun ofKeepsNoReferenceToItsArguments() {
        val view = rigid(0.3f, 0f, 1f, 0f, 0.1f, -0.2f, -0.5f)
        val model = rigid(1.1f, 0f, 1f, 0f, 0.4f, -0.8f, -1.2f)
        val proj = FloatArray(16) { (it + 1).toFloat() }
        val sv = ScreenView.of(view, model, proj, 720, 1600, 16f)
        val eye = sv.planeToEye.copyOf()
        val clip = sv.eyeToClip.copyOf()
        assertNotSame(view, sv.planeToEye)
        assertNotSame(model, sv.planeToEye)
        assertNotSame(proj, sv.eyeToClip)
        // The host reuses its scratch arrays for the next pick: what it writes there later changes nothing here.
        view.fill(7f)
        model.fill(-3f)
        proj.fill(0f)
        assertContentEquals(eye, sv.planeToEye)
        assertContentEquals(clip, sv.eyeToClip)
        assertEquals(16, sv.planeToEye.size)
        assertEquals(16, sv.eyeToClip.size)
    }
}
