package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiToolchainWarning
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.toolchain_warning_accept_note
import dev.ide.ui.generated.resources.toolchain_warning_build_anyway
import dev.ide.ui.generated.resources.toolchain_warning_working
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The editor-level notice for a toolchain problem that WILL break this module's build, shown before the build
 * is attempted rather than left to surface as a wall of errors in generated code. Today's only case is a bundled
 * KSP processor whose generated code needs a newer runtime than the module declares (see
 * `KspProcessorCatalog.RuntimeMismatch`).
 *
 * Two actions, because there are only two honest outcomes. The fix aligns the declared runtime to the version
 * the IDE bundles (an update, or a downgrade when the project pins something newer; the backend labels which).
 * "Build anyway" records that the user accepts it: source generation stops refusing to run and reports the
 * problem once per build instead, so the build proceeds to the compile error the generated code causes. That is
 * an acknowledgement, not a fix, and the banner says so.
 *
 * Dismiss is per session and per file open: it hides the strip without recording anything, so the problem
 * returns rather than being silently forgotten.
 */
@Composable
internal fun ToolchainWarningBanner(state: IdeUiState, filePath: String) {
    var warnings by remember(filePath) { mutableStateOf<List<UiToolchainWarning>>(emptyList()) }
    var dismissed by remember(filePath) { mutableStateOf(setOf<String>()) }
    var busyId by remember(filePath) { mutableStateOf<String?>(null) }
    var result by remember(filePath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val workingLabel = stringResource(Res.string.toolchain_warning_working)

    // Re-read on file switch and after an action: fixing one removes it, accepting one hides it.
    suspend fun reload() {
        warnings = runCatching { state.backend.modules.toolchainWarnings(filePath) }.getOrDefault(emptyList())
    }
    LaunchedEffect(filePath) { reload() }

    val shown = warnings.filterNot { it.id in dismissed }
    if (shown.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        for (w in shown) {
            val busy = busyId == w.id
            AnimatedVisibility(true) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Ide.colors.warning.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(CaIcons.warning, null, Modifier.size(16.dp).padding(top = 2.dp), tint = Ide.colors.warning)
                        Column(Modifier.weight(1f)) {
                            Text(
                                w.title,
                                color = Ide.colors.warning,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (busy) workingLabel else (result ?: w.detail),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButtonCa(
                            CaIcons.close, stringResource(Res.string.dismiss),
                            { dismissed = dismissed + w.id }, boxSize = 24, iconSize = 14,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The fix: align the declared runtime to the bundled processor's version.
                        w.fixLabel?.let { label ->
                            BannerAction(label, primary = true, enabled = !busy) {
                                busyId = w.id
                                scope.launch {
                                    val r = state.backend.modules.fixToolchainWarning(w.moduleName, w.id)
                                    result = r.message
                                    busyId = null
                                    reload()
                                    if (r.success) state.reanalyzeOpenFiles()
                                }
                            }
                        }
                        // The acknowledgement: unblock generation, keep reporting it, expect the compile to fail.
                        if (w.acceptable) {
                            BannerAction(
                                stringResource(Res.string.toolchain_warning_build_anyway),
                                primary = false, enabled = !busy,
                            ) {
                                busyId = w.id
                                scope.launch {
                                    val r = state.backend.modules.acceptToolchainWarning(w.moduleName, w.id)
                                    result = r.message
                                    busyId = null
                                    reload()
                                }
                            }
                            Text(
                                stringResource(Res.string.toolchain_warning_accept_note),
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A pill action in the warning strip: filled for the fix, outlined for the acknowledgement. */
@Composable
private fun BannerAction(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Ide.colors.warning else MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .background(
                Ide.colors.warning.copy(alpha = if (primary) 0.18f else 0.08f),
                RoundedCornerShape(Ca.radius.pill),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tint, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
