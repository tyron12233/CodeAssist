package dev.ide.lsp.client

import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.NavigationTarget
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure conversions from LSP's JSON shapes to the IDE's types. Kept apart from the adapters so they test without
 * a server: every function here takes the text the positions are in (as a [LineIndex]) and returns values.
 */
internal object LspConvert {

    // ---------------------------------------------------------------------------------------------- diagnostics

    fun diagnostics(items: JsonArray, lines: LineIndex): List<Diagnostic> = items.mapNotNull { e ->
        val d = e.obj() ?: return@mapNotNull null
        val range = lines.toRange(d["range"]) ?: return@mapNotNull null
        val message = d.str("message") ?: return@mapNotNull null
        val tags = d.arr("tags")?.mapNotNullTo(HashSet()) {
            when ((it as? JsonPrimitive)?.intOrNull) {
                1 -> DiagnosticTag.UNUSED
                2 -> DiagnosticTag.DEPRECATED
                else -> null
            }
        } ?: emptySet()
        Diagnostic(
            range = range,
            severity = when (d.int("severity")) {
                2 -> Severity.WARNING
                3 -> Severity.INFO
                4 -> Severity.HINT
                else -> Severity.ERROR
            },
            message = message,
            source = DiagnosticSource.Compiler,
            code = d.scalar("code"),
            tags = tags,
        )
    }

    // ---------------------------------------------------------------------------------------------- completion

    fun completionKind(kind: Int?): CompletionItemKind = when (kind) {
        2, 3 -> CompletionItemKind.METHOD
        4 -> CompletionItemKind.CONSTRUCTOR
        5, 10, 21, 23 -> CompletionItemKind.FIELD
        6, 12 -> CompletionItemKind.VARIABLE
        7, 22 -> CompletionItemKind.CLASS
        8 -> CompletionItemKind.INTERFACE
        9, 19 -> CompletionItemKind.PACKAGE
        13 -> CompletionItemKind.ENUM
        14, 24 -> CompletionItemKind.KEYWORD
        15 -> CompletionItemKind.SNIPPET
        20 -> CompletionItemKind.ENUM_CONSTANT
        25 -> CompletionItemKind.TYPE_PARAMETER
        else -> CompletionItemKind.WORD
    }

    // ---------------------------------------------------------------------------------------------- symbols

    fun symbolKind(kind: Int?): SymbolKind = when (kind) {
        1, 2, 3, 4 -> SymbolKind.PACKAGE
        5, 19, 23 -> SymbolKind.CLASS
        6, 12, 25 -> SymbolKind.METHOD
        9 -> SymbolKind.CONSTRUCTOR
        10 -> SymbolKind.ENUM
        11 -> SymbolKind.INTERFACE
        22 -> SymbolKind.ENUM_CONSTANT
        26 -> SymbolKind.TYPE_PARAMETER
        else -> SymbolKind.FIELD
    }

    /** `DocumentSymbol[]` (a tree) or `SymbolInformation[]` (flat) as the structure view's rows, in file order. */
    fun structure(result: JsonElement?, lines: LineIndex): List<StructureItem> {
        val out = ArrayList<StructureItem>()
        fun walk(symbols: JsonArray, depth: Int) {
            for (e in symbols) {
                val s = e.obj() ?: continue
                val name = s.str("name") ?: continue
                val hierarchical = s["selectionRange"] != null
                val range = if (hierarchical) lines.toRange(s["range"]) else lines.toRange(s.obj("location")?.get("range"))
                range ?: continue
                val nameRange = if (hierarchical) lines.toRange(s["selectionRange"]) ?: range else range
                out += StructureItem(
                    name = name,
                    detail = s.str("detail") ?: s.str("containerName"),
                    kind = symbolKind(s.int("kind")),
                    nameOffset = nameRange.start,
                    endOffset = range.end,
                    depth = depth,
                )
                s.arr("children")?.let { walk(it, depth + 1) }
            }
        }
        result.arr()?.let { walk(it, 0) }
        return out.sortedBy { it.nameOffset }
    }

    // ---------------------------------------------------------------------------------------------- semantic tokens

    /**
     * Decode `SemanticTokens.data` (five integers per token, each position relative to the previous token's)
     * against the server's legend. Types the editor has a colour for map to its own kinds; any other type keeps
     * the server's name, so a plugin can give it a colour of its own. Strings, numbers, comments and operators
     * are left to the lexer, which already colours them before the server answers.
     */
    fun semanticTokens(data: JsonArray, types: List<String>, modifiers: List<String>, lines: LineIndex): List<SemanticToken> =
        semanticTokens(ints(data), types, modifiers, lines)

    fun ints(array: JsonArray): IntArray = IntArray(array.size) { (array[it] as? JsonPrimitive)?.intOrNull ?: 0 }

