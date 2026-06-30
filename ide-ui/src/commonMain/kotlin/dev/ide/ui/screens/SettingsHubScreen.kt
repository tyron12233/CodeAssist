package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The Settings & Tools hub — the single entry to the settings and toolchain managers, reachable both from the
 * project picker (with no project open) and from inside the editor. The Settings row opens the unified settings
 * screen, which merges the project-scoped pages in when a project is open (and shows only the global pages from
 * the picker). Pure navigation: each row hands control back to the host.
 */
@Composable
fun SettingsHubScreen(
    onBack: () -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onOpenCodeStyle: () -> Unit,
    onOpenSdkManager: () -> Unit,
    onOpenKeystoreManager: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButtonCa(CaIcons.chevronLeft, "Back", onBack)
                    Icon(CaIcons.gear, null, Modifier.size(20.dp), tint = Ca.colors.accent)
                    Text("Settings & Tools", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HubRow(CaIcons.gear, "Settings", "Appearance, editor, completion & analysis", onOpenGlobalSettings)
                HubRow(CaIcons.braces, "Code Style", "Per-language formatting profiles & live preview", onOpenCodeStyle)
                HubRow(CaIcons.pkg, "SDK Manager", "Download Android SDK & JDK sources", onOpenSdkManager)
                HubRow(CaIcons.key, "Keystore Manager", "Create, import & manage signing keystores", onOpenKeystoreManager)
            }
        }
    }
}

@Composable
private fun HubRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Ca.colors.surface, RoundedCornerShape(Ca.radius.lg))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Ca.colors.accent)
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.body, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(18.dp), tint = Ca.colors.textTertiary)
    }
}
