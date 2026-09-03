package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * A body whose inference runs out of STACK must not be recorded as "this declaration has no type". The
     * inferred-type memo is keyed by declaration and lives for the session, so caching that null left every
     * declaration the overflow unwound through permanently untyped: a chain off them stayed unresolved until
     * the source model was rebuilt, with nothing logged, because the error was read as a body that failed to
     * type. Driven by a chain of expression-body functions long enough to exhaust a small thread stack.
     */
    @Test
    fun stackExhaustionIsNotCachedAsAnAbsentType() {
        val depth = 400
        val chain = buildString {
            append("package demo\n")
            for (i in 1 until depth) append("fun f$i() = f${i + 1}()\n")
            append("fun f$depth() = 1\n")
        }
        val dir = tempProject(mapOf("Chain.kt" to chain))
        val service = KotlinSymbolService(sourceRoots = listOf(DiskFile(dir)), classpathJars = listOf(stdlibJarPath()))

        fun typeOfF1() = service.topLevelByName("f1").firstOrNull()?.type?.toString()

        // 400 nested inferences need far more than 256 KB, so this attempt exhausts the stack.
        var overflowed = false
        val shallow = Thread(null, {
            try { typeOfF1() } catch (e: StackOverflowError) { overflowed = true }
        }, "shallow-stack-inference", 256L * 1024)
        shallow.start()
        shallow.join(120_000)
        assertTrue(!shallow.isAlive, "the shallow-stack inference did not finish")
        assertTrue(overflowed, "the shallow attempt must surface the overflow, not swallow it")

        assertEquals("Int", typeOfF1(), "the same declaration types normally once there is stack for it")
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
