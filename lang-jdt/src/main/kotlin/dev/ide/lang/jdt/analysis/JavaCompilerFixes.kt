package dev.ide.lang.jdt.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.lang.jdt.dom.JdtDomNode
import dev.ide.lang.jdt.dom.JdtParsedFile
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.ExpressionStatement
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.eclipse.jdt.core.dom.VariableDeclarationStatement

/**
 * Compiler-keyed Java quick-fixes: the payoff of carrying ecj's precise problem id through as a
 * [Diagnostic.code] (see [JavaProblemCodes]). Each provider attaches to one problem family and reads the
 * structured data it needs from the live binding parse and the raw ecj problem's arguments
 * ([JdtSourceAnalyzer.ecjProblemsAt]), never from the (localized) message text.
 *
 * The applicable edit is built eagerly in [QuickFixProvider.fixes]; the engine re-runs `fixes` against a
 * fresh target at apply time, so the captured edit is always computed over the buffer the user is acting on.
 */

private val JAVA = LanguageId("java")

// ---------------------------------------------------------------------------------------------------
// Remove unused local variable
// ---------------------------------------------------------------------------------------------------

/** On `LocalVariableIsNeverUsed`, delete the (single-fragment) local variable declaration statement. */
class RemoveUnusedLocalQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaProblemCodes.UNUSED_LOCAL)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val pf = target.parsed as? JdtParsedFile ?: return emptyList()
        val node = jdtNodeAt(pf, diagnostic.range.start) ?: return emptyList()
        // Only a real local-variable declaration with one fragment; a method parameter
        // (SingleVariableDeclaration) or a `int a, b;` group is left alone (signature / sibling-var risk).
        val vds = node.ancestorOfType(VariableDeclarationStatement::class.java) ?: return emptyList()
        if (vds.fragments().size != 1) return emptyList()
        val name = (vds.fragments()[0] as VariableDeclarationFragment).name.identifier
        val range = fullLineRange(pf.text(), vds.startPosition, vds.startPosition + vds.length)
        return listOf(simpleFix("Remove unused local variable '$name'", target.file, DocumentEdit(range.start, range.length(), "")))
    }
}

// ---------------------------------------------------------------------------------------------------
// Remove unused private member
// ---------------------------------------------------------------------------------------------------

/** On `UnusedPrivate*`, delete the unused private method (or single-fragment field) declaration. */
class RemoveUnusedMemberQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaProblemCodes.UNUSED_PRIVATE)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val pf = target.parsed as? JdtParsedFile ?: return emptyList()
        val node = jdtNodeAt(pf, diagnostic.range.start) ?: return emptyList()
        val method = node.ancestorOfType(MethodDeclaration::class.java)
        val field = node.ancestorOfType(FieldDeclaration::class.java)
        val (decl, label) = when {
            // Deleting a whole nested type is too aggressive for an automatic fix; methods/fields are fine.
            method != null && (field == null || method.startPosition >= field.startPosition) ->
                method to (if (method.isConstructor) "constructor" else "method '${method.name.identifier}'")
            field != null && field.fragments().size == 1 ->
                field to "field '${(field.fragments()[0] as VariableDeclarationFragment).name.identifier}'"
            else -> return emptyList()
        }
        val range = fullLineRange(pf.text(), decl.startPosition, decl.startPosition + decl.length)
        return listOf(simpleFix("Remove unused $label", target.file, DocumentEdit(range.start, range.length(), "")))
    }
}

// ---------------------------------------------------------------------------------------------------
// Unhandled checked exception: add to `throws`, or surround with try/catch
// ---------------------------------------------------------------------------------------------------

/** On `UnhandledException`, add the unhandled checked exception(s) to the enclosing method's `throws`. */
class AddExceptionToThrowsQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaProblemCodes.UNHANDLED_EXCEPTION)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val analyzer = target.resolver as? JdtSourceAnalyzer ?: return emptyList()
        val pf = target.parsed as? JdtParsedFile ?: return emptyList()
        val exceptions = unhandledExceptionsAt(analyzer, target, diagnostic.range)
        if (exceptions.isEmpty()) return emptyList()
        val node = jdtNodeAt(pf, diagnostic.range.start) ?: return emptyList()
        val method = node.ancestorOfType(MethodDeclaration::class.java) ?: return emptyList()
        val text = pf.text()

        // Exceptions not already declared on the method (compare simple names, good enough for the buffer).
        val declared = method.thrownExceptionTypes().filterIsInstance<Type>()
            .map { text.subSequence(it.startPosition, it.startPosition + it.length).toString().substringAfterLast('.') }.toSet()
        val tbl = importTable(pf)
        val imports = LinkedHashSet<String>()
        val toAdd = exceptions.filter { it.substringAfterLast('.') !in declared }
            .map { shortenType(it, tbl, imports) }.distinct()
        if (toAdd.isEmpty()) return emptyList()

        val edits = ArrayList<DocumentEdit>()
        val existing = method.thrownExceptionTypes().filterIsInstance<Type>()
        if (existing.isNotEmpty()) {
            val last = existing.last()
            edits.add(DocumentEdit(last.startPosition + last.length, 0, ", " + toAdd.joinToString(", ")))
        } else {
            val anchor = method.body?.startPosition ?: (method.startPosition + method.length)
            var i = (anchor - 1).coerceIn(0, text.length - 1)
            while (i > 0 && text[i] != ')') i--
            edits.add(DocumentEdit(i + 1, 0, " throws " + toAdd.joinToString(", ")))
        }
        if (imports.isNotEmpty()) edits.add(DocumentEdit(importInsertOffset(pf), 0, imports.joinToString("") { "import $it;\n" }))

        val title = if (toAdd.size == 1) "Add '${toAdd[0]}' to throws declaration" else "Add exceptions to throws declaration"
        return listOf(eagerFix(title, target.file, edits))
    }
}

