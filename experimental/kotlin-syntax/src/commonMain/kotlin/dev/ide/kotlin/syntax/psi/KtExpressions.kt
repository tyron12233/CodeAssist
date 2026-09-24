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
    val lBrace: KtElement? get() = child(KtTokens.LBRACE)
    val rBrace: KtElement? get() = child(KtTokens.RBRACE)
}

/** A bare name in expression position, mirroring `KtNameReferenceExpression`. */
class KtNameReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtSimpleNameExpression {
    override fun getReferencedName(): String = text.removeSurrounding("`")

    val identifier: KtElement? get() = child(KtTokens.IDENTIFIER)
}

/** The operator position of a unary or binary expression, mirroring `KtOperationReferenceExpression`. */
class KtOperationReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtSimpleNameExpression {
    /** The operator token, or null when the operator is an infix function name. */
    val operationSignTokenType: SyntaxElementType? get() = children.firstOrNull()?.elementType

    /**
     * The token behind the name, which is `IDENTIFIER` for an infix call and the operator sign otherwise.
     *
     * `a as B` and `a as? B` are the same node with different tokens here, which is the one way to tell them
     * apart without reading text.
     */
    fun getReferencedNameElementType(): SyntaxElementType? = operationSignTokenType ?: elementType

    override fun getReferencedName(): String = text
}

class KtCallExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    /** What is being called: a name, or another expression for an invoke. */
    val calleeExpression: KtExpression? get() = children.firstOrNull() as? KtExpression

    val valueArgumentList: KtValueArgumentList? get() = firstChildOfType()

    /**
     * Every argument, the trailing lambda INCLUDED.
     *
     * The obvious reading is "what is in the parentheses", and it is wrong: upstream appends the lambda
     * arguments, so an arity check over this list counts `f(a) { }` as two. A facade that stopped at the
     * parentheses would make every such rule off by one on exactly the calls Kotlin is written with.
     */
    val valueArguments: List<KtValueArgument>
        get() {
            val inParentheses = valueArgumentList?.arguments.orEmpty()
            val lambdas = lambdaArguments
            return if (lambdas.isEmpty()) inParentheses else inParentheses + lambdas
        }

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
open class KtValueArgument internal constructor(session: KtTreeSession, node: LightNode) :
    KtElement(session, node), ValueArgument {

    override fun getArgumentExpression(): KtExpression? = childrenOfType<KtExpression>().lastOrNull()

    /** `name = value` in a call: the name node, or null for a positional argument. */
    override fun getArgumentName(): KtValueArgumentName? =
        child(KtNodeTypes.VALUE_ARGUMENT_NAME) as? KtValueArgumentName

    val isSpread: Boolean get() = hasChild(KtTokens.MUL)

    /** The `*` of a spread argument, which is what an arity rule has to notice before counting. */
    fun getSpreadElement(): KtElement? = child(KtTokens.MUL)
}

/**
 * A trailing lambda. It extends [KtValueArgument] because upstream does, and because
 * [KtCallExpression.valueArguments] appends these to the parenthesised ones: a call site writes
 * `arg is KtLambdaArgument` over that list, which does not even compile if the two are unrelated.
 */
class KtLambdaArgument internal constructor(session: KtTreeSession, node: LightNode) :
    KtValueArgument(session, node) {
    /**
     * A function, not a property, because that is what upstream declares and both compile to the same JVM
     * getter — so a facade offering both cannot be loaded at all.
     */
    fun getLambdaExpression(): KtLambdaExpression? = firstChildOfType()

    /** A trailing lambda IS the argument, which is the whole reason [ValueArgument] is an interface. */
    override fun getArgumentExpression(): KtExpression? = getLambdaExpression()

    /** A trailing lambda is never named. */
    override fun getArgumentName(): KtValueArgumentName? = null
}

class KtLambdaExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    /** Non-null as upstream: a lambda expression IS its literal plus the braces around it. */
    val functionLiteral: KtFunctionLiteral get() = firstChildOfType()!!

    val valueParameters: List<KtParameter> get() = functionLiteral?.valueParameters.orEmpty()

    val bodyExpression: KtBlockExpression? get() = functionLiteral?.bodyExpression
}

class KtFunctionLiteral internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtFunction {

    // Non-null as upstream: a function literal is the braces and what is between them.
    val lBrace: KtElement get() = child(KtTokens.LBRACE)!!

    val rBrace: KtElement? get() = child(KtTokens.RBRACE)

    val arrow: KtElement? get() = child(KtTokens.ARROW)

    override val valueParameterList: KtParameterList? get() = firstChildOfType()

    override val valueParameters: List<KtParameter> get() = valueParameterList?.parameters.orEmpty()

    override val bodyExpression: KtBlockExpression? get() = firstChildOfType()

    override val bodyBlockExpression: KtBlockExpression? get() = bodyExpression

    /** A lambda is never written with `= expr`; the arrow is not an equals token. */
    override val equalsToken: KtElement? get() = null

    /** A lambda's body is always a block, even where the source wrote a single expression inside it. */
    override fun hasBlockBody(): Boolean = true

