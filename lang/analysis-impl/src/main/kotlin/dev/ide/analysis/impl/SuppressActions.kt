package dev.ide.analysis.impl

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.KotlinNodeKinds
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.incremental.DocumentEdit

/**
 * The id a suppression names for a diagnostic code. An Android lint check's code goes by lint's id, the one
 * `@SuppressLint` and `//noinspection` take in Android Studio (`android.newApi` is `NewApi`); any other code is
 * its own id. [SuppressionFilter] accepts either spelling.
 */
internal object SuppressIds {
    private val LINT = mapOf(
        "android.newApi" to "NewApi",
        "android.missingPermission" to "MissingPermission",
    )

    fun idFor(code: String): String = LINT[code] ?: code

    fun isLint(code: String): Boolean = code in LINT
}

/**
 * "Suppress" actions for a warning in a Java or Kotlin file, as Android Studio offers them: for the line
 * (`//noinspection id` above it), for the enclosing function, property or class (an annotation on it), and for
 * the whole file (`@file:Suppress`, Kotlin only). The annotation is `@SuppressLint` for an Android lint check,
 * else `@Suppress` in Kotlin and `@SuppressWarnings` in Java; one already on the declaration gets the id added
 * rather than a second annotation. Errors are not offered: hiding one does not make the code compile.
 *
 * Built on the language-neutral tree and the source text alone, so both editors share it.
 */
internal object SuppressActions {

    private const val SUPPRESS_LINT = "android.annotation.SuppressLint"

    fun forDiagnostic(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val code = diagnostic.code ?: return emptyList()
        if (diagnostic.severity == Severity.ERROR) return emptyList()
        val kotlin = target.file.name.let { it.endsWith(".kt") || it.endsWith(".kts") }
        if (!kotlin && !target.file.name.endsWith(".java")) return emptyList()
        val id = SuppressIds.idFor(code)
        val at = diagnostic.range.start
        val out = ArrayList<QuickFix>()
        out += action("Suppress \"$id\" for line") { text, _ -> lineComment(text, at, id) }
        // A local variable, then the function, property or field holding the warning, then the class.
        for (scope in SCOPES) {
            val kind = scope(target.parsed, at)?.let { scopeLabel(it.kind) } ?: continue
            out += action("Suppress \"$id\" for $kind") { text, parsed ->
                val node = scope(parsed, at) ?: return@action null
                annotate(text, node.range.start, id, code, kotlin)
            }
        }
        if (kotlin) out += action("Suppress \"$id\" for file") { text, _ -> fileAnnotation(text, id) }
        return out
    }

