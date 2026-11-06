package dev.ide.core

import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.NavKind
import dev.ide.lsp.LANGUAGE_SERVER_EP
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.lsp.client.JsonRpcConnection
import dev.ide.lsp.client.LineIndex
import dev.ide.platform.PluginId
import dev.ide.platform.log.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A plugin that registers nothing but a file type and a [LanguageServerDefinition] gets the editor's language
 * features for its files, through the engine's ordinary entry points: diagnostics, go to declaration and
 * implementation, find usages, highlight usages, rename (applied to disk), folding. And its server is shut
 * down when the project closes.
 */
class LanguageServerPluginTest {

    private val root = createTempDirectory("lsp-plugin")
    private var services: IdeServices? = null
    private val server = ToyServer()

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun aLanguageServerPluginLightsUpTheEditor() {
        val env = ApplicationEnvironment()
        val plugin = PluginId("test-toy")
        env.platform.extensions.register(FILE_TYPE_EP, FileTypeMapping(listOf(".toy"), LanguageId("toy")), plugin)
        env.platform.extensions.register(LANGUAGE_SERVER_EP, server.definition, plugin)
        env.languageServers.registerAll()
        val s = IdeServices.bootstrapJavaDemo(root, env).also { services = it }

        val text = "fun greet() {\n  bad\n}\ngreet()\n"
        val file = write(s, "main.toy", text)

        val diagnostics = runBlocking { s.analyzeDiagnostics(file, text) }
        val error = diagnostics.single { it.message == "'bad' is not allowed" }
        assertEquals(Severity.ERROR, error.severity)
        assertEquals("bad", text.substring(error.range.start, error.range.end))

        val call = text.lastIndexOf("greet")
        val declaration = s.navigationTargets(file, text, call, NavKind.DECLARATION).single()
        assertEquals(text.indexOf("greet"), declaration.offset)
        val implementation = s.navigationTargets(file, text, call, NavKind.IMPLEMENTATION).single()
        assertEquals(text.indexOf("greet"), implementation.offset)
        assertTrue(s.navigationOptions(file, text, call).any { it.first == NavKind.IMPLEMENTATION })

        val usages = s.navigationTargets(file, text, call, NavKind.USAGES)
        // Find Usages leaves the declaration out; Find References keeps it.
        assertEquals(listOf(call), usages.map { it.offset })
        assertEquals(2, s.findReferences(file, text, call).size)
        assertEquals(2, assertNotNull(s.usageHighlights(file, text, call)).size)

        assertEquals("greet", assertNotNull(s.prepareRename(file, text, call)).oldName)
        val outcome = runBlocking { s.rename(file, text, call, "hello") }
        assertTrue(outcome.success, outcome.message)
        assertEquals("Renamed 'greet' to 'hello'", outcome.message)
        assertEquals(text.replace("greet", "hello"), file.readText())

        val renamed = file.readText()
        val folds = s.codeFolds(file, renamed)
        assertEquals("\n  bad\n", renamed.substring(folds.single().range.start, folds.single().range.end))

        s.close()
        services = null
        val deadline = System.currentTimeMillis() + 5_000
        while ("shutdown" !in server.received && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("shutdown" in server.received, "the server is shut down with its project: ${server.received}")
    }

    private fun write(services: IdeServices, name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        services.modules()
        return file
    }

    /**
     * A server for a toy language: `bad` is an error, `fun NAME` declares NAME, every other NAME refers to
     * it. Speaks over pipes with the client's own JSON-RPC connection.
     */
    private class ToyServer {
        val texts = ConcurrentHashMap<String, String>()
        val received = CopyOnWriteArrayList<String>()
        @Volatile private var rpc: JsonRpcConnection? = null

        val definition = object : LanguageServerDefinition {
            override val id = "toy"
            override val languages = setOf(LanguageId("toy"))
            override fun start(context: LanguageServerContext): LanguageServerConnection {
                val clientOut = PipedOutputStream()
                val serverIn = PipedInputStream(clientOut, 1 shl 16)
                val serverOut = PipedOutputStream()
                val clientIn = PipedInputStream(serverOut, 1 shl 16)
                val c = JsonRpcConnection(serverIn, serverOut, "toy-server", Log.logger("toy-server"))
                c.requestHandler = JsonRpcConnection.RequestHandler { m, p -> received += m; answer(m, p as? JsonObject) }
                c.notificationHandler = JsonRpcConnection.NotificationHandler { m, p -> received += m; notified(m, p as? JsonObject) }
                rpc = c
                c.start()
                return LanguageServerConnection(clientIn, clientOut, null) { c.close() }
            }
        }

        private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.content

        private fun notified(method: String, p: JsonObject?) {
            val doc = p?.get("textDocument")?.jsonObject ?: return
            val uri = doc.s("uri") ?: return
            when (method) {
                "textDocument/didOpen" -> texts[uri] = doc.s("text")!!
                "textDocument/didChange" -> texts[uri] = p["contentChanges"]!!.jsonArray.last().jsonObject.s("text")!!
                else -> return
            }
            val text = texts.getValue(uri)
            val lines = LineIndex(text)
            rpc?.notify("textDocument/publishDiagnostics", buildJsonObject {
                put("uri", uri)
                put("version", doc["version"]!!.jsonPrimitive.int)
                put("diagnostics", buildJsonArray {
                    for (m in Regex("\\bbad\\b").findAll(text)) addJsonObject {
                        put("range", lines.range(TextRange(m.range.first, m.range.last + 1)))
                        put("severity", 1)
                        put("message", "'bad' is not allowed")
                    }
                })
            })
        }

        private fun answer(method: String, p: JsonObject?): JsonElement? {
            val uri = p?.get("textDocument")?.jsonObject?.s("uri")
            val text = uri?.let { texts[it] }.orEmpty()
            val lines = LineIndex(text)
            val offset = p?.get("position")?.let { lines.toOffset(it) } ?: 0
            val word = Regex("\\w+").findAll(text).firstOrNull { offset in it.range.first..it.range.last + 1 }
            fun range(r: IntRange) = lines.range(TextRange(r.first, r.last + 1))
            fun occurrences() = Regex("\\b${word!!.value}\\b").findAll(text).map { it.range }.toList()
            fun declaration() = Regex("\\bfun\\s+(${word!!.value})\\b").find(text)?.groups?.get(1)?.range
            return when (method) {
                "initialize" -> buildJsonObject {
                    putJsonObject("capabilities") {
                        put("textDocumentSync", 1)
                        put("definitionProvider", true)
                        put("implementationProvider", true)
                        put("referencesProvider", true)
                        put("documentHighlightProvider", true)
                        put("renameProvider", true)
                        put("foldingRangeProvider", true)
                    }
                }
                "shutdown" -> null
                "textDocument/definition", "textDocument/implementation" -> {
                    val decl = declaration() ?: return null
                    buildJsonObject { put("uri", uri!!); put("range", range(decl)) }
                }
                "textDocument/references" -> buildJsonArray {
                    // includeDeclaration is honoured: Find Usages asks without, Find References with.
                    val withDecl = p!!["context"]!!.jsonObject["includeDeclaration"]!!.jsonPrimitive.content == "true"
                    val decl = declaration()
                    for (r in occurrences()) if (withDecl || r != decl) addJsonObject { put("uri", uri!!); put("range", range(r)) }
                }
                "textDocument/documentHighlight" -> buildJsonArray { for (r in occurrences()) addJsonObject { put("range", range(r)) } }
                "textDocument/rename" -> buildJsonObject {
                    putJsonObject("changes") {
                        putJsonArray(uri!!) {
                            for (r in occurrences()) addJsonObject { put("range", range(r)); put("newText", p!!.s("newName")!!) }
                        }
                    }
                }
                "textDocument/foldingRange" -> buildJsonArray {
                    val open = text.indexOf('{')
                    val close = text.indexOf('}')
                    addJsonObject {
                        put("startLine", lines.line(open)); put("startCharacter", open + 1 - lines.offset(lines.line(open), 0))
                        put("endLine", lines.line(close)); put("endCharacter", close - lines.offset(lines.line(close), 0))
                    }
                }
                else -> null
            }
        }
    }
}
