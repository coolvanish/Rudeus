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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.PrimaryButton
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusSpacing

/**
 * Shown by :androidApp in place of the AR view until the CAMERA permission is granted (paper, one Sun button).
 * :shared knows nothing about permissions; the host decides when to show it and what [onAllow] launches.
 * It sits in the AR slot, under the Scanning HUD and its bottom row (which has the Back), so Allow stays mid-screen.
 */
@Composable
fun PermissionScreen(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(RudeusColors.Paper), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = RudeusSpacing.screenEdge)) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(RudeusColors.SunTint), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.CAMERA, RudeusColors.Ink, size = 42.dp, strokeWidth = 2.4f)
            }
            Spacer(Modifier.height(RudeusSpacing.lg))
            Text(t(StringId.camera_permission_title), style = MaterialTheme.typography.displayLarge, color = RudeusColors.Ink)
            Spacer(Modifier.height(10.dp))
            Text(t(StringId.camera_permission_hint), style = MaterialTheme.typography.bodyLarge, color = RudeusColors.InkSoft)
            Spacer(Modifier.height(RudeusSpacing.lg))
            PrimaryButton(text = t(StringId.camera_permission_button), glyph = Glyph.CAMERA, onClick = onAllow, modifier = Modifier.fillMaxWidth())
        }
    }
}
