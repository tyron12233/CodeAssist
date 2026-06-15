package dev.ide.core

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Codes
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit

/**
 * Built-in Java code actions, the editor-side complement to [JavaAnalyzers]. Two kinds, both
 * speaking the analysis-api [QuickFix] currency and the neutral DOM (never JDT types):
 *
 *  - [QuickFixProvider]s keyed by [Diagnostic.code]: "Add import" on an unresolved reference, "Remove
 *    unused import" on the unused-import warning. The compiler/analyzer stays fix-agnostic; these attach
 *    by code.
 *  - [ActionProvider]s keyed by caret/selection position: "Introduce local variable", "Surround with
 *    try/catch". No diagnostic needed; the engine unions them with the quick-fixes in
 *    `AnalysisService.editorActionsAt`.
 *
 * Registered with the [dev.ide.analysis.impl.AnalysisEngine] in [IdeServices]; surfaced to the editor
 * lightbulb / Alt-Enter menu through `IdeBackend.actionsAt` / `applyAction`.
 */
private val JAVA = LanguageId("java")
private val CLASS_NAMES = IndexId("java.classNames")

// ---------------------------------------------------------------------------------------------------
// Quick fixes (diagnostic-keyed)
// ---------------------------------------------------------------------------------------------------

/**
 * On an unresolved reference whose text is a type name (Uppercase), offer "Import <fqn>" for each
 * matching fully-qualified type from the class-name index: the same auto-import edit completion makes,
 * but reachable from the lightbulb on an already-typed unresolved name.
 */
class AddImportQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf(Codes.UNRESOLVED_REFERENCE)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val text = target.parsed.text()
        val name = text.subSequence(
            diagnostic.range.start.coerceIn(0, text.length),
            diagnostic.range.end.coerceIn(diagnostic.range.start.coerceIn(0, text.length), text.length),
        ).toString().trim()
        // A plausible type simple-name only; skip unresolved variables/methods (lowercase).
        if (name.isEmpty() || !name[0].isJavaIdentifierStart() || !name[0].isUpperCase()) return emptyList()
        if (!name.all { it.isJavaIdentifierPart() }) return emptyList()

        val candidates = target.index.fuzzy<ClassNameValue>(CLASS_NAMES, name, 50)
            .map { it.value.fqn }
            .filter { it.substringAfterLast('.') == name }
            .filter { it.substringBeforeLast('.', "").let { p -> p.isNotEmpty() && p != "java.lang" } }
            .distinct()
            .sorted()
            .take(8)
            .toList()

        return candidates.map { fqn ->
            object : QuickFix {
                override val title = "Import $fqn"
                override val kind = CodeActionKind.QUICK_FIX
                override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                    val t = ctx.target.parsed.text().toString()
                    if (Regex("(?m)^\\s*import\\s+${Regex.escape(fqn)}\\s*;").containsMatchIn(t)) return WorkspaceEdit.EMPTY
                    val off = importInsertOffset(ctx.target.parsed)
                    return WorkspaceEdit.of(ctx.target.file, DocumentEdit(off, 0, "import $fqn;\n"))
                }
            }
        }
    }
}

/** On the unused-import warning, delete the whole import line. Keyed on [UnusedImportAnalyzer]'s code. */
class RemoveUnusedImportQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf("java.unusedImport")

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> = listOf(
        object : QuickFix {
            override val title = "Remove unused import"
            override val kind = CodeActionKind.QUICK_FIX
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                val text = ctx.target.parsed.text()
                var start = diagnostic.range.start.coerceIn(0, text.length)
                var end = diagnostic.range.end.coerceIn(start, text.length)
                while (start > 0 && text[start - 1] != '\n') start--   // back to the line start
                while (end < text.length && text[end] != '\n') end++   // forward to the line end
                if (end < text.length) end++                            // and swallow the trailing newline
                return WorkspaceEdit.of(ctx.target.file, DocumentEdit(start, end - start, ""))
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------
// Intentions (position-keyed)
// ---------------------------------------------------------------------------------------------------

/**
 * "Introduce local variable": extract the expression at the caret (or the selected expression) into a
 * `<Type> <name> = <expr>;` declared just above the enclosing statement, and replace the expression with
 * `<name>`. The declared type is the expression's resolved type (a method call's return type, a `new`'s
 * class), rendered with its simple name plus an auto-`import`, or fully qualified when the simple name is
 * already taken. `var` is used only as a last resort when the type cannot be named (unresolved, `void`,
 * `null`, or otherwise unwritable).
 *
 * The applicability check ([actions]) stays on the syntax-only tree (it locates the expression
 * and its statement via [ParsedFile.nodeAt]); the binding-level type resolution happens in [computeEdits],
 * paid only when the intention is selected.
 */
class IntroduceVariableActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val parsed = target.parsed
        val text = parsed.text()
        val expr = expressionToExtract(parsed, range) ?: return emptyList()
        val stmt = enclosingStatement(expr) ?: return emptyList()
        if (expr.range == stmt.range) return emptyList() // the whole statement isn't an expression to extract
        val exprText = text.subSequence(expr.range.start, expr.range.end).toString()
        if (exprText.isBlank()) return emptyList()
        val indent = lineIndent(text, stmt.range.start)
        val name = freshVariableName(exprText, text)
        return listOf(
            object : QuickFix {
                override val title = "Introduce local variable '$name'"
                override val kind = CodeActionKind.REFACTOR
                override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                    val imports = LinkedHashSet<String>()
                    val typeName = ctx.target.resolver.resolveType(expr)
                        ?.let { renderType(it, importTable(parsed), imports) } ?: "var"
                    val decl = "$typeName $name = $exprText;\n$indent"
                    // Replace-then-insert order: consumers apply descending-by-offset stably, so when the
                    // expression starts exactly at the statement (`foo();`) the replace still lands first; the
                    // import edit sits at a lower offset (file top) and applies last, shifting nothing below it.
                    val edits = ArrayList<DocumentEdit>()
                    edits.add(DocumentEdit(expr.range.start, expr.range.length, name))
                    edits.add(DocumentEdit(stmt.range.start, 0, decl))
                    if (imports.isNotEmpty()) {
                        edits.add(DocumentEdit(importInsertOffset(parsed), 0, imports.joinToString("") { "import $it;\n" }))
                    }
                    return WorkspaceEdit(mapOf(ctx.target.file to edits))
                }
            },
        )
    }
}

