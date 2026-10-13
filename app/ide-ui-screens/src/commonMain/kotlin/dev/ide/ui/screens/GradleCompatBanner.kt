package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import dev.ide.ui.backend.UiSyncResult
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.gradle_convert
import dev.ide.ui.generated.resources.gradle_kmp_android
import dev.ide.ui.generated.resources.gradle_kmp_jvm
import dev.ide.ui.generated.resources.gradle_kmp_target
import dev.ide.ui.generated.resources.gradle_mode_title
import dev.ide.ui.generated.resources.gradle_resync
import dev.ide.ui.generated.resources.gradle_sync_needed
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
 * compat chip re-opens it, so the limitation is never truly hidden. For a Kotlin Multiplatform project whose
 * modules could be imported for Android or the desktop JVM, the details also hold that choice.
 * [onSynced] runs after a successful sync, so the caller can re-read [info] (its notes describe the import).
 */
@Composable
internal fun GradleCompatBanner(
    state: IdeUiState,
    info: UiCompatibilityInfo,
    visible: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
    onConvert: () -> Unit,
    onSynced: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val syncingLabel = stringResource(Res.string.gradle_syncing)
    // A changed build file makes the model out of date; say so in place of the standing explanation.
    val leadLine = if (info.syncNeeded) stringResource(Res.string.gradle_sync_needed) else info.summary
    val hasDetails = info.notes.isNotEmpty() || info.kmpTarget != null
    fun sync(action: suspend () -> UiSyncResult) {
        syncing = true
        result = syncingLabel
        scope.launch {
            val r = action()
            result = r.message
            syncing = false
            if (r.ok) {
                state.reanalyzeOpenFiles()
                onSynced()
            }
        }
    }

    AnimatedVisibility(visible) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), material = GlassMaterial.Regular) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Ide.colors.warning.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ide.colors.warning)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.gradle_mode_title),
                            color = Ide.colors.warning, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            result ?: leadLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Re-sync: re-read the Gradle scripts into the model, then re-resolve deps + re-index.
                    Row(
                        Modifier.background(Ide.colors.warning.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.pill))
                            .clickable(enabled = !syncing) { sync { state.backend.projects.syncProject() } }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (syncing) CircularProgressIndicator(Modifier.size(13.dp), color = Ide.colors.warning, strokeWidth = 2.dp)
                        else Icon(CaIcons.refresh, stringResource(Res.string.gradle_resync), Modifier.size(13.dp), tint = Ide.colors.warning)
                        if (!compact) Text(stringResource(Res.string.gradle_resync), color = Ide.colors.warning, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    // Convert: make module.toml the source of truth (moves the Gradle files to a backup folder).
                    Row(
                        Modifier.background(Ide.colors.warning.copy(alpha = 0.18f), RoundedCornerShape(Ca.radius.pill))
                            .clickable(enabled = !syncing, onClick = onConvert)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(CaIcons.check, stringResource(Res.string.gradle_convert), Modifier.size(13.dp), tint = Ide.colors.warning)
                        if (!compact) Text(stringResource(Res.string.gradle_convert), color = Ide.colors.warning, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (hasDetails) {
                        IconButtonCa(
                            if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                            if (expanded) stringResource(Res.string.hide_details) else stringResource(Res.string.show_details),
                            { expanded = !expanded }, boxSize = 24, iconSize = 14,
                        )
                    }
                    IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), onDismiss, boxSize = 24, iconSize = 14)
                }
                AnimatedVisibility(expanded && hasDetails) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(top = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        info.kmpTarget?.let { current ->
                            KmpTargetChoice(current, enabled = !syncing) { target ->
                                sync { state.backend.projects.setKmpTarget(target) }
                            }
                        }
                        for (note in info.notes) {
                            Text(
                                "•  $note",
                                color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which target a Kotlin Multiplatform project's modules are imported for: Android or the desktop JVM. Picking
 * the other one re-imports the project (see `ProjectService.setKmpTarget`).
 */
@Composable
private fun KmpTargetChoice(current: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(Res.string.gradle_kmp_target),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
        )
        for ((id, label) in listOf("android" to Res.string.gradle_kmp_android, "jvm" to Res.string.gradle_kmp_jvm)) {
            val selected = id == current
            Text(
                stringResource(label),
                modifier = Modifier
                    .background(Ide.colors.warning.copy(alpha = if (selected) 0.30f else 0.10f), RoundedCornerShape(Ca.radius.pill))
                    .clickable(enabled = enabled && !selected) { onPick(id) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Ide.colors.warning,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