    private fun action(title: String, edits: (String, dev.ide.lang.dom.ParsedFile) -> List<DocumentEdit>?): QuickFix =
        object : QuickFix {
            override val title = title
            override val kind = CodeActionKind.QUICK_FIX
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                val parsed = ctx.target.parsed
                val list = edits(parsed.text().toString(), parsed)?.takeIf { it.isNotEmpty() } ?: return WorkspaceEdit.EMPTY
                return WorkspaceEdit(mapOf(ctx.target.file to list))
            }
        }

    private val MEMBER_KINDS = setOf(NodeKind.METHOD_DECL, NodeKind.FIELD_DECL, KotlinNodeKinds.PROPERTY)

    /** The declarations a suppression can sit on, innermost first; each answers null when there is none. */
    private val SCOPES: List<(dev.ide.lang.dom.ParsedFile, Int) -> DomNode?> = listOf(
        // A local variable, only when the warning is in it rather than in a function declared inside it.
        { parsed, at -> enclosing(parsed.nodeAt(at), MEMBER_KINDS + NodeKind.LOCAL_VAR)?.takeIf { it.kind == NodeKind.LOCAL_VAR } },
        { parsed, at -> enclosing(parsed.nodeAt(at), MEMBER_KINDS) },
        { parsed, at -> enclosing(parsed.nodeAt(at), setOf(NodeKind.CLASS_DECL)) },
    )

    private fun scopeLabel(kind: NodeKind): String = when (kind) {
        NodeKind.METHOD_DECL -> "function"
        NodeKind.FIELD_DECL -> "field"
        KotlinNodeKinds.PROPERTY -> "property"
        NodeKind.LOCAL_VAR -> "variable"
        else -> "class"
    }

    private fun enclosing(start: DomNode?, kinds: Set<NodeKind>): DomNode? {
        var n = start
        while (n != null && n.kind !in kinds) n = n.parent
        return n
    }

    /** `//noinspection id` on its own line above [offset]'s, or the id added to one already there. */
    private fun lineComment(text: String, offset: Int, id: String): List<DocumentEdit> {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (offset == 0 || it < 0) 0 else it + 1 }
        val indent = text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        if (lineStart > 0) {
            val prevStart = text.lastIndexOf('\n', lineStart - 2).let { if (it < 0) 0 else it + 1 }
            val prev = text.substring(prevStart, lineStart - 1)
            val m = Regex("""^\s*//\s*noinspection\s+(.*)$""").find(prev)
            if (m != null) {
                if (id in m.groupValues[1].split(Regex("[\\s,]+"))) return emptyList()
                return listOf(DocumentEdit(lineStart - 1, 0, " $id"))
            }
        }
        return listOf(DocumentEdit(lineStart, 0, "$indent//noinspection $id\n"))
    }

    /**
     * The suppression annotation on the declaration starting at [declStart]: the id added to a matching one
     * already there, else a new annotation on its own line above the declaration (after its doc comment).
     */
    private fun annotate(text: String, declStart: Int, id: String, code: String, kotlin: Boolean): List<DocumentEdit>? {
        val lint = SuppressIds.isLint(code)
        val name = when {
            lint -> "SuppressLint"
            kotlin -> "Suppress"
            else -> "SuppressWarnings"
        }
        val start = skipComments(text, declStart)
        // The annotations leading the declaration, one by one.
        val annotation = Regex("""\G\s*@([\w.]+)\s*(\((?:[^()"]|"[^"]*"|\([^()]*\))*\))?""")
        var at = start
        while (true) {
            val m = annotation.find(text, at) ?: break
            if (m.range.first != at) break
            val args = m.groups[2]
            if (m.groupValues[1].substringAfterLast('.') == name && args != null) {
                if ("\"$id\"" in args.value) return null
                return listOf(addArgument(args.value, args.range.first, id, kotlin))
            }
            at = m.range.last + 1
        }
        val indent = text.substring(text.lastIndexOf('\n', start - 1) + 1, start).takeWhile { it == ' ' || it == '\t' }
        val edits = mutableListOf(DocumentEdit(start, 0, "@$name(\"$id\")\n$indent"))
        if (lint) importEdit(text, SUPPRESS_LINT, kotlin)?.let(edits::add)
        return edits
    }

    /** `("a")` → `("a", "id")`; Java's `{"a"}` → `{"a", "id"}`, and a single Java value becomes an array. */
    private fun addArgument(args: String, argsStart: Int, id: String, kotlin: Boolean): DocumentEdit {
        val inner = args.substring(1, args.length - 1).trim()
        val close = args.lastIndexOf(')')
        return when {
            inner.isEmpty() -> DocumentEdit(argsStart + close, 0, "\"$id\"")
            !kotlin && inner.endsWith("}") -> DocumentEdit(argsStart + args.lastIndexOf('}'), 0, ", \"$id\"")
            !kotlin -> DocumentEdit(argsStart, args.length, "({$inner, \"$id\"})")
            else -> DocumentEdit(argsStart + close, 0, ", \"$id\"")
        }
    }

    /** `@file:Suppress("id")` above the package line, or the id added to one already there. */
    private fun fileAnnotation(text: String, id: String): List<DocumentEdit>? {
        val existing = Regex("""@file\s*:\s*Suppress\s*(\((?:[^()"]|"[^"]*")*\))""").find(text)
        if (existing != null) {
            val args = existing.groups[1]!!
            if ("\"$id\"" in args.value) return null
            return listOf(addArgument(args.value, args.range.first, id, kotlin = true))
        }
        return listOf(DocumentEdit(0, 0, "@file:Suppress(\"$id\")\n\n"))
    }

    /** The offset of the first non-comment, non-blank text at or after [from]. */
    private fun skipComments(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it + 1 }
                else -> return i
            }
        }
        return i
    }

    /** An import of [fqn] after the last import (or the package line), unless the file already has one. */
    private fun importEdit(text: String, fqn: String, kotlin: Boolean): DocumentEdit? {
        val end = if (kotlin) "" else ";"
        if (Regex("""(?m)^\s*import\s+${Regex.escape(fqn)}\s*$end""").containsMatchIn(text)) return null
        val last = Regex("""(?m)^\s*import\s+[^\n]*$""").findAll(text).lastOrNull()
            ?: Regex("""(?m)^\s*package\s+[^\n]*$""").find(text)
            ?: return DocumentEdit(0, 0, "import $fqn$end\n\n")
        val blank = if (last.value.trimStart().startsWith("package")) "\n" else ""
        return DocumentEdit(last.range.last + 1, 0, "\n${blank}import $fqn$end")
    }
}
