package kz.rudy.ar.ar

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyTexture
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Scale
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.texture.ImageTexture
import kz.rudy.ar.core.bundle.SavedCharacters
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmotePose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// HAND-REVIEW: T02 — the character is ONE Y-only billboard quad: SceneView `PlaneNode` (geometry, not the ARCore
// Plane) + `materialLoader.createImageInstance(texture)`. SceneView 4.31.0's image_texture.filamat is UNLIT,
// blending TRANSPARENT, culling NONE (decoded from the AAR), so a transparent PNG renders as a cut-out and a
// negative X scale (facing flip) is safe. The quad is a child of the session AnchorNode, so its local position
// is plane coords (u, h, v) via PlaneCoords.localPosition. Sprite source: assets/ until T03 passes
// CharacterResult.sprite bytes to [spriteFromBytes]. Nothing here decides game state (that is :core Physics);
// this only maps CharacterState -> node transform, once per AR frame, from the main thread.
// HAND-REVIEW: T14 — the quad height is a parameter (SurfaceProfile.characterHeightM: 6 cm table, 24 cm floor) and a
// sprite can be destroyed when replaced: the Texture was built by us (Engine owns it -> engine.destroyTexture via
// SceneView's runCatching `safeDestroyTexture`), the MaterialInstance by the MaterialLoader, which tracks its
// instances and destroys them with the scene -> `materialLoader.destroyMaterialInstance` removes it from that list
// first, so there is no double free at scene disposal (SceneView 4.31 MaterialLoader.kt:541).

/** Character height on the table, metres (SurfaceProfile.TABLE.characterHeightM). Width follows the sprite's pixel aspect. */
internal const val CHARACTER_HEIGHT_M = 0.06f

/** Bundled demo sprite (copied from docs/bundled/toy_dino) until in-app capture lands. */
internal const val DEMO_SPRITE_ASSET = "sprites/toy_dino.png"

/** The demo dino is drawn facing -X (left). T03 decides this per generated sprite. */
internal const val DEMO_SPRITE_FACES_RIGHT = false

private const val WALK_BOB_PERIOD_MS = 400L
private const val WALK_BOB_STRETCH = 0.08f
private const val WALK_BOB_SQUASH = 0.05f

// HAND-REVIEW: T21 — jump squash-stretch, the minimal cut of the DESIGN.md motion table (no anticipation: the jump
// starts on the frame of the tap). Rising (JUMPING, vertical velocity > 0): (0.85, 1.15). Falling (JUMPING with
// vv <= 0, or FALLING): (1, 1). For JUMP_LAND_MS after the LANDED event: (1.25, 0.75) easing back to 1 (ease-out
// cubic, no overshoot). The quad's origin is at the feet, so every scale keeps the feet on the plane. The landing
// squash wins over the walk bob while it lasts (a landing mid-walk).
private const val JUMP_RISE_SX = 0.85f
private const val JUMP_RISE_SY = 1.15f
private const val JUMP_LAND_SX = 1.25f
private const val JUMP_LAND_SY = 0.75f
private const val JUMP_LAND_MS = 120L

// HAND-REVIEW: Pixel 2026-11-03 — the table cut off the feet (screenshots 12:07 and 03:57; no bundled sprite has a
// transparent margin below it). Depth occlusion compares the bottom of the quad with depth-from-motion of the table right
// behind it, and a plane a centimetre low puts it under the table. So the quad is scaled about the camera, by this share of
// the character's height (3.6 cm on the table): every pixel stays exactly where it was and only its depth shrinks. A cup
// still hides a character behind it: the cup's near side is about 11 cm in front of it. PAUSED and the physics do not see
// this, it is the node transform only. 0f turns it off.
private const val FEET_DEPTH_BIAS = 0.6f

/** |facing · right| below this keeps the previous flip, so walking straight at/away from the camera cannot flicker. */
private const val FLIP_DEADBAND = 0.15f

// HAND-REVIEW: T32 — the emote pose (core EmotePose, sampled by GameLoop on the simulated clock) is node transform only,
// on top of the physics pose: scaleX/scaleY multiply the node scale, yawDeg adds to the billboard yaw, tiltDeg rotates
// about the quad's OWN Z (its normal, so a bow leans in the quad's plane and still faces the camera), hop raises the quad
// by hop * sprite height * size. The walk bob is off while an emote runs; the landing squash still wins over the bob.
// The rotation is `quaternion = Q_y(yaw) * Q_z(tilt)` (kotlin-math 1.8.0 Quaternion.fromAxisAngle, degrees; Node.quaternion
// Node.kt:370) and NOT `Rotation(y, z)`: Node.rotation's Euler setter (Node.kt:414) is kotlin-math ZYX, which composes
// Q_z * Q_y and would tilt about the anchor's Z, not the quad's. With tilt 0 the product is exactly Q_y(yaw), the v1.0 pose.
private val Y_AXIS = Float3(0f, 1f, 0f)
private val Z_AXIS = Float3(0f, 0f, 1f)

/** `a * b` of kotlin-math (Quaternion.kt:432), spelled out: its `times` is inline and built for JVM 21, which our JVM 11 bytecode cannot inline. */
private fun hamilton(a: Quaternion, b: Quaternion) = Quaternion(
    a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
)

internal class CharacterSprite(
    val texture: Texture,
    val material: MaterialInstance,
    val widthM: Float,
    val heightM: Float,
    val facesRight: Boolean,
) {
    /** Releases the GPU resources. Main thread; only after no node uses [material] (RudeusArView keys the node on the sprite). */
    fun destroy(engine: Engine, materialLoader: MaterialLoader) {
        materialLoader.destroyMaterialInstance(material)
        engine.safeDestroyTexture(texture)
    }
}

