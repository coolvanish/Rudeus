package kz.rudy.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kz.rudy.ar.ui.theme.LocalReducedMotion
import kz.rudy.ar.ui.theme.RudeusColors
import kz.rudy.ar.ui.theme.RudeusMotion
import kz.rudy.ar.ui.theme.RudeusRadius
import kz.rudy.ar.ui.theme.RudeusSpacing

/** How far a downward drag on the sheet has to travel to close it. */
private val DRAG_TO_CLOSE = 48.dp

/**
 * Paper sheet with a handle over [RudeusColors.Scrim]. There are no modal dialogs (DESIGN.md (f)); this is the one
 * overlay. A tap on the scrim or a downward drag calls [onDismiss]; null keeps the sheet up until the content acts.
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(RudeusMotion.BANNER_ENTER_MS)),
            exit = fadeOut(tween(RudeusMotion.BANNER_EXIT_MS)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RudeusColors.Scrim)
                    .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } },
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (reduced) fadeIn(tween(RudeusMotion.REDUCED_MS)) else
                slideInVertically(tween(RudeusMotion.BANNER_ENTER_MS, easing = RudeusMotion.Emphasised)) { it },
            exit = if (reduced) fadeOut(tween(RudeusMotion.REDUCED_MS)) else
                slideOutVertically(tween(RudeusMotion.BANNER_EXIT_MS, easing = RudeusMotion.Standard)) { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = RudeusRadius.hero, topEnd = RudeusRadius.hero))
                    .background(RudeusColors.Paper)
                    .pointerInput(onDismiss) {
                        var dragged = 0f
                        detectVerticalDragGestures(onDragStart = { dragged = 0f }) { _, dy ->
                            dragged += dy
                            if (dragged > DRAG_TO_CLOSE.toPx()) onDismiss?.invoke()
                        }
                    }
                    .navigationBarsPadding()
                    .padding(start = RudeusSpacing.screenEdge, end = RudeusSpacing.screenEdge, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(RudeusSpacing.md),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(40.dp, 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(RudeusColors.Line),
                )
                content()
            }
        }
    }
}
