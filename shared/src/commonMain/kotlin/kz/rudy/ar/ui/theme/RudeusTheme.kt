package kz.rudy.ar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** True when the Settings toggle or the host asks for reduced motion; see DESIGN.md "Motion". */
val LocalReducedMotion = staticCompositionLocalOf { false }

val RudeusColorScheme: ColorScheme = lightColorScheme(
    primary = RudeusColors.Sun,
    onPrimary = RudeusColors.Ink,
    primaryContainer = RudeusColors.Sun,
    onPrimaryContainer = RudeusColors.Ink,
    secondary = RudeusColors.Sky,
    onSecondary = RudeusColors.Paper,
    secondaryContainer = RudeusColors.Cream,
    onSecondaryContainer = RudeusColors.Ink,
    tertiary = RudeusColors.Leaf,
    onTertiary = RudeusColors.Paper,
    error = RudeusColors.Tomato,
    onError = RudeusColors.Paper,
    errorContainer = RudeusColors.TomatoTint,
    onErrorContainer = RudeusColors.Tomato,
    background = RudeusColors.Paper,
    onBackground = RudeusColors.Ink,
    surface = RudeusColors.Paper,
    onSurface = RudeusColors.Ink,
    surfaceVariant = RudeusColors.Cream,
    onSurfaceVariant = RudeusColors.InkSoft,
    outline = RudeusColors.Ink,
    outlineVariant = RudeusColors.Line,
    scrim = RudeusColors.Ink,
)

val RudeusShapes = Shapes(
    extraSmall = RoundedCornerShape(RudeusRadius.tag),
    small = RoundedCornerShape(RudeusRadius.tile),
    medium = RoundedCornerShape(RudeusRadius.card),
    large = RoundedCornerShape(RudeusRadius.hero),
    extraLarge = RoundedCornerShape(RudeusRadius.full),
)

@Composable
fun RudeusTheme(reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = RudeusColorScheme,
            typography = rudeusTypography(),
            shapes = RudeusShapes,
            content = content,
        )
    }
}
