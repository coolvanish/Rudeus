package kz.rudy.ar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.rudy.ar.ui.Lang
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.components.BottomSheet
import kz.rudy.ar.ui.components.Glyph
import kz.rudy.ar.ui.components.GlyphIcon
import kz.rudy.ar.ui.components.SegmentedControl
import kz.rudy.ar.ui.components.Switch
import kz.rudy.ar.ui.components.Tag
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMono
import kz.rudy.ar.ui.theme.RudeusSpacing

/** Matches `versionName` in androidApp/build.gradle.kts; :shared has no BuildConfig of its own. */
private const val ABOUT_VERSION = "Rudeus 1.0"

/** Settings, over Capture: the language switch, Sounds, Reduce motion, Metrics, About. [reduceMotion] is App's toggle. */
@Composable
fun SettingsSheet(
    visible: Boolean,
    lang: Lang,
    onLangChange: (Lang) -> Unit,
    sounds: Boolean,
    onSoundsChange: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
    onOpenMetrics: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Text(t(StringId.settings_title), style = MaterialTheme.typography.titleLarge, color = RudeusColors.Ink)
        LanguageRow(lang, onLangChange)
        Column {
            SwitchRow(Glyph.SOUND, t(StringId.settings_sounds), null, sounds, onSoundsChange)
            Divider()
            SwitchRow(Glyph.MOTION, t(StringId.settings_reduce_motion), t(StringId.settings_reduce_motion_hint), reduceMotion, onReduceMotionChange)
            Divider()
            SettingRow(
                glyph = Glyph.METRICS,
                label = t(StringId.metrics_title),
                sub = t(StringId.settings_metrics_hint),
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenMetrics),
            ) {
                Tag("dev")
                GlyphIcon(Glyph.RIGHT, RudeusColors.InkMuted)
            }
            Divider()
            SettingRow(Glyph.INFO, t(StringId.settings_about), ABOUT_VERSION, subStyle = RudeusMono.copy(fontSize = 12.sp, lineHeight = 17.sp))
        }
    }
}

@Composable
private fun LanguageRow(lang: Lang, onLangChange: (Lang) -> Unit) {
    Column {
        SettingRow(Glyph.GLOBE, t(StringId.settings_language), null, Modifier.heightIn(min = 48.dp))
        Spacer(Modifier.height(6.dp))
        SegmentedControl(
            options = Lang.entries,
            selected = lang,
            onSelect = onLangChange,
            // Each language is named in its own script whatever the current one is: ҚАЗ · РУС · ENG.
            label = {
                when (it) {
                    Lang.KK -> StringId.lang_kk.kk
                    Lang.RU -> StringId.lang_ru.kk
                    Lang.EN -> StringId.lang_en.kk
                }
            },
            fill = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(glyph: Glyph, label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        glyph = glyph,
        label = label,
        sub = sub,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) { Switch(checked) }
}

@Composable
private fun SettingRow(
    glyph: Glyph,
    label: String,
    sub: String?,
    modifier: Modifier = Modifier,
    subStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = RudeusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(RudeusColors.Cream),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(glyph, RudeusColors.Ink, size = 22.dp) }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp, lineHeight = 22.sp), color = RudeusColors.Ink)
            if (sub != null) Text(sub, style = subStyle, color = RudeusColors.InkSoft)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(RudeusSpacing.hairline).background(RudeusColors.Line))
}
