package kz.rudy.ar.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.rudy.ar.ArCharacter
import kz.rudy.ar.ui.StringId
import kz.rudy.ar.ui.Strings
import kz.rudy.ar.ui.t
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusSpacing

/** How long the "Table full" pill stays after a tap on the full `+`. */
private const val TABLE_FULL_MS = 2000L

/** The active ring: Sun, thicker than the 2 dp Ink stroke, so it reads over the camera. */
private val ACTIVE_RING = 4.dp

/**
 * T31: who is on the table, top-left on Play. One round 56 dp chip per member with its sprite (the ability glyph when
 * it has none, i.e. the host's built-in sprite); the [activeId] one is ringed in Sun. The line under the members
 * starts with the `+` chip, then the active one's name pill with the ability ("Мұрат · Секір", tap = [onNameTap], the
 * old ability chip). The `+` chip:
 * [onAdd] while there is room ([canAdd]); at `Cast.MAX` it is drawn InkMuted and a tap shows "Table full".
 * The ring changes colour with a fade, so reduced motion needs nothing else.
 */
@Composable
fun CastChips(
    cast: List<ArCharacter>,
    activeId: String?,
    canAdd: Boolean,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
    onNameTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullAt by remember { mutableStateOf(0) }
    LaunchedEffect(fullAt) {
        if (fullAt > 0) { delay(TABLE_FULL_MS); fullAt = 0 }
    }
    val active = CastUi.active(cast, activeId)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Members alone on the top line (4 x 56 + 3 x 8 = 248 dp), so the right column keeps its room on a 411 dp phone.
        Row(horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            cast.forEach { c -> MemberChip(c, isActive = c.id == active?.id, onClick = { onPick(c.id) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(RudeusSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            AddChip(enabled = canAdd, onClick = { if (canAdd) onAdd() else fullAt++ })
            if (fullAt > 0) {
                Pill(t(StringId.play_table_full))
            } else if (active != null) {
                Chip(
                    text = "${active.name} · ${t(Strings.abilityName(active.ability))}",
                    glyph = active.ability.glyph(),
                    onClick = onNameTap,
                )
            }
        }
    }
}

@Composable
private fun MemberChip(c: ArCharacter, isActive: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(
        if (isActive) RudeusColors.Sun else RudeusColors.Ink,
        tween(RudeusMotion.REDUCED_MS),
        label = "ring",
    )
    val px = with(LocalDensity.current) { RudeusSpacing.touchTarget.roundToPx() }
    val sprite = rememberSpriteBitmap(c.spritePng, maxWidthPx = px, maxHeightPx = px)
    Box(
        Modifier
            .size(RudeusSpacing.touchTarget)
            .semantics { contentDescription = c.name; selected = isActive }
            .clip(CircleShape)
            .background(RudeusColors.Paper)
            .border(if (isActive) ACTIVE_RING else RudeusSpacing.stroke, ring, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (sprite != null) {
            SpriteImage(sprite, contentDescription = null, modifier = Modifier.padding(7.dp))
        } else {
            GlyphIcon(c.ability.glyph(), RudeusColors.Ink, size = 26.dp)
        }
    }
}

@Composable
private fun AddChip(enabled: Boolean, onClick: () -> Unit) {
    val label = t(StringId.play_add)
    val tint = if (enabled) RudeusColors.Ink else RudeusColors.InkMuted
    Box(
        Modifier
            .size(RudeusSpacing.touchTarget)
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .background(RudeusColors.Paper)
            .border(RudeusSpacing.stroke, tint, CircleShape)
            // Tappable when full too: the tap is what shows "Table full".
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.PLUS, tint, size = 22.dp)
    }
}
