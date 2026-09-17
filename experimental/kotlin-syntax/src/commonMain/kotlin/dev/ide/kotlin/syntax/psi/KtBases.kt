package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.SyntaxElementType
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode

/**
 * The bases the compiler's PSI declares and a call site names, even though it never constructs one.
 *
 * A backend written against `org.jetbrains.kotlin.psi` says `fun f(d: KtDeclarationWithBody)` and
 * `is KtAnnotated`, not the concrete types, because the whole point of the hierarchy is that a rule about
 * "anything with a body" is written once. The facade modelled the leaves first and these were missing, so
 * every such call site failed to compile against it. That is the same lesson `KtLoopExpression` taught: the
 * backend walks through the BASE, so the base has to exist even where nothing is ever an instance of only
 * that.
 *
 * They are interfaces here rather than the abstract classes some are upstream, because a type is often
 * several of them at once: a `KtNamedFunction` is annotated AND has a modifier list AND has type parameters
 * AND has a body. The members resolve off [KtElement], which already carries them, so a class satisfies one
 * of these by naming it and nothing else.
 */

/**
 * What `PsiElement` is: the tree surface every element has, declared where an INTERFACE can inherit it.
 *
 * The bases below are interfaces because upstream's hierarchy is not this one — a function literal is a
 * declaration upstream and an expression here — and a Kotlin interface cannot extend a class. Without this,
 * the backend's `owner.getParentOfType<…>()` on a value typed `KtTypeParameterListOwner` has no receiver,
 * which fails at the call site rather than anywhere near type parameters.
 */
interface KtPsiElement {
    val elementType: SyntaxElementType
    val text: String
    val textOffset: Int
    val textLength: Int
    val textRange: TextRange
    val parent: KtElement?
    val children: List<KtElement>
    val containingKtFile: KtFile
}

/** Anything that can carry annotation entries. */
interface KtAnnotated : KtPsiElement {
    val annotationEntries: List<KtAnnotationEntry>
}

/** Anything that can carry a modifier list, and so can be asked whether it has a given modifier. */
interface KtModifierListOwner : KtPsiElement {
    val modifierList: KtModifierList?
    fun hasModifier(modifier: com.intellij.platform.syntax.SyntaxElementType): Boolean
}

/** Anything that declares its own type parameters: a class, a function, a property, a type alias. */
interface KtTypeParameterListOwner : KtPsiElement {
    val typeParameterList: KtTypeParameterList?
    val typeParameters: List<KtTypeParameter>
    val typeConstraintList: KtTypeConstraintList?
    val typeConstraints: List<KtTypeConstraint>
}

/**
 * A declaration with executable contents: a named function, a property accessor, a lambda's literal.
 *
 * The body is EITHER a block or an expression, never both, which is why both accessors exist and why a
 * caller that only reads [bodyExpression] silently ignores every block-bodied function.
 */
interface KtDeclarationWithBody : KtPsiElement {
    val bodyExpression: KtExpression?
    val bodyBlockExpression: KtBlockExpression?

    /** The `=` of an expression body, which is where a quick fix converting to a block body cuts. */
    val equalsToken: KtElement?

    val valueParameters: List<KtParameter>

    // Functions, not properties: `hasX()` is not a Java getter prefix, so Kotlin sees a METHOD, and a
    // call site written against the compiler's PSI writes the parentheses. The facade learned this the
    // hard way once already.
    fun hasBlockBody(): Boolean
    fun hasBody(): Boolean
}

/**
 * One argument at a call site.
 *
 * An interface upstream because a `KtLambdaArgument` is one without being a `KtValueArgument`: a trailing
 * lambda is an argument that was written outside the parentheses. A resolver that iterated only
 * `valueArguments` would miss every trailing lambda in the language.
 */
interface ValueArgument : KtPsiElement {
    fun getArgumentExpression(): KtExpression?

    /** The `name` of `name = value`, or null when the argument is positional. */
    fun getArgumentName(): KtValueArgumentName?

    fun isNamed(): Boolean = getArgumentName() != null
}

/**
 * A bare name in expression position: a reference, an operator, a label.
 *
 * An interface rather than a class because the facade's chain is not upstream's. Upstream a function
 * literal is a DECLARATION; here it is an expression, and re-parenting it to fix that would change what
 * every existing `KtLambdaExpression.functionLiteral` call site sees. Interfaces let a type join a family
 * without leaving the one it is already in, which is what these families are for.
 */
