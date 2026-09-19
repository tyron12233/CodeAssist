package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Members a Kotlin type inherits from the JVM class behind it, which only resolve with a JDK on the classpath.
 *
 * `StringBuilder` is a typealias to `java.lang.StringBuilder`, so its whole Java API is callable; `MutableMap`
 * is a mapped built-in whose `.kotlin_builtins` shape omits the JDK default methods Kotlin grafts back on
 * (see `Builtins.ADDITIONAL_JVM_MEMBERS`). Both read their signatures off real bytecode, so both were
 * reported unresolved by the module sweep until the harness carried a JDK at all.
 */
class KotlinJdkMemberResolutionTest {

    @Test
    fun aLambdaParameterIsTypedThroughASafeCallChain() = probe(
        """
        package demo
        class Holder { val items: List<String> = emptyList() }
        fun f(h: Holder?) {
            h?.items?.takeIf { it.isNotEmpty() }?.let { list ->
                println(list.first())
            }
        }
        """,
    )

    @Test
    fun aStringBuilderTakesTheThreeArgumentAppend() = probe(
        """
        package demo
        fun f(t: String, sb: StringBuilder) {
            sb.append(t, 0, 1)
        }
        """,
    )

    @Test
    fun aMapTakesPutIfAbsent() = probe(
        """
        package demo
        fun f(m: MutableMap<String, Int>) {
            m.putIfAbsent("a", 1)
        }
        """,
    )

    private fun probe(code: String) = runBlocking {
        val src = tempProject(mapOf("Probe.kt" to code.trimIndent()))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(code.trimIndent(), DiskFile(src.resolve("Probe.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val errors = analyzer.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    companion object {
        /** The JDK, which `java.class.path` does not carry since Java 9. See [TestJars.jdkBaseJar]. */
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
