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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiToolchainWarning
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.hide_details
import dev.ide.ui.generated.resources.show_details
import dev.ide.ui.generated.resources.toolchain_warning_accept_note
import dev.ide.ui.generated.resources.toolchain_warning_build_anyway
import dev.ide.ui.generated.resources.toolchain_warning_many
import dev.ide.ui.generated.resources.toolchain_warning_working
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The editor-level notice for a toolchain problem that WILL break a module's build, shown as soon as the project
 * opens rather than after a build has already failed. Today's only case is a bundled KSP processor whose
 * generated code needs a newer runtime than the module declares (see `KspProcessorCatalog.RuntimeMismatch`).
 *
 * Project-scoped, not tied to the active file: the problem belongs to a module's configuration, and the module
 * that has it is usually one nobody opens (a `di/` or `data/` module). So this sits with the other
 * project-level strips, above the tab bar, and is present with no file open at all.
 *
 * Two actions per problem, because there are only two honest outcomes. The fix aligns the declared runtime to
 * the version the IDE bundles (an update, or a downgrade when the project pins something newer; the backend
 * labels which). "Build anyway" records that the user accepts it: source generation stops refusing to run and
 * reports the problem once per build instead, so the build proceeds to the compile error the generated code
 * causes. That is an acknowledgement, not a fix, and the strip says so.
 *
 * Dismiss is per session: it hides the strip without recording anything, so the problem comes back rather than
 * being silently forgotten.
 *
 * **Bounded on a phone**, which is the whole layout constraint here. One problem renders as a card whose
 * explanation is two lines until expanded and whose actions are full-width stacked rows ([compact]) instead of
 * pills competing with a sentence for a 430dp line. Several problems collapse behind one summary row, so a
 * project with three bad modules cannot push the editor off screen.
 */
@Composable
internal fun ToolchainWarningBanner(state: IdeUiState, compact: Boolean) {
    val rootPath = state.backend.project.rootPath
    val depsState by state.backend.deps.depsState.collectAsState()
    var warnings by remember(rootPath) { mutableStateOf<List<UiToolchainWarning>>(emptyList()) }
    var dismissed by remember(rootPath) { mutableStateOf(setOf<String>()) }
    var expanded by remember(rootPath) { mutableStateOf(setOf<String>()) }
    var listOpen by remember(rootPath) { mutableStateOf(false) }
    var busyId by remember(rootPath) { mutableStateOf<String?>(null) }
    var results by remember(rootPath) { mutableStateOf(mapOf<String, String>()) }
    var epoch by remember(rootPath) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Read on open, after an action, and once a dependency resolve settles (the versions may just have changed
    // from the Dependencies screen, which is the other way this gets fixed).
    LaunchedEffect(rootPath, epoch, depsState.resolving) {
        if (!depsState.resolving) {
            warnings = runCatching { state.backend.modules.toolchainWarnings() }.getOrDefault(emptyList())
        }
    }

    val shown = warnings.filterNot { it.id in dismissed }
    if (shown.isEmpty()) return

    val onFix: (UiToolchainWarning) -> Unit = { w ->
        busyId = w.id
        scope.launch {
            val r = state.backend.modules.fixToolchainWarning(w.moduleName, w.id)
            results = results + (w.id to r.message)
            busyId = null
            epoch++
            if (r.success) state.reanalyzeOpenFiles()
        }
    }
    val onAccept: (UiToolchainWarning) -> Unit = { w ->
        busyId = w.id
        scope.launch {
            val r = state.backend.modules.acceptToolchainWarning(w.moduleName, w.id)
            results = results + (w.id to r.message)
            busyId = null
            epoch++
        }
    }

    Column(Modifier.fillMaxWidth().background(Ide.colors.warning.copy(alpha = 0.10f))) {
        // Several affected modules collapse behind one row: three full cards would fill a phone screen, and the
        // count is the part that matters at a glance.
        if (shown.size > 1) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { listOpen = !listOpen }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ide.colors.warning)
                Text(
                    stringResource(Res.string.toolchain_warning_many, shown.size),
                    color = Ide.colors.warning,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (listOpen) CaIcons.caretDown else CaIcons.caretRight,
                    if (listOpen) stringResource(Res.string.hide_details) else stringResource(Res.string.show_details),
                    Modifier.size(14.dp), tint = Ide.colors.warning,
                )
                IconButtonCa(
                    CaIcons.close, stringResource(Res.string.dismiss),
                    { dismissed = dismissed + shown.map { it.id } }, boxSize = 24, iconSize = 14,
                )
            }
        }
        AnimatedVisibility(shown.size == 1 || listOpen) {
            Column(Modifier.fillMaxWidth()) {
                for (w in shown) {
                    WarningCard(
                        warning = w,
                        compact = compact,
                        busy = busyId == w.id,
                        detailExpanded = w.id in expanded,
                        result = results[w.id],
                        indented = shown.size > 1,
                        onToggleDetail = { expanded = if (w.id in expanded) expanded - w.id else expanded + w.id },
                        onDismiss = { dismissed = dismissed + w.id },
                        onFix = { onFix(w) },
                        onAccept = { onAccept(w) },
                    )
                }
            }
        }
    }
}

