package kz.rudy.ar.core.domain

import kotlinx.serialization.Serializable

/**
 * Which side of a character a sprite shows (ai/build-plan-v2.md, "2.5D views"). The billboard always faces the
 * camera; the view says what is painted on it.
 */
@Serializable
enum class SpriteView {
    /** The character looks at the camera. */
    FRONT,

    /** Profile. One picture serves both directions: the host mirrors it. */
    SIDE,

    /** The character looks away from the camera. */
    BACK,
}
