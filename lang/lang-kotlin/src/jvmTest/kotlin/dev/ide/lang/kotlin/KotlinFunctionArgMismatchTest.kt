package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A NON-function value passed where a function type is expected — the `onClick = onItemClick(x)` mistake (a
 * missing lambda: it should be `onClick = { onItemClick(x) }`), which evaluates to the handler's `Unit` result.
 * This is a category error (a `Unit`/`Int` can never be a `() -> R`) and now surfaces as a `kt.typeMismatch`,
 * so the editor flags it instead of the mistake only showing up as a preview no-op / (pre-fix) crash. The
 * function-SHAPE check on a lambda/function-typed argument stays deliberately skipped (too imprecise).
 */
class KotlinFunctionArgMismatchTest {

    private fun mismatches(code: String): List<Diagnostic> {
        val dir = tempProject(mapOf("Use.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        val doc = SnippetDoc(code, DiskFile(dir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH }
    }

    private val item = "fun item(onClick: () -> Unit) {}\n"

    @Test fun unitResultPassedToFunctionParamIsFlagged() {
        // The reported shape: `onItemClick(x)` (an `(T) -> Unit` invoked) yields Unit, not a `() -> Unit`.
        val ds = mismatches("package demo\n${item}fun drawer(onItemClick: (String) -> Unit) { item(onClick = onItemClick(\"x\")) }")
        assertTrue(ds.any { "() -> Unit" in it.message && "Unit" in it.message }, "expected a Unit-vs-()->Unit mismatch; got $ds")
    }

    @Test fun positionalUnitResultIsFlagged() {
        val ds = mismatches("package demo\n${item}fun drawer(onItemClick: (String) -> Unit) { item(onItemClick(\"x\")) }")
        assertTrue(ds.isNotEmpty(), "a positional Unit-result argument to a () -> Unit param must be flagged; got $ds")
    }

    @Test fun nonFunctionLiteralPassedToFunctionParamIsFlagged() {
        val ds = mismatches("package demo\n${item}fun f() { item(42) }")
        assertTrue(ds.any { "() -> Unit" in it.message }, "an Int argument to a () -> Unit param must be flagged; got $ds")
    }

    @Test fun aCorrectLambdaIsNotFlagged() {
        val ds = mismatches("package demo\n${item}fun drawer(onItemClick: (String) -> Unit) { item(onClick = { onItemClick(\"x\") }) }")
        assertTrue(ds.isEmpty(), "a real lambda must not be flagged; got $ds")
    }

    @Test fun aFunctionTypedValueIsNotFlagged() {
        val ds = mismatches("package demo\n${item}fun f(cb: () -> Unit) { item(cb) }")
        assertTrue(ds.isEmpty(), "a function-typed value must not be flagged; got $ds")
    }
}
