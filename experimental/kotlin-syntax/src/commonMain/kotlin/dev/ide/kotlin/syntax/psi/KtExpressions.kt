package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.SyntaxElementType
import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode

/**
 * The expression half of the facade, mirroring `org.jetbrains.kotlin.psi`'s expression classes.
 *
 * The one shape worth internalising is qualified access, because it is not what the surface syntax suggests.
 * `foo.bar(1)` is a [KtDotQualifiedExpression] whose receiver is a [KtNameReferenceExpression] and whose
 * selector is a [KtCallExpression] — the call wraps only `bar(1)`, not the dot. Everything that resolves a
 * member goes through [KtQualifiedExpression.receiverExpression] and [KtQualifiedExpression.selectorExpression]
 * for that reason.
 */
open class KtExpression internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node)

/** A code block, mirroring `KtBlockExpression`. */
class KtBlockExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val statements: List<KtExpression> get() = childrenOfType()
}

/** A bare name in expression position, mirroring `KtNameReferenceExpression`. */
class KtNameReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    fun getReferencedName(): String = text.removeSurrounding("`")

    val identifier: KtElement? get() = child(KtTokens.IDENTIFIER)
}

/** The operator position of a unary or binary expression, mirroring `KtOperationReferenceExpression`. */
class KtOperationReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    /** The operator token, or null when the operator is an infix function name. */
    val operationSignTokenType: SyntaxElementType? get() = children.firstOrNull()?.elementType

    fun getReferencedName(): String = text
}

class KtCallExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    /** What is being called: a name, or another expression for an invoke. */
    val calleeExpression: KtExpression? get() = children.firstOrNull() as? KtExpression

    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()

    /** Arguments in the parentheses, WITHOUT the trailing lambda. */
    val valueArguments: List<KtValueArgument> get() = valueArgumentList?.arguments.orEmpty()

    val lambdaArguments: List<KtLambdaArgument> get() = childrenOfType()

    val typeArgumentList: KtTypeArgumentList? get() = firstChildOfType()

    val typeArguments: List<KtTypeProjection> get() = typeArgumentList?.arguments.orEmpty()
}

class KtValueArgumentList internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val arguments: List<KtValueArgument> get() = childrenOfType()
}

/**
 * One argument of a call.
 *
 * Its accessors are FUNCTIONS rather than properties, and so are the label accessors below. PSI is Java, so
 * the editor backend spells them `getX()` — 207 of its ~226 such call sites are five methods, these among
 * them — and a Kotlin property cannot be called that way from Kotlin source even though it compiles to the
 * same JVM getter.
 */
class KtValueArgument internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {

    fun getArgumentExpression(): KtExpression? = childrenOfType<KtExpression>().lastOrNull()

    /** `name = value` in a call: the name, or null for a positional argument. */
    fun getArgumentName(): String? = child(KtNodeTypes.VALUE_ARGUMENT_NAME)?.text

    val isSpread: Boolean get() = hasChild(KtTokens.MUL)
}

/** A lambda passed outside the parentheses, mirroring `KtLambdaArgument`. */
class KtLambdaArgument internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val lambdaExpression: KtLambdaExpression? get() = firstChildOfType()
}

class KtLambdaExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    val functionLiteral: KtFunctionLiteral? get() = firstChildOfType()

    val valueParameters: List<KtParameter> get() = functionLiteral?.valueParameters.orEmpty()

    val bodyExpression: KtBlockExpression? get() = functionLiteral?.bodyExpression
}

class KtFunctionLiteral internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    val valueParameterList: KtParameterList? get() = firstChildOfType()

    val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()

    val bodyExpression: KtBlockExpression? get() = firstChildOfType()

    /** A lambda with no declared parameters has an implicit `it`. */
    val hasParameterSpecification: Boolean get() = valueParameterList != null
}

/** `a.b` or `a?.b`, mirroring `KtQualifiedExpression`. */
abstract class KtQualifiedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    // The dot itself is a child too, so both sides are found by type rather than by position.
    val receiverExpression: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull()

    val selectorExpression: KtExpression?
        get() = childrenOfType<KtExpression>().let { if (it.size > 1) it.last() else null }

    val operationTokenNode: KtElement?
        get() = child(KtTokens.DOT) ?: child(KtTokens.SAFE_ACCESS)
}

class KtDotQualifiedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtQualifiedExpression(session, node)

class KtSafeQualifiedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtQualifiedExpression(session, node)

class KtBinaryExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    val left: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull { it !is KtOperationReferenceExpression }

    val operationReference: KtOperationReferenceExpression? get() = firstChildOfType()

    val operationToken: SyntaxElementType? get() = operationReference?.operationSignTokenType

    val right: KtExpression?
        get() {
            val operation = operationReference ?: return null
            return childrenOfType<KtExpression>().lastOrNull { it.textOffset > operation.textOffset }
        }
}

/** `a is B` / `a !is B`, mirroring `KtIsExpression`. */
class KtIsExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val leftHandSide: KtExpression? get() = children.firstOrNull() as? KtExpression
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val isNegated: Boolean get() = hasChild(KtTokens.NOT_IS)
}

/** `a as B` / `a as? B`, mirroring `KtBinaryExpressionWithTypeRHS`. */
class KtBinaryExpressionWithTypeRHS internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val left: KtExpression? get() = children.firstOrNull() as? KtExpression
    val right: KtTypeReference? get() = firstChildOfType()
}

class KtPrefixExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val operationReference: KtOperationReferenceExpression? get() = firstChildOfType()
    val operationToken: SyntaxElementType? get() = operationReference?.operationSignTokenType
    val baseExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull { it !is KtOperationReferenceExpression }
}

class KtPostfixExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val operationReference: KtOperationReferenceExpression? get() = firstChildOfType()
    val operationToken: SyntaxElementType? get() = operationReference?.operationSignTokenType
    val baseExpression: KtExpression?
        get() = childrenOfType<KtExpression>().firstOrNull { it !is KtOperationReferenceExpression }
}

class KtParenthesizedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val expression: KtExpression? get() = firstChildOfType()
}

class KtArrayAccessExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val arrayExpression: KtExpression? get() = children.firstOrNull() as? KtExpression
    val indexExpressions: List<KtExpression> get() = child(KtNodeTypes.INDICES)?.childrenOfType<KtExpression>().orEmpty()
}

class KtCallableReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val receiverExpression: KtExpression? get() = children.firstOrNull()?.takeIf { it.textOffset < textOffset + 2 } as? KtExpression
    val callableReference: KtNameReferenceExpression? get() = childrenOfType<KtNameReferenceExpression>().lastOrNull()
}

class KtClassLiteralExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val receiverExpression: KtExpression? get() = children.firstOrNull() as? KtExpression
}

class KtConstantExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    /** INTEGER_CONSTANT, FLOAT_CONSTANT, BOOLEAN_CONSTANT, CHARACTER_CONSTANT or NULL. */
    val constantType: SyntaxElementType get() = elementType
}

class KtStringTemplateExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    val entries: List<KtStringTemplateEntry> get() = childrenOfType()

    /** True when nothing is interpolated, so the value is known without resolving anything. */
    val isPlain: Boolean get() = entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }
}

abstract class KtStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    open val expression: KtExpression? get() = firstChildOfType()
}

class KtLiteralStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtStringTemplateEntry(session, node) {
    override val expression: KtExpression? get() = null
}

class KtEscapeStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtStringTemplateEntry(session, node) {
    override val expression: KtExpression? get() = null
}

class KtSimpleNameStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtStringTemplateEntry(session, node)

class KtBlockStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtStringTemplateEntry(session, node)

class KtIfExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    val then: KtExpression? get() = child(KtNodeTypes.THEN)?.firstChildOfType()
    val `else`: KtExpression? get() = child(KtNodeTypes.ELSE)?.firstChildOfType()
}

class KtWhenExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    /**
     * `when (subject) { … }`, or null for the subjectless form.
     *
     * Entries are not expressions, so the subject is simply the first expression child — which also covers
     * `when (val v = f())`, whose subject is a declaration.
     */
    val subjectExpression: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull()

    val entries: List<KtWhenEntry> get() = childrenOfType()

    val elseExpression: KtExpression? get() = entries.firstOrNull { it.isElse }?.expression
}

