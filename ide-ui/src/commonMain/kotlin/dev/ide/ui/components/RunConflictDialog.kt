package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.PendingRun
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.run_conflict_dont_ask
import dev.ide.ui.generated.resources.run_conflict_message
import dev.ide.ui.generated.resources.run_conflict_stop_and_run
import dev.ide.ui.generated.resources.run_conflict_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * "A build or program is already running" — the confirmation raised when the user starts a Run while one is
 * still in flight (the classic case: a runaway/infinite-loop program the engine won't let a second run
 * shadow). Observes [IdeUiState.runConflict] and routes the choice back through [IdeUiState.confirmStopAndRun]
 * / [IdeUiState.dismissRunConflict]. A "Don't ask again" checkbox persists the Stop-and-Run choice so future
 * runs skip the prompt. Hosted app-wide so it overlays whatever screen the run was started from.
 */
@Composable
fun RunConflictDialog(state: IdeUiState) {
    val pending: PendingRun? = state.runConflict
    // Retain the last request so the exit animation doesn't flash empty as it clears.
    var shown by remember { mutableStateOf<PendingRun?>(null) }
    if (pending != null) shown = pending
    // Reset the checkbox each time the dialog (re)opens.
    var dontAsk by remember(pending != null) { mutableStateOf(false) }

    CenteredDialog(visible = pending != null, onDismiss = { state.dismissRunConflict() }) {
        if (shown != null) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                    .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CaIcons.warning, contentDescription = null, Modifier.size(18.dp), tint = Ca.colors.warning)
                    Text(
                        stringResource(Res.string.run_conflict_title),
                        color = Ca.colors.textPrimary,
                        style = Ca.type.subhead,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.run_conflict_message),
                    color = Ca.colors.textSecondary,
                    style = Ca.type.footnote,
                )
                Spacer(Modifier.height(14.dp))
                DontAskRow(checked = dontAsk, onToggle = { dontAsk = !dontAsk })
                Spacer(Modifier.height(14.dp))
                ConflictButton(stringResource(Res.string.run_conflict_stop_and_run), fill = Ca.colors.accent, fg = Ca.colors.textOnAccent) {
                    state.confirmStopAndRun(dontAsk)
                }
                Spacer(Modifier.height(8.dp))
                ConflictButton(stringResource(Res.string.cancel), fill = Ca.colors.surface3, fg = Ca.colors.textPrimary) {
                    state.dismissRunConflict()
                }
            }
        }
    }
}

@Composable
private fun DontAskRow(checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(
                    if (checked) Ca.colors.accent else Color.Transparent,
                    RoundedCornerShape(Ca.radius.xs),
                )
                .border(
                    1.dp,
                    if (checked) Ca.colors.accent else Ca.colors.glassEdge,
                    RoundedCornerShape(Ca.radius.xs),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(CaIcons.check, contentDescription = null, Modifier.size(14.dp), tint = Ca.colors.textOnAccent)
        }
        Text(
            stringResource(Res.string.run_conflict_dont_ask),
            color = Ca.colors.textSecondary,
            style = Ca.type.footnote,
        )
    }
}

@Composable
private fun ConflictButton(label: String, fill: Color, fg: Color, onClick: () -> Unit) {
    Text(
        label,
        color = fg,
        style = Ca.type.footnote,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(Ca.radius.control))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
