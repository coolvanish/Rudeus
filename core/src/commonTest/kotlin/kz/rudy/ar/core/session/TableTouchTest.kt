package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmoteSet
import kz.rudy.ar.core.physics.CastMember
import kz.rudy.ar.core.session.ArPort
import kz.rudy.ar.core.session.BillboardPoser
import kz.rudy.ar.core.session.CameraFrame
import kz.rudy.ar.core.session.FrameInput
import kz.rudy.ar.core.session.NodePose
import kz.rudy.ar.core.session.ScreenView
import kz.rudy.ar.core.session.TableHit
import kz.rudy.ar.core.session.TouchAction
import kz.rudy.ar.core.session.TouchEvent
import kz.rudy.ar.core.session.TouchPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Characterization tests of what a finger does after the anchor (T54): the table tap (pick a member or walk the active
// one, the depth edge on the way, the boundary growth), the screen-space pick, the finger grab, the lead and the
// release, as they ran inside RudeusArView's tap handler, its CastTable and ar/CastTouch.kt. Written against the
// host-local copy first; the same file, with only the package line and the class name changed, runs against the copy in
// :core. The AR session is a FakePort over a pinhole camera in plain numbers (the anchor is not the identity, so the
// ScreenView goes through ScreenView.of as the host builds it); a tap comes in as the host makes it (its own hit test,
// then onTap with the depth reading as a lambda), a touch as a TouchEvent. A log line with a float is asserted by its
// key and its integer fields only (the float text may differ on iOS).

private const val FRAME_MS = 16L

/** An arbitrary host clock origin (SystemClock.elapsedRealtime is never small on a phone). */
private const val T0 = 1_000_000L

/** The host's WALK_DEPTH_SAMPLES and LEAD_DEPTH_SAMPLES (androidApp ar/RudeusArView.kt). */
private const val WALK = 6
private const val LEAD = 2

/** The host's FEET_DEPTH_BIAS and REAPPEAR_AFTER_TRACKING_MS. */
private const val BIAS = 0.6f
private const val REAPPEAR_MS = 800L

/** A picture on the table at size 1, as the host's CharacterSprite has it: the profile's height, a width from the PNG. */
private const val QUAD_W = 0.05f
private const val QUAD_H = 0.06f

/** A drop the Depth API reports past a table edge (EDGE_DROP_M is 15 cm on the table). */
private const val DROP = -0.30f

private fun member(id: String) = Member(id, Ability.JUMP, EmoteSet.defaults())

/** A plane point (u, h, v) in doubles: the test's own geometry, independent of the engine's floats. */
private class D3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: D3) = D3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: D3) = D3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = D3(x * s, y * s, z * s)
    fun dot(o: D3) = x * o.x + y * o.y + z * o.z
    fun cross(o: D3) = D3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun unit(): D3 = this * (1.0 / sqrt(dot(this)))
}

private fun d3(p: Vec2, h: Float = 0f) = D3(p.u.toDouble(), h.toDouble(), p.v.toDouble())

/** A member's pick rectangle on the screen, closed form: the quad from the feet up, widened by the margin. */
private class PickRect(
    val cx: Double, val cy: Double, val halfW: Double,
    val feetY: Double, val headY: Double, val margin: Double,
    /** Pixels per metre at the feet and at the head (the pick uses the feet's). */
    val pxPerMFeet: Double, val pxPerMHead: Double,
) {
    val left get() = cx - halfW
    val right get() = cx + halfW
    val top get() = minOf(feetY, headY) - margin
    val bottom get() = maxOf(feetY, headY) + margin
}

/**
 * A pinhole camera in plain numbers, in the anchor's plane frame: [eye] and [at] are plane points (u, h, v), up is +h,
 * [fovYDeg] the vertical field of view, [w] x [h] the view, [margin] the pick margin in pixels. The anchor sits in the
 * world at [anchorAt], turned [anchorYaw] about +Y: the host's view matrix is the world camera's, its model matrix
 * the anchor's, and ScreenView.of multiplies them back into this plane camera.
 */
private class Pinhole(
    val eye: D3 = D3(0.0, 0.35, 0.45),
    val at: D3 = D3(0.0, 0.0, 0.0),
    val fovYDeg: Double = 65.0,
    val w: Int = 1080,
    val h: Int = 2160,
    val margin: Float = 42f,
    val anchorYaw: Double = 0.7,
    val anchorAt: D3 = D3(0.3, -0.9, -1.2),
) {
    val fwd = (at - eye).unit()
    val right = fwd.cross(D3(0.0, 1.0, 0.0)).unit()
    val up = right.cross(fwd)
    val focalPx = h / 2.0 / tan(fovYDeg * PI / 360.0)

    /** Plane point -> (x px, y px, depth m along the view axis); a depth <= 0 is behind the camera. */
    fun pixel(p: D3): Triple<Double, Double, Double> {
        val d = p - eye
        val depth = d.dot(fwd)
        return Triple(w / 2.0 + focalPx * d.dot(right) / depth, h / 2.0 - focalPx * d.dot(up) / depth, depth)
    }

    fun px(uv: Vec2, hM: Float = 0f): Pair<Float, Float> = pixel(d3(uv, hM)).let { it.first.toFloat() to it.second.toFloat() }

    /** View pixel -> the plane point under it (h = 0); null when the ray does not come down to the plane. */
    fun table(x: Float, y: Float): Vec2? {
        val dir = fwd + right * ((x - w / 2.0) / focalPx) + up * (-(y - h / 2.0) / focalPx)
        if (dir.y >= -1e-9) return null
        val t = -eye.y / dir.y
        return Vec2((eye.x + dir.x * t).toFloat(), (eye.z + dir.z * t).toFloat())
    }

    /** The pick rectangle of a member at [pos], [hM] above the plane, a [qw] x [qh] picture at [size]. */
    fun rect(pos: Vec2, hM: Float, size: Float, qw: Float = QUAD_W, qh: Float = QUAD_H): PickRect {
        val feet = pixel(d3(pos, hM))
        val head = pixel(d3(pos, hM + qh * size))
        val pxPerM = focalPx / feet.third
        return PickRect(
            cx = (feet.first + head.first) / 2, cy = (feet.second + head.second) / 2,
            halfW = pxPerM * qw * size * 0.5 + margin, feetY = feet.second, headY = head.second, margin = margin.toDouble(),
            pxPerMFeet = pxPerM, pxPerMHead = focalPx / head.third,
        )
    }

    /** What the engine's frame gets from this camera: where it is and its screen-right, in plane coordinates. */
    val frame: CameraFrame get() = CameraFrame(Vec2(eye.x.toFloat(), eye.z.toFloat()), eye.y.toFloat(), Vec2(right.x.toFloat(), right.z.toFloat()))

    /** As the host builds it: the world camera's view matrix and the anchor's model matrix, through ScreenView.of. */
    fun screen(): ScreenView = ScreenView.of(worldToEye(), anchorModel(), perspective(fovYDeg, w, h), w, h, margin)

    private fun anchorRotation(): DoubleArray { // column-major 3x3 about +Y
        val c = cos(anchorYaw)
        val s = sin(anchorYaw)
        return doubleArrayOf(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c)
    }

    private fun anchorModel(): FloatArray {
        val r = anchorRotation()
        return floatArrayOf(
            r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), 0f,
            r[3].toFloat(), r[4].toFloat(), r[5].toFloat(), 0f,
            r[6].toFloat(), r[7].toFloat(), r[8].toFloat(), 0f,
            anchorAt.x.toFloat(), anchorAt.y.toFloat(), anchorAt.z.toFloat(), 1f,
        )
    }

    /** World -> eye: the world point back into the plane frame (the inverse anchor), then the plane camera's look-at. */
    private fun worldToEye(): FloatArray {
        val r = anchorRotation()
        val out = FloatArray(16)
        val axes = listOf(right, up, fwd * -1.0) // the eye's X, Y, Z in plane coordinates
        for (row in 0 until 3) {
            val a = axes[row]
            // plane = R^T (world - t); eye[row] = a . (plane - eye) = (R a) . world - (R a) . t - a . eye
            val ra = D3(r[0] * a.x + r[3] * a.y + r[6] * a.z, r[1] * a.x + r[4] * a.y + r[7] * a.z, r[2] * a.x + r[5] * a.y + r[8] * a.z)
            out[row] = ra.x.toFloat()
            out[4 + row] = ra.y.toFloat()
            out[8 + row] = ra.z.toFloat()
            out[12 + row] = (-ra.dot(anchorAt) - a.dot(eye)).toFloat()
        }
        out[15] = 1f
        return out
    }
}

/** android.opengl.Matrix.perspectiveM (column-major), clip planes as the host's PROJECTION_NEAR_M / PROJECTION_FAR_M. */
private fun perspective(fovYDeg: Double, w: Int, h: Int, near: Double = 0.01, far: Double = 30.0): FloatArray {
    val f = 1.0 / tan(fovYDeg * PI / 360.0)
    val m = FloatArray(16)
    m[0] = (f * h / w).toFloat()
    m[5] = f.toFloat()
    m[10] = ((far + near) / (near - far)).toFloat()
    m[11] = -1f
    m[14] = (2 * far * near / (near - far)).toFloat()
    return m
}

/**
 * The AR session as the engine asks it. [camera] answers the screen (null without a [frame] or while not [laidOut]);
 * the table under a pixel is the camera's ray down to the plane, only inside [table] (the real table) and only while
 * the camera is [tracking]; [depth] is what the Depth API reads at a plane point, as height above the plane. Every
 * question lands in [asked], in order ("hit", "way <samples>", "screen"; the host's tap adds "depth").
 */
private class FakePort(var camera: Pinhole) : ArPort {
    var frame = true
    var tracking = true
    var laidOut = true
    var table: Polygon = Polygon.rect(Vec2.ZERO, 0.80f, 0.60f)
    var depth: (Vec2) -> Float? = { null }

    /** A screen of its own instead of [camera]'s (the agreement test builds the host's matrices itself). */
    var fixed: ScreenView? = null
    val asked = ArrayList<String>()
    val tolerances = ArrayList<Float>()

    fun onTable(uv: Vec2) = table.contains(uv)

    override fun hitTable(xPx: Float, yPx: Float, toleranceM: Float): TableHit? {
        asked += "hit"
        tolerances += toleranceM
        if (!frame || !tracking) return null
        val uv = camera.table(xPx, yPx) ?: return null
        if (!onTable(uv)) return null
        return TableHit(uv, depth(uv))
    }

    override fun depthGapsOnWay(from: Vec2, to: Vec2, samples: Int): List<Float?> {
        asked += "way $samples"
        if (!frame || !tracking) return List(samples) { null }
        return (1..samples).map { i -> depth(from + (to - from) * (i / samples.toFloat())) }
    }

    override fun screenView(): ScreenView? {
        asked += "screen"
        if (!frame || !laidOut) return null
        return fixed ?: camera.screen()
    }
}

