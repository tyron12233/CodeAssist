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

    // --- the call helpers -------------------------------------------------
    //
    // The whole point of these is that ONE pattern matches both backend shapes. Kotlin wraps each
    // argument in an ARGUMENT node and Java does not, so the tests below build each shape by hand and
    // run the same pattern over both. `JavaDomShapeTest` and `KotlinNeutralCallShapeTest` assert that
    // the real backends produce exactly these two shapes.

    /** `Color("#f00", 1)` the way :lang-kotlin builds it: every argument inside an ARGUMENT wrapper. */
    private fun kotlinShapedCall(): N {
        val call = N(NodeKind.METHOD_CALL)
        N(NodeKind.NAME_REF, "Color", parent = call)
        val list = N(NodeKind.ARGUMENT_LIST, parent = call)
        N(NodeKind.STRING_LITERAL, "\"#f00\"", parent = N(NodeKind.ARGUMENT, parent = list))
        N(NodeKind.LITERAL, "1", parent = N(NodeKind.ARGUMENT, parent = list))
        return call
    }

    /** The same call the way :lang-java builds it: the values hang off the list directly. */
    private fun javaShapedCall(): N {
        val call = N(NodeKind.METHOD_CALL)
        N(NodeKind.NAME_REF, "Color", parent = call)
        val list = N(NodeKind.ARGUMENT_LIST, parent = call)
        N(NodeKind.STRING_LITERAL, "\"#f00\"", parent = list)
        N(NodeKind.LITERAL, "1", parent = list)
        return call
    }

    @Test
    fun withArgumentMatchesBothBackendShapes() {
        // One pattern object, both shapes. This is the claim the DSL makes and could not keep.
        val pattern = DomPatterns.invocation("Color").withArgument(DomPatterns.stringLiteral())
        assertTrue(pattern.accepts(kotlinShapedCall()), "Kotlin shape")
        assertTrue(pattern.accepts(javaShapedCall()), "Java shape")
        assertFalse(pattern.accepts(N(NodeKind.METHOD_CALL)), "a call with no arguments at all")
    }

    @Test
    fun withChildIsTheTrapWithArgumentExistsToAvoid() {
        // The naive spelling reaches the argument LIST, never the arguments, on either backend. It used
        // to work on Java by accident, because the list was flattened away and the arguments were direct
        // children of the call; a pattern written that way silently matched nothing on Kotlin.
        assertFalse(DomPatterns.call().withChild(DomPatterns.stringLiteral()).accepts(kotlinShapedCall()))
        assertFalse(DomPatterns.call().withChild(DomPatterns.stringLiteral()).accepts(javaShapedCall()))
    }

    @Test
    fun withArgumentAtIsPositionalAndBoundsSafe() {
        for (call in listOf(kotlinShapedCall(), javaShapedCall())) {
            assertTrue(DomPatterns.invocation().withArgumentAt(0, DomPatterns.stringLiteral()).accepts(call))
            assertTrue(DomPatterns.invocation().withArgumentAt(1, DomPatterns.literal()).accepts(call))
            assertFalse(DomPatterns.invocation().withArgumentAt(1, DomPatterns.stringLiteral()).accepts(call))
            assertFalse(DomPatterns.invocation().withArgumentAt(9, DomPatterns.anyLiteral()).accepts(call))
        }
    }

    @Test
    fun asArgumentOfClimbsThroughTheOptionalWrapper() {
        val pattern = DomPatterns.stringLiteral().asArgumentOf(DomPatterns.invocation("Color"))
        for (call in listOf(kotlinShapedCall(), javaShapedCall())) {
            val string = call.children.single { it.kind == NodeKind.ARGUMENT_LIST }
                .let { list -> list.children.first() }
                .let { first -> if (first.kind == NodeKind.ARGUMENT) first.children.single() else first }
            assertTrue(pattern.accepts(string), "argument of ${call.children.size}-child call")
        }
        // A string that is not an argument of a `Color` call must not match.
        assertFalse(pattern.accepts(N(NodeKind.STRING_LITERAL, "\"#f00\"")))
    }

    @Test
    fun calleeNameIgnoresQualificationAndTypeArguments() {
        // Java puts the qualifier on the callee node; Kotlin nests it above the call. Both answer `max`.
        val javaStyle = N(NodeKind.METHOD_CALL)
        N(NodeKind.MEMBER_ACCESS, "java.lang.Math.max", parent = javaStyle)
        N(NodeKind.ARGUMENT_LIST, parent = javaStyle)
        assertTrue(DomPatterns.invocation("max").accepts(javaStyle))

        val generic = N(NodeKind.METHOD_CALL)
        N(NodeKind.NAME_REF, "listOf<Int>", parent = generic)
        N(NodeKind.ARGUMENT_LIST, parent = generic)
        assertTrue(DomPatterns.invocation("listOf").accepts(generic))
    }

    @Test
    fun invocationCoversBothCallKinds() {
        val ctor = N(NodeKind.CONSTRUCTOR_CALL)
        N(NodeKind.TYPE_REF, "Color", parent = ctor)
        N(NodeKind.ARGUMENT_LIST, parent = ctor)

        // The point of `invocation`: `new Color()` is a CONSTRUCTOR_CALL and Kotlin's `Color()` a
        // METHOD_CALL, so a pattern that names only one is wrong on one language and says nothing.
        assertTrue(DomPatterns.invocation("Color").accepts(ctor))
        assertTrue(DomPatterns.invocation("Color").accepts(kotlinShapedCall()))
        assertFalse(DomPatterns.call().accepts(ctor))
        assertFalse(DomPatterns.constructorCall().accepts(kotlinShapedCall()))
    }

    @Test
    fun literalPatternsSplitStringsOut() {
        val string = N(NodeKind.STRING_LITERAL, "\"x\"")
        val number = N(NodeKind.LITERAL, "7")
        assertTrue(DomPatterns.stringLiteral().accepts(string))
        assertFalse(DomPatterns.literal().accepts(string), "a string is no longer a plain LITERAL")
        assertTrue(DomPatterns.literal().accepts(number))
        assertTrue(DomPatterns.anyLiteral().accepts(string) && DomPatterns.anyLiteral().accepts(number))
    }

    @Test
    fun anyNodeRejectsNull() {
        assertTrue(DomPatterns.anyNode().accepts(N(NodeKind.LITERAL)))
        assertFalse(DomPatterns.anyNode().accepts(null))
        assertFalse(DomPatterns.node().withKind(NodeKind.NAME_REF).accepts(null))
    }
}
