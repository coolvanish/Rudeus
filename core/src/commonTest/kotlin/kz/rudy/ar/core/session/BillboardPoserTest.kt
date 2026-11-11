package kz.rudy.ar.core.session

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmotePose
import kz.rudy.ar.core.emote.SpriteViews
import kz.rudy.ar.core.emote.ViewChoice
import kz.rudy.ar.core.emote.ViewPicker
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.session.GameLoop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Characterization tests of BillboardPoser, the billboard maths of the AR host (T52): where the character's quad
// stands, how it is turned, squashed, stretched and mirrored. Written against the class as it is; the same file runs
// against the copy in :core (only the package line and the class name differ), and nothing in it is imported from either
// package, so each copy binds to its own BillboardPoser, NodePose and constants. The rotation is asserted on what it
// does to the quad's axes (the quad's front is +Z, its up +Y; x = u, y = h, z = v), not on angles.
class BillboardPoserTest {
    /** The quad's height on the table (SurfaceProfile.TABLE.characterHeightM). */
    private val heightM = 0.06f

    /** The host's FEET_DEPTH_BIAS (androidApp ar/CharacterNode.kt), the value the Pixel runs with. */
    private val pixelBias = 0.6f

    private val front = Vec3(0f, 0f, 1f)
    private val up = Vec3(0f, 1f, 0f)
    private val deg = (PI / 180.0).toFloat()

    /** Seen from (0, 1), a character at the origin is looked at along -v: screen-right is +u. */
    private val camera = Vec2(0f, 1f)
    private val cameraH = 0.3f

    private fun poser(facesRight: Boolean = false, bias: Float = 0f) = BillboardPoser("hero", heightM, facesRight, bias)

    /** The direction from the character to a camera at [toCam], in the anchor frame, level. */
    private fun inPlane(toCam: Vec2) = Vec3(toCam.u, 0f, toCam.v).normalized()

