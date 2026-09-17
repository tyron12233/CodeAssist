package dev.ide.kotlin.syntax.psi

import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode

/**
 * Node types the facade had no name for.
 *
 * The parser produces all of these; the facade modelled the ones a structure view needs and stopped. A
 * backend needs the rest, because they are what its rules are written about: a named argument is a
 * `KtValueArgumentName`, a `this` delegation is a `KtConstructorDelegationCall`, and a `when` guard is not
 * an ordinary condition. Each is small, and each was a compile error at a call site until it existed.
 */

/** The `name =` of a named argument. */
class KtValueArgumentName internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    // Both non-null as upstream: the node exists only because a name was written.
    val referenceExpression: KtNameReferenceExpression get() = firstChildOfType()!!
    val asName: Name get() = Name.identifier(referenceExpression.getReferencedName())
}

/** `@get:` / `@field:` / `@file:`: the target written before the colon of a use-site annotation. */
class KtAnnotationUseSiteTarget internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node)

/** An `@[Foo Bar]` group. The entries inside it are ordinary [KtAnnotationEntry]s. */
class KtAnnotation internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtAnnotated {
    override val annotationEntries: List<KtAnnotationEntry> get() = children.filterIsInstance<KtAnnotationEntry>()
}

/** A file-level `@file:Foo` list, which hangs off the file rather than off any declaration. */
class KtFileAnnotationList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), KtAnnotated {
    override val annotationEntries: List<KtAnnotationEntry> get() = children.filterIsInstance<KtAnnotationEntry>()
}

/** The callee of a super-type call: the type being constructed. */
class KtConstructorCalleeExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val constructorReferenceExpression: KtNameReferenceExpression? get() = firstChildOfType()
}

/**
 * The `: this(...)` or `: super(...)` a secondary constructor delegates through.
 *
 * It is present even when the source wrote nothing, in which case it is IMPLICIT and covers no text: a
 * constructor with no delegation still delegates. A caller that treats its absence and its emptiness the
 * same way gets the right answer; one that reads its arguments without checking gets an empty list.
 */
class KtConstructorDelegationCall internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node) {
    val calleeExpression: KtConstructorDelegationReferenceExpression? get() = firstChildOfType()
    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()
    val valueArguments: List<KtValueArgument> get() = valueArgumentList?.arguments.orEmpty()
    val isImplicit: Boolean get() = textLength == 0

    /** `: this(...)` rather than `: super(...)`. An implicit delegation is to super. */
    val isCallToThis: Boolean
        get() = calleeExpression?.text == "this" || (isImplicit && false)
}

/** The `this` or `super` word inside a [KtConstructorDelegationCall]. */
class KtConstructorDelegationReferenceExpression internal constructor(
    session: KtTreeSession,
    node: LightNode,
) : KtExpression(session, node) {
    val isThis: Boolean get() = text.trim() == "this"
}

/** The type named by an enum entry's `super` reference. */
class KtEnumEntrySuperclassReferenceExpression internal constructor(
    session: KtTreeSession,
    node: LightNode,
) : KtExpression(session, node) {
    fun getReferencedName(): String = text
}

/** The receiver of a function TYPE: the `T` in `T.() -> R`. Not a declaration's receiver. */
class KtFunctionTypeReceiver internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
}

/** The `: Super(), Other` list on an enum entry or object literal. */
class KtInitializerList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val initializers: List<KtSuperTypeListEntry> get() = children.filterIsInstance<KtSuperTypeListEntry>()
}

/** The `@label` of a labelled expression or a labelled `this`/`super`/`break`/`continue`/`return`. */
class KtLabelReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtSimpleNameExpression {
    override fun getReferencedName(): String = text.removePrefix("@")
}

/** A multi-dollar string's `$$` prefix, which decides how many dollars start an interpolation. */
class KtStringInterpolationPrefix internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    /** How many `$` a template entry needs inside this string. One, unless the source asked otherwise. */
    val dollarCount: Int get() = text.count { it == '$' }.coerceAtLeast(1)
}

/** The `if (…)` guard on a `when` entry: a second condition, not one of the entry's subjects. */
class KtWhenEntryGuard internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val expression: KtExpression? get() = firstChildOfType()
}

/** A `context(A, B)` list on a declaration. */
class KtContextReceiverList internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node) {
    val contextParameters: List<KtParameter> get() = children.filterIsInstance<KtParameter>()
    val typeReferences: List<KtTypeReference> get() = children.filterIsInstance<KtTypeReference>()
}

/**
 * A wrapper the parser inserts that the source never wrote.
 *
 * A control structure's condition and body are each wrapped in one, so that an `if` with no `else` still
 * has a well-formed shape. Upstream call sites reach THROUGH it, which is why it has to be nameable even
 * though nothing is ever interested in the node itself.
 */
open class KtContainerNode internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node)

/** The [KtContainerNode] specifically around a control structure's body. */
class KtContainerNodeForControlStructureBody internal constructor(
    session: KtTreeSession,
    node: LightNode,
) : KtContainerNode(session, node) {
    val expression: KtExpression? get() = firstChildOfType()
}

internal fun createAddedPsi(session: KtTreeSession, node: LightNode): KtElement? =
    when (session.tree.getType(node)) {
        KtNodeTypes.VALUE_ARGUMENT_NAME -> KtValueArgumentName(session, node)
        KtNodeTypes.ANNOTATION -> KtAnnotation(session, node)
        KtNodeTypes.ANNOTATION_TARGET -> KtAnnotationUseSiteTarget(session, node)
        KtNodeTypes.FILE_ANNOTATION_LIST -> KtFileAnnotationList(session, node)
        KtNodeTypes.CONSTRUCTOR_CALLEE -> KtConstructorCalleeExpression(session, node)
        KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL -> KtConstructorDelegationCall(session, node)
        KtNodeTypes.CONSTRUCTOR_DELEGATION_REFERENCE ->
            KtConstructorDelegationReferenceExpression(session, node)

        KtNodeTypes.ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION ->
            KtEnumEntrySuperclassReferenceExpression(session, node)

        KtNodeTypes.FUNCTION_TYPE_RECEIVER -> KtFunctionTypeReceiver(session, node)
        KtNodeTypes.INITIALIZER_LIST -> KtInitializerList(session, node)
        KtNodeTypes.LABEL -> KtLabelReferenceExpression(session, node)
        KtNodeTypes.STRING_INTERPOLATION_PREFIX -> KtStringInterpolationPrefix(session, node)
        KtNodeTypes.WHEN_ENTRY_GUARD -> KtWhenEntryGuard(session, node)
        // The vendored grammar names this CONTEXT_PARAMETER_LIST; the PSI class upstream is still
        // KtContextReceiverList, from before context parameters were renamed.
        KtNodeTypes.CONTEXT_PARAMETER_LIST -> KtContextReceiverList(session, node)

        // The control-structure wrappers. `if (c) a else b` puts `c`, `a` and `b` each inside a node of
        // their own, which is why walking to a branch is `child(THEN)?.firstChild` and not `children[1]`.
        KtNodeTypes.THEN, KtNodeTypes.ELSE, KtNodeTypes.BODY ->
            KtContainerNodeForControlStructureBody(session, node)

        KtNodeTypes.CONDITION, KtNodeTypes.LOOP_RANGE, KtNodeTypes.LABEL_QUALIFIER, KtNodeTypes.INDICES ->
            KtContainerNode(session, node)

        else -> null
    }
