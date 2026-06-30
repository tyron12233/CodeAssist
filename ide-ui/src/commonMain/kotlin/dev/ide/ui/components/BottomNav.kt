package dev.ide.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion

/** One destination in the [BottomNavBar]: a stable [id], its [label], and the [icon] glyph. */
data class BottomNavItem(val id: String, val label: String, val icon: ImageVector)

/**
 * The home screen's bottom navigation bar. A glass strip with a top hairline; each destination is an
 * icon over a label, the selected one lifted to the accent color with a soft accent pill behind its glyph.
 * Hosted inside the app root's consumed `safeDrawing` inset, so it already sits above the system nav bar
 * (the nav-bar inset reads zero here) — no extra inset padding is applied.
 */
@Composable
fun BottomNavBar(
    items: List<BottomNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val edge = Ca.colors.glassEdgeTop
    Row(
        modifier
            .fillMaxWidth()
            .background(Ca.colors.glassThick)
            .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            NavTab(item, selected = item.id == selectedId, onClick = { onSelect(item.id) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavTab(item: BottomNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        if (selected) Ca.colors.accent else Ca.colors.textTertiary,
        tween(Motion.FAST, easing = Motion.soft),
        label = "navTint",
    )
    // The selected glyph rides a soft accent pill that grows in; unselected tabs have no fill.
    val pillWidth by animateDpAsState(
        if (selected) 46.dp else 36.dp,
        tween(Motion.BASE, easing = Motion.spring),
        label = "navPill",
    )
    Column(
        modifier
            .pressScale(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .width(pillWidth)
                .height(30.dp)
                .background(
                    if (selected) Ca.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(Ca.radius.pill),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, item.label, Modifier.size(20.dp), tint = tint)
        }
        Text(
            item.label,
            color = tint,
            style = Ca.type.caption2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
