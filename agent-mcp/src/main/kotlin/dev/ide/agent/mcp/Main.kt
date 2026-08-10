package dev.ide.agent.mcp

import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AllowAllGate
import dev.ide.agent.PermissionMode
import dev.ide.agent.WriteRequest
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch

/**
 * The standalone stdio entry point for the CodeAssist MCP server.
 *
 * Usage:
 * ```
 * ./gradlew :agent-mcp:installDist
 * build/install/agent-mcp/bin/agent-mcp --project /path/to/project [--auto-accept]
 * ```
 *
 * A client (Claude Desktop, opencode, Cursor, ...) spawns this process and speaks newline-delimited
 * JSON-RPC over stdin/stdout. It serves the built-in agent tools over a [FileSystemAgentWorkspace]
 * rooted at `--project` (defaulting to the current directory or `$CODEASSIST_MCP_PROJECT`), so a client
 * can read, search, edit, and reorganize a real project without the IDE. The engine-backed capabilities
 * (diagnostics, completion, semantic rename, builds/runs) report "not supported" here — host the server
 * in the IDE to get those.
 *
 * Mutating tools (writes, deletes, renames, HTTP requests) are denied by default, matching the agent's
 * conservative posture; pass `--auto-accept` to authorize them, or embed the server and supply your own
 * [AgentPermissionGate] for interactive approval.
 */
fun main(args: Array<String>) {
    var project = System.getenv("CODEASSIST_MCP_PROJECT")
    var autoAccept = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--project" -> project = args.getOrNull(++i) ?: error("--project requires a directory argument")
            "--auto-accept" -> autoAccept = true
            "--help", "-h" -> {
                println(
                    """
                    |Usage: agent-mcp [--project <dir>] [--auto-accept]
                    |
                    |  --project <dir>  Project root to serve (default: current directory, or
                    |                   ${'$'}CODEASSIST_MCP_PROJECT).
                    |  --auto-accept    Authorize mutating tools (writes, deletes, HTTP requests) without
                    |                   prompting. Off by default: they are refused.
                    |
                    |Serves the Model Context Protocol over stdin/stdout (stdio transport).
                    """.trimMargin(),
                )
                return
            }
            else -> error("Unknown argument: ${args[i]} (see --help)")
        }
        i++
    }

    val root = Paths.get(project ?: ".").toAbsolutePath().normalize()
    require(Files.isDirectory(root)) { "Project root is not a directory: $root" }

    val workspace = FileSystemAgentWorkspace(root)
    val gate = if (autoAccept) AllowAllGate else ReadOnlyGate

    // MCP output MUST be the only thing on stdout; anything else a client parses as a protocol message.
    System.err.println("CodeAssist MCP server ready (project: $root)")
    System.err.println("Mutating tools: ${if (autoAccept) "auto-accepted" else "denied"}. Waiting for protocol messages on stdin.")

    val mapper = McpJsonDefaults.getMapper()
    val transport = StdioServerTransportProvider(mapper, System.`in`, System.out)
    val server = CodeAssistMcpServer.build(transport, workspace, gate = gate, mapper = mapper)

    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    // The stdio transport owns an inbound thread; this latch just keeps main alive until the process ends.
    SHUTDOWN_LATCH.await()
}

/**
 * A permission gate that refuses every mutating tool, so a stdio client started without `--auto-accept`
 * can read and search but cannot change the project.
 */
private object ReadOnlyGate : AgentPermissionGate {
    override val mode: PermissionMode get() = PermissionMode.PLAN_ONLY
    override suspend fun authorize(request: WriteRequest): Boolean = false
}

private val SHUTDOWN_LATCH = CountDownLatch(1)
