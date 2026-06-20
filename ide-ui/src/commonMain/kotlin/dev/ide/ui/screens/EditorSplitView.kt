package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca

/**
 * The Split layout: the code editor and its live preview together, with a draggable divider to rebalance.
 * Both slots get the SAME surfaces the single-pane modes use, so editing flows straight through to the
 * (debounced) preview.
 *
 * [stacked] (a phone) puts the **preview on top and the editor on the bottom** — the editor sits against the
 * soft keyboard (natural text-input position) while the result stays visible above it. The app root already
 * insets `safeDrawing` (which includes the IME), so when the keyboard is up the usable height is *already*
 * reduced; splitting that proportionally would crush the preview to a sliver, so we cap the editor's share
 * and let the preview keep the larger part — you only need a band of code around the caret while typing, and
 * the editor scrolls to keep it visible. A wider screen places them side by side (editor left, preview right).
 */
@Composable
internal fun SplitEditorPreview(
    stacked: Boolean,
    editor: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    // The editor's share of the split; the divider drags it within [0.2, 0.8].
    var fraction by remember { mutableStateOf(0.5f) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BoxWithConstraints(modifier) {
        if (stacked) {
            // While typing, keep the preview as the larger pane (cap editor ≤ half of the reduced height).
            val editorShare = if (keyboardOpen) fraction.coerceIn(0.3f, 0.5f) else fraction
            val extentPx = with(LocalDensity.current) { maxHeight.toPx() }
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.fillMaxWidth().weight(1f - editorShare))
                // Editor is the bottom pane, so dragging the handle DOWN shrinks it.
                SplitHandle(stacked = true) { delta -> fraction = (fraction - delta / extentPx).coerceIn(0.2f, 0.8f) }
                editor(Modifier.fillMaxWidth().weight(editorShare))
            }
        } else {
            val extentPx = with(LocalDensity.current) { maxWidth.toPx() }
            Row(Modifier.fillMaxSize()) {
                editor(Modifier.fillMaxHeight().weight(fraction))
                SplitHandle(stacked = false) { delta -> fraction = (fraction + delta / extentPx).coerceIn(0.2f, 0.8f) }
                preview(Modifier.fillMaxHeight().weight(1f - fraction))
            }
        }
    }
}

/** The draggable divider between the Split panes — a thin bar with a centered grip handle. */
@Composable
private fun SplitHandle(stacked: Boolean, onDrag: (Float) -> Unit) {
    val drag = Modifier.draggable(
        orientation = if (stacked) androidx.compose.foundation.gestures.Orientation.Vertical else androidx.compose.foundation.gestures.Orientation.Horizontal,
        state = androidx.compose.foundation.gestures.rememberDraggableState { onDrag(it) },
    )
    val grip = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
    if (stacked) {
        Box(Modifier.fillMaxWidth().height(12.dp).then(drag).background(Ca.colors.surface2), contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(3.dp).clip(grip).background(Ca.colors.separatorStrong))
        }
    } else {
        Box(Modifier.fillMaxHeight().width(12.dp).then(drag).background(Ca.colors.surface2), contentAlignment = Alignment.Center) {
            Box(Modifier.height(34.dp).width(3.dp).clip(grip).background(Ca.colors.separatorStrong))
        }
    }
}
