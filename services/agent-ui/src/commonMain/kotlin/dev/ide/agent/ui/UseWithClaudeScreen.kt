package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.claude_copy_link
import dev.ide.agent.ui.generated.resources.claude_link_copied
import dev.ide.agent.ui.generated.resources.claude_open_connectors
import dev.ide.agent.ui.generated.resources.claude_screen_intro
import dev.ide.agent.ui.generated.resources.claude_screen_title
import dev.ide.agent.ui.generated.resources.conn_claude_desktop_note
import dev.ide.agent.ui.generated.resources.conn_claude_intro
import dev.ide.agent.ui.generated.resources.conn_claude_step_adb
import dev.ide.agent.ui.generated.resources.conn_claude_step_code
import dev.ide.agent.ui.generated.resources.conn_claude_step_desktop
import dev.ide.agent.ui.generated.resources.conn_claude_toggle
import dev.ide.agent.ui.generated.resources.conn_claude_warning
import dev.ide.agent.ui.generated.resources.conn_relay_intro
import dev.ide.agent.ui.generated.resources.conn_relay_new_link
import dev.ide.agent.ui.generated.resources.conn_relay_sign_in
import dev.ide.agent.ui.generated.resources.conn_relay_status_connected
import dev.ide.agent.ui.generated.resources.conn_relay_status_connecting
import dev.ide.agent.ui.generated.resources.conn_relay_status_in_use
import dev.ide.agent.ui.generated.resources.conn_relay_status_offline
import dev.ide.agent.ui.generated.resources.conn_relay_status_replaced
import dev.ide.agent.ui.generated.resources.conn_relay_status_signed_out
import dev.ide.agent.ui.generated.resources.conn_relay_step
import dev.ide.agent.ui.generated.resources.conn_relay_toggle
import dev.ide.agent.ui.generated.resources.conn_relay_warning
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiMcpRelayStatus
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * CodeAssist as a server for Claude: the hosted relay that claude.ai and the Claude app reach, and the local MCP
 * server that Claude Code and Claude Desktop reach. A screen of its own, opened from the chat's menu, `/claude`,
 * the editor's menu and status chip, Settings and Home, because the direction is the reverse of the chat's
 * Connections (tools for this app's agent): here another AI works on the project.
 */
@Composable
internal fun UseWithClaudeScreen(backend: IdeBackend, onBack: () -> Unit) {
    // The relay reports whether an account is signed in when asked. Ask on opening, and again when a sign-in
    // started here comes back through the store's deep link.
    val auth by remember(backend) { backend.store.authState() }.collectAsState()
    LaunchedEffect(auth) { backend.agent.refreshConnections() }
    ExpressiveScaffold(title = stringResource(Res.string.claude_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.claude_screen_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
            ClaudeRelaySection(backend)
            ClaudeLinkSection(backend)
            Spacer(Modifier.size(24.dp))
        }
    }
}

/**
 * The hosted relay: the same tools at a public address, which is what Claude on the web and on a phone need,
 * because their connectors call from Anthropic's servers. The address is a secret and is shown only while on.
 */
@Composable
private fun ClaudeRelaySection(backend: IdeBackend) {
    val relay by backend.agent.mcpRelay.collectAsState()
    if (!relay.supported) return
    val uriHandler = LocalUriHandler.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.conn_relay_toggle),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Turning it on needs an account; turning it off never does.
            CaSwitch(relay.enabled, { if (relay.signedIn || !it) backend.agent.setMcpRelayEnabled(it) })
        }
        Hint(stringResource(Res.string.conn_relay_intro))
        Text(stringResource(Res.string.conn_relay_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!relay.signedIn) {
            Hint(stringResource(Res.string.conn_relay_sign_in))
            SignInButtons(backend, pitch = false)
        }
        if (relay.enabled) {
            val (status, good) = when (relay.status) {
                UiMcpRelayStatus.CONNECTED -> Res.string.conn_relay_status_connected to true
                UiMcpRelayStatus.OFFLINE -> Res.string.conn_relay_status_offline to false
                UiMcpRelayStatus.SIGNED_OUT -> Res.string.conn_relay_status_signed_out to false
                UiMcpRelayStatus.IN_USE -> Res.string.conn_relay_status_in_use to false
                UiMcpRelayStatus.REPLACED -> Res.string.conn_relay_status_replaced to false
                UiMcpRelayStatus.CONNECTING, UiMcpRelayStatus.OFF -> Res.string.conn_relay_status_connecting to false
            }
            Text(
                stringResource(status),
                color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            if (relay.url.isNotEmpty()) {
                CommandBlock(stringResource(Res.string.conn_relay_step), relay.url)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    CopyLinkButton(relay.url)
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(ClaudeLink.CONNECTORS_URL) } },
                        modifier = Modifier.height(38.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(Res.string.claude_open_connectors), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Icon(CaIcons.arrowRight, null, Modifier.size(16.dp))
                    }
                }
            }
            TextAction(stringResource(Res.string.conn_relay_new_link)) { backend.agent.resetMcpRelayLink() }
        }
    }
}