/** Demo path: decode a PNG from app assets (alpha pre-multiplied, as Filament expects for colour textures). */
internal fun spriteFromAssets(
    engine: Engine,
    materialLoader: MaterialLoader,
    assets: AssetManager,
    heightM: Float = CHARACTER_HEIGHT_M,
    path: String = DEMO_SPRITE_ASSET,
    facesRight: Boolean = DEMO_SPRITE_FACES_RIGHT,
): CharacterSprite = spriteFromBitmap(engine, materialLoader, ImageTexture.getBitmap(assets, path), heightM, facesRight)

/**
 * T03 entry point: `CharacterResult.sprite` PNG bytes -> billboard material. Main thread (Filament).
 * HAND-REVIEW: review fix — undecodable bytes (a corrupt sprite) fall back to the bundled dino from [assets] with a
 * log line instead of an NPE inside composition.
 */
internal fun spriteFromBytes(
    engine: Engine,
    materialLoader: MaterialLoader,
    assets: AssetManager,
    png: ByteArray,
    heightM: Float,
    facesRight: Boolean,
): CharacterSprite {
    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
    if (bitmap == null) {
        Log.w(GATE_TAG, "sprite_undecodable bytes=${png.size}: showing the demo sprite")
        return spriteFromAssets(engine, materialLoader, assets, heightM)
    }
    return spriteFromBitmap(engine, materialLoader, boundedForTexture(bitmap), heightM, facesRight)
}

/**
 * HAND-REVIEW: memory audit 2026-11-03 — a fresh Muse sprite reached Filament at up to 1408x1536 (about 11.5 MB with
 * mips) while saved and bundled ones are 768 px and look fine on the Pixel. Same bound as SavedCharacters. A filtered
 * scale, not `inSampleSize`: a sampled PNG decode can skip pixels and roughen the sprite's bold outline. Only the
 * local full-size decode is recycled; the bitmap handed to Filament is left to the GC, its upload may be asynchronous.
 */
private fun boundedForTexture(src: Bitmap): Bitmap {
    val longSide = maxOf(src.width, src.height)
    if (longSide <= SavedCharacters.SPRITE_MAX_SIDE) return src
    val scale = SavedCharacters.SPRITE_MAX_SIDE.toFloat() / longSide
    val w = maxOf(1, (src.width * scale).roundToInt())
    val h = maxOf(1, (src.height * scale).roundToInt())
    return Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
}

private fun spriteFromBitmap(
    engine: Engine,
    materialLoader: MaterialLoader,
    bitmap: Bitmap,
    heightM: Float,
    facesRight: Boolean,
): CharacterSprite {
    val texture = ImageTexture.Builder().bitmap(bitmap).build(engine)
    val material = materialLoader.createImageInstance(texture)
    return CharacterSprite(
        texture = texture,
        material = material,
        widthM = heightM * bitmap.width / bitmap.height,
        heightM = heightM,
        facesRight = facesRight,
    )
}

/**
 * Writes a [CharacterState] into the billboard node every frame. Keeps only the flip sign between frames.
 * The node must be a child of the session AnchorNode (local frame = plane frame, see PlaneCoords.kt).
 */
internal class CharacterPoser(private val sprite: CharacterSprite) {
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
    fun apply(
        node: PlaneNode,
        state: CharacterState,
        cameraLocal: Vec2,
        cameraHeightM: Float,
        walkPhaseMs: Long,
        verticalVelocity: Float = 0f,
        sinceLandedMs: Long = Long.MAX_VALUE,
        size: Float = 1f,
        emotePose: EmotePose? = null,
    ) {
        val toCam = cameraLocal - state.pos
        val upToCam = cameraHeightM - state.h
        // See FEET_DEPTH_BIAS. At most half way, for a camera right over the sprite.
        val camDist = sqrt(toCam.lengthSquared + upToCam * upToCam)
        val pull = if (camDist > 1e-3f) minOf(sprite.heightM * FEET_DEPTH_BIAS / camDist, 0.5f) else 0f
        val hopM = (emotePose?.hop ?: 0f) * sprite.heightM * size
        node.position = localPosition(state.pos + toCam * pull, state.h + hopM + upToCam * pull)

        // Y-only billboard. After a yaw of θ about Y the node's +Z (the quad's front) is (sin θ, 0, cos θ) in
        // plane coords; point it at the camera. The emote's yaw (a spin) adds; its tilt is about the quad's own Z.
        val yawRad = atan2(toCam.u, toCam.v)
        val yawDeg = (yawRad * 180.0 / PI).toFloat() + (emotePose?.yawDeg ?: 0f)
        node.quaternion = hamilton(Quaternion.fromAxisAngle(Y_AXIS, yawDeg), Quaternion.fromAxisAngle(Z_AXIS, emotePose?.tiltDeg ?: 0f))

        // Facing flip. The node's +X (the viewer's right) is (cos θ, -sin θ) in plane coords.
        val right = Vec2(cos(yawRad), -sin(yawRad))
        val along = state.facing.dot(right)
        if (abs(along) > FLIP_DEADBAND) {
            val wantsRight = along > 0f
            flip = if (wantsRight == sprite.facesRight) 1f else -1f
        }

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
        // HAND-REVIEW: T25 — Bigger / Smaller is a node scale, not a new sprite: no Texture rebuild, and the origin at
        // the feet keeps them on the table at every size.
        node.scale = Scale(x = flip * sx * (1f - pull) * size, y = sy * (1f - pull) * size, z = 1f)
    }
}
