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
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiMcpHost
import dev.ide.ui.backend.UiMcpRelay
import dev.ide.ui.backend.UiMcpRelayStatus
import dev.ide.ui.backend.StoreService
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.ScreenRegistry
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen PNGs of the "Use with Claude" screen (signed in with the relay connected, and signed out) and of the
 * chat's empty state with its "Connect claude.ai" line, at a phone's width. Judged by eye; the assertion only
 * catches a blank frame.
 */
class UseWithClaudeSnapshot {

    private val relayUrl = "https://codeassist-mcp-relay.example.workers.dev/c/HQaW0Nj6zbeHFwNMRM5wI_pcay8xr-eLlCmPs5sxUtA/mcp"

    private fun backend(
        host: UiMcpHost = UiMcpHost(supported = true, port = 8765),
        relay: UiMcpRelay = UiMcpRelay(supported = true),
    ) = object : StubBackend() {
        override val store: StoreService = object : StoreService {
            override fun authProviders(): List<String> = listOf("github", "google")
        }
        override val agent: AgentService = object : AgentService {
            override val mcpHost: StateFlow<UiMcpHost> = MutableStateFlow(host)
            override val mcpRelay: StateFlow<UiMcpRelay> = MutableStateFlow(relay)
            override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(UiAgentChatState())
            override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
            override val models: StateFlow<List<UiAgentModel>> = MutableStateFlow(emptyList())
            override fun config() = UiAgentConfig(
                providers = listOf(UiAgentProvider("anthropic", "Anthropic (Claude)", emptyList(), "claude-opus-5", "sk-live")),
                selectedProvider = "anthropic", model = "claude-opus-5", configured = true, mode = UiAgentPermissionMode.ASK_EACH,
            )
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

    private val connected = backend(
        host = UiMcpHost(supported = true, running = true, port = 8765, url = "http://127.0.0.1:8765/mcp"),
        relay = UiMcpRelay(supported = true, signedIn = true, enabled = true, status = UiMcpRelayStatus.CONNECTED, url = relayUrl),
    )

    @Test
    fun screenConnectedDark() = snapshot("claude-screen-connected-dark.png", dark = true) {
        UseWithClaudeScreen(connected, onBack = {})
    }

    @Test
    fun screenConnectedLight() = snapshot("claude-screen-connected-light.png", dark = false) {
        UseWithClaudeScreen(connected, onBack = {})
    }

    @Test
    fun screenConnectedFullDark() = snapshot("claude-screen-connected-full-dark.png", dark = true, height = 3000) {
        UseWithClaudeScreen(connected, onBack = {})
    }

    @Test
    fun screenSignedOutLight() = snapshot("claude-screen-signed-out-light.png", dark = false) {
        UseWithClaudeScreen(backend(), onBack = {})
    }

    @Test
    fun chatEmptyWithClaudeLineDark() {
        // The line leads to a screen of the agent plugin's, so it shows only while that screen is registered.
        val screen = ScreenRegistry.register(ScreenContribution(AgentService.SCREEN_USE_WITH_CLAUDE, "Use with Claude") {})
        try {
            snapshot("claude-chat-empty-dark.png", dark = true) { ChatDrawer(backend(), onClose = {}) }
        } finally {
            screen.dispose()
        }
    }

    private fun snapshot(name: String, dark: Boolean, height: Int = 1780, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 822, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
        try {
            var img = scene.render()
            for (frame in 1..60) img = scene.render(frame * 16_666_667L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = System.getenv("SNAPSHOT_DIR") ?: "build/snapshots"
            File(out).mkdirs()
            File("$out/$name").writeBytes(png)
            assertTrue(png.size > 10_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }
}
