package dev.ide.lang.kotlin

import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Signature help (parameter info) over the standalone Kotlin PSI + the backend's own resolver. */
class KotlinSignatureHelpTest {

    private fun help(file: String, codeWithCaret: String): SignatureHelp? {
        val offset = codeWithCaret.indexOf("|CARET|")
        require(offset >= 0) { "missing |CARET|" }
        val text = codeWithCaret.replace("|CARET|", "")
        val doc = SnippetDoc(text, DiskFile(srcDir.resolve(file)))
        return runBlocking {
            analyzer.signatureHelp!!.signatureHelp(SignatureHelpRequest(doc, offset, SignatureHelpTrigger.Explicit))
        }
    }

    @Test
    fun showsParametersAndActiveIndexForTopLevelFunction() {
        val code = "package demo\n" +
            "fun greet(name: String, count: Int) {}\n" +
            "fun m() { greet(\"x\", |CARET|) }\n"
        val h = help("Use.kt", code)
        assertNotNull(h, "expected signature help inside greet(...)")
        val active = h.signatures[h.activeSignature]
        assertTrue(active.label.contains("greet("), "label should show the callee; got ${active.label}")
        assertTrue(active.label.contains("name: String") && active.label.contains("count: Int"),
            "should carry real names + types; got ${active.label}")
        assertEquals(1, h.activeParameter, "caret after the first comma → second argument")
    }

    @Test
    fun resolvesConstructorParameters() {
        // The callee type is resolved through the source index, so the class lives on disk (cross-file).
        val code = "package demo\nfun m() { Box(|CARET|) }\n"
        val h = help("Use.kt", code)
        assertNotNull(h, "expected signature help inside Box(...)")
        val active = h.signatures[h.activeSignature]
        assertTrue(active.label.contains("a: Int") && active.label.contains("b: String"),
            "constructor params should resolve; got ${active.label}")
        assertEquals(0, h.activeParameter)
    }

    @Test
    fun nullOutsideAnyCall() {
        val code = "package demo\nval x = |CARET|1\n"
        assertEquals(null, help("Use.kt", code))
    }

    @Test
    fun namedArgumentMarksThatParameterAlreadyNamed() {
        // `count` is passed by name, so it should be flagged already-named (the popup dims it); `name` isn't.
        val code = "package demo\n" +
            "fun greet(name: String, count: Int) {}\n" +
            "fun m() { greet(count = 1, |CARET|) }\n"
        val h = help("Use.kt", code)
        assertNotNull(h, "expected signature help inside greet(...)")
        val active = h.signatures[h.activeSignature]
        val nameParam = active.parameters.first { it.label.contains("name") }
        val countParam = active.parameters.first { it.label.contains("count") }
        assertEquals(true, countParam.alreadyNamed, "count was supplied by name")
        assertEquals(false, nameParam.alreadyNamed, "name was not supplied yet")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf(
            "Seed.kt" to "package demo\n",
            "Model.kt" to "package demo\nclass Box(val a: Int, val b: String)\n",
        ))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
