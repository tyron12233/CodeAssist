package dev.ide.lang.java.parse

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiVariable
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile
import java.util.IdentityHashMap

/**
 * Adapts an IntelliJ [PsiJavaFile] (and the PSI subtree under it) to the backend-neutral DOM. The editor only
 * ever sees [DomNode]s; the backend's own resolver reaches the underlying PSI through [JavaDomNode.psi] and
 * hands it to IntelliJ's resolution engine.
 *
 * Mirrors [dev.ide.lang.kotlin.parse.KotlinParsedFile]: "represented" nodes are a curated set of meaningful
 * Java elements (members, statements, expressions, references) plus [PsiErrorElement] (which preserves error
 * tolerance). Leaf tokens (identifiers, keywords, whitespace, punctuation) are not their own DOM nodes — they
 * are covered by their enclosing represented element's [text]. `nodeAt` climbs from PSI's `findElementAt` leaf
 * to the nearest represented ancestor. The [PsiJavaFile] itself is the [JavaParsedFile] compilation-unit node,
 * never a separate [JavaDomNode].
 */
internal fun isRepresented(psi: PsiElement): Boolean = when (psi) {
    is PsiClass, is PsiMethod, is PsiVariable,      // PsiVariable = fields + params + locals
    is PsiCodeBlock, is PsiStatement, is PsiExpression,
    is PsiJavaCodeReferenceElement, is PsiTypeElement,
    is PsiPackageStatement, is PsiImportStatementBase,
    is PsiErrorElement -> true
    else -> false
}

private fun kindOf(psi: PsiElement): NodeKind = when (psi) {
    is PsiJavaFile -> NodeKind.COMPILATION_UNIT
    is PsiPackageStatement -> NodeKind.PACKAGE_DECL
    is PsiImportStatementBase -> NodeKind.IMPORT_DECL
    is PsiClass -> NodeKind.CLASS_DECL
    is PsiMethod -> NodeKind.METHOD_DECL
    is PsiParameter -> NodeKind.PARAMETER
    is PsiLocalVariable -> NodeKind.LOCAL_VAR
    is PsiField -> NodeKind.FIELD_DECL
    is PsiCodeBlock -> NodeKind.BLOCK
    is PsiNewExpression -> JavaNodeKinds.NEW_EXPR
    is PsiMethodCallExpression -> NodeKind.METHOD_CALL
    is PsiReferenceExpression -> if (psi.qualifierExpression != null) NodeKind.MEMBER_ACCESS else NodeKind.NAME_REF
    is PsiLiteralExpression -> NodeKind.LITERAL
    is PsiJavaCodeReferenceElement -> NodeKind.TYPE_REF
    is PsiTypeElement -> NodeKind.TYPE_REF
    is PsiErrorElement -> NodeKind.ERROR
    else -> JavaNodeKinds.OTHER
}

/** Represented children of [parent], flattening any non-represented wrappers in between. */
internal fun representedChildrenOf(parent: PsiElement, owner: JavaParsedFile): List<DomNode> {
    val out = ArrayList<DomNode>()
    fun collect(psi: PsiElement) {
        if (isRepresented(psi)) { out += owner.adapt(psi); return }
        var c = psi.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    var c = parent.firstChild
    while (c != null) { collect(c); c = c.nextSibling }
    return out
}

class JavaParsedFile(
    val javaFile: PsiJavaFile,
    override val file: VirtualFile,
    override val documentVersion: Long,
) : ParsedFile {

    private val cache = IdentityHashMap<PsiElement, JavaDomNode>()

    /** Stable wrapper for a non-file PSI element. Never call with the [PsiJavaFile] — that node is `this`. */
    internal fun adapt(psi: PsiElement): JavaDomNode = cache.getOrPut(psi) { JavaDomNode(psi, this) }

    // --- DomNode: this IS the compilation-unit node ---
    override val kind: NodeKind get() = NodeKind.COMPILATION_UNIT
    override val range: TextRange get() = TextRange(0, javaFile.textLength)
    override val parent: DomNode? get() = null
    override val children: List<DomNode> by lazy { representedChildrenOf(javaFile, this) }
    override fun text(): CharSequence = javaFile.text

    // Syntax (PsiErrorElements) + resolution-derived semantic diagnostics (unresolved references). Lazy, so
    // the (locked, resolving) semantic pass runs only when diagnostics are actually read (the analysis pass),
    // not for completion/folding/etc. that never touch `.diagnostics`.
    override val diagnostics: List<Diagnostic> by lazy {
        collectDiagnostics(javaFile) + dev.ide.lang.java.resolve.JavaSemanticDiagnostics.of(javaFile)
    }

    override fun nodeAt(offset: Int): DomNode {
        val len = javaFile.textLength
        if (len == 0) return this
        val clamped = offset.coerceIn(0, len)
        // findElementAt returns null exactly at EOF; probe one char back so caret-at-end still lands.
        var psi: PsiElement? = javaFile.findElementAt(if (clamped >= len) len - 1 else clamped)
        while (psi != null && !isRepresented(psi)) psi = psi.parent
        return if (psi == null || psi is PsiJavaFile) this else adapt(psi)
    }

    override fun nodesIn(range: TextRange): Sequence<DomNode> {
        val out = ArrayList<DomNode>()
        fun walk(node: DomNode) {
            if (!node.range.intersects(range)) return
            out += node
            node.children.forEach { walk(it) }
        }
        walk(this)
        return out.asSequence()
    }

    private fun collectDiagnostics(root: PsiElement): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        fun visit(psi: PsiElement) {
            if (psi is PsiErrorElement) {
                val r = psi.textRange
                // Prefix with the "Syntax error" token so the message matches the IDE-wide convention (JDT's
                // wording) while keeping IntelliJ's precise description ("Expression expected", …) after it.
                out += Diagnostic(
                    range = TextRange(r.startOffset, r.endOffset),
                    severity = Severity.ERROR,
                    message = "Syntax error" + (psi.errorDescription?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                    code = JavaDiagnosticCodes.SYNTAX,
                )
            }
            var c = psi.firstChild
            while (c != null) { visit(c); c = c.nextSibling }
        }
        visit(root)
        return out
    }
}

/**
 * One adapted PSI element. Identity is stable per [JavaParsedFile] (cached), so `parent.children` contains
 * `this`. [psi] is the hidden back-reference the resolver/inference use; the editor never sees it.
 */
class JavaDomNode internal constructor(
    val psi: PsiElement,
    internal val owner: JavaParsedFile,
) : DomNode {

    override val kind: NodeKind = kindOf(psi)

    override val range: TextRange
        get() = psi.textRange.let { TextRange(it.startOffset, it.endOffset) }

    override val parent: DomNode?
        get() {
            var p = psi.parent
            while (p != null && !isRepresented(p)) p = p.parent
            return if (p == null || p is PsiJavaFile) owner else owner.adapt(p)
        }

    override val children: List<DomNode> by lazy { representedChildrenOf(psi, owner) }

    override fun text(): CharSequence = psi.text
}
