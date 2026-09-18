package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A bare read of a member INHERITED from a supertype, when a top-level function of the same name is in scope.
 *
 * "Function invocation 'x(...)' expected" fires when a name resolves to a function and to no value. The value
 * half looked only at members DECLARED by the enclosing classes, so a property inherited from a base class was
 * invisible and a same-named stdlib extension (`last`, `first`, `count`, `single`, `max`) won by default.
 * Found sweeping the Kotlin standard library, where `AbstractMutableList.ListIteratorImpl` reads the `last`
 * that its own `IteratorImpl` supertype declares.
 */
class KotlinInheritedMemberReadTest {

    private fun errors(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }

    @Test
    fun aPropertyInheritedFromASameFileBaseIsAValueRead() {
        val diags = errors(
            "InheritedLast.kt",
            """
            package demo
            open class Base {
                protected var last: Int = -1
            }
            class Sub : Base() {
                fun bump(): Int {
                    last = last + 1
                    return last
                }
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "`last` is the inherited property; got ${diags.map { it.message }}")
    }

    @Test
    fun aPropertyInheritedThroughTwoLevelsIsAValueRead() {
        val diags = errors(
            "InheritedTwice.kt",
            """
            package demo
            open class Root {
                protected var count: Int = 0
            }
            open class Middle : Root()
            class Leaf : Middle() {
                fun read(): Int = count
            }
            """.trimIndent(),
        )
        assertTrue(diags.isEmpty(), "`count` is inherited two levels up; got ${diags.map { it.message }}")
    }

    @Test
    fun aBareUninvokedTopLevelFunctionIsStillFlagged() {
        val diags = errors(
            "StillFlagged.kt",
            "package demo\nfun helper(): Int = 1\nfun use(): Int = helper\n",
        )
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.FUNCTION_CALL_EXPECTED },
            "a bare `helper` with no value binding is still an un-invoked call; got ${diags.map { it.message }}",
        )
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
