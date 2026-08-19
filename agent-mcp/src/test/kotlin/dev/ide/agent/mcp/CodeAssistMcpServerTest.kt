package dev.ide.agent.mcp

import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.AllowAllGate
import dev.ide.agent.DiagnosticInfo
import dev.ide.agent.ModuleInfo
import dev.ide.agent.PermissionMode
import dev.ide.agent.ProjectOverview
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.SymbolHit
import dev.ide.agent.TextEdit
import dev.ide.agent.TextMatch
import dev.ide.agent.WorkspaceEntry
import dev.ide.agent.WriteRequest
import dev.ide.agent.impl.builtinTools
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.ProtocolVersions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end stdio protocol tests: a real MCP client talks newline-delimited JSON-RPC to the server over
 * in-process pipe streams, so the full wire path (initialize handshake, tools/list, tools/call, resources,
 * prompts) is exercised offline with no subprocess.
 */
class CodeAssistMcpServerTest {

    @Test
    fun `initialize and tools list expose the agent tool set`() {
        val fake = FakeWorkspace("Main.kt" to "fun main() {}")
        val (server, client) = connect(fake, AllowAllGate)

        try {
            val init = client.initialize()
            assertEquals(CodeAssistMcpServer.DEFAULT_SERVER_NAME, init.serverInfo().name())

            val toolNames = client.listTools().tools().map { it.name() }.toSet()
            assertTrue("read_file" in toolNames)
            assertTrue("edit_file" in toolNames)
            assertTrue("create_file" in toolNames)
            assertTrue("search_text" in toolNames)
            assertTrue("project_overview" in toolNames)
            assertTrue("run_program" in toolNames)
            assertTrue("run_task" in toolNames)
            assertTrue("read_memory" in toolNames)
            assertTrue("web_fetch" in toolNames)
            assertTrue("http_request" in toolNames)
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `read_file tool returns the workspace file content`() {
        val fake = FakeWorkspace("src/Main.kt" to "fun main() { println(\"hi\") }")
        val (server, client) = connect(fake, AllowAllGate)

        try {
            client.initialize()
            val result = client.callTool(
                McpSchema.CallToolRequest.builder("read_file")
                    .arguments(mapOf("path" to "src/Main.kt"))
                    .build(),
            )
            assertFalse(result.isError())
            val text = textOf(result)
            assertTrue("fun main()" in text)
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `mutating tool applies edits when the gate allows`() {
        val fake = FakeWorkspace("src/Main.kt" to "fun main() { println(\"old\") }")
        val (server, client) = connect(fake, AllowAllGate)

        try {
            client.initialize()
            val result = client.callTool(
                McpSchema.CallToolRequest.builder("edit_file")
                    .arguments(
                        mapOf(
                            "path" to "src/Main.kt",
                            "old_string" to "old",
                            "new_string" to "new",
                        ),
                    )
                    .build(),
            )
            assertFalse(result.isError())
            assertEquals("fun main() { println(\"new\") }", fake.readNow("src/Main.kt"))
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `mutating tool is refused when the gate denies`() {
        val fake = FakeWorkspace("src/Main.kt" to "fun main() {}")
        val (server, client) = connect(fake, DenyGate)

        try {
            client.initialize()
            val result = client.callTool(
                McpSchema.CallToolRequest.builder("write_file")
                    .arguments(mapOf("path" to "src/Other.kt", "content" to "fun other() {}"))
                    .build(),
            )
            assertTrue(result.isError())
            assertTrue(textOf(result).contains("Permission denied"))
            assertTrue("Other.kt" !in fake.files.keys)
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `resources and prompt are advertised`() {
        val fake = FakeWorkspace(memory = "Convention: use spaces.")
        val (server, client) = connect(fake, AllowAllGate)

        try {
            client.initialize()
            val resourceUris = client.listResources().resources().map { it.uri() }
            assertTrue(CodeAssistMcpServer.PROJECT_OVERVIEW_URI in resourceUris)
            assertTrue(CodeAssistMcpServer.PROJECT_MEMORY_URI in resourceUris)

            val memory = client.readResource(
                McpSchema.ReadResourceRequest.builder(CodeAssistMcpServer.PROJECT_MEMORY_URI).build(),
            )
            val memoryText = (memory.contents().single() as McpSchema.TextResourceContents).text()
            assertTrue("Convention: use spaces." in memoryText)

            val prompt = client.getPrompt(
                McpSchema.GetPromptRequest.builder(CodeAssistMcpServer.AGENT_PROMPT).build(),
            )
            val systemMessage = prompt.messages().first()
            assertEquals(McpSchema.Role.USER, systemMessage.role())
            val body = (systemMessage.content() as McpSchema.TextContent).text()
            assertTrue("CodeAssist" in body)
            assertTrue("read_file" in body)
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `create and delete round-trip through the filesystem workspace`() {
        val root = java.nio.file.Files.createTempDirectory("ca-mcp")
        val workspace = FileSystemAgentWorkspace(root)
        val (server, client) = connect(workspace, AllowAllGate)

        try {
            client.initialize()
            val created = client.callTool(
                McpSchema.CallToolRequest.builder("create_file")
                    .arguments(mapOf("path" to "notes.md", "content" to "# Hi"))
                    .build(),
            )
            assertFalse(created.isError())
            assertEquals("# Hi", java.nio.file.Files.readString(root.resolve("notes.md")))

            val deleted = client.callTool(
                McpSchema.CallToolRequest.builder("delete_path")
                    .arguments(mapOf("path" to "notes.md"))
                    .build(),
            )
            assertFalse(deleted.isError())
            assertTrue(!java.nio.file.Files.exists(root.resolve("notes.md")))
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    // --- Plumbing ---

    private fun connect(workspace: AgentWorkspace, gate: AgentPermissionGate): Pair<Server, Client> {
        val mapper = McpJsonDefaults.getMapper()

        // client writes requests -> server reads; server writes responses -> client reads
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)

        val server = CodeAssistMcpServer.build(
            transportProvider = StdioServerTransportProvider(mapper, serverInput, serverToClient),
            workspace = workspace,
            tools = SimpleToolRegistry(builtinTools(workspace)),
            gate = gate,
            mapper = mapper,
        )
        val client = McpClient.sync(PipeMcpClientTransport(clientInput, clientToServer, mapper)).build()
        return Pair(server, client)
    }

    private fun textOf(result: McpSchema.CallToolResult): String =
        result.content().joinToString("\n") { (it as McpSchema.TextContent).text() }

    /** An in-memory [AgentWorkspace] for the wire-level tests. */
    internal class FakeWorkspace(
        val files: MutableMap<String, String> = mutableMapOf(),
        private val memory: String = "",
    ) : AgentWorkspace {
        constructor(vararg entries: Pair<String, String>) : this(entries.toMap().toMutableMap())

        fun readNow(path: String): String = files.getValue(path)

        override fun projectRoot(): String = "/project"
        override suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String =
            files[path] ?: throw IllegalArgumentException("no such file: $path")
        override suspend fun listDir(path: String): List<WorkspaceEntry> = emptyList()
        override suspend fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, limit: Int): List<TextMatch> = emptyList()
        override suspend fun findSymbol(query: String, limit: Int): List<SymbolHit> = emptyList()
        override suspend fun diagnostics(path: String): List<DiagnosticInfo> = emptyList()
        override suspend fun projectOverview(): ProjectOverview =
            ProjectOverview("test", listOf(ModuleInfo("app", "java", "17", listOf("src"), emptyList())))
        override suspend fun createFile(path: String, content: String): String {
            require(path !in files) { "already exists: $path" }
            files[path] = content
            return path
        }
        override suspend fun writeFile(path: String, content: String) { files[path] = content }
        override suspend fun applyEdits(path: String, edits: List<TextEdit>) {
            var text = files[path] ?: ""
            edits.sortedByDescending { it.offset }.forEach { e ->
                text = text.substring(0, e.offset) + e.newText + text.substring(e.offset + e.oldLength)
            }
            files[path] = text
        }
        override suspend fun createDir(path: String): String = path
        override suspend fun renamePath(path: String, newName: String): String = newName
        override suspend fun movePath(path: String, destDir: String): String = path
        override suspend fun deletePath(path: String): Boolean = files.remove(path) != null
        override suspend fun addDependency(module: String, coordinate: String): String = "added"
        override suspend fun readMemory(): String = memory
        override suspend fun writeMemory(content: String): String = "saved"
        override suspend fun fetchUrl(url: String, maxChars: Int): String = "page"
        override suspend fun httpRequest(method: String, url: String, headers: List<String>, body: String?, maxChars: Int): String =
            "HTTP 200 OK"
    }

    /** Refuses every mutating tool. */
    internal object DenyGate : AgentPermissionGate {
        override val mode: PermissionMode get() = PermissionMode.PLAN_ONLY
        override suspend fun authorize(request: WriteRequest): Boolean = false
    }

    /**
     * A minimal in-process [McpClientTransport] speaking newline-delimited JSON-RPC over the given
     * streams — the client half of the wire the stdio server speaks, without spawning a subprocess. It
     * mirrors [io.modelcontextprotocol.client.transport.StdioClientTransport]'s shape (a reader thread
     * feeding a sink, writes serialized with the mapper).
     */
    private class PipeMcpClientTransport(
        private val input: InputStream,
        private val output: OutputStream,
        private val mapper: McpJsonMapper,
    ) : McpClientTransport {

        private val inbound = Sinks.many().unicast().onBackpressureBuffer<McpSchema.JSONRPCMessage>()

        override fun protocolVersions(): List<String> = listOf(ProtocolVersions.MCP_2025_11_25)

        override fun connect(
            handler: Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>,
        ): Mono<Void> {
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val message = McpSchema.deserializeJsonRpcMessage(mapper, line)
                        inbound.tryEmitNext(message)
                    }
                } finally {
                    inbound.tryEmitComplete()
                }
            }.apply { isDaemon = true }.start()
            return inbound.asFlux()
                .flatMap { message -> Mono.just(message).transform(handler) }
                .then()
        }

        override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> = Mono.fromRunnable {
            val json = mapper.writeValueAsString(message).replace("\n", "\\n")
            synchronized(output) {
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                output.write('\n'.code)
                output.flush()
            }
        }

        override fun closeGracefully(): Mono<Void> = Mono.fromRunnable {
            inbound.tryEmitComplete()
            try {
                input.close()
            } catch (_: Exception) {
            }
        }

        override fun <T> unmarshalFrom(data: Any, typeRef: TypeRef<T>): T = mapper.convertValue(data, typeRef)
    }

    private typealias Server = io.modelcontextprotocol.server.McpSyncServer
    private typealias Client = io.modelcontextprotocol.client.McpSyncClient
}