/**
 * Drives one engine the way RudeusArView and rememberTableEngine do: [latest] is the slot of the last composition,
 * every slot callback lands in [calls] in order ("anchor", "<id> <event>", "active <id>", "level", "tracking <b>"),
 * the log in [lines], the port's questions in [asked]. Taps and touches come between frames, as on the phone.
 */
private class TouchHost(surface: Surface = Surface.TABLE, camera: Pinhole = Pinhole()) {
    val lines = ArrayList<String>()
    val calls = ArrayList<String>()
    val levels = ArrayList<Level>()

    /** Every member's walk target at the moment onLevel came. */
    val targetsAtLevel = ArrayList<Map<String, Vec2?>>()
    val port = FakePort(camera)
    val asked get() = port.asked
    var now = T0
    var latest: TableSlot = slotOf()
    val engine = TableEngine(
        { latest }, surface, {}, port, { lines += it },
        feetDepthBias = BIAS, reappearAfterTrackingMs = REAPPEAR_MS, walkDepthSamples = WALK, leadDepthSamples = LEAD,
    )
    val loop get() = engine.loop

    fun slotOf(vararg members: Member, activeId: String? = null, size: Float = 1f) = TableSlot(
        members.toList(), activeId, command = null, soundsOn = true,
        onAnchorPlaced = { calls += "anchor" },
        onCastEvent = { id, e -> calls += "$id $e" },
        characterSize = size,
        onTracking = { calls += "tracking $it" },
        onActiveChanged = { calls += "active $it" },
        onLevel = { l ->
            levels += l
            calls += "level"
            targetsAtLevel += loop.cast.members.associate { it.id to it.body.state.target }
        },
    )

    /** The slot with [ids], their pictures announced as the host's composition does, the size effect, the anchor; then clear. */
    fun start(vararg ids: String, activeId: String? = null, size: Float = 1f): TouchHost {
        latest = slotOf(*ids.map { member(it) }.toTypedArray(), activeId = activeId, size = size)
        for (id in ids) engine.spriteBuilt(id, false, QUAD_W, QUAD_H)
        engine.configure(size)
        anchor()
        clear()
        return this
    }

    fun anchor() = engine.anchorPlaced { port.camera.frame.right }

    /** Members put where a test wants them (GameLoop.remove, then add in this order): IDLE at h = 0, no event. */
    fun place(vararg spots: Pair<String, Vec2>) {
        for ((id, _) in spots) loop.remove(id)
        for ((id, p) in spots) loop.add(id, p, Ability.JUMP)
    }

    fun frame(tracking: Boolean = true, gapMs: Long = FRAME_MS): List<NodePose> {
        now += gapMs
        return engine.onFrame(FrameInput(now, tracking, port.camera.frame))
    }

    fun frames(n: Int, tracking: Boolean = true) = repeat(n) { frame(tracking) }

    /** Frames until [id] arrived at its target (at most [max]); false when it never did. */
    fun walkUntilArrived(id: String, max: Int = 3_000): Boolean {
        var n = 0
        while (state(id).target != null) {
            if (n++ >= max) return false
            frame()
        }
        return true
    }

    /**
     * A tap at view pixel ([x], [y]) as the host makes it: its own hit test first (a tap that misses the table is not
     * reported: false), then onTap with the depth reading as a lambda.
     */
    fun tap(x: Float, y: Float): Boolean {
        val uv = port.camera.table(x, y)?.takeIf { port.onTable(it) } ?: return false
        engine.onTap(x, y, uv) { asked += "depth"; port.depth(uv) }
        return true
    }

    /** A tap whose table point is exactly [uv], at its pixel. */
    fun tapAt(uv: Vec2) {
        assertTrue(port.onTable(uv), "$uv is on the table")
        val (x, y) = port.camera.px(uv)
        engine.onTap(x, y, uv) { asked += "depth"; port.depth(uv) }
    }

    /** Member [id]'s pick rectangle as it stands now, at the latest slot's size. */
    fun rectOf(id: String): PickRect = state(id).let { port.camera.rect(it.pos, it.h, latest.characterSize) }

    /** The centre of member [id]'s quad on the screen. */
    fun centreOf(id: String): Pair<Float, Float> = rectOf(id).let { it.cx.toFloat() to it.cy.toFloat() }

    fun touch(action: TouchAction, vararg pointers: TouchPoint, index: Int = 0): Boolean =
        engine.onTouch(TouchEvent(action, index, pointers.toList(), now))

    fun down(x: Float, y: Float, pointer: Int = 0) = touch(TouchAction.DOWN, TouchPoint(pointer, x, y))
    fun down(at: Pair<Float, Float>, pointer: Int = 0) = down(at.first, at.second, pointer)
    fun move(vararg pointers: TouchPoint) = touch(TouchAction.MOVE, *pointers)
    fun up(x: Float, y: Float, pointer: Int = 0) = touch(TouchAction.UP, TouchPoint(pointer, x, y))
    fun cancel() = touch(TouchAction.CANCEL, TouchPoint(0, 0f, 0f))

    /** Who a finger put down at ([x], [y]) grabs, or null: DOWN then CANCEL, and the log, the calls and the questions put back. */
    fun pickAt(x: Float, y: Float): String? {
        val l = lines.size
        val c = calls.size
        val a = asked.size
        val grabbed = down(x, y)
        val id = calls.drop(c).firstOrNull { it.startsWith("active ") }?.removePrefix("active ")
        cancel()
        while (lines.size > l) lines.removeAt(lines.lastIndex)
        while (calls.size > c) calls.removeAt(calls.lastIndex)
        while (asked.size > a) asked.removeAt(asked.lastIndex)
        if (grabbed) assertNotNull(id, "a grab makes its member active")
        return if (grabbed) id else null
    }

    fun clear() {
        lines.clear(); calls.clear(); levels.clear(); targetsAtLevel.clear(); asked.clear(); port.tolerances.clear()
    }

    fun state(id: String) = assertNotNull(loop.state(id), id)
}

private fun at(p: Pair<Float, Float>, id: Int = 0) = TouchPoint(id, p.first, p.second)

/** [line] has the words of [shape] in order; a word `key=` in [shape] stands for that key with any number. */
private fun assertLine(shape: String, line: String?) {
    assertNotNull(line, shape)
    val want = shape.split(' ')
    val got = line.split(' ')
    assertEquals(want.size, got.size, "'$line' against '$shape'")
    for ((w, g) in want.zip(got)) {
        if (w.endsWith("=")) assertTrue(g.startsWith(w) && g.removePrefix(w).toFloatOrNull() != null, "$w in '$line'")
        else assertEquals(w, g, "'$line' against '$shape'")
    }
}

/** The readings of a line's `way=[..]`, as their text ("null" or a number). */
private fun wayOf(line: String): List<String> {
    val from = line.indexOf("way=[")
    assertTrue(from >= 0, "no way= in '$line'")
    val to = line.indexOf(']', from)
    return line.substring(from + 5, to).split(", ")
}

/** A `tap_depth_gap=.. feet_h=.. way=[..] id=<id>` line with [entries] readings on its way. */
private fun assertTapLine(id: String, entries: Int, line: String?) {
    assertNotNull(line, "tap_depth_gap")
    assertTrue(line.startsWith("tap_depth_gap="), line)
    assertTrue(" feet_h=" in line && line.endsWith("] id=$id"), line)
    assertEquals(entries, wayOf(line).size, line)
}

/** [p] inside [poly] or on its outline (Polygon.contains is half-open on edges and vertices). */
private fun insideOrOn(poly: Polygon, p: Vec2): Boolean {
    if (poly.contains(p) || p in poly.points) return true
    return poly.edges().any { (a, b) ->
        val ab = b - a
        val t = ((p - a).dot(ab) / ab.lengthSquared).coerceIn(0f, 1f)
        (a + ab * t).distanceTo(p) < 1e-6f
    }
}

private fun assertNear(want: Vec2, got: Vec2?, what: String, eps: Float = 1e-5f) {
    assertNotNull(got, what)
    assertTrue(abs(want.u - got.u) <= eps && abs(want.v - got.v) <= eps, "$what: $got, want $want")
}

// --- The old pick, transcribed (pickAgreesWithTheOldWorldSpaceProjection) ---

/** android.opengl.Matrix.multiplyMV: column-major [m] times [v], the native sum order (util.cpp mx4transform). */
private fun multiplyMV(out: FloatArray, m: FloatArray, v: FloatArray) {
    val x = v[0]
    val y = v[1]
    val z = v[2]
    val w = v[3]
    out[0] = m[0] * x + m[4] * y + m[8] * z + m[12] * w
    out[1] = m[1] * x + m[5] * y + m[9] * z + m[13] * w
    out[2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w
    out[3] = m[3] * x + m[7] * y + m[11] * z + m[15] * w
}

/**
 * ARCore 1.54.0 `Pose.compose(Pose.makeTranslation(u, h, v))`'s translation (PlaneCoords.toWorld): the anchor's
 * quaternion [q] (x, y, z, w) turns (u, h, v) as `Quaternion.rotateVector` does it, read from its bytecode (q x v, then
 * that times the conjugate, in its float order), then the anchor's translation [t] is added.
 */
private fun poseToWorld(q: FloatArray, t: FloatArray, u: Float, h: Float, v: Float, out: FloatArray) {
    val qx = q[0]
    val qy = q[1]
    val qz = q[2]
    val qw = q[3]
    val tx = qw * u + qy * v - qz * h
    val ty = qw * h + qz * u - qx * v
    val tz = qw * v + qx * h - qy * u
    val tw = -qx * u - qy * h - qz * v
    out[0] = tx * qw + tw * -qx + ty * -qz - tz * -qy + t[0]
    out[1] = ty * qw + tw * -qy + tz * -qx - tx * -qz + t[1]
    out[2] = tz * qw + tw * -qz + tx * -qy - ty * -qx + t[2]
}

/**
 * Old ar/RudeusArView.kt:544-562 with ScreenProjector.load / project / pxPerM (261-279) and toWorld (PlaneCoords.kt:52),
 * transcribed as they ran: each ground member's feet and head go to the world through the anchor's pose, then
 * through the view and the projection. [closest] is how near the touch came to anything that decides the pick, pixels.
 */
private class OldPick(
    val q: FloatArray, val t: FloatArray, val view: FloatArray, val proj: FloatArray,
    val w: Int, val h: Int, val margin: Float,
) {
    private val p = FloatArray(3)
    private val world = FloatArray(4)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)
    var closest = Float.MAX_VALUE

    private fun project(u: Float, hM: Float, v: Float, out: FloatArray): Boolean {
        poseToWorld(q, t, u, hM, v, p)
        world[0] = p[0]; world[1] = p[1]; world[2] = p[2]; world[3] = 1f
        multiplyMV(eye, view, world)
        multiplyMV(clip, proj, eye)
        closest = minOf(closest, abs(clip[3] - 1e-4f) * 1e4f) // the behind-the-camera test, scaled to count as "near"
        if (clip[3] <= 1e-4f) return false
        out[0] = (clip[0] / clip[3] + 1f) * 0.5f * w
        out[1] = (1f - clip[1] / clip[3]) * 0.5f * h
        out[2] = -eye[2]
        return true
    }

    private fun pxPerM(depth: Float): Float = proj[0] * w * 0.5f / depth

    fun pick(x: Float, y: Float, members: List<CastMember>, quads: Map<String, Pair<Float, Float>>, size: Float): String? {
        closest = Float.MAX_VALUE
        val feet = FloatArray(3)
        val head = FloatArray(3)
        val ground = members.filter { it.body.state.mode == Mode.IDLE || it.body.state.mode == Mode.WALKING }
        var best: String? = null
        var bestD2 = Float.MAX_VALUE
        val inside = ArrayList<Float>()
        for (m in ground) {
            val s = m.body.state
            val sprite = quads[m.id] ?: continue
            if (!project(s.pos.u, s.h, s.pos.v, feet)) continue
            if (!project(s.pos.u, s.h + sprite.second * size, s.pos.v, head)) continue
            val halfW = pxPerM(feet[2]) * sprite.first * size * 0.5f + margin
            val cx = (feet[0] + head[0]) * 0.5f
            val cy = (feet[1] + head[1]) * 0.5f
            val top = minOf(feet[1], head[1]) - margin
            val bottom = maxOf(feet[1], head[1]) + margin
            closest = minOf(closest, abs(minOf(halfW - abs(x - cx), y - top, bottom - y)))
            if (abs(x - cx) > halfW) continue
            if (y < minOf(feet[1], head[1]) - margin || y > maxOf(feet[1], head[1]) + margin) continue
            val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            inside += sqrt(d2)
            if (d2 < bestD2) { bestD2 = d2; best = m.id }
        }
        inside.sort()
        if (inside.size >= 2) closest = minOf(closest, inside[1] - inside[0])
        return best
    }
}

