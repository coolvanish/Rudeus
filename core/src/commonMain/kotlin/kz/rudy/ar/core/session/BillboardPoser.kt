package kz.rudy.ar.core.session

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmotePose
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// T52 — moved here from :androidApp (ar/BillboardPoser.kt, the pure half of the host's CharacterPoser, ar/CharacterNode.kt)
// as it was, and pinned by BillboardPoserTest, the same tests that ran against the host copy. The host keeps the three
// node writes and FEET_DEPTH_BIAS, which it passes in. The notes below are the host review notes the code carried, kept
// as they were. The one piece re-expressed and not moved is [billboardRotation]: see there.

internal const val WALK_BOB_PERIOD_MS = 400L
internal const val WALK_BOB_STRETCH = 0.08f
internal const val WALK_BOB_SQUASH = 0.05f

// T21 — jump squash-stretch, the minimal cut of the DESIGN.md motion table (no anticipation: the jump
// starts on the frame of the tap). Rising (JUMPING, vertical velocity > 0): (0.85, 1.15). Falling (JUMPING with
// vv <= 0, or FALLING): (1, 1). For JUMP_LAND_MS after the LANDED event: (1.25, 0.75) easing back to 1 (ease-out
// cubic, no overshoot). The quad's origin is at the feet, so every scale keeps the feet on the plane. The landing
// squash wins over the walk bob while it lasts (a landing mid-walk).
internal const val JUMP_RISE_SX = 0.85f
internal const val JUMP_RISE_SY = 1.15f
internal const val JUMP_LAND_SX = 1.25f
internal const val JUMP_LAND_SY = 0.75f
internal const val JUMP_LAND_MS = 120L

/** |facing · right| below this keeps the previous flip, so walking straight at/away from the camera cannot flicker. */
internal const val FLIP_DEADBAND = 0.15f

// T32 — the emote pose (core EmotePose, sampled by GameLoop on the simulated clock) is node transform only,
// on top of the physics pose: scaleX/scaleY multiply the node scale, yawDeg adds to the billboard yaw, tiltDeg rotates
// about the quad's OWN Z (its normal, so a bow leans in the quad's plane and still faces the camera), hop raises the quad
// by hop * sprite height * size. The walk bob is off while an emote runs; the landing squash still wins over the bob.
// The rotation is `quaternion = Q_y(yaw) * Q_z(tilt)` (kotlin-math 1.8.0 Quaternion.fromAxisAngle, degrees; Node.quaternion
// Node.kt:370) and NOT `Rotation(y, z)`: Node.rotation's Euler setter (Node.kt:414) is kotlin-math ZYX, which composes
// Q_z * Q_y and would tilt about the anchor's Z, not the quad's. With tilt 0 the product is exactly Q_y(yaw), the v1.0 pose.
internal val Y_AXIS = Vec3(0f, 1f, 0f)
internal val Z_AXIS = Vec3(0f, 0f, 1f)

/** Degrees to radians: the factor kotlin-math 1.8.0's `Quaternion.fromAxisAngle` multiplies by (read from its bytecode). */
private const val DEG_TO_RAD = 0.017453292f

/**
 * Q_y(yawDeg) * Q_z(tiltDeg), degrees, right-handed; with tilt 0 exactly a yaw.
 *
 * The host built this with kotlin-math as `hamilton(Quaternion.fromAxisAngle(Y_AXIS, yawDeg), Quaternion.fromAxisAngle(
 * Z_AXIS, tiltDeg))`. Here [Quat.times] is that same Hamilton product, term for term, and [Quat.fromAxisAngle] takes
 * radians, so the degrees are scaled by kotlin-math's own factor [DEG_TO_RAD] and halved the same way.
 */
internal fun billboardRotation(yawDeg: Float, tiltDeg: Float): Quat =
    Quat.fromAxisAngle(Y_AXIS, yawDeg * DEG_TO_RAD) * Quat.fromAxisAngle(Z_AXIS, tiltDeg * DEG_TO_RAD)

/** One billboard for one frame, in the anchor frame (x = u, y = h, z = v). The host writes it into its node, nothing else. */
@Serializable
data class NodePose(
    val id: String,        // the member this pose belongs to
    val pos: Vec2,         // the quad's origin (the feet), plane coordinates, metres; the feet depth bias is in it
    val h: Float,          // its height above the plane, metres; the emote's hop and the bias are in it
    val rotation: Quat,    // yaw about +h so the front faces the camera, then tilt about the quad's own front axis
    val scaleX: Float,     // magnitudes on the quad the host built (height = the profile's, width = height x aspect);
    val scaleY: Float,     //   never negative: the flip is [mirrored]
    val mirrored: Boolean, // show the picture flipped horizontally (Android: a negative X scale, as today)
)

/**
 * The billboard pose of one character, every frame: where the quad stands, how it is turned, squashed and mirrored.
 * Keeps only the flip sign between frames, starting unflipped; every input is state or a clock the caller owns.
 *
 * @param heightM the quad's height at size 1 (`SurfaceProfile.characterHeightM`).
 * @param facesRight the picture is drawn looking to its own right.
 * @param feetDepthBias share of the height the quad is pulled toward the camera (the host's FEET_DEPTH_BIAS); 0f = off.
 */
