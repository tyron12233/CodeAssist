package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `while (x != null) { … }` narrows `x` to non-null in the body, the way `if (x != null)` does.
 *
 * Walking a parent/sibling chain with a nullable `var` is how this module traverses PSI, and every such loop
 * reported "Only safe (?.) or non-null asserted (!!) calls are allowed on a nullable receiver".
 */
class KotlinWhileNullGuardTest {

    @Test
    fun aWhileNullGuardNarrowsItsBody() = clean(
        """
        class Node(val parent: Node?, val name: String)
        fun f(start: Node?) {
            var n = start
            while (n != null) {
                println(n.name)
                n = n.parent
            }
        }
        """,
    )

    /** The control: the same guard written as an `if`. */
    @Test
    fun anIfNullGuardNarrowsItsBody() = clean(
        """
        class Node(val parent: Node?, val name: String)
        fun f(n: Node?) {
            if (n != null) {
                println(n.name)
            }
        }
        """,
    )

    @Test
    fun aWhileNullGuardNarrowsAcrossSeveralStatements() = clean(
        """
        class Node(val nextSibling: Node?, val firstChild: Node?, val name: String)
        fun f(p: Node) {
            var c = p.firstChild
            while (c != null) {
                println(c.name)
                println(c.firstChild?.name)
                c = c.nextSibling
            }
        }
        """,
    )

    /** Without the guard it must still be flagged. */
    @Test
    fun anUnguardedNullableReceiverIsStillFlagged() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val name: String)
            fun f(n: Node?) {
                println(n.name)
            }
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UNSAFE_NULLABLE },
            "an unguarded nullable receiver must still be flagged, got $errors",
        )
    }

    /**
     * The back edge really can invalidate a narrowing when the loop does NOT re-check: on the second
     * iteration `x` is whatever the body last assigned. These must stay flagged, or the fix is unsound.
     */
    @Test
    fun aWhileLoopNotGuardedOnTheVarDoesNotNarrowIt() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val name: String)
            fun f(t: Node, c: Boolean) {
                var x: Node? = t
                while (c) {
                    println(x.name)
                    x = null
                }
            }
            """,
        )
        assertTrue(errors.isNotEmpty(), "an unguarded while must not narrow, but the file came back clean")
    }

    @Test
    fun aDoWhileLoopDoesNotNarrowAVarItAssigns() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val name: String)
            fun f(t: Node, c: Boolean) {
                var x: Node? = t
                do {
                    println(x.name)
                    x = null
                } while (c)
            }
            """,
        )
        assertTrue(errors.isNotEmpty(), "a do-while must not narrow, but the file came back clean")
    }

    @Test
    fun anInnerLoopAssignmentStillExcludes() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val name: String)
            fun f(start: Node?, xs: List<Int>) {
                var n = start
                while (n != null) {
                    for (i in xs) { n = n.parent }
                    println(n.name)
                }
            }
            """,
        )
        assertTrue(errors.isNotEmpty(), "an inner-loop write must still exclude, but the file came back clean")
    }

    /** A use AFTER the loop is not narrowed: `while (n != null)` exits precisely when `n` is null. */
    @Test
    fun aUseAfterTheLoopIsStillFlagged() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val name: String)
            fun f(start: Node?) {
                var n = start
                while (n != null) {
                    n = n.parent
                }
                println(n.name)
            }
            """,
        )
        assertTrue(errors.isNotEmpty(), "a use after the loop must still be flagged, got $errors")
    }

    private fun clean(code: String) = runBlocking {
        val errors = analyze(code)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private suspend fun analyze(code: String): List<dev.ide.lang.dom.Diagnostic> {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return analyzer.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
