package kz.rudy.ar.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.core.domain.ObjectKind

/**
 * Design tokens. The source of truth is DESIGN.md at the project root; keep both in sync.
 * "Crayon on paper": light only, because the UI always sits over a live camera feed.
 */
object RudeusColors {
    val Paper = Color(0xFFFFF6E5)
    val PaperDeep = Color(0xFFF3E4C8)
    val Ink = Color(0xFF1F1A17)
    val InkSoft = Color(0xFF5C534C)
    val Sun = Color(0xFFFFB300)
    val SunDeep = Color(0xFFE09A00)
    val Sky = Color(0xFF1E6FE0)
    val Leaf = Color(0xFF2E7D32)
    val Tomato = Color(0xFFD93A2F)
    val Grape = Color(0xFF7B3FBF)
    val Hazard = Color(0xFFFF6D00)

    /** Behind any text drawn over the camera feed. */
    val Scrim = Ink.copy(alpha = 0.55f)

    /** Paused banner, error sheet. */
    val ScrimStrong = Ink.copy(alpha = 0.75f)

    val FootObstacle = Tomato
    val FootPlatform = Leaf
    val FootDynamic = Grape
    val FootCliff = Hazard

    /** Footprint debug tint per [ObjectKind]; fill at [FootFillAlpha], outline at [FootOutlineAlpha]. */
    fun footprint(kind: ObjectKind): Color = when (kind) {
        ObjectKind.OBSTACLE -> FootObstacle
        ObjectKind.PLATFORM -> FootPlatform
        ObjectKind.DYNAMIC -> FootDynamic
        ObjectKind.CLIFF -> FootCliff
    }

    const val FootFillAlpha = 0.35f
    const val FootOutlineAlpha = 0.9f
}

object RudeusSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /** Minimum hit target for a child's finger. */
    val touchTarget = 56.dp

    /** Height of the one primary button per screen. */
    val primaryButton = 64.dp

    /** The "crayon" outline width. */
    val stroke = 3.dp
}

object RudeusRadius {
    val card = 16.dp
    val bubble = 24.dp
    val full = 999.dp
}

object RudeusMotion {
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasised: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> pop() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    const val BUTTON_PRESS_MS = 90
    const val BANNER_ENTER_MS = 200
    const val BANNER_EXIT_MS = 150
    const val BUBBLE_FADE_MS = 120
    const val BUBBLE_VISIBLE_MS = 2500L
    const val SCAN_DEFAULT_MS = 4000
    const val PUPIL_SWEEP_MS = 1400
    const val BRACKET_BREATHE_MS = 900
    const val STAGE_PROGRESS_MS = 300
    const val SCORE_COUNT_MS = 600
    const val REDUCED_MS = 150
}

private val sans = FontFamily.SansSerif

val RudeusDisplay = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp)
val RudeusTitle = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
val RudeusBody = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
val RudeusLabel = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp)
val RudeusMono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)

val RudeusTypography = Typography(
    displayLarge = RudeusDisplay,
    displayMedium = RudeusDisplay,
    headlineLarge = RudeusTitle,
    headlineMedium = RudeusTitle,
    titleLarge = RudeusTitle,
    titleMedium = RudeusBody.copy(fontWeight = FontWeight.Bold),
    bodyLarge = RudeusBody,
    bodyMedium = RudeusMono,
    bodySmall = RudeusLabel.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = RudeusLabel,
    labelMedium = RudeusLabel,
    labelSmall = RudeusLabel,
)
