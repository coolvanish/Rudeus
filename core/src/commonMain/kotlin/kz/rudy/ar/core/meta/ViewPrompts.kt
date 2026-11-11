package kz.rudy.ar.core.meta

import kz.rudy.ar.core.domain.SpriteView

/**
 * T41: the Muse Image edit prompts that redraw a character's sprite from another side ([MetaModels.drawView]). Built
 * like the T33 pose prompts (`Prompts.pose`, judged GO on the T30 spike): the same opening that keeps the character,
 * the view, and the same suffix, which keeps Muse's plain white background for the matte. Kept apart from `Prompts`
 * (MetaDtos.kt) so the sheet work there and this file never touch the same lines.
 */
object ViewPrompts {
    /** The same words as `Prompts.pose`'s base: the character, identical colours, outline and proportions. */
    const val BASE = "the very same character, identical colours, outline and proportions"

    /** The same words as `Prompts.pose`'s suffix: SpriteMatte floods the plain white background from the border. */
    const val SUFFIX = ". Keep the drawing style of the first image. Plain white background, nothing else in the picture."

    /** What each view asks for. SIDE looks right: [kz.rudy.ar.core.emote.ViewPicker] relies on drawn SIDE views facing right. */
    val VIEWS: Map<SpriteView, String> = mapOf(
        SpriteView.FRONT to "seen from the front, facing us",
        SpriteView.SIDE to "seen from the side, in profile, looking to the right",
        SpriteView.BACK to "seen from behind, showing its back",
    )

    /** The Muse edit prompt for [view]. */
    fun view(view: SpriteView): String = "$BASE, ${VIEWS.getValue(view)}$SUFFIX"
}
