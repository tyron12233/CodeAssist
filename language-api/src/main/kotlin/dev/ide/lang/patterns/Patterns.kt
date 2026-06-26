package dev.ide.lang.patterns

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind

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
}

/** Factory for [DomNode] patterns. */
object DomPatterns {
    fun node(): DomNodePattern = DomNodePattern()
    fun node(kind: NodeKind): DomNodePattern = DomNodePattern().withKind(kind)

    fun nameRef(): DomNodePattern = node(NodeKind.NAME_REF)
    fun memberAccess(): DomNodePattern = node(NodeKind.MEMBER_ACCESS)
    fun call(): DomNodePattern = node(NodeKind.METHOD_CALL)
    fun typeRef(): DomNodePattern = node(NodeKind.TYPE_REF)
    fun literal(): DomNodePattern = node(NodeKind.LITERAL)

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