    /**
     * A `SemanticTokensDelta`'s edits applied to [previous]: each replaces `deleteCount` integers at `start`
     * with its `data`. Null when an edit falls outside the array, which means the delta was not computed against
     * what this client holds.
     */
    fun applyTokenEdits(previous: IntArray, edits: JsonArray): IntArray? {
        var out = previous.toMutableList()
        val sorted = edits.mapNotNull { it.obj() }.sortedByDescending { it.int("start") ?: 0 }
        for (e in sorted) {
            val start = e.int("start") ?: return null
            val delete = e.int("deleteCount") ?: 0
            if (start < 0 || delete < 0 || start + delete > out.size) return null
            val insert = e.arr("data")?.let(::ints)?.toList().orEmpty()
            out = (out.subList(0, start) + insert + out.subList(start + delete, out.size)).toMutableList()
        }
        return out.toIntArray()
    }

    fun semanticTokens(data: IntArray, types: List<String>, modifiers: List<String>, lines: LineIndex): List<SemanticToken> {
        val out = ArrayList<SemanticToken>(data.size / 5)
        var line = 0
        var character = 0
        var i = 0
        while (i + 4 < data.size) {
            val deltaLine = data[i]
            val deltaStart = data[i + 1]
            val length = data[i + 2]
            val type = data[i + 3]
            val bits = data[i + 4]
            i += 5
            if (deltaLine > 0) { line += deltaLine; character = deltaStart } else character += deltaStart
            val kind = highlightKind(types.getOrNull(type) ?: continue) ?: continue
            val start = lines.offset(line, character)
            val end = lines.offset(line, character + length)
            if (end <= start) continue
            out += SemanticToken(TextRange(start, end), kind, highlightModifiers(bits, modifiers))
        }
        return out
    }

    private val SKIPPED_TYPES = setOf("comment", "string", "number", "regexp", "operator")

    fun highlightKind(type: String): HighlightKind? = when (type) {
        in SKIPPED_TYPES -> null
        "namespace" -> HighlightKind.NAMESPACE
        "type", "class", "struct" -> HighlightKind.CLASS
        "enum" -> HighlightKind.ENUM
        "interface" -> HighlightKind.INTERFACE
        "typeParameter" -> HighlightKind.TYPE_PARAMETER
        "parameter" -> HighlightKind.PARAMETER
        "variable" -> HighlightKind.LOCAL_VARIABLE
        "property", "event" -> HighlightKind.PROPERTY
        "enumMember" -> HighlightKind.ENUM_CONSTANT
        "function" -> HighlightKind.FUNCTION
        "method" -> HighlightKind.METHOD
        "macro" -> HighlightKind.CONSTANT
        "keyword", "modifier" -> HighlightKind.KEYWORD
        "decorator", "annotation" -> HighlightKind.ANNOTATION
        else -> HighlightKind(type)
    }

