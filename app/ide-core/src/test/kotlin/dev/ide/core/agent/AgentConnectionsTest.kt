package dev.ide.core.agent

import dev.ide.agent.AllowAllGate
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.impl.builtinTools
import dev.ide.agent.mcp.CodeAssistMcpServer
import dev.ide.agent.mcp.FileSystemAgentWorkspace
import dev.ide.agent.mcp.HttpMcpServer
import dev.ide.ui.backend.UiAgentConnections
import dev.ide.ui.backend.UiMcpScope
import dev.ide.ui.backend.UiMcpServerConfig
import dev.ide.ui.backend.UiMcpStatus
import dev.ide.ui.backend.UiMcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentConnectionsTest {
    private val root: Path = Files.createTempDirectory("agent-conn")
    private val shared: Path = root.resolve("shared").createDirectories()
    private val project: Path = root.resolve("project").createDirectories()
    private val prefs = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server: HttpMcpServer = run {
        val ws = FileSystemAgentWorkspace(project)
        CodeAssistMcpServer.startHttpServer(ws, port = 0, tools = SimpleToolRegistry(builtinTools(ws)), gate = AllowAllGate)
    }
    private val url get() = "http://127.0.0.1:${server.port}/mcp"

    private fun connections(stdio: Boolean = true) = AgentConnections(
        sharedRoot = { shared },
        projectRootOf = { project },
        preference = { prefs[it] },
        setPreference = { k, v -> prefs[k] = v },
        scope = scope,
        stdioSupported = stdio,
    )

    @AfterEach
    fun tearDown() {
        server.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a server from the project's files waits for approval, then connects and offers its tools`() {
        project.resolve(".mcp.json").writeText("""{"mcpServers":{"docs":{"type":"http","url":"$url"}}}""")
        val c = connections()
        await(c) { server("docs")?.status == UiMcpStatus.UNTRUSTED }
        assertTrue(c.tools().none { it.spec.name.startsWith("mcp__") })

        c.trust("docs")
        await(c) { server("docs")?.status == UiMcpStatus.CONNECTED }
        assertNotNull(c.tools().firstOrNull { it.spec.name == "mcp__docs__read_file" })

        // Pointing the same name somewhere else needs approving again.
        project.resolve(".mcp.json").writeText("""{"mcpServers":{"docs":{"type":"http","url":"$url?v=2"}}}""")
        c.refresh()
        await(c) { server("docs")?.status == UiMcpStatus.UNTRUSTED }
    }

    @Test
    fun `servers added in the app are approved, saved in the right file, and can be switched off`() {
        val c = connections()
        assertNull(c.save(UiMcpServerConfig("local", UiMcpTransport.HTTP, url = url), UiMcpScope.PROJECT, null))
        assertTrue(project.resolve(".codeassist/mcp.json").readText().contains("\"local\""))
        await(c) { server("local")?.status == UiMcpStatus.CONNECTED }
        assertTrue(c.tools().any { it.spec.name.startsWith("mcp__local__") })

        c.setEnabled("local", UiMcpScope.PROJECT, false)
        await(c) { server("local")?.status == UiMcpStatus.DISABLED }
        assertTrue(c.tools().none { it.spec.name.startsWith("mcp__local__") })

        c.remove("local", UiMcpScope.PROJECT)
        await(c) { servers.isEmpty() }
    }

    @Test
    fun `a project server hides the app-wide one with the same name`() {
        val c = connections()
        assertNull(c.save(UiMcpServerConfig("api", UiMcpTransport.HTTP, url = url), UiMcpScope.APP, null))
        assertTrue(shared.resolve("agent/mcp.json").exists())
        assertNull(c.save(UiMcpServerConfig("api", UiMcpTransport.HTTP, url = "$url?project"), UiMcpScope.PROJECT, null))
        await(c) { servers.size == 2 && servers.single { it.scope == UiMcpScope.APP }.shadowed }
        await(c) { servers.single { it.scope == UiMcpScope.PROJECT }.status == UiMcpStatus.CONNECTED }
    }

    @Test
    fun `bad server entries are refused with a reason`() {
        val c = connections(stdio = false)
        assertNotNull(c.save(UiMcpServerConfig("", UiMcpTransport.HTTP, url = url), UiMcpScope.APP, null))
        assertNotNull(c.save(UiMcpServerConfig("has space", UiMcpTransport.HTTP, url = url), UiMcpScope.APP, null))
        assertNotNull(c.save(UiMcpServerConfig("nourl", UiMcpTransport.HTTP), UiMcpScope.APP, null))
        assertNotNull(c.save(UiMcpServerConfig("ftp", UiMcpTransport.HTTP, url = "ftp://x"), UiMcpScope.APP, null))
        assertNotNull(c.save(UiMcpServerConfig("cmd", UiMcpTransport.STDIO, command = "npx"), UiMcpScope.APP, null))
    }

    @Test
    fun `skills are found, offered through the skill tool, and can be switched off`() {
        val c = connections()
        val path = c.createSkill("Release Notes", UiMcpScope.PROJECT)
        assertNotNull(path)
        assertTrue(path.endsWith(".codeassist/skills/release-notes/SKILL.md"))
        shared.resolve("agent/skills/pdf").createDirectories().resolve("SKILL.md")
            .writeText("---\nname: pdf\ndescription: Fill in PDF forms\n---\nSteps.")
        c.refresh()
        await(c) { skills.map { it.name }.toSet() == setOf("release-notes", "pdf") }
        assertTrue(c.tools().any { it.spec.name == "skill" })
        assertTrue(c.promptSections().any { "- pdf: Fill in PDF forms" in it })

        c.setSkillEnabled("pdf", false)
        assertFalse(c.promptSections().any { "pdf" in it })
        c.setSkillEnabled("release-notes", false)
        assertTrue(c.tools().none { it.spec.name == "skill" })
    }

    private fun await(c: AgentConnections, predicate: UiAgentConnections.() -> Boolean) = runBlocking {
        try {
            withTimeout(20_000) { c.state.first { it.predicate() } }
        } catch (e: Exception) {
            throw AssertionError("State never matched: ${c.state.value}", e)
        }
    }

    private fun UiAgentConnections.server(name: String) = servers.firstOrNull { it.config.name == name }
}
