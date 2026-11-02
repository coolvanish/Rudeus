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
import org.jetbrains.compose.resources.Font
import androidx.compose.runtime.Composable
import rudeus.shared.generated.resources.Res
import rudeus.shared.generated.resources.nunito_semibold
import rudeus.shared.generated.resources.nunito_bold
import rudeus.shared.generated.resources.nunito_extrabold
import rudeus.shared.generated.resources.nunito_black

/**
 * Design tokens v2 — "bright & clean". Source of truth: DESIGN.md (docs/design-v2 holds the mockups).
 * Light only: the UI is paper and ink over a live camera feed.
 * Fonts: add Nunito (OFL) TTFs 600/700/800/900 to shared/src/commonMain/composeResources/font/.
 */
object RudeusColors {
    // surfaces
    val Paper = Color(0xFFFFFDF8)
    val Cream = Color(0xFFFAF4E8)       // borderless card fill
    val CreamDeep = Color(0xFFF2E9D6)   // pressed, tracks, table stripes
    val Line = Color(0xFFE8DFCD)        // 1dp edge on HUD surfaces, list dividers
    // ink
    val Ink = Color(0xFF1D1A16)
    val InkSoft = Color(0xFF5D564E)     // secondary text, 7.2:1 on Paper
    val InkMuted = Color(0xFF968E84)    // disabled / decorative only
    // brand
    val Sun = Color(0xFFFFD02B)
    val SunHover = Color(0xFFFFD94F)
    val SunDeep = Color(0xFFF2B90F)
    val SunTint = Color(0xFFFFF4C7)
    // secondary / status
    val Sky = Color(0xFF1E6FE0)
    val SkyDeep = Color(0xFF1659B8)
    val SkyTint = Color(0xFFE7F0FC)
    val Leaf = Color(0xFF2E8540)
    val LeafTint = Color(0xFFE4F2E7)
    val Tomato = Color(0xFFD93A2F)
    val TomatoTint = Color(0xFFFCE8E6)
    val Grape = Color(0xFF7B3FBF)
    val GrapeTint = Color(0xFFF1E9FA)
    val Hazard = Color(0xFFFF6D00)

    val Scrim = Ink.copy(alpha = 0.55f)
    val ScrimStrong = Ink.copy(alpha = 0.75f)

    val FootObstacle = Tomato
    val FootPlatform = Leaf
    val FootDynamic = Grape
    val FootCliff = Hazard

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
    val smd = 12.dp
    val md = 16.dp
    val screenEdge = 20.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    val touchTarget = 56.dp
    val primaryButton = 64.dp
    val secondaryButton = 48.dp

    /** Ink edge on interactive things only (buttons, chips, segmented, switch). Cards have none. */
    val stroke = 2.dp
    /** Edge on non-interactive HUD surfaces over the camera (pills, banners, bubble). */
    val hairline = 1.dp
}

object RudeusRadius {
    val tag = 8.dp
    val small = 10.dp
    val tile = 16.dp
    val card = 20.dp
    val hero = 28.dp      // hero card, bottom sheet
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

@Composable
fun nunito() = FontFamily(
    Font(Res.font.nunito_semibold, FontWeight.SemiBold),
    Font(Res.font.nunito_bold, FontWeight.Bold),
    Font(Res.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(Res.font.nunito_black, FontWeight.Black),
)

@Composable
fun rudeusTypography(): Typography {
    val f = nunito()
    val display = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 30.sp)
    val headline = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
    val label = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp)
    val caption = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.sp)
    return Typography(
        displayLarge = display, displayMedium = display,
        headlineLarge = title, headlineMedium = title, titleLarge = title,
        titleMedium = headline,
        bodyLarge = body, bodyMedium = RudeusMono, bodySmall = caption,
        labelLarge = label, labelMedium = label, labelSmall = caption,
    )
}

/** Metrics numbers and run ids. System monospace is fine; JetBrains Mono if bundled. */
val RudeusMono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)

/** Wordmark "Rudeus" on Capture. */
@Composable
fun rudeusWordmark() = TextStyle(fontFamily = nunito(), fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 28.sp, letterSpacing = (-0.8).sp)
