package dev.ide.lang.patterns

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.argumentNodes
import dev.ide.lang.dom.calleeName

/**
 * A composable, IntelliJ-`ElementPattern`-style matcher. A [CompletionContributor] (and any other
 * pattern-driven extension) declares *where* it applies by an [ElementPattern] over the neutral
 * [DomNode] tree, so plugins target positions ("a name reference inside a call argument whose callee is
 * `Text`") without owning a language backend or re-walking the PSI themselves.
 *
 * The design mirrors `dev.ide.lang.dom`'s neutrality: patterns match the backend-agnostic DOM, never a
 * concrete parser's nodes, so one pattern works across JDT / Kotlin / XML. Build them with [DomPatterns]
 * (DOM-specific conditions) and combine with [StandardPatterns] (`or`/`and`/`not`).
 */
fun interface ElementPattern<T> {
    /** True when [t] satisfies this pattern. A null candidate never matches. */
    fun accepts(t: T?): Boolean
}

/**
 * Base for a pattern accumulating AND-ed conditions. [withCondition] returns `this` (typed as [Self]) so
 * conditions chain fluently: `node().withKind(NAME_REF).inside(call())`.
 */
abstract class ObjectPattern<T, Self : ObjectPattern<T, Self>> : ElementPattern<T> {
    private val conditions = ArrayList<(T) -> Boolean>()

    @Suppress("UNCHECKED_CAST")
    protected fun withCondition(cond: (T) -> Boolean): Self {
        conditions.add(cond)
        return this as Self
    }

    override fun accepts(t: T?): Boolean = t != null && conditions.all { it(t) }
}

/** Conditions over a [DomNode] — kind, text, and structural (parent / ancestor / child) matchers. */
class DomNodePattern internal constructor() : ObjectPattern<DomNode, DomNodePattern>() {

    fun withKind(kind: NodeKind): DomNodePattern = withCondition { it.kind == kind }
    fun withKind(kinds: Set<NodeKind>): DomNodePattern = withCondition { it.kind in kinds }

    fun withText(text: String): DomNodePattern = withCondition { it.text().toString() == text }
    fun withText(regex: Regex): DomNodePattern = withCondition { regex.matches(it.text()) }
    fun withTextContaining(s: String): DomNodePattern = withCondition { it.text().contains(s) }

    /** The node's *direct* parent matches [parent]. */
    fun withParent(parent: ElementPattern<DomNode>): DomNodePattern = withCondition { parent.accepts(it.parent) }
    fun withParent(kind: NodeKind): DomNodePattern = withParent(DomPatterns.node().withKind(kind))

    /** Some ancestor (any depth) matches [ancestor]. */
    fun inside(ancestor: ElementPattern<DomNode>): DomNodePattern = withCondition {
        var p = it.parent
        while (p != null) {
            if (ancestor.accepts(p)) return@withCondition true
            p = p.parent
        }
        false
    }
    fun inside(kind: NodeKind): DomNodePattern = inside(DomPatterns.node().withKind(kind))

    /** Some direct child matches [child]. */
    fun withChild(child: ElementPattern<DomNode>): DomNodePattern = withCondition { n -> n.children.any { child.accepts(it) } }

    /** Free-form extra condition. */
    fun where(cond: (DomNode) -> Boolean): DomNodePattern = withCondition(cond)

    // --- calls -------------------------------------------------------------
    //
    // A call's shape is the one place the backends still differ after the neutral ARGUMENT_LIST: Kotlin
    // wraps each argument in an ARGUMENT node and Java does not. These four absorb that, so a pattern
    // about a call is written once. See [dev.ide.lang.dom.argumentNodes].

    /**
     * The node is a call whose callee is SPELLED [name]: `Color(…)`, `graphics.Color(…)`, `Color<Int>(…)`
     * and Java's `new Color(…)` all match `withCalleeNamed("Color")`.
     *
     * A name, not a symbol. Two different `Color`s in scope are indistinguishable here, which is the
     * point: this is the cheap gate to run over every call in a file before resolving the few that pass.
     */
    fun withCalleeNamed(name: String): DomNodePattern = withCondition { it.calleeName() == name }

