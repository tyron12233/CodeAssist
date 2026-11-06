package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLanguageServer
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.SettingsCard
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.lsp_add
import dev.ide.ui.generated.resources.lsp_cancel
import dev.ide.ui.generated.resources.lsp_check
import dev.ide.ui.generated.resources.lsp_delete
import dev.ide.ui.generated.resources.lsp_edit_title
import dev.ide.ui.generated.resources.lsp_empty
import dev.ide.ui.generated.resources.lsp_field_command
import dev.ide.ui.generated.resources.lsp_field_extensions
import dev.ide.ui.generated.resources.lsp_field_extensions_hint
import dev.ide.ui.generated.resources.lsp_field_language
import dev.ide.ui.generated.resources.lsp_field_name
import dev.ide.ui.generated.resources.lsp_field_options
import dev.ide.ui.generated.resources.lsp_intro
import dev.ide.ui.generated.resources.lsp_new_title
import dev.ide.ui.generated.resources.lsp_presets
import dev.ide.ui.generated.resources.lsp_save
import dev.ide.ui.generated.resources.lsp_status_not_started
import dev.ide.ui.generated.resources.lsp_status_running
import dev.ide.ui.generated.resources.lsp_status_starting
import dev.ide.ui.generated.resources.lsp_unsupported
import dev.ide.ui.generated.resources.settings_language_servers
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Language Servers: the Language Server Protocol servers the user has installed, each with the
 * file extensions it serves. Files with those extensions get the server's errors, completion, navigation,
 * rename and formatting, with no plugin. Presets fill in the common ones (gopls, rust-analyzer, pyright, ...).
 *
 * A change is saved and applied at once: a server whose command changed restarts, and a new language applies
 * to the next project opened (an open project keeps the languages it opened with).
 */
@Composable
fun LanguageServersScreen(backend: IdeBackend, onBack: () -> Unit) {
    val settings = backend.settings
    var servers by remember { mutableStateOf(settings.languageServers()) }
    var editing by remember { mutableStateOf<UiLanguageServer?>(null) }
    val presets = remember { settings.languageServerPresets() }
    val supported = remember { settings.languageServersSupported() }

    fun refresh() { servers = settings.languageServers() }

    ExpressiveScaffold(title = stringResource(Res.string.settings_language_servers), onBack = onBack) { innerPadding ->
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!supported) {
                Text(stringResource(Res.string.lsp_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(stringResource(Res.string.lsp_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (servers.isEmpty()) {
                Text(stringResource(Res.string.lsp_empty), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            }
            for (s in servers) {
                ServerRow(
                    s,
                    onEdit = { editing = s },
                    onToggle = { on ->
                        settings.saveLanguageServer(s.copy(enabled = on))
                        refresh()
                    },
                )
            }
            PrimaryButton(
                text = stringResource(Res.string.lsp_add),
                onClick = { editing = UiLanguageServer("", "", "", emptyList(), "") },
            )
            if (presets.isNotEmpty()) {
                Spacer(Modifier.padding(top = 4.dp))
                SettingsCard(stringResource(Res.string.lsp_presets)) {
                    for (p in presets) {
                        val taken = servers.any { it.command == p.command }
                        Row(
                            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    p.command + "  ·  " + p.extensions.joinToString(" "),
                                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { editing = p }, enabled = !taken) { Text(stringResource(Res.string.lsp_add)) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { s ->
        ServerDialog(
            backend = backend,
            initial = s,
            onDismiss = { editing = null },
            onSaved = { editing = null; refresh() },
            onDelete = if (s.id.isNotEmpty()) ({ settings.deleteLanguageServer(s.id); editing = null; refresh() }) else null,
        )
    }
}

@Composable
private fun ServerRow(s: UiLanguageServer, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                s.command, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.extensions.joinToString(" ") + "  ·  " + statusLabel(s.status),
                color = if (s.status.startsWith("Stopped")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CaSwitch(s.enabled, onToggle)
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "Running" -> stringResource(Res.string.lsp_status_running)
    "Starting" -> stringResource(Res.string.lsp_status_starting)
    "Not started", "" -> stringResource(Res.string.lsp_status_not_started)
    else -> status
}

@Composable
private fun ServerDialog(
    backend: IdeBackend,
    initial: UiLanguageServer,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var extensions by remember { mutableStateOf(initial.extensions.joinToString(", ")) }
    var language by remember { mutableStateOf(initial.languageId) }
    var options by remember { mutableStateOf(initial.initializationOptions) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id.isEmpty()) Res.string.lsp_new_title else Res.string.lsp_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(Res.string.lsp_field_name), name) { name = it }
                Field(stringResource(Res.string.lsp_field_command), command, mono = true) { command = it; message = null }
                Field(stringResource(Res.string.lsp_field_extensions), extensions, placeholder = stringResource(Res.string.lsp_field_extensions_hint)) { extensions = it }
                Field(stringResource(Res.string.lsp_field_language), language, mono = true) { language = it }
                Field(stringResource(Res.string.lsp_field_options), options, mono = true, singleLine = false) { options = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val check = backend.settings.checkLanguageServerCommand(command)
                        message = check.found to check.message
                    }) { Text(stringResource(Res.string.lsp_check)) }
                    if (onDelete != null) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text(stringResource(Res.string.lsp_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
                message?.let { (ok, text) ->
                    Text(
                        text, style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val error = backend.settings.saveLanguageServer(
                    initial.copy(
                        name = name, command = command, languageId = language, initializationOptions = options,
                        extensions = extensions.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() },
                    )
                )
                if (error == null) onSaved() else message = false to error
            }) { Text(stringResource(Res.string.lsp_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.lsp_cancel)) } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    mono: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
