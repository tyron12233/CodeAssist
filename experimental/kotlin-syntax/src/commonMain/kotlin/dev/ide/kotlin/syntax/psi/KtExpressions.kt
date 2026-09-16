package dev.ide.kotlin.syntax.psi

import dev.ide.kotlin.syntax.KtNodeTypes
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.AstNode
import dev.ide.kotlin.syntax.tree.IElementType

/**
 * The expression half of the facade, mirroring `org.jetbrains.kotlin.psi`'s expression classes.
 *
 * The one shape worth internalising is qualified access, because it is not what the surface syntax suggests.
 * `foo.bar(1)` is a [KtDotQualifiedExpression] whose receiver is a [KtNameReferenceExpression] and whose
 * selector is a [KtCallExpression] — the call wraps only `bar(1)`, not the dot. Everything that resolves a
 * member goes through [KtQualifiedExpression.receiverExpression] and [KtQualifiedExpression.selectorExpression]
 * for that reason.
 */
open class KtExpression(node: AstNode) : KtElement(node)

/** A code block, mirroring `KtBlockExpression`. */
class KtBlockExpression(node: AstNode) : KtExpression(node) {
    val statements: List<KtExpression> get() = childrenOfType()
}

/** A bare name in expression position, mirroring `KtNameReferenceExpression`. */
class KtNameReferenceExpression(node: AstNode) : KtExpression(node) {
    fun getReferencedName(): String = text.removeSurrounding("`")

    val getIdentifier: AstNode? get() = node.findChildByType(KtTokens.IDENTIFIER)
}

/** The operator position of a unary or binary expression, mirroring `KtOperationReferenceExpression`. */
class KtOperationReferenceExpression(node: AstNode) : KtExpression(node) {
    /** The operator token, or null when the operator is an infix function name. */
    val operationSignTokenType: IElementType? get() = node.firstChildNode?.elementType

    fun getReferencedName(): String = text
}

class KtCallExpression(node: AstNode) : KtExpression(node) {

    /** What is being called: a name, or another expression for an invoke. */
    val calleeExpression: KtExpression? get() = children.firstOrNull() as? KtExpression

    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()

    /** Arguments in the parentheses, WITHOUT the trailing lambda. */
    val valueArguments: List<KtValueArgument> get() = valueArgumentList?.arguments.orEmpty()

    val lambdaArguments: List<KtLambdaArgument> get() = childrenOfType()

    val typeArgumentList: KtTypeArgumentList? get() = firstChildOfType()

    val typeArguments: List<KtTypeProjection> get() = typeArgumentList?.arguments.orEmpty()
}

class KtValueArgumentList(node: AstNode) : KtElement(node) {
    val arguments: List<KtValueArgument> get() = childrenOfType()
}

class KtValueArgument(node: AstNode) : KtElement(node) {

    val argumentExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()

    /** `name = value` in a call: the name, or null for a positional argument. */
    val argumentName: String? get() = child(KtNodeTypes.VALUE_ARGUMENT_NAME)?.text

    val isSpread: Boolean get() = node.children.any { it.elementType === KtTokens.MUL }
}

/** A lambda passed outside the parentheses, mirroring `KtLambdaArgument`. */
class KtLambdaArgument(node: AstNode) : KtElement(node) {
    val lambdaExpression: KtLambdaExpression? get() = firstChildOfType()
}

class KtLambdaExpression(node: AstNode) : KtExpression(node) {

    val functionLiteral: KtFunctionLiteral? get() = firstChildOfType()

    val valueParameters: List<KtParameter> get() = functionLiteral?.valueParameters.orEmpty()

    val bodyExpression: KtBlockExpression? get() = functionLiteral?.bodyExpression
}

class KtFunctionLiteral(node: AstNode) : KtExpression(node) {

    val valueParameterList: KtParameterList? get() = firstChildOfType()

    val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()

    val bodyExpression: KtBlockExpression? get() = firstChildOfType()

    /** A lambda with no declared parameters has an implicit `it`. */
    val hasParameterSpecification: Boolean get() = valueParameterList != null
}

/** `a.b` or `a?.b`, mirroring `KtQualifiedExpression`. */
abstract class KtQualifiedExpression(node: AstNode) : KtExpression(node) {

    // The dot itself is a child too, so both sides are found by type rather than by position.
    val receiverExpression: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull()

    val selectorExpression: KtExpression?
        get() = childrenOfType<KtExpression>().let { if (it.size > 1) it.last() else null }

