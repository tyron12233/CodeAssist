package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_cancel
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_retry
import dev.ide.agent.ui.generated.resources.conn_add_server
import dev.ide.agent.ui.generated.resources.conn_add_title
import dev.ide.agent.ui.generated.resources.conn_approve
import dev.ide.agent.ui.generated.resources.conn_create
import dev.ide.agent.ui.generated.resources.conn_edit
import dev.ide.agent.ui.generated.resources.conn_edit_title
import dev.ide.agent.ui.generated.resources.conn_field_args
import dev.ide.agent.ui.generated.resources.conn_field_command
import dev.ide.agent.ui.generated.resources.conn_field_env
import dev.ide.agent.ui.generated.resources.conn_field_headers
import dev.ide.agent.ui.generated.resources.conn_field_name
import dev.ide.agent.ui.generated.resources.conn_field_type
import dev.ide.agent.ui.generated.resources.conn_field_url
import dev.ide.agent.ui.generated.resources.conn_field_where
import dev.ide.agent.ui.generated.resources.conn_headers_hint
import dev.ide.agent.ui.generated.resources.conn_hide_tools
import dev.ide.agent.ui.generated.resources.conn_intro
import dev.ide.agent.ui.generated.resources.conn_new_skill
import dev.ide.agent.ui.generated.resources.conn_remove
import dev.ide.agent.ui.generated.resources.conn_save
import dev.ide.agent.ui.generated.resources.conn_scope_app
import dev.ide.agent.ui.generated.resources.conn_scope_project
import dev.ide.agent.ui.generated.resources.conn_servers
import dev.ide.agent.ui.generated.resources.conn_servers_empty
import dev.ide.agent.ui.generated.resources.conn_show_tools
import dev.ide.agent.ui.generated.resources.conn_sign_in
import dev.ide.agent.ui.generated.resources.conn_sign_out
import dev.ide.agent.ui.generated.resources.conn_skill_create_failed
import dev.ide.agent.ui.generated.resources.conn_skill_name
import dev.ide.agent.ui.generated.resources.conn_skills
import dev.ide.agent.ui.generated.resources.conn_skills_empty
import dev.ide.agent.ui.generated.resources.conn_skills_folders
import dev.ide.agent.ui.generated.resources.conn_status_connected
import dev.ide.agent.ui.generated.resources.conn_status_connecting
import dev.ide.agent.ui.generated.resources.conn_status_failed
import dev.ide.agent.ui.generated.resources.conn_status_needs_auth
import dev.ide.agent.ui.generated.resources.conn_status_off
import dev.ide.agent.ui.generated.resources.conn_status_shadowed
import dev.ide.agent.ui.generated.resources.conn_status_unsupported
import dev.ide.agent.ui.generated.resources.conn_status_untrusted
import dev.ide.agent.ui.generated.resources.conn_title
import dev.ide.agent.ui.generated.resources.conn_tools
import dev.ide.agent.ui.generated.resources.conn_type_http
import dev.ide.agent.ui.generated.resources.conn_type_sse
import dev.ide.agent.ui.generated.resources.conn_type_stdio
import dev.ide.agent.ui.generated.resources.conn_waiting
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentConnections
import dev.ide.ui.backend.UiAgentSkill
import dev.ide.ui.backend.UiMcpScope
import dev.ide.ui.backend.UiMcpServer
import dev.ide.ui.backend.UiMcpServerConfig
import dev.ide.ui.backend.UiMcpStatus
import dev.ide.ui.backend.UiMcpTransport
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The agent's connections, as a full-screen page like the provider manager: the MCP servers it can call (app-wide
 * and this project's), and the skills it can load. Everything saves as it is changed. Reached from the chat's menu
 * and the `/mcp` and `/skills` commands.
 */