/** On `UnhandledException`, wrap the enclosing statement in `try { … } catch (E e) { … }` for the real type. */
class SurroundWithTryCatchForExceptionQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaProblemCodes.UNHANDLED_EXCEPTION)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val analyzer = target.resolver as? JdtSourceAnalyzer ?: return emptyList()
        val exceptions = unhandledExceptionsAt(analyzer, target, diagnostic.range)
        if (exceptions.isEmpty()) return emptyList()
        val parsed = target.parsed
        val stmt = enclosingStatement(parsed.nodeAt(diagnostic.range.start)) ?: return emptyList()
        val text = parsed.text()
        val tbl = importTable(parsed)
        val imports = LinkedHashSet<String>()
        val caught = exceptions.map { shortenType(it, tbl, imports) }.distinct().joinToString(" | ")
        val indent = lineIndent(text, stmt.range.start)
        val inner = "$indent    "
        val stmtText = text.subSequence(stmt.range.start, stmt.range.end).toString().replace("\n", "\n    ")
        val wrapped = buildString {
            append("try {\n")
            append(inner).append(stmtText).append('\n')
            append(indent).append("} catch ($caught e) {\n")
            append(inner).append("e.printStackTrace();\n")
            append(indent).append('}')
        }
        val edits = ArrayList<DocumentEdit>()
        edits.add(DocumentEdit(stmt.range.start, stmt.range.length, wrapped))
        if (imports.isNotEmpty()) edits.add(DocumentEdit(importInsertOffset(parsed), 0, imports.joinToString("") { "import $it;\n" }))
        return listOf(eagerFix("Surround with try/catch", target.file, edits))
    }
}

// ---------------------------------------------------------------------------------------------------
// Create method from usage
// ---------------------------------------------------------------------------------------------------

/**
 * On `UndefinedMethod`, create a stub method in the enclosing type when the call targets *this* type
 * (`foo(args)` / `this.foo(args)`). Parameter types come from the call's argument types (ecj's problem
 * arguments); the return type is `void` for a statement call, else `Object`. Inserted after the enclosing
 * method, `private` (and `static` when the call site is static), with a `throw new
 * UnsupportedOperationException()` body so it compiles regardless of the return type.
 */
class CreateMethodFromUsageQuickFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaProblemCodes.UNDEFINED_METHOD)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val analyzer = target.resolver as? JdtSourceAnalyzer ?: return emptyList()
        val pf = target.parsed as? JdtParsedFile ?: return emptyList()
        // arguments = [ownerFqn, methodName, paramType0, paramType1, ...]
        val args = analyzer.ecjProblemsAt(target.file, pf.text(), diagnostic.range)
            .firstOrNull { it.id == IProblem.UndefinedMethod }?.arguments ?: return emptyList()
        if (args.size < 2) return emptyList()
        val ownerSimple = args[0].substringAfterLast('.')
        val name = args[1]
        // ecj packs the parameter types into a single comma-joined argument, e.g. "int, java.lang.String".
        val paramTypes = splitTopLevel(args.getOrNull(2) ?: "")

        val node = jdtNodeAt(pf, diagnostic.range.start) ?: return emptyList()
        val enclosingType = node.ancestorOfType(AbstractTypeDeclaration::class.java) ?: return emptyList()
        // Only create in the current type (creating in another file is a separate, larger fix).
        if (enclosingType.name.identifier != ownerSimple) return emptyList()
        val enclosingMethod = node.ancestorOfType(MethodDeclaration::class.java) ?: return emptyList()

        val text = pf.text()
        val tbl = importTable(pf)
        val imports = LinkedHashSet<String>()
        val params = paramTypes.mapIndexed { i, t -> "${renderTypeName(t, tbl, imports)} arg$i" }.joinToString(", ")
        val returnsVoid = (node.callInvocation()?.parent is ExpressionStatement)
        val returnType = if (returnsVoid) "void" else "Object"
        val isStatic = Modifier.isStatic(enclosingMethod.modifiers)
        val modifiers = if (isStatic) "private static" else "private"
        val indent = lineIndent(text, enclosingMethod.startPosition)

        val method = buildString {
            append("\n\n").append(indent).append("$modifiers $returnType $name($params) {\n")
            append(indent).append("    throw new UnsupportedOperationException(\"TODO: $name\");\n")
            append(indent).append("}")
        }
        val insertAt = (enclosingMethod.startPosition + enclosingMethod.length).coerceIn(0, text.length)
        val edits = ArrayList<DocumentEdit>()
        edits.add(DocumentEdit(insertAt, 0, method))
        if (imports.isNotEmpty()) edits.add(DocumentEdit(importInsertOffset(pf), 0, imports.joinToString("") { "import $it;\n" }))
        val sig = "$name(${paramTypes.joinToString(", ") { it.substringAfterLast('.') }})"
        return listOf(eagerFix("Create method '$sig'", target.file, edits))
    }
}

