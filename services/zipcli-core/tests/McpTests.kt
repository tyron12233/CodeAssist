// Standalone JVM program (like ScenarioTests.kt): starts the real MCP HTTP server on a random local port,
// talks to it over raw sockets and checks protocol, security and tool behaviour against a temp directory.
//
// Needs on the classpath: the app's domain/fs/zip/mcp sources (no Android classes are touched:
// McpController and McpService are NOT used here) plus kotlinx-serialization-json, kotlinx-coroutines-core.
import com.zipcli.ke.domain.*
import com.zipcli.ke.domain.commands.*
import com.zipcli.ke.fs.*
import com.zipcli.ke.mcp.*
import com.zipcli.ke.zip.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket

private var passed = 0
private var failed = 0
private fun check(name: String, cond: Boolean, extra: String = "") {
    if (cond) passed++ else { failed++; println("FAIL: $name $extra") }
}

private const val TOKEN = "test-token-123"

private class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
    val json: JsonObject? get() = try { Json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
    val result: JsonObject? get() = json?.get("result") as? JsonObject
    val errorCode: Int? get() = ((json?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
    /** Text of the first content block of a tools/call result. */
    val toolText: String get() =
        (((result?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: ""
    val toolIsError: Boolean get() = (result?.get("isError") as? JsonPrimitive)?.booleanOrNull ?: false
}

private fun http(
    port: Int, method: String, path: String = "/mcp", body: String? = null,
    headers: Map<String, String> = emptyMap(), token: String? = TOKEN,
): Reply {
    Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10_000
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
        if (token != null) sb.append("Authorization: Bearer $token\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        if (body != null) sb.append("Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n")
        sb.append("\r\n")
        if (body != null) sb.append(body)
        s.getOutputStream().write(sb.toString().toByteArray())
        s.getOutputStream().flush()
        val raw = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) { val n = s.getInputStream().read(buf); if (n < 0) break; raw.write(buf, 0, n) }
        return parse(raw.toString("UTF-8"))
    }
}

private fun parse(raw: String): Reply {
    val head = raw.substringBefore("\r\n\r\n")
    val body = raw.substringAfter("\r\n\r\n", "")
    val lines = head.split("\r\n")
    val status = lines[0].split(' ')[1].toInt()
    val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    return Reply(status, headers, body)
}

private fun rpc(id: Int, method: String, params: String = "{}") =
    """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

private fun call(port: Int, command: String, extraArgs: String = ""): Reply {
    val args = "{" + "\"command\":" + JsonPrimitive(command).toString() + extraArgs + "}"
    return http(port, "POST", body = rpc(1, "tools/call", """{"name":"run_command","arguments":$args}"""))
}

fun main() {
    val root = File.createTempFile("zcli-mcp", "").also { it.delete(); it.mkdirs() }
    val fs = NioFileSystem(mapOf("/home" to root))
    lateinit var reg: CommandRegistry
    val specs = buildCoreCommands { reg } + buildContentCommands() + buildArchiveCommands(Zipper(fs), Unzipper(fs)) + buildScriptCommands()
    reg = CommandRegistry(specs)
    val audit = ArrayList<String>()
    val tools = ZipCliMcpTools(reg, CommandParser(reg), fs) { synchronized(audit) { audit += it } }
    val server = McpHttpServer("127.0.0.1", 0, false, { TOKEN }, McpProtocol(tools))
    server.start()
    val port = server.boundPort
    fun file(p: String) = File(root, p)

    try {
        // ── transport security ──
        http(port, "POST", body = rpc(1, "ping"), token = null).let {
            check("no token -> 401", it.status == 401, "${it.status}")
            check("401 has WWW-Authenticate", it.headers["www-authenticate"]?.startsWith("Bearer") == true)
        }
        check("wrong token -> 401", http(port, "POST", body = rpc(1, "ping"), token = "nope").status == 401)
        check("same-length wrong token -> 401", http(port, "POST", body = rpc(1, "ping"), token = "x".repeat(TOKEN.length)).status == 401)
        http(port, "GET").let {
            check("GET -> 405", it.status == 405)
            check("405 has Allow: POST", it.headers["allow"] == "POST")
        }
        check("DELETE -> 405", http(port, "DELETE").status == 405)
        check("other path -> 404", http(port, "POST", path = "/other", body = rpc(1, "ping")).status == 404)
        check("evil Origin -> 403", http(port, "POST", body = rpc(1, "ping"), headers = mapOf("Origin" to "https://evil.example")).status == 403)
        check("opaque Origin -> 403", http(port, "POST", body = rpc(1, "ping"), headers = mapOf("Origin" to "null")).status == 403)
        check("localhost Origin ok", http(port, "POST", body = rpc(1, "ping"), headers = mapOf("Origin" to "http://localhost:6274")).status == 200)
        check("chunked -> 411", http(port, "POST", body = "{}", headers = mapOf("Transfer-Encoding" to "chunked")).status == 411)
        check("huge body -> 413", http(port, "POST", headers = mapOf("Content-Length" to "99999999")).status == 413)
        http(port, "POST", body = "{").let {
            check("bad json -> 400 parse error", it.status == 400 && it.errorCode == -32700, it.body)
        }
        check("batch -> 400", http(port, "POST", body = "[${rpc(1, "ping")}]").status == 400)

        // ── legacy handshake (2025-11-25 and earlier) ──
        http(port, "POST", body = rpc(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")).let {
            check("initialize 200", it.status == 200, it.body)
            check("initialize echoes version", (it.result?.get("protocolVersion") as? JsonPrimitive)?.content == "2025-06-18", it.body)
            check("initialize has tools capability", it.result?.get("capabilities")?.jsonObject?.containsKey("tools") == true)
            check("initialize has instructions", (it.result?.get("instructions") as? JsonPrimitive)?.content?.contains("run_command") == true)
            check("no session id issued", !it.headers.containsKey("mcp-session-id"))
        }
        http(port, "POST", body = rpc(1, "initialize", """{"protocolVersion":"2099-01-01"}""")).let {
            check("unknown client version -> newest legacy", (it.result?.get("protocolVersion") as? JsonPrimitive)?.content == "2025-11-25", it.body)
        }
        http(port, "POST", body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""").let {
            check("notification -> 202", it.status == 202 && it.body.isEmpty(), "${it.status} '${it.body}'")
        }
        http(port, "POST", body = rpc(7, "tools/list")).let { r ->
            val names = (r.result?.get("tools") as? JsonArray)?.map { ((it as JsonObject)["name"] as JsonPrimitive).content }.orEmpty()
            check("tools/list names", names == listOf("run_command", "list_commands"), "$names")
            check("tools/list has no resultType (legacy)", r.result?.containsKey("resultType") == false)
        }
        check("legacy unknown method -> -32601", http(port, "POST", body = rpc(1, "resources/list")).errorCode == -32601)
        check("unknown tool -> -32602", http(port, "POST", body = rpc(1, "tools/call", """{"name":"nope","arguments":{}}""")).errorCode == -32602)

        // ── run_command ──
        call(port, "create a.txt; update a.txt -c 'hello'; list").let {
            check("run ok", it.status == 200 && !it.toolIsError, it.body)
            check("file really created", file("a.txt").exists() && file("a.txt").readText().contains("hello"), it.toolText)
            check("output lists file", it.toolText.contains("a.txt"), it.toolText)
            check("footer shows exit+cwd", it.toolText.contains("[exit 0 | cwd /home]"), it.toolText)
        }
        call(port, "create sub/").let { check("mkdir", file("sub").isDirectory, it.toolText) }
        call(port, "cd sub").let { check("cd ok", it.toolText.contains("cwd /home/sub"), it.toolText) }
        call(port, "pwd").let { check("cwd persists across calls", it.toolText.contains("/home/sub"), it.toolText) }
        call(port, "pwd", ""","cwd":"/home"""").let { check("cwd arg", it.toolText.contains("[exit 0 | cwd /home]"), it.toolText) }
        call(port, "pwd", ""","cwd":"/home/missing"""").let { check("bad cwd arg is an error", it.toolIsError, it.toolText) }
        call(port, "delete nothere -f").let {
            check("failure -> isError", it.toolIsError, it.body)
            check("failure text prefixed", it.toolText.contains("error:"), it.toolText)
        }
        call(port, "echo one; delete nothere -f; echo two").let {
            check("script stops at first failure", it.toolIsError && !it.toolText.contains("two"), it.toolText)
        }
        call(port, "edit a.txt").let {
            check("edit refused non-interactively", it.toolIsError && !it.toolText.contains("EDITOR"), it.toolText)
        }
        call(port, "").let { check("empty command rejected", it.toolIsError) }
        call(port, "nosuchcommand").let { check("unknown command is an error", it.toolIsError && it.toolText.contains("Unknown command"), it.toolText) }

        // confirmation gating
        call(port, "create victim.txt").let { check("victim created", file("victim.txt").exists()) }
        call(port, "delete victim.txt").let {
            check("delete skipped without confirm", file("victim.txt").exists(), it.toolText)
            check("tells model about confirm", it.toolText.contains("confirm=true"), it.toolText)
        }
        call(port, "delete victim.txt", ""","confirm":true""").let {
            check("delete runs with confirm=true", !file("victim.txt").exists() && !it.toolIsError, it.toolText)
        }

        // ── list_commands ──
        http(port, "POST", body = rpc(1, "tools/call", """{"name":"list_commands","arguments":{}}""")).let {
            check("list_commands lists zip", it.toolText.contains("zip"), it.toolText)
        }
        http(port, "POST", body = rpc(1, "tools/call", """{"name":"list_commands","arguments":{"name":"delete"}}""")).let {
            check("list_commands detail shows flags", it.toolText.contains("--force"), it.toolText)
        }
        http(port, "POST", body = rpc(1, "tools/call", """{"name":"list_commands","arguments":{"name":"zzz"}}""")).let {
            check("list_commands unknown name -> isError", it.toolIsError)
        }

        // ── audit trail ──
        check("audit recorded commands", synchronized(audit) { audit.any { it.startsWith("[mcp] $ create a.txt") } }, "$audit")

        // ── modern era (2026-07-28) ──
        val modernMeta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
        fun modernHeaders(method: String, name: String? = null): Map<String, String> =
            mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to method) + (if (name != null) mapOf("Mcp-Name" to name) else emptyMap())

        http(port, "POST", body = rpc(1, "server/discover", "{$modernMeta}"), headers = modernHeaders("server/discover")).let {
            val r = it.result
            check("discover 200", it.status == 200, it.body)
            check("discover resultType", (r?.get("resultType") as? JsonPrimitive)?.content == "complete", it.body)
            check("discover lists modern version", (r?.get("supportedVersions") as? JsonArray)?.any { v -> (v as JsonPrimitive).content == "2026-07-28" } == true)
            check("discover has cache hints", r?.containsKey("ttlMs") == true && (r?.get("cacheScope") as? JsonPrimitive)?.content == "public", it.body)
            check("discover has serverInfo in _meta", r?.get("_meta")?.jsonObject?.containsKey("io.modelcontextprotocol/serverInfo") == true, it.body)
            check("discover has tools capability", r?.get("capabilities")?.jsonObject?.containsKey("tools") == true)
        }
        http(port, "POST", body = rpc(1, "tools/list", "{$modernMeta}"), headers = modernHeaders("tools/list")).let {
            check("modern tools/list has resultType+ttl", it.result?.containsKey("resultType") == true && it.result?.containsKey("ttlMs") == true, it.body)
        }
        val modernCall = rpc(1, "tools/call", """{"name":"run_command","arguments":{"command":"pwd"},$modernMeta}""")
        http(port, "POST", body = modernCall, headers = modernHeaders("tools/call", "run_command")).let {
            check("modern tools/call ok", it.status == 200 && it.toolText.contains("/home"), it.body)
            check("modern tools/call resultType", (it.result?.get("resultType") as? JsonPrimitive)?.content == "complete", it.body)
        }
        http(port, "POST", body = modernCall, headers = modernHeaders("tools/call", "other_tool")).let {
            check("Mcp-Name mismatch -> 400/-32020", it.status == 400 && it.errorCode == -32020, it.body)
        }
        http(port, "POST", body = modernCall, headers = modernHeaders("tools/list", "run_command")).let {
            check("Mcp-Method mismatch -> 400/-32020", it.status == 400 && it.errorCode == -32020, it.body)
        }
        val b64 = java.util.Base64.getEncoder().encodeToString("run_command".toByteArray())
        http(port, "POST", body = modernCall, headers = modernHeaders("tools/call", "=?base64?$b64?=")).let {
            check("base64 Mcp-Name accepted", it.status == 200, it.body)
        }
        http(port, "POST", body = modernCall, headers = mapOf("MCP-Protocol-Version" to "2025-11-25", "Mcp-Method" to "tools/call")).let {
            check("header/_meta version mismatch -> -32020", it.status == 400 && it.errorCode == -32020, it.body)
        }
        http(port, "POST", body = rpc(1, "ping"), headers = mapOf("MCP-Protocol-Version" to "2099-01-01")).let {
            check("unsupported version -> 400/-32022", it.status == 400 && it.errorCode == -32022, it.body)
            val data = it.json?.get("error")?.jsonObject?.get("data")?.jsonObject
            check("-32022 lists supported", (data?.get("supported") as? JsonArray)?.isNotEmpty() == true, it.body)
        }
        http(port, "POST", body = rpc(1, "initialize", "{$modernMeta}"), headers = modernHeaders("initialize")).let {
            check("initialize is gone in modern era -> 404/-32601", it.status == 404 && it.errorCode == -32601, it.body)
        }

        // ── keep-alive: two requests on one connection ──
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            fun one(): String {
                val body = rpc(1, "ping")
                out.write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $TOKEN\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray())
                out.flush()
                // read headers
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) break; head.append(c.toChar()) }
                val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val b = ByteArray(len); var r = 0
                while (r < len) { val n = inp.read(b, r, len - r); if (n < 0) break; r += n }
                return head.toString() + String(b)
            }
            val first = one(); val second = one()
            check("keep-alive request 1", first.startsWith("HTTP/1.1 200"), first)
            check("keep-alive request 2 on same socket", second.startsWith("HTTP/1.1 200"), second)
        }
    } finally {
        server.stop()
        root.deleteRecursively()
    }

    println("MCP tests: $passed passed, $failed failed")
    if (failed > 0) System.exit(1)
}
