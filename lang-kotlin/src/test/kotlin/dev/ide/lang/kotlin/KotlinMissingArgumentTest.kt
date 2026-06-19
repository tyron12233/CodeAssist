package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Missing-required-argument detection ([KotlinSourceAnalyzer]'s `missingRequiredArgument`, backed by
 * [dev.ide.lang.kotlin.resolve.KotlinResolver.missingRequiredArgument]). A call that omits a parameter with
 * NO default — e.g. `Button { }` without `onClick` — is a compile error and must be flagged `kt.argumentCount`.
 * Sound over the parse-only model: a call that supplies (or only omits defaulted) parameters must NOT be flagged.
 */
class KotlinMissingArgumentTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val srcDir = tempProject(mapOf("A.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("A.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun trailingLambdaCallOmittingRequiredParamIsFlagged() {
        // The `Button { }` shape: only the trailing `content` lambda is supplied, the required `onClick` omitted.
        val d = diagnose(
            "package p\nfun Banner(onClick: () -> Unit, label: String = \"\", content: () -> Unit) {}\nfun f() { Banner { } }",
        )
        assertTrue(d.any { it.code == "kt.argumentCount" }, "Banner { } omitting the required onClick must be flagged; got $d")
    }

    @Test
    fun overloadedCallMissingRequiredParamIsFlaggedByName() {
        // Two overloads, BOTH requiring `onClick` (so the same-file single-candidate check backs off and the
        // overload-aware `missingRequiredArgument` path runs): `Banner { }` is invalid for both, flagged by name.
        val d = diagnose(
            "package p\n" +
                "fun Banner(onClick: () -> Unit, content: () -> Unit) {}\n" +
                "fun Banner(onClick: () -> Unit, count: Int, content: () -> Unit) {}\n" +
                "fun f() { Banner { } }",
        )
        assertTrue(
            d.any { it.code == "kt.argumentCount" && it.message.contains("onClick") },
            "the overload-aware check must flag the missing onClick by name; got $d",
        )
    }

    @Test
    fun supplyingTheRequiredParamIsNotFlagged() {
        val d = diagnose(
            "package p\nfun Banner(onClick: () -> Unit, label: String = \"\", content: () -> Unit) {}\nfun f() { Banner(onClick = {}) { } }",
        )
        assertTrue(d.none { it.code == "kt.argumentCount" }, "supplying onClick must not be flagged; got $d")
    }

    @Test
    fun omittingOnlyDefaultedParamsIsNotFlagged() {
        val d = diagnose(
            "package p\nfun Banner(onClick: () -> Unit, label: String = \"\", content: () -> Unit) {}\nfun f() { Banner(onClick = {}, content = {}) }",
        )
        assertTrue(d.none { it.code == "kt.argumentCount" }, "omitting only the defaulted label must not be flagged; got $d")
    }

    @Test
    fun anAcceptingOverloadSuppressesTheFlag() {
        // Two overloads: one needs `onClick`, one doesn't. `Banner { }` is valid via the no-onClick overload, so
        // it must NOT be flagged — the check backs off when ANY candidate accepts the call.
        val d = diagnose(
            "package p\n" +
                "fun Banner(onClick: () -> Unit, content: () -> Unit) {}\n" +
                "fun Banner(content: () -> Unit) {}\n" +
                "fun f() { Banner { } }",
        )
        assertTrue(d.none { it.code == "kt.argumentCount" }, "an accepting overload must suppress the flag; got $d")
    }
}
