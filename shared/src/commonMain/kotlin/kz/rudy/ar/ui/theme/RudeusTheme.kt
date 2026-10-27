package kz.rudy.ar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** True when the host (androidApp) reports a reduced-motion preference; see DESIGN.md "Motion". */
val LocalReducedMotion = staticCompositionLocalOf { false }

val RudeusColorScheme: ColorScheme = lightColorScheme(
    primary = RudeusColors.Sun,
    onPrimary = RudeusColors.Ink,
    primaryContainer = RudeusColors.Sun,
    onPrimaryContainer = RudeusColors.Ink,
    secondary = RudeusColors.Sky,
    onSecondary = RudeusColors.Paper,
    secondaryContainer = RudeusColors.PaperDeep,
    onSecondaryContainer = RudeusColors.Ink,
    tertiary = RudeusColors.Leaf,
    onTertiary = RudeusColors.Paper,
    error = RudeusColors.Tomato,
    onError = RudeusColors.Paper,
    errorContainer = RudeusColors.Tomato,
    onErrorContainer = RudeusColors.Paper,
    background = RudeusColors.Paper,
    onBackground = RudeusColors.Ink,
    surface = RudeusColors.Paper,
    onSurface = RudeusColors.Ink,
    surfaceVariant = RudeusColors.PaperDeep,
    onSurfaceVariant = RudeusColors.InkSoft,
    outline = RudeusColors.Ink,
    outlineVariant = RudeusColors.InkSoft,
    scrim = RudeusColors.Ink,
)

val RudeusShapes = Shapes(
    extraSmall = RoundedCornerShape(RudeusRadius.card),
    small = RoundedCornerShape(RudeusRadius.card),
    medium = RoundedCornerShape(RudeusRadius.card),
    large = RoundedCornerShape(RudeusRadius.bubble),
    extraLarge = RoundedCornerShape(RudeusRadius.full),
)

@Composable
fun RudeusTheme(reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = RudeusColorScheme,
            typography = RudeusTypography,
            shapes = RudeusShapes,
            content = content,
        )
    }
}