private fun Random.between(a: Double, b: Double) = a + (b - a) * nextDouble()

/** A column-major look-at view matrix (android.opengl.Matrix.setLookAtM), computed in doubles. */
private fun lookAt(eye: D3, at: D3, up: D3): FloatArray {
    val f = (at - eye).unit()
    val s = f.cross(up).unit()
    val u = s.cross(f)
    return floatArrayOf(
        s.x.toFloat(), u.x.toFloat(), (-f.x).toFloat(), 0f,
        s.y.toFloat(), u.y.toFloat(), (-f.y).toFloat(), 0f,
        s.z.toFloat(), u.z.toFloat(), (-f.z).toFloat(), 0f,
        (-s.dot(eye)).toFloat(), (-u.dot(eye)).toFloat(), f.dot(eye).toFloat(), 1f,
    )
}

/** A pose's model matrix (Pose.toMatrix: the unit quaternion's rotation, then the translation), column-major. */
private fun poseMatrix(q: FloatArray, t: FloatArray): FloatArray {
    val (x, y, z, w) = q
    return floatArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y), 0f,
        2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x), 0f,
        2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y), 0f,
        t[0], t[1], t[2], 1f,
    )
}

class TableTouchTest {

    // --- The tap ---

    @Test
    fun aTapWalksTheActiveMemberAndAsksThePortOnceForTheWay() {
        val h = TouchHost().start("a")
        val uv = Vec2(0.10f, 0.05f)
        h.tapAt(uv)
        assertEquals(listOf("depth", "way $WALK"), h.asked, "one member: no screen, no hit; the depth, then the way")
        assertEquals(uv, h.state("a").target)
        assertEquals(Mode.WALKING, h.state("a").mode)
        assertEquals(emptyList(), h.calls, "reported by the next frame")
        assertTapLine("a", WALK + 1, h.lines.single())
        h.frame()
        assertEquals(listOf("a STARTED_WALKING"), h.calls)
        assertTrue(h.walkUntilArrived("a"))
        assertNear(uv, h.state("a").pos, "arrived")

        // Two on the table: the pick asks the screen first, then the same two questions; still no hit test.
        val g = TouchHost().start("a", "b")
        g.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        g.tapAt(Vec2(0f, -0.15f))
        assertEquals(listOf("screen", "depth", "way $WALK"), g.asked)
        assertEquals(Vec2(0f, -0.15f), g.state("a").target, "the active member is the first")
        assertNull(g.state("b").target)
    }

