package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.TextRange
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A scope function called on an EXPLICIT receiver whose type is unknown (`unresolved?.let { it }`) must not
 * bind its receiver to the enclosing implicit receiver. Resolving it as a bare `let { }` typed `it` as the
 * surrounding `ColumnScope` and reported a false type mismatch on `open(it, …)`.
 */
class KotlinUnknownReceiverScopeFunctionTest {

    private fun hints(file: String, code: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(file)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.inlayHints!!.hints(doc.file, TextRange(0, code.length)) }
            .map { it.parts.joinToString("") { p -> p.text } }
    }

    private fun diagnose(file: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(file)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private val base = """
        package demo
        interface ColumnScope
        fun Column(content: ColumnScope.() -> Unit) {}
        fun open(path: String, offset: Int) {}
    """.trimIndent() + "\n"

    @Test
    fun unknownReceiverDoesNotBindToImplicitReceiver() {
        val code = base + "fun f(route: Unknown) { Column { route.filePath?.let { open(it, offset = 0) } } }"
        val hs = hints("A.kt", code)
        assertTrue(hs.none { it.startsWith("it: ColumnScope") }, "it must not be the implicit receiver; got $hs")
        val diags = diagnose("A.kt", code)
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "no type mismatch on an untyped it; got $diags")
    }

    @Test
    fun lambdaParamFromNamedArgumentTypesNestedLet() {
        val code = base + """
            fun section(name: String, onRouteTap: (Route) -> Unit) {}
            fun f(m: Map<String, List<Route>>) {
                Column {
                    m.forEach { (name, _) ->
                        section(name = name, onRouteTap = { route -> route.filePath?.let { open(it, offset = 0) } })
                    }
                }
            }
        """.trimIndent()
        val hs = hints("B.kt", code)
        assertTrue(hs.contains(": Route") && hs.any { it.startsWith("it: String") }, "route: Route, it: String; got $hs")
        assertTrue(diagnose("B.kt", code).none { it.code == "kt.typeMismatch" })
    }

    @Test
    fun bareLetStillBindsTheImplicitReceiver() {
        val hs = hints("C.kt", base + "fun f() { Column { let { it.hashCode() } } }")
        assertTrue(hs.any { it.startsWith("it: ColumnScope") }, "a bare let's it is the implicit receiver; got $hs")
    }

    @Test
    fun packageQualifiedCallStillResolves() {
        val diags = diagnose("D.kt", "package demo\nfun f() { val s: String = kotlin.text.buildString { append(1) } }")
        assertTrue(diags.none { it.code == "kt.typeMismatch" || it.code == "kt.unresolved" }, "got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf("Route.kt" to "package demo\ndata class Route(val typeName: String, val filePath: String? = null)"),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
