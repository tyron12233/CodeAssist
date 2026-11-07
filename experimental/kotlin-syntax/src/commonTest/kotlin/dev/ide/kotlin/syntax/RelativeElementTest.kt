package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.counterpartUnder
import dev.ide.kotlin.syntax.psi.elementAt
import dev.ide.kotlin.syntax.psi.hasElementAt
import dev.ide.kotlin.syntax.psi.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [relativeTo] and [elementAt]: finding an element again under an identical ancestor, and only there. */
class RelativeElementTest {

    private fun KtElement.descendants(): List<KtElement> = children.flatMap { listOf(it) + it.descendants() }

    @Test
    fun anIdenticalDeclarationHasEveryElementAgain() {
        val text = "fun a(x: Int): Int {\n    val y = x * 2\n    return y + listOf(1, 2).size\n}\n"
        val old = KotlinSyntax.parseFile(text).declarations.single()
        val new = KotlinSyntax.parseFile("\n\n$text").declarations.single()
        for (e in old.descendants()) {
            val rel = e.relativeTo(old) ?: continue
            assertTrue(new.hasElementAt(rel), "$e")
            assertEquals(e.text, e.counterpartUnder(old, new)?.text)
        }
    }

    /**
     * Under a declaration shaped differently the lookup lands on whatever index the old one had, which can be
     * no node at all: composites are numbered among the parser's markers, its end markers included, and an end
     * marker's slot has no type. That has to answer "not here", not throw.
     */
    @Test
    fun aDifferentlyShapedDeclarationAnswersNoneAndNeverThrows() {
        val old = KotlinSyntax.parseFile(
            "fun a(x: Int): Int {\n    val y = foo(bar(x), baz { it + 1 })\n    return y\n}\n",
        ).declarations.single()
        val shapes = listOf(
            "fun a() {}",
            "fun a(x: Int) = x",
            "val a = 1",
            "class A(val x: Int) { fun b() = listOf(x) }",
            "fun a(x: Int): Int {\n    return x\n}",
            "fun a(x: Int): Int { if (x > 0) { return foo(x) } else { return bar(x, 2) } }",
        )
        var misses = 0
        for (shape in shapes) {
            val new = KotlinSyntax.parseFile(shape).declarations.single()
            for (e in old.descendants()) {
                val rel = e.relativeTo(old) ?: continue
                if (!new.hasElementAt(rel)) {
                    misses++
                    assertNull(new.elementAt(rel))
                }
            }
        }
        assertFalse(misses == 0, "the shapes should differ from the original somewhere")
    }

    /**
     * A keystroke inside a function body is spliced into the previous tree rather than parsed with the file
     * ([KotlinSyntax.reparseFileLazily]). The result must number its nodes as a parse of the new text does, the
     * parser's end-marker slots included, or every element after the body in the same declaration sits at a
     * different index than in the next full parse, and is found again (or not) by the wrong one.
     */
    @Test
    fun aSplicedBodyIsNumberedAsAFreshParseNumbersIt() {
        val bodies = dev.ide.kotlin.syntax.psi.LazyBodyParser.UNCACHED
        val text = "class A {\n    fun a(x: Int?) = run { x ?: 0 }\n    fun b(y: String?) {\n        val z = y?.length ?: 0\n        println(z!!)\n    }\n    fun c() = 1\n}\n"
        val at = text.indexOf("println")
        val edited = text.substring(0, at) + "val w = y!!\n        " + text.substring(at)
        val spliced = KotlinSyntax.reparseFileLazily(KotlinSyntax.parseFileLazily(text, bodies), edited)
        assertNotNull(spliced, "an edit inside a function body reparses in place")
        val fresh = KotlinSyntax.parseFileLazily(edited, bodies)
        val a = fresh.session.tree
        val b = spliced.session.tree
        assertEquals(a.rootIndex, b.rootIndex)
        for (i in 0 until a.rootIndex) assertEquals(a.compositeTypeAt(i), b.compositeTypeAt(i), "slot $i")

        // So the class, which the source index carries from this build to the next when an edit elsewhere has
        // the file parsed in full, is found again element for element; `fun c` after the edited body included.
        val splicedClass = spliced.declarations.single()
        val freshClass = fresh.declarations.single()
        for (e in splicedClass.descendants()) {
            if (e.relativeTo(splicedClass) == null) continue
            assertEquals(e.text, e.counterpartUnder(splicedClass, freshClass)?.text, "$e")
        }
    }

    @Test
    fun aBlockParsedInPlaceIsNotTakenForACollapsedBody() {
        // A branch of an `if` in a property initializer is parsed in full even lazily. `{ a }` holds only a
        // reference, all tokens below it, which once passed for a body the lazy grammar had collapsed.
        val bodies = dev.ide.kotlin.syntax.psi.LazyBodyParser.UNCACHED
        val text = "val v = if (c) { a } else { b }\n"
        val edited = text.replace("{ a }", "{ ab }")
        val spliced = KotlinSyntax.reparseFileLazily(KotlinSyntax.parseFileLazily(text, bodies), edited)
        assertNull(spliced, "not a collapsed body, so the file is parsed again")
    }
}
