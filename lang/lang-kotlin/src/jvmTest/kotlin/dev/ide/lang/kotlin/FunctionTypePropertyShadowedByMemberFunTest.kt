package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `name(args)` where the class has BOTH a member function `name` and a function-typed property `name`: Kotlin
 * tries the function first, but an INAPPLICABLE function (wrong arity) doesn't end resolution -- the property's
 * `invoke` is the call. Reported from the field: a `WebViewClient` subclass with an `onPageStarted: (String) ->
 * Unit` constructor property calling `onPageStarted(url)` inside `override fun onPageStarted(view, url, favicon)`
 * read "inferred type is String but WebView was expected".
 */
class FunctionTypePropertyShadowedByMemberFunTest {

    private fun errors(files: Map<String, String>, focal: String): List<Diagnostic> {
        val src = tempProject(files)
        val jdk = TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdk)))
        val doc = SnippetDoc(files.getValue(focal), DiskFile(src.resolve(focal)))
        return runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR }
    }

    @Test
    fun sourceSuperclassMemberFunWithOtherArity() {
        val code = """
            package demo
            class View
            open class Client { open fun onPageStarted(view: View, url: String, favicon: Any?) {} }
            class TestClient(private val onPageStarted: (String) -> Unit = {}) : Client() {
                override fun onPageStarted(view: View, url: String, favicon: Any?) {
                    onPageStarted(url)
                }
            }
        """.trimIndent()
        assertEquals(emptyList(), errors(mapOf("Main.kt" to code), "Main.kt").map { "${it.code}: ${it.message}" })
    }

    @Test
    fun binarySuperclassMemberFunWithOtherArity() {
        val code = """
            package demo
            class Sink(private val write: (String) -> Unit = {}) : java.io.OutputStream() {
                override fun write(b: Int) {
                    write(b.toString())
                }
            }
        """.trimIndent()
        assertEquals(emptyList(), errors(mapOf("Main.kt" to code), "Main.kt").map { "${it.code}: ${it.message}" })
    }

    @Test
    fun ownMemberFunWithOtherArity() {
        val code = """
            package demo
            class C(private val handle: (String) -> Unit) {
                fun handle(a: Int, b: Int) { handle("x") }
            }
        """.trimIndent()
        assertEquals(emptyList(), errors(mapOf("Main.kt" to code), "Main.kt").map { "${it.code}: ${it.message}" })
    }

    @Test
    fun applicableMemberFunStillWins() {
        // Both can take `handle("x")`: the member function is tried first and wins, so the call is typed by ITS
        // return type (Int), not the property's Unit.
        val code = """
            package demo
            class C(private val handle: (String) -> Unit) {
                fun handle(a: String): Int = 1
                fun use() { val s: String = handle("x") }
            }
        """.trimIndent()
        val errs = errors(mapOf("Main.kt" to code), "Main.kt").map { "${it.code}: ${it.message}" }
        assertEquals(1, errs.size, errs.toString())
        assertTrue("Int" in errs.single(), errs.toString())
    }
}
