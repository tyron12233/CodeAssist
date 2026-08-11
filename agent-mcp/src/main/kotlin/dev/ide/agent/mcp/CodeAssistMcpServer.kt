package dev.ide.agent.mcp

import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentTool
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.AllowAllGate
import dev.ide.agent.PermissionMode
import dev.ide.agent.ProjectOverview
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.WriteRequest
import dev.ide.agent.impl.SystemPrompt
import dev.ide.agent.impl.builtinTools
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransportProvider
import kotlinx.coroutines.runBlocking

/**
 * Turns CodeAssist's agent tool layer into an MCP server. Every tool in the registry is advertised over the
 * wire (its JSON schema carried verbatim) and routed through the same [AgentPermissionGate] the in-IDE
 * agent uses, so mutating tools are authorized before they run and read tools never prompt. Two read-only
 * resources expose the project's live overview and agent memory, and a prompt returns the CodeAssist agent
 * grounding for a client that wants it.
 *
 * The [McpServerTransportProvider] is injected so a host can serve over stdio (the [runStdioServer]
 * entry point), an embedded socket, or any other transport without changing the tool wiring.
 */
object CodeAssistMcpServer {

    const val DEFAULT_SERVER_NAME = "codeassist"
    const val DEFAULT_SERVER_VERSION = "1.0.0"

    const val PROJECT_OVERVIEW_URI = "codeassist://project/overview"
    const val PROJECT_MEMORY_URI = "codeassist://project/memory"
    const val AGENT_PROMPT = "codeassist_agent"

