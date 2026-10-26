package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.PendingClose
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.save
import dev.ide.ui.generated.resources.unsaved_close_discard
import dev.ide.ui.generated.resources.unsaved_close_dont_ask
import dev.ide.ui.generated.resources.unsaved_close_dont_ask_hint
import dev.ide.ui.generated.resources.unsaved_close_message_many
import dev.ide.ui.generated.resources.unsaved_close_message_one
import dev.ide.ui.generated.resources.unsaved_close_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * "Save changes before closing?" — raised when the user closes tabs that hold unsaved edits. Observes
 * [IdeUiState.unsavedClose] and answers through [IdeUiState.resolveUnsavedClose] (Save / Don't Save) or
 * [IdeUiState.dismissUnsavedClose] (Cancel, the scrim, Escape). "Don't ask again" makes the chosen answer the
 * Editor setting for every later close. Hosted app-wide next to `RunConflictDialog`.
 *
 * On a keyboard, Enter saves and Escape cancels. The dialog takes focus while it is up so typing can't reach the
 * editor behind it, and hands focus back to the editor when it goes.
 */
@Composable
fun UnsavedCloseDialog(state: IdeUiState) {
    val pending: PendingClose? = state.unsavedClose
    // Retain the last request so the exit animation doesn't flash empty as it clears.
    var shown by remember { mutableStateOf<PendingClose?>(null) }
    if (pending != null) shown = pending
    // Reset the checkbox each time the dialog (re)opens.
    var dontAsk by remember(pending != null) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(pending != null) { if (pending != null) runCatching { focus.requestFocus() } }

    fun finish(answer: () -> Unit) {
        answer()
        if (!isMobilePlatform) state.editorFocusEpoch++ // a touch host would pop the keyboard back up
    }
    val save = { finish { state.resolveUnsavedClose(save = true, remember = dontAsk) } }
    val discard = { finish { state.resolveUnsavedClose(save = false, remember = dontAsk) } }
    val cancel = { finish { state.dismissUnsavedClose() } }

    CenteredDialog(visible = pending != null, onDismiss = cancel) {
        val files = shown?.files ?: return@CenteredDialog
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown || pending == null) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> { cancel(); true }
                        Key.Enter, Key.NumPadEnter -> { save(); true }
                        else -> false
                    }
                }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.warning, contentDescription = null, Modifier.size(18.dp), tint = Ide.colors.warning)
                Text(
                    stringResource(Res.string.unsaved_close_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (files.size == 1) stringResource(Res.string.unsaved_close_message_one, files.single().name)
                else stringResource(Res.string.unsaved_close_message_many),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (files.size > 1) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { f ->
                        Text(
                            f.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            DontAskRow(stringResource(Res.string.unsaved_close_dont_ask), checked = dontAsk, onToggle = { dontAsk = !dontAsk })
            if (dontAsk) {
                Text(
                    stringResource(Res.string.unsaved_close_dont_ask_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 30.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            ConflictButton(stringResource(Res.string.save), fill = MaterialTheme.colorScheme.primary, fg = MaterialTheme.colorScheme.onPrimary, onClick = save)
            Spacer(Modifier.height(8.dp))
            ConflictButton(stringResource(Res.string.unsaved_close_discard), fill = MaterialTheme.colorScheme.surfaceContainerHighest, fg = MaterialTheme.colorScheme.error, onClick = discard)
            Spacer(Modifier.height(8.dp))
            ConflictButton(stringResource(Res.string.cancel), fill = MaterialTheme.colorScheme.surfaceContainerHighest, fg = MaterialTheme.colorScheme.onSurface, onClick = cancel)
        }
    }
}
