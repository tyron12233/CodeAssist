package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AI agent against [MockAiServer]: a streamed chat reply through the custom-gateway provider, and an MCP
 * server added through the UI that connects and lists its tool.
 */
@RunWith(AndroidJUnit4::class)
class AgentTest : E2eTest() {
    private val server = MockAiServer()

    override fun seed() = DEFAULT_SEED + mapOf(
        "settings.ai.provider" to "gateway",
        "settings.ai.gatewayBaseUrl" to server.baseUrl,
        "settings.ai.gatewayModel" to MockAiServer.MODEL,
        "settings.ai.gatewayKey" to "e2e-key",
    )

    @After
    fun stopServer() = server.close()

    /** A project's editor → bottom-bar AI: the chat panel (Home's "AI" tab is the new-app builder instead). */
    private fun openChat() {
        createProject("Kotlin Console App", uniqueName("Ai"))
        // A tap while the freshly created project is still settling can be lost; tap again until the panel is up.
        poll(60_000, 10_000) {
            findNow("Send") ?: run { clickIfPresent("AI"); null }
        } ?: failWithScreen("the AI panel never opened")
    }

    /** The chat input: the panel's bottom-most text field (its placeholder isn't exposed to accessibility). */
    private fun chatInput() = poll(DEFAULT_TIMEOUT) {
        device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.EditText")).maxByOrNull { it.visibleBounds.bottom }
    } ?: failWithScreen("no chat input")

    @Test
    fun chatStreamsAReplyFromTheGateway() {
        openChat()
        chatInput().let { it.click(); it.text = "Say pong" }
        click("Send")
        awaitTextContains(MockAiServer.REPLY, 60_000)
        assertTrue("the app never called the gateway", server.chatRequests.get() > 0)
    }

    @Test
    fun mcpServerConnectsAndListsItsTool() {
        openChat()
        // The chat header's "More" (the editor's bottom bar has one too, lower down).
        clickObject(device.findObjects(androidx.test.uiautomator.By.desc("More")).minByOrNull { it.visibleBounds.top }
            ?: failWithScreen("no More menu"))
        click("Connections")
        click("MCP servers")
        click("Add server")
        await("Add MCP server")
        fill("Name", "e2e")
        click("Remote (HTTP)")
        // The URL and headers fields expose no label (their placeholders aren't in the accessibility tree):
        // top to bottom the form's fields are Name, Server URL, Headers.
        val fields = device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.EditText")).sortedBy { it.visibleBounds.top }
        if (fields.size < 2) failWithScreen("the MCP form has no URL field")
        fields[1].let { it.click(); it.text = "${server.baseUrl}/mcp" }
        hideKeyboard()
        click("Save")
        await(Regex("^Connected · 1 tools?$"), 60_000)
        assertTrue("the app never reached the MCP server", server.mcpRequests.get() > 0)
    }
}
