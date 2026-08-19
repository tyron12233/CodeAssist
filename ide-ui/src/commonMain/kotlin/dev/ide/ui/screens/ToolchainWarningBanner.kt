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
    val warningState = rememberToolchainWarningState(state)
    val shown = warningState.shown
    if (shown.isEmpty()) return

    Column(Modifier.fillMaxWidth().background(Ide.colors.warning.copy(alpha = 0.10f))) {
        // Several affected modules collapse behind one row: three full cards would fill a phone screen, and the
        // count is the part that matters at a glance.
        if (shown.size > 1) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClick = warningState::toggleList)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    if (warningState.listOpen) CaIcons.caretDown else CaIcons.caretRight,
                    if (warningState.listOpen) stringResource(Res.string.hide_details) else stringResource(Res.string.show_details),
                    Modifier.size(14.dp), tint = Ide.colors.warning,
                )
                IconButtonCa(
                    CaIcons.close, stringResource(Res.string.dismiss),
                    warningState::dismissAll, boxSize = 24, iconSize = 14,
                )
            }
        }
        AnimatedVisibility(shown.size == 1 || warningState.listOpen) {
            Column(Modifier.fillMaxWidth()) {
                for (w in shown) {
                    WarningCard(
                        warning = w,
                        compact = compact,
                        busy = warningState.busyId == w.id,
                        detailExpanded = w.id in warningState.expanded,
                        result = warningState.results[w.id],
                        indented = shown.size > 1,
                        onToggleDetail = { warningState.toggleDetail(w.id) },
                        onDismiss = { warningState.dismiss(w.id) },
                        onFix = { warningState.fix(w) },
                        onAccept = { warningState.accept(w) },
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
    Column(
        Modifier.fillMaxWidth().padding(start = if (indented) 12.dp else 0.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The icon sits ON the card's padding edge, and the explanation and actions below start there too, so
        // the card reads as one left edge. Only the title is offset, since it follows the icon in this row.
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
            modifier = Modifier.fillMaxWidth(),
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
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fix?.invoke()
                accept?.invoke()
                note(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
