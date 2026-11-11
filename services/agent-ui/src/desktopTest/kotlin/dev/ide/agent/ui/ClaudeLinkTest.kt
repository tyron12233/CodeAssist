package dev.ide.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The commands shown for connecting Claude are typed as they are, so their exact text is the contract. */
class ClaudeLinkTest {
    private val url = "http://127.0.0.1:8765/mcp"

    @Test
    fun claudeCodeCommandNamesTheServerBeforeItsUrl() {
        assertEquals("claude mcp add --transport http --scope user codeassist $url", ClaudeLink.claudeCodeCommand(url))
    }

    @Test
    fun claudeDesktopEntryBridgesStdioToThePlainHttpServer() {
        val config = ClaudeLink.claudeDesktopConfig(url)
        assertTrue(config.startsWith("{\n  \"mcpServers\": {\n    \"codeassist\": {"), config)
        assertTrue("\"args\": [\"-y\", \"mcp-remote\", \"$url\", \"--allow-http\"]" in config, config)
        assertTrue(config.trimEnd().endsWith("}"), config)
    }

    @Test
    fun adbForwardMapsThePortToItself() {
        assertEquals("adb forward tcp:8765 tcp:8765", ClaudeLink.adbForward(8765))
    }
}
