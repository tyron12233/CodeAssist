package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAppRenamePreview
import dev.ide.ui.backend.UiAppRenameRequest
import dev.ide.ui.backend.UiAppRenameResult
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.modcfg_rename_app
import dev.ide.ui.generated.resources.modcfg_rename_app_apply
import dev.ide.ui.generated.resources.modcfg_rename_app_change_id
import dev.ide.ui.generated.resources.modcfg_rename_app_change_id_note
import dev.ide.ui.generated.resources.modcfg_rename_app_name
import dev.ide.ui.generated.resources.modcfg_rename_app_nothing
import dev.ide.ui.generated.resources.modcfg_rename_app_package
import dev.ide.ui.generated.resources.modcfg_rename_app_review
import dev.ide.ui.generated.resources.modcfg_rename_app_summary
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Renames [moduleName]'s package and/or app name across the project, in two steps: the fields, then a review
 * of every file it changes and what it means for the app's id. Nothing is written before Rename is pressed.
 * [onDone] gets the result of a finished rename (success or not); [onDismiss] means nothing was changed.
 */
@Composable
internal fun AppRenameDialog(
    backend: IdeBackend,
    moduleName: String,
    codeFont: FontFamily,
    onDismiss: () -> Unit,
    onDone: (UiAppRenameResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val identity by produceState<Result<dev.ide.ui.backend.UiAppIdentity?>?>(null, backend, moduleName) {
        value = runCatching { backend.modules.appIdentity(moduleName) }
    }
    var appName by remember { mutableStateOf<String?>(null) }
    var packageName by remember { mutableStateOf<String?>(null) }
    var changeId by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<UiAppRenamePreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val current = identity?.getOrNull()
    val name = appName ?: current?.appName.orEmpty()
    val pkg = packageName ?: current?.packageName.orEmpty()
    val request = UiAppRenameRequest(
        module = moduleName,
        newPackage = pkg.trim().takeIf { current?.packageName != null && it != current.packageName },
        newAppName = name.trim().takeIf { current?.appName != null && it != current.appName },
        changeApplicationId = changeId,
    )
    val changed = request.newPackage != null || request.newAppName != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.modcfg_rename_app)) },
        text = {
            val review = preview
            when {
                identity == null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                current == null || (current.packageName == null && current.appName == null) ->
                    Text(stringResource(Res.string.modcfg_rename_app_nothing))
                review != null -> ReviewBody(review, codeFont)
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (current.appName != null) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { appName = it; error = null },
                            label = { Text(stringResource(Res.string.modcfg_rename_app_name)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (current.packageName != null) {
                        OutlinedTextField(
                            value = pkg,
                            onValueChange = { packageName = it; error = null },
                            label = { Text(stringResource(Res.string.modcfg_rename_app_package)) },
                            singleLine = true,
                            enabled = !busy,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = codeFont),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val appId = current.applicationId
                    if (appId != null && current.packageName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.modcfg_rename_app_change_id), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(Res.string.modcfg_rename_app_change_id_note, appId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = changeId, onCheckedChange = { changeId = it }, enabled = !busy)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            val review = preview
            if (review != null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching { backend.modules.applyAppRename(request) }
                                .getOrElse { UiAppRenameResult(false, it.message ?: "Couldn't rename.") }
                            busy = false
                            onDone(result)
                        }
                    },
                ) { Text(stringResource(Res.string.modcfg_rename_app_apply)) }
            } else if (current != null && (current.packageName != null || current.appName != null)) {
                TextButton(
                    enabled = changed && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching { backend.modules.previewAppRename(request) }
                                .getOrElse { UiAppRenamePreview(error = it.message ?: "Couldn't check the rename.") }
                            busy = false
                            if (result.error != null) error = result.error else preview = result
                        }
                    },
                ) { Text(stringResource(Res.string.modcfg_rename_app_review)) }
            }
        },
        dismissButton = {
            if (preview != null) {
                TextButton(enabled = !busy, onClick = { preview = null }) { Text(stringResource(Res.string.back)) }
            } else {
                TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

/** The review step: how many files change, what the user should know first, and the files themselves. */
@Composable
private fun ReviewBody(preview: UiAppRenamePreview, codeFont: FontFamily) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(Res.string.modcfg_rename_app_summary, preview.files.size, preview.moved),
            style = MaterialTheme.typography.bodyMedium,
        )
        preview.notes.forEach { note ->
            Text("• $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(preview.files) { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
