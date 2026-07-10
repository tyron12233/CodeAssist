package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiCompatibilityInfo
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.gradle_mode_title
import dev.ide.ui.generated.resources.gradle_resync
import dev.ide.ui.generated.resources.gradle_syncing
import dev.ide.ui.generated.resources.hide_details
import dev.ide.ui.generated.resources.show_details
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The editor-level notice for a **Gradle compatibility-mode** project: an amber strip under the toolbar
 * explaining that the build scripts were read statically (not run), so builds and dependency resolution may
 * fail, and dependencies/versions were extracted best-effort. Expands to the reader's per-item notes, offers
 * **Re-sync** (re-read the scripts into the model + re-resolve + re-index), and is dismissible — the top-bar
 * compat chip re-opens it, so the limitation is never truly hidden.
 */
@Composable
internal fun GradleCompatBanner(
    state: IdeUiState,
    info: UiCompatibilityInfo,
    visible: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val syncingLabel = stringResource(Res.string.gradle_syncing)

    AnimatedVisibility(visible) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), material = GlassMaterial.Regular) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Ca.colors.warning.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ca.colors.warning)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.gradle_mode_title),
                            color = Ca.colors.warning, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            result ?: info.summary,
                            color = Ca.colors.textSecondary, style = Ca.type.caption2,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Re-sync: re-read the Gradle scripts into the model, then re-resolve deps + re-index.
                    Row(
                        Modifier.background(Ca.colors.warning.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.pill))
                            .clickable(enabled = !syncing) {
                                syncing = true
                                result = syncingLabel
                                scope.launch {
                                    val r = state.backend.projects.syncGradle()
                                    result = r.message
                                    syncing = false
                                    if (r.ok) state.reanalyzeOpenFiles()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (syncing) CircularProgressIndicator(Modifier.size(13.dp), color = Ca.colors.warning, strokeWidth = 2.dp)
                        else Icon(CaIcons.refresh, stringResource(Res.string.gradle_resync), Modifier.size(13.dp), tint = Ca.colors.warning)
                        if (!compact) Text(stringResource(Res.string.gradle_resync), color = Ca.colors.warning, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                    }
                    if (info.notes.isNotEmpty()) {
                        IconButtonCa(
                            if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                            if (expanded) stringResource(Res.string.hide_details) else stringResource(Res.string.show_details),
                            { expanded = !expanded }, boxSize = 24, iconSize = 14,
                        )
                    }
                    IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), onDismiss, boxSize = 24, iconSize = 14)
                }
                AnimatedVisibility(expanded && info.notes.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(top = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (note in info.notes) {
                            Text(
                                "•  $note",
                                color = Ca.colors.textTertiary, style = Ca.type.caption2,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
