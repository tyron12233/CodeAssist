package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiUnrecognizedProject
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.unrecognized_project_origin
import dev.ide.ui.generated.resources.unrecognized_project_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The editor-level notice for a project adopted from a folder no build system recognized, a cloned repository
 * being the case it exists for: an amber strip under the toolbar saying that the files are open for editing
 * but the project has no modules, so building, running and analysis have nothing to work from.
 *
 * Dismissible, and unlike the Gradle compatibility notice there is no top-bar chip to re-open it: dismissing
 * lasts for the current open, and the notice returns the next time the project is opened. Nothing here offers
 * a fix, because the fix is to add a module, which is the Module Settings screen's job.
 */
@Composable
internal fun UnrecognizedProjectBanner(
    info: UiUnrecognizedProject,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), material = GlassMaterial.Regular) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Ide.colors.warning.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ide.colors.warning)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.unrecognized_project_title),
                        color = Ide.colors.warning,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        info.summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.origin.isNotBlank()) {
                        Text(
                            stringResource(Res.string.unrecognized_project_origin, info.origin),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), onDismiss, boxSize = 24, iconSize = 14)
            }
        }
    }
}
