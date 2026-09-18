package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Variable must be initialized before it is used" — what counts as a USE, and what counts as initializing.
 *
 * Both halves were wrong on ordinary Kotlin, and the repository sweep found both in the same function:
 *
 *     val name: String
 *     when (element) {
 *         is KtEnumEntry -> { name = element.name ?: return null }
 *         is KtClass     -> { name = element.name ?: return null }
 *         else -> return null
 *     }
 *     use(name)
 *
 * The `when` contributed no assignments at all, so `use(name)` was flagged; and `element.name` was matched to
 * the local by SPELLING, so each line that initializes `name` was flagged for using it first.
 */
class KotlinUninitializedReadTest {

    private fun errors(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR && it.code == KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE }
    }

    @Test
    fun aMemberThatSharesALocalsNameIsNotAUseOfTheLocal() {
        val diags = errors(
            "Shadow.kt",
            """
            package demo
            class Thing(val name: String, val size: Int)
            fun describe(t: Thing): String {
                val name: String
                val size: Int
                name = t.name
                size = t.size
                return name + size
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "`t.name` is a member of `t`; got ${diags.map { it.message }}")
    }

    @Test
    fun aWhenWhoseBranchesAllAssignInitializesTheVariable() {
        val diags = errors(
            "WhenAssign.kt",
            """
            package demo
            fun label(n: Int): String {
                val text: String
                when (n) {
                    0 -> text = "zero"
                    1 -> text = "one"
                    else -> text = "many"
                }
                return text
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "every branch assigns it; got ${diags.map { it.message }}")
    }

    @Test
    fun aBranchThatJumpsDoesNotHaveToAssign() {
        val diags = errors(
            "WhenJump.kt",
            """
            package demo
            fun label(n: Int): String? {
                val text: String
                when (n) {
                    0 -> text = "zero"
                    1 -> text = "one"
                    else -> return null
                }
                return text
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "the `else` returns, so it never reaches the read; got ${diags.map { it.message }}")
    }

    /** A named-argument LABEL is the callee's parameter name, not a reference to anything in scope. */
    @Test
    fun aNamedArgumentLabelIsNotAUseOfASameNamedLocal() {
        val diags = errors(
            "NamedArg.kt",
            """
            package demo
            fun make(text: String): String = text
            fun run2(): String {
                val text: String
                text = make(text = "x")
                return text
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "`text =` inside the call is a label; got ${diags.map { it.message }}")
    }

    /** The check still has to FIRE: a branch that neither assigns nor jumps leaves the variable unset. */
    @Test
    fun aWhenBranchThatNeitherAssignsNorJumpsIsStillFlagged() {
        val diags = errors(
            "WhenGap.kt",
            """
            package demo
            fun label(n: Int): String {
                val text: String
                when (n) {
                    0 -> text = "zero"
                    else -> println("nothing")
                }
                return text
            }
            """.trimIndent(),
        )
        assertTrue(diags.isNotEmpty(), "the `else` branch leaves `text` unassigned")
    }

    @Test
    fun aPlainReadBeforeAnyAssignmentIsStillFlagged() {
        val diags = errors(
            "Plain.kt",
            "package demo\nfun f(): Int {\n    val x: Int\n    val y = x + 1\n    x = 2\n    return y\n}\n",
        )
        assertTrue(diags.isNotEmpty(), "`x` is read before it is assigned")
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
