package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A file whose declarations all take their type from a call, analyzed without blowing the stack.
 *
 * `inferReturnFromBody` had a re-entrancy guard, which breaks a declaration that types THROUGH ITSELF. It
 * does not break the shape that actually occurs: an `object` holding a hundred `val x = build(…)`. Typing one
 * of them resolves `build`, which walks the file scope, which enumerates the object's members, which types
 * the next property, which resolves `build` again. Every step is a different callable, so nothing is
 * re-entrant; the descent is as deep as the file has properties.
 *
 * `CaIcons.kt` in this repository was the one that overflowed, found by sweeping the checkers over the
 * repository's own sources. The analysis runs on every keystroke, so an exception there is a dead editor
 * pane -- and on ART a swallowed StackOverflowError can take the whole process with it.
 */
class KotlinDeepInferenceTest {

    @Test
    fun aFileOfManyInferredDeclarationsDoesNotOverflowTheStack() {
        val body = (1..200).joinToString("\n") { "    val icon$it = build(\"icon$it\", \"d$it\")" }
        val code = """
            package demo

            object Icons {
                class Glyph(val name: String, val path: String)

                private fun build(name: String, path: String) = Glyph(name, path)

            $body
            }
        """.trimIndent()

        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Icons.kt")))
        // The assertion IS that this returns: before the depth cap it threw StackOverflowError.
        val diagnostics = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics
        }

        assertTrue(
            diagnostics.none { it.code == KotlinDiagnosticCodes.SYNTAX },
            "the file parses; got ${diagnostics.filter { it.code == KotlinDiagnosticCodes.SYNTAX }.map { it.message }}",
        )
    }

    /** The shallow case still infers: the cap is far above anything real code reaches. */
    @Test
    fun aShortChainOfInferredDeclarationsStillTypes() {
        val code = """
            package demo

            class Box(val size: Int)

            object Holder {
                private fun make(n: Int) = Box(n)
                val a = make(1)
                val b = a.size
                val c = b + 1
            }
        """.trimIndent()

        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Holder.kt")))
        val diagnostics = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics
        }

        assertTrue(
            diagnostics.none { it.code == KotlinDiagnosticCodes.UNRESOLVED },
            "`a.size` resolves through the inferred type of `a`; got ${diagnostics.map { it.message }}",
        )
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