/** "Copy link", confirming for a moment once the link is on the clipboard. */
@Composable
private fun CopyLinkButton(url: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    PrimaryButton(
        stringResource(if (copied) Res.string.claude_link_copied else Res.string.claude_copy_link),
        onClick = {
            clipboard.setText(AnnotatedString(url))
            copied = true
        },
        icon = if (copied) CaIcons.check else CaIcons.copy,
    )
}

/**
 * The other direction on this machine: turning it on starts the built-in MCP server, and the commands below
 * register it with Claude Code and Claude Desktop, which then work on the open project with the agent's tools.
 * The commands are literal, so they are shown as they are typed and never translated.
 */
@Composable
private fun ClaudeLinkSection(backend: IdeBackend) {
    val host by backend.agent.mcpHost.collectAsState()
    if (!host.supported) return
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.conn_claude_toggle),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            CaSwitch(host.running, { backend.agent.setMcpHostEnabled(it) })
        }
        Hint(stringResource(Res.string.conn_claude_intro))
        Text(stringResource(Res.string.conn_claude_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        host.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (host.running) {
            if (isMobilePlatform) {
                CommandBlock(stringResource(Res.string.conn_claude_step_adb), ClaudeLink.adbForward(host.port))
            }
            CommandBlock(stringResource(Res.string.conn_claude_step_code), ClaudeLink.claudeCodeCommand(host.url))
            CommandBlock(stringResource(Res.string.conn_claude_step_desktop), ClaudeLink.claudeDesktopConfig(host.url))
            Hint(stringResource(Res.string.conn_claude_desktop_note))
        }
    }
}

/** A labelled command, in monospace, with a copy button. */
@Composable
private fun CommandBlock(label: String, command: String) {
    Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(command, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        CopyButton(command)
    }
}

/** What a user types to connect Claude to the hosted MCP server at a given address. */
internal object ClaudeLink {
    /** The name Claude lists the server under. */
    const val SERVER_NAME = "codeassist"

    /** Where claude.ai adds a custom connector. */
    const val CONNECTORS_URL = "https://claude.ai/settings/connectors"

    /** Makes the phone's port reachable as the same port on the computer it is plugged into. */
    fun adbForward(port: Int): String = "adb forward tcp:$port tcp:$port"

    /** Claude Code: registers the server for every project of this user. */
    fun claudeCodeCommand(url: String): String = "claude mcp add --transport http --scope user $SERVER_NAME $url"

    /**
     * Claude Desktop starts local servers as commands, so the entry runs `mcp-remote`, a bridge from stdio to an
     * HTTP server. `--allow-http` is what lets it use a plain-http address, which a loopback one is.
     */
    fun claudeDesktopConfig(url: String): String = """
        {
          "mcpServers": {
            "$SERVER_NAME": {
              "command": "npx",
              "args": ["-y", "mcp-remote", "$url", "--allow-http"]
            }
          }
        }
    """.trimIndent()
}
