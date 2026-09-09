package dev.ide.lang.kotlin.parse

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinDiagnosticCodes
import dev.ide.lang.kotlin.KotlinNodeKinds
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenExpression
import java.util.IdentityHashMap

/**
 * Adapts a Kotlin [KtFile] (and the PSI subtree under it) to the backend-neutral DOM. The editor only
 * ever sees [DomNode]s; the backend's own resolver reaches the underlying PSI through [KotlinDomNode.psi].
 *
 * "Represented" nodes are every [KtElement] plus [PsiErrorElement] (which preserves error tolerance);
 * leaf tokens (identifiers, keywords, whitespace, punctuation) are not their own DOM nodes — they're
 * covered by their enclosing element's [text]. `nodeAt` climbs from PSI's `findElementAt` leaf to the
 * nearest represented ancestor. The [KtFile] itself is represented by the [KotlinParsedFile] (the
 * compilation-unit node), never by a separate [KotlinDomNode].
 */
internal fun isRepresented(psi: PsiElement): Boolean = psi is KtElement || psi is PsiErrorElement

private fun kindOf(psi: PsiElement): NodeKind = when {
    psi is KtFile -> KotlinNodeKinds.COMPILATION_UNIT
    psi is KtPackageDirective -> KotlinNodeKinds.PACKAGE_DECL
    psi is KtImportDirective -> KotlinNodeKinds.IMPORT_DECL
    psi is KtObjectDeclaration -> KotlinNodeKinds.CLASS_DECL
    psi is KtClassOrObject -> KotlinNodeKinds.CLASS_DECL
    psi is KtNamedFunction -> KotlinNodeKinds.METHOD_DECL      // receiver type ref present => extension (read later)
    psi is KtProperty -> if (psi.isLocal) KotlinNodeKinds.LOCAL_VAR else KotlinNodeKinds.PROPERTY
    psi is KtParameter -> KotlinNodeKinds.PARAMETER
    psi is KtPrimaryConstructor -> KotlinNodeKinds.CONSTRUCTOR
    psi is KtSecondaryConstructor -> KotlinNodeKinds.CONSTRUCTOR
    psi is KtTypeAlias -> KotlinNodeKinds.TYPEALIAS
    psi is KtBlockExpression -> KotlinNodeKinds.BLOCK
    psi is KtCallExpression -> KotlinNodeKinds.METHOD_CALL
    psi is KtSafeQualifiedExpression -> KotlinNodeKinds.SAFE_ACCESS  // a?.b — same member set as `.`
    psi is KtDotQualifiedExpression -> KotlinNodeKinds.MEMBER_ACCESS  // a.b — selector is the completion site
    psi is KtNameReferenceExpression -> KotlinNodeKinds.NAME_REF
    psi is KtTypeReference -> KotlinNodeKinds.TYPE_REF
    psi is KtConstantExpression -> KotlinNodeKinds.LITERAL
    psi is KtStringTemplateExpression -> KotlinNodeKinds.STRING_TEMPLATE
    psi is KtLambdaExpression -> KotlinNodeKinds.LAMBDA
    psi is KtWhenExpression -> KotlinNodeKinds.WHEN
    psi is KtBinaryExpression -> KotlinNodeKinds.BINARY
    psi is PsiErrorElement -> KotlinNodeKinds.ERROR
    else -> KotlinNodeKinds.OTHER
}

/** Represented children of [parent], flattening any non-represented wrappers in between. */
internal fun representedChildrenOf(parent: PsiElement, owner: KotlinParsedFile): List<DomNode> {
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

class KotlinParsedFile(
    val ktFile: KtFile,
    override val file: VirtualFile,
    override val documentVersion: Long,
) : ParsedFile {

    private val cache = IdentityHashMap<PsiElement, KotlinDomNode>()

    /** Stable wrapper for a non-file PSI element. Never call with the [KtFile] — that node is `this`. */
    internal fun adapt(psi: PsiElement): KotlinDomNode = cache.getOrPut(psi) { KotlinDomNode(psi, this) }

    // --- DomNode: this IS the compilation-unit node ---
    override val kind: NodeKind get() = KotlinNodeKinds.COMPILATION_UNIT
    override val range: TextRange get() = TextRange(0, ktFile.textLength)
    override val parent: DomNode? get() = null
    override val children: List<DomNode> by lazy { representedChildrenOf(ktFile, this) }
    override fun text(): CharSequence = ktFile.text

    override val diagnostics: List<Diagnostic> by lazy { collectDiagnostics(ktFile) }

    override fun nodeAt(offset: Int): DomNode {
        val len = ktFile.textLength
        if (len == 0) return this
        val clamped = offset.coerceIn(0, len)
        // findElementAt returns null exactly at EOF; probe one char back so caret-at-end still lands.
        var psi: PsiElement? = ktFile.findElementAt(if (clamped >= len) len - 1 else clamped)
        while (psi != null && !isRepresented(psi)) psi = psi.parent
        return if (psi == null || psi is KtFile) this else adapt(psi)
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
                out += Diagnostic(
                    range = TextRange(r.startOffset, r.endOffset),
                    severity = Severity.ERROR,
                    message = psi.errorDescription ?: "Syntax error",
                    code = KotlinDiagnosticCodes.SYNTAX,
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
 * One adapted PSI element. Identity is stable per [KotlinParsedFile] (cached), so `parent.children`
 * contains `this`. [psi] is the hidden back-reference the resolver/inference use; the editor never sees it.
 */
class KotlinDomNode internal constructor(
    val psi: PsiElement,
    internal val owner: KotlinParsedFile,
) : DomNode {

    override val kind: NodeKind = kindOf(psi)

    override val range: TextRange
        get() = psi.textRange.let { TextRange(it.startOffset, it.endOffset) }

    override val parent: DomNode?
        get() {
            var p = psi.parent
            while (p != null && !isRepresented(p)) p = p.parent
            return if (p == null || p is KtFile) owner else owner.adapt(p)
        }

    override val children: List<DomNode> by lazy { representedChildrenOf(psi, owner) }

    override fun text(): CharSequence = psi.text
}
