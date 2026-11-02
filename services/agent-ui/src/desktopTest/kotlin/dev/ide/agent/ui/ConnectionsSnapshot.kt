package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentConnections
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentSkill
import dev.ide.ui.backend.UiMcpScope
import dev.ide.ui.backend.UiMcpServer
import dev.ide.ui.backend.UiMcpServerConfig
import dev.ide.ui.backend.UiMcpStatus
import dev.ide.ui.backend.UiMcpTransport
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNGs of the Connections page and the server editor, for a human to look at. Not an assertion. */
class ConnectionsSnapshot {

    private val sample = UiAgentConnections(
        mcpSupported = true,
        stdioSupported = true,
        projectOpen = true,
        servers = listOf(
            UiMcpServer(
                UiMcpServerConfig("linear", UiMcpTransport.HTTP, url = "https://mcp.linear.app/mcp"),
                UiMcpScope.APP, enabled = true, status = UiMcpStatus.CONNECTED,
                tools = listOf("list_issues", "create_issue", "update_issue", "search"), signedIn = true,
            ),
            UiMcpServer(
                UiMcpServerConfig("sentry", UiMcpTransport.HTTP, url = "https://mcp.sentry.dev/mcp"),
                UiMcpScope.APP, enabled = true, status = UiMcpStatus.NEEDS_AUTH,
            ),
            UiMcpServer(
                UiMcpServerConfig("files", UiMcpTransport.STDIO, command = "npx", args = listOf("-y", "@modelcontextprotocol/server-filesystem", ".")),
                UiMcpScope.PROJECT, enabled = true, status = UiMcpStatus.UNTRUSTED,
            ),
            UiMcpServer(
                UiMcpServerConfig("internal-api", UiMcpTransport.SSE, url = "https://tools.example.com/sse"),
                UiMcpScope.PROJECT, enabled = true, status = UiMcpStatus.FAILED,
                error = "Could not find the server (tools.example.com). Check the URL and your connection.",
            ),
            UiMcpServer(
                UiMcpServerConfig("github", UiMcpTransport.HTTP, url = "https://api.githubcopilot.com/mcp/"),
                UiMcpScope.APP, enabled = false, status = UiMcpStatus.DISABLED,
            ),
        ),
        skills = listOf(
            UiAgentSkill("release-notes", "Writes release notes from the commits since the last tag, in the project's changelog style.", UiMcpScope.PROJECT, "/p/.codeassist/skills/release-notes/SKILL.md", true),
            UiAgentSkill("compose-review", "Reviews Jetpack Compose code for recomposition and state-hoisting problems.", UiMcpScope.APP, "/a/skills/compose-review/SKILL.md", false),
        ),
        projectSkillsDir = "/storage/emulated/0/CodeAssist/MyApp/.codeassist/skills",
        appSkillsDir = "/data/user/0/com.tyron.code/files/agent/skills",
    )

    private fun backend(state: UiAgentConnections) = object : StubBackend() {
        override val agent: AgentService = object : AgentService {
            override val connections: StateFlow<UiAgentConnections> = MutableStateFlow(state)
            override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(UiAgentChatState())
            override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
            override val models: StateFlow<List<UiAgentModel>> = MutableStateFlow(emptyList())
            override fun config() = UiAgentConfig(providers = emptyList(), selectedProvider = "", model = "", configured = true, mode = UiAgentPermissionMode.ASK_EACH)
            override fun refreshModels() {}
            override fun setModel(model: String) {}
            override fun selectProvider(id: String) {}
            override fun setProviderKey(providerId: String, key: String) {}
            override fun setGateway(baseUrl: String, model: String, caCert: String) {}
            override fun send(text: String) {}
            override fun retry() {}
            override fun stop() {}
            override fun newSession() {}
            override fun setPermissionMode(mode: UiAgentPermissionMode) {}
            override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
        }
    }

    @Test
    fun renderConnectionsDark() = snapshot("connections-dark.png", dark = true) { AgentConnectionsSheet(backend(sample), onOpenFile = {}) {} }

    @Test
    fun renderConnectionsLight() = snapshot("connections-light.png", dark = false) { AgentConnectionsSheet(backend(sample), onOpenFile = {}) {} }

    @Test
    fun renderConnectionsEmptyLight() = snapshot("connections-empty-light.png", dark = false) {
        AgentConnectionsSheet(backend(UiAgentConnections(mcpSupported = true, projectOpen = true, appSkillsDir = "/a/skills")), onOpenFile = {}) {}
    }

    @Test
    fun renderEditorDark() = snapshot("connections-editor-dark.png", dark = true) {
        ServerEditor(backend(sample), sample, sample.servers[3]) {}
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
        try {
            var img = scene.render()
            for (frame in 1..60) img = scene.render(frame * 16_666_667L)
            val out = System.getenv("SNAPSHOT_DIR") ?: "build/snapshots"
            File(out).mkdirs()
            File("$out/$name").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
