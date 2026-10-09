package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.close_project_body
import dev.ide.ui.generated.resources.close_project_building
import dev.ide.ui.generated.resources.close_project_save_and_close
import dev.ide.ui.generated.resources.close_project_title
import dev.ide.ui.generated.resources.close_project_unsaved
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Asks before leaving the editor for the project list: the top bar's back arrow, the palette's Close project
 * and system Back all come here. Edits that are not on disk yet are saved on the way out, so the button says so.
 */
@Composable
internal fun CloseProjectDialog(
    visible: Boolean,
    projectName: String,
    unsavedFiles: Int,
    building: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    CenteredDialog(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier.widthIn(max = 400.dp).padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.close_project_title, projectName),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val body = MaterialTheme.typography.bodyMedium
            Text(stringResource(Res.string.close_project_body), color = MaterialTheme.colorScheme.onSurfaceVariant, style = body)
            if (unsavedFiles > 0) {
                Text(
                    pluralStringResource(Res.plurals.close_project_unsaved, unsavedFiles, unsavedFiles),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = body,
                )
            }
            if (building) {
                Text(stringResource(Res.string.close_project_building), color = MaterialTheme.colorScheme.onSurface, style = body)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)) {
                DialogButton(stringResource(Res.string.cancel), primary = false, onClick = onDismiss)
                DialogButton(
                    stringResource(if (unsavedFiles > 0) Res.string.close_project_save_and_close else Res.string.close),
                    primary = true,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.background(if (primary) scheme.primary else scheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.control))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (primary) scheme.onPrimary else scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
