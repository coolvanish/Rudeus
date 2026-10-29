package kz.rudy.ar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing
import androidx.compose.ui.unit.dp

/**
 * Shown by :androidApp in place of the AR view until the CAMERA permission is granted (paper, one Sun button).
 * :shared knows nothing about permissions; the host decides when to show it and what [onAllow] launches.
 */
@Composable
fun PermissionScreen(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(RudeusColors.Paper), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(RudeusSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphIcon(Glyph.CAMERA, RudeusColors.Ink, size = 64.dp)
            Spacer(Modifier.height(RudeusSpacing.md))
            Text(t(StringId.camera_permission_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(RudeusSpacing.sm))
            Text(t(StringId.camera_permission_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(RudeusSpacing.lg))
            PrimaryButton(text = t(StringId.camera_permission_button), glyph = Glyph.CAMERA, onClick = onAllow, modifier = Modifier.fillMaxWidth())
        }
    }
}