/** One problem: title, a two-line-until-expanded explanation, and the two actions. */
@Composable
private fun WarningCard(
    warning: UiToolchainWarning,
    compact: Boolean,
    busy: Boolean,
    detailExpanded: Boolean,
    result: String?,
    indented: Boolean,
    onToggleDetail: () -> Unit,
    onDismiss: () -> Unit,
    onFix: () -> Unit,
    onAccept: () -> Unit,
) {
    val workingLabel = stringResource(Res.string.toolchain_warning_working)
    // Text and actions hang off one indent, so the icon column reads as a gutter rather than the text
    // re-starting at a different x on every row.
    val indent = if (indented) 12.dp else 24.dp
    Column(
        Modifier.fillMaxWidth().padding(start = if (indented) 12.dp else 0.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!indented) {
                Icon(
                    CaIcons.warning, null,
                    Modifier.padding(top = 2.dp).size(16.dp), tint = Ide.colors.warning,
                )
            }
            Text(
                warning.title,
                color = Ide.colors.warning,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButtonCa(
                if (detailExpanded) CaIcons.caretDown else CaIcons.caretRight,
                if (detailExpanded) stringResource(Res.string.hide_details) else stringResource(Res.string.show_details),
                onToggleDetail, boxSize = 24, iconSize = 14,
            )
            if (!indented) {
                IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), onDismiss, boxSize = 24, iconSize = 14)
            }
        }
        // The explanation (or the last action's result). Two lines by default so the strip never pushes the
        // editor off screen; the caret above opens it.
        Text(
            if (busy) workingLabel else (result ?: warning.detail),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = if (detailExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = indent),
        )

        val fix: (@Composable () -> Unit)? = warning.fixLabel?.let { label ->
            { BannerAction(label, primary = true, enabled = !busy, stretch = compact, onClick = onFix) }
        }
        val accept: (@Composable () -> Unit)? = if (!warning.acceptable) null else {
            {
                BannerAction(
                    stringResource(Res.string.toolchain_warning_build_anyway),
                    primary = false, enabled = !busy, stretch = compact, onClick = onAccept,
                )
            }
        }
        val note: @Composable (Modifier) -> Unit = { mod ->
            if (warning.acceptable) {
                Text(
                    stringResource(Res.string.toolchain_warning_accept_note),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = mod,
                )
            }
        }

        if (compact) {
            // Phone: one full-width action per row, then the caveat on its own line. Nothing competes for
            // horizontal space, so no label is clipped and every target is comfortably tappable.
            Column(
                Modifier.fillMaxWidth().padding(start = indent),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fix?.invoke()
                accept?.invoke()
                note(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = indent),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fix?.invoke()
                accept?.invoke()
                note(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A pill action in the warning strip: filled for the fix, outlined for the acknowledgement. [stretch] makes it
 * a full-width row with centered text, which is what the compact layout stacks.
 */
@Composable
private fun BannerAction(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    stretch: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Ide.colors.warning else MaterialTheme.colorScheme.outline
    Row(
        (if (stretch) Modifier.fillMaxWidth() else Modifier)
            .background(
                Ide.colors.warning.copy(alpha = if (primary) 0.18f else 0.08f),
                RoundedCornerShape(Ca.radius.pill),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (stretch) Arrangement.Center else Arrangement.Start,
    ) {
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (stretch) TextAlign.Center else TextAlign.Start,
        )
    }
}