class KtWhenEntry internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {

    val isElse: Boolean get() = hasChild(KtTokens.ELSE_KEYWORD)

    val conditions: List<KtWhenCondition> get() = childrenOfType()

    val guard: KtExpression? get() = child(KtNodeTypes.WHEN_ENTRY_GUARD)?.firstChildOfType()

    /** The entry's body, which is everything after the arrow. */
    val expression: KtExpression?
        get() {
            val arrow = session.tree.findChildByType(node, KtTokens.ARROW) ?: return null
            val at = session.tree.getStartOffset(arrow)
            return children.firstOrNull { it.textOffset > at } as? KtExpression
        }
}

abstract class KtWhenCondition internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node)

class KtWhenConditionWithExpression internal constructor(session: KtTreeSession, node: LightNode) : KtWhenCondition(session, node) {
    val expression: KtExpression? get() = firstChildOfType()
}

class KtWhenConditionInRange internal constructor(session: KtTreeSession, node: LightNode) : KtWhenCondition(session, node) {
    val rangeExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull { it !is KtOperationReferenceExpression }
    val isNegated: Boolean get() = hasChild(KtTokens.NOT_IN)
}

class KtWhenConditionIsPattern internal constructor(session: KtTreeSession, node: LightNode) : KtWhenCondition(session, node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val isNegated: Boolean get() = hasChild(KtTokens.NOT_IS)
}

class KtTryExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val tryBlock: KtBlockExpression? get() = firstChildOfType()
    val catchClauses: List<KtCatchClause> get() = childrenOfType()
    val finallyBlock: KtFinallySection? get() = firstChildOfType()
}

class KtCatchClause internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val parameterList: KtParameterList? get() = firstChildOfType()
    val catchParameter: KtParameter? get() = parameterList?.parameters?.firstOrNull()
    val catchBody: KtBlockExpression? get() = firstChildOfType()
}

class KtFinallySection internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {
    val finalExpression: KtBlockExpression? get() = firstChildOfType()
}

/**
 * `for`, `while` and `do`-`while`, mirroring `KtLoopExpression`.
 *
 * The editor backend walks loops through this base rather than the three concrete types, so it has to exist
 * even though nothing in the tree is named after it.
 */
abstract class KtLoopExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node) {
    abstract val body: KtExpression?
}

class KtForExpression internal constructor(session: KtTreeSession, node: LightNode) : KtLoopExpression(session, node) {
    val loopParameter: KtParameter? get() = firstChildOfType()
    val destructuringDeclaration: KtDestructuringDeclaration? get() = firstChildOfType()
    val loopRange: KtExpression? get() = child(KtNodeTypes.LOOP_RANGE)?.firstChildOfType()
    override val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtWhileExpression internal constructor(session: KtTreeSession, node: LightNode) : KtLoopExpression(session, node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    override val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtDoWhileExpression internal constructor(session: KtTreeSession, node: LightNode) : KtLoopExpression(session, node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    override val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtReturnExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val returnedExpression: KtExpression? get() = firstChildOfType()
    /** `return@label`, or null for a plain return. */
    fun getLabelName(): String? = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtThrowExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val thrownExpression: KtExpression? get() = firstChildOfType()
}

class KtBreakExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    fun getLabelName(): String? = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtContinueExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    fun getLabelName(): String? = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtThisExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    fun getLabelName(): String? = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtSuperExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val superTypeQualifier: KtTypeReference? get() = firstChildOfType()
    fun getLabelName(): String? = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtObjectLiteralExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val objectDeclaration: KtObjectDeclaration? get() = firstChildOfType()
}

class KtLabeledExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    fun getLabelName(): String? = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removeSuffix("@")
    val baseExpression: KtExpression? get() = firstChildOfType()
}

class KtAnnotatedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val baseExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()
}

class KtCollectionLiteralExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val innerExpressions: List<KtExpression> get() = childrenOfType()
}

/** A region the parser could not make sense of, mirroring `PsiErrorElement`. */
class KtErrorElement internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node)
