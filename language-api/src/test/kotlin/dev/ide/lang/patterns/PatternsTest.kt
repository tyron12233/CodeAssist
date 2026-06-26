package dev.ide.lang.patterns

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The DOM [ElementPattern] DSL: kind/text/structural matchers and the [StandardPatterns] combinators. */
class PatternsTest {

    /** A minimal DOM node for pattern matching; links itself into [parent]'s children. */
    private class N(
        override val kind: NodeKind,
        private val txt: String = "",
        override val parent: DomNode? = null,
    ) : DomNode {
        override val range = TextRange(0, txt.length)
        override val children = ArrayList<DomNode>()
        override fun text(): CharSequence = txt
        init { (parent as? N)?.children?.add(this) }
    }

    @Test
    fun withKindMatchesExactly() {
        val n = N(NodeKind.NAME_REF, "foo")
        assertTrue(DomPatterns.node().withKind(NodeKind.NAME_REF).accepts(n))
        assertFalse(DomPatterns.node().withKind(NodeKind.METHOD_CALL).accepts(n))
    }

    @Test
    fun withKindSetMatchesAny() {
        val n = N(NodeKind.LITERAL)
        assertTrue(DomPatterns.node().withKind(setOf(NodeKind.LITERAL, NodeKind.NAME_REF)).accepts(n))
    }

    @Test
    fun textMatchers() {
        val n = N(NodeKind.NAME_REF, "Text")
        assertTrue(DomPatterns.nameRef().withText("Text").accepts(n))
        assertTrue(DomPatterns.nameRef().withText(Regex("Te.t")).accepts(n))
        assertTrue(DomPatterns.nameRef().withTextContaining("ex").accepts(n))
        assertFalse(DomPatterns.nameRef().withText("Other").accepts(n))
    }

    @Test
    fun withParentMatchesDirectParentOnly() {
        val call = N(NodeKind.METHOD_CALL)
        val args = N(NodeKind.BLOCK, parent = call)
        val name = N(NodeKind.NAME_REF, "x", parent = args)
        assertTrue(DomPatterns.nameRef().withParent(NodeKind.BLOCK).accepts(name))
        assertFalse(DomPatterns.nameRef().withParent(NodeKind.METHOD_CALL).accepts(name)) // grandparent, not parent
    }

    @Test
    fun insideMatchesAnyAncestor() {
        val call = N(NodeKind.METHOD_CALL)
        val args = N(NodeKind.BLOCK, parent = call)
        val name = N(NodeKind.NAME_REF, "x", parent = args)
        assertTrue(DomPatterns.nameRef().inside(NodeKind.METHOD_CALL).accepts(name))
        assertFalse(DomPatterns.nameRef().inside(NodeKind.TYPE_REF).accepts(name))
    }

    @Test
    fun withChildMatches() {
        val call = N(NodeKind.METHOD_CALL)
        N(NodeKind.NAME_REF, "callee", parent = call)
        assertTrue(DomPatterns.call().withChild(DomPatterns.nameRef().withText("callee")).accepts(call))
    }

    @Test
    fun standardCombinators() {
        val n = N(NodeKind.NAME_REF, "foo")
        val isName = DomPatterns.node().withKind(NodeKind.NAME_REF)
        val isCall = DomPatterns.node().withKind(NodeKind.METHOD_CALL)
        assertTrue(StandardPatterns.or(isCall, isName).accepts(n))
        assertFalse(StandardPatterns.and(isCall, isName).accepts(n))
        assertTrue(StandardPatterns.not(isCall).accepts(n))
    }

    @Test
    fun anyNodeRejectsNull() {
        assertTrue(DomPatterns.anyNode().accepts(N(NodeKind.LITERAL)))
        assertFalse(DomPatterns.anyNode().accepts(null))
        assertFalse(DomPatterns.node().withKind(NodeKind.NAME_REF).accepts(null))
    }
}
