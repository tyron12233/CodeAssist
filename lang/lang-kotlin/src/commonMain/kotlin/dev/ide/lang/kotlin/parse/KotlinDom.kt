package dev.ide.lang.kotlin.parse

import dev.ide.kotlin.syntax.psi.KDoc
import dev.ide.kotlin.syntax.psi.KDocLink
import dev.ide.kotlin.syntax.psi.KDocName
import dev.ide.kotlin.syntax.psi.KDocSection
import dev.ide.kotlin.syntax.psi.KDocTag
import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtAnnotation
import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtAnnotationUseSiteTarget
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtClassLiteralExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtCollectionLiteralExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtConstructorCalleeExpression
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationReferenceExpression
import dev.ide.kotlin.syntax.psi.KtContainerNode
import dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody
import dev.ide.kotlin.syntax.psi.KtContextReceiverList
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclarationEntry
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntrySuperclassReferenceExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFileAnnotationList
import dev.ide.kotlin.syntax.psi.KtFinallySection
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtFunctionTypeReceiver
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtImportAlias
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtImportList
import dev.ide.kotlin.syntax.psi.KtInitializerList
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtLabelReferenceExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtModifierList
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtOperationReferenceExpression
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtPropertyDelegate
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtStringInterpolationPrefix
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntryWithExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtSuperTypeList
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeArgumentList
import dev.ide.kotlin.syntax.psi.KtTypeConstraint
import dev.ide.kotlin.syntax.psi.KtTypeConstraintList
import dev.ide.kotlin.syntax.psi.KtTypeParameter
import dev.ide.kotlin.syntax.psi.KtTypeParameterList
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtValueArgumentName
import dev.ide.kotlin.syntax.psi.KtWhenCondition
import dev.ide.kotlin.syntax.psi.KtWhenConditionInRange
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenEntryGuard
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.kotlin.syntax.psi.parsedKDoc
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinDiagnosticCodes
import dev.ide.lang.dom.KotlinNodeKinds
import dev.ide.vfs.VirtualFile

/**
 * Adapts a Kotlin [KtFile] (and the PSI subtree under it) to the backend-neutral DOM. The editor only
 * ever sees [DomNode]s; the backend's own resolver reaches the underlying PSI through [KotlinDomNode.psi].
 *
 * "Represented" nodes are the COMPOSITES, error elements included (which preserves error tolerance);
 * leaf tokens (identifiers, keywords, whitespace, punctuation) are not their own DOM nodes, being
 * covered by their enclosing element's [text]. `nodeAt` climbs from the `findElementAt` leaf to the
 * nearest represented ancestor. The [KtFile] itself is represented by the [KotlinParsedFile] (the
 * compilation-unit node), never by a separate [KotlinDomNode].
 */
// Under PSI this read `psi is KtElement || psi is PsiErrorElement`, and that was the same question: only
// composites implemented `KtElement` there, so the type test excluded leaves. Every node in the facade's tree
// is a `KtElement`, so the question has to be asked directly, and asking it by type instead represented every
// brace, keyword and identifier as a DOM node of its own.
internal fun isRepresented(psi: KtElement): Boolean = when (psi) {
    // A doc comment's own scaffolding is structure, not shapes the IDE has anything to say about: only the
    // links inside it are, so the comment, its sections and its tags are walked THROUGH. Represent them and
    // two things break at once: they arrive as the catch-all kind, and the walk stops at them and never
    // reaches the links they contain.
    is KDoc, is KDocSection, is KDocTag -> false
    is KDocLink, is KDocName -> true
    else -> !psi.isTokenLike
}

private fun kindOf(psi: KtElement): NodeKind = when {
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
    psi.isErrorElement -> KotlinNodeKinds.ERROR

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

    psi is KDocLink -> KotlinNodeKinds.KDOC_LINK
    psi is KDocName -> KotlinNodeKinds.KDOC_NAME

    // Everything else in a doc comment (the comment element itself, its sections and tags) has no kind of its
    // own; the two above are the ones the IDE treats as references.

    else -> KotlinNodeKinds.OTHER
}

/** Represented children of [parent], flattening any non-represented wrappers in between. */
internal fun representedChildrenOf(parent: KtElement, owner: KotlinParsedFile): List<DomNode> {
    val out = ArrayList<DomNode>()
    fun collect(psi: KtElement) {
        if (isRepresented(psi)) { out += owner.adapt(psi); return }
        for (c in kdocAwareChildren(psi)) collect(c)
    }
    for (c in kdocAwareChildren(parent)) collect(c)
    return out
}

/**
 * A node's children for the purpose of projecting the DOM, with a doc comment's CONTENTS spliced in.
 *
 * Two things differ from the plain child walk. A doc comment is trivia, so it is not in [KtElement.children]
 * at all and has to be picked out of [KtElement.childrenWithTrivia]. And it is a single token there, whose
 * structure is a parse of its own ([parsedKDoc]) that the DOM wants: `[Foo.bar]` inside a KDoc is a reference
 * the IDE resolves and navigates, which is why KDOC_LINK and KDOC_NAME are kinds in the first place.
 *
 * Nothing else in trivia is represented, so ordinary comments and whitespace still contribute no nodes.
 */
private fun kdocAwareChildren(psi: KtElement): List<KtElement> {
    // A link's names are a parse of their own again (the text inside `[ ]` is Kotlin, not KDoc), so they are
    // not children of the link in any tree and have to be spliced the same way the comment itself was.
    if (psi is KDocLink) return psi.names
    val doc = psi.docCommentChildren
    if (doc.isEmpty()) return psi.children
    return psi.children + doc.mapNotNull { it.parsedKDoc }
}

class KotlinParsedFile(
    val ktFile: KtFile,
    override val file: VirtualFile,
    override val documentVersion: Long,
) : ParsedFile {

    // A plain HashMap, not an identity map. `KtElement.equals` is `session === session && node.index ==
    // node.index`, and every element here comes out of this file's one session, so equality and identity
    // pick out the same node. Over IntelliJ PSI that was not true and the map had to be an identity one.
    private val cache = HashMap<KtElement, KotlinDomNode>()

    /** Stable wrapper for a non-file PSI element. Never call with the [KtFile] — that node is `this`. */
    internal fun adapt(psi: KtElement): KotlinDomNode = cache.getOrPut(psi) { KotlinDomNode(psi, this) }

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
        var psi: KtElement? = ktFile.findElementAt(if (clamped >= len) len - 1 else clamped)
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

    private fun collectDiagnostics(root: KtElement): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        fun visit(psi: KtElement) {
            if (psi.isErrorElement) {
                val r = psi.textRange
                out += Diagnostic(
                    range = TextRange(r.startOffset, r.endOffset),
                    severity = Severity.ERROR,
                    // The light tree records that the parser recovered here, not what it wanted instead, so
                    // there is no per-site message to report the way `PsiErrorElement.errorDescription` gave one.
                    message = "Syntax error",
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
    val psi: KtElement,
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