/** "Surround with try/catch": wrap the enclosing statement in `try { … } catch (Exception e) { … }`. */
class SurroundWithTryCatchActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val parsed = target.parsed
        val text = parsed.text()
        val stmt = enclosingStatement(parsed.nodeAt(range.start)) ?: return emptyList()
        val indent = lineIndent(text, stmt.range.start)
        val inner = "$indent    "
        val stmtText = text.subSequence(stmt.range.start, stmt.range.end).toString()
        return listOf(
            object : QuickFix {
                override val title = "Surround with try/catch"
                override val kind = CodeActionKind.REFACTOR
                override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                    val body = stmtText.replace("\n", "\n    ") // re-indent the wrapped statement one level
                    val wrapped = buildString {
                        append("try {\n")
                        append(inner).append(body).append('\n')
                        append(indent).append("} catch (Exception e) {\n")
                        append(inner).append("e.printStackTrace();\n")
                        append(indent).append('}')
                    }
                    return WorkspaceEdit.of(ctx.target.file, DocumentEdit(stmt.range.start, stmt.range.length, wrapped))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Shared neutral-DOM helpers
// ---------------------------------------------------------------------------------------------------

/** Expression-ish node kinds, recognised structurally so this stays backend-neutral (no JDT types). */
private fun isExpression(n: DomNode): Boolean = n.kind == NodeKind.METHOD_CALL ||
    n.kind == NodeKind.MEMBER_ACCESS || n.kind == NodeKind.NAME_REF || n.kind == NodeKind.LITERAL ||
    n.kind.id.endsWith("Expression") || n.kind.id.endsWith("Literal") || n.kind.id == "ClassInstanceCreation"

/** For a bare caret, only "interesting" expressions are worth extracting — not a lone name reference. */
private fun isExtractableAtCaret(n: DomNode): Boolean =
    isExpression(n) && n.kind != NodeKind.NAME_REF

/**
 * The expression to extract for [range]: with a selection, the smallest expression covering it; with a
 * bare caret, the innermost extractable expression at the caret, stopping at the statement boundary.
 */
private fun expressionToExtract(parsed: ParsedFile, range: TextRange): DomNode? {
    if (range.start != range.end) {
        var n: DomNode? = parsed.nodeAt(range.start)
        while (n != null) {
            if (n.range.start <= range.start && n.range.end >= range.end && isExpression(n)) return n
            n = n.parent
        }
        return null
    }
    var n: DomNode? = parsed.nodeAt(range.start)
    while (n != null) {
        if (isExtractableAtCaret(n)) return n
        if (n.parent?.kind == NodeKind.BLOCK) return null
        n = n.parent
    }
    return null
}

/** The statement enclosing [node] — the ancestor that is a direct child of a [NodeKind.BLOCK]. */
private fun enclosingStatement(node: DomNode): DomNode? {
    var n: DomNode? = node
    while (n != null) {
        if (n.parent?.kind == NodeKind.BLOCK) return n
        n = n.parent
    }
    return null
}

/** The leading whitespace of the line containing [offset]. */
private fun lineIndent(text: CharSequence, offset: Int): String {
    var start = offset.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] != '\n') start--
    val sb = StringBuilder()
    var i = start
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) { sb.append(text[i]); i++ }
    return sb.toString()
}

