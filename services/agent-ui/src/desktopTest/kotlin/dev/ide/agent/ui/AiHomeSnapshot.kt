package dev.ide.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.ui.HomeTab
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiAgentRecentChat
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.ext.HomeTabContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.HomeAiTab
import dev.ide.ui.screens.HomeScreen
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/** Off-screen PNGs of the AI home tab (landing and a new-app chat) inside the real home screen. Not an assertion. */
class AiHomeSnapshot {

    @AfterTest
    fun reset() {
        AgentHomeState.inChat = false
        AgentHomeState.target = null
        AgentHomeState.drawerOpen = false
        AgentHomeState.pickerOpen = false
    }

    @Test
    fun renderProjectTargetLight() {
        AgentHomeState.target = "/p/habits"
        snapshot("ai-home-project-light.png", dark = false) { Home(backend()) }
    }

    @Test
    fun renderProjectPickerDark() {
        AgentHomeState.target = "/p/quiz"
        AgentHomeState.pickerOpen = true
        snapshot("ai-home-picker-dark.png", dark = true) { Home(backend()) }
    }

    @Test
    fun renderDrawerDark() {
        AgentHomeState.drawerOpen = true
        snapshot("ai-home-drawer-dark.png", dark = true) { Home(backend()) }
    }

    private val now = System.currentTimeMillis()

    private fun backend(chat: UiAgentChatState = UiAgentChatState(), epoch: Int = 0) = object : StubBackend() {
        override val projectEpoch: StateFlow<Int> = MutableStateFlow(epoch)
        override fun projects() = listOf(
            ProjectInfo("Habit Tracker", "/p/habits", 1, isAndroid = true, lastOpened = now - 3_600_000),
            ProjectInfo("Space Quiz", "/p/quiz", 1, isAndroid = true, lastOpened = now - 86_400_000),
            ProjectInfo("JavaProbe", "/p/probe", 1, lastOpened = now - 13 * 3_600_000),
            ProjectInfo("My App", "/p/app1", 1, isAndroid = true, lastOpened = now - 14 * 3_600_000),
            ProjectInfo("My App", "/p/app2", 1, isAndroid = true, lastOpened = now - 3 * 86_400_000),
            ProjectInfo("CppHello", "/p/cpp", 1, lastOpened = now - 5 * 86_400_000),
            ProjectInfo("ExecProbe", "/p/exec", 1, lastOpened = now - 9 * 86_400_000),
        )
        override val agent: AgentService = object : AgentService {
            override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(chat)
            override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
            override val models: StateFlow<List<UiAgentModel>> = MutableStateFlow(listOf(UiAgentModel("claude-haiku-5-5", "Claude Haiku 5.5")))
            override fun recentChats() = listOf(
                UiAgentRecentChat("/p/habits", "s1", "Add a weekly summary screen", now - 25 * 60_000, 6),
                UiAgentRecentChat("/p/quiz", "s2", "Why does the score reset on rotation?", now - 2 * 86_400_000, 4),
            )
            override fun config() = UiAgentConfig(
                providers = listOf(UiAgentProvider("anthropic", "Anthropic", listOf(UiAgentModel("claude-haiku-5-5", "Claude Haiku 5.5")), "claude-haiku-5-5", "k")),
                selectedProvider = "anthropic", model = "claude-haiku-5-5", configured = true, mode = UiAgentPermissionMode.ASK_EACH,
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
    private fun Home(backend: IdeBackend) {
        val ctx = object : HomeTabContext {
            override val backend = backend
            override val runProject: (() -> Unit) = {}
        }
        HomeScreen(
            tab = HomeTab.Ai,
            onSelectTab = {},
            projectsContent = {},
            storeContent = {},
            learnContent = {},
            challengesContent = {},
            ai = HomeAiTab("AI", CaIcons.sparkle) { AgentHomeTab(ctx) },
        )
    }

    @Test
    fun renderLandingLight() = snapshot("ai-home-light.png", dark = false) { Home(backend()) }

    @Test
    fun renderLandingDark() = snapshot("ai-home-dark.png", dark = true) { Home(backend()) }

    @Test
    fun renderNewAppChatDark() {
        AgentHomeState.inChat = true
        val chat = UiAgentChatState(
            messages = listOf(
                UiAgentMessage(1, UiAgentRole.USER, "A tip calculator that splits the bill"),
                UiAgentMessage(
                    2, UiAgentRole.ASSISTANT, "Creating a Compose app for it, then the calculator screen.",
                    toolCalls = listOf(
                        UiAgentToolCall("t1", "list_project_templates", UiAgentToolStatus.OK),
                        UiAgentToolCall("t2", "create project Tip Calculator", UiAgentToolStatus.RUNNING),
                    ),
                    streaming = true,
                ),
            ),
            busy = true,
            creatingApp = true,
        )
        snapshot("ai-home-chat-dark.png", dark = true) { Home(backend(chat)) }
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = dark) { content() }
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
