package dev.ide.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

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
