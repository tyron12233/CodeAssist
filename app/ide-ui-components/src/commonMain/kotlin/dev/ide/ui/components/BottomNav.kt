package dev.ide.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.CaMotion
import dev.ide.ui.theme.Symbol

/** One destination in the [BottomNavBar]: a stable [id], its [label], and the [icon] glyph. */
data class BottomNavItem(val id: String, val label: String, val icon: ImageVector)

/**
 * The home screen's bottom navigation, a native M3 [NavigationBar]. The selected destination gets the
 * expressive `secondaryContainer` pill indicator behind its glyph automatically. Hosted inside the app
 * root's consumed `safeDrawing` inset, so the bar's own system-bar inset reads zero and it sits correctly
 * above the system nav bar.
 */
@Composable
fun BottomNavBar(
    items: List<BottomNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.id == selectedId,
                onClick = { onSelect(item.id) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

/**
 * [AppNavBar] with some destinations wearing a Halloween glyph ([seasonal], keyed by destination id) in place
 * of their usual one. Labels, semantics, the animated pill and the colors are [AppNavBar]'s, unchanged.
 *
 * A sibling rather than a parameter on [AppNavBar] only because that file belongs to other in-flight work;
 * folding this into an `icon` slot there is the natural next step, and this then becomes a one-line call.
 */
@Composable
fun SeasonalAppNavBar(
    destinations: List<NavDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    seasonal: Map<String, HalloweenGlyph>,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.height(82.dp).padding(horizontal = 8.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { d ->
                val selected = d.id == selectedId
                val pillWidth by animateDpAsState(
                    targetValue = if (selected) 76.dp else 52.dp,
                    animationSpec = CaMotion.defaultSpatial(),
                    label = "navPill",
                )
                Column(
                    Modifier.weight(1f)
                        .selectable(selected = selected, onClick = { onSelect(d.id) }, role = Role.Tab)
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier.width(pillWidth).height(34.dp)
                            .clip(CircleShape)
                            .background(if (selected) c.primaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = if (selected) c.onPrimaryContainer else c.onSurfaceVariant
                        val glyph = seasonal[d.id]
                        if (glyph != null) {
                            HalloweenNavGlyph(glyph, selected, size = 24.dp, tint = tint)
                        } else {
                            Symbol(glyph = d.glyph, contentDescription = null, size = 24.dp, filled = selected, tint = tint)
                        }
                    }
                    Text(
                        text = d.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (selected) c.onSurface else c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