    /**
     * Builds a configured [McpServer.McpSyncServer] that serves [tools] over [transportProvider].
     * Defaults the tool registry to the built-in tool set bound to [workspace] (the engine-backed port
     * implemented by the host), so a call needs nothing but the workspace when the built-ins suffice.
     */
    fun build(
        transportProvider: McpServerTransportProvider,
        workspace: AgentWorkspace,
        tools: AgentToolRegistry = SimpleToolRegistry(builtinTools(workspace)),
        gate: AgentPermissionGate = AllowAllGate,
        serverName: String = DEFAULT_SERVER_NAME,
        serverVersion: String = DEFAULT_SERVER_VERSION,
        mapper: McpJsonMapper = McpJsonDefaults.getMapper(),
    ): McpSyncServer {
        val toolSpecs = tools.tools.map { tool -> toolSpec(tool, workspace, gate, mapper) }
        return McpServer.sync(transportProvider)
            .serverInfo(serverName, serverVersion)
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(true, false)
                    .prompts(true)
                    .build(),
            )
            .tools(toolSpecs)
            .resources(projectOverviewResource(workspace, mapper), projectMemoryResource(workspace, mapper))
            .prompts(agentPrompt(workspace, tools))
            .build()
    }

    /**
     * Advertises a single [AgentTool] as an MCP tool. The call handler bridges MCP's blocking
     * [McpSchema.CallToolRequest] handler onto the agent's suspend world with [runBlocking]; a stdio
     * server processes one session at a time, so the bridge cannot interleave. Mutating tools are
     * authorized through [gate] first and refused (as an MCP tool-level error the client's model can act
     * on) when denied.
     */
    private fun toolSpec(
        tool: AgentTool,
        workspace: AgentWorkspace,
        gate: AgentPermissionGate,
        mapper: McpJsonMapper,
    ): McpServerFeatures.SyncToolSpecification = McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool.spec.toMcpTool(mapper))
        .callHandler { _, request ->
            val args = MapToolArgs(request.arguments() ?: emptyMap(), mapper)
            if (tool.mutating) {
                val granted = runBlocking {
                    gate.authorize(WriteRequest(tool.spec.name, tool.summarize(args), pathArg(args)))
                }
                if (!granted) {
                    return@callHandler ToolExecutionResult.error(
                        "Permission denied (${describeMode(gate)}): ${tool.summarize(args)} was not authorized.",
                    ).toCallToolResult()
                }
            }
            val result = try {
                runBlocking { tool.execute(args) }
            } catch (t: Throwable) {
                ToolExecutionResult.error("Tool '${tool.spec.name}' failed: ${t.message ?: t::class.simpleName}")
            }
            result.toCallToolResult()
        }
        .build()

    private fun pathArg(args: MapToolArgs): String? = args.optString("path")

    private fun describeMode(gate: AgentPermissionGate): String = when (gate.mode) {
        PermissionMode.ASK_EACH -> "the user did not approve it"
        PermissionMode.AUTO_ACCEPT -> "it was rejected by the permission policy"
        PermissionMode.PLAN_ONLY -> "file changes are disabled in PLAN_ONLY mode"
    }

    // --- Resources (read-only, never permission-gated) ---

    private fun projectOverviewResource(
        workspace: AgentWorkspace,
        mapper: McpJsonMapper,
    ): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder(PROJECT_OVERVIEW_URI, "Project overview")
            .description("The open project's modules, source roots, and dependencies.")
            .mimeType("text/plain")
            .build()
        return McpServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val text = runBlocking { formatOverview(workspace.projectOverview()) }
            McpSchema.ReadResourceResult.builder(listOf(textResource(PROJECT_OVERVIEW_URI, text))).build()
        }
    }

    private fun projectMemoryResource(
        workspace: AgentWorkspace,
        mapper: McpJsonMapper,
    ): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder(PROJECT_MEMORY_URI, "Project memory")
            .description("The agent's persisted notes and the project's own instruction files.")
            .mimeType("text/markdown")
            .build()
        return McpServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val text = runBlocking { workspace.readMemory() }
            McpSchema.ReadResourceResult.builder(listOf(textResource(PROJECT_MEMORY_URI, text))).build()
        }
    }

    private fun textResource(uri: String, text: String): McpSchema.TextResourceContents =
        McpSchema.TextResourceContents.builder(uri, text).build()

    private fun formatOverview(overview: ProjectOverview): String {
        val sb = StringBuilder("Project: ${overview.name}")
        overview.modules.forEach { m ->
            sb.append("\n\nModule ${m.name} (${m.type})")
            m.languageLevel?.let { sb.append(", language level ").append(it) }
            sb.append("\n  source roots: ").append(m.sourceRoots.joinToString(", ").ifEmpty { "(none)" })
            sb.append("\n  dependencies: ").append(m.dependencies.joinToString(", ").ifEmpty { "(none)" })
        }
        return sb.toString()
    }

    // --- Prompt ---

    /**
     * A `codeassist_agent` prompt that returns the same grounding the in-IDE agent is given, so a client
     * can adopt the platform's real shape (on-device, interpreter-based runs, no hosted Gradle) instead of
     * guessing at a desktop toolchain.
     */
    private fun agentPrompt(
        workspace: AgentWorkspace,
        tools: AgentToolRegistry,
    ): McpServerFeatures.SyncPromptSpecification {
        val prompt = McpSchema.Prompt.builder(AGENT_PROMPT)
            .description("The CodeAssist agent grounding: what this IDE is and how to work in it.")
            .build()
        return McpServerFeatures.SyncPromptSpecification(prompt) { _, _ ->
            val grounding = SystemPrompt.build(
                mode = PermissionMode.ASK_EACH,
                toolNames = tools.specs().map { it.name },
                projectContext = runBlocking { workspace.projectRoot()?.let { "Project root: $it" } },
            )
            val messages = listOf(
                McpSchema.PromptMessage.builder(
                    McpSchema.Role.USER,
                    McpSchema.TextContent.builder(grounding).build(),
                ).build(),
            )
            McpSchema.GetPromptResult.builder(messages)
                .description("CodeAssist agent grounding and working rules.")
                .build()
        }
    }
}
