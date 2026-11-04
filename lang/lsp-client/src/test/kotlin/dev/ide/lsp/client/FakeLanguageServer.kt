package dev.ide.lsp.client

import dev.ide.lang.LanguageId
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.log.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny in-process language server for a toy language whose identifiers are words: enough of the protocol to
 * drive every adapter, spoken over pipes through the same [JsonRpcConnection] the client uses.
 *
 * Its "semantics": every word `bad` is an error; `fun NAME` declares NAME; any other occurrence of NAME
 * references it. Completion offers the declared names. A code action on a `bad` runs a command that asks the
 * client to apply an edit replacing it with `good`.
 */
class FakeLanguageServer {
    val texts = ConcurrentHashMap<String, String>()
    val received = CopyOnWriteArrayList<String>()
    @Volatile var pushDiagnostics = true
    @Volatile var connection: JsonRpcConnection? = null
    @Volatile var configurationAnswer: JsonElement? = null
    /** How long the server works on a request, by method: a server still busy analysing. */
    val delays = ConcurrentHashMap<String, Long>()

    val definition = object : LanguageServerDefinition {
        override val id = "toy"
        override val displayName = "Toy server"
        override val languages = setOf(LanguageId("toy"))
        override fun start(context: LanguageServerContext): LanguageServerConnection = connect()
        override fun configuration(section: String?, context: LanguageServerContext): String? = """{"section":"$section"}"""
    }

