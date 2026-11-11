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
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Scale
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.texture.ImageTexture
import kz.rudy.ar.core.bundle.SavedCharacters
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.emote.EmotePose
import kz.rudy.ar.core.geometry.Quat
import kotlin.math.roundToInt

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

// HAND-REVIEW: Pixel 2026-11-03 — the table cut off the feet (screenshots 12:07 and 03:57; no bundled sprite has a
// transparent margin below it). Depth occlusion compares the bottom of the quad with depth-from-motion of the table right
// behind it, and a plane a centimetre low puts it under the table. So the quad is scaled about the camera, by this share of
// the character's height (3.6 cm on the table): every pixel stays exactly where it was and only its depth shrinks. A cup
// still hides a character behind it: the cup's near side is about 11 cm in front of it. PAUSED and the physics do not see
// this, it is the node transform only. 0f turns it off.
private const val FEET_DEPTH_BIAS = 0.6f

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

// HAND-REVIEW: T52 — the maths is in BillboardPoser (it returns a NodePose); these three writes are the whole host side.
// The squash / stretch numbers and FLIP_DEADBAND went with it; FEET_DEPTH_BIAS stays here (a Pixel number, passed in).
/**
 * Writes a [CharacterState] into the billboard node every frame. Keeps only the flip sign between frames.
 * The node must be a child of the session AnchorNode (local frame = plane frame, see PlaneCoords.kt).
 */
internal class CharacterPoser(id: String, sprite: CharacterSprite) {
    private val poser = BillboardPoser(id, sprite.heightM, sprite.facesRight, FEET_DEPTH_BIAS)

    /** [BillboardPoser.pose] written into [node]; the parameters are documented there. */
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
        val p = poser.pose(state, cameraLocal, cameraHeightM, walkPhaseMs, verticalVelocity, sinceLandedMs, size, emotePose)
        node.position = localPosition(p.pos, p.h)
        node.quaternion = p.rotation.toNode()
        node.scale = Scale(x = if (p.mirrored) -p.scaleX else p.scaleX, y = p.scaleY, z = 1f)
    }
}

/** The one place a :core rotation becomes a node's. */
internal fun Quat.toNode(): Quaternion = Quaternion(x, y, z, w)
