package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Full-fidelity built-ins: `List`/`Int`/… now come from the real `.kotlin_builtins` declarations (via
 * [dev.ide.lang.kotlin.symbols.BuiltinsReader]), not the `java.util.List`/`java.lang.Integer` approximation.
 * So a read-only `List` has no mutators, `MutableList` does, and `Int.` exposes its companion's `MAX_VALUE`.
 */
class BuiltinsFidelityTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun readOnlyListHasRealMembersButNoMutators() {
        assertTrue("size" in labels("fun f() { listOf(\"\").siz| }"), "read-only List has size")
        assertTrue("get" in labels("fun f() { listOf(\"\").ge| }"), "read-only List has get")
        // The whole point: java.util.List's mutators are NOT part of Kotlin's read-only List.
        assertTrue("add" !in labels("fun f() { listOf(\"\").ad| }"), "read-only List must NOT have add")
        assertTrue("set" !in labels("fun f() { listOf(\"\").se| }"), "read-only List must NOT have set")
    }

    @Test
    fun mutableListHasMutators() {
        assertTrue("add" in labels("fun f() { mutableListOf(\"\").ad| }"), "MutableList has add")
    }

    @Test
    fun intCompanionShowsOnTypeAccess() {
        // `Int.` is type access → the companion's MAX_VALUE shows (it didn't with the java.lang.Integer hack).
        assertTrue("MAX_VALUE" in labels("fun f() { val x = Int.MAX| }"), "Int. should show companion MAX_VALUE")
    }

    @Test
    fun listTypeAccessIsEmpty() {
        // `List.` — a Kotlin interface with no companion → nothing (matches IntelliJ).
        assertTrue(labels("fun f() { List.| }").isEmpty(), "List. (no companion) should be empty; got ${labels("fun f() { List.| }").take(10)}")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
