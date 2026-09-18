package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.lang.kotlin.parse.KotlinParserHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every shape of edit a buffer goes through mid-typing, parsed, with the contract the editor relies on:
 * whatever the text, the tree covers ALL of it and the parse does not throw.
 *
 * **This used to assert something stronger and no longer can.** The backend parsed into IntelliJ PSI, whose
 * tree is mutable, so an edit could be applied in place and the unchanged subtrees reused, and the test
 * checked both that node identity survived (`assertSame` on a declaration outside the edit) and that the
 * result was byte-identical to a full reparse. The parser is now the Kotlin compiler's own vendored grammar
 * (`:kotlin-syntax`), which builds an immutable `LightSyntaxTree`: there is no in-place reparse to verify,
 * and every parse IS the full parse the old one was compared against.
 *
 * The cost that reuse was avoiding has not gone away, and `IncrementalKotlinParse` is where the replacement
 * lives: lazy bodies, expansion on demand, and skipping the file parse entirely when the edit provably stays
 * inside one body. It is not wired into this backend yet, and when it is it brings its own tests, because
 * what has to be proved about it ("the same tree you would have got") is not what was proved here.
 *
 * What is left is the part that was always independent of how the tree got built, and is the reason a wrong
 * answer here would silently desync completion, diagnostics and highlighting from the buffer.
 */
class IncrementalReparseTest {

    private fun base() = """
        package com.example

        import kotlin.math.max

        class Greeter(val name: String) {
            fun greet(times: Int): String {
                val out = StringBuilder()
                for (i in 0 until times) {
                    out.append("Hello, ").append(name).append("! ")
                }
                return out.toString()
            }
        }

        fun alpha(): Int {
            val a = 1
            return a
        }

        fun beta(): Int {
            val b = 100
            return b
        }
    """.trimIndent()

    /** Parse [text] and assert the tree accounts for all of it, which is the error-tolerance contract. */
    private fun assertCoversItsText(text: String, name: String = "F.kt"): KtFile {
        val file = KotlinParserHost.parse(name, text)
        assertEquals(text, file.text, "[$name] the tree's text must be the whole buffer")
        assertEquals(0, file.textRange.startOffset, "[$name] the tree must start at the buffer's start")
        assertEquals(text.length, file.textRange.endOffset, "[$name] the tree must reach the buffer's end")
        return file
    }

    @Test
    fun aBodyEditLeavesTheSurroundingDeclarationsIntact() {
        val new = base().replace("val b = 100", "val b = 999")
        val file = assertCoversItsText(new, "Reuse.kt")

        val classes = file.declarations.filterIsInstance<KtClassOrObject>().mapNotNull { it.name }
        val funs = file.declarations.filterIsInstance<KtNamedFunction>().mapNotNull { it.name }
        assertEquals(listOf("Greeter"), classes)
        assertEquals(listOf("alpha", "beta"), funs)

        val beta = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "beta" }
        assertTrue(beta.text.contains("999"), "beta reflects the edit")
    }

    @Test
    fun parsesEveryEditShape() {
        val b = base()
        // literal change
        assertCoversItsText(b.replace("val b = 100", "val b = 1234"))
        // insert a statement inside a body
        assertCoversItsText(b.replace("val a = 1\n", "val a = 1\n    val a2 = a + 2\n"))
        // delete a statement
        assertCoversItsText(b.replace("    val out = StringBuilder()\n", ""))
        // rename across a declaration header (changes the structure of one fun's signature)
        assertCoversItsText(b.replace("fun beta(): Int", "fun betaRenamed(): Long"))
        // edit at the very start (package) and the very end (last fun body)
        assertCoversItsText(b.replace("package com.example", "package com.example.app"))
        assertCoversItsText(b.replace("return b", "return b * 2"))
        // multi-line insert (a whole new function)
        assertCoversItsText(b + "\n\nfun gamma() = 42\n")
    }

    @Test
    fun staysErrorTolerantOnABrokenMidEditBuffer() {
        val b = base()
        // mid-typing: an unbalanced brace / a dangling operator. The parser must recover and still cover the
        // buffer, because completion fires on exactly this input.
        assertCoversItsText(b.replace("val b = 100", "val b = 100 +"))
        assertCoversItsText(b.replace("return a\n}", "return a\n")) // drop a closing brace
        assertCoversItsText(b.replace("fun greet(times: Int): String {", "fun greet(times: Int): String "))
    }

    @Test
    fun handlesEmptyAndWholeFileReplacement() {
        assertCoversItsText("")
        assertCoversItsText(base())
        assertCoversItsText("fun onlyThing() = Unit\n")
    }
}
