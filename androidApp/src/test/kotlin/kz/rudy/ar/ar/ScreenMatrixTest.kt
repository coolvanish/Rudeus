package kz.rudy.ar.ar

import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// T54: the host's ScreenProjector reads the anchor's model matrix (`Pose.toMatrix`, column-major) once per pick, where
// the old grab test composed a world pose per member (`toWorld`, PlaneCoords.kt:52-53). This pins that the matrix sends
// a plane point (u, h, v) where `toWorld` sends it, so the engine's plane-to-eye product projects the same world points.
// ARCore's Pose is plain Java: it runs on the JVM.
class ScreenMatrixTest {

    @Test
    fun anchorMatrixTimesAPlanePointIsToWorld() {
        val rnd = Random(54)
        val m = FloatArray(16)
        var worst = 0f
        repeat(10_000) { n ->
            // Any rotation (an ARCore anchor on a table only yaws, but the matrix must not depend on that).
            val qd = DoubleArray(4) { rnd.nextDouble() * 2 - 1 }
            val norm = sqrt(qd.sumOf { it * it }).coerceAtLeast(1e-3)
            val q = FloatArray(4) { (qd[it] / norm).toFloat() }
            val t = FloatArray(3) { (rnd.nextDouble() * 6 - 3).toFloat() }
            val anchor = Pose(t, q)
            anchor.toMatrix(m, 0)
            val u = (rnd.nextDouble() * 2 - 1).toFloat()
            val h = (rnd.nextDouble() * 0.3).toFloat()
            val v = (rnd.nextDouble() * 2 - 1).toFloat()
            val world = anchor.compose(Pose.makeTranslation(u, h, v)) // toWorld(anchor, Vec2(u, v), h)
            val x = m[0] * u + m[4] * h + m[8] * v + m[12]
            val y = m[1] * u + m[5] * h + m[9] * v + m[13]
            val z = m[2] * u + m[6] * h + m[10] * v + m[14]
            val w = m[3] * u + m[7] * h + m[11] * v + m[15]
            val d = maxOf(abs(x - world.tx()), abs(y - world.ty()), abs(z - world.tz()))
            worst = maxOf(worst, d)
            assertTrue(d <= 1e-5f, "pose $n: matrix ($x, $y, $z) vs toWorld (${world.tx()}, ${world.ty()}, ${world.tz()})")
            assertTrue(abs(w - 1f) <= 1e-6f, "pose $n: the last row is (0, 0, 0, 1)")
        }
        println("anchorMatrixTimesAPlanePointIsToWorld: worst ${worst} m over 10000 poses")
    }
}
