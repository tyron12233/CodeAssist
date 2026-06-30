package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.RailDestination
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/** The expanded-layout left navigation rail (glass-regular, 64px): project tile, destinations, settings. */
@Composable
fun SideRail(
    selected: RailDestination,
    onSelect: (RailDestination) -> Unit,
    projectInitial: String,
    onOpenHub: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.width(76.dp).fillMaxHeight(),
        material = GlassMaterial.Regular,
    ) {
        Column(
            Modifier.fillMaxHeight().padding(top = 18.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProjectTile(projectInitial, size = 42.dp)
            Box(Modifier.padding(vertical = 2.dp).width(32.dp).height(1.dp).background(Ca.colors.separator))

            RailItem(CaIcons.docText, "Files", selected == RailDestination.Files) { onSelect(RailDestination.Files) }
            RailItem(CaIcons.search, "Search", selected == RailDestination.Search) { onSelect(RailDestination.Search) }
            RailItem(CaIcons.gitBranch, "Source", selected == RailDestination.Source) { onSelect(RailDestination.Source) }
            RailItem(CaIcons.ellipsis, "More", selected == RailDestination.More) { onSelect(RailDestination.More) }

            Box(Modifier.weight(1f))
            // One entry to the Settings & Tools hub (global settings · code style · SDK & keystore managers).
            RailItem(CaIcons.gear, "Settings & Tools", false, onClick = onOpenHub)
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            IconButtonCa(
                icon = icon,
                contentDescription = label,
                onClick = onClick,
                active = active,
                iconSize = 22,
                boxSize = 46,
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(15.dp)
                        .background(Ca.colors.error, RoundedCornerShape(Ca.radius.pill)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$badge", color = Ca.colors.textOnAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            label,
            color = if (active) Ca.colors.accent else Ca.colors.textTertiary,
            fontSize = 10.5f.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