    @Test
    fun aTapInsideTheBoundaryLeavesTheLevelAlone() {
        val h = TouchHost().start("a")
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.40f, 0.40f))
        h.engine.level = level
        h.tapAt(Vec2(0.05f, 0.05f))
        assertSame(level, h.engine.level)
        assertEquals(emptyList(), h.levels)
        assertEquals(1, h.lines.size, "only the tap_depth_gap line")
        assertTrue(h.lines.none { it.startsWith("boundary_grown") })
        assertEquals(Vec2(0.05f, 0.05f), h.state("a").target, "and it walks")
    }

    @Test
    fun aTapOutsideTheBoundaryGrowsItThenWalks() {
        val h = TouchHost().start("a")
        val cup = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.08f, -0.05f), 0.04f, 0.04f), 0.08f)
        val level = Level(listOf(cup), boundary = Polygon.rect(Vec2.ZERO, 0.30f, 0.30f), spawn = Vec2(0.01f, 0.02f))
        h.engine.level = level
        val uv = Vec2(0.25f, 0.02f) // 10 cm past the boundary's edge, on the table
        h.tapAt(uv)
        val grown = h.levels.single()
        assertSame(grown, h.engine.level)
        assertNotSame(level, grown)
        assertEquals(listOf(mapOf<String, Vec2?>("a" to null)), h.targetsAtLevel, "onLevel comes before the walk")
        assertEquals(level.objects, grown.objects, "objects kept")
        assertEquals(level.spawn, grown.spawn, "spawn kept")
        assertTrue(grown.boundary!!.contains(uv))
        assertEquals(listOf("level"), h.calls)
        assertEquals(uv, h.state("a").target)
        assertEquals(2, h.lines.size)
        assertTapLine("a", WALK + 1, h.lines[0])
        assertLine("boundary_grown n=${grown.boundary!!.size} u= v=", h.lines[1])
        assertTrue(h.walkUntilArrived("a"))
        assertNear(uv, h.state("a").pos, "arrived past the old edge")
        assertEquals(listOf("level", "a STARTED_WALKING", "a ARRIVED"), h.calls, "nobody falls")
    }

    @Test
    fun theBoundaryGrowsByTheTapsSquareAndNeverShrinks() {
        val h = TouchHost().start("a")
        val old = Polygon.rect(Vec2.ZERO, 0.30f, 0.30f)
        h.engine.level = Level(emptyList(), boundary = old)
        val uv = Vec2(0.25f, 0.10f)
        h.tapAt(uv)
        val grown = assertNotNull(h.engine.level.boundary)
        val square = listOf(
            Vec2(uv.u - 0.02f, uv.v - 0.02f), Vec2(uv.u + 0.02f, uv.v - 0.02f),
            Vec2(uv.u + 0.02f, uv.v + 0.02f), Vec2(uv.u - 0.02f, uv.v + 0.02f),
        )
        for (p in grown.points) assertTrue(p in old.points || p in square, "$p is a vertex of the old boundary or of the 2 cm square")
        for (p in square) assertTrue(insideOrOn(grown, p), "$p, a corner of the square, is inside the hull")
        assertTrue(square.count { it in grown.points } >= 2, "the square's far corners are hull vertices")
        assertTrue(old.points.count { it in grown.points } >= 2, "the hull keeps old vertices")
        assertTrue(grown.area > old.area)
        for (p in old.points) assertTrue(insideOrOn(grown, p), "never shrinks: $p")
        // The same tap again changes nothing: the same Level, no onLevel, no line.
        val once = h.engine.level
        h.clear()
        h.tapAt(uv)
        assertSame(once, h.engine.level)
        assertEquals(emptyList(), h.levels)
        assertTrue(h.lines.none { it.startsWith("boundary_grown") })
        // A tap inside never shrinks it either.
        h.tapAt(Vec2(0f, 0f))
        assertSame(once, h.engine.level)
        // A second tap outside grows it again, around both taps.
        h.tapAt(Vec2(-0.25f, -0.12f))
        val twice = assertNotNull(h.engine.level.boundary)
        assertTrue(twice.area > grown.area)
        for (p in grown.points) assertTrue(insideOrOn(twice, p), "never shrinks: $p")
    }

    @Test
    fun withoutABoundaryNothingGrows() {
        // The table before its first scan.
        val h = TouchHost().start("a")
        val before = h.engine.level
        assertNull(before.boundary)
        h.tapAt(Vec2(0.35f, 0.25f))
        assertSame(before, h.engine.level)
        assertEquals(emptyList(), h.levels)
        assertEquals(Vec2(0.35f, 0.25f), h.state("a").target)
        // The floor, even with objects scanned.
        val floorCamera = Pinhole(eye = D3(0.0, 1.3, 1.6))
        val f = TouchHost(Surface.FLOOR, floorCamera)
        f.port.table = Polygon.rect(Vec2.ZERO, 3.2f, 2.4f)
        f.start("a")
        val box = LevelObject("box-1", "box", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.5f, 0.5f), 0.2f, 0.2f), 0.1f)
        val level = Level(listOf(box), boundary = null)
        f.engine.level = level
        f.tapAt(Vec2(1.2f, -0.9f))
        assertSame(level, f.engine.level)
        assertEquals(emptyList(), f.levels)
        assertTrue(f.lines.none { it.startsWith("boundary_grown") })
        assertEquals(Vec2(1.2f, -0.9f), f.state("a").target)
    }

    @Test
    fun aTapPastADepthEdgeIsBlockedAtOnceAndGrowsNothing() {
        val h = TouchHost().start("a")
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.30f, 0.30f))
        h.engine.level = level
        val uv = Vec2(0.30f, 0f)
        h.port.depth = { p -> if (p.u > 0.2f) DROP else 0f } // the table, then 30 cm down
        h.tapAt(uv)
        assertEquals(listOf("a BLOCKED"), h.calls, "from the tap, before any frame")
        assertNull(h.state("a").target)
        assertEquals(Mode.IDLE, h.state("a").mode)
        assertSame(level, h.engine.level, "nothing grows")
        assertEquals(emptyList(), h.levels)
        assertEquals(2, h.lines.size)
        assertTapLine("a", WALK + 1, h.lines[0])
        assertLine("tap_past_edge u= v=", h.lines[1])
        h.frames(10)
        assertEquals(listOf("a BLOCKED"), h.calls, "the frame adds nothing")
        assertEquals(Vec2.ZERO, h.state("a").pos)
        // A drop on the way alone refuses too; only the first reading is the table under the character.
        h.clear()
        h.port.depth = { p -> if (p.u > 0.05f && p.u < 0.15f) DROP else 0f }
        h.tapAt(Vec2(0.12f, 0f))
        assertEquals(listOf("a BLOCKED"), h.calls)
        assertNull(h.state("a").target)
    }

    @Test
    fun noDepthReadingNeverRefuses() {
        val h = TouchHost().start("a")
        h.port.depth = { null }
        h.tapAt(Vec2(0.35f, -0.25f))
        assertEquals(Vec2(0.35f, -0.25f), h.state("a").target)
        assertEquals(List(WALK + 1) { "null" }, wayOf(h.lines.single()))
        // Gaps in the readings refuse nothing either: only readings are compared.
        h.port.depth = { p -> if (p.u < -0.2f) null else 0f }
        h.tapAt(Vec2(-0.35f, 0.2f))
        assertEquals(Vec2(-0.35f, 0.2f), h.state("a").target)
        assertTrue(h.calls.none { it.endsWith("BLOCKED") })
        assertTrue(h.lines.none { it.startsWith("tap_past_edge") })
    }

    @Test
    fun aTapWithNobodyOnTheTableDoesNothing() {
        val h = TouchHost().start()
        assertTrue(h.engine.anchored)
        h.tapAt(Vec2(0.1f, 0.1f))
        h.tap(540f, 1300f)
        assertEquals(emptyList(), h.asked, "no screen, no depth, no way")
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.calls)
        assertEquals(emptyList(), h.loop.ids)
    }

    @Test
    fun tapAndTouchBeforeTheAnchorDoNothing() {
        val h = TouchHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.spriteBuilt("a", false, QUAD_W, QUAD_H)
        h.engine.spriteBuilt("b", false, QUAD_W, QUAD_H)
        h.tapAt(Vec2(0.1f, 0.1f))
        h.tap(540f, 1080f)
        assertFalse(h.down(540f, 1080f))
        assertFalse(h.move(TouchPoint(0, 560f, 1100f)))
        assertFalse(h.up(560f, 1100f))
        assertFalse(h.cancel())
        assertEquals(emptyList(), h.asked)
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.calls)
        assertEquals(emptyList(), h.loop.ids)
        assertFalse(h.engine.anchored)
        // The anchor then works as ever, and nothing was queued.
        h.anchor()
        assertEquals(listOf("anchor", "a PLACED", "b PLACED", "tracking true"), h.calls)
        h.frames(5)
        assertNull(h.state("a").target)
        assertNull(h.state("b").target)
    }

    @Test
    fun theTapsOwnReadingIsTheLastOfTheWay() {
        // The way reads nothing; the host's reading at the tap (its own hit test's depth point) is a small one.
        val h = TouchHost().start("a")
        val uv = Vec2(0.20f, 0.10f)
        val (x, y) = h.port.camera.px(uv)
        h.engine.onTap(x, y, uv) { h.asked += "depth"; -0.01f }
        val way = wayOf(h.lines.single())
        assertEquals(WALK + 1, way.size, "walkDepthSamples + 1")
        assertEquals(List(WALK) { "null" }, way.take(WALK), "the way's samples first")
        assertTrue(way[WALK] != "null", "the tap's own reading last: $way")
        assertEquals(uv, h.state("a").target)
        // A drop only in the tap's own reading refuses the tap.
        val g = TouchHost().start("a")
        g.engine.onTap(x, y, uv) { g.asked += "depth"; DROP }
        assertEquals(listOf("a BLOCKED"), g.calls)
        assertNull(g.state("a").target)
        assertEquals(listOf("depth", "way $WALK"), g.asked)
        assertEquals(List(WALK) { "null" }, wayOf(g.lines[0]).take(WALK))
        assertLine("tap_past_edge u= v=", g.lines[1])
    }

    @Test
    fun theTapsDepthIsReadOnlyWhenTheTapWalks() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        // A tap on b selects it: the screen is asked, the depth never.
        assertTrue(h.tap(h.centreOf("b").first, h.centreOf("b").second))
        assertEquals(listOf("screen"), h.asked)
        assertEquals(listOf("active b"), h.calls)
        // A tap on the table: the pick first, then the depth once, then the way.
        h.clear()
        h.tapAt(Vec2(0f, -0.15f))
        assertEquals(listOf("screen", "depth", "way $WALK"), h.asked)
        // A tap the depth refuses read it the same way, once.
        h.clear()
        h.port.depth = { p -> if (p.v > 0.1f) DROP else 0f }
        h.tapAt(Vec2(0f, 0.15f))
        assertEquals(listOf("screen", "depth", "way $WALK"), h.asked)
        assertTrue("a BLOCKED" in h.calls)
        // With nobody to walk, nothing is read.
        val e = TouchHost().start()
        e.tapAt(Vec2(0f, 0f))
        assertEquals(emptyList(), e.asked)
    }

    @Test
    fun aTapDuringALossSetsTheTargetAndKeepsThePause() {
        val h = TouchHost().start("a")
        h.frames(3)
        h.clear()
        h.frame(tracking = false) // the anchor is lost, the camera still tracks: the only way the host's hit test hits
        assertEquals(listOf("tracking false", "a PAUSED"), h.calls)
        h.tapAt(Vec2(0.10f, 0f))
        assertEquals(Mode.PAUSED, h.state("a").mode, "the pause is kept")
        assertEquals(Vec2(0.10f, 0f), h.state("a").target, "the target is set")
        assertEquals(listOf("depth", "way $WALK"), h.asked, "nothing here looks at tracking")
        h.frames(5, tracking = false)
        assertEquals(Vec2.ZERO, h.state("a").pos, "nobody moves while paused")
        assertEquals(listOf("tracking false", "a PAUSED"), h.calls)
    }

    @Test
    fun theWalkOfATapMadeDuringALossIsReportedAsResumedOnly() {
        val h = TouchHost().start("a")
        h.frames(3)
        h.frame(tracking = false)
        h.clear()
        h.tapAt(Vec2(0.10f, 0f))
        h.frame(tracking = false, gapMs = 3_000)
        h.frame() // the first frame back
        assertEquals(Mode.WALKING, h.state("a").mode)
        assertEquals(listOf("tracking true", "a RESUMED"), h.calls, "STARTED_WALKING is folded into RESUMED (T47 finding 1)")
        assertTrue(h.walkUntilArrived("a"))
        assertEquals(listOf("tracking true", "a RESUMED", "a ARRIVED"), h.calls)
        assertNear(Vec2(0.10f, 0f), h.state("a").pos, "arrived")
    }

    @Test
    fun tapsAndTouchesNeverPlaceAnAnchor() {
        val h = TouchHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.spriteBuilt("a", false, QUAD_W, QUAD_H)
        h.engine.spriteBuilt("b", false, QUAD_W, QUAD_H)
        val rnd = Random(54)
        fun some() {
            repeat(100) {
                val x = rnd.nextInt(0, 1080).toFloat()
                val y = rnd.nextInt(0, 2160).toFloat()
                h.tap(x, y)
                h.down(x, y)
                h.move(TouchPoint(0, x + 20f, y + 10f))
                h.now += 70
                h.move(TouchPoint(0, x + 40f, y + 20f))
                h.up(x + 40f, y + 20f)
                h.frame()
            }
        }
        some()
        assertFalse(h.engine.anchored)
        assertEquals(0, h.calls.count { it == "anchor" })
        h.anchor()
        some()
        assertEquals(1, h.calls.count { it == "anchor" })
        assertTrue(h.lines.none { it == "anchor_refused" })
        assertTrue(h.engine.anchored)
    }

    // --- Picking ---

    @Test
    fun aTapOnAMemberOnlyMakesItActive() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.tap(h.centreOf("b").first, h.centreOf("b").second))
        assertEquals(listOf("active b"), h.calls)
        assertEquals(listOf("screen"), h.asked, "no depth, no way")
        assertEquals(emptyList(), h.lines)
        assertNull(h.state("a").target)
        assertNull(h.state("b").target)
        // A tap on the active one does nothing more: no callback, no depth, no way.
        h.clear()
        assertTrue(h.tap(h.centreOf("a").first, h.centreOf("a").second))
        assertEquals(emptyList(), h.calls)
        assertEquals(listOf("screen"), h.asked)
        assertEquals(emptyList(), h.lines)
        assertNull(h.state("a").target)
        // The HUD makes b active: a tap on the table now walks b, and a tap on b does nothing more.
        h.latest = h.slotOf(member("a"), member("b"), activeId = "b")
        h.clear()
        h.tapAt(Vec2(0.10f, -0.15f))
        assertEquals(Vec2(0.10f, -0.15f), h.state("b").target)
        assertNull(h.state("a").target)
        h.clear()
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.tap(h.centreOf("b").first, h.centreOf("b").second))
        assertEquals(emptyList(), h.calls)
    }

    @Test
    fun aTapBesideAMemberWalksTheActiveOne() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val r = h.rectOf("b")
        val x = (r.right + 1).toFloat()
        val y = r.cy.toFloat()
        assertTrue(h.tap(x, y))
        assertEquals(listOf("screen", "depth", "way $WALK"), h.asked)
        assertEquals(emptyList(), h.calls)
        assertNear(assertNotNull(h.port.camera.table(x, y)), h.state("a").target, "a walks to the table under the finger")
        assertNull(h.state("b").target)
    }

    @Test
    fun pickRectangleIsTheQuadPlusTheMargin() {
        for (size in listOf(0.5f, 2f)) {
            val h = TouchHost().start("a", "b")
            h.place("a" to Vec2(-0.07f, 0.15f), "b" to Vec2(0.07f, 0.15f)) // near the camera: the feet and the head differ in depth
            h.latest = h.slotOf(member("a"), member("b"), size = size) // the latest slot's size, read at the pick
            val r = h.rectOf("b")
            val label = "size $size"
            // The width comes from the feet depth: the head's would move each side by more than the probes' pixel.
            val headHalfW = r.pxPerMHead * QUAD_W * size * 0.5 + r.margin
            assertTrue(abs(headHalfW - r.halfW) > 1.5, "$label: the camera tells feet from head (${headHalfW - r.halfW} px)")
            assertTrue(r.feetY > r.headY, "$label: the feet are below the head on the screen")
            fun pick(x: Double, y: Double) = h.pickAt(x.toFloat(), y.toFloat())
            assertEquals("b", pick(r.right - 1, r.cy), "$label right, inside")
            assertNull(pick(r.right + 1, r.cy), "$label right, outside")
            assertEquals("b", pick(r.left + 1, r.cy), "$label left, inside")
            assertNull(pick(r.left - 1, r.cy), "$label left, outside")
            assertEquals("b", pick(r.cx, r.top + 1), "$label top, inside")
            assertNull(pick(r.cx, r.top - 1), "$label top, outside")
            assertEquals("b", pick(r.cx, r.bottom - 1), "$label bottom, inside")
            assertNull(pick(r.cx, r.bottom + 1), "$label bottom, outside")
            // The quad alone would end a margin earlier on every side (the probes inside lie in the margin band).
            assertTrue(r.margin > 2)
        }
    }

    @Test
    fun theNearestCentreWinsAlsoThroughAMemberInFront() {
        val h = TouchHost().start("a", "b")
        // a stands 10 cm in front of b, toward the camera: a's quad covers the lower part of b's on the screen.
        h.place("a" to Vec2(0f, 0.10f), "b" to Vec2(0.02f, 0f))
        val ra = h.rectOf("a")
        val rb = h.rectOf("b")
        assertTrue(h.port.camera.pixel(d3(Vec2(0f, 0.10f))).third < h.port.camera.pixel(d3(Vec2(0.02f, 0f))).third, "a is nearer the camera")
        val left = maxOf(ra.left, rb.left)
        val right = minOf(ra.right, rb.right)
        val top = maxOf(ra.top, rb.top)
        val bottom = minOf(ra.bottom, rb.bottom)
        assertTrue(left < right && top < bottom, "the two rectangles overlap")
        // The corner of the overlap nearest b's centre is nearer b's centre than a's, though it is on a's quad.
        val x = (if (abs(left - rb.cx) < abs(right - rb.cx)) left + 0.5 else right - 0.5)
        val y = top + 0.5
        fun dist(r: PickRect) = sqrt((x - r.cx) * (x - r.cx) + (y - r.cy) * (y - r.cy))
        assertTrue(dist(rb) + 5 < dist(ra), "b's centre is nearer: ${dist(rb)} < ${dist(ra)}")
        assertEquals("b", h.pickAt(x.toFloat(), y.toFloat()), "nearest centre in pixels, not nearest to the camera")
        // At the bottom of the overlap a's centre is the nearer one.
        val y2 = bottom - 0.5
        val x2 = (left + right) / 2
        fun dist2(r: PickRect) = sqrt((x2 - r.cx) * (x2 - r.cx) + (y2 - r.cy) * (y2 - r.cy))
        assertTrue(dist2(ra) < dist2(rb))
        assertEquals("a", h.pickAt(x2.toFloat(), y2.toFloat()))
        // A tap there makes b active as well.
        assertTrue(h.tap(x.toFloat(), y.toFloat()))
        assertEquals(listOf("active b"), h.calls)
    }

    @Test
    fun oneMemberIsNeverPicked() {
        val h = TouchHost().start("a")
        h.place("a" to Vec2(0.05f, 0f))
        val (x, y) = h.centreOf("a")
        // A tap on it walks it, to the table under the finger (behind its feet).
        assertTrue(h.tap(x, y))
        assertEquals(listOf("depth", "way $WALK"), h.asked, "the screen is not asked")
        val under = assertNotNull(h.port.camera.table(x, y))
        assertNear(under, h.state("a").target, "the table point under the finger")
        assertTrue(under.v < 0f, "behind its feet, seen from the camera")
        assertEquals(emptyList(), h.calls)
        // A finger on it is not captured.
        h.clear()
        assertFalse(h.down(x, y))
        assertFalse(h.up(x, y))
        assertEquals(emptyList(), h.asked)
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.calls)
    }

    @Test
    fun aMemberInTheAirOrPausedIsNotPicked() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        h.frames(2)
        assertTrue(h.loop.jump("b"))
        h.frames(8)
        assertEquals(Mode.JUMPING, h.state("b").mode)
        assertTrue(h.state("b").h > 0f)
        h.clear()
        val (x, y) = h.centreOf("b") // where b is drawn now, in the air
        assertTrue(h.tap(x, y))
        assertEquals(emptyList(), h.calls, "b is not made active")
        assertNear(assertNotNull(h.port.camera.table(x, y)), h.state("a").target, "a walks to the point behind b")
        assertNull(h.pickAt(x, y), "nor grabbed")
        // While paused nobody is picked either: the tap walks the active member, which keeps the pause.
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        h.frames(2)
        h.frame(tracking = false)
        assertEquals(Mode.PAUSED, h.state("b").mode)
        h.clear()
        val (bx, by) = h.centreOf("b")
        assertNull(h.pickAt(bx, by))
        assertTrue(h.tap(bx, by))
        assertEquals(listOf("screen", "depth", "way $WALK"), h.asked)
        assertEquals(emptyList(), h.calls)
        assertNear(assertNotNull(h.port.camera.table(bx, by)), h.state("a").target, "a, paused, gets b's point as its target")
        assertEquals(Mode.PAUSED, h.state("a").mode)
        // A walking member is picked.
        h.frame()
        h.loop.walkTo("b", Vec2(0.08f, -0.05f))
        h.frame()
        assertEquals(Mode.WALKING, h.state("b").mode)
        assertEquals("b", h.pickAt(h.centreOf("b").first, h.centreOf("b").second))
    }

    @Test
    fun aMemberWithoutAPictureIsNotPicked() {
        val h = TouchHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.spriteBuilt("a", false, QUAD_W, QUAD_H)
        h.engine.spriteBuilt("b", false) // a picture announced without its size
        h.anchor()
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        h.clear()
        val (x, y) = h.centreOf("b")
        assertNull(h.pickAt(x, y))
        assertTrue(h.tap(x, y))
        assertEquals(emptyList(), h.calls)
        assertNotNull(h.state("a").target, "the tap walks the active one")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        // Announced with its size: picked.
        h.engine.spriteBuilt("b", false, QUAD_W, QUAD_H)
        assertEquals("b", h.pickAt(x, y))
        // Gone: not picked, though it still stands on the table.
        h.engine.spriteGone("b")
        assertTrue(h.loop.has("b"))
        assertNull(h.pickAt(x, y))
        // Never announced at all: not picked.
        val g = TouchHost()
        g.latest = g.slotOf(member("a"), member("c"))
        g.engine.spriteBuilt("a", false, QUAD_W, QUAD_H)
        g.anchor()
        g.place("a" to Vec2(-0.08f, 0f), "c" to Vec2(0.08f, 0f))
        assertNull(g.pickAt(g.centreOf("c").first, g.centreOf("c").second))
        assertEquals("a", g.pickAt(g.centreOf("a").first, g.centreOf("a").second))
    }

    @Test
    fun withoutAScreenTheNearestFeetWithinReachArePicked() {
        for (surface in Surface.entries) {
            val k = SurfaceProfile.of(surface).scale
            val camera = if (surface == Surface.TABLE) Pinhole() else Pinhole(eye = D3(0.0, 1.3, 1.6))
            for (size in listOf(1f, 0.25f)) {
                val label = "$surface size $size"
                val h = TouchHost(surface, camera)
                h.port.table = Polygon.rect(Vec2.ZERO, 0.80f * k, 0.60f * k)
                h.start("a", "b", size = size)
                h.port.laidOut = false
                h.place("a" to Vec2(-0.10f, 0f) * k, "b" to Vec2(0.10f, 0f) * k)
                val profile = SurfaceProfile.of(surface)
                val reach = maxOf(profile.radiusAt(size) * 1.5f, 0.03f * profile.scale)
                if (size == 1f) assertEquals(0.045f * k, reach, 1e-6f, label) else assertEquals(0.03f * k, reach, 1e-6f, label)
                fun pickOn(uv: Vec2): String? {
                    val (x, y) = h.port.camera.px(uv)
                    return h.pickAt(x, y)
                }
                val b = Vec2(0.10f, 0f) * k
                assertEquals("b", pickOn(b + Vec2(reach - 0.001f * k, 0f)), "$label just inside the reach")
                assertNull(pickOn(b + Vec2(reach + 0.001f * k, 0f)), "$label just outside")
                assertEquals("b", pickOn(b + Vec2(0f, -(reach - 0.001f * k))), "$label any direction")
                assertEquals("a", pickOn(Vec2(-0.10f, 0.01f) * k), label)
                h.clear()
                assertTrue(h.down(h.port.camera.px(b)))
                assertEquals(listOf("screen", "hit"), h.asked, "$label: one hit test")
                h.cancel()
                // only the nearest is tried: with both within reach, the nearer feet win
                h.place("a" to Vec2(-0.035f, 0f) * k, "b" to Vec2(0.035f, 0f) * k)
                if (size == 1f) {
                    assertEquals("b", pickOn(Vec2(0.005f, 0f) * k), label)
                    assertEquals("a", pickOn(Vec2(-0.005f, 0f) * k), label)
                }
                assertEquals(listOf(profile.tapHeightToleranceM), h.port.tolerances.distinct(), "$label: the tap's tolerance")
            }
        }
        // A port without a frame answers both questions with null: nobody is picked.
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.10f, 0f), "b" to Vec2(0.10f, 0f))
        h.port.frame = false
        val (x, y) = h.port.camera.px(Vec2(0.10f, 0f))
        assertFalse(h.down(x, y))
        assertEquals(listOf("screen", "hit"), h.asked)
        assertEquals(emptyList(), h.calls)
    }

    @Test
    fun pickAsksTheScreenOnlyWhenATouchNeedsIt() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        h.frames(5)
        assertEquals(emptyList(), h.asked, "never on a frame")
        val a = h.centreOf("a")
        val b = h.centreOf("b")
        assertTrue(h.down(a))
        assertEquals(listOf("screen"), h.asked, "DOWN")
        h.clear()
        assertTrue(h.touch(TouchAction.POINTER_DOWN, at(a, 0), at(b, 1), index = 1))
        assertEquals(listOf("screen"), h.asked, "POINTER_DOWN")
        h.clear()
        h.now += 100
        assertTrue(h.move(at(a.first + 30f to a.second, 0), at(b.first + 30f to b.second, 1)))
        h.frames(3)
        assertTrue(h.touch(TouchAction.POINTER_UP, at(a.first + 30f to a.second, 0), at(b.first + 30f to b.second, 1), index = 1))
        assertTrue(h.up(a.first + 30f, a.second))
        assertTrue(h.asked.none { it == "screen" }, "never on MOVE, POINTER_UP or UP: ${h.asked}")
        h.clear()
        assertTrue(h.down(b))
        assertTrue(h.cancel())
        assertEquals(listOf("screen"), h.asked, "CANCEL asks nothing")
        h.clear()
        h.tapAt(Vec2(0f, -0.2f))
        assertEquals(1, h.asked.count { it == "screen" }, "a tap with two members")
        // With one member nothing ever asks it.
        val one = TouchHost().start("a")
        one.down(one.centreOf("a"))
        one.tapAt(Vec2(0.1f, 0.1f))
        assertTrue(one.asked.none { it == "screen" })
    }

    @Test
    fun aPickRightAfterTheAnchorUsesTheScreen() {
        val h = TouchHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.spriteBuilt("a", false, QUAD_W, QUAD_H)
        h.engine.spriteBuilt("b", false, QUAD_W, QUAD_H)
        h.anchor() // a at the anchor, b beside it toward camera-right; no frame yet
        h.clear()
        val (x, y) = h.centreOf("b")
        assertTrue(h.down(x, y))
        assertEquals(listOf("screen"), h.asked, "the screen picks; no hit test")
        assertEquals(listOf("active b"), h.calls)
        assertLine("grab pointer=0 id=b", h.lines.single())
    }

    @Test
    fun aMemberBehindTheCameraIsNotPicked() {
        // A camera low over the table, looking along it: b stands 1.2 m behind it.
        val camera = Pinhole(eye = D3(0.0, 0.06, 0.0), at = D3(0.0, 0.03, -1.0))
        val h = TouchHost(camera = camera).start("a", "b")
        h.port.table = Polygon.rect(Vec2.ZERO, 4f, 4f)
        h.place("a" to Vec2(0.15f, -0.5f), "b" to Vec2(0f, 1.2f))
        // Without the behind-the-camera test, b would land mirrored at this pixel, its rectangle still a few pixels wide.
        val feet = camera.pixel(d3(Vec2(0f, 1.2f)))
        val head = camera.pixel(d3(Vec2(0f, 1.2f), QUAD_H))
        assertTrue(feet.third < 0 && head.third < 0, "behind the camera")
        val mirroredHalfW = camera.focalPx / feet.third * QUAD_W * 0.5 + camera.margin
        assertTrue(mirroredHalfW > 1.0, "the mirrored rectangle is not empty: $mirroredHalfW")
        val x = ((feet.first + head.first) / 2).toFloat()
        val y = ((feet.second + head.second) / 2).toFloat()
        assertTrue(x in 0f..1080f && y in 0f..2160f, "the mirrored point is on the screen: $x, $y")
        assertNull(h.pickAt(x, y))
        assertEquals("a", h.pickAt(h.centreOf("a").first, h.centreOf("a").second), "the one in front is")
    }

    @Test
    fun otherActionsChangeNothing() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val a = h.centreOf("a")
        assertFalse(h.touch(TouchAction.OTHER, at(a)), "nothing captured: false")
        assertEquals(emptyList(), h.asked)
        assertEquals(emptyList(), h.lines)
        assertTrue(h.down(a))
        h.clear()
        assertTrue(h.touch(TouchAction.OTHER, at(a)), "a captured stream stays captured")
        assertTrue(h.touch(TouchAction.OTHER, at(a), TouchPoint(1, 10f, 10f), index = 1))
        assertEquals(emptyList(), h.asked)
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.calls)
        // The binding is still there: the next move leads.
        h.now += 60
        val to = h.port.camera.px(Vec2(-0.05f, -0.05f))
        assertTrue(h.move(at(to)))
        assertEquals(listOf("hit", "way $LEAD"), h.asked)
        assertNotNull(h.state("a").target)
    }

    @Test
    fun pickAgreesWithTheOldWorldSpaceProjection() {
        val rnd = Random(5_454)
        val ids = listOf("a", "b", "c")
        val h = TouchHost().start(*ids.toTypedArray())
        var near = 0
        var picked = 0
        var compared = 0
        val wrong = ArrayList<String>()
        repeat(10_000) { n ->
            // The anchor: a yaw and a small tilt, within a metre.
            val yaw = rnd.between(0.0, 2 * PI)
            val tilt = rnd.between(-0.05, 0.05)
            val tiltAxis = rnd.between(0.0, 2 * PI)
            val qd = doubleArrayOf(
                sin(tilt / 2) * cos(tiltAxis), sin(yaw / 2) * cos(tilt / 2), sin(tilt / 2) * sin(tiltAxis), cos(yaw / 2) * cos(tilt / 2),
            )
            val qn = sqrt(qd.sumOf { it * it })
            val q = FloatArray(4) { (qd[it] / qn).toFloat() }
            val t = floatArrayOf(rnd.between(-1.0, 1.0).toFloat(), rnd.between(-1.0, 0.3).toFloat(), rnd.between(-1.0, 1.0).toFloat())
            // Three members around a point of the table, some on a book.
            val centre = Vec2(rnd.between(-0.2, 0.2).toFloat(), rnd.between(-0.2, 0.2).toFloat())
            val spots = ids.map { it to centre + Vec2(rnd.between(-0.2, 0.2).toFloat(), rnd.between(-0.2, 0.2).toFloat()) }
            h.place(*spots.toTypedArray())
            val books = spots.filter { rnd.nextDouble() < 0.4 }.mapIndexed { i, (_, p) ->
                LevelObject("book-$i", "book", ObjectKind.PLATFORM, Polygon.rect(p, 0.08f, 0.08f), rnd.between(0.005, 0.035).toFloat())
            }
            h.engine.level = Level(books, boundary = null)
            val size = listOf(0.5f, 0.75f, 1f, 1.5f, 2f)[rnd.nextInt(5)]
            h.latest = h.slotOf(*ids.map { member(it) }.toTypedArray(), size = size)
            val quads = HashMap<String, Pair<Float, Float>>()
            for (id in ids) {
                if (rnd.nextDouble() < 0.08) {
                    h.engine.spriteBuilt(id, false)
                } else {
                    val q2 = rnd.between(0.02, 0.10).toFloat() to rnd.between(0.04, 0.08).toFloat()
                    quads[id] = q2
                    h.engine.spriteBuilt(id, false, q2.first, q2.second)
                }
            }
            h.frame() // a book lifts whoever stands on it
            // The camera somewhere around and above, looking near the members; portrait or landscape.
            val phi = rnd.between(0.0, 2 * PI)
            val r = rnd.between(0.25, 0.8)
            val eyeP = D3(centre.u + r * cos(phi), rnd.between(0.12, 0.7), centre.v + r * sin(phi))
            val atP = D3(centre.u + rnd.between(-0.1, 0.1), rnd.between(0.0, 0.05), centre.v + rnd.between(-0.1, 0.1))
            fun toWorld(p: D3): D3 {
                val out = FloatArray(3)
                poseToWorld(q, t, p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), out)
                return D3(out[0].toDouble(), out[1].toDouble(), out[2].toDouble())
            }
            val upW = toWorld(D3(0.0, 1.0, 0.0)) - toWorld(D3(0.0, 0.0, 0.0))
            val view = lookAt(toWorld(eyeP), toWorld(atP), upW)
            val portrait = rnd.nextBoolean()
            val w = if (portrait) 1080 else 2340
            val hPx = if (portrait) 2340 else 1080
            val proj = perspective(rnd.between(45.0, 75.0), w, hPx)
            val margin = rnd.between(0.0, 60.0).toFloat()
            h.port.fixed = ScreenView.of(view, poseMatrix(q, t), proj, w, hPx, margin)
            val old = OldPick(q, t, view, proj, w, hPx, margin)
            // The touch: mostly at a member, sometimes anywhere.
            val members = h.loop.cast.members
            var x = rnd.between(-0.1 * w, 1.1 * w).toFloat()
            var y = rnd.between(-0.1 * hPx, 1.1 * hPx).toFloat()
            if (rnd.nextDouble() < 0.75) {
                val m = members[rnd.nextInt(members.size)].body.state
                val feet = FloatArray(4)
                val eye = FloatArray(4)
                val clip = FloatArray(4)
                poseToWorld(q, t, m.pos.u, m.h + 0.03f * size, m.pos.v, feet)
                feet[3] = 1f
                multiplyMV(eye, view, feet)
                multiplyMV(clip, proj, eye)
                if (clip[3] > 1e-3f) {
                    val pxPerM = proj[0] * w * 0.5f / clip[3]
                    x = (clip[0] / clip[3] + 1f) * 0.5f * w + rnd.between(-1.0, 1.0).toFloat() * (pxPerM * 0.03f * size + margin) * 1.3f
                    y = (1f - clip[1] / clip[3]) * 0.5f * hPx + rnd.between(-1.0, 1.0).toFloat() * (pxPerM * 0.04f * size + margin) * 1.3f
                }
            }
            val want = old.pick(x, y, members, quads, size)
            val got = h.pickAt(x, y)
            if (old.closest < 0.01f) {
                near++
            } else {
                compared++
                if (got != want) wrong += "case $n: old $want, engine $got at ($x, $y), closest ${old.closest}"
            }
            if (want != null) picked++
        }
        assertEquals(emptyList(), wrong.take(10), "${wrong.size} disagree")
        assertTrue(near < 100, "touches within 0.01 px of a threshold: $near of 10000 (under 1 %)")
        assertTrue(picked > 3_000, "the cases pick someone often: $picked")
        println("pickAgreesWithTheOldWorldSpaceProjection: picked=$picked near=$near compared=$compared")
        assertEquals(10_000, compared + near)
    }

    @Test
    fun projectionMatchesAPinholeCamera() {
        val cameras = listOf(
            "portrait" to Pinhole(w = 1080, h = 2160, fovYDeg = 65.0),
            "landscape" to Pinhole(eye = D3(0.1, 0.30, 0.42), at = D3(0.02, 0.0, -0.02), w = 2160, h = 1080, fovYDeg = 42.0, margin = 30f, anchorYaw = -2.1),
        )
        for ((label, camera) in cameras) for (size in listOf(1f, 1.5f)) {
            val h = TouchHost(camera = camera).start("a", "b", size = size)
            h.place("a" to Vec2(-0.12f, -0.02f), "b" to Vec2(0.06f, 0.03f))
            val r = h.rectOf("b")
            val what = "$label size $size"
            fun picks(x: Double, y: Double) = h.pickAt(x.toFloat(), y.toFloat()) == "b"
            /** The pick's edge between (x0, y0), which picks b, and (x1, y1), which does not: bisected to well under 0.001 px. */
            fun edge(x0: Double, y0: Double, x1: Double, y1: Double): Pair<Double, Double> {
                assertTrue(picks(x0, y0), "$what: inside")
                assertFalse(picks(x1, y1), "$what: outside")
                var lo = 0.0
                var hi = 1.0
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (picks(x0 + (x1 - x0) * mid, y0 + (y1 - y0) * mid)) lo = mid else hi = mid
                }
                val s = (lo + hi) / 2
                return (x0 + (x1 - x0) * s) to (y0 + (y1 - y0) * s)
            }
            val right = edge(r.cx, r.cy, r.right + 20, r.cy).first
            val left = edge(r.cx, r.cy, r.left - 20, r.cy).first
            val top = edge(r.cx, r.cy, r.cx, r.top - 20).second
            val bottom = edge(r.cx, r.cy, r.cx, r.bottom + 20).second
            val m = camera.margin.toDouble()
            assertEquals(r.cx, (left + right) / 2, 0.01, "$what: the quad's centre")
            assertEquals(r.headY, top + m, 0.01, "$what: the head")
            assertEquals(r.feetY, bottom - m, 0.01, "$what: the feet")
            assertEquals(r.pxPerMFeet * QUAD_W * size * 0.5, (right - left) / 2 - m, 0.01, "$what: pixels per metre at the feet")
        }
    }

    @Test
    fun spriteGoneAlsoDropsThePoserAndTheDrawnFacing() {
        val h = TouchHost()
        h.latest = h.slotOf(member("a"), member("b"))
        h.engine.spriteBuilt("a", true, QUAD_W, QUAD_H) // a's picture looks to its right
        h.engine.spriteBuilt("b", false, QUAD_W, QUAD_H)
        h.anchor()
        h.place("a" to Vec2(-0.05f, 0f), "b" to Vec2(0.10f, 0f))
        h.frame()
        // a walks to the left (screen-left): drawn looking right, it is mirrored; then straight at the camera, the flip holds.
        h.loop.walkTo("a", Vec2(-0.10f, 0f))
        assertTrue(h.walkUntilArrived("a"))
        h.loop.walkTo("a", Vec2(-0.10f, 0.03f))
        assertTrue(h.walkUntilArrived("a"))
        assertTrue(h.frame().first { it.id == "a" }.mirrored, "mirrored, and the deadband holds it")
        val bBefore = h.frame().first { it.id == "b" }.mirrored
        // The host drops a's picture while a stays on the table: its next pose is a new poser's, unflipped, drawn facing left.
        h.engine.spriteGone("a")
        assertTrue(h.loop.has("a"))
        val bare = BillboardPoser("a", SurfaceProfile.TABLE.characterHeightM, false, BIAS)
        fun want(): NodePose {
            val b = assertNotNull(h.loop.body("a"))
            return bare.pose(
                b.state, h.port.camera.frame.pos, h.port.camera.frame.heightM, h.loop.walkPhaseMs("a"),
                verticalVelocity = b.verticalVelocity, sinceLandedMs = h.now - h.loop.landedAtMs("a"),
                size = h.latest.characterSize, emotePose = h.loop.emotePose("a"),
            )
        }
        h.now += FRAME_MS
        val first = h.engine.onFrame(FrameInput(h.now, true, h.port.camera.frame))
        val wantFirst = want()
        assertEquals(wantFirst, first.first { it.id == "a" })
        assertFalse(wantFirst.mirrored, "unflipped")
        assertEquals(bBefore, first.first { it.id == "b" }.mirrored, "b keeps its poser")
        // Walking to screen-right now mirrors it: the picture is taken as drawn facing left.
        h.loop.walkTo("a", Vec2(0f, 0.03f))
        var mirroredSeen = false
        repeat(200) {
            h.now += FRAME_MS
            val p = h.engine.onFrame(FrameInput(h.now, true, h.port.camera.frame)).first { it.id == "a" }
            assertEquals(want(), p)
            mirroredSeen = mirroredSeen || p.mirrored
        }
        assertTrue(mirroredSeen, "walking right, a left-facing picture is mirrored")
        assertNull(h.pickAt(h.centreOf("a").first, h.centreOf("a").second), "and its size is gone: not picked")
    }

    // --- The grab ---

    @Test
    fun aFingerOnAMemberBindsItAndMakesItActive() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.down(h.centreOf("b")))
        assertEquals(listOf("active b"), h.calls)
        assertLine("grab pointer=0 id=b", h.lines.single())
        assertEquals(listOf("screen"), h.asked, "no table question")
        assertTrue(h.up(h.centreOf("b").first, h.centreOf("b").second))
        // Also when it is already active (as found: the tap checks first, the grab does not).
        h.latest = h.slotOf(member("a"), member("b"), activeId = "b")
        h.clear()
        assertTrue(h.down(h.centreOf("b")))
        assertEquals(listOf("active b"), h.calls)
        assertLine("grab pointer=0 id=b", h.lines.single())
        // The first member, active by default, too.
        h.cancel()
        h.latest = h.slotOf(member("a"), member("b"))
        h.clear()
        assertTrue(h.down(h.centreOf("a"), pointer = 7))
        assertEquals(listOf("active a"), h.calls)
        assertLine("grab pointer=7 id=a", h.lines.single())
    }

    @Test
    fun aFingerOnTheTableIsNotCaptured() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val p = h.port.camera.px(Vec2(0f, -0.2f))
        assertFalse(h.down(p))
        h.now += 80
        assertFalse(h.move(at(p.first + 40f to p.second)))
        h.now += 80
        assertFalse(h.move(at(p.first + 80f to p.second)))
        assertFalse(h.up(p.first + 80f, p.second))
        assertEquals(listOf("screen"), h.asked, "the pick on DOWN, nothing else")
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.calls)
        assertNull(h.state("a").target)
        assertNull(h.state("b").target)
    }

    @Test
    fun aTapOnAMemberThroughTheGrabWalksItToThePointUnderTheFinger() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val r = h.rectOf("b")
        val x = r.cx.toFloat()
        val y = (r.headY + 4).toFloat() // a finger on its head
        assertTrue(h.down(x, y))
        assertTrue(h.up(x, y))
        assertEquals(listOf("screen", "hit", "way $LEAD"), h.asked, "one hit, one lead")
        val under = assertNotNull(h.port.camera.table(x, y))
        assertNear(under, h.state("b").target, "b walks to the table point under the finger")
        assertTrue(under.distanceTo(Vec2(0.08f, 0f)) > 0.03f, "behind its feet: ${under.distanceTo(Vec2(0.08f, 0f))} m")
        assertEquals(listOf("grab pointer=0 id=b", "release pointer=0 id=b"), h.lines)
        assertEquals(listOf("active b"), h.calls)
    }

    @Test
    fun leadIsThrottledToSixtyMsAndFiveMillimetres() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val t = h.now
        assertTrue(h.down(h.centreOf("a")))
        h.clear()
        val p1 = h.port.camera.px(Vec2(-0.08f, -0.04f))
        h.now = t + 1
        assertTrue(h.move(at(p1)))
        h.now = t + 59
        assertTrue(h.move(at(p1)))
        assertEquals(emptyList(), h.asked, "nothing asked before 60 ms, also right after DOWN")
        h.now = t + 60
        assertTrue(h.move(at(p1)))
        assertEquals(listOf("hit", "way $LEAD"), h.asked, "at 60 ms: one hit test, then the way")
        val first = assertNotNull(h.state("a").target)
        h.clear()
        h.now = t + 119
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.08f, -0.08f)))))
        assertEquals(emptyList(), h.asked, "59 ms after the last lead")
        // Under 5 mm from the last lead: a hit test, no way, the same target.
        h.now = t + 120
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.08f, -0.036f)))))
        assertEquals(listOf("hit"), h.asked)
        assertEquals(first, h.state("a").target)
        // The refused move restarted the clock: 59 ms later nothing, 60 ms later a lead 5 mm or more away.
        h.clear()
        h.now = t + 179
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.08f, -0.06f)))))
        assertEquals(emptyList(), h.asked)
        h.now = t + 180
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.08f, -0.06f)))))
        assertEquals(listOf("hit", "way $LEAD"), h.asked)
        assertNear(Vec2(-0.08f, -0.06f), h.state("a").target, "led")
    }

    @Test
    fun aLeadIsClampedIntoTheBoundaryAndGrowsNothing() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.30f, 0.30f))
        h.engine.level = level
        assertTrue(h.down(h.centreOf("a")))
        h.clear()
        h.now += 60
        val p = h.port.camera.px(Vec2(-0.25f, -0.05f)) // 10 cm past the boundary, on the table
        assertTrue(h.move(at(p)))
        val under = assertNotNull(h.port.camera.table(p.first, p.second))
        val target = assertNotNull(h.state("a").target)
        assertEquals(level.nearestInside(under), target, "the nearest point inside the boundary")
        assertTrue(level.boundary!!.contains(target))
        assertTrue(target.distanceTo(under) > 0.09f)
        assertSame(level, h.engine.level, "a lead grows nothing")
        assertEquals(emptyList(), h.levels)
        assertEquals(emptyList(), h.lines)
        assertEquals(emptyList(), h.calls)
        // Before a scan there is no boundary: unclamped.
        h.engine.level = Level(emptyList(), boundary = null)
        h.now += 60
        val q = h.port.camera.px(Vec2(-0.30f, 0.10f))
        assertTrue(h.move(at(q)))
        assertNear(assertNotNull(h.port.camera.table(q.first, q.second)), h.state("a").target, "unclamped")
    }

    @Test
    fun aFingerOffTheTableLeavesTheLastTarget() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.down(h.centreOf("a")))
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.20f, -0.05f)))))
        val last = assertNotNull(h.state("a").target)
        h.clear()
        h.now += 60
        val off = h.port.camera.px(Vec2(-0.55f, -0.05f)) // past the table's edge
        assertNull(h.port.camera.table(off.first, off.second)?.takeIf { h.port.onTable(it) })
        assertTrue(h.move(at(off)))
        assertEquals(listOf("hit"), h.asked)
        assertEquals(last, h.state("a").target, "the last target stays")
        h.clear()
        assertTrue(h.up(off.first, off.second))
        assertEquals(listOf("hit"), h.asked, "lifting off the table: no lead either")
        assertEquals(last, h.state("a").target)
        assertTrue(h.walkUntilArrived("a"))
        assertNear(last, h.state("a").pos, "it finishes its walk")
    }

    @Test
    fun aLeadPastADepthEdgeKeepsTheLastTarget() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        h.port.depth = { p -> if (p.u < -0.15f) DROP else 0f } // the table, then a drop left of u = -15 cm
        assertTrue(h.down(h.centreOf("a")))
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.12f, 0f)))))
        val last = assertNotNull(h.state("a").target)
        h.clear()
        h.now += 60
        val refused = Vec2(-0.20f, 0f)
        assertTrue(h.move(at(h.port.camera.px(refused))))
        assertEquals(listOf("hit", "way $LEAD"), h.asked)
        assertEquals(last, h.state("a").target, "the last target stays")
        val line = h.lines.single()
        assertTrue(line.startsWith("lead_past_edge id=a u=") && " v=" in line, line)
        assertEquals(LEAD + 1, wayOf(line).size, "the way and the finger's own reading")
        // As found: the refused point counts as the last one, so a finger resting within 5 mm of it is not asked again.
        h.clear()
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(refused + Vec2(0.003f, 0f)))))
        assertEquals(listOf("hit"), h.asked, "no way: within 5 mm of the refused point")
        assertEquals(last, h.state("a").target)
        assertEquals(emptyList(), h.lines)
    }

    @Test
    fun liftingLeadsOnceMoreThenReleases() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val t = h.now
        assertTrue(h.down(h.centreOf("a")))
        h.now = t + 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.10f, -0.05f)))))
        h.clear()
        // 1 ms later the finger lifts 4 cm away: the throttle does not apply.
        h.now = t + 61
        val lift = Vec2(-0.14f, -0.05f)
        val (x, y) = h.port.camera.px(lift)
        assertTrue(h.up(x, y), "UP returns true once")
        assertEquals(listOf("hit", "way $LEAD"), h.asked)
        assertNear(lift, h.state("a").target, "led to where the finger lifted")
        assertEquals(listOf("release pointer=0 id=a"), h.lines)
        assertFalse(h.up(x, y), "the stream is over")
        assertFalse(h.move(at(x to y)))
        // The 5 mm rule does apply to the last lead.
        val g = TouchHost().start("a", "b")
        g.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val t2 = g.now
        assertTrue(g.down(g.centreOf("a")))
        g.now = t2 + 60
        assertTrue(g.move(at(g.port.camera.px(Vec2(-0.10f, -0.05f)))))
        val led = g.state("a").target
        g.clear()
        g.now = t2 + 70
        val near = g.port.camera.px(Vec2(-0.10f, -0.047f))
        assertTrue(g.up(near.first, near.second))
        assertEquals(listOf("hit"), g.asked, "within 5 mm: no lead")
        assertEquals(led, g.state("a").target)
        assertEquals(listOf("release pointer=0 id=a"), g.lines)
    }

    @Test
    fun theStreamStaysCapturedUntilTheLastFingerLifts() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val a = h.centreOf("a")
        val table = h.port.camera.px(Vec2(0.05f, -0.20f))
        assertTrue(h.down(a))
        // A second finger on the bare table: still the engine's stream, and it walks nobody.
        assertTrue(h.touch(TouchAction.POINTER_DOWN, at(a, 0), at(table, 1), index = 1))
        h.now += 60
        val table2 = h.port.camera.px(Vec2(0.10f, -0.20f))
        assertTrue(h.move(at(a, 0), at(table2, 1)))
        h.now += 60
        assertTrue(h.touch(TouchAction.POINTER_UP, at(a, 0), at(table2, 1), index = 1))
        assertNull(h.state("b").target, "nobody walks to the second finger")
        assertEquals(listOf("grab pointer=0 id=a"), h.lines, "no grab or release for the table finger")
        h.clear()
        // Two bound fingers: POINTER_UP releases only its own.
        val b = h.centreOf("b")
        assertTrue(h.touch(TouchAction.POINTER_DOWN, at(a, 0), at(b, 1), index = 1))
        h.now += 60
        assertTrue(h.touch(TouchAction.POINTER_UP, at(a, 0), at(h.port.camera.px(Vec2(0.08f, -0.05f)), 1), index = 1))
        assertEquals(listOf("grab pointer=1 id=b", "release pointer=1 id=b"), h.lines)
        assertNear(Vec2(0.08f, -0.05f), h.state("b").target, "b led once more as its finger lifted")
        h.clear()
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.08f, -0.05f)), 0)))
        assertNear(Vec2(-0.08f, -0.05f), h.state("a").target, "a's finger still leads")
        assertTrue(h.up(h.port.camera.px(Vec2(-0.08f, -0.05f)).first, h.port.camera.px(Vec2(-0.08f, -0.05f)).second))
        assertEquals(listOf("release pointer=0 id=a"), h.lines)
        assertFalse(h.down(table), "a new stream on the table is not captured")
    }

    @Test
    fun twoFingersLeadTwoMembersOneFingerPerMember() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val a = h.centreOf("a")
        val b = h.centreOf("b")
        assertTrue(h.down(a))
        assertTrue(h.touch(TouchAction.POINTER_DOWN, at(a, 0), at(b, 3), index = 1))
        assertEquals(listOf("active a", "active b"), h.calls)
        // A third finger on a, already held: nothing.
        assertTrue(h.touch(TouchAction.POINTER_DOWN, at(a, 0), at(b, 3), at(a, 5), index = 2))
        assertEquals(listOf("grab pointer=0 id=a", "grab pointer=3 id=b"), h.lines)
        assertEquals(listOf("active a", "active b"), h.calls)
        h.clear()
        h.now += 60
        val toA = h.port.camera.px(Vec2(-0.12f, -0.06f))
        val toB = h.port.camera.px(Vec2(0.14f, -0.04f))
        assertTrue(h.move(at(toA, 0), at(toB, 3), at(toA, 5)))
        assertEquals(listOf("hit", "way $LEAD", "hit", "way $LEAD"), h.asked, "each bound finger leads; the third asks nothing")
        assertNear(Vec2(-0.12f, -0.06f), h.state("a").target, "a follows its finger")
        assertNear(Vec2(0.14f, -0.04f), h.state("b").target, "b follows its own")
        h.frames(30)
        assertTrue(h.up(toA.first, toA.second))
        assertEquals(2, h.lines.count { it.startsWith("release pointer=") })
    }

    @Test
    fun cancelReleasesEverythingWithoutALead() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        val a = h.centreOf("a")
        val b = h.centreOf("b")
        assertTrue(h.down(a))
        assertTrue(h.touch(TouchAction.POINTER_DOWN, at(a, 0), at(b, 1), index = 1))
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.10f, -0.05f)), 0), at(h.port.camera.px(Vec2(0.10f, -0.05f)), 1)))
        val ta = h.state("a").target
        val tb = h.state("b").target
        h.clear()
        h.now += 200
        assertTrue(h.cancel(), "the stream was the engine's")
        assertEquals(emptyList(), h.asked, "no lead")
        assertEquals(setOf("release pointer=0 id=a", "release pointer=1 id=b"), h.lines.toSet())
        assertEquals(2, h.lines.size)
        assertEquals(ta, h.state("a").target)
        assertEquals(tb, h.state("b").target)
        assertFalse(h.move(at(a, 0)), "nothing bound, nothing captured")
        assertFalse(h.cancel())
        assertEquals(emptyList(), h.asked)
    }

    @Test
    fun touchLogLinesKeepTheirKeys() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        h.engine.level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.30f, 0.30f))
        h.port.depth = { p -> if (p.v < -0.20f) DROP else 0f }
        assertTrue(h.down(h.centreOf("b"), pointer = 2))
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(0.08f, -0.25f)), 2))) // past the edge
        assertTrue(h.up(h.port.camera.px(Vec2(0.08f, -0.25f)).first, h.port.camera.px(Vec2(0.08f, -0.25f)).second, pointer = 2))
        h.tapAt(Vec2(0.25f, 0f)) // outside the boundary: grows it, walks
        val grownN = h.engine.level.boundary!!.size
        h.tapAt(Vec2(0f, -0.25f)) // past the edge
        val lines = h.lines
        assertEquals(7, lines.size, lines.joinToString("\n"))
        assertLine("grab pointer=2 id=b", lines[0])
        assertTrue(lines[1].endsWith("]"), lines[1])
        assertLine("lead_past_edge id=b u= v=", lines[1].substringBefore(" way="))
        assertEquals(LEAD + 1, wayOf(lines[1]).size)
        assertLine("release pointer=2 id=b", lines[2])
        assertTapLine("a", WALK + 1, lines[3])
        assertLine("boundary_grown n=$grownN u= v=", lines[4])
        assertTapLine("a", WALK + 1, lines[5])
        assertLine("tap_past_edge u= v=", lines[6])
        assertEquals(listOf("active b", "level", "a BLOCKED"), h.calls)
    }

    @Test
    fun aNewDownDropsWhatTheLastStreamLeft() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.down(h.centreOf("a")))
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.10f, -0.05f)))))
        val led = h.state("a").target
        h.clear()
        // The UP was lost: the next stream starts on the table.
        val table = h.port.camera.px(Vec2(0.02f, -0.22f))
        assertFalse(h.down(table, pointer = 4), "the new stream is not captured")
        assertEquals(listOf("release pointer=0 id=a"), h.lines)
        h.now += 60
        assertFalse(h.move(at(table, 4)))
        assertFalse(h.move(at(h.port.camera.px(Vec2(-0.2f, -0.1f)), 0)), "the old pointer leads nothing")
        assertEquals(listOf("screen"), h.asked)
        assertEquals(led, h.state("a").target)
    }

    @Test
    fun aGrabHeldThroughALossLeadsNowhereAndGoesOnAfterIt() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.down(h.centreOf("a")))
        h.frames(2)
        h.port.tracking = false // the camera is lost: the port answers no hit
        h.frame(tracking = false)
        h.clear()
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.12f, -0.05f)))))
        assertEquals(listOf("hit"), h.asked)
        assertNull(h.state("a").target)
        assertEquals(Mode.PAUSED, h.state("a").mode)
        h.port.tracking = true
        h.frame() // back
        h.clear()
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(-0.12f, -0.05f)))), "still bound on the frame back")
        assertEquals(listOf("hit", "way $LEAD"), h.asked)
        assertNear(Vec2(-0.12f, -0.05f), h.state("a").target, "it leads again")
        assertTrue(h.walkUntilArrived("a"))
    }

    @Test
    fun aReleaseDuringALossStillReleases() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.down(h.centreOf("a")))
        h.now += 60
        val p = h.port.camera.px(Vec2(-0.14f, -0.05f))
        assertTrue(h.move(at(p)))
        val target = assertNotNull(h.state("a").target)
        h.frames(5)
        h.port.tracking = false
        h.frame(tracking = false)
        h.clear()
        assertTrue(h.up(p.first + 50f, p.second), "released")
        assertEquals(listOf("hit"), h.asked, "no lead: no table under the finger")
        assertEquals(listOf("release pointer=0 id=a"), h.lines)
        assertEquals(Mode.PAUSED, h.state("a").mode)
        assertEquals(target, h.state("a").target, "the last target is kept")
        h.frames(10, tracking = false)
        h.port.tracking = true
        h.frame()
        assertTrue(h.walkUntilArrived("a"))
        assertNear(target, h.state("a").pos, "finished after the loss")
        assertTrue("a RESUMED" in h.calls)
    }

    @Test
    fun aGrabbedMemberRemovedFromTheCastIsReleased() {
        val h = TouchHost().start("a", "b")
        h.place("a" to Vec2(-0.08f, 0f), "b" to Vec2(0.08f, 0f))
        assertTrue(h.down(h.centreOf("b")))
        h.clear()
        h.latest = h.slotOf(member("a"))
        h.frame()
        assertEquals(listOf("release pointer=0 id=b", "cast_remove id=b"), h.lines, "released before cast_remove")
        assertFalse(h.loop.has("b"))
        h.clear()
        h.now += 60
        assertTrue(h.move(at(h.port.camera.px(Vec2(0.1f, -0.1f)))), "as found: the stream stays captured")
        assertEquals(emptyList(), h.asked, "its later MOVE does nothing")
        assertEquals(emptyList(), h.lines)
        assertTrue(h.up(0f, 0f))
        assertEquals(emptyList(), h.lines, "nothing left to release")
    }
}