    val operationTokenNode: AstNode?
        get() = node.findChildByType(KtTokens.DOT) ?: node.findChildByType(KtTokens.SAFE_ACCESS)
}

class KtDotQualifiedExpression(node: AstNode) : KtQualifiedExpression(node)

class KtSafeQualifiedExpression(node: AstNode) : KtQualifiedExpression(node)

class KtBinaryExpression(node: AstNode) : KtExpression(node) {

    val left: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull { it !is KtOperationReferenceExpression }

    val operationReference: KtOperationReferenceExpression? get() = firstChildOfType()

    val operationToken: IElementType? get() = operationReference?.operationSignTokenType

    val right: KtExpression?
        get() {
            val operation = operationReference ?: return null
            return childrenOfType<KtExpression>().lastOrNull { it.textOffset > operation.textOffset }
        }
}

/** `a is B` / `a !is B`, mirroring `KtIsExpression`. */
class KtIsExpression(node: AstNode) : KtExpression(node) {
    val leftHandSide: KtExpression? get() = children.firstOrNull() as? KtExpression
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val isNegated: Boolean get() = node.children.any { it.elementType === KtTokens.NOT_IS }
}

/** `a as B` / `a as? B`, mirroring `KtBinaryExpressionWithTypeRHS`. */
class KtBinaryExpressionWithTypeRHS(node: AstNode) : KtExpression(node) {
    val left: KtExpression? get() = children.firstOrNull() as? KtExpression
    val right: KtTypeReference? get() = firstChildOfType()
}

class KtPrefixExpression(node: AstNode) : KtExpression(node) {
    val operationReference: KtOperationReferenceExpression? get() = firstChildOfType()
    val operationToken: IElementType? get() = operationReference?.operationSignTokenType
    val baseExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull { it !is KtOperationReferenceExpression }
}

class KtPostfixExpression(node: AstNode) : KtExpression(node) {
    val operationReference: KtOperationReferenceExpression? get() = firstChildOfType()
    val operationToken: IElementType? get() = operationReference?.operationSignTokenType
    val baseExpression: KtExpression?
        get() = childrenOfType<KtExpression>().firstOrNull { it !is KtOperationReferenceExpression }
}

class KtParenthesizedExpression(node: AstNode) : KtExpression(node) {
    val expression: KtExpression? get() = firstChildOfType()
}

class KtArrayAccessExpression(node: AstNode) : KtExpression(node) {
    val arrayExpression: KtExpression? get() = children.firstOrNull() as? KtExpression
    val indexExpressions: List<KtExpression> get() = child(KtNodeTypes.INDICES)?.childrenOfType<KtExpression>().orEmpty()
}

class KtCallableReferenceExpression(node: AstNode) : KtExpression(node) {
    val receiverExpression: KtExpression? get() = children.firstOrNull()?.takeIf { it.textOffset < textOffset + 2 } as? KtExpression
    val callableReference: KtNameReferenceExpression? get() = childrenOfType<KtNameReferenceExpression>().lastOrNull()
}

class KtClassLiteralExpression(node: AstNode) : KtExpression(node) {
    val receiverExpression: KtExpression? get() = children.firstOrNull() as? KtExpression
}

class KtConstantExpression(node: AstNode) : KtExpression(node) {
    /** INTEGER_CONSTANT, FLOAT_CONSTANT, BOOLEAN_CONSTANT, CHARACTER_CONSTANT or NULL. */
    val constantType: IElementType get() = elementType
}

class KtStringTemplateExpression(node: AstNode) : KtExpression(node) {

    val entries: List<KtStringTemplateEntry> get() = childrenOfType()

    /** True when nothing is interpolated, so the value is known without resolving anything. */
    val isPlain: Boolean get() = entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }
}

abstract class KtStringTemplateEntry(node: AstNode) : KtElement(node) {
    open val expression: KtExpression? get() = firstChildOfType()
}

class KtLiteralStringTemplateEntry(node: AstNode) : KtStringTemplateEntry(node) {
    override val expression: KtExpression? get() = null
}

class KtEscapeStringTemplateEntry(node: AstNode) : KtStringTemplateEntry(node) {
    override val expression: KtExpression? get() = null
}

class KtSimpleNameStringTemplateEntry(node: AstNode) : KtStringTemplateEntry(node)

class KtBlockStringTemplateEntry(node: AstNode) : KtStringTemplateEntry(node)

class KtIfExpression(node: AstNode) : KtExpression(node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    val then: KtExpression? get() = child(KtNodeTypes.THEN)?.firstChildOfType()
    val `else`: KtExpression? get() = child(KtNodeTypes.ELSE)?.firstChildOfType()
}

