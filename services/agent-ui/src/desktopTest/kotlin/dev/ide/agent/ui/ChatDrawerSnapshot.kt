package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen PNGs of the chat drawer in both themes, so the Material 3 / Material You restyle can be
 * eyeballed without a device: tonal containers, the content-colour pairing on the user bubble, the error
 * container, and the filled send button. Not an assertion — it writes files for a human to look at.
 */
class ChatDrawerSnapshot {

    private fun transcript(): List<UiAgentMessage> = listOf(
        UiAgentMessage(1, UiAgentRole.USER, text = "Why is the preview blank for JetsnackTheme?"),
        UiAgentMessage(
            2, UiAgentRole.ASSISTANT,
            thinking = "Checking how the theme's CompositionLocals are declared.",
            toolCalls = listOf(
                UiAgentToolCall("a", "read Theme.kt", UiAgentToolStatus.OK, "48 lines"),
                UiAgentToolCall("b", "diagnostics Theme.kt", UiAgentToolStatus.OK, "No diagnostics."),
                UiAgentToolCall("c", "run_program :app", UiAgentToolStatus.RUNNING, "compiling"),
            ),
            text = "`staticCompositionLocalOf` in a top-level `val` gets a **fresh identity** per preview " +
                "process, so the theme reads its default rather than the value you provide.\n\n" +
                "- move the local into the theme object\n- or provide it at the preview root",
            usage = UiAgentUsage(input = 1240, output = 380, cacheRead = 18400),
        ),
        UiAgentMessage(3, UiAgentRole.USER, text = "Apply the first fix."),
        UiAgentMessage(
            4, UiAgentRole.ASSISTANT,
            text = "Rate limit reached. Try again in 12s.", isError = true, canRetry = true,
        ),
    )

    private fun backend(): StubBackend = object : StubBackend() {
        override val agent: AgentService = object : AgentService {
            override val chatState: StateFlow<UiAgentChatState> =
                MutableStateFlow(UiAgentChatState(messages = transcript(), busy = false))
            override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
            override val models: StateFlow<List<UiAgentModel>> =
                MutableStateFlow(listOf(UiAgentModel("claude-opus-5", "Claude Opus 5")))

            override fun config(): UiAgentConfig = UiAgentConfig(
                providers = listOf(
                    UiAgentProvider(
                        "anthropic", "Anthropic (Claude)",
                        listOf(UiAgentModel("claude-opus-5", "Claude Opus 5")), "claude-opus-5", "sk-live",
                    ),
                ),
                selectedProvider = "anthropic",
                model = "claude-opus-5",
                configured = true,
                mode = UiAgentPermissionMode.AUTO_ACCEPT,
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

    @Composable
    private fun Chat() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            ChatDrawer(backend(), onClose = {})
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderChatDark() {
        snapshot("chat-m3-dark.png", dark = true)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderChatLight() {
        snapshot("chat-m3-light.png", dark = false)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, dark: Boolean) {
        // A real phone viewport (411 x 890 dp), so what falls below the fold is what a user would see.
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = dark) { Chat() }
        }
        try {
            var img = scene.render()
            for (frame in 1..80) img = scene.render(frame * 16_666_667L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(OUT_DIR).mkdirs()
            File("$OUT_DIR/$name").writeBytes(png)
            println("wrote $OUT_DIR/$name")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = System.getenv("SNAPSHOT_DIR") ?: "build/snapshots"
    }
}
