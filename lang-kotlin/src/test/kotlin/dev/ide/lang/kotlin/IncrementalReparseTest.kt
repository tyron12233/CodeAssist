package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParserHost
import com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Track D — incremental PSI reparse. Verifies [KotlinParserHost.tryReparse] (1) actually reuses unchanged
 * subtrees (node identity survives), and (2) produces a tree IDENTICAL to a full reparse across a range of
 * edit shapes, including a syntactically-broken mid-edit buffer (the error-tolerance contract the editor
 * relies on). A divergence here would silently desync completion/diagnostics/highlighting from the buffer.
 */
class IncrementalReparseTest {

    /** Structural dump of the whole tree: one `type@offset:length` per AST node, depth-first. */
    private fun dump(file: KtFile): String {
        val sb = StringBuilder()
        fun walk(node: ASTNode, depth: Int) {
            sb.append(" ".repeat(depth)).append(node.elementType).append('@')
                .append(node.startOffset).append(':').append(node.textLength).append('\n')
            var child = node.firstChildNode
            while (child != null) { walk(child, depth + 1); child = child.treeNext }
        }
        walk(file.node, 0)
        return sb.toString()
    }

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

    /** Reparse `old` -> `new` incrementally and assert the result equals a fresh full parse of `new`. */
    private fun assertReparseMatchesFull(old: String, new: String, name: String = "F.kt") {
        val incremental = KotlinParserHost.parse(name, old)
        val reparsed = KotlinParserHost.tryReparse(incremental, new)
        val full = KotlinParserHost.parse(name, new)
        if (reparsed == null) {
            // Fallback path: acceptable, but then the caller full-parses — nothing to compare structurally.
            return
        }
        assertEquals(new, reparsed.text, "[$name] reparsed text must equal the new buffer")
        assertEquals(dump(full), dump(reparsed), "[$name] incremental tree must be identical to a full reparse")
    }

    @Test
    fun reusesUnchangedNodesOnABodyEdit() {
        val old = base()
        val new = old.replace("val b = 100", "val b = 999") // edit confined to beta()'s body

        val file = KotlinParserHost.parse("Reuse.kt", old)
        // Top-level declarations OUTSIDE the edited beta(): the Greeter class + alpha(). Capture their name
        // identifiers up front; if incremental reuse is real, these exact PSI instances survive the reparse.
        val classBefore = file.declarations.filterIsInstance<KtClassOrObject>().first { it.name == "Greeter" }.nameIdentifier
        val alphaBefore = file.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "alpha" }.nameIdentifier
        assertNotNull(classBefore); assertNotNull(alphaBefore)

        val reparsed = KotlinParserHost.tryReparse(file, new)
        assertNotNull(reparsed, "incremental reparse should apply for a localized body edit")
        assertSame(file, reparsed, "reparse mutates in place and returns the same KtFile")

        val classAfter = reparsed.declarations.filterIsInstance<KtClassOrObject>().first { it.name == "Greeter" }.nameIdentifier
        val alphaAfter = reparsed.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "alpha" }.nameIdentifier
        assertSame(classBefore, classAfter, "Greeter class node reused (incremental, not full reparse)")
        assertSame(alphaBefore, alphaAfter, "alpha() node reused (incremental, not full reparse)")
        val beta = reparsed.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "beta" }
        assertTrue(beta.text.contains("999"), "beta reflects the edit")
    }

    @Test
    fun matchesFullParseAcrossEditShapes() {
        val b = base()
        // literal change
        assertReparseMatchesFull(b, b.replace("val b = 100", "val b = 1234"))
        // insert a statement inside a body
        assertReparseMatchesFull(b, b.replace("val a = 1\n", "val a = 1\n    val a2 = a + 2\n"))
        // delete a statement
        assertReparseMatchesFull(b, b.replace("    val out = StringBuilder()\n", ""))
        // rename across a declaration header (changes structure of one fun's signature)
        assertReparseMatchesFull(b, b.replace("fun beta(): Int", "fun betaRenamed(): Long"))
        // edit at the very start (package) and very end (last fun body)
        assertReparseMatchesFull(b, b.replace("package com.example", "package com.example.app"))
        assertReparseMatchesFull(b, b.replace("return b", "return b * 2"))
        // multi-line insert (a whole new function)
        assertReparseMatchesFull(b, b + "\n\nfun gamma() = 42\n")
    }

    @Test
    fun matchesFullParseForBrokenMidEditBuffer() {
        val b = base()
        // mid-typing: an unbalanced brace / dangling operator — the parser must stay error-tolerant and the
        // incremental tree (if produced) must match the full error-recovering parse exactly.
        assertReparseMatchesFull(b, b.replace("val b = 100", "val b = 100 +"))
        assertReparseMatchesFull(b, b.replace("return a\n}", "return a\n")) // drop a closing brace
        assertReparseMatchesFull(b, b.replace("fun greet(times: Int): String {", "fun greet(times: Int): String "))
    }

    @Test
    fun handlesEmptyAndWholeFileReplacement() {
        assertReparseMatchesFull(base(), "")
        assertReparseMatchesFull("", base())
        assertReparseMatchesFull(base(), "fun onlyThing() = Unit\n")
    }
}
