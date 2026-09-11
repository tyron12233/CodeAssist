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
import org.jetbrains.kotlin.kdoc.psi.impl.KDocLink
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtAnnotationUseSiteTarget
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtConstructorDelegationReferenceExpression
import org.jetbrains.kotlin.psi.KtContainerNode
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtContextReceiverList
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntrySuperclassReferenceExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFileAnnotationList
import org.jetbrains.kotlin.psi.KtFinallySection
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtFunctionTypeReceiver
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtImportAlias
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtInitializerList
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringInterpolationPrefix
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeEntry
import org.jetbrains.kotlin.psi.KtSuperTypeList
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtTypeConstraint
import org.jetbrains.kotlin.psi.KtTypeConstraintList
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeParameterList
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhenCondition
import org.jetbrains.kotlin.psi.KtWhenConditionInRange
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenEntryGuard
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
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
    // Order matters: `$x`/`${…}` are entries too, and they're the ones that carry an expression.
    psi is KtStringTemplateEntryWithExpression -> KotlinNodeKinds.STRING_TEMPLATE_INTERPOLATION
    psi is KtStringTemplateEntry -> KotlinNodeKinds.STRING_TEMPLATE_ENTRY
    psi is KtLambdaExpression -> KotlinNodeKinds.LAMBDA
    psi is KtWhenExpression -> KotlinNodeKinds.WHEN
    psi is KtBinaryExpression -> KotlinNodeKinds.BINARY
    psi is PsiErrorElement -> KotlinNodeKinds.ERROR

    // Calls and arguments. KtLambdaArgument extends KtValueArgument, so it has to be tested first.
    psi is KtLambdaArgument -> KotlinNodeKinds.LAMBDA_ARGUMENT
    psi is KtValueArgument -> KotlinNodeKinds.ARGUMENT
    psi is KtValueArgumentList -> KotlinNodeKinds.ARGUMENT_LIST
    psi is KtValueArgumentName -> KotlinNodeKinds.ARGUMENT_NAME
    psi is KtConstructorCalleeExpression -> KotlinNodeKinds.CONSTRUCTOR_CALLEE
    psi is KtEnumEntrySuperclassReferenceExpression -> KotlinNodeKinds.CONSTRUCTOR_CALLEE
    psi is KtConstructorDelegationCall -> KotlinNodeKinds.CONSTRUCTOR_DELEGATION_CALL
    psi is KtConstructorDelegationReferenceExpression -> KotlinNodeKinds.CONSTRUCTOR_DELEGATION_REF

    // Types.
    psi is KtUserType -> KotlinNodeKinds.USER_TYPE
    psi is KtNullableType -> KotlinNodeKinds.NULLABLE_TYPE
    psi is KtFunctionType -> KotlinNodeKinds.FUNCTION_TYPE
    psi is KtFunctionTypeReceiver -> KotlinNodeKinds.FUNCTION_TYPE_RECEIVER
    psi is KtTypeProjection -> KotlinNodeKinds.TYPE_PROJECTION
    psi is KtTypeArgumentList -> KotlinNodeKinds.TYPE_ARGUMENT_LIST
    psi is KtTypeParameter -> KotlinNodeKinds.TYPE_PARAMETER
    psi is KtTypeParameterList -> KotlinNodeKinds.TYPE_PARAMETER_LIST
    psi is KtTypeConstraint -> KotlinNodeKinds.TYPE_CONSTRAINT
    psi is KtTypeConstraintList -> KotlinNodeKinds.TYPE_CONSTRAINT_LIST
    psi is KtContextReceiverList -> KotlinNodeKinds.CONTEXT_RECEIVER_LIST

    // Operators and the remaining expression shapes.
    psi is KtOperationReferenceExpression -> KotlinNodeKinds.OPERATOR
    psi is KtFunctionLiteral -> KotlinNodeKinds.FUNCTION_LITERAL
    psi is KtPrefixExpression -> KotlinNodeKinds.PREFIX
    psi is KtPostfixExpression -> KotlinNodeKinds.POSTFIX
    psi is KtParenthesizedExpression -> KotlinNodeKinds.PARENTHESIZED
    psi is KtArrayAccessExpression -> KotlinNodeKinds.ARRAY_ACCESS
    psi is KtIsExpression -> KotlinNodeKinds.IS
    psi is KtBinaryExpressionWithTypeRHS -> KotlinNodeKinds.AS
    psi is KtThisExpression -> KotlinNodeKinds.THIS
    psi is KtSuperExpression -> KotlinNodeKinds.SUPER
    psi is KtLabelReferenceExpression -> KotlinNodeKinds.LABEL_REF
    psi is KtCallableReferenceExpression -> KotlinNodeKinds.CALLABLE_REFERENCE
    psi is KtClassLiteralExpression -> KotlinNodeKinds.CLASS_LITERAL
    psi is KtObjectLiteralExpression -> KotlinNodeKinds.OBJECT_LITERAL
    psi is KtDestructuringDeclaration -> KotlinNodeKinds.DESTRUCTURING
    psi is KtDestructuringDeclarationEntry -> KotlinNodeKinds.DESTRUCTURING_ENTRY
    psi is KtAnnotatedExpression -> KotlinNodeKinds.ANNOTATED_EXPRESSION
    psi is KtLabeledExpression -> KotlinNodeKinds.LABELED_EXPRESSION
    psi is KtCollectionLiteralExpression -> KotlinNodeKinds.COLLECTION_LITERAL
    psi is KtStringInterpolationPrefix -> KotlinNodeKinds.STRING_INTERPOLATION_PREFIX

    // Control flow. The body wrapper is a subclass of the generic container, so it comes first.
    psi is KtContainerNodeForControlStructureBody -> KotlinNodeKinds.CONTROL_BODY
    psi is KtContainerNode -> KotlinNodeKinds.CONTAINER
    psi is KtReturnExpression -> KotlinNodeKinds.RETURN
    psi is KtIfExpression -> KotlinNodeKinds.IF
    psi is KtWhenEntry -> KotlinNodeKinds.WHEN_ENTRY
    psi is KtWhenConditionIsPattern -> KotlinNodeKinds.WHEN_CONDITION_IS
    psi is KtWhenConditionInRange -> KotlinNodeKinds.WHEN_CONDITION_IN
    psi is KtWhenCondition -> KotlinNodeKinds.WHEN_CONDITION
    psi is KtWhenEntryGuard -> KotlinNodeKinds.WHEN_ENTRY_GUARD
    psi is KtForExpression -> KotlinNodeKinds.FOR
    psi is KtWhileExpression -> KotlinNodeKinds.WHILE
    psi is KtDoWhileExpression -> KotlinNodeKinds.DO_WHILE
    psi is KtContinueExpression -> KotlinNodeKinds.CONTINUE
    psi is KtBreakExpression -> KotlinNodeKinds.BREAK
    psi is KtTryExpression -> KotlinNodeKinds.TRY
    psi is KtCatchClause -> KotlinNodeKinds.CATCH
    psi is KtFinallySection -> KotlinNodeKinds.FINALLY
    psi is KtThrowExpression -> KotlinNodeKinds.THROW

    // Declaration structure.
    psi is KtParameterList -> KotlinNodeKinds.PARAMETER_LIST
    psi is KtModifierList -> KotlinNodeKinds.MODIFIER_LIST   // covers KtDeclarationModifierList
    psi is KtAnnotationEntry -> KotlinNodeKinds.ANNOTATION_ENTRY
    psi is KtAnnotation -> KotlinNodeKinds.ANNOTATION_GROUP
    psi is KtAnnotationUseSiteTarget -> KotlinNodeKinds.ANNOTATION_USE_SITE
    psi is KtFileAnnotationList -> KotlinNodeKinds.FILE_ANNOTATION_LIST
    psi is KtClassBody -> KotlinNodeKinds.CLASS_BODY
    psi is KtSuperTypeList -> KotlinNodeKinds.SUPERTYPE_LIST
    psi is KtInitializerList -> KotlinNodeKinds.SUPERTYPE_LIST   // an enum entry's `: Base(1)`
    psi is KtSuperTypeCallEntry -> KotlinNodeKinds.SUPERTYPE_CALL
    psi is KtDelegatedSuperTypeEntry -> KotlinNodeKinds.SUPERTYPE_DELEGATE
    psi is KtSuperTypeEntry -> KotlinNodeKinds.SUPERTYPE_ENTRY
    psi is KtPropertyAccessor -> KotlinNodeKinds.PROPERTY_ACCESSOR
    psi is KtPropertyDelegate -> KotlinNodeKinds.PROPERTY_DELEGATE
    psi is KtClassInitializer -> KotlinNodeKinds.INIT
    psi is KtImportList -> KotlinNodeKinds.IMPORT_LIST
    psi is KtImportAlias -> KotlinNodeKinds.IMPORT_ALIAS

    // KDoc.
    psi is KDocLink -> KotlinNodeKinds.KDOC_LINK
    psi is KDocName -> KotlinNodeKinds.KDOC_NAME

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