    /** The viewer's right in the plane for a camera at [toCam] from the character: (look direction) x up. */
    private fun screenRight(toCam: Vec2) = Vec2(toCam.v, -toCam.u).normalized()

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1e-5f, "$what: expected $expected, got $actual")

    private fun near(expected: Vec2, actual: Vec2, what: String) {
        near(expected.u, actual.u, "$what (u)")
        near(expected.v, actual.v, "$what (v)")
    }

    private fun near(expected: Vec3, actual: Vec3, what: String) {
        near(expected.x, actual.x, "$what (x)")
        near(expected.y, actual.y, "$what (y)")
        near(expected.z, actual.z, "$what (z)")
    }

    private fun near(expected: Quat, actual: Quat, what: String) {
        near(expected.x, actual.x, "$what (x)")
        near(expected.y, actual.y, "$what (y)")
        near(expected.z, actual.z, "$what (z)")
        near(expected.w, actual.w, "$what (w)")
    }

    private fun scale(sx: Float, sy: Float, p: NodePose, what: String) {
        near(sx, p.scaleX, "$what: scaleX")
        near(sy, p.scaleY, "$what: scaleY")
    }

    private fun finite(p: NodePose): Boolean = listOf(
        p.pos.u, p.pos.v, p.h, p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w, p.scaleX, p.scaleY,
    ).all { it.isFinite() }

    private val walking = CharacterState(mode = Mode.WALKING, target = Vec2(0.3f, 0f))

    // ---- rotation ----

    @Test
    fun quadFrontPointsAtTheCameraInThePlane() {
        val feet = Vec2(0.05f, -0.03f)
        for (k in 0 until 24) {
            val a = k * PI / 12
            val toCam = Vec2(cos(a).toFloat(), sin(a).toFloat()) * 0.4f
            for (bias in listOf(0f, pixelBias)) for (facing in listOf(Vec2(1f, 0f), Vec2(0f, -1f))) {
                val p = BillboardPoser("hero", heightM, false, bias).pose(CharacterState(pos = feet, facing = facing), feet + toCam, 0.25f, 0L)
                val what = "camera at ${k * 15} deg, bias $bias, facing $facing"
                near(inPlane(toCam), p.rotation.rotate(front), "$what: the front points at the camera")
                near(up, p.rotation.rotate(up), "$what: Y-only, the quad stays upright")
            }
        }
    }

    @Test
    fun rotationIsYawThenTiltAboutTheQuadsOwnAxis() {
        // A left-drawn sprite at the origin walking toward a camera at (1, 0): yaw 90, along the line of sight, no flip.
        val p = poser().pose(CharacterState(facing = Vec2(1f, 0f)), Vec2(1f, 0f), cameraH, 0L, emotePose = EmotePose(tiltDeg = 30f))
        assertFalse(p.mirrored)
        near(Vec3(1f, 0f, 0f), p.rotation.rotate(front), "the front still points at the camera while it bows")
        near(Vec3(0f, cos(30f * deg), sin(30f * deg)), p.rotation.rotate(up), "the top leans 30 deg in the quad's plane, to the viewer's left (+v)")
        near(Quat(0.18301f, 0.68301f, 0.18301f, 0.68301f), p.rotation, "Q_y(90) * Q_z(30)")
        // The other order, Q_z(30) * Q_y(90), is told apart by the sign of x, and it tips the front 30 deg up.
        val other = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), 30f * deg) * Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 90f * deg)
        near(-0.18301f, other.x, "Q_z * Q_y")
        near(Vec3(cos(30f * deg), sin(30f * deg), 0f), other.rotate(front), "Q_z * Q_y tips the front up")
    }

    @Test
    fun zeroTiltIsAPureYaw() {
        val feet = Vec2(-0.1f, 0.2f)
        for (k in 0 until 36) {
            val a = k * PI / 18 + 0.05
            val toCam = Vec2(cos(a).toFloat(), sin(a).toFloat()) * 0.6f
            for (emote in listOf(null, EmotePose(scaleX = 1.1f, yawDeg = 40f, tiltDeg = 0f))) {
                val p = poser(bias = pixelBias).pose(CharacterState(pos = feet, facing = Vec2(0.6f, 0.8f)), feet + toCam, 0.4f, 0L, emotePose = emote)
                val what = "camera at ${k * 10} deg, emote $emote, mirrored ${p.mirrored}"
                near(0f, p.rotation.x, "$what: x")
                near(0f, p.rotation.z, "$what: z")
                val spin = Quat.fromAxisAngle(up, (emote?.yawDeg ?: 0f) * deg)
                near(spin.rotate(inPlane(toCam)), p.rotation.rotate(front), "$what: the yaw")
            }
        }
    }

    @Test
    fun bowLeansTheWayTheSpriteLooks() {
        val bow = EmotePose(tiltDeg = 30f)
        for (facesRight in listOf(false, true)) for (k in 0 until 8) {
            val a = k * PI / 4 + 0.3
            val toCam = Vec2(cos(a).toFloat(), sin(a).toFloat()) * 0.5f
            val right = screenRight(toCam)
            for (walksRight in listOf(true, false)) {
                val p = poser(facesRight, pixelBias).pose(CharacterState(facing = if (walksRight) right else -right), toCam, cameraH, 0L, emotePose = bow)
                val what = "facesRight=$facesRight, camera at ${k * 45} deg, walks screen-${if (walksRight) "right" else "left"}"
                assertEquals(walksRight != facesRight, p.mirrored, "$what: the picture looks the way it walks")
                val lean = p.rotation.rotate(up).dot(Vec3(right.u, 0f, right.v))
                near(if (walksRight) 0.5f else -0.5f, lean, "$what: the top leans sin 30 toward the side it looks at")
                near(inPlane(toCam), p.rotation.rotate(front), "$what: and the quad still faces the camera")
            }
        }
    }

    @Test
    fun emoteYawSpinsTheQuadAndLeavesTheFlipAlone() {
        // Seen from (0, -1) screen-right is -u, so facing -u walks screen-right. A spin of 180 would turn screen-right
        // around: if the flip read the spun yaw, the left-drawn picture would lose its mirror.
        val toCam = Vec2(0f, -1f)
        val walksRight = CharacterState(facing = Vec2(-1f, 0f))
        for (facesRight in listOf(false, true)) for (spin in listOf(90f, 180f, -135f)) {
            val plain = poser(facesRight).pose(walksRight, toCam, cameraH, 0L)
            val spun = poser(facesRight).pose(walksRight, toCam, cameraH, 0L, emotePose = EmotePose(yawDeg = spin))
            val what = "facesRight=$facesRight, spin $spin"
            assertEquals(!facesRight, plain.mirrored, "$what: walking screen-right")
            assertEquals(plain.mirrored, spun.mirrored, "$what: the flip never sees the spin")
            near(Quat.fromAxisAngle(up, spin * deg).rotate(inPlane(toCam)), spun.rotation.rotate(front), "$what: the front turns by the spin")
            near(up, spun.rotation.rotate(up), "$what: upright")
            scale(plain.scaleX, plain.scaleY, spun, what)
        }
    }

    // ---- flip ----

    @Test
    fun flipFollowsFacingAcrossTheLineOfSight() {
        // A left-drawn sprite is mirrored exactly when it walks to the viewer's right of the line of sight.
        val poser = poser(facesRight = false)
        fun mirrored(facing: Vec2, cam: Vec2 = camera) = poser.pose(CharacterState(facing = facing), cam, cameraH, 0L).mirrored
        assertTrue(mirrored(Vec2(1f, 0f)), "screen-right")
        assertFalse(mirrored(Vec2(-1f, 0f)), "screen-left")
        assertTrue(mirrored(Vec2(0.6f, 0.8f)), "toward the camera, to the right")
        assertTrue(mirrored(Vec2(0.6f, -0.8f)), "away, to the right")
        assertFalse(mirrored(Vec2(-0.6f, 0.8f)), "toward the camera, to the left")
        assertFalse(mirrored(Vec2(-0.6f, -0.8f)), "away, to the left")
        // Seen from the other side of the table, the same facing is on the other side of the line of sight.
        assertTrue(mirrored(Vec2(-1f, 0f), cam = Vec2(0f, -1f)), "-u is screen-right from (0, -1)")
        assertFalse(mirrored(Vec2(1f, 0f), cam = Vec2(0f, -1f)))
        // A camera at +u: screen-right is -v.
        assertTrue(mirrored(Vec2(0f, -1f), cam = Vec2(1f, 0f)), "-v is screen-right from (1, 0)")
        assertFalse(mirrored(Vec2(0f, 1f), cam = Vec2(1f, 0f)))
    }

    @Test
    fun flipUsesTheSpritesDrawnFacing() {
        for (facing in listOf(Vec2(1f, 0f), Vec2(-1f, 0f), Vec2(0.6f, 0.8f), Vec2(-0.6f, -0.8f))) {
            val left = poser(facesRight = false).pose(CharacterState(facing = facing), camera, cameraH, 0L)
            val right = poser(facesRight = true).pose(CharacterState(facing = facing), camera, cameraH, 0L)
            val walksRight = facing.u > 0f // screen-right is +u from (0, 1)
            assertEquals(walksRight, left.mirrored, "a left-drawn picture is mirrored to walk right ($facing)")
            assertEquals(!walksRight, right.mirrored, "a right-drawn one to walk left ($facing)")
        }
    }

    @Test
    fun flipHoldsInsideTheDeadbandAndStartsUnmirrored() {
        for (facesRight in listOf(false, true)) {
            val poser = poser(facesRight)
            // Seen from (0, 1), facing . screen-right is facing.u.
            fun mirrored(along: Float) = poser.pose(CharacterState(facing = Vec2(along, sqrt(1f - along * along))), camera, cameraH, 0L).mirrored
            val mirroring = if (facesRight) -1f else 1f // the side of the line of sight that mirrors this picture
            val what = "facesRight=$facesRight"
            assertFalse(mirrored(0f), "$what: starts unmirrored")
            assertFalse(mirrored(0.14f * mirroring), "$what: inside the band nothing changes")
            assertTrue(mirrored(0.16f * mirroring), "$what: out of the band")
            assertTrue(mirrored(0.14f * mirroring), "$what: held")
            assertTrue(mirrored(0f), "$what: held along the line of sight")
            assertTrue(mirrored(-0.14f * mirroring), "$what: held on the other side of it")
            assertTrue(mirrored(-FLIP_DEADBAND * mirroring), "$what: the band's edge itself holds")
            assertFalse(mirrored(-0.16f * mirroring), "$what: out on the other side")
            assertFalse(mirrored(FLIP_DEADBAND * mirroring), "$what: held unmirrored at the edge")
        }
    }

    /**
     * The walk of `ViewPickerTest.noneMatchesTheHostFlipRule` (the same seed and moves), with the poser in place of the
     * copied host rule: 2 000 frames, the character and the camera both moving, the camera at least 10 cm away in the plane.
     */
    @Test
    fun flipMatchesViewPickerForASideBaseSprite() {
        for (facesRight in listOf(false, true)) {
            val poser = poser(facesRight, pixelBias)
            val views = SpriteViews.of(SpriteView.SIDE, facesRight, emptyMap())
            val rnd = Random(41)
            var previous: ViewChoice? = null
            var pos = Vec2(0f, 0f)
            var heading = 0f
            var orbit = 0f
            var inBand = 0
            var mirroredFrames = 0
            repeat(2_000) { i ->
                heading += if (rnd.nextInt(10) == 0) rnd.nextFloat() * 6f - 3f else rnd.nextFloat() * 0.3f - 0.15f
                val facing = Vec2(cos(heading), sin(heading))
                pos = (pos + facing * 0.01f).let { if (it.length > 0.2f) it * (0.2f / it.length) else it }
                orbit += 0.013f
                val r = 0.7f + 0.4f * sin(orbit * 3.1f)
                val cam = Vec2(r * cos(orbit), r * sin(orbit))
                val toCam = cam - pos
                assertTrue(toCam.length >= 0.1f, "frame $i: the camera is 10 cm away or more")
                if (abs(facing.dot(screenRight(toCam))) <= FLIP_DEADBAND) inBand++

                val p = poser.pose(CharacterState(pos = pos, mode = Mode.WALKING, facing = facing), cam, 0.35f, i * 16L)
                val c = ViewPicker.pick(facing, toCam, previous, views)
                assertEquals(SpriteView.SIDE, c.view, "frame $i")
                assertEquals(c.mirrored, p.mirrored, "frame $i, facesRight=$facesRight")
                if (p.mirrored) mirroredFrames++
                previous = c
            }
            assertTrue(inBand > 20, "the walk spends frames in the dead band ($inBand)")
            assertTrue(mirroredFrames in 100..1_900, "both flags occur ($mirroredFrames)")
        }
    }

    @Test
    fun flipDeadbandIsTheViewPickersMirrorDeadband() {
        assertEquals(ViewPicker.MIRROR_DEADBAND, FLIP_DEADBAND)
        assertEquals(0.15f, FLIP_DEADBAND)
        // And the two act alike on either side of it, from the same unmirrored start.
        for (facesRight in listOf(false, true)) {
            val views = SpriteViews.of(SpriteView.SIDE, facesRight, emptyMap())
            for (along in listOf(0.149f, -0.149f, 0.151f, -0.151f)) {
                val facing = Vec2(along, sqrt(1f - along * along))
                val p = poser(facesRight).pose(CharacterState(facing = facing), camera, cameraH, 0L)
                val what = "facesRight=$facesRight, facing . right = $along"
                assertEquals(ViewPicker.pick(facing, camera, null, views).mirrored, p.mirrored, what)
                assertEquals(abs(along) > 0.15f && (along > 0f) != facesRight, p.mirrored, what)
            }
        }
    }

    @Test
    fun cameraStraightAboveFlipsByFacingU() {
        // The camera right over the feet: no bearing in the plane. The poser's yaw is atan2(0, 0) = 0, so its screen-right
        // is +u and the flip follows facing.u; ViewPicker holds its previous choice there instead (T50 decides).
        val feet = Vec2(0.1f, -0.05f)
        for (facesRight in listOf(false, true)) {
            val poser = poser(facesRight, pixelBias)
            val views = SpriteViews.of(SpriteView.SIDE, facesRight, emptyMap())
            val held = ViewChoice(SpriteView.SIDE, false, SpriteView.SIDE)
            for ((facing, mirrored) in listOf(
                Vec2(1f, 0f) to !facesRight,
                Vec2(0f, 1f) to !facesRight, // |facing.u| in the band: held
                Vec2(-0.6f, 0.8f) to facesRight,
                Vec2(0.1f, -0.995f) to facesRight, // held
                Vec2(0.6f, -0.8f) to !facesRight,
            )) {
                val p = poser.pose(CharacterState(pos = feet, facing = facing), feet, 0.4f, 0L)
                val what = "facesRight=$facesRight, facing $facing"
                assertTrue(finite(p), "$what: no NaN ($p)")
                assertEquals(mirrored, p.mirrored, "$what: the flip follows facing.u")
                near(front, p.rotation.rotate(front), "$what: yaw 0")
                near(feet, p.pos, "$what: the pull is straight up")
                assertSame(held, ViewPicker.pick(facing, feet - feet, held, views), "$what: ViewPicker holds")
            }
        }
    }

    // ---- position ----

    @Test
    fun feetStandWherePhysicsPutsThemWithoutBias() {
        val poser = poser(bias = 0f)
        val states = listOf(
            CharacterState(pos = Vec2(0.12f, -0.07f)),
            CharacterState(pos = Vec2(-0.2f, 0.3f), h = 0.05f, mode = Mode.JUMPING),
        )
        for (state in states) {
            for ((cam, camH) in listOf(Vec2(0.5f, 0.4f) to 0.3f, state.pos to 0.5f, Vec2(3f, -2f) to 1.2f, state.pos + Vec2(0.01f, 0f) to state.h)) {
                val p = poser.pose(state, cam, camH, 0L)
                val what = "feet ${state.pos} h ${state.h}, camera $cam at $camH"
                near(state.pos, p.pos, "$what: pos")
                near(state.h, p.h, "$what: h")
                scale(1f, 1f, p, what)
            }
        }
    }

    @Test
    fun feetDepthBiasKeepsTheLineOfSightAndShrinksTheQuadByTheSameFactor() {
        val poser = poser(bias = pixelBias)
        val state = CharacterState(pos = Vec2(0.1f, 0.05f), h = 0.02f)
        val feet = Vec3(state.pos.u, state.h, state.pos.v)
        for ((cam, camH) in listOf(Vec2(0.1f, 0.45f) to 0.32f, Vec2(-0.5f, 0.6f) to 0.5f, Vec2(1.2f, -0.9f) to 0.9f, Vec2(0.13f, 0.09f) to 0.1f)) {
            val p = poser.pose(state, cam, camH, 0L)
            val eye = Vec3(cam.u, camH, cam.v)
            val posed = Vec3(p.pos.u, p.h, p.pos.v)
            val dist = (eye - feet).length
            val pull = 1f - (eye - posed).length / dist
            val what = "camera $cam at $camH"
            near(heightM * pixelBias / dist, pull, "$what: the pull is 0.6 heights over the distance")
            near(0f, (posed - feet).cross(eye - feet).length, "$what: camera, posed feet and true feet are collinear")
            assertTrue((posed - feet).dot(eye - feet) > 0f, "$what: toward the camera")
            scale(1f - pull, 1f - pull, p, "$what: the quad shrinks by the same factor")
        }
        // 50 cm away: 3.6 cm / 50 cm = 0.072, so 7.2 % nearer and 7.2 % smaller.
        scale(0.928f, 0.928f, poser.pose(state, Vec2(0.1f, 0.45f), 0.32f, 0L), "50 cm")
    }

    @Test
    fun feetDepthBiasIsCappedAtHalfWayAndOffAtTheLens() {
        val poser = poser(bias = pixelBias)
        val state = CharacterState(pos = Vec2(0.1f, 0.05f), h = 0.02f)
        // 5 cm away 3.6 / 5 = 0.72, capped: the quad stands half way and is half its size.
        val close = poser.pose(state, state.pos + Vec2(0.03f, 0.04f), state.h, 0L)
        near(state.pos + Vec2(0.015f, 0.02f), close.pos, "5 cm: half way")
        near(state.h, close.h, "5 cm, level: h")
        scale(0.5f, 0.5f, close, "5 cm")
        // 2 mm above the feet: still half way.
        val above = poser.pose(state, state.pos, state.h + 0.002f, 0L)
        near(state.pos, above.pos, "2 mm: pos")
        near(state.h + 0.001f, above.h, "2 mm: h")
        scale(0.5f, 0.5f, above, "2 mm")
        // Within 1 mm (the lens at the feet): off, and no division by zero.
        for (gap in listOf(0f, 0.0009f)) {
            val p = poser.pose(state, state.pos, state.h + gap, 0L)
            assertTrue(finite(p), "gap $gap: no NaN ($p)")
            near(state.pos, p.pos, "gap $gap: pos")
            near(state.h, p.h, "gap $gap: h")
            scale(1f, 1f, p, "gap $gap")
        }
    }

    @Test
    fun hopRaisesByHopTimesHeightTimesSize() {
        val state = CharacterState(pos = Vec2(0.1f, 0.05f), h = 0.02f)
        val hop = EmotePose(hop = 0.5f)
        for (size in listOf(0.5f, 1f, 2f)) {
            val p = poser().pose(state, Vec2(0.1f, 0.6f), cameraH, 0L, size = size, emotePose = hop)
            near(state.h + 0.5f * heightM * size, p.h, "size $size: h")
            near(state.pos, p.pos, "size $size: straight up")
        }
        // With the bias the hop rides on top of the pull, which aims at the feet physics knows (it does not see the hop),
        // and the hop is not shrunk by (1 - pull): 50 cm away h = 0.02 + 0.03 + 0.3 x 0.072.
        val biased = poser(bias = pixelBias).pose(state, Vec2(0.1f, 0.45f), 0.32f, 0L, emotePose = hop)
        near(0.02f + 0.03f + 0.3f * 0.072f, biased.h, "biased: h")
        scale(0.928f, 0.928f, biased, "biased")
    }

    // ---- scale ----

    @Test
    fun idleFallingAndPausedAreUnitScale() {
        val poser = poser()
        for ((mode, vv) in listOf(
            Mode.IDLE to 0f, Mode.FALLING to -0.4f, Mode.PAUSED to 0f, Mode.PAUSED to 0.5f,
            Mode.RESPAWNING to 0f, Mode.JUMPING to 0f, Mode.JUMPING to -0.4f,
        )) {
            scale(1f, 1f, poser.pose(CharacterState(mode = mode), camera, cameraH, walkPhaseMs = 100L, verticalVelocity = vv), "$mode at $vv m/s")
        }
        scale(1f, 1f, poser.pose(CharacterState(mode = Mode.PAUSED), camera, cameraH, 100L, sinceLandedMs = 30L), "PAUSED just after a landing")
    }

    @Test
    fun walkBobFollowsTheWalkPhase() {
        assertEquals(400L, WALK_BOB_PERIOD_MS)
        assertEquals(0.08f, WALK_BOB_STRETCH)
        assertEquals(0.05f, WALK_BOB_SQUASH)
        val poser = poser()
        val s45 = sin(PI / 4).toFloat()
        for ((ms, sx, sy) in listOf(
            Triple(0L, 1f, 1f), Triple(100L, 0.95f, 1.08f), Triple(200L, 1f, 1f), Triple(300L, 1.05f, 0.92f),
            Triple(400L, 1f, 1f), Triple(500L, 0.95f, 1.08f), Triple(50L, 1f - 0.05f * s45, 1f + 0.08f * s45),
        )) {
            scale(sx, sy, poser.pose(walking, camera, cameraH, ms), "$ms ms")
        }
    }

    @Test
    fun risingJumpStretches() {
        assertEquals(0.85f, JUMP_RISE_SX)
        assertEquals(1.15f, JUMP_RISE_SY)
        val poser = poser()
        val jumping = CharacterState(mode = Mode.JUMPING, h = 0.01f)
        for (vv in listOf(0.6f, 0.001f)) scale(0.85f, 1.15f, poser.pose(jumping, camera, cameraH, 100L, verticalVelocity = vv), "rising at $vv m/s")
        for (vv in listOf(0f, -0.6f)) scale(1f, 1f, poser.pose(jumping, camera, cameraH, 100L, verticalVelocity = vv), "at the top or falling, $vv m/s")
    }

    @Test
    fun landingSquashEasesOutOver120Ms() {
        assertEquals(120L, JUMP_LAND_MS)
        assertEquals(1.25f, JUMP_LAND_SX)
        assertEquals(0.75f, JUMP_LAND_SY)
        val poser = poser()
        val idle = CharacterState()
        scale(1.25f, 0.75f, poser.pose(idle, camera, cameraH, 0L, sinceLandedMs = 0L), "touchdown")
        scale(1.10546875f, 0.89453125f, poser.pose(idle, camera, cameraH, 0L, sinceLandedMs = 30L), "30 ms: (3/4)^3 of the squash")
        scale(1.03125f, 0.96875f, poser.pose(idle, camera, cameraH, 0L, sinceLandedMs = 60L), "60 ms: (1/2)^3 of it")
        scale(1f, 1f, poser.pose(idle, camera, cameraH, 0L, sinceLandedMs = 120L), "120 ms: over")
        scale(1f, 1f, poser.pose(idle, camera, cameraH, 0L, sinceLandedMs = -1L), "a negative clock")
        scale(1f, 1f, poser.pose(idle, camera, cameraH, 0L), "never landed (the default clock)")
        var last = Float.MAX_VALUE
        for (ms in 0L until 120L) {
            val sx = poser.pose(idle, camera, cameraH, 0L, sinceLandedMs = ms).scaleX
            assertTrue(sx in 1f..last, "$ms ms: the squash only relaxes ($sx after $last)")
            last = sx
        }
    }

    @Test
    fun landingSquashWinsOverTheBobAndNeedsTheGround() {
        val poser = poser()
        scale(1.25f, 0.75f, poser.pose(walking, camera, cameraH, 100L, sinceLandedMs = 0L), "a landing mid-walk: the squash, not the bob")
        scale(1.03125f, 0.96875f, poser.pose(walking, camera, cameraH, 100L, sinceLandedMs = 60L), "60 ms into it")
        scale(0.95f, 1.08f, poser.pose(walking, camera, cameraH, 100L, sinceLandedMs = 120L), "then the bob again")
        val rising = poser.pose(CharacterState(mode = Mode.JUMPING), camera, cameraH, 100L, verticalVelocity = 0.6f, sinceLandedMs = 0L)
        scale(0.85f, 1.15f, rising, "off the ground again at once: the stretch")
        for (mode in listOf(Mode.JUMPING, Mode.FALLING, Mode.PAUSED, Mode.RESPAWNING)) {
            scale(1f, 1f, poser.pose(CharacterState(mode = mode), camera, cameraH, 100L, sinceLandedMs = 0L), "$mode: no ground, no squash")
        }
    }

    @Test
    fun emoteScalesMultiplyAndSwitchTheBobOff() {
        val poser = poser()
        val e = EmotePose(scaleX = 1.2f, scaleY = 0.9f)
        scale(1.2f, 0.9f, poser.pose(CharacterState(), camera, cameraH, 100L, emotePose = e), "idle")
        scale(1.2f, 0.9f, poser.pose(walking, camera, cameraH, 100L, emotePose = e), "walking: the bob is off")
        scale(1f, 1f, poser.pose(walking, camera, cameraH, 100L, emotePose = EmotePose.REST), "any running emote stops the bob, even at rest")
        scale(1.5f, 0.675f, poser.pose(walking, camera, cameraH, 100L, sinceLandedMs = 0L, emotePose = e), "on the landing squash")
        val rising = poser.pose(CharacterState(mode = Mode.JUMPING), camera, cameraH, 100L, verticalVelocity = 0.6f, emotePose = e)
        scale(1.02f, 1.035f, rising, "on the rising stretch")
    }

    @Test
    fun sizeScalesBothAxes() {
        scale(2f, 2f, poser().pose(CharacterState(), camera, cameraH, 0L, size = 2f), "idle, size 2")
        scale(0.475f, 0.54f, poser().pose(walking, camera, cameraH, 100L, size = 0.5f), "the bob, size 0.5")
        // The pull is a share of the size-1 height, whatever the size: 50 cm away (1 - 0.072) x 2.
        val biased = poser(bias = pixelBias).pose(CharacterState(pos = Vec2(0.1f, 0.05f), h = 0.02f), Vec2(0.1f, 0.45f), 0.32f, 0L, size = 2f)
        scale(1.856f, 1.856f, biased, "biased, size 2")
        near(Vec2.ZERO, poser().pose(CharacterState(), camera, cameraH, 0L, size = 2f).pos, "the feet do not move with the size")
    }

    @Test
    fun scaleMagnitudesNeverCarryTheFlip() {
        for (facesRight in listOf(false, true)) {
            val toRight = poser(facesRight, pixelBias).pose(walking.copy(facing = Vec2(1f, 0f)), camera, cameraH, 100L)
            val toLeft = poser(facesRight, pixelBias).pose(walking.copy(facing = Vec2(-1f, 0f)), camera, cameraH, 100L)
            assertTrue(toRight.mirrored != toLeft.mirrored, "facesRight=$facesRight: one of the two is mirrored")
            scale(toRight.scaleX, toRight.scaleY, toLeft, "facesRight=$facesRight: the same magnitudes either way")
            val poser = poser(facesRight, pixelBias)
            val seen = HashSet<Boolean>()
            for (k in 0 until 16) {
                val facing = Vec2(cos(k * PI / 8).toFloat(), sin(k * PI / 8).toFloat())
                val p = poser.pose(walking.copy(facing = facing), camera, cameraH, 100L + k * 25L)
                assertTrue(p.scaleX > 0f && p.scaleY > 0f, "facesRight=$facesRight, facing $facing: ($p)")
                seen += p.mirrored
            }
            assertEquals(setOf(false, true), seen)
        }
    }

    // ---- identity and the loop ----

    @Test
    fun poseCarriesThePosersId() {
        val dino = BillboardPoser("dino", heightM, false, pixelBias)
        val cat = BillboardPoser("cat-2", heightM, true, 0f)
        assertEquals("dino", dino.id)
        assertEquals("cat-2", cat.id)
        assertEquals("dino", dino.pose(CharacterState(), camera, cameraH, 0L).id)
        assertEquals("cat-2", cat.pose(CharacterState(), camera, cameraH, 0L).id)
    }

    @Test
    fun jumpOnTheResumeFrameStillStretchesThoughItsEventIsLost() {
        val loop = GameLoop(Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.70f, 0.70f)))
        loop.add("hero", Vec2(0.1f, 0.1f), Ability.JUMP)
        val poser = poser()
        val events = ArrayList<Pair<String, CharacterEvent>>()
        var now = 1_000_000L
        fun frame(tracking: Boolean = true, beforeStep: (() -> Unit)? = null) {
            now += 16L
            loop.onFrame(now, tracking, { id, e -> events += id to e }, beforeStep)
        }
        // The poser fed from the loop's state, as the host's frame callback does.
        fun pose(): NodePose {
            val body = loop.body("hero")!!
            return poser.pose(
                body.state, Vec2(0.1f, 0.6f), cameraH, loop.walkPhaseMs("hero"),
                verticalVelocity = body.verticalVelocity,
                sinceLandedMs = now - loop.landedAtMs("hero"),
                emotePose = loop.emotePose("hero"),
            )
        }
        repeat(3) { frame() }
        repeat(10) { frame(tracking = false) }
        events.clear()
        var accepted = false
        frame(beforeStep = { accepted = loop.jump("hero") })
        assertTrue(accepted, "the jump is accepted on the first frame back")
        assertEquals(listOf("hero" to CharacterEvent.RESUMED), events, "the frame reports RESUMED only: JUMPED is lost (T47)")
        var rising = 0
        while (loop.body("hero")!!.verticalVelocity > 0f && rising < 200) {
            assertEquals(Mode.JUMPING, loop.body("hero")!!.state.mode, "rising frame $rising")
            scale(0.85f, 1.15f, pose(), "rising frame $rising: the stretch reads state, not the lost event")
            rising++
            frame()
        }
        assertTrue(rising >= 5, "it rises for a while ($rising frames)")
        // Its landing is reported as usual (T47), so the squash shows on the landing frame.
        events.clear()
        var frames = 0
        while (events.isEmpty() && frames++ < 200) frame()
        assertEquals(listOf("hero" to CharacterEvent.LANDED), events)
        scale(1.25f, 0.75f, pose(), "the landing frame")
    }
}
