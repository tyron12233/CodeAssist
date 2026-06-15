package dev.ide.lang.jdt.dom

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.NodeFinder
import org.eclipse.jdt.core.dom.Statement
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor

/** All ASTNode child nodes (single + list structural properties), in source order. */
internal fun childNodes(node: ASTNode): List<ASTNode> {
    val out = ArrayList<ASTNode>()
    @Suppress("UNCHECKED_CAST")
    for (prop in node.structuralPropertiesForType() as List<StructuralPropertyDescriptor>) {
        when {
            prop.isChildProperty -> (node.getStructuralProperty(prop) as? ASTNode)?.let(out::add)
            prop.isChildListProperty -> (node.getStructuralProperty(prop) as? List<*>)?.forEach { (it as? ASTNode)?.let(out::add) }
        }
    }
    return out.sortedBy { it.startPosition }
}

/**
 * Error-tolerant tree over a JDT [CompilationUnit]: it always covers the file (JDT recovers broken
 * regions), carries [diagnostics] from JDT's problems, and resolves [nodeAt] via JDT's [NodeFinder].
 */
class JdtParsedFile(
    override val file: VirtualFile,
    override val documentVersion: Long,
    val cu: CompilationUnit,
    private val source: CharSequence,
) : ParsedFile {

    override val kind: NodeKind = NodeKind.COMPILATION_UNIT
    override val range: TextRange = TextRange(0, source.length)
    override val parent: DomNode? = null
    override val children: List<DomNode> get() = childNodes(cu).map { JdtDomNode(it, this) }

    override val diagnostics: List<Diagnostic> = computeDiagnostics()

    /**
     * JDT problems → neutral diagnostics, dropping semantic noise from statements that did not parse. A
     * statement with a syntax error can't be type-checked reliably: ecj's statement recovery routinely
     * emits a spurious *semantic* error there — e.g. an unterminated `…map(it -> 1)` recovers the inline
     * lambda as just its body, yielding "method map(…) is not applicable for the arguments (int)" on top
     * of the genuine missing-`;`. We keep every syntax problem, and drop a non-syntax problem only when it
     * falls inside a statement that itself carries a syntax error (so real type errors elsewhere survive).
     */
    private fun computeDiagnostics(): List<Diagnostic> = diagnosticsFrom(cu.problems)

    /**
     * Map an arbitrary ecj [problems] set over THIS file's text → neutral diagnostics, applying the same
     * broken-statement noise filtering as [computeDiagnostics]. Shared so a diagnostics source other than
     * the DOM's own `cu.problems` — namely the low-level compiler's `CompilationResult` problems, which
     * carry binding-level errors without the disk-environment cost of a binding-resolving DOM parse — maps
     * identically. Both are `org.eclipse.jdt.core.compiler.IProblem`, so only the source differs; this
     * routes them through one code path so the two never drift.
     */
    fun diagnosticsFrom(problems: Array<out IProblem>): List<Diagnostic> {
        val brokenStatements = problems.asSequence()
            .filter { (it.id and IProblem.Syntax) != 0 }
            .mapNotNull { enclosingStatementRange(it.sourceStart) }
            .toList()
        return problems.asSequence()
            .filterNot { p -> (p.id and IProblem.Syntax) == 0 && brokenStatements.any { p.sourceStart in it } }
            .map { p ->
                val start = p.sourceStart.coerceIn(0, source.length)
                val end = (p.sourceEnd + 1).coerceIn(start, source.length)
                Diagnostic(TextRange(start, end), if (p.isError) Severity.ERROR else Severity.WARNING, p.message)
            }
            .toList()
    }

    /** Source range of the smallest statement enclosing [offset] (its own range if none), for taint checks. */
    private fun enclosingStatementRange(offset: Int): IntRange {
        var n: ASTNode? = NodeFinder.perform(cu, offset.coerceIn(0, source.length), 0)
        while (n != null && n !is Statement) n = n.parent
        return n?.let { it.startPosition until it.startPosition + it.length } ?: (offset..offset)
    }

    override fun text(): CharSequence = source

    override fun nodeAt(offset: Int): DomNode =
        NodeFinder.perform(cu, offset.coerceIn(0, source.length), 0)?.let { JdtDomNode(it, this) } ?: this

    override fun nodesIn(range: TextRange): Sequence<DomNode> = sequence {
        for (n in preOrder(cu)) {
            val r = TextRange(n.startPosition, n.startPosition + n.length)
            if (r.intersects(range)) yield(JdtDomNode(n, this@JdtParsedFile))
        }
    }

    private fun preOrder(node: ASTNode): Sequence<ASTNode> = sequence {
        yield(node)
        for (c in childNodes(node)) yieldAll(preOrder(c))
    }
}

/** A neutral [DomNode] over a JDT [ASTNode]; [node] is exposed for binding-based resolution. */
class JdtDomNode(val node: ASTNode, val pf: JdtParsedFile) : DomNode {
    override val kind: NodeKind = kindOf(node)
    override val range: TextRange = TextRange(node.startPosition, node.startPosition + node.length)
    override val parent: DomNode? get() = node.parent?.let { if (it is CompilationUnit) pf else JdtDomNode(it, pf) }
    override val children: List<DomNode> get() = childNodes(node).map { JdtDomNode(it, pf) }
    override fun text(): CharSequence {
        val s = pf.text()
        val start = range.start.coerceIn(0, s.length)
        return s.subSequence(start, range.end.coerceIn(start, s.length))
    }
}

private fun kindOf(node: ASTNode): NodeKind = when (node.nodeType) {
    ASTNode.COMPILATION_UNIT -> NodeKind.COMPILATION_UNIT
    ASTNode.PACKAGE_DECLARATION -> NodeKind.PACKAGE_DECL
    ASTNode.IMPORT_DECLARATION -> NodeKind.IMPORT_DECL
    ASTNode.TYPE_DECLARATION, ASTNode.ENUM_DECLARATION, ASTNode.RECORD_DECLARATION, ASTNode.ANNOTATION_TYPE_DECLARATION -> NodeKind.CLASS_DECL
    ASTNode.METHOD_DECLARATION -> NodeKind.METHOD_DECL
    ASTNode.FIELD_DECLARATION -> NodeKind.FIELD_DECL
    ASTNode.SINGLE_VARIABLE_DECLARATION -> NodeKind.PARAMETER
    ASTNode.VARIABLE_DECLARATION_FRAGMENT, ASTNode.VARIABLE_DECLARATION_STATEMENT -> NodeKind.LOCAL_VAR
    ASTNode.BLOCK -> NodeKind.BLOCK
    ASTNode.SIMPLE_NAME -> NodeKind.NAME_REF
    ASTNode.FIELD_ACCESS, ASTNode.QUALIFIED_NAME, ASTNode.SUPER_FIELD_ACCESS -> NodeKind.MEMBER_ACCESS
    ASTNode.METHOD_INVOCATION, ASTNode.SUPER_METHOD_INVOCATION -> NodeKind.METHOD_CALL
    ASTNode.SIMPLE_TYPE, ASTNode.QUALIFIED_TYPE, ASTNode.PARAMETERIZED_TYPE, ASTNode.NAME_QUALIFIED_TYPE -> NodeKind.TYPE_REF
    ASTNode.STRING_LITERAL, ASTNode.NUMBER_LITERAL, ASTNode.BOOLEAN_LITERAL, ASTNode.CHARACTER_LITERAL, ASTNode.NULL_LITERAL -> NodeKind.LITERAL
    else -> NodeKind(ASTNode.nodeClassForType(node.nodeType).simpleName)
}