class BillboardPoser(
    val id: String,
    private val heightM: Float,
    private val facesRight: Boolean,
    private val feetDepthBias: Float,
) {
    private var flip = 1f

    /**
     * @param cameraLocal camera position in plane coords: `planeCoords(anchor, frame.camera.pose)`.
     * @param cameraHeightM its height above the plane: `planeHeight(anchor, frame.camera.pose)`.
     * @param walkPhaseMs time spent walking, from [GameLoop.walkPhaseMs].
     * @param verticalVelocity feet velocity along h, m/s (`PhysicsBody.verticalVelocity`); only read while JUMPING.
     * @param sinceLandedMs host clock minus [GameLoop.landedAtMs]; drives the landing squash while < JUMP_LAND_MS.
     * @param size the player's Bigger / Smaller step (`ArSlot.characterSize`).
     * @param emotePose the running emote's pose from [GameLoop.emotePose]; null when none runs.
     */
    fun pose(
        state: CharacterState,
        cameraLocal: Vec2,
        cameraHeightM: Float,
        walkPhaseMs: Long,
        verticalVelocity: Float = 0f,
        sinceLandedMs: Long = Long.MAX_VALUE,
        size: Float = 1f,
        emotePose: EmotePose? = null,
    ): NodePose {
        val toCam = cameraLocal - state.pos
        val upToCam = cameraHeightM - state.h
        // See feetDepthBias. At most half way, for a camera right over the sprite.
        val camDist = sqrt(toCam.lengthSquared + upToCam * upToCam)
        val pull = if (camDist > 1e-3f) minOf(heightM * feetDepthBias / camDist, 0.5f) else 0f
        val hopM = (emotePose?.hop ?: 0f) * heightM * size
        val pos = state.pos + toCam * pull
        val h = state.h + hopM + upToCam * pull

        // Y-only billboard. After a yaw of θ about Y the node's +Z (the quad's front) is (sin θ, 0, cos θ) in
        // plane coords; point it at the camera. The emote's yaw (a spin) adds; its tilt is about the quad's own Z.
        val yawRad = atan2(toCam.u, toCam.v)
        val yawDeg = (yawRad * 180.0 / PI).toFloat() + (emotePose?.yawDeg ?: 0f)

        // Facing flip. The node's +X (the viewer's right) is (cos θ, -sin θ) in plane coords.
        val right = Vec2(cos(yawRad), -sin(yawRad))
        val along = state.facing.dot(right)
        if (abs(along) > FLIP_DEADBAND) {
            val wantsRight = along > 0f
            flip = if (wantsRight == facesRight) 1f else -1f
        }
        // T32 review fix — a positive tilt about +Z leans the top to the viewer's LEFT (right-handed, +Z at
        // the camera). A bow should lean the way the sprite looks: left for an unflipped left-facing sprite (the demo
        // dino, facesRight = false), so the tilt is signed by the flip and the sprite's drawn facing.
        val lean = if (facesRight) -flip else flip
        val rotation = billboardRotation(yawDeg, (emotePose?.tiltDeg ?: 0f) * lean)

        // Cheap squash-and-stretch bob while walking (Day 3 plan: picked over a sprite sheet).
        // The quad's origin is at the feet (center = height/2), so scaling keeps the feet on the table.
        var sx = 1f
        var sy = 1f
        if (sinceLandedMs in 0 until JUMP_LAND_MS && (state.mode == Mode.IDLE || state.mode == Mode.WALKING)) {
            val t = 1f - sinceLandedMs.toFloat() / JUMP_LAND_MS // 1 at touchdown -> 0
            val k = t * t * t // ease-out: the squash relaxes fast, then settles
            sx = 1f + (JUMP_LAND_SX - 1f) * k
            sy = 1f + (JUMP_LAND_SY - 1f) * k
        } else if (state.mode == Mode.JUMPING && verticalVelocity > 0f) {
            sx = JUMP_RISE_SX
            sy = JUMP_RISE_SY
        } else if (state.mode == Mode.WALKING && emotePose == null) {
            val s = sin(2.0 * PI * (walkPhaseMs % WALK_BOB_PERIOD_MS) / WALK_BOB_PERIOD_MS).toFloat()
            sy = 1f + WALK_BOB_STRETCH * s
            sx = 1f - WALK_BOB_SQUASH * s
        }
        if (emotePose != null) {
            sx *= emotePose.scaleX
            sy *= emotePose.scaleY
        }
        // T25 — Bigger / Smaller is a node scale, not a new sprite: no Texture rebuild, and the origin at
        // the feet keeps them on the table at every size.
        return NodePose(id, pos, h, rotation, scaleX = sx * (1f - pull) * size, scaleY = sy * (1f - pull) * size, mirrored = flip < 0f)
    }
}
