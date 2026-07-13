package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for a class of `by`-delegated properties whose delegates are generic calls taking a CALL-valued
 * argument (`var tiles by mutableStateOf(emptyList<Tile>())`, the reported 2048 `Game2048State` shape). Two
 * defects, both surfaced by this shape:
 *  1. STACK OVERFLOW while editing the buffer: resolving any call/bare name enumerates the file's declarations
 *     ([sameFileScopeSymbols]), eagerly typing each delegate via [delegatedValueType] → [inferType]; typing a
 *     delegate resolves its generic argument, whose expected-type lookup runs [callTargets] →
 *     [sameFileScopeSymbols] again, re-entering the SAME delegate expression. `inferType`'s cache is written
 *     only after the compute, so the same-expression re-entry recursed forever. Fixed by the
 *     [KotlinResolver.inferringTypes] guard (re-entry returns null; the outer call still computes).
 *  2. The delegated property stayed UNTYPED: the symbol service enumerates the class's members while typing the
 *     delegate (its expected-type detour), and pinned that partial list — in which the property under inference
 *     is re-entrant-null — in the source-member cache for the session. Fixed by not caching the member list
 *     while a body/delegate type is being inferred.
 *
 * `nil<T>()` is a SOURCE generic facade standing in for `emptyList<Tile>()` (a generic call with an explicit
 * type arg), so the test stays off the analyzer's library classpath while exercising the same detour.
 */
class KotlinDelegateReentrancyTest {
    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "State.kt", code) }.items.map { it.symbol?.name ?: it.label }

    // The delegates + the caret all live in ONE file, so completion runs the live-buffer [sameFileScopeSymbols]
    // path that overflowed.
    private fun stateFile(caretLine: String): String =
        "package demo\n" +
            "import kotlin.reflect.KProperty\n" +
            "class Box<T>(var value: T)\n" +
            "operator fun <T> Box<T>.getValue(t: Any?, p: KProperty<*>): T = value\n" +
            "operator fun <T> Box<T>.setValue(t: Any?, p: KProperty<*>, v: T) { value = v }\n" +
            "fun <T> boxOf(value: T): Box<T> = Box(value)\n" +
            "fun <T> nil(): List<T> = throw RuntimeException()\n" +
            "fun countOf(xs: List<Int>): Int = xs.size\n" +
            "class S {\n" +
            "    var items by boxOf(nil<Int>())\n" +
            "        private set\n" +
            "    var count by boxOf(countOf(nil<Int>()))\n" +
            "        private set\n" +
            "    fun use() { $caretLine }\n" +
            "}\n"

    @Test fun bareNameCompletionAmongDelegatesDoesNotOverflow() {
        // Completing a bare name inside the class enumerates its members — typing both delegates — and used to
        // overflow the stack. With the guard it terminates and the top-level `boxOf` still completes.
        val items = labels(stateFile("boxO|"))
        assertTrue("boxOf" in items, "bare-name completion should terminate + resolve scope; got ${items.take(30)}")
    }

    @Test fun callResolutionAmongDelegatesDoesNotOverflow() {
        // Resolving a call argument runs the same overload-target enumeration ([callTargets] →
        // [sameFileScopeSymbols]) the delegate typing loops through; `countOf(...)` still resolves its param.
        val items = labels(stateFile("countOf(nil<Int>()).plu|"))
        assertTrue("plus" in items, "call resolution should terminate; `countOf(...)` is Int, got ${items.take(30)}")
    }

    @Test fun memberOffGenericDelegateIsTyped() {
        // Member completion off the generic-delegate property itself (defect 2): `items` is `List<Int>`, so
        // `size` completes. Fails if the partial (re-entrant-null) member list got pinned in the cache.
        val items = labels(stateFile("items.siz|"))
        assertTrue("size" in items, "`items` should infer List<Int>; got ${items.take(30)}")
    }

    @Test fun memberOffMonomorphicDelegateIsTyped() {
        // The monomorphic delegate never triggered the detour, so it always typed; kept as a control.
        val items = labels(stateFile("count.plu|"))
        assertTrue("plus" in items, "`count` should infer Int; got ${items.take(30)}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