    private fun highlightModifiers(bits: Int, legend: List<String>): Set<HighlightModifier> {
        if (bits == 0) return emptySet()
        val out = HashSet<HighlightModifier>(4)
        for ((index, name) in legend.withIndex()) {
            if (index >= 31 || bits and (1 shl index) == 0) continue
            when (name) {
                "declaration", "definition" -> out += HighlightModifier.DECLARATION
                "static" -> out += HighlightModifier.STATIC
                "abstract" -> out += HighlightModifier.ABSTRACT
                "deprecated" -> out += HighlightModifier.DEPRECATED
                "readonly" -> out += HighlightModifier.READONLY
                "async" -> out += HighlightModifier.SUSPEND
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------- folding

    fun folds(result: JsonElement?, lines: LineIndex): List<FoldRegion> = result.arr().orEmpty().mapNotNull { e ->
        val f = e.obj() ?: return@mapNotNull null
        val startLine = f.int("startLine") ?: return@mapNotNull null
        val endLine = f.int("endLine") ?: return@mapNotNull null
        if (endLine <= startLine) return@mapNotNull null
        val start = f.int("startCharacter")?.let { lines.offset(startLine, it) } ?: lines.lineEnd(startLine)
        val end = f.int("endCharacter")?.let { lines.offset(endLine, it) } ?: lines.lineEnd(endLine)
        if (end <= start) return@mapNotNull null
        val kind = when (f.str("kind")) {
            "comment" -> FoldKind.COMMENT
            "imports" -> FoldKind.IMPORTS
            else -> FoldKind.BLOCK
        }
        FoldRegion(TextRange(start, end), f.str("collapsedText") ?: "...", kind, collapsedByDefault = kind == FoldKind.IMPORTS)
    }

    // ---------------------------------------------------------------------------------------------- edits

    fun textEdits(edits: JsonArray?, lines: LineIndex): List<DocumentEdit> = edits.orEmpty().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        val range = lines.toRange(o["range"]) ?: return@mapNotNull null
        DocumentEdit(range.start, range.length, o.str("newText") ?: "")
    }

    /**
     * An LSP `WorkspaceEdit` as the IDE's. [textOf] gives the text a document's positions are in (the server's
     * copy when it has one, the disk otherwise); [focal] is reused for its own path so the host recognises the
     * file the action started from. A `CreateFile` followed by edits to the new file becomes one created file
     * holding their result. Renames and deletes are not advertised by this client and are skipped.
     */
    fun workspaceEdit(edit: JsonObject?, focal: VirtualFile?, textOf: (Path) -> String?): WorkspaceEdit {
        if (edit == null) return WorkspaceEdit.EMPTY
        val perFile = LinkedHashMap<Path, MutableList<JsonElement>>()
        val created = LinkedHashSet<Path>()
        edit.obj("changes")?.forEach { (uri, edits) ->
            val path = pathOf(uri) ?: return@forEach
            perFile.getOrPut(path) { ArrayList() } += edits.arr().orEmpty()
        }
        for (change in edit.arr("documentChanges").orEmpty()) {
            val c = change.obj() ?: continue
            when (c.str("kind")) {
                "create" -> pathOf(c.str("uri") ?: continue)?.let { created.add(it) }
                "rename", "delete" -> Unit
                null -> {
                    val path = pathOf(c.obj("textDocument")?.str("uri") ?: continue) ?: continue
                    perFile.getOrPut(path) { ArrayList() } += c.arr("edits").orEmpty()
                }
            }
        }
        var result = WorkspaceEdit.EMPTY
        for (path in created) if (path !in perFile) result += WorkspaceEdit.createFile(path.toString(), "")
        for ((path, edits) in perFile) {
            if (path in created) {
                val text = applyEdits("", textEdits(JsonArray(edits), LineIndex("")))
                result += WorkspaceEdit.createFile(path.toString(), text)
                continue
            }
            val text = textOf(path) ?: continue
            val converted = textEdits(JsonArray(edits), LineIndex(text))
            if (converted.isEmpty()) continue
            val file = if (focal != null && samePath(focal.path, path)) focal else LspFile(path)
            result += WorkspaceEdit(mapOf(file to converted))
        }
        return result
    }

    /** [edits] (all relative to [text]) applied to it. */
    fun applyEdits(text: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) {
            val s = e.offset.coerceIn(0, sb.length)
            val end = (e.offset + e.oldLength).coerceIn(s, sb.length)
            sb.replace(s, end, e.newText.toString())
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------------- locations

    /** `Location | Location[] | LocationLink[]` as navigation targets. */
    fun locations(result: JsonElement?, textOf: (Path) -> String?): List<NavigationTarget> {
        val items = when (result) {
            is JsonArray -> result.toList()
            is JsonObject -> listOf(result)
            else -> emptyList()
        }
        return items.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val uri = o.str("targetUri") ?: o.str("uri") ?: return@mapNotNull null
            val range = o["targetSelectionRange"] ?: o["range"] ?: return@mapNotNull null
            val path = pathOf(uri) ?: return@mapNotNull null
            val text = textOf(path) ?: return@mapNotNull null
            val lines = LineIndex(text)
            val offset = lines.toRange(range)?.start ?: return@mapNotNull null
            NavigationTarget(path.toString(), offset, "${path.fileName}:${lines.line(offset) + 1}")
        }.distinctBy { it.path to it.offset }
    }

    /** `Location[]` as (path, range) pairs. */
    fun ranges(result: JsonElement?, textOf: (Path) -> String?): List<Pair<Path, TextRange>> {
        val indexes = HashMap<Path, LineIndex?>()
        return result.arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val path = pathOf(o.str("uri") ?: return@mapNotNull null) ?: return@mapNotNull null
            val lines = indexes.getOrPut(path) { textOf(path)?.let(::LineIndex) } ?: return@mapNotNull null
            val range = lines.toRange(o["range"]) ?: return@mapNotNull null
            path to range
        }
    }

    // ---------------------------------------------------------------------------------------------- text

    fun readText(path: Path): String? =
        runCatching { if (Files.isRegularFile(path)) String(Files.readAllBytes(path), Charsets.UTF_8) else null }.getOrNull()

    fun samePath(a: String, b: Path): Boolean =
        runCatching { java.nio.file.Paths.get(a).toAbsolutePath().normalize() == b.toAbsolutePath().normalize() }.getOrDefault(false)

    /** The identifier around [offset], for a hover's name and a rename without `prepareRename`. */
    fun wordAt(text: CharSequence, offset: Int): TextRange? {
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && part(text[start - 1])) start--
        while (end < text.length && part(text[end])) end++
        return if (end > start) TextRange(start, end) else null
    }

    fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull
}

/** A project file named by an edit the server sent, for [WorkspaceEdit]'s keys. Read lazily from disk. */
internal class LspFile(private val nio: Path) : VirtualFile {
    override val path: String = nio.toString()
    override val name: String get() = nio.fileName?.toString() ?: path
    override val isDirectory: Boolean get() = Files.isDirectory(nio)
    override val exists: Boolean get() = Files.exists(nio)
    override val length: Long get() = runCatching { Files.size(nio) }.getOrDefault(0L)
    override fun parent(): VirtualFile? = nio.parent?.let(::LspFile)
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = ContentHash.of(readBytes())
    override fun readBytes(): ByteArray = runCatching { Files.readAllBytes(nio) }.getOrDefault(ByteArray(0))
    override fun readText(): CharSequence = String(readBytes(), Charsets.UTF_8)
    override fun equals(other: Any?): Boolean = other is VirtualFile && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
