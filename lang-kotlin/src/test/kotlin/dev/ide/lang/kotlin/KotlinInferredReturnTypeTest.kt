package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An expression-body declaration with no explicit return type (`fun f() = expr`, `val p = expr`) must take its
 * type from the body, resolved like the editor would, so a chain off it resolves and it shows a return type.
 * Earlier the type came from a crude text heuristic that only covered literals/constructor calls and otherwise
 * (or, worse, for a member call like `this.trim()` parsed as a bogus type) left the declaration un-typed.
 */
class KotlinInferredReturnTypeTest {
    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    /** `expr.uppercase()` completes iff `expr` is typed as String (uppercase is a String-only extension). */
    private fun assertInfersString(expr: String) {
        val items = labels("package demo\nfun f() { $expr.upper| }")
        assertTrue("uppercase" in items, "$expr should infer String; got ${items.take(30)}")
    }

    @Test fun thisReceiverBody() = assertInfersString("\"\".identity()")
    @Test fun singleExtensionCallBody() = assertInfersString("\"\".trimmed()")
    @Test fun memberCallBody() = assertInfersString("\"\".asText()")
    @Test fun topLevelFunctionWithInferredReturn() = assertInfersString("make()")
    @Test fun chainedCallBody() = assertInfersString("\"\".normalized()")

    @Test fun inferredReturnShownAcrossFilesViaProperty() {
        // A property with an inferred type drives a chain too (`greeting` is String via its initializer).
        val items = labels("package demo\nfun f() { greeting.upper| }")
        assertTrue("uppercase" in items, "top-level val with inferred String type; got ${items.take(30)}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Seed.kt" to "package demo\n",
                "Ext.kt" to "package demo\n" +
                    "fun String.identity() = this\n" +
                    "fun String.trimmed() = this.trim()\n" +
                    "fun String.asText() = this.toString()\n" +
                    "fun make() = \"x\"\n" +
                    "fun String.normalized() = this.trim().toString()\n" +
                    "val greeting = make()\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