    override fun hasBody(): Boolean = bodyExpression != null

    /** A lambda with no declared parameters has an implicit `it`. */
    val hasParameterSpecification: Boolean get() = valueParameterList != null
}

/** `a.b` or `a?.b`, mirroring `KtQualifiedExpression`. */
abstract class KtQualifiedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {

    // The dot itself is a child too, so both sides are found by type rather than by position.

    /**
     * The left side, non-null as upstream: `a.b` is only parsed as a qualified expression when there is
     * something to the left of the dot. Use [receiverExpressionOrNull] where a broken parse is expected.
     */
    val receiverExpression: KtExpression get() = receiverExpressionOrNull!!

    val receiverExpressionOrNull: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull()

    val selectorExpression: KtExpression?
        get() = childrenOfType<KtExpression>().let { if (it.size > 1) it.last() else null }

    val operationTokenNode: KtElement
        get() = (child(KtTokens.DOT) ?: child(KtTokens.SAFE_ACCESS))!!
}

class KtDotQualifiedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtQualifiedExpression(session, node)

class KtSafeQualifiedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtQualifiedExpression(session, node)

class KtBinaryExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtOperationExpression {

    val left: KtExpression? get() = childrenOfType<KtExpression>().firstOrNull { it !is KtOperationReferenceExpression }

    // Non-null, as upstream: these nodes are parsed around their operator, so one without a reference
    // cannot be built, and the backend reads it straight through at 37 call sites.
    override val operationReference: KtOperationReferenceExpression get() = firstChildOfType()!!

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

    /**
     * `!is` rather than `is`.
     *
     * The operator is NOT a direct child: the parser wraps it in an OPERATION_REFERENCE, so looking for a
     * `NOT_IS` child answers false for every negated check, and a guard like `if (a !is Dog) return` reads as
     * its own opposite. Upstream asks the operation reference the same way.
     */
    val isNegated: Boolean
        get() = firstChildOfType<KtOperationReferenceExpression>()?.operationSignTokenType == KtTokens.NOT_IS
}

/** `a as B` / `a as? B`, mirroring `KtBinaryExpressionWithTypeRHS`. */
class KtBinaryExpressionWithTypeRHS internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtOperationExpression {
    val left: KtExpression? get() = children.firstOrNull() as? KtExpression
    val right: KtTypeReference? get() = firstChildOfType()
    // Non-null, as upstream: these nodes are parsed around their operator, so one without a reference
    // cannot be built, and the backend reads it straight through at 37 call sites.
    override val operationReference: KtOperationReferenceExpression get() = firstChildOfType()!!
}

class KtPrefixExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtUnaryExpression(session, node) {
    // Non-null, as upstream: these nodes are parsed around their operator, so one without a reference
    // cannot be built, and the backend reads it straight through at 37 call sites.
    override val operationReference: KtOperationReferenceExpression get() = firstChildOfType()!!
    override val baseExpression: KtExpression?
        get() = childrenOfType<KtExpression>().lastOrNull { it !is KtOperationReferenceExpression }
}

class KtPostfixExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtUnaryExpression(session, node) {
    // Non-null, as upstream: these nodes are parsed around their operator, so one without a reference
    // cannot be built, and the backend reads it straight through at 37 call sites.
    override val operationReference: KtOperationReferenceExpression get() = firstChildOfType()!!
    override val baseExpression: KtExpression?
        get() = childrenOfType<KtExpression>().firstOrNull { it !is KtOperationReferenceExpression }
}

class KtParenthesizedExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val expression: KtExpression? get() = firstChildOfType()
}

class KtArrayAccessExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val arrayExpression: KtExpression? get() = children.firstOrNull() as? KtExpression
    val indexExpressions: List<KtExpression> get() = child(KtNodeTypes.INDICES)?.childrenOfType<KtExpression>().orEmpty()
}

class KtCallableReferenceExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtDoubleColonExpression(session, node) {
    override val receiverExpression: KtExpression? get() = children.firstOrNull()?.takeIf { it.textOffset < textOffset + 2 } as? KtExpression
    val callableReference: KtNameReferenceExpression? get() = childrenOfType<KtNameReferenceExpression>().lastOrNull()
}

class KtClassLiteralExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtDoubleColonExpression(session, node) {
    override val receiverExpression: KtExpression? get() = children.firstOrNull() as? KtExpression
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

/**
 * The two entries that interpolate something: `$name` and `${expr}`.
 *
 * Literal and escape entries deliberately do NOT implement this, which is the whole point of the interface:
 * a constant-folding walk asks `entry is KtStringTemplateEntryWithExpression` rather than asking every entry
 * for an expression and getting null from the ones that can never have one.
 */

class KtLiteralStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtStringTemplateEntry(session, node) {
    override val expression: KtExpression? get() = null
}

class KtEscapeStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) : KtStringTemplateEntry(session, node) {
    override val expression: KtExpression? get() = null

