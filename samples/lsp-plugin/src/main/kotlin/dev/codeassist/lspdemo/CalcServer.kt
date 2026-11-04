@file:JvmName("CalcServerMain")

package dev.codeassist.lspdemo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * The Calc language server's entry point, run in a VM of its own: `dalvikvm64 -cp <the plugin's APK>
 * dev.codeassist.lspdemo.CalcServerMain`. Speaks LSP on stdin/stdout; stdout carries nothing else, and logs
 * go to stderr (which the IDE's client drains into its log).
 */
fun main() {
    CalcServer(System.`in`, System.out).run()
}

/** A single-threaded LSP server for Calc. Every request is answered from a fresh [CalcAnalysis]. */
class CalcServer(input: InputStream, private val output: OutputStream) {
    private val input = BufferedInputStream(input)
    private val documents = HashMap<String, String>()
    private val analyses = HashMap<String, CalcAnalysis>()
    private var nextId = 1

    /** `workspace/executeCommand` requests waiting for the client's answer to the `applyEdit` they sent. */
    private val waitingCommands = HashMap<Int, JsonElement>()

    fun run() {
        while (true) {
            val body = read() ?: return
            val message = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            val method = (message["method"] as? JsonPrimitive)?.contentOrNull
            val id = message["id"]?.takeIf { it !is JsonNull }
            if (method == null) {
                // The client's answer to one of ours: an applyEdit a command is waiting on.
                val key = (id as? JsonPrimitive)?.intOrNull ?: continue
                waitingCommands.remove(key)?.let { respond(it, JsonNull) }
                continue
            }
            val params = message["params"] as? JsonObject
            if (id == null) {
                notification(method, params)
                continue
            }
            try {
                val result = request(method, params, id)
                if (result !== DEFERRED) respond(id, result ?: JsonNull)
            } catch (e: Exception) {
                System.err.println("calc: $method failed: $e")
                send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("error") { put("code", -32603); put("message", e.message ?: e.toString()) }
                })
            }
        }
    }

    private fun notification(method: String, p: JsonObject?) {
        when (method) {
            "textDocument/didOpen" -> {
                val doc = p!!.obj("textDocument")
                update(doc.str("uri"), doc.str("text"), doc.int("version"))
            }
            "textDocument/didChange" -> {
                val doc = p!!.obj("textDocument")
                val text = (p["contentChanges"] as JsonArray).last().jsonObject.str("text")
                update(doc.str("uri"), text, doc.int("version"))
            }
            "textDocument/didClose" -> {
                val uri = p!!.obj("textDocument").str("uri")
                documents.remove(uri); analyses.remove(uri)
            }
            "exit" -> exitProcess(0)
        }
    }

    private fun update(uri: String, text: String, version: Int?) {
        documents[uri] = text
        val analysis = CalcAnalysis(text)
        analyses[uri] = analysis
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "textDocument/publishDiagnostics")
            putJsonObject("params") {
                put("uri", uri)
                version?.let { put("version", it) }
                put("diagnostics", buildJsonArray {
                    for (problem in analysis.problems) addJsonObject {
                        put("range", range(problem.span))
                        put("severity", problem.severity.lsp)
                        put("code", problem.code)
                        put("source", "calc")
                        put("message", problem.message)
                        if (problem.code == "calc.unused") putJsonArray("tags") { add(1) }
                        problem.name?.let { putJsonObject("data") { put("name", it) } }
                    }
                })
            }
        })
    }

    private fun request(method: String, p: JsonObject?, id: JsonElement): JsonElement? {
        val uri = p?.get("textDocument")?.let { (it as JsonObject).str("uri") }
        val a = uri?.let { analyses[it] }
        val pos = p?.get("position") as? JsonObject
        val line = pos?.int("line") ?: 0
        val column = pos?.int("character") ?: 0
        return when (method) {
            "initialize" -> initialize()
            "shutdown" -> JsonNull
            "textDocument/completion" -> a?.let { completion(it, line, column) }
            "textDocument/hover" -> a?.let { hover(it, line, column) }
            "textDocument/signatureHelp" -> a?.let { signatureHelp(it, line, column) }
            "textDocument/definition" -> a?.symbolAt(line, column)?.let { name ->
                a.declarations[name]?.let { location(uri, it.span) }
            }
            "textDocument/references" -> a?.symbolAt(line, column)?.let { name ->
                val withDeclaration = p.obj("context").bool("includeDeclaration") != false
                buildJsonArray { for (s in a.references(name, withDeclaration)) add(location(uri, s)) }
            }
            "textDocument/documentHighlight" -> a?.symbolAt(line, column)?.let { name ->
                buildJsonArray {
                    a.declarations[name]?.let { d -> addJsonObject { put("range", range(d.span)); put("kind", 3) } }
                    for ((n, s) in a.uses) if (n == name) addJsonObject { put("range", range(s)); put("kind", 2) }
                }
            }
            "textDocument/prepareRename" -> a?.symbolAt(line, column)?.let { name ->
                val span = (a.references(name)).first { it.contains(line, column) }
                buildJsonObject { put("range", range(span)); put("placeholder", name) }
            }
            "textDocument/rename" -> a?.let { rename(it, uri, line, column, p.str("newName")) }
            "textDocument/documentSymbol" -> a?.let { symbols(it) }
            "textDocument/foldingRange" -> a?.let { folds(it) }
            "textDocument/formatting" -> a?.let { formatting(it) }
            "textDocument/semanticTokens/full" -> a?.let { semanticTokens(it) }
            "textDocument/inlayHint" -> a?.let { inlayHints(it) }
            "textDocument/codeAction" -> a?.let { codeActions(it, uri, p) }
            "workspace/executeCommand" -> executeCommand(p!!, id)
            else -> throw IllegalArgumentException("unsupported: $method")
        }
    }

    private fun initialize(): JsonElement = buildJsonObject {
        putJsonObject("capabilities") {
            putJsonObject("textDocumentSync") { put("openClose", true); put("change", 1) }
            putJsonObject("completionProvider") { putJsonArray("triggerCharacters") {} }
            put("hoverProvider", true)
            putJsonObject("signatureHelpProvider") { putJsonArray("triggerCharacters") { add("("); add(",") } }
            put("definitionProvider", true)
            put("referencesProvider", true)
            put("documentHighlightProvider", true)
            putJsonObject("renameProvider") { put("prepareProvider", true) }
            put("documentSymbolProvider", true)
            put("foldingRangeProvider", true)
            put("documentFormattingProvider", true)
            put("inlayHintProvider", true)
            putJsonObject("codeActionProvider") { putJsonArray("codeActionKinds") { add("quickfix") } }
            putJsonObject("executeCommandProvider") { putJsonArray("commands") { add(REMOVE_LINE) } }
            putJsonObject("semanticTokensProvider") {
                putJsonObject("legend") {
                    putJsonArray("tokenTypes") { TOKEN_TYPES.forEach { add(it) } }
                    putJsonArray("tokenModifiers") { add("declaration"); add("readonly") }
                }
                put("full", true)
            }
        }
        putJsonObject("serverInfo") { put("name", "calc-language-server"); put("version", "1.0.0") }
    }

    // ---------------------------------------------------------------------------------------------- features

    private fun completion(a: CalcAnalysis, line: Int, column: Int): JsonElement = buildJsonObject {
        put("isIncomplete", false)
        putJsonArray("items") {
            val atLineStart = a.lines.getOrNull(line).orEmpty().take(column).isBlank() ||
                tokenize(a.lines.getOrNull(line).orEmpty().take(column)).size <= 1
            if (atLineStart) {
                addJsonObject {
                    put("label", "let"); put("kind", 14); put("detail", "declare a constant")
                    put("insertTextFormat", 2); put("insertText", "let \${1:name} = \$0"); put("sortText", "0let")
                }
                addJsonObject {
                    put("label", "test"); put("kind", 14); put("detail", "a test: an expression and the value it should have")
                    put("insertTextFormat", 2); put("insertText", "test \${1:name}: \$2 is \$0"); put("sortText", "0test")
                }
                addJsonObject {
                    put("label", "print"); put("kind", 14); put("detail", "print a value")
                    put("insertTextFormat", 2); put("insertText", "print \$0"); put("sortText", "0print")
                }
            }
            for (d in a.visibleAt(line)) addJsonObject {
                put("label", d.name); put("kind", 21)
                put("detail", d.value?.let(::formatValue) ?: d.expression)
                put("sortText", "1${d.name}")
            }
            for (b in BUILTINS.values) addJsonObject {
                put("label", b.name); put("kind", 3); put("detail", b.signature)
                putJsonObject("documentation") { put("kind", "markdown"); put("value", b.doc) }
                put("insertTextFormat", 2)
                put("insertText", "${b.name}(" + b.parameters.mapIndexed { i, n -> "\${${i + 1}:$n}" }.joinToString(", ") + ")\$0")
                put("sortText", "2${b.name}")
            }
        }
    }

    private fun hover(a: CalcAnalysis, line: Int, column: Int): JsonElement? {
        val name = a.symbolAt(line, column)
        val markdown = if (name != null) {
            val d = a.declarations[name] ?: return null
            "```calc\nlet ${d.name} = ${d.expression}\n```\n" +
                (d.value?.let { "Value: **${formatValue(it)}**" } ?: "Has no value: its expression has an error.") +
                "\n\nDeclared on line ${d.span.line + 1}, used ${a.references(name, false).size} time(s)."
        } else {
            val call = a.calls.firstOrNull { it.second.contains(line, column) } ?: return null
            val b = BUILTINS[call.first] ?: return null
            "```calc\n${b.signature}\n```\n${b.doc}"
        }
        return buildJsonObject { putJsonObject("contents") { put("kind", "markdown"); put("value", markdown) } }
    }

    /** The innermost unclosed `name(` left of the caret, and which argument the caret is in. */
    private fun signatureHelp(a: CalcAnalysis, line: Int, column: Int): JsonElement? {
        val before = a.lines.getOrNull(line)?.take(column) ?: return null
        var depth = 0
        var commas = 0
        var i = before.length - 1
        while (i >= 0) {
            when (before[i]) {
                ')' -> depth++
                '(' -> if (depth == 0) break else depth--
                ',' -> if (depth == 0) commas++
            }
            i--
        }
        if (i < 0) return null
        val name = before.substring(0, i).trimEnd().takeLastWhile { it.isLetterOrDigit() || it == '_' }
        val b = BUILTINS[name] ?: return null
        return buildJsonObject {
            putJsonArray("signatures") {
                addJsonObject {
                    put("label", b.signature)
                    putJsonObject("documentation") { put("kind", "markdown"); put("value", b.doc) }
                    putJsonArray("parameters") {
                        var at = b.name.length + 1
                        for (p in b.parameters) {
                            addJsonObject { putJsonArray("label") { add(at); add(at + p.length) } }
                            at += p.length + 2
                        }
                    }
                }
            }
            put("activeSignature", 0)
            put("activeParameter", commas.coerceAtMost(b.parameters.size - 1))
        }
    }

    private fun rename(a: CalcAnalysis, uri: String, line: Int, column: Int, newName: String): JsonElement? {
        val name = a.symbolAt(line, column) ?: return null
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(newName)) { "'$newName' is not a valid name" }
        require(newName !in KEYWORDS && newName !in BUILTINS) { "'$newName' is reserved" }
        require(newName == name || newName !in a.declarations) { "'$newName' is already defined" }
        return buildJsonObject {
            putJsonObject("changes") {
                putJsonArray(uri) {
                    for (s in a.references(name)) addJsonObject { put("range", range(s)); put("newText", newName) }
                }
            }
        }
    }

    private fun symbols(a: CalcAnalysis): JsonElement = buildJsonArray {
        for (d in a.declarations.values) addJsonObject {
            put("name", d.name)
            put("detail", d.value?.let { "= ${formatValue(it)}" } ?: d.expression)
            put("kind", 14) // Constant
            put("range", buildJsonObject {
                put("start", position(d.span.line, 0))
                put("end", position(d.span.line, a.lines[d.span.line].length))
            })
            put("selectionRange", range(d.span))
        }
    }

    /** Runs of two or more comment lines fold, like a doc block. */
    private fun folds(a: CalcAnalysis): JsonElement = buildJsonArray {
        var start = -1
        fun close(end: Int) {
            if (start >= 0 && end > start) addJsonObject {
                put("startLine", start); put("startCharacter", a.lines[start].length)
                put("endLine", end); put("endCharacter", a.lines[end].length)
                put("kind", "comment")
            }
            start = -1
        }
        for ((i, l) in a.lines.withIndex()) {
            if (l.trimStart().startsWith("#")) { if (start < 0) start = i } else close(i - 1)
        }
        close(a.lines.size - 1)
    }

    private fun formatting(a: CalcAnalysis): JsonElement = buildJsonArray {
        for (i in a.lines.indices) {
            val f = a.formatted(i) ?: continue
            addJsonObject {
                put("range", buildJsonObject { put("start", position(i, 0)); put("end", position(i, a.lines[i].length)) })
                put("newText", f)
            }
        }
    }

    private fun semanticTokens(a: CalcAnalysis): JsonElement {
        // (span, type, modifiers), sorted into document order, then delta-encoded.
        val tokens = ArrayList<Triple<Span, Int, Int>>()
        a.keywords.forEach { tokens += Triple(it, 0, 0) }
        a.declarations.values.forEach { tokens += Triple(it.span, 1, 0b11) }
        a.uses.forEach { tokens += Triple(it.second, 1, 0b10) }
        a.calls.forEach { tokens += Triple(it.second, 2, 0) }
        tokens.sortWith(compareBy({ it.first.line }, { it.first.start }))
        return buildJsonObject {
            putJsonArray("data") {
                var prevLine = 0
                var prevStart = 0
                for ((span, type, mods) in tokens) {
                    val dl = span.line - prevLine
                    add(dl); add(if (dl == 0) span.start - prevStart else span.start)
                    add(span.end - span.start); add(type); add(mods)
                    prevLine = span.line; prevStart = span.start
                }
            }
        }
    }

    /** The value of every statement whose expression is not already a plain number. */
    private fun inlayHints(a: CalcAnalysis): JsonElement = buildJsonArray {
        for ((line, value) in a.values.entries.sortedBy { it.key }) {
            val source = a.lines[line]
            val code = source.substringBefore('#').trimEnd()
            val expression = code.substringAfter('=', code.substringAfter("print", code)).trim()
            if (expression.toDoubleOrNull() != null) continue
            addJsonObject {
                put("position", position(line, code.length))
                put("label", "= ${formatValue(value)}")
                put("kind", 1)
                put("paddingLeft", true)
            }
        }
    }

    private fun codeActions(a: CalcAnalysis, uri: String, p: JsonObject): JsonElement = buildJsonArray {
        val diagnostics = p.obj("context")["diagnostics"] as? JsonArray ?: JsonArray(emptyList())
        for (d in diagnostics) {
            val o = d as? JsonObject ?: continue
            val name = (o["data"] as? JsonObject)?.str("name") ?: continue
            val line = o.obj("range").obj("start").int("line") ?: continue
            when (o.str("code")) {
                // A plain edit: the client applies it as it is.
                "calc.undefined" -> addJsonObject {
                    put("title", "Define '$name'")
                    put("kind", "quickfix")
                    put("isPreferred", true)
                    putJsonArray("diagnostics") { add(o) }
                    putJsonObject("edit") {
                        putJsonObject("changes") {
                            putJsonArray(uri) {
                                addJsonObject {
                                    put("range", buildJsonObject { put("start", position(line, 0)); put("end", position(line, 0)) })
                                    put("newText", "let $name = 0\n")
                                }
                            }
                        }
                    }
                }
                // A command: the server sends the edit back with workspace/applyEdit, as many real servers do.
                "calc.unused" -> addJsonObject {
                    put("title", "Remove unused '$name'")
                    put("kind", "quickfix")
                    putJsonArray("diagnostics") { add(o) }
                    putJsonObject("command") {
                        put("title", "Remove unused '$name'")
                        put("command", REMOVE_LINE)
                        putJsonArray("arguments") { add(uri); add(line) }
                    }
                }
            }
        }
    }

    private fun executeCommand(p: JsonObject, id: JsonElement): JsonElement? {
        require(p.str("command") == REMOVE_LINE) { "unknown command ${p.str("command")}" }
        val args = p["arguments"] as JsonArray
        val uri = (args[0] as JsonPrimitive).content
        val line = (args[1] as JsonPrimitive).intOrNull ?: return null
        val lines = documents[uri]?.split('\n') ?: return null
        val end = if (line + 1 < lines.size) position(line + 1, 0) else position(line, lines[line].length)
        val requestId = nextId++
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", requestId); put("method", "workspace/applyEdit")
            putJsonObject("params") {
                put("label", "Remove unused declaration")
                putJsonObject("edit") {
                    putJsonObject("changes") {
                        putJsonArray(uri) {
                            addJsonObject {
                                put("range", buildJsonObject { put("start", position(line, 0)); put("end", end) })
                                put("newText", "")
                            }
                        }
                    }
                }
            }
        })
        // Answered once the client has answered the applyEdit (see [run]).
        waitingCommands[requestId] = id
        return DEFERRED
    }

    // ---------------------------------------------------------------------------------------------- protocol

    private fun position(line: Int, character: Int) = buildJsonObject { put("line", line); put("character", character) }
    private fun range(s: Span) = buildJsonObject { put("start", position(s.line, s.start)); put("end", position(s.line, s.end)) }
    private fun location(uri: String, s: Span) = buildJsonObject { put("uri", uri); put("range", range(s)) }

    private fun respond(id: JsonElement, result: JsonElement) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })

    private fun send(message: JsonObject) {
        val body = message.toString().toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun read(): String? {
        var length = -1
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) { if (length >= 0) break else continue }
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", true)) {
                length = line.substring(colon + 1).trim().toInt()
            }
        }
        val bytes = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(bytes, n, length - n)
            if (r < 0) return null
            n += r
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLine(): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("US-ASCII")
            if (b == '\n'.code) return buf.toString("US-ASCII")
            if (b != '\r'.code) buf.write(b)
        }
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject
    private fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val REMOVE_LINE = "calc.removeLine"
        val TOKEN_TYPES = listOf("keyword", "variable", "function")

        /** A request answered later, not now. */
        private val DEFERRED: JsonElement = JsonPrimitive("deferred")
    }
}