class KtWhenExpression(node: AstNode) : KtExpression(node) {

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

class KtWhenEntry(node: AstNode) : KtElement(node) {

    val isElse: Boolean get() = node.children.any { it.elementType === KtTokens.ELSE_KEYWORD }

    val conditions: List<KtWhenCondition> get() = childrenOfType()

    val guard: KtExpression? get() = child(KtNodeTypes.WHEN_ENTRY_GUARD)?.firstChildOfType()

    /** The entry's body, which is everything after the arrow. */
    val expression: KtExpression?
        get() {
            val arrow = node.children.firstOrNull { it.elementType === KtTokens.ARROW } ?: return null
            return children.firstOrNull { it.textOffset > arrow.startOffset } as? KtExpression
        }
}

abstract class KtWhenCondition(node: AstNode) : KtElement(node)

class KtWhenConditionWithExpression(node: AstNode) : KtWhenCondition(node) {
    val expression: KtExpression? get() = firstChildOfType()
}

class KtWhenConditionInRange(node: AstNode) : KtWhenCondition(node) {
    val rangeExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull { it !is KtOperationReferenceExpression }
    val isNegated: Boolean get() = node.children.any { it.elementType === KtTokens.NOT_IN }
}

class KtWhenConditionIsPattern(node: AstNode) : KtWhenCondition(node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()
    val isNegated: Boolean get() = node.children.any { it.elementType === KtTokens.NOT_IS }
}

class KtTryExpression(node: AstNode) : KtExpression(node) {
    val tryBlock: KtBlockExpression? get() = firstChildOfType()
    val catchClauses: List<KtCatchClause> get() = childrenOfType()
    val finallyBlock: KtFinallySection? get() = firstChildOfType()
}

class KtCatchClause(node: AstNode) : KtElement(node) {
    val parameterList: KtParameterList? get() = firstChildOfType()
    val catchParameter: KtParameter? get() = parameterList?.parameters?.firstOrNull()
    val catchBody: KtBlockExpression? get() = firstChildOfType()
}

class KtFinallySection(node: AstNode) : KtElement(node) {
    val finalExpression: KtBlockExpression? get() = firstChildOfType()
}

class KtForExpression(node: AstNode) : KtExpression(node) {
    val loopParameter: KtParameter? get() = firstChildOfType()
    val destructuringDeclaration: KtDestructuringDeclaration? get() = firstChildOfType()
    val loopRange: KtExpression? get() = child(KtNodeTypes.LOOP_RANGE)?.firstChildOfType()
    val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtWhileExpression(node: AstNode) : KtExpression(node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtDoWhileExpression(node: AstNode) : KtExpression(node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtReturnExpression(node: AstNode) : KtExpression(node) {
    val returnedExpression: KtExpression? get() = firstChildOfType()
    /** `return@label`, or null for a plain return. */
    val labelName: String? get() = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtThrowExpression(node: AstNode) : KtExpression(node) {
    val thrownExpression: KtExpression? get() = firstChildOfType()
}

class KtBreakExpression(node: AstNode) : KtExpression(node) {
    val labelName: String? get() = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtContinueExpression(node: AstNode) : KtExpression(node) {
    val labelName: String? get() = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtThisExpression(node: AstNode) : KtExpression(node) {
    val labelName: String? get() = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtSuperExpression(node: AstNode) : KtExpression(node) {
    val superTypeQualifier: KtTypeReference? get() = firstChildOfType()
    val labelName: String? get() = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removePrefix("@")
}

class KtObjectLiteralExpression(node: AstNode) : KtExpression(node) {
    val objectDeclaration: KtObjectDeclaration? get() = firstChildOfType()
}

class KtLabeledExpression(node: AstNode) : KtExpression(node) {
    val labelName: String? get() = child(KtNodeTypes.LABEL_QUALIFIER)?.text?.removeSuffix("@")
    val baseExpression: KtExpression? get() = firstChildOfType()
}

class KtAnnotatedExpression(node: AstNode) : KtExpression(node) {
    val baseExpression: KtExpression? get() = childrenOfType<KtExpression>().lastOrNull()
}

class KtCollectionLiteralExpression(node: AstNode) : KtExpression(node) {
    val innerExpressions: List<KtExpression> get() = childrenOfType()
}

/** A region the parser could not make sense of, mirroring `PsiErrorElement`. */
class KtErrorElement(node: AstNode) : KtElement(node)
