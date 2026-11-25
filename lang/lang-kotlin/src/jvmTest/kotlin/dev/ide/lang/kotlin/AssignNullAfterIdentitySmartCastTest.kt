package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A smart cast narrows READS of a variable, never the type a write must conform to. After `if (v === web)` (with
 * `web` non-null) a read of `v` is a `WebView`, but `v = null` still assigns to the declared `WebView?`. Field
 * report: "Null can not be a value of a non-null type WebView" on `webView = null` in a detach().
 */
class AssignNullAfterIdentitySmartCastTest {

    private fun errors(code: String): List<String> {
        val src = tempProject(mapOf("Main.kt" to code))
        val a = KotlinSourceAnalyzer(fakeContext(src))
        val doc = SnippetDoc(code, DiskFile(src.resolve("Main.kt")))
        return runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
            .filter { it.severity == Severity.ERROR }.map { "${it.code}: ${it.message}" }
    }

    @Test
    fun objectPropertyAfterIdentityCheck() = assertEquals(emptyList(), errors("""
        package demo
        class View
        object Holder {
            @Volatile
            private var view: View? = null
            fun detach(v: View) {
                if (view === v) {
                    view = null
                }
            }
        }
    """.trimIndent()))

    @Test
    fun localAfterEqualityAndNullCheck() = assertEquals(emptyList(), errors("""
        package demo
        class View
        fun f(v: View, start: View?) {
            var a: View? = start
            if (a == v) a = null
            var b: View? = start
            if (b != null) { b = null }
        }
    """.trimIndent()))

    @Test
    fun nonNullTargetStillFlagged() {
        val errs = errors("""
            package demo
            class View
            fun f(v: View) {
                var a: View = v
                a = null
            }
        """.trimIndent())
        assertEquals(1, errs.size, errs.toString())
    }
}