    private fun connect(): LanguageServerConnection {
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer, 1 shl 16)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient, 1 shl 16)
        val server = JsonRpcConnection(serverIn, serverToClient, "fake-server", Log.logger("fake-server"))
        server.requestHandler = JsonRpcConnection.RequestHandler { method, params ->
            received += method
            delays[method]?.let { Thread.sleep(it) }
            answer(method, params.obj())
        }
        server.notificationHandler = JsonRpcConnection.NotificationHandler { method, params -> received += method; onNotification(method, params.obj()) }
        connection = server
        server.start()
        return LanguageServerConnection(clientIn, clientToServer, null) { server.close() }
    }

    private fun onNotification(method: String, params: JsonObject?) {
        val p = params ?: return
        when (method) {
            "textDocument/didOpen" -> {
                val doc = p.obj("textDocument")!!
                texts[doc.str("uri")!!] = doc.str("text")!!
                publish(doc.str("uri")!!, doc.int("version")!!)
            }
            "textDocument/didChange" -> {
                val doc = p.obj("textDocument")!!
                texts[doc.str("uri")!!] = p.arr("contentChanges")!!.last().jsonObject.str("text")!!
                publish(doc.str("uri")!!, doc.int("version")!!)
            }
        }
    }

    private fun publish(uri: String, version: Int) {
        if (!pushDiagnostics) return
        val text = texts[uri] ?: return
        connection?.notify("textDocument/publishDiagnostics", buildJsonObject {
            put("uri", uri)
            put("version", version)
            put("diagnostics", diagnosticsFor(text))
        })
    }

    fun diagnosticsFor(text: String): JsonArray = buildJsonArray {
        val lines = LineIndex(text)
        for (m in Regex("\\bbad\\b").findAll(text)) addJsonObject {
            put("range", lines.range(dev.ide.lang.dom.TextRange(m.range.first, m.range.last + 1)))
            put("severity", 1)
            put("code", "toy.bad")
            put("message", "'bad' is not allowed")
        }
    }

    private fun words(text: String) = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text)

    private fun declarations(text: String): Map<String, IntRange> =
        Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(text).associate { it.groupValues[1] to it.groups[1]!!.range }

    private fun wordAt(text: String, offset: Int): MatchResult? =
        words(text).firstOrNull { offset in it.range.first..(it.range.last + 1) }

    private fun answer(method: String, params: JsonObject?): JsonElement? {
        val uri = params?.obj("textDocument")?.str("uri")
        val text = uri?.let { texts[it] }.orEmpty()
        val lines = LineIndex(text)
        val offset = params?.get("position")?.let { lines.toOffset(it) } ?: 0
        return when (method) {
            "initialize" -> buildJsonObject {
                putJsonObject("capabilities") {
                    putJsonObject("textDocumentSync") { put("openClose", true); put("change", 1); putJsonObject("save") { put("includeText", false) } }
                    putJsonObject("completionProvider") { putJsonArray("triggerCharacters") { add(".") } }
                    put("hoverProvider", true)
                    putJsonObject("signatureHelpProvider") { putJsonArray("triggerCharacters") { add("(") } }
                    put("definitionProvider", true)
                    put("referencesProvider", true)
                    put("documentHighlightProvider", true)
                    put("documentSymbolProvider", true)
                    putJsonObject("codeActionProvider") { put("resolveProvider", false) }
                    put("documentFormattingProvider", true)
                    putJsonObject("renameProvider") { put("prepareProvider", true) }
                    put("foldingRangeProvider", true)
                    putJsonObject("semanticTokensProvider") {
                        putJsonObject("legend") {
                            putJsonArray("tokenTypes") { add("function"); add("keyword"); add("toyThing") }
                            putJsonArray("tokenModifiers") { add("declaration") }
                        }
                        put("full", true)
                    }
                    put("inlayHintProvider", true)
                    putJsonObject("executeCommandProvider") { putJsonArray("commands") { add("toy.fix") } }
                }
                putJsonObject("serverInfo") { put("name", "toy"); put("version", "1") }
            }
            "shutdown" -> null
            "textDocument/completion" -> buildJsonObject {
                put("isIncomplete", false)
                putJsonArray("items") {
                    for (name in declarations(text).keys) addJsonObject {
                        put("label", name); put("kind", 3); put("detail", "fun $name()")
                        put("insertTextFormat", 2); put("insertText", "$name(\$1)\$0")
                    }
                    addJsonObject { put("label", "fun"); put("kind", 14) }
                }
            }
            "textDocument/hover" -> {
                val w = wordAt(text, offset) ?: return null
                buildJsonObject {
                    putJsonObject("contents") {
                        put("kind", "markdown")
                        put("value", "```toy\nfun ${w.value}()\n```\nDeclares **${w.value}**.")
                    }
                }
            }
            "textDocument/signatureHelp" -> buildJsonObject {
                putJsonArray("signatures") {
                    addJsonObject {
                        put("label", "call(a, b)")
                        putJsonArray("parameters") {
                            addJsonObject { putJsonArray("label") { add(5); add(6) } }
                            addJsonObject { put("label", "b") }
                        }
                    }
                }
                put("activeParameter", 1)
            }
            "textDocument/definition" -> {
                val w = wordAt(text, offset) ?: return null
                val decl = declarations(text)[w.value] ?: return null
                buildJsonObject {
                    put("uri", uri!!)
                    put("range", lines.range(dev.ide.lang.dom.TextRange(decl.first, decl.last + 1)))
                }
            }
            "textDocument/references", "textDocument/documentHighlight" -> {
                val w = wordAt(text, offset) ?: return null
                buildJsonArray {
                    for (m in words(text).filter { it.value == w.value }) addJsonObject {
                        if (method.endsWith("references")) put("uri", uri!!)
                        put("range", lines.range(dev.ide.lang.dom.TextRange(m.range.first, m.range.last + 1)))
                        if (method.endsWith("Highlight")) put("kind", 2)
                    }
                }
            }
            "textDocument/prepareRename" -> {
                val w = wordAt(text, offset) ?: return null
                buildJsonObject {
                    put("range", lines.range(dev.ide.lang.dom.TextRange(w.range.first, w.range.last + 1)))
                    put("placeholder", w.value)
                }
            }
            "textDocument/rename" -> {
                val w = wordAt(text, offset) ?: return null
                val newName = params!!.str("newName")!!
                if (newName == "fun") throw ResponseErrorException(-32602, "'fun' is a keyword")
                buildJsonObject {
                    putJsonObject("changes") {
                        putJsonArray(uri!!) {
                            for (m in words(text).filter { it.value == w.value }) addJsonObject {
                                put("range", lines.range(dev.ide.lang.dom.TextRange(m.range.first, m.range.last + 1)))
                                put("newText", newName)
                            }
                        }
                    }
                }
            }
            "textDocument/documentSymbol" -> buildJsonArray {
                for ((name, range) in declarations(text)) addJsonObject {
                    put("name", name)
                    put("kind", 12)
                    val end = text.indexOf('}', range.last).let { if (it < 0) text.length else it + 1 }
                    put("range", lines.range(dev.ide.lang.dom.TextRange(range.first - 4, end)))
                    put("selectionRange", lines.range(dev.ide.lang.dom.TextRange(range.first, range.last + 1)))
                }
            }
            "textDocument/foldingRange" -> buildJsonArray {
                val open = text.indexOf('{')
                val close = text.lastIndexOf('}')
                if (open >= 0 && close > open && lines.line(close) > lines.line(open)) addJsonObject {
                    put("startLine", lines.line(open)); put("startCharacter", open + 1 - (lines.offset(lines.line(open), 0)))
                    put("endLine", lines.line(close)); put("endCharacter", close - lines.offset(lines.line(close), 0))
                }
            }
            "textDocument/semanticTokens/full" -> buildJsonObject {
                // Every `fun` keyword (type 1) and the declared name after it (type 0, declaration).
                putJsonArray("data") {
                    var prevLine = 0
                    var prevChar = 0
                    for (m in Regex("\\bfun\\s+([A-Za-z_]\\w*)").findAll(text)) {
                        for ((start, len, type, mods) in listOf(
                            listOf(m.range.first, 3, 1, 0),
                            listOf(m.groups[1]!!.range.first, m.groups[1]!!.value.length, 0, 1),
                        )) {
                            val line = lines.line(start)
                            val char = start - lines.offset(line, 0)
                            add(line - prevLine); add(if (line == prevLine) char - prevChar else char)
                            add(len); add(type); add(mods)
                            prevLine = line; prevChar = char
                        }
                    }
                }
            }
            "textDocument/inlayHint" -> buildJsonArray {
                for ((name, range) in declarations(text)) addJsonObject {
                    put("position", lines.position(range.last + 1))
                    put("label", ": Unit")
                    put("kind", 1)
                }
            }
            "textDocument/formatting" -> buildJsonArray {
                // Collapse runs of spaces to one.
                for (m in Regex(" {2,}").findAll(text)) addJsonObject {
                    put("range", lines.range(dev.ide.lang.dom.TextRange(m.range.first, m.range.last + 1)))
                    put("newText", " ")
                }
            }
            "textDocument/codeAction" -> buildJsonArray {
                val diags = params!!.obj("context")?.arr("diagnostics").orEmpty()
                for (d in diags) addJsonObject {
                    put("title", "Replace with 'good'")
                    put("kind", "quickfix")
                    putJsonObject("command") {
                        put("title", "fix")
                        put("command", "toy.fix")
                        putJsonArray("arguments") { add(uri!!); add(d.jsonObject["range"]!!) }
                    }
                }
                addJsonObject { put("title", "Say hello"); put("kind", "refactor.rewrite"); putJsonObject("edit") {} }
            }
            "workspace/executeCommand" -> {
                val args = params!!.arr("arguments")!!
                val target = args[0].let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                val range = args[1]
                // Ask the client to apply the edit, as real servers do, and wait for its answer.
                val applied = connection!!.request("workspace/applyEdit", buildJsonObject {
                    putJsonObject("edit") {
                        putJsonObject("changes") {
                            putJsonArray(target) { addJsonObject { put("range", range); put("newText", "good") } }
                        }
                    }
                }).future.get()
                configurationAnswer = applied
                null
            }
            else -> throw ResponseErrorException(ResponseErrorException.METHOD_NOT_FOUND, method)
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
