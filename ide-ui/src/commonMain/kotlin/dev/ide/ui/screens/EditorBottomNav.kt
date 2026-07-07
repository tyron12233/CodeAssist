package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.RailDestination
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.edbottomnav_source
import dev.ide.ui.generated.resources.more
import dev.ide.ui.generated.resources.search
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/** The phone-only bottom navigation items (the compact counterpart to the desktop side rail). Files has no
 *  tab here — the file tree is the left push drawer (edge swipe or the top-bar toggle), not a destination.
 *  Draws no surface of its own: it renders as the collapsed face of the [dev.ide.ui.components.BuildDock],
 *  which owns the glass bar and the swipe-up-to-console gesture. */
@Composable
internal fun BottomNav(selected: RailDestination, onSelect: (RailDestination) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomNavItem(CaIcons.search, stringResource(Res.string.search), selected == RailDestination.Search) { onSelect(RailDestination.Search) }
        BottomNavItem(CaIcons.gitBranch, stringResource(Res.string.edbottomnav_source), selected == RailDestination.Source) { onSelect(RailDestination.Source) }
        BottomNavItem(CaIcons.ellipsis, stringResource(Res.string.more), selected == RailDestination.More) { onSelect(RailDestination.More) }
    }
}

@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            IconButtonCa(icon, label, onClick, active = active, iconSize = 22, boxSize = 40)
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .height(15.dp).width(15.dp)
                        .background(Ca.colors.error, androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.pill)),
                    contentAlignment = Alignment.Center,
                ) { Text("$badge", color = Ca.colors.textOnAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text(label, color = if (active) Ca.colors.accent else Ca.colors.textTertiary, fontSize = 10.5f.sp)
    }
}