// ---------------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------------

/** The distinct fully-qualified checked-exception types ecj flags as unhandled across [range]. */
private fun unhandledExceptionsAt(analyzer: JdtSourceAnalyzer, target: AnalysisTarget, range: TextRange): List<String> =
    analyzer.ecjProblemsAt(target.file, target.parsed.text(), range)
        .filter { JavaProblemCodes.codeFor(it.id) == JavaProblemCodes.UNHANDLED_EXCEPTION }
        .mapNotNull { it.arguments?.firstOrNull() }
        .distinct()

/** The JDT [ASTNode] at [offset], or null when the file isn't a JDT tree. */
private fun jdtNodeAt(pf: JdtParsedFile, offset: Int): ASTNode? = (pf.nodeAt(offset) as? JdtDomNode)?.node

/** Nearest ancestor (inclusive) of the given JDT node type. */
private fun <T : ASTNode> ASTNode.ancestorOfType(type: Class<T>): T? {
    var n: ASTNode? = this
    while (n != null) {
        if (type.isInstance(n)) return type.cast(n)
        n = n.parent
    }
    return null
}

/** From the SimpleName under the caret, the enclosing method-invocation node (for return-context checks). */
private fun ASTNode.callInvocation(): ASTNode? {
    var n: ASTNode? = this
    while (n != null) {
        val t = n.nodeType
        if (t == ASTNode.METHOD_INVOCATION || t == ASTNode.SUPER_METHOD_INVOCATION) return n
        n = n.parent
    }
    return null
}

/** Split a comma-joined type list at top-level commas only (commas inside `<>`/`()`/`[]` are kept). */
private fun splitTopLevel(s: String): List<String> {
    if (s.isBlank()) return emptyList()
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var depth = 0
    for (c in s) when (c) {
        '<', '(', '[' -> { depth++; sb.append(c) }
        '>', ')', ']' -> { depth--; sb.append(c) }
        ',' -> if (depth == 0) { out.add(sb.toString().trim()); sb.setLength(0) } else sb.append(c)
        else -> sb.append(c)
    }
    if (sb.isNotBlank()) out.add(sb.toString().trim())
    return out
}

/** Expand [[start], [end]) to whole lines: back to the line start, forward past the trailing newline. */
private fun fullLineRange(text: CharSequence, start: Int, end: Int): TextRange {
    var s = start.coerceIn(0, text.length)
    var e = end.coerceIn(s, text.length)
    while (s > 0 && text[s - 1] != '\n') s--
    while (e < text.length && text[e] != '\n') e++
    if (e < text.length) e++ // swallow the trailing newline
    return TextRange(s, e)
}

private fun TextRange.length() = end - start

/** Shorten a (possibly generic) type name string, collecting needed imports; primitives pass through. */
private val FQN_NAME = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""")
private fun renderTypeName(qn: String, tbl: ImportTable, imports: MutableSet<String>): String =
    FQN_NAME.replace(qn) { m -> shortenType(m.value, tbl, imports) }

/** A fix whose single edit replaces nothing else; built eagerly, returned verbatim at apply time. */
private fun simpleFix(title: String, file: dev.ide.vfs.VirtualFile, edit: DocumentEdit): QuickFix =
    eagerFix(title, file, listOf(edit))

private fun eagerFix(fixTitle: String, file: dev.ide.vfs.VirtualFile, edits: List<DocumentEdit>): QuickFix =
    object : QuickFix {
        override val title = fixTitle
        override val kind = CodeActionKind.QUICK_FIX
        private val edit = WorkspaceEdit(mapOf(file to edits))
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = edit
    }