@Composable
internal fun AgentConnectionsSheet(backend: IdeBackend, onOpenFile: ((String) -> Unit)?, onClose: () -> Unit) {
    val state by backend.agent.connections.collectAsState()
    var editing by remember { mutableStateOf<Pair<UiMcpServer?, Boolean>?>(null) }
    var newSkill by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { backend.agent.refreshConnections() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ExpressiveScaffold(title = stringResource(Res.string.conn_title), onBack = onClose) { padding ->
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .widthIn(max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(Res.string.conn_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

                    SectionHeader(stringResource(Res.string.conn_servers), stringResource(Res.string.conn_add_server)) { editing = null to true }
                    if (state.servers.isEmpty()) Hint(stringResource(Res.string.conn_servers_empty))
                    state.servers.forEach { server -> ServerCard(backend, server) { editing = server to true } }

                    Spacer(Modifier.size(4.dp))
                    SectionHeader(stringResource(Res.string.conn_skills), stringResource(Res.string.conn_new_skill)) { newSkill = true }
                    if (state.skills.isEmpty()) Hint(stringResource(Res.string.conn_skills_empty))
                    state.skills.forEach { skill -> SkillRow(backend, skill, onOpenFile) }
                    val folders = listOfNotNull(state.projectSkillsDir.takeIf { state.projectOpen }, state.appSkillsDir)
                    if (folders.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.conn_skills_folders),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                        )
                        folders.forEach {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
        editing?.let { (server, _) ->
            ServerEditor(backend, state, server) { editing = null }
        }
        if (newSkill) {
            NewSkillDialog(backend, state, onOpenFile) { newSkill = false }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextAction(action, icon = true, onClick = onAction)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TextAction(label: String, icon: Boolean = false, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(CaIcons.plus, null, Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** A row's name and where it applies, taking the space left of its switch; the name gives way first. */
@Composable
private fun TitleAndScope(title: String, scope: UiMcpScope, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        ScopeChip(scope)
    }
}

@Composable
private fun ScopeChip(scope: UiMcpScope) {
    Text(
        stringResource(if (scope == UiMcpScope.PROJECT) Res.string.conn_scope_project else Res.string.conn_scope_app),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ServerCard(backend: IdeBackend, server: UiMcpServer, onEdit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val coroutines = rememberCoroutineScope()
    var showTools by remember { mutableStateOf(false) }
    val name = server.config.name
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor(server.status)))
            TitleAndScope(name, server.scope, Modifier.weight(1f))
            if (!server.shadowed) CaSwitch(server.enabled) { backend.agent.setMcpServerEnabled(name, server.scope, it) }
        }
        Text(
            target(server.config), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val status = when {
            server.shadowed -> stringResource(Res.string.conn_status_shadowed)
            else -> when (server.status) {
                UiMcpStatus.CONNECTED -> stringResource(
                    Res.string.conn_status_connected,
                    pluralStringResource(Res.plurals.conn_tools, server.tools.size, server.tools.size),
                )
                UiMcpStatus.CONNECTING -> stringResource(Res.string.conn_status_connecting)
                UiMcpStatus.NEEDS_AUTH -> stringResource(Res.string.conn_status_needs_auth)
                UiMcpStatus.FAILED -> stringResource(Res.string.conn_status_failed)
                UiMcpStatus.DISABLED -> stringResource(Res.string.conn_status_off)
                UiMcpStatus.UNSUPPORTED -> stringResource(Res.string.conn_status_unsupported)
                UiMcpStatus.UNTRUSTED -> stringResource(Res.string.conn_status_untrusted)
            }
        }
        Text(status, color = if (server.status == UiMcpStatus.FAILED) scheme.error else scheme.onSurface, style = MaterialTheme.typography.bodySmall)
        server.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        if (showTools && server.tools.isNotEmpty()) {
            Text(
                server.tools.joinToString(", "), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                server.status == UiMcpStatus.UNTRUSTED ->
                    TextAction(stringResource(Res.string.conn_approve)) { backend.agent.trustMcpServer(name) }
                server.signInPending -> {
                    Text(
                        stringResource(Res.string.conn_waiting), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    TextAction(stringResource(Res.string.chat_cancel)) { backend.agent.cancelMcpSignIn() }
                }
                server.status == UiMcpStatus.NEEDS_AUTH && server.config.transport != UiMcpTransport.STDIO ->
                    TextAction(stringResource(Res.string.conn_sign_in)) {
                        coroutines.launch {
                            backend.agent.beginMcpSignIn(name)?.let { url ->
                                runCatching { uriHandler.openUri(url) }.onFailure { backend.agent.cancelMcpSignIn() }
                            }
                        }
                    }
                server.status == UiMcpStatus.FAILED ->
                    TextAction(stringResource(Res.string.chat_retry)) { backend.agent.reconnectMcpServer(name) }
            }
            if (server.status == UiMcpStatus.CONNECTED && server.tools.isNotEmpty()) {
                TextAction(stringResource(if (showTools) Res.string.conn_hide_tools else Res.string.conn_show_tools)) { showTools = !showTools }
            }
            if (server.signedIn && !server.signInPending) {
                TextAction(stringResource(Res.string.conn_sign_out)) { backend.agent.signOutMcpServer(name) }
            }
            TextAction(stringResource(Res.string.conn_edit), onClick = onEdit)
            TextAction(stringResource(Res.string.conn_remove), color = scheme.error) { backend.agent.removeMcpServer(name, server.scope) }
        }
    }
}

@Composable
private fun statusColor(status: UiMcpStatus): Color = when (status) {
    UiMcpStatus.CONNECTED -> Color(0xFF34A853)
    UiMcpStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
    UiMcpStatus.NEEDS_AUTH, UiMcpStatus.UNTRUSTED -> Color(0xFFF9AB00)
    UiMcpStatus.FAILED -> MaterialTheme.colorScheme.error
    UiMcpStatus.DISABLED, UiMcpStatus.UNSUPPORTED -> MaterialTheme.colorScheme.outline
}

private fun target(config: UiMcpServerConfig): String = when (config.transport) {
    UiMcpTransport.STDIO -> (listOf(config.command) + config.args).joinToString(" ")
    else -> config.url
}

@Composable
private fun SkillRow(backend: IdeBackend, skill: UiAgentSkill, onOpenFile: ((String) -> Unit)?) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TitleAndScope(
                skill.name, skill.scope,
                Modifier.weight(1f)
                    .then(if (onOpenFile != null) Modifier.clip(RoundedCornerShape(Ca.radius.sm)).clickable { onOpenFile(skill.path) } else Modifier),
            )
            CaSwitch(skill.enabled) { backend.agent.setSkillEnabled(skill.name, it) }
        }
        Text(skill.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Add or change a server. [server] is null to add one. */
@Composable
internal fun ServerEditor(backend: IdeBackend, state: UiAgentConnections, server: UiMcpServer?, onDone: () -> Unit) {
    val original = server?.config
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var scope by remember { mutableStateOf(server?.scope ?: if (state.projectOpen) UiMcpScope.PROJECT else UiMcpScope.APP) }
    var transport by remember { mutableStateOf(original?.transport ?: UiMcpTransport.HTTP) }
    var url by remember { mutableStateOf(original?.url.orEmpty()) }
    var headers by remember { mutableStateOf(original?.headers.orEmpty().entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var command by remember { mutableStateOf(original?.command.orEmpty()) }
    var args by remember { mutableStateOf(original?.args.orEmpty().joinToString("\n")) }
    var env by remember { mutableStateOf(original?.env.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var error by remember { mutableStateOf<String?>(null) }

    val save = {
        val config = UiMcpServerConfig(
            name = name.trim(),
            transport = transport,
            url = url.trim(),
            headers = pairs(headers, ':'),
            command = command.trim(),
            args = args.lines().map { it.trim() }.filter { it.isNotEmpty() },
            env = pairs(env, '='),
        )
        if (server != null && server.scope != scope) {
            // Moving a server between app and project: add it there, then drop it here.
            error = backend.agent.saveMcpServer(config, scope, null)
            if (error == null) backend.agent.removeMcpServer(server.config.name, server.scope)
        } else {
            error = backend.agent.saveMcpServer(config, scope, original?.name)
        }
        if (error == null) onDone()
    }

    CenteredDialog(visible = true, onDismiss = onDone) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Ca.radius.xl)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (server == null) Res.string.conn_add_title else Res.string.conn_edit_title),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onDone, iconSize = 16, boxSize = 30)
            }
            Label(stringResource(Res.string.conn_field_name))
            Field(name, "") { name = it }

            Label(stringResource(Res.string.conn_field_where))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(Res.string.conn_scope_app), scope == UiMcpScope.APP, { scope = UiMcpScope.APP })
                if (state.projectOpen) {
                    PillChip(stringResource(Res.string.conn_scope_project), scope == UiMcpScope.PROJECT, { scope = UiMcpScope.PROJECT })
                }
            }

            Label(stringResource(Res.string.conn_field_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(Res.string.conn_type_http), transport == UiMcpTransport.HTTP, { transport = UiMcpTransport.HTTP })
                PillChip(stringResource(Res.string.conn_type_sse), transport == UiMcpTransport.SSE, { transport = UiMcpTransport.SSE })
                if (state.stdioSupported || transport == UiMcpTransport.STDIO) {
                    PillChip(stringResource(Res.string.conn_type_stdio), transport == UiMcpTransport.STDIO, { transport = UiMcpTransport.STDIO })
                }
            }

            if (transport == UiMcpTransport.STDIO) {
                Field(command, stringResource(Res.string.conn_field_command)) { command = it }
                Field(args, stringResource(Res.string.conn_field_args), singleLine = false) { args = it }
                Field(env, stringResource(Res.string.conn_field_env), singleLine = false) { env = it }
            } else {
                Field(url, stringResource(Res.string.conn_field_url)) { url = it }
                Field(headers, stringResource(Res.string.conn_field_headers), singleLine = false) { headers = it }
                Hint(stringResource(Res.string.conn_headers_hint))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextAction(stringResource(Res.string.chat_cancel), onClick = onDone)
                PrimaryButton(stringResource(Res.string.conn_save), { save() })
            }
        }
    }
}

@Composable
private fun NewSkillDialog(backend: IdeBackend, state: UiAgentConnections, onOpenFile: ((String) -> Unit)?, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(if (state.projectOpen) UiMcpScope.PROJECT else UiMcpScope.APP) }
    var failed by remember { mutableStateOf(false) }
    CenteredDialog(visible = true, onDismiss = onDone) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Ca.radius.xl)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(Res.string.conn_new_skill), color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Field(name, stringResource(Res.string.conn_skill_name)) { name = it; failed = false }
            Label(stringResource(Res.string.conn_field_where))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(Res.string.conn_scope_app), scope == UiMcpScope.APP, { scope = UiMcpScope.APP })
                if (state.projectOpen) {
                    PillChip(stringResource(Res.string.conn_scope_project), scope == UiMcpScope.PROJECT, { scope = UiMcpScope.PROJECT })
                }
            }
            if (failed) Text(stringResource(Res.string.conn_skill_create_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextAction(stringResource(Res.string.chat_cancel), onClick = onDone)
                PrimaryButton(stringResource(Res.string.conn_create), {
                    val path = backend.agent.createSkill(name, scope)
                    if (path == null) {
                        failed = true
                    } else {
                        onOpenFile?.invoke(path)
                        onDone()
                    }
                })
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Field(value: String, placeholder: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(Ca.radius.control)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .then(if (singleLine) Modifier else Modifier.heightIn(min = 64.dp)),
    ) {
        if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Key: value" (or "KEY=value") lines to a map; lines without the separator are skipped. */
private fun pairs(text: String, separator: Char): Map<String, String> = text.lines()
    .mapNotNull { line ->
        val i = line.indexOf(separator)
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }
    .filter { it.first.isNotEmpty() }
    .toMap()