    /**
     * What the escape stands for: `\n` becomes a newline, `\u0041` becomes `A`.
     *
     * A constant folder needs the VALUE, not the four characters that spell it, or `"a\nb"` compares unequal
     * to the string it actually is.
     */
    val unescapedValue: String
        get() {
            val raw = text
            if (raw.length < 2 || raw[0] != '\\') return raw
            return when (val code = raw[1]) {
                't' -> "\t"
                'b' -> "\b"
                'n' -> "\n"
                'r' -> "\r"
                '\'' -> "'"
                '"' -> "\""
                '\\' -> "\\"
                '$' -> "$"
                'u' -> raw.drop(2).take(4).toIntOrNull(16)?.toChar()?.toString() ?: raw
                else -> code.toString()
            }
        }
}

class KtSimpleNameStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtStringTemplateEntry(session, node), KtStringTemplateEntryWithExpression

class KtBlockStringTemplateEntry internal constructor(session: KtTreeSession, node: LightNode) :
    KtStringTemplateEntry(session, node), KtStringTemplateEntryWithExpression

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

    /** `when (val v = f())`: the subject declared in place, which is in scope in every branch. */
    val subjectVariable: KtProperty? get() = firstChildOfType()

    val entries: List<KtWhenEntry> get() = childrenOfType()

    val elseExpression: KtExpression? get() = entries.firstOrNull { it.isElse }?.expression

    /** Non-null as upstream: a `when` node is only built after the keyword has been read. */
    val whenKeyword: KtElement get() = child(KtTokens.WHEN_KEYWORD)!!

    val openBrace: KtElement? get() = child(KtTokens.LBRACE)

    val closeBrace: KtElement? get() = child(KtTokens.RBRACE)
}

class KtWhenEntry internal constructor(session: KtTreeSession, node: LightNode) : KtElement(session, node) {

    val isElse: Boolean get() = hasChild(KtTokens.ELSE_KEYWORD)

    val conditions: List<KtWhenCondition> get() = childrenOfType()

    val guard: KtExpression? get() = child(KtNodeTypes.WHEN_ENTRY_GUARD)?.firstChildOfType()

    /** The entry's body, which is everything after the arrow. */
    val expression: KtExpression?
        get() {
            val arrow = session.tree.findChildByType(node, KtTokens.ARROW) ?: return null
            val at = session.tree.getStartOffset(arrow) + session.baseOffset
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

    /** `!in` rather than `in`; see [KtIsExpression.isNegated] for why the operator is not a direct child. */
    val isNegated: Boolean
        get() = firstChildOfType<KtOperationReferenceExpression>()?.operationSignTokenType == KtTokens.NOT_IN
}

class KtWhenConditionIsPattern internal constructor(session: KtTreeSession, node: LightNode) : KtWhenCondition(session, node) {
    val typeReference: KtTypeReference? get() = firstChildOfType()

    /** `!is` rather than `is`; see [KtIsExpression.isNegated] for why the operator is not a direct child. */
    val isNegated: Boolean get() = hasChild(KtTokens.NOT_IS) ||
        firstChildOfType<KtOperationReferenceExpression>()?.operationSignTokenType == KtTokens.NOT_IS
}

class KtTryExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    /** Non-null as upstream: `try` without a block does not parse as a try expression. */
    val tryBlock: KtBlockExpression get() = firstChildOfType()!!
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

    /**
     * `(k, v)` in `for ((k, v) in m)`.
     *
     * Through the loop parameter, not a direct child: the parser puts the destructuring INSIDE a
     * VALUE_PARAMETER, so a direct-child lookup answers null and a destructured loop variable is invisible,
     * which is how both entries lost their highlighting. Upstream reads it off the loop parameter too.
     */
    val destructuringDeclaration: KtDestructuringDeclaration?
        get() = loopParameter?.destructuringDeclaration
    val loopRange: KtExpression? get() = child(KtNodeTypes.LOOP_RANGE)?.firstChildOfType()
    override val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

class KtWhileExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtWhileExpressionBase(session, node)

class KtDoWhileExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtWhileExpressionBase(session, node)

class KtReturnExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpressionWithLabel(session, node) {
    val returnedExpression: KtExpression? get() = firstChildOfType()
}

class KtThrowExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val thrownExpression: KtExpression? get() = firstChildOfType()
}

class KtBreakExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpressionWithLabel(session, node)

class KtContinueExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpressionWithLabel(session, node)

class KtThisExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtInstanceExpressionWithLabel(session, node)

class KtSuperExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtInstanceExpressionWithLabel(session, node) {
    val superTypeQualifier: KtTypeReference? get() = firstChildOfType()
}

class KtObjectLiteralExpression internal constructor(session: KtTreeSession, node: LightNode) : KtExpression(session, node) {
    val objectDeclaration: KtObjectDeclaration? get() = firstChildOfType()
}

class KtLabeledExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpressionWithLabel(session, node) {
    // A label WRITES `outer@ while (...)`, where every other label site READS `break@outer`, so the
    // `@` lands on the other end of the text.
    override fun getLabelName(): String? = labelQualifier?.text?.removeSuffix("@")

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
