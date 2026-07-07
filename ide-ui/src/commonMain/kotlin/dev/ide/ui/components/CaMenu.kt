package dev.ide.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The app's dropdown menu: a softly-rounded, hairline-bordered panel on the elevated surface with a gentle
 * shadow — replacing Material's squared default so every popup matches the bespoke design language. A drop-in
 * for [DropdownMenu] (the [containerColor] is clipped to the rounded [shape], so call sites no longer need the
 * old `Modifier.background(...)` hack). [offset] nudges the menu off its anchor.
 */
@Composable
fun CaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(Ca.radius.md),
        containerColor = Ca.colors.surface2,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, Ca.colors.glassEdge),
        content = content,
    )
}

/**
 * A menu row that opens a native **flyout submenu** beside itself, rather than swapping the parent menu's
 * contents in place. The flyout anchors to the row's top-right corner (flipping toward the left edge of the
 * window automatically when there's no room on the right) and inherits [CaDropdownMenu]'s scale+fade
 * entrance, so it visibly grows out of the parent row the way a desktop/native submenu does.
 *
 * It opens on hover (desktop) or tap (touch). [expanded]/[onExpandedChange] are hoisted so the hosting menu
 * can keep a single submenu open at a time (set its key on open, clear on close) — point each sibling at the
 * same backing state and only one flyout is ever live.
 */
@Composable
fun CaSubmenuItem(
    label: String,
    icon: ImageVector?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var itemSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Desktop: hovering the row opens the flyout (the native affordance). Touch has no hover, so the tap
    // handler below is the open path there. We never auto-close on hover-out, so the pointer can travel from
    // the row into the (separate) flyout popup without it collapsing — it closes via dismiss or a sibling.
    LaunchedEffect(hovered) { if (hovered) onExpandedChange(true) }
    val labelColor = if (danger) Ca.colors.error else Ca.colors.textPrimary
    val iconTint = if (danger) Ca.colors.error else Ca.colors.textSecondary
    Box {
        DropdownMenuItem(
            text = { Text(label, color = labelColor, style = Ca.type.footnote) },
            leadingIcon = icon?.let { { Icon(it, null, Modifier.size(15.dp), tint = iconTint) } },
            trailingIcon = {
                Icon(
                    CaIcons.caretRight,
                    null,
                    Modifier.size(15.dp),
                    tint = Ca.colors.textTertiary
                )
            },
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .hoverable(interaction)
                .onGloballyPositioned { itemSize = it.size },
        )
        // Anchor the flyout to the row's top-right corner: shift it right by the row's width (less a hair so
        // it overlaps the row and leaves no dead gap a hovering pointer would fall through) and up by the row
        // height so the panel's top lines up with the row. Material's menu position provider keeps it on
        // screen, flipping horizontally near the window edge.
        val flyoutOffset = with(density) {
            DpOffset(x = itemSize.width.toDp() - 4.dp, y = -itemSize.height.toDp())
        }
        CaDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = flyoutOffset,
            content = content,
        )
    }
}