/** Offset of a fresh line just after the last import (else the package decl, else the file start). */
private fun importInsertOffset(parsed: ParsedFile): Int {
    val text = parsed.text()
    var anchor = -1
    for (n in parsed.nodesIn(parsed.range)) if (n.kind == NodeKind.IMPORT_DECL) anchor = maxOf(anchor, n.range.end)
    if (anchor < 0) for (n in parsed.nodesIn(parsed.range)) if (n.kind == NodeKind.PACKAGE_DECL) anchor = maxOf(anchor, n.range.end)
    if (anchor < 0) return 0
    var i = anchor.coerceIn(0, text.length)
    while (i < text.length && text[i] != '\n') i++  // to end of the anchor's line
    if (i < text.length) i++                         // past its newline → the start of a fresh line
    return i
}

/** The file's package + type imports, used to decide simple-name-vs-qualified when naming a type. */
private class ImportTable(
    val packageName: String,
    /** simple name → its single (non-static) imported fully-qualified name. */
    val singleImports: Map<String, String>,
    /** packages imported on-demand (`import a.b.*;`). */
    val starImports: Set<String>,
)

/** Scan the DOM once for the package declaration and (non-static) imports. */
private fun importTable(parsed: ParsedFile): ImportTable {
    var pkg = ""
    val single = HashMap<String, String>()
    val star = HashSet<String>()
    for (n in parsed.nodesIn(parsed.range)) when (n.kind) {
        NodeKind.PACKAGE_DECL -> pkg = n.text().toString().substringAfter("package").substringBefore(';').trim()
        NodeKind.IMPORT_DECL -> {
            var t = n.text().toString().trim().removePrefix("import").trim().removeSuffix(";").trim()
            if (t.startsWith("static ")) continue // static member/star import — irrelevant to type naming
            if (t.endsWith(".*")) star.add(t.removeSuffix(".*")) else single[t.substringAfterLast('.')] = t
        }
        else -> {}
    }
    return ImportTable(pkg, single, star)
}

/**
 * Render [type] as a Java source type, collecting the imports it needs into [imports]. Every fully-qualified
 * name inside the type string (the type itself plus any generic arguments, e.g. `java.util.List<java.lang.String>`)
 * is shortened to its simple name + an import, unless that simple name is ambiguous (already bound to a
 * different import) in which case it stays fully qualified. Returns null for types that can't be written as a
 * declaration (`void`/`null`/capture/intersection/anonymous) so the caller can fall back to `var`.
 */
private val FQN = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""")

private fun renderType(type: dev.ide.lang.resolve.TypeRef, tbl: ImportTable, imports: MutableSet<String>): String? {
    val qn = type.qualifiedName
    if (qn.isBlank() || qn == "void" || qn == "null" || qn.contains('#') || qn.contains('&') || qn.contains("capture")) return null
    return FQN.replace(qn) { m -> shortenType(m.value, tbl, imports) }
}

/** Decide simple-name (+import) vs fully-qualified for one fully-qualified type name. */
private fun shortenType(fqn: String, tbl: ImportTable, imports: MutableSet<String>): String {
    val simple = fqn.substringAfterLast('.')
    val pkg = fqn.substringBeforeLast('.', "")
    return when {
        pkg.isEmpty() -> fqn
        pkg == "java.lang" || pkg == tbl.packageName -> simple          // implicitly in scope
        tbl.singleImports[simple] == fqn -> simple                      // exactly this type already imported
        tbl.singleImports.containsKey(simple) -> fqn                    // simple name taken by another import → qualify
        imports.any { it != fqn && it.substringAfterLast('.') == simple } -> fqn // collides with one being added
        pkg in tbl.starImports -> simple                                // covered by an on-demand import
        else -> { imports.add(fqn); simple }                            // import it, use the simple name
    }
}

/** A readable, non-clashing variable name derived from [exprText] (a method/getter name, else "value"). */
private fun freshVariableName(exprText: String, allText: CharSequence): String {
    val callName = Regex("""([A-Za-z_$][\w$]*)\s*\(""").find(exprText)?.groupValues?.get(1)
    val ident = callName ?: exprText.trim().substringAfterLast('.').takeWhile { it.isJavaIdentifierPart() }
    val cleaned = ident.ifBlank { "value" }
    val stripped = when {
        cleaned.startsWith("get") && cleaned.length > 3 -> cleaned.removePrefix("get")
        cleaned.startsWith("is") && cleaned.length > 2 && cleaned[2].isUpperCase() -> cleaned.removePrefix("is")
        else -> cleaned
    }
    val base = stripped.replaceFirstChar { it.lowercaseChar() }
        .filter { it.isJavaIdentifierPart() }
        .ifBlank { "value" }
    val safe = if (base.first().isJavaIdentifierStart()) base else "value"
    var name = safe
    var i = 1
    while (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(allText)) {
        name = "$safe${i++}"
        if (i > 99) break
    }
    return name
}