interface KtSimpleNameExpression : KtPsiElement {
    fun getReferencedName(): String
}

/**
 * Anything written with an operator: `a + b`, `-x`, `x++`, `a as B`.
 *
 * `operationReference` is NOT nullable, which upstream reaches by being Java. Every one of these nodes is
 * parsed around its operator, so there is no such thing as one without a reference, and 37 call sites read
 * it straight through.
 */
interface KtOperationExpression : KtPsiElement {
    val operationReference: KtOperationReferenceExpression

    /** The operator token's own element, which a formatter aligns on. */
    val operationTokenNode: KtElement get() = operationReference
}

/**
 * An abstract class, not an interface, because upstream `KtUnaryExpression` extends `KtExpression` and the
 * backend passes one straight into code that wants an element: `unsupported("unary without operand", e)`.
 * A bare interface compiles until the first such call and then fails somewhere with nothing to do with
 * unary expressions.
 */
abstract class KtUnaryExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node), KtOperationExpression {
    abstract val baseExpression: KtExpression?
    val operationToken: SyntaxElementType? get() = operationReference.operationSignTokenType
}

/** `break@loop`, `return@run`, `this@Foo`: an expression carrying an optional label. */
open class KtExpressionWithLabel internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node) {

    val labelQualifier: KtElement? get() = child(KtNodeTypes.LABEL_QUALIFIER)

    /**
     * The label, without its `@`.
     *
     * A label is WRITTEN `loop@ while (…)` and READ `break@loop`, so the `@` sits on opposite ends of the
     * same text; [KtLabeledExpression] overrides this for the writing side.
     */
    open fun getLabelName(): String? = labelQualifier?.text?.removePrefix("@")

    /**
     * The `@name` token itself, which is what a highlighter colours.
     *
     * Every label site holds it the same way, so this is settled once here rather than repeated on the six
     * expressions that can carry one.
     */
    fun getTargetLabel(): KtLabelReferenceExpression? = labelQualifier?.firstChildOfType()
}

/** `this@Foo` and `super@Foo`, the two labelled instance expressions. */
abstract class KtInstanceExpressionWithLabel internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpressionWithLabel(session, node) {
    /** The `this` or `super` word, which a highlighter colours as a keyword even inside a template. */
    val instanceReference: KtElement get() = firstChildOfType<KtNameReferenceExpression>() ?: this
}

/**
 * Anything callable that is declared: a named function, a constructor, a function literal.
 *
 * The family a rule about "a function" is written against. It includes the lambda's literal, which is why
 * this is an interface: the literal is an expression in this facade and a declaration upstream, and an
 * `is KtFunction` that missed lambdas would be quietly wrong in every rule about function bodies.
 */
interface KtFunction : KtDeclarationWithBody {
    val valueParameterList: KtParameterList?
}

/**
 * `Foo::class` and `Foo::member`: the two expressions written with `::`.
 *
 * Both are reached the same way from a name reference: walk up, and if the parent is one of these, the name
 * is a reflection target rather than a value being read.
 */
abstract class KtDoubleColonExpression internal constructor(session: KtTreeSession, node: LightNode) :
    KtExpression(session, node) {
    abstract val receiverExpression: KtExpression?
}

/** `while (c) { }` and `do { } while (c)`, which differ only in where the condition is written. */
abstract class KtWhileExpressionBase internal constructor(session: KtTreeSession, node: LightNode) :
    KtLoopExpression(session, node) {
    val condition: KtExpression? get() = child(KtNodeTypes.CONDITION)?.firstChildOfType()
    override val body: KtExpression? get() = child(KtNodeTypes.BODY)?.firstChildOfType()
}

/** `Foo`, `Foo?`, `() -> Unit`, `dynamic`: the shapes a type reference can wrap. */
interface KtTypeElement : KtPsiElement

/** A `$name` or `${expr}` inside a string template, as opposed to its literal text. */
interface KtStringTemplateEntryWithExpression : KtPsiElement {
    val expression: KtExpression?
}

/** An `init { }` block. Named for what it is upstream, where a class initializer is one of two kinds. */
interface KtAnonymousInitializer : KtPsiElement {
    val body: KtExpression?
}

/** The variance written at a use site: `out T`, `in T`, `*`, or nothing. */
enum class KtProjectionKind(val label: String) {
    IN("in"),
    OUT("out"),
    STAR("*"),
    NONE(""),
}