    /** Some argument of this call matches [argument], whichever shape the backend's tree has. */
    fun withArgument(argument: ElementPattern<DomNode>): DomNodePattern =
        withCondition { n -> n.argumentNodes().any { argument.accepts(it) } }

    /** The argument of this call at [index] (0-based) matches [argument]; no match when there are fewer. */
    fun withArgumentAt(index: Int, argument: ElementPattern<DomNode>): DomNodePattern =
        withCondition { n -> argument.accepts(n.argumentNodes().getOrNull(index)) }

    /**
     * This node IS an argument of a call matching [call] — the inverse of [withArgument], for a pattern
     * whose subject is the argument (a completion position, a literal to decorate).
     *
     * Climbs through Kotlin's [NodeKind.ARGUMENT] wrapper when there is one, so the same pattern holds on
     * a Java tree where the argument hangs off the list directly.
     */
    fun asArgumentOf(call: ElementPattern<DomNode>): DomNodePattern = withCondition { n ->
        var up = n.parent
        if (up != null && up.kind == NodeKind.ARGUMENT) up = up.parent
        up != null && up.kind == NodeKind.ARGUMENT_LIST && call.accepts(up.parent)
    }
}

/** Factory for [DomNode] patterns. */
object DomPatterns {
    fun node(): DomNodePattern = DomNodePattern()
    fun node(kind: NodeKind): DomNodePattern = DomNodePattern().withKind(kind)

    fun nameRef(): DomNodePattern = node(NodeKind.NAME_REF)
    fun memberAccess(): DomNodePattern = node(NodeKind.MEMBER_ACCESS)
    fun call(): DomNodePattern = node(NodeKind.METHOD_CALL)
    fun typeRef(): DomNodePattern = node(NodeKind.TYPE_REF)

    /** A non-string constant: a number, a boolean, a char, `null`. Strings are [stringLiteral]. */
    fun literal(): DomNodePattern = node(NodeKind.LITERAL)

    /** A string literal in any language: Java's `"x"` and text blocks, Kotlin's `"x"` and `"""…"""`. */
    fun stringLiteral(): DomNodePattern = node(NodeKind.STRING_LITERAL)

    /** Any constant, string or not. */
    fun anyLiteral(): DomNodePattern = node().withKind(setOf(NodeKind.LITERAL, NodeKind.STRING_LITERAL))

    /** The `(…)` of a call. */
    fun argumentList(): DomNodePattern = node(NodeKind.ARGUMENT_LIST)

    /** Java's `new Foo(…)`, Kotlin's `: Base(…)`. A Kotlin `Foo()` is a [call] — see [invocation]. */
    fun constructorCall(): DomNodePattern = node(NodeKind.CONSTRUCTOR_CALL)

    /**
     * Anything being invoked: a [call] or a [constructorCall].
     *
     * The kind that "calling `Foo(...)`" maps to is a property of the LANGUAGE, not of the code: Java's
     * `new Foo()` is a constructor call and Kotlin's `Foo()` is a method call, because only resolution can
     * tell a Kotlin constructor from a function. A pattern that means "is something being invoked here"
     * wants both, and getting that wrong is silent.
     */
    fun invocation(): DomNodePattern =
        node().withKind(setOf(NodeKind.METHOD_CALL, NodeKind.CONSTRUCTOR_CALL))

    /** An [invocation] whose callee is spelled [name]. The one-liner most call patterns want. */
    fun invocation(name: String): DomNodePattern = invocation().withCalleeNamed(name)

    /** Matches any non-null node — the default "applies everywhere" pattern. */
    fun anyNode(): ElementPattern<DomNode> = ElementPattern { it != null }
}

/** Logical combinators over any [ElementPattern]. */
object StandardPatterns {
    fun <T> or(vararg patterns: ElementPattern<T>): ElementPattern<T> =
        ElementPattern { t -> patterns.any { it.accepts(t) } }

    fun <T> and(vararg patterns: ElementPattern<T>): ElementPattern<T> =
        ElementPattern { t -> patterns.all { it.accepts(t) } }

    fun <T> not(pattern: ElementPattern<T>): ElementPattern<T> =
        ElementPattern { t -> !pattern.accepts(t) }

    fun <T> alwaysTrue(): ElementPattern<T> = ElementPattern { it != null }
    fun <T> alwaysFalse(): ElementPattern<T> = ElementPattern { false }
}
